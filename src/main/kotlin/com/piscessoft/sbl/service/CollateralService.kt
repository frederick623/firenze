package com.piscessoft.sbl.service

import com.piscessoft.sbl.model.*
import com.piscessoft.sbl.repo.*
import com.piscessoft.sbl.util.Ids
import com.piscessoft.sbl.util.Money
import com.piscessoft.sbl.util.SblTime
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Module 4 - Collateral Management, kept as one collateral account per counterparty.
 *
 * - Positive side: collateral posted to the account plus the counterparty's settled client
 *   stock held at the firm (valued at the MARGIN_EQTY haircut and concentration limit).
 * - Negative side: every contract on loan to the counterparty, at borrowed value x the
 *   contract's required ratio (1.00 general / 1.05 short-sale floor).
 *
 * The account is netted: a surplus left by one contract covers another, and a margin call is
 * raised on the account (at most one open per counterparty) when eligible collateral falls
 * below the total requirement. Collateral is always valued by the system; a type with a
 * concentration limit counts only up to that share of the total requirement, a type eligible
 * for only one kind of borrow counts only against that kind, and assets with the same issuer
 * as any borrowed stock count for nothing (wrong-way risk).
 *
 * The account key is the contract's borrower. Street borrow-ins (internal desk as borrower)
 * therefore net across street lenders; a per-lender mirror ledger is not modelled yet.
 */
