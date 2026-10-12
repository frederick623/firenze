package com.piscessoft.sbl.service

import com.piscessoft.sbl.model.*
import com.piscessoft.sbl.repo.*
import com.piscessoft.sbl.util.Ids
import com.piscessoft.sbl.util.Money
import com.piscessoft.sbl.util.SblTime
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Module 3 - Contract Lifecycle Management.
 *
 * Owns the borrow-contract state machine: open (from an approved locate), recall,
 * partial return, rerate, rollover and close. Also runs the SFO-regulated-person
 * 5-business-day deployment timer that feeds the Cap.571X disclosure check.
 */
class ContractService(
    private val contracts: ContractRepo,
    private val events: LifecycleEventRepo,
    private val recalls: RecallRepo,
    private val rateHistory: ContractRateHistoryRepo,
    private val counterparties: CounterpartyRepo,
    private val assurances: DocumentaryAssuranceRepo,
    private val locateService: LocateService,
    private val locates: LocateRepo,
    private val inventory: InventoryService,
    private val audit: AuditService,
    /** Standard settlement cycle for recall returns (T+2). */
    private val recallSettlementDays: Int = 2,
    /** SFO deployment window for regulated persons (plan section 4.5). */
    private val regulatedPersonWindowDays: Int = 5,
    /** Business days after a missed recall deadline before a buy-in is triggered. */
    private val buyInGraceDays: Int = 3
) {

    /** Open a borrow contract by converting an APPROVED/PARTIAL locate (plan section 4.1). */
    fun openFromLocate(
        locateId: String,
        lenderId: String,
        transactionModel: TransactionModel = TransactionModel.PRINCIPAL,
        tradeType: TradeType = TradeType.FIXED,
        closeDate: LocalDate? = null,
        collateralRatio: BigDecimal? = null,
        agentId: String? = null,
        agentSplitPct: BigDecimal? = null
    ): SblContract {
        val locate = locates.find(locateId) ?: error("Locate $locateId not found")
        require(locate.status == LocateStatus.APPROVED || locate.status == LocateStatus.PARTIAL) {
            "Locate $locateId is ${locate.status}, cannot open a contract"
        }
        val qty = locate.approvedQty ?: error("Locate $locateId has no approved quantity")
        val borrower = counterparties.find(locate.borrowerId) ?: error("Unknown borrower")
        val isShort = true // locate flow assumes short-sale coverage unless a blanket says otherwise
        val ratio = collateralRatio ?: if (isShort) BigDecimal("1.05") else BigDecimal("1.00")
        val now = SblTime.now()
        val today = SblTime.today()

        val regulated = borrower.regulatedPerson
        val deadline = if (regulated) SblTime.addBusinessDays(today, regulatedPersonWindowDays) else null

        val contract = SblContract(
            contractId = Ids.newId(),
            status = ContractStatus.ACTIVE,
            tradeType = tradeType,
            transactionModel = transactionModel,
            lenderId = lenderId,
            borrowerId = locate.borrowerId,
            agentId = agentId,
            stockCode = locate.stockCode,
            quantity = qty,
            quantityReturned = 0,
            quantityOutstanding = qty,
            openDate = today,
            closeDate = closeDate,
            feeRate = Money.rate(locate.feeRate ?: BigDecimal.ZERO),
            feeType = locate.feeType ?: FeeType.FIXED,
            feeRateEffectiveDate = today,
            collateralRatioRequired = ratio,
            isShortSaleBorrow = isShort,
            sblaId = locate.sblaId,
            locateId = locate.locateId,
            assuranceId = locate.assuranceId,
            assuranceBasis = locate.assuranceBasis,
            agentSplitPct = agentSplitPct,
            regulatedPersonFlag = regulated,
            regulatedPersonDeadline = deadline,
            createdAt = now,
            updatedAt = now
        )
        contracts.insert(contract)
        recordRate(contract, today, "OPEN")

        // BORROW documentary assurance (SFO borrow record, 3-year retention).
        val borrowAssurance = DocumentaryAssurance(
            assuranceId = Ids.newId(),
            assuranceType = AssuranceType.BORROW,
            lenderId = lenderId,
            borrowerId = locate.borrowerId,
            stockCode = locate.stockCode,
            quantity = qty,
            assuranceBasis = locate.assuranceBasis,
            channel = "CONTRACT",
            createdTs = now,
            expiresAt = null,
            retentionTier = RetentionTier.THREE_YEAR,
            retentionExpiry = today.plusYears(RetentionTier.THREE_YEAR.years.toLong())
        )
        assurances.insert(borrowAssurance)

        locateService.markConverted(locateId, contract.contractId)
        event(contract, "OPEN", null, ContractStatus.ACTIVE, "qty=$qty rate=${contract.feeRate}")
        audit.log("CONTRACT", contract.contractId, "OPEN", lenderId, ActorType.SYSTEM,
            newValue = "${locate.stockCode} qty=$qty rate=${contract.feeRate}",
            regulatoryRef = if (regulated) "Cap.571X" else null,
            retentionTier = RetentionTier.THREE_YEAR)
        if (regulated) {
            audit.log("CONTRACT", contract.contractId, "REGULATED_PERSON_TIMER_START", "SYSTEM",
                newValue = "deploy by $deadline", regulatoryRef = "Cap.571X")
        }
        return contract
    }

    /** Serve a recall for [qty] with a T+[recallSettlementDays] return deadline (plan section 4.3). */
    fun recall(
        contractId: String, qty: Long, reason: RecallReason,
        deadlineOverride: LocalDate? = null
    ): RecallNotice {
        val c = load(contractId)
        require(c.status.isOpen()) { "Contract $contractId is ${c.status}, cannot recall" }
        require(qty in 1..c.quantityOutstanding) { "Invalid recall qty $qty (outstanding ${c.quantityOutstanding})" }
        val now = SblTime.now()
        val deadline = deadlineOverride ?: SblTime.addBusinessDays(SblTime.today(), recallSettlementDays)
        val notice = RecallNotice(
            recallId = Ids.newId(),
            contractId = contractId,
            recallQty = qty,
            recallReason = reason,
            noticeServedAt = now,
            returnDeadline = deadline,
            status = RecallStatus.PENDING,
            createdAt = now
        )
        recalls.insert(notice)
        transition(c, ContractStatus.RECALLED, "RECALL", "qty=$qty reason=$reason deadline=$deadline")
        audit.log("CONTRACT", contractId, "RECALL", "SYSTEM",
            newValue = "qty=$qty reason=$reason deadline=$deadline")
        return notice
    }

    /** Process a return of [qty] shares; closes or partially-returns the contract. */
    fun processReturn(contractId: String, qty: Long): SblContract {
        val c = load(contractId)
        require(c.status.isOpen()) { "Contract $contractId is ${c.status}, cannot return" }
        require(qty in 1..c.quantityOutstanding) { "Invalid return qty $qty (outstanding ${c.quantityOutstanding})" }
        val now = SblTime.now()

        c.quantityReturned += qty
        c.quantityOutstanding -= qty

        // Apply against any open recall for this contract.
        recalls.forContract(contractId)
            .filter { it.status == RecallStatus.PENDING || it.status == RecallStatus.PARTIALLY_RETURNED || it.status == RecallStatus.OVERDUE }
            .sortedBy { it.returnDeadline }
            .firstOrNull()?.let { rc ->
                rc.returnedQty += qty
                rc.status = if (rc.returnedQty >= rc.recallQty) RecallStatus.RETURNED else RecallStatus.PARTIALLY_RETURNED
                if (rc.status == RecallStatus.RETURNED) rc.returnedAt = now
                recalls.update(rc)
            }

        if (c.quantityOutstanding == 0L) {
            c.status = ContractStatus.CLOSED
            c.actualCloseDate = SblTime.today()
            persist(c, "CLOSE", "fully returned")
            audit.log("CONTRACT", contractId, "CLOSE", "SYSTEM", newValue = "fully returned")
        } else {
            c.status = ContractStatus.PARTIALLY_RETURNED
            persist(c, "PARTIAL_RETURN", "returned=$qty outstanding=${c.quantityOutstanding}")
            audit.log("CONTRACT", contractId, "PARTIAL_RETURN", "SYSTEM",
                newValue = "returned=$qty outstanding=${c.quantityOutstanding}")
        }
        return c
    }

    /** Full early close (borrower returns everything outstanding). */
    fun close(contractId: String): SblContract {
        val c = load(contractId)
        return if (c.quantityOutstanding > 0) processReturn(contractId, c.quantityOutstanding) else c
    }

    /** Change the fee rate effective from [effectiveDate] (plan section 4.4 rerate). */
    fun rerate(contractId: String, newRate: BigDecimal, effectiveDate: LocalDate = SblTime.today()): SblContract {
        val c = load(contractId)
        require(c.status.isOpen()) { "Cannot rerate ${c.status} contract" }
        val old = c.feeRate
        c.feeRate = Money.rate(newRate)
        c.feeRateEffectiveDate = effectiveDate
        // Close the outgoing rate period and open the new one so accruals dated before
        // the effective date keep pricing at the old rate.
        rateHistory.closeOpen(contractId, effectiveDate)
        recordRate(c, effectiveDate, "RERATE")
        persist(c, "RERATE", "rate $old -> ${c.feeRate} from $effectiveDate")
        audit.log("CONTRACT", contractId, "RERATE", "USER", ActorType.USER,
            oldValue = old.toPlainString(), newValue = c.feeRate.toPlainString())
        return c
    }

    /** Extend the contract close date (rollover). */
    fun rollover(contractId: String, newCloseDate: LocalDate): SblContract {
        val c = load(contractId)
        require(c.status.isOpen()) { "Cannot roll over ${c.status} contract" }
        val old = c.closeDate
        c.closeDate = newCloseDate
        persist(c, "ROLLOVER", "close $old -> $newCloseDate")
        audit.log("CONTRACT", contractId, "ROLLOVER", "USER", ActorType.USER,
            oldValue = old?.toString(), newValue = newCloseDate.toString())
        return c
    }

    /** Record that a regulated-person borrower deployed the borrowed securities in time. */
    fun markDeployed(contractId: String, purpose: String): SblContract {
        val c = load(contractId)
        c.regulatedPersonDeployed = true
        c.regulatedPersonPurpose = purpose
        persist(c, "DEPLOYED", purpose)
        audit.log("CONTRACT", contractId, "REGULATED_PERSON_DEPLOYED", "USER", ActorType.USER,
            newValue = purpose, regulatoryRef = "Cap.571X")
        return c
    }

    /**
     * Daily job (plan section 4.5): flag regulated-person contracts whose 5-business-day
     * deployment window has lapsed without deployment as potential non-exempt acquisitions
     * under Cap.571X. These feed the Reporting module's disclosure obligations.
     */
    fun checkRegulatedPersonDeadlines(): List<SblContract> {
        val today = SblTime.today()
        val flagged = mutableListOf<SblContract>()
        for (c in contracts.active()) {
            if (!c.regulatedPersonFlag) continue
            if (c.regulatedPersonDeployed == true || c.potentialNonExempt) continue
            val dl = c.regulatedPersonDeadline ?: continue
            if (today.isAfter(dl)) {
                c.potentialNonExempt = true
                c.nonExemptReason = "Not deployed within $regulatedPersonWindowDays business days - deemed acquisition (Cap.571X)"
                persist(c, "NON_EXEMPT", c.nonExemptReason!!)
                audit.log("CONTRACT", c.contractId, "DEEMED_ACQUISITION", "SYSTEM",
                    newValue = c.nonExemptReason, regulatoryRef = "Cap.571X Part XV")
                flagged += c
            }
        }
        return flagged
    }

    /** Daily job: mark recalls whose deadline has passed as OVERDUE (escalation candidates). */
    fun checkOverdueRecalls(): List<RecallNotice> {
        val today = SblTime.today()
        val overdue = mutableListOf<RecallNotice>()
        for (r in recalls.open()) {
            if (r.status == RecallStatus.OVERDUE) continue
            if (today.isAfter(r.returnDeadline) && r.returnedQty < r.recallQty) {
                r.status = RecallStatus.OVERDUE
                recalls.update(r)
                audit.log("CONTRACT", r.contractId, "RECALL_OVERDUE", "SYSTEM",
                    newValue = "recall ${r.recallId} past ${r.returnDeadline}")
                overdue += r
            }
        }
        return overdue
    }

    /**
     * Escalate failed returns to buy-in (plan section 4.3 / HK market practice).
     *
     * When a recall is still unsatisfied [buyInGraceDays] business days past its return
     * deadline, the lender's remedy is to buy the shares in the market at the borrower's
     * cost. The recall is marked DEFAULTED to hand the position to the buy-in desk; the
     * contract itself stays open until the resulting purchase settles.
     */
    fun escalateBuyIns(graceDays: Int = buyInGraceDays): List<RecallNotice> {
        val today = SblTime.today()
        val escalated = mutableListOf<RecallNotice>()
        for (r in recalls.open()) {
            if (r.status != RecallStatus.OVERDUE) continue
            if (r.returnedQty >= r.recallQty) continue
            val buyInDate = SblTime.addBusinessDays(r.returnDeadline, graceDays)
            if (today.isBefore(buyInDate)) continue

            val shortfall = r.recallQty - r.returnedQty
            r.status = RecallStatus.DEFAULTED
            recalls.update(r)
            audit.log("CONTRACT", r.contractId, "BUY_IN_TRIGGERED", "SYSTEM",
                oldValue = RecallStatus.OVERDUE.name, newValue = RecallStatus.DEFAULTED.name,
                regulatoryRef = "Buy-in: shortfall $shortfall past $buyInDate",
                retentionTier = RetentionTier.SEVEN_YEAR)
            contracts.find(r.contractId)?.let {
                event(it, "BUY_IN", it.status, it.status,
                    "recall ${r.recallId} shortfall=$shortfall buy-in from $buyInDate")
            }
            escalated += r
        }
        return escalated
    }

    /** Corporate-action quantity adjustment (splits/bonus/consolidation, plan section 7.2). */
    fun adjustQuantity(contractId: String, ratio: BigDecimal, detail: String): SblContract {
        val c = load(contractId)
        require(c.status.isOpen()) { "Cannot adjust ${c.status} contract" }
        fun scale(v: Long) = BigDecimal(v).multiply(ratio).setScale(0, java.math.RoundingMode.HALF_UP).toLong()
        val oldQty = c.quantity
        c.quantity = scale(c.quantity)
        c.quantityReturned = scale(c.quantityReturned)
        c.quantityOutstanding = c.quantity - c.quantityReturned
        c.status = ContractStatus.CA_ADJUSTED
        persist(c, "CA_ADJUSTMENT", "$detail qty $oldQty -> ${c.quantity}")
        audit.log("CONTRACT", contractId, "CA_ADJUSTMENT", "SYSTEM",
            oldValue = oldQty.toString(), newValue = c.quantity.toString(), regulatoryRef = "CA")
        return c
    }

    /** Terminate a contract early (e.g. delisting) without a standard return (plan section 7.4). */
    fun earlyTerminate(contractId: String, reason: String): SblContract {
        val c = load(contractId)
        require(c.status.isOpen()) { "Cannot terminate ${c.status} contract" }
        c.status = ContractStatus.CLOSED
        c.actualCloseDate = SblTime.today()
        persist(c, "EARLY_TERMINATION", reason)
        audit.log("CONTRACT", contractId, "EARLY_TERMINATION", "SYSTEM",
            newValue = reason, regulatoryRef = "CA")
        return c
    }

    // ---- helpers ----

    /** Open a new rate period for the contract, effective from [from]. */
    private fun recordRate(c: SblContract, from: LocalDate, reason: String) {
        rateHistory.insert(
            ContractRateHistory(
                rateHistoryId = Ids.newId(),
                contractId = c.contractId,
                feeType = c.feeType,
                annualRate = c.feeRate,
                floatingIndex = c.floatingIndex,
                floatingSpreadBps = c.floatingSpreadBps,
                effectiveFrom = from,
                effectiveTo = null,
                reason = reason,
                createdAt = SblTime.now()
            )
        )
    }

    private fun ContractStatus.isOpen(): Boolean = this in setOf(
        ContractStatus.ACTIVE, ContractStatus.RECALLED, ContractStatus.PARTIALLY_RETURNED,
        ContractStatus.RERATED, ContractStatus.ROLLED_OVER, ContractStatus.CA_ADJUSTED
    )

    private fun load(contractId: String): SblContract =
        contracts.find(contractId) ?: error("Contract $contractId not found")

    private fun transition(c: SblContract, to: ContractStatus, type: String, detail: String) {
        val from = c.status
        c.status = to
        persist(c, type, detail, from)
    }

    private fun persist(c: SblContract, type: String, detail: String, from: ContractStatus? = null) {
        val fromStatus = from ?: c.status
        if (!contracts.update(c)) {
            // Reload version and retry once (single-threaded demo path).
            val fresh = contracts.find(c.contractId) ?: error("Contract vanished")
            c.version = fresh.version
            check(contracts.update(c)) { "Optimistic lock failed twice for ${c.contractId}" }
        }
        event(c, type, fromStatus, c.status, detail)
    }

    private fun event(c: SblContract, type: String, from: ContractStatus?, to: ContractStatus?, detail: String) {
        events.insert(
            LifecycleEvent(
                eventId = Ids.newId(),
                contractId = c.contractId,
                eventType = type,
                fromStatus = from,
                toStatus = to,
                detail = detail,
                eventTs = SblTime.now()
            )
        )
    }
}
