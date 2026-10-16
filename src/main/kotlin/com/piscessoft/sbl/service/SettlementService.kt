package com.piscessoft.sbl.service

import com.piscessoft.sbl.model.*
import com.piscessoft.sbl.repo.ContractRepo
import com.piscessoft.sbl.repo.SettlementLegRepo
import com.piscessoft.sbl.util.SblTime
import java.time.LocalDate

/**
 * Settlement of contract legs (stock delivery on open, redelivery on return).
 *
 * - OPEN legs settle only once collateral covering the loan is in place (collateral first).
 * - In [SettlementMode.AUTO] the system settles due legs itself; in [SettlementMode.EXTERNAL]
 *   legs settle only via [confirm] (CCASS / custodian feed).
 * - A leg still unsettled at the end of its intended settlement date is marked FAILED and
 *   keeps retrying (AUTO) or awaiting confirmation (EXTERNAL).
 */
class SettlementService(
    private val legs: SettlementLegRepo,
    private val contracts: ContractRepo,
    private val contractService: ContractService,
    private val collateralService: CollateralService,
    private val audit: AuditService,
    val mode: SettlementMode = SettlementMode.AUTO
) {

    /** Confirm a leg settled on [settledOn]. Idempotent when re-sent with the same [externalRef]. */
    fun confirm(legId: String, settledOn: LocalDate = SblTime.today(), externalRef: String? = null): SettlementLeg {
        val leg = legs.find(legId) ?: error("Settlement leg $legId not found")
        if (leg.status == LegStatus.SETTLED && externalRef != null && externalRef == leg.externalRef) return leg
        check(leg.status == LegStatus.PENDING || leg.status == LegStatus.FAILED) { "Leg $legId is ${leg.status}" }
        if (externalRef != null) {
            legs.findByExternalRef(externalRef)?.let { check(it.legId == legId) { "externalRef $externalRef already used by leg ${it.legId}" } }
        }
        require(!settledOn.isBefore(leg.tradeDate)) { "Settlement date $settledOn is before trade date ${leg.tradeDate}" }
        require(!settledOn.isAfter(SblTime.today())) { "Settlement date $settledOn is in the future" }
        blocker(leg, settledOn)?.let { throw IllegalArgumentException(it) }
        settle(leg, settledOn, externalRef, "CONFIRMED")
        return leg
    }

    /** Record a settlement fail reported by the custodian / operations. */
    fun fail(legId: String, reason: String): SettlementLeg {
        val leg = legs.find(legId) ?: error("Settlement leg $legId not found")
        check(leg.status == LegStatus.PENDING || leg.status == LegStatus.FAILED) { "Leg $legId is ${leg.status}" }
        markFailed(leg, reason)
        return leg
    }

    /**
     * End-of-day settlement run: AUTO-settles legs due on or before [date] whose preconditions
     * are met; legs still unsettled on/after their intended date are marked FAILED.
     */
    fun settleDue(date: LocalDate = SblTime.today()): SettlementRun {
        val settled = mutableListOf<SettlementLeg>()
        val failed = mutableListOf<SettlementLeg>()
        for (leg in legs.unsettled()) {
            if (leg.intendedSettleDate.isAfter(date)) continue
            val reason = if (mode == SettlementMode.AUTO) blocker(leg, date)
                         else "No settlement confirmation received by $date"
            if (reason == null) {
                settle(leg, date, null, "AUTO")
                settled += leg
            } else if (leg.status != LegStatus.FAILED) {
                markFailed(leg, reason)
                failed += leg
            }
        }
        return SettlementRun(date, settled, failed)
    }

    /** AUTO mode: settle a contract's due legs immediately (e.g. right after collateral arrives). */
    fun trySettleNow(contractId: String): List<SettlementLeg> {
        if (mode != SettlementMode.AUTO) return emptyList()
        val today = SblTime.today()
        return legs.forContract(contractId)
            .filter { (it.status == LegStatus.PENDING || it.status == LegStatus.FAILED) && !it.intendedSettleDate.isAfter(today) }
            .filter { blocker(it, today) == null }
            .onEach { settle(it, today, null, "AUTO") }
    }

    /** AUTO mode: settle due OPEN legs of every contract awaiting delivery to [borrowerId]. */
    fun trySettleForBorrower(borrowerId: String): List<SettlementLeg> =
        contracts.byStatus(ContractStatus.CONFIRMED).filter { it.borrowerId == borrowerId }
            .flatMap { trySettleNow(it.contractId) }

    /**
     * Why [leg] cannot settle on [date], or null if it can. An OPEN leg needs the borrower's
     * collateral account to cover every delivered contract plus this one.
     */
    private fun blocker(leg: SettlementLeg, date: LocalDate): String? {
        if (leg.legType != LegType.OPEN) return null
        val c = contracts.find(leg.contractId) ?: return "Contract ${leg.contractId} not found"
        val account = collateralService.valuateAccount(c.borrowerId, date, include = c)
        val own = account.contracts.first { it.contractId == c.contractId }
        if (own.priceMissing || own.borrowedValueHkd!!.signum() == 0)
            return "No price/FX for ${c.stockCode} on $date - cannot verify collateral"
        if (account.priceMissing)
            return "No price/FX for ${account.unpricedContracts.joinToString()} on $date - cannot verify ${c.borrowerId} account cover"
        if (account.eligibleCollateralHkd < account.requiredCollateralHkd) {
            return "Account ${c.borrowerId} collateral ${account.eligibleCollateralHkd.toPlainString()} below required " +
                "${account.requiredCollateralHkd.toPlainString()} HKD including this contract"
        }
        return null
    }

    private fun settle(leg: SettlementLeg, date: LocalDate, externalRef: String?, how: String) {
        contractService.applySettlement(leg, date, externalRef)
        audit.log("SETTLEMENT_LEG", leg.legId, "LEG_SETTLED", "SYSTEM",
            newValue = "${leg.legType} contract=${leg.contractId} qty=${leg.quantity} on $date via $how ref=${leg.externalRef}")
    }

    private fun markFailed(leg: SettlementLeg, reason: String) {
        leg.status = LegStatus.FAILED
        leg.failReason = reason
        leg.updatedAt = SblTime.now()
        legs.update(leg)
        audit.log("SETTLEMENT_LEG", leg.legId, "SETTLEMENT_FAIL", "SYSTEM",
            newValue = "${leg.legType} contract=${leg.contractId} intended=${leg.intendedSettleDate}: $reason")
    }
}