class CollateralService(
    private val collateral: CollateralPositionRepo,
    private val marginCalls: MarginCallRepo,
    private val accountHistory: MtmHistoryRepo,
    private val contractHistory: ContractMtmHistoryRepo,
    private val contracts: ContractRepo,
    private val configs: CollateralTypeConfigRepo,
    private val market: MarketDataRepo,
    private val audit: AuditService,
    private val securities: DesignatedSecurityRepo,
    private val positions: PositionRepo,
    private val counterparties: CounterpartyRepo,
    /** Surplus above the requirement (as a share of it) before the account is flagged for release. */
    private val excessReleaseBuffer: BigDecimal = BigDecimal("0.05"),
    private val marginCallCutoff: LocalTime = LocalTime.of(17, 0),
    private val pricing: PricingService = PricingService(market, securities, configs, contracts, positions, collateral, audit)
) {

    /**
     * Post collateral to a counterparty's account (plan section 5.1). The unit price is never
     * taken from the caller, so a borrower cannot overstate its own cover.
     */
    fun postCollateral(
        counterpartyId: String, collateralType: String, assetId: String, quantity: BigDecimal
    ): CollateralPosition {
        val pos = buildPosition(counterpartyId, collateralType, assetId, quantity)
        collateral.insert(pos)
        audit.log("COLLATERAL", counterpartyId, "POST", "SYSTEM",
            newValue = "${pos.positionId} $collateralType $assetId adj=${pos.adjustedValueHkd}")
        return pos
    }

    /** Value a counterparty's account without persisting. [include] adds a contract awaiting delivery. */
    fun valuateAccount(
        counterpartyId: String, date: LocalDate = SblTime.today(), include: SblContract? = null
    ): CollateralAccount {
        val book = deliveredBook(counterpartyId)
        val withCandidate = if (include == null || book.any { it.contractId == include.contractId }) book else book + include
        return valuate(counterpartyId, withCandidate, collateral.activeForCounterparty(counterpartyId), date)
    }

    /** One contract's requirement: the negative side it contributes to its borrower's account. */
    fun requirementFor(contract: SblContract, date: LocalDate = SblTime.today()): ContractRequirement {
        val qty = BigDecimal(contract.quantityOutstanding)
        val price = market.latestPrice(contract.stockCode, date)
        val fx = market.fxOrNull(currencyOf(contract.stockCode), date)
        if (price == null || fx == null) {
            return ContractRequirement(contract.contractId, contract.borrowerId, contract.stockCode,
                contract.quantityOutstanding, contract.isShortSaleBorrow, contract.collateralRatioRequired,
                price, fx, null, null, priceMissing = true)
        }
        val borrowed = Money.money(price.multiply(fx).multiply(qty))
        return ContractRequirement(contract.contractId, contract.borrowerId, contract.stockCode,
            contract.quantityOutstanding, contract.isShortSaleBorrow, contract.collateralRatioRequired,
            price, fx, borrowed, Money.money(borrowed.multiply(contract.collateralRatioRequired)),
            priceMissing = false)
    }

    /**
     * Daily MTM across every account with stock on loan or collateral held (plan section 5.2).
     * Contracts that cannot be valued (no price / FX) are audited and listed on the account;
     * the account's requirement is then a lower bound, so a call is still raised if even the
     * priced contracts are not covered.
     */
    fun runDailyMtm(date: LocalDate = SblTime.today()): List<CollateralAccount> {
        val books = contracts.active().filter { it.quantityOutstanding > 0 }.groupBy { it.borrowerId }
        val results = mutableListOf<CollateralAccount>()
        for (cp in (books.keys + collateral.counterpartiesWithActive()).toSortedSet()) {
            val posted = collateral.activeForCounterparty(cp)
            posted.forEach { pricing.revalueCollateral(it, date) }
            val account = valuate(cp, books[cp].orEmpty(), posted, date)
            results += account

            for (r in account.contracts) {
                if (r.priceMissing) {
                    audit.log("COLLATERAL", r.contractId, "MTM_NO_PRICE", "SYSTEM",
                        newValue = "No price/FX for ${r.stockCode} on $date - contract not valued, $cp account requirement understated")
                    continue
                }
                contractHistory.insert(ContractMtmHistory(Ids.newId(), r.contractId, date, r.closingPrice!!,
                    r.borrowedValueHkd!!, r.requiredCollateralHkd!!, SblTime.now()))
            }
            account.marginCall?.let { persistMarginCall(it) }
            if (!account.priceMissing) {
                accountHistory.insert(CollateralMtmHistory(
                    historyId = Ids.newId(),
                    counterpartyId = cp,
                    mtmDate = date,
                    borrowedValueHkd = account.borrowedValueHkd,
                    requiredCollateralHkd = account.requiredCollateralHkd,
                    actualCollateralHkd = account.eligibleCollateralHkd,
                    coverageRatio = account.coverageRatio,
                    marginCallTriggered = account.marginCall != null,
                    createdAt = SblTime.now()
                ))
            }
        }
        return results
    }

    /** Daily job: PENDING margin calls whose deadline has passed are ESCALATED. */
    fun escalateOverdueMarginCalls(now: Instant = SblTime.now()): List<MarginCall> {
        val escalated = mutableListOf<MarginCall>()
        for (call in marginCalls.pending()) {
            if (call.status != MarginCallStatus.PENDING || !call.deadline.isBefore(now)) continue
            call.status = MarginCallStatus.ESCALATED
            marginCalls.update(call)
            audit.log("COLLATERAL", call.counterpartyId, "MARGIN_CALL_ESCALATED", "SYSTEM",
                oldValue = MarginCallStatus.PENDING.name,
                newValue = "call=${call.callId} deficit=${call.deficitHkd} deadline=${call.deadline}")
            escalated += call
        }
        return escalated
    }

    fun resolveMarginCall(callId: String, method: ResolutionMethod): MarginCall {
        val call = marginCalls.find(callId) ?: error("Margin call $callId not found")
        check(call.status == MarginCallStatus.PENDING || call.status == MarginCallStatus.ESCALATED) {
            "Margin call $callId is ${call.status}"
        }
        call.status = MarginCallStatus.RESOLVED
        call.resolvedAt = SblTime.now()
        call.resolutionMethod = method
        marginCalls.update(call)
        audit.log("COLLATERAL", call.counterpartyId, "MARGIN_CALL_RESOLVED", "USER", ActorType.USER,
            newValue = "call=$callId method=$method")
        return call
    }

    /** Atomically substitute one collateral position for another (plan section 5.3). */
    fun substitute(
        oldPositionId: String, newType: String, newAssetId: String, newQuantity: BigDecimal
    ): CollateralPosition {
        val old = collateral.find(oldPositionId) ?: error("Collateral $oldPositionId not found")
        require(old.status == CollateralStatus.ACTIVE) { "Collateral $oldPositionId is ${old.status}" }

        // Value the replacement first and only write once it has passed the value test,
        // otherwise a rejected substitution would leave an orphan ACTIVE position behind.
        val replacement = buildPosition(old.counterpartyId, newType, newAssetId, newQuantity)
        require(replacement.adjustedValueHkd >= old.adjustedValueHkd) {
            "Substitute value ${replacement.adjustedValueHkd} < original ${old.adjustedValueHkd}"
        }
        requireCoverAfterSubstitution(old, replacement)

        collateral.insert(replacement)
        old.status = CollateralStatus.SUBSTITUTED
        old.updatedAt = SblTime.now()
        collateral.update(old)
        audit.log("COLLATERAL", old.counterpartyId, "SUBSTITUTION", "USER", ActorType.USER,
            oldValue = old.positionId,
            newValue = "${replacement.positionId} $newType $newAssetId adj=${replacement.adjustedValueHkd}")
        return replacement
    }

    /**
     * Release a collateral position back to the counterparty (plan section 5.4).
     *
     * Refused if the account would no longer cover every booked contract (including those
     * awaiting delivery) - account surplus may be returned, pledged margin may not. [force]
     * exists for operations overrides and is audited distinctly.
     */
    fun releaseCollateral(positionId: String, force: Boolean = false): CollateralPosition {
        val p = collateral.find(positionId) ?: error("Collateral $positionId not found")
        require(p.status == CollateralStatus.ACTIVE) { "Collateral $positionId is ${p.status}" }

        if (!force) {
            val book = bookedBook(p.counterpartyId)
            if (book.isNotEmpty()) {
                val remaining = valuate(p.counterpartyId, book,
                    collateral.activeForCounterparty(p.counterpartyId).filter { it.positionId != p.positionId },
                    SblTime.today())
                if (remaining.priceMissing) throw IllegalStateException(
                    "No price/FX for ${remaining.unpricedContracts.joinToString()} - cannot verify account cover")
                require(remaining.eligibleCollateralHkd >= remaining.requiredCollateralHkd) {
                    "Release would breach account cover: remaining ${remaining.eligibleCollateralHkd} < required ${remaining.requiredCollateralHkd}"
                }
            }
        }

        p.status = CollateralStatus.RELEASED
        p.updatedAt = SblTime.now()
        collateral.update(p)
        audit.log("COLLATERAL", p.counterpartyId, if (force) "RELEASE_FORCED" else "RELEASE", "SYSTEM",
            newValue = "released ${p.positionId} adj=${p.adjustedValueHkd}")
        return p
    }

    // ---- account valuation ----

    /** Contracts whose stock has been delivered to [counterpartyId] and is still out. */
    private fun deliveredBook(counterpartyId: String): List<SblContract> =
        contracts.active().filter { it.borrowerId == counterpartyId && it.quantityOutstanding > 0 }

    /** Delivered contracts plus contracts booked and awaiting delivery. */
    private fun bookedBook(counterpartyId: String): List<SblContract> =
        contracts.booked().filter { it.borrowerId == counterpartyId && it.quantityOutstanding > 0 }

    private fun valuate(
        counterpartyId: String, book: List<SblContract>, posted: List<CollateralPosition>, date: LocalDate
    ): CollateralAccount {
        val reqs = book.map { requirementFor(it, date) }
        val priced = reqs.filter { !it.priceMissing }
        val borrowed = Money.money(priced.fold(BigDecimal.ZERO) { a, r -> a.add(r.borrowedValueHkd!!) })
        val required = Money.money(priced.fold(BigDecimal.ZERO) { a, r -> a.add(r.requiredCollateralHkd!!) })
        val requiredShort = Money.money(priced.filter { it.shortSale }
            .fold(BigDecimal.ZERO) { a, r -> a.add(r.requiredCollateralHkd!!) })
        val requiredGeneral = required.subtract(requiredShort)

        val lines = posted.map { postedLine(it, date, book) } + clientStockLines(counterpartyId, date, book)
        val byType = lines.filter { it.excludedReason == null }.groupBy { it.collateralType }.map { (type, group) ->
            val value = Money.money(group.fold(BigDecimal.ZERO) { a, l -> a.add(l.adjustedValueHkd) })
            val limit = typeLimit(configs.find(type), required, requiredShort, requiredGeneral)
            CollateralTypeCover(type, value, limit, if (limit != null && value > limit) limit else value)
        }
        val gross = Money.money(byType.fold(BigDecimal.ZERO) { a, t -> a.add(t.adjustedValueHkd) })
        val eligible = Money.money(byType.fold(BigDecimal.ZERO) { a, t -> a.add(t.eligibleValueHkd) })
        val coverage = if (borrowed.signum() == 0) BigDecimal.ZERO else Money.ratio(eligible, borrowed)
        val unpriced = reqs.filter { it.priceMissing }.map { it.contractId }
        val marginCall = if (eligible < required)
            buildMarginCall(counterpartyId, borrowed, required, eligible, coverage, date) else null
        val releaseEligible = unpriced.isEmpty() &&
            eligible.subtract(required) > Money.money(required.multiply(excessReleaseBuffer))
        return CollateralAccount(
            counterpartyId = counterpartyId,
            valuationDate = date,
            collateral = lines,
            byType = byType,
            contracts = reqs,
            grossCollateralHkd = gross,
            eligibleCollateralHkd = eligible,
            borrowedValueHkd = borrowed,
            requiredCollateralHkd = required,
            netHkd = Money.money(eligible.subtract(required)),
            coverageRatio = Money.rate(coverage),
            unpricedContracts = unpriced,
            priceMissing = unpriced.isNotEmpty(),
            marginCall = marginCall,
            excessReleaseEligible = releaseEligible
        )
    }

    /**
     * How much of one collateral type counts towards cover: a type eligible for only one kind
     * of borrow counts at most against that kind's requirement, and a type with a concentration
     * limit at most `maxConcentration x total required`. Null when uncapped.
     */
    private fun typeLimit(
        cfg: CollateralTypeConfig?, required: BigDecimal, requiredShort: BigDecimal, requiredGeneral: BigDecimal
    ): BigDecimal? {
        if (cfg == null) return null
        var limit: BigDecimal? = null
        if (!(cfg.eligibleShortSale && cfg.eligibleGeneral)) {
            limit = (if (cfg.eligibleShortSale) requiredShort else BigDecimal.ZERO)
                .add(if (cfg.eligibleGeneral) requiredGeneral else BigDecimal.ZERO)
        }
        cfg.maxConcentration?.let {
            val cap = required.multiply(it)
            limit = limit?.min(cap) ?: cap
        }
        return limit?.let { Money.money(it) }
    }

    private fun postedLine(p: CollateralPosition, date: LocalDate, book: List<SblContract>): CollateralLine {
        val v = pricing.valueCollateral(p, date)
        val nominal = configs.find(p.collateralType)?.nominalValued ?: false
        return CollateralLine(CollateralSource.POSTED, p.positionId, p.collateralType, p.assetId, p.quantity,
            v.marketValueHkd, p.haircutPct, v.adjustedValueHkd,
            excludedReason = if (nominal) null else wrongWayConflict(p.assetId, book))
    }

    /** Settled client holdings of an external counterparty, valued as margin-eligible equities. */
    private fun clientStockLines(counterpartyId: String, date: LocalDate, book: List<SblContract>): List<CollateralLine> {
        val party = counterparties.find(counterpartyId) ?: return emptyList()
        if (party.type == CounterpartyType.INTERNAL) return emptyList()
        val cfg = configs.find(CLIENT_STOCK_TYPE) ?: return emptyList()
        return positions.forOwner(counterpartyId)
            .filter { it.accountType in CLIENT_ACCOUNTS && it.settlementStatus == SettlementStatus.SETTLED && it.quantity > 0 }
            .map { p ->
                val qty = BigDecimal(p.quantity)
                val price = market.latestPrice(p.stockCode, date)
                val fx = market.fxOrNull(currencyOf(p.stockCode), date)
                if (price == null || fx == null) {
                    CollateralLine(CollateralSource.CLIENT_STOCK, p.positionId, cfg.collateralType, p.stockCode, qty,
                        BigDecimal.ZERO, cfg.haircutPct, BigDecimal.ZERO, "No price/FX for ${p.stockCode} on $date")
                } else {
                    val hkd = Money.money(qty.multiply(price).multiply(fx))
                    CollateralLine(CollateralSource.CLIENT_STOCK, p.positionId, cfg.collateralType, p.stockCode, qty,
                        hkd, cfg.haircutPct, Money.money(hkd.multiply(BigDecimal.ONE.subtract(cfg.haircutPct))),
                        wrongWayConflict(p.stockCode, book))
                }
            }
    }

    // ---- posting ----

    /**
     * Value a candidate collateral position without persisting it. Used to validate a
     * substitution before anything is written, so a rejected substitution cannot leave
     * an orphan ACTIVE row behind.
     */
    private fun buildPosition(
        counterpartyId: String, collateralType: String, assetId: String, quantity: BigDecimal
    ): CollateralPosition {
        val cfg = configs.find(collateralType) ?: error("Unknown collateral type $collateralType")
        requireNotNull(counterparties.find(counterpartyId)) { "Counterparty $counterpartyId not found" }
        require(cfg.eligibleGeneral || cfg.eligibleShortSale) {
            "$collateralType is not eligible collateral for any borrow"
        }
        if (!cfg.nominalValued) {
            wrongWayConflict(assetId, bookedBook(counterpartyId))?.let { throw IllegalArgumentException(it) }
        }
        val date = SblTime.today()
        val unit = pricing.collateralUnitPrice(cfg, assetId, date)
            ?: throw IllegalArgumentException("No market price for $assetId on $date - cannot value $collateralType collateral")
        val fx = market.fxOrNull(cfg.currency, date)
            ?: throw IllegalArgumentException("No ${cfg.currency}/HKD rate on $date - cannot value $collateralType collateral")
        val local = Money.money(quantity.multiply(unit))
        val hkd = Money.money(local.multiply(fx))
        val haircut = cfg.haircutPct
        val adjusted = Money.money(hkd.multiply(BigDecimal.ONE.subtract(haircut)))
        val now = SblTime.now()
        return CollateralPosition(
            positionId = Ids.newId(),
            counterpartyId = counterpartyId,
            collateralType = collateralType,
            assetId = assetId,
            quantity = quantity,
            marketValueLocal = local,
            fxRateToHkd = fx,
            marketValueHkd = hkd,
            haircutPct = haircut,
            adjustedValueHkd = adjusted,
            lastMtmDate = date,
            status = CollateralStatus.ACTIVE,
            createdAt = now,
            updatedAt = now
        )
    }

    /**
     * Wrong-way risk control: a security the counterparty borrows (or another line of the
     * same issuer) cannot back its own account. Its value collapses in exactly the scenario
     * where the collateral would need to be realised. Returns the reason, or null if clean.
     */
    private fun wrongWayConflict(assetId: String, book: List<SblContract>): String? {
        val issuer = issuerOf(assetId)
        for (k in book) {
            if (assetId.equals(k.stockCode, ignoreCase = true))
                return "Wrong-way risk: ${k.stockCode} is borrowed by ${k.borrowerId} and cannot back its own account"
            if (issuerOf(k.stockCode).equals(issuer, ignoreCase = true))
                return "Wrong-way risk: $assetId has the same issuer ($issuer) as borrowed security ${k.stockCode}"
        }
        return null
    }

    private fun issuerOf(code: String): String = securities.find(code)?.issuerId ?: code

    private fun currencyOf(stockCode: String): String = pricing.currencyOf(stockCode)

    private fun requireCoverAfterSubstitution(old: CollateralPosition, replacement: CollateralPosition) {
        val book = bookedBook(old.counterpartyId)
        if (book.isEmpty()) return
        val date = SblTime.today()
        val current = collateral.activeForCounterparty(old.counterpartyId)
        val before = valuate(old.counterpartyId, book, current, date)
        if (before.priceMissing) throw IllegalStateException(
            "No price/FX for ${before.unpricedContracts.joinToString()} on $date - cannot verify account cover")
        val after = valuate(old.counterpartyId, book,
            current.filter { it.positionId != old.positionId } + replacement, date)
        require(after.eligibleCollateralHkd >= before.eligibleCollateralHkd ||
                after.eligibleCollateralHkd >= after.requiredCollateralHkd) {
            "Substitution would reduce eligible cover to ${after.eligibleCollateralHkd} " +
                "(required ${after.requiredCollateralHkd}) after concentration limits"
        }
    }

    // ---- internals ----

    private fun buildMarginCall(
        counterpartyId: String, borrowed: BigDecimal, required: BigDecimal,
        eligible: BigDecimal, coverage: BigDecimal, date: LocalDate
    ): MarginCall = MarginCall(
        callId = Ids.newId(),
        counterpartyId = counterpartyId,
        callDate = date,
        borrowedValueHkd = borrowed,
        requiredCollateralHkd = required,
        currentCollateralHkd = eligible,
        deficitHkd = Money.money(required.subtract(eligible)),
        coverageRatio = Money.rate(coverage),
        deadline = callDeadline(date),
        status = MarginCallStatus.PENDING,
        createdAt = SblTime.now()
    )

    /**
     * A call raised before the daily cutoff is due the same day; one raised at or after
     * the cutoff (or on a non-business day) rolls to the next business day, so a call is
     * never issued with a deadline that has already passed.
     */
    private fun callDeadline(date: LocalDate): Instant {
        val sameDay = date.atTime(marginCallCutoff).atZone(SblTime.HKT).toInstant()
        val dueDate = if (SblTime.isBusinessDay(date) && SblTime.now().isBefore(sameDay)) date
        else SblTime.addBusinessDays(date, 1)
        return dueDate.atTime(marginCallCutoff).atZone(SblTime.HKT).toInstant()
    }

    /**
     * One open call per counterparty. If a call is already outstanding and the account has
     * deteriorated further, the existing call is restated to the current deficit rather
     * than silently leaving the counterparty with a stale, understated obligation.
     */
    private fun persistMarginCall(call: MarginCall) {
        val existing = marginCalls.pendingForCounterparty(call.counterpartyId)
        if (existing == null) {
            marginCalls.insert(call)
            audit.log("COLLATERAL", call.counterpartyId, "MARGIN_CALL", "SYSTEM",
                newValue = "call=${call.callId} deficit=${call.deficitHkd} coverage=${call.coverageRatio}")
            return
        }
        if (call.deficitHkd > existing.deficitHkd) {
            val oldDeficit = existing.deficitHkd
            existing.borrowedValueHkd = call.borrowedValueHkd
            existing.requiredCollateralHkd = call.requiredCollateralHkd
            existing.currentCollateralHkd = call.currentCollateralHkd
            existing.deficitHkd = call.deficitHkd
            existing.coverageRatio = call.coverageRatio
            marginCalls.update(existing)
            audit.log("COLLATERAL", call.counterpartyId, "MARGIN_CALL_INCREASED", "SYSTEM",
                oldValue = oldDeficit.toPlainString(), newValue = call.deficitHkd.toPlainString())
        }
    }

    private companion object {
        /** Client stock is valued as HKEX margin-eligible equity collateral. */
        const val CLIENT_STOCK_TYPE = "MARGIN_EQTY"
        val CLIENT_ACCOUNTS = setOf(AccountType.CLIENT_DISCRETIONARY, AccountType.CLIENT_NON_DISC)
    }
}
