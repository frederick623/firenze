package com.piscessoft.sbl.service

import com.piscessoft.sbl.model.*
import com.piscessoft.sbl.repo.*
import com.piscessoft.sbl.util.Ids
import com.piscessoft.sbl.util.Money
import com.piscessoft.sbl.util.SblTime
import java.math.BigDecimal
import java.time.LocalTime

/** Tunable regulatory / risk limits used by the locate workflow (plan section 2.1 step 4). */
data class LocateLimits(
    val shortInterestCapPct: BigDecimal = BigDecimal("0.10"),   // 10% of outstanding shares
    val concentrationCapPct: BigDecimal = BigDecimal("0.50"),   // per-borrower share of lendable pool
    val locateExpiryTime: LocalTime = LocalTime.of(16, 0)       // same trading day 16:00 HKT
)

data class RateResult(
    val feeRate: BigDecimal,
    val feeType: FeeType,
    val indexRate: BigDecimal? = null,
    val spreadBps: Int? = null
)

/**
 * Module 1 - Locate Management.
 *
 * Implements the six-step locate lifecycle: eligibility check, availability check,
 * rate determination, limit checks, decision & commitment, and documentary-assurance
 * record creation (SFO s.171).
 */
class LocateService(
    private val locates: LocateRepo,
    private val counterparties: CounterpartyRepo,
    private val sblas: SblaRepo,
    private val designated: DesignatedSecurityRepo,
    private val rateSchedules: RateScheduleRepo,
    private val market: MarketDataRepo,
    private val assurances: DocumentaryAssuranceRepo,
    private val contracts: ContractRepo,
    private val inventory: InventoryService,
    private val audit: AuditService,
    private val limits: LocateLimits = LocateLimits()
) {

    /** Full locate request workflow. Returns a decision with outcome and message. */
    fun requestLocate(
        borrowerId: String,
        stockCode: String,
        requestedQty: Long,
        requestingSystem: String = "TRADING_SYSTEM",
        requestRef: String? = null,
        isShortSale: Boolean = true
    ): LocateDecision {
        val now = SblTime.now()
        val security = designated.find(stockCode)
        val locate = LocateRequest(
            locateId = Ids.newId(),
            status = LocateStatus.REQUESTED,
            borrowerId = borrowerId,
            requestingSystem = requestingSystem,
            requestRef = requestRef,
            stockCode = stockCode,
            requestedQty = requestedQty,
            requestedAt = now,
            designatedSecurity = security?.eligible == true,
            createdAt = now
        )
        locates.insert(locate)
        audit.log("LOCATE", locate.locateId, "CREATED", requestingSystem, ActorType.EXTERNAL,
            newValue = "$stockCode qty=$requestedQty", regulatoryRef = "SFO s.171")

        // Step 1 - Eligibility
        val ineligible = eligibilityFailure(borrowerId, stockCode, security)
        if (ineligible != null) return reject(locate, ineligible)

        // Step 2 - Availability
        val available = inventory.netAvailableToLend(stockCode)
        if (available <= 0) return reject(locate, "NO_AVAILABILITY")

        // Step 3 - Rate determination
        val rate = resolveRate(stockCode, borrowerId)
            ?: return reject(locate, "NO_RATE_SCHEDULE")

        // Step 4 - Limit checks (cap the approvable quantity)
        val limitCap = limitCappedQty(borrowerId, stockCode, requestedQty, isShortSale)
        val approvable = minOf(requestedQty, available, limitCap)
        if (approvable <= 0) return reject(locate, "LIMIT_BREACH")

        // Step 5 - Decision & commitment
        val sbla = sblas.forBorrower(borrowerId).firstOrNull { it.registeredWithIrd && it.status == "ACTIVE" }
        locate.approvedQty = approvable
        locate.feeRate = rate.feeRate
        locate.feeType = rate.feeType
        locate.rateBasis = "ANNUAL"
        locate.approvedAt = now
        locate.expiresAt = SblTime.today().atTime(limits.locateExpiryTime).atZone(SblTime.HKT).toInstant()
        locate.sblaId = sbla?.id
        locate.assuranceBasis = AssuranceBasis.LEGAL_ENTITY
        locate.assuranceType = AssuranceType.HOLD_NOTICE

        val partial = approvable < requestedQty
        if (partial) {
            locate.status = LocateStatus.PARTIAL
            locate.rejectedQty = requestedQty - approvable
            locate.rejectReason = "PARTIAL_AVAILABILITY"
        } else {
            locate.status = LocateStatus.APPROVED
        }

        // Step 6 - Documentary assurance (HOLD_NOTICE, SFO s.171; 1-year retention)
        val assurance = DocumentaryAssurance(
            assuranceId = Ids.newId(),
            assuranceType = AssuranceType.HOLD_NOTICE,
            lenderId = null,
            borrowerId = borrowerId,
            stockCode = stockCode,
            quantity = approvable,
            assuranceBasis = AssuranceBasis.LEGAL_ENTITY,
            channel = requestingSystem,
            createdTs = now,
            expiresAt = locate.expiresAt,
            retentionTier = RetentionTier.ONE_YEAR,
            retentionExpiry = SblTime.today().plusYears(1)
        )
        assurances.insert(assurance)
        locate.assuranceId = assurance.assuranceId
        locates.update(locate)

        audit.log("LOCATE", locate.locateId, locate.status.name, requestingSystem, ActorType.SYSTEM,
            newValue = "approved=$approvable rate=${rate.feeRate}", regulatoryRef = "SFO s.171")
        audit.log("ASSURANCE", assurance.assuranceId, "HOLD_NOTICE_CREATED", "SYSTEM",
            regulatoryRef = "SFO s.171", retentionTier = RetentionTier.ONE_YEAR)

        val msg = if (partial)
            "PARTIAL: approved $approvable of $requestedQty (shortfall ${requestedQty - approvable})"
        else "APPROVED: $approvable @ ${rate.feeRate}"
        return LocateDecision(locate, locate.status, available, msg)
    }

    /** Mark a locate CONVERTED once a contract is created from it. */
    fun markConverted(locateId: String, contractId: String) {
        val l = locates.find(locateId) ?: return
        l.status = LocateStatus.CONVERTED
        l.convertedAt = SblTime.now()
        l.contractId = contractId
        locates.update(l)
        audit.log("LOCATE", locateId, "CONVERTED", "SYSTEM", newValue = "contract=$contractId")
    }

    fun cancel(locateId: String) {
        val l = locates.find(locateId) ?: return
        if (l.status in setOf(LocateStatus.APPROVED, LocateStatus.PARTIAL, LocateStatus.REQUESTED)) {
            l.status = LocateStatus.CANCELLED
            locates.update(l)
            audit.log("LOCATE", locateId, "CANCELLED", "USER", ActorType.USER)
        }
    }

    /** Expiry job (plan section 2.3): release commitments for locates past their expiry. */
    fun expireLocates(): Int {
        val now = SblTime.now()
        var expired = 0
        for (l in locates.openCommitments()) {
            val exp = l.expiresAt
            if (exp != null && !exp.isAfter(now)) {
                l.status = LocateStatus.EXPIRED
                locates.update(l)
                audit.log("LOCATE", l.locateId, "EXPIRED", "SYSTEM",
                    newValue = "released ${l.approvedQty} qty")
                expired++
            }
        }
        return expired
    }

    // ---- Rate determination (most specific rule wins) ----

    fun resolveRate(stockCode: String, borrowerId: String): RateResult? {
        val tier = counterparties.find(borrowerId)?.tier
        val today = SblTime.today()
        val applicable = rateSchedules.all().filter { r ->
            (r.stockCode == null || r.stockCode == stockCode) &&
                (r.borrowerTier == null || r.borrowerTier == tier) &&
                !today.isBefore(r.effectiveFrom) &&
                (r.effectiveTo == null || !today.isAfter(r.effectiveTo))
        }
        val best = applicable.maxWithOrNull(
            compareBy<RateSchedule>({ specificity(it) }, { -it.priority })
        ) ?: return null

        return when (best.feeType) {
            FeeType.FIXED -> RateResult(Money.rate(best.annualRate ?: BigDecimal.ZERO), FeeType.FIXED)
            FeeType.FLOATING -> {
                val index = best.floatingIndex ?: return null
                val indexRate = market.index(index, today) ?: return null
                val spread = best.floatingSpreadBps ?: 0
                var rate = indexRate.add(BigDecimal(spread).divide(BigDecimal(10000)))
                val floor = best.minRate
                if (floor != null && rate < floor) rate = floor
                RateResult(Money.rate(rate), FeeType.FLOATING, Money.rate(indexRate), spread)
            }
            FeeType.NEGOTIATED -> RateResult(Money.rate(best.annualRate ?: BigDecimal.ZERO), FeeType.NEGOTIATED)
        }
    }

    private fun specificity(r: RateSchedule): Int =
        (if (r.stockCode != null) 2 else 0) + (if (r.borrowerTier != null) 1 else 0)

    // ---- Eligibility & limits ----

    private fun eligibilityFailure(borrowerId: String, stockCode: String, security: DesignatedSecurity?): String? {
        if (security == null) return "NOT_DESIGNATED_SECURITY"
        if (!security.eligible) return "SECURITY_INELIGIBLE"
        if (security.caRestricted) return "CA_RESTRICTED"
        val borrower = counterparties.find(borrowerId) ?: return "UNKNOWN_BORROWER"
        if (!borrower.approvedBorrower) return "BORROWER_NOT_APPROVED"
        val hasSbla = sblas.forBorrower(borrowerId).any { it.registeredWithIrd && it.status == "ACTIVE" }
        if (!hasSbla) return "NO_REGISTERED_SBLA"
        return null
    }

    /**
     * Returns the maximum quantity approvable after credit, concentration and short-interest limits.
     *
     * Every check counts open locate commitments (approved but not yet drawn down) alongside
     * on-loan quantity. Without that, a borrower can stack several locates that each pass in
     * isolation but breach the limit once they are all converted into contracts.
     */
    private fun limitCappedQty(
        borrowerId: String, stockCode: String, requestedQty: Long, isShortSale: Boolean
    ): Long {
        val price = market.latestPrice(stockCode, SblTime.today()) ?: BigDecimal.ZERO
        var cap = requestedQty

        // Counterparty credit limit: headroom / price
        val borrower = counterparties.find(borrowerId)
        val creditLimit = borrower?.creditLimitHkd
        if (creditLimit != null && price.signum() > 0) {
            val onLoanExposure = contracts.booked()
                .filter { it.borrowerId == borrowerId }
                .fold(BigDecimal.ZERO) { acc, c ->
                    val p = market.latestPrice(c.stockCode, SblTime.today()) ?: BigDecimal.ZERO
                    acc.add(p.multiply(BigDecimal(c.quantityOutstanding)))
                }
            val committedExposure = locates.openCommitments()
                .filter { it.borrowerId == borrowerId }
                .fold(BigDecimal.ZERO) { acc, l ->
                    val p = market.latestPrice(l.stockCode, SblTime.today()) ?: BigDecimal.ZERO
                    acc.add(p.multiply(BigDecimal(l.approvedQty ?: 0L)))
                }
            val headroom = creditLimit.subtract(onLoanExposure).subtract(committedExposure).max(BigDecimal.ZERO)
            val headroomQty = headroom.divide(price, 0, java.math.RoundingMode.DOWN).toLong()
            cap = minOf(cap, headroomQty)
        }

        // Short-interest cap: total short-sale on-loan + committed <= X% of issued shares
        val security = designated.find(stockCode)
        if (isShortSale && security != null) {
            val existingShort = contracts.bookedForStock(stockCode)
                .filter { it.isShortSaleBorrow }
                .sumOf { it.quantityOutstanding }
            val committedShort = locates.committedQty(stockCode)
            val maxShort = BigDecimal(security.issuedShares)
                .multiply(limits.shortInterestCapPct).toLong()
            cap = minOf(cap, (maxShort - existingShort - committedShort).coerceAtLeast(0))
        }

        // Concentration cap: borrower's share of the stock's gross lendable pool
        val gross = inventory.grossLendable(stockCode)
        if (gross > 0) {
            val borrowerOnLoan = contracts.bookedForStock(stockCode)
                .filter { it.borrowerId == borrowerId }
                .sumOf { it.quantityOutstanding }
            val borrowerCommitted = locates.openCommitments()
                .filter { it.borrowerId == borrowerId && it.stockCode == stockCode }
                .sumOf { it.approvedQty ?: 0L }
            val maxForBorrower = BigDecimal(gross).multiply(limits.concentrationCapPct).toLong()
            cap = minOf(cap, (maxForBorrower - borrowerOnLoan - borrowerCommitted).coerceAtLeast(0))
        }

        return cap.coerceAtLeast(0)
    }

    private fun reject(locate: LocateRequest, reason: String): LocateDecision {
        locate.status = LocateStatus.REJECTED
        locate.rejectedQty = locate.requestedQty
        locate.rejectReason = reason
        locates.update(locate)
        audit.log("LOCATE", locate.locateId, "REJECTED", locate.requestingSystem, ActorType.SYSTEM,
            newValue = reason, regulatoryRef = "SFO s.171")
        return LocateDecision(locate, LocateStatus.REJECTED, 0, "REJECTED: $reason")
    }
}
