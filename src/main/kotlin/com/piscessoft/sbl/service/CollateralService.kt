package com.piscessoft.sbl.service

import com.piscessoft.sbl.model.*
import com.piscessoft.sbl.repo.*
import com.piscessoft.sbl.util.Ids
import com.piscessoft.sbl.util.Money
import com.piscessoft.sbl.util.SblTime
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/**
 * Module 4 - Collateral Management.
 *
 * Daily mark-to-market of borrowed value and posted collateral, margin-call generation
 * when coverage falls below the required ratio (1.00 general / 1.05 short-sale), collateral
 * substitution, and excess-collateral release (plan section 5).
 */
class CollateralService(
    private val collateral: CollateralPositionRepo,
    private val marginCalls: MarginCallRepo,
    private val mtmHistory: MtmHistoryRepo,
    private val contracts: ContractRepo,
    private val configs: CollateralTypeConfigRepo,
    private val market: MarketDataRepo,
    private val audit: AuditService,
    /** Coverage headroom above the required ratio before collateral can be released. */
    private val excessReleaseBuffer: BigDecimal = BigDecimal("0.05"),
    private val marginCallCutoff: LocalTime = LocalTime.of(17, 0)
) {

    /** Post collateral against a contract (plan section 5.1). */
    fun postCollateral(
        contractId: String, collateralType: String, assetId: String,
        quantity: BigDecimal, unitPriceLocal: BigDecimal
    ): CollateralPosition {
        val pos = buildPosition(contractId, collateralType, assetId, quantity, unitPriceLocal)
        collateral.insert(pos)
        audit.log("COLLATERAL", pos.positionId, "POST", "SYSTEM",
            newValue = "$collateralType $assetId adj=${pos.adjustedValueHkd}")
        return pos
    }

    /**
     * Value a candidate collateral position without persisting it. Used to validate a
     * substitution before anything is written, so a rejected substitution cannot leave
     * an orphan ACTIVE row behind.
     */
    private fun buildPosition(
        contractId: String, collateralType: String, assetId: String,
        quantity: BigDecimal, unitPriceLocal: BigDecimal
    ): CollateralPosition {
        val cfg = configs.find(collateralType) ?: error("Unknown collateral type $collateralType")
        rejectWrongWayRisk(contractId, assetId)
        val date = SblTime.today()
        val local = Money.money(quantity.multiply(unitPriceLocal))
        val fx = market.fx(cfg.currency, date)
        val hkd = Money.money(local.multiply(fx))
        val haircut = cfg.haircutPct
        val adjusted = Money.money(hkd.multiply(BigDecimal.ONE.subtract(haircut)))
        val now = SblTime.now()
        return CollateralPosition(
            positionId = Ids.newId(),
            contractId = contractId,
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
     * Wrong-way risk control: the security being borrowed (or another line of the same
     * issuer) must not be pledged back as collateral for that same borrow. Its value
     * collapses in exactly the scenario where the collateral would need to be realised.
     */
    private fun rejectWrongWayRisk(contractId: String, assetId: String) {
        val c = contracts.find(contractId) ?: return
        require(!assetId.equals(c.stockCode, ignoreCase = true)) {
            "Wrong-way risk: cannot post the borrowed security ${c.stockCode} as collateral for its own borrow"
        }
    }

    /** Value a single contract's collateral coverage without persisting (dashboards). */
    fun valuate(contract: SblContract, date: LocalDate = SblTime.today()): MtmResult {
        val price = market.latestPrice(contract.stockCode, date) ?: BigDecimal.ZERO
        val borrowed = Money.money(price.multiply(BigDecimal(contract.quantityOutstanding)))
        val requiredRatio = contract.collateralRatioRequired
        val required = Money.money(borrowed.multiply(requiredRatio))

        val positions = collateral.activeForContract(contract.contractId)
        val adjusted = positions.fold(BigDecimal.ZERO) { acc, p -> acc.add(revalue(p, date)) }
        val coverage = if (borrowed.signum() == 0) BigDecimal.ZERO else Money.ratio(adjusted, borrowed)
        val surplusDeficit = Money.money(adjusted.subtract(required))
        val marginCall = if (coverage < requiredRatio && borrowed.signum() > 0)
            buildMarginCall(contract, borrowed, required, adjusted, coverage, date) else null
        val releaseEligible = coverage > requiredRatio.add(excessReleaseBuffer)
        return MtmResult(contract.contractId, borrowed, required, adjusted, surplusDeficit,
            Money.rate(coverage), marginCall, releaseEligible)
    }

    /** Daily MTM cycle across all open contracts (plan section 5.2). */
    fun runDailyMtm(date: LocalDate = SblTime.today()): List<MtmResult> {
        val results = mutableListOf<MtmResult>()
        for (c in contracts.active()) {
            if (c.quantityOutstanding <= 0) continue
            val price = market.latestPrice(c.stockCode, date) ?: continue
            // Persist revalued collateral positions.
            for (p in collateral.activeForContract(c.contractId)) {
                revalueAndPersist(p, date)
            }
            val result = valuate(c, date)
            val triggered = result.marginCall != null
            if (triggered) persistMarginCall(c, result.marginCall!!)

            mtmHistory.insert(
                CollateralMtmHistory(
                    historyId = Ids.newId(),
                    contractId = c.contractId,
                    mtmDate = date,
                    borrowedValueHkd = result.borrowedValueHkd,
                    requiredCollateralHkd = result.requiredCollateralHkd,
                    actualCollateralHkd = result.currentCollateralHkd,
                    coverageRatio = result.coverageRatio,
                    marginCallTriggered = triggered,
                    closingPrice = price,
                    createdAt = SblTime.now()
                )
            )
            results += result
        }
        return results
    }

    fun resolveMarginCall(callId: String, method: ResolutionMethod): MarginCall {
        val call = marginCalls.find(callId) ?: error("Margin call $callId not found")
        call.status = MarginCallStatus.RESOLVED
        call.resolvedAt = SblTime.now()
        call.resolutionMethod = method
        marginCalls.update(call)
        audit.log("COLLATERAL", call.contractId, "MARGIN_CALL_RESOLVED", "USER", ActorType.USER,
            newValue = "call=$callId method=$method")
        return call
    }

    /** Atomically substitute one collateral position for another (plan section 5.3). */
    fun substitute(
        oldPositionId: String, newType: String, newAssetId: String,
        newQuantity: BigDecimal, newUnitPriceLocal: BigDecimal
    ): CollateralPosition {
        val old = collateral.find(oldPositionId) ?: error("Collateral $oldPositionId not found")
        require(old.status == CollateralStatus.ACTIVE) { "Collateral $oldPositionId is ${old.status}" }

        // Value the replacement first and only write once it has passed the value test,
        // otherwise a rejected substitution would leave an orphan ACTIVE position behind.
        val replacement = buildPosition(old.contractId, newType, newAssetId, newQuantity, newUnitPriceLocal)
        require(replacement.adjustedValueHkd >= old.adjustedValueHkd) {
            "Substitute value ${replacement.adjustedValueHkd} < original ${old.adjustedValueHkd}"
        }

        collateral.insert(replacement)
        audit.log("COLLATERAL", replacement.positionId, "POST", "SYSTEM",
            newValue = "$newType $newAssetId adj=${replacement.adjustedValueHkd}")
        old.status = CollateralStatus.SUBSTITUTED
        old.updatedAt = SblTime.now()
        collateral.update(old)
        audit.log("COLLATERAL", old.contractId, "SUBSTITUTION", "USER", ActorType.USER,
            oldValue = old.positionId, newValue = replacement.positionId)
        return replacement
    }

    /**
     * Release an excess collateral position back to the borrower (plan section 5.4).
     *
     * The release is refused if it would drop the contract below its required coverage
     * ratio - excess collateral may be returned, pledged margin may not. [force] exists
     * for operations overrides (e.g. releasing against a closed contract) and is audited
     * distinctly.
     */
    fun releaseCollateral(positionId: String, force: Boolean = false): CollateralPosition {
        val p = collateral.find(positionId) ?: error("Collateral $positionId not found")
        require(p.status == CollateralStatus.ACTIVE) { "Collateral $positionId is ${p.status}" }

        if (!force) {
            val contract = contracts.find(p.contractId)
            if (contract != null && contract.quantityOutstanding > 0) {
                val date = SblTime.today()
                val mtm = valuate(contract, date)
                val remaining = Money.money(mtm.currentCollateralHkd.subtract(revalue(p, date)))
                val required = mtm.requiredCollateralHkd
                require(remaining >= required) {
                    "Release would breach coverage: remaining $remaining < required $required"
                }
            }
        }

        p.status = CollateralStatus.RELEASED
        p.updatedAt = SblTime.now()
        collateral.update(p)
        audit.log("COLLATERAL", p.contractId, if (force) "RELEASE_FORCED" else "RELEASE", "SYSTEM",
            newValue = "released ${p.positionId} adj=${p.adjustedValueHkd}")
        return p
    }

    // ---- internals ----

    private fun revalue(p: CollateralPosition, date: LocalDate): BigDecimal {
        val unit = market.latestPrice(p.assetId, date)
            ?: if (p.quantity.signum() == 0) BigDecimal.ZERO else Money.ratio(p.marketValueLocal, p.quantity)
        val cfg = configs.find(p.collateralType)
        val fx = cfg?.let { market.fx(it.currency, date) } ?: p.fxRateToHkd
        val local = Money.money(p.quantity.multiply(unit))
        val hkd = Money.money(local.multiply(fx))
        return Money.money(hkd.multiply(BigDecimal.ONE.subtract(p.haircutPct)))
    }

    private fun revalueAndPersist(p: CollateralPosition, date: LocalDate) {
        val unit = market.latestPrice(p.assetId, date)
            ?: if (p.quantity.signum() == 0) BigDecimal.ZERO else Money.ratio(p.marketValueLocal, p.quantity)
        val cfg = configs.find(p.collateralType)
        val fx = cfg?.let { market.fx(it.currency, date) } ?: p.fxRateToHkd
        p.marketValueLocal = Money.money(p.quantity.multiply(unit))
        p.fxRateToHkd = fx
        p.marketValueHkd = Money.money(p.marketValueLocal.multiply(fx))
        p.adjustedValueHkd = Money.money(p.marketValueHkd.multiply(BigDecimal.ONE.subtract(p.haircutPct)))
        p.lastMtmDate = date
        p.updatedAt = SblTime.now()
        collateral.update(p)
    }

    private fun buildMarginCall(
        contract: SblContract, borrowed: BigDecimal, required: BigDecimal,
        adjusted: BigDecimal, coverage: BigDecimal, date: LocalDate
    ): MarginCall = MarginCall(
        callId = Ids.newId(),
        contractId = contract.contractId,
        callDate = date,
        borrowedValueHkd = borrowed,
        requiredCollateralHkd = required,
        currentCollateralHkd = adjusted,
        deficitHkd = Money.money(required.subtract(adjusted)),
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
    private fun callDeadline(date: LocalDate): java.time.Instant {
        val sameDay = date.atTime(marginCallCutoff).atZone(SblTime.HKT).toInstant()
        val dueDate = if (SblTime.isBusinessDay(date) && SblTime.now().isBefore(sameDay)) date
        else SblTime.addBusinessDays(date, 1)
        return dueDate.atTime(marginCallCutoff).atZone(SblTime.HKT).toInstant()
    }

    /**
     * One open call per contract. If a call is already outstanding and the position has
     * deteriorated further, the existing call is restated to the current deficit rather
     * than silently leaving the borrower with a stale, understated obligation.
     */
    private fun persistMarginCall(contract: SblContract, call: MarginCall) {
        val existing = marginCalls.pendingForContract(contract.contractId)
        if (existing == null) {
            marginCalls.insert(call)
            audit.log("COLLATERAL", contract.contractId, "MARGIN_CALL", "SYSTEM",
                newValue = "deficit=${call.deficitHkd} coverage=${call.coverageRatio}")
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
            audit.log("COLLATERAL", contract.contractId, "MARGIN_CALL_INCREASED", "SYSTEM",
                oldValue = oldDeficit.toPlainString(), newValue = call.deficitHkd.toPlainString())
        }
    }
}
