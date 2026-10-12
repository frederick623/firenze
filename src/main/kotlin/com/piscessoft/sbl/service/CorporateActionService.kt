package com.piscessoft.sbl.service

import com.piscessoft.sbl.model.*
import com.piscessoft.sbl.repo.*
import com.piscessoft.sbl.util.Ids
import com.piscessoft.sbl.util.Money
import com.piscessoft.sbl.util.SblTime
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Module 6 - Corporate Actions.
 *
 * Ingests corporate-action events and applies the correct treatment to affected open
 * contracts: manufactured payments (cash dividends), quantity adjustments (splits/bonus/
 * consolidation), mandatory recalls (rights/voting) and early termination (delisting).
 * (plan section 7.)
 */
class CorporateActionService(
    private val caEvents: CaEventRepo,
    private val caImpacts: CaImpactRepo,
    private val contracts: ContractRepo,
    private val contractService: ContractService,
    private val market: MarketDataRepo,
    private val audit: AuditService
) {

    fun ingest(event: CaEvent): CaEvent {
        caEvents.insert(event)
        audit.log("CA", event.caEventId, "CA_INGESTED", event.source, ActorType.EXTERNAL,
            newValue = "${event.stockCode} ${event.caType}")
        return event
    }

    /** Process every unprocessed CA event against affected open contracts. */
    fun processAll(): List<CaContractImpact> =
        caEvents.unprocessed().flatMap { process(it) }

    fun process(event: CaEvent): List<CaContractImpact> {
        val affected = contracts.activeForStock(event.stockCode)
            .filter { it.quantityOutstanding > 0 }
            .filter { entitled(event, it) }
        val impacts = affected.map { contract ->
            when (event.caType) {
                CaType.CASH_DIVIDEND, CaType.SCRIP_DIVIDEND ->
                    manufacturedPayment(event, contract)
                CaType.STOCK_SPLIT, CaType.CONSOLIDATION, CaType.STOCK_DIVIDEND ->
                    quantityAdjustment(event, contract)
                CaType.RIGHTS_ISSUE, CaType.VOTING ->
                    mandatoryRecall(event, contract)
                CaType.DELISTING ->
                    earlyTermination(event, contract)
                CaType.NAME_CHANGE ->
                    alertOnly(event, contract)
            }
        }
        impacts.forEach { caImpacts.insert(it) }
        caEvents.markProcessed(event.caEventId)
        return impacts
    }

    // ---- Entitlement ----

    /**
     * A borrow only carries a CA entitlement if it was open before the security went
     * ex. Shares borrowed on or after the ex-date are delivered ex-entitlement, so the
     * borrower owes no manufactured dividend and needs no split/bonus re-basing (the
     * quantity borrowed is already expressed in post-event terms).
     *
     * Events that are not entitlement-driven (delisting, name change, voting) affect
     * every open contract regardless of when it was opened.
     */
    private fun entitled(event: CaEvent, c: SblContract): Boolean {
        if (!event.caType.isEntitlementBased()) return true
        val ex = event.exDate ?: return true
        return c.openDate.isBefore(ex)
    }

    private fun CaType.isEntitlementBased(): Boolean = when (this) {
        CaType.CASH_DIVIDEND, CaType.SCRIP_DIVIDEND, CaType.STOCK_DIVIDEND,
        CaType.STOCK_SPLIT, CaType.CONSOLIDATION, CaType.RIGHTS_ISSUE -> true
        CaType.DELISTING, CaType.VOTING, CaType.NAME_CHANGE -> false
    }

    // ---- CA treatments ----

    private fun manufacturedPayment(event: CaEvent, c: SblContract): CaContractImpact {
        val perShare = event.cashAmount ?: BigDecimal.ZERO
        val fx = market.fx(event.currency ?: "HKD", event.paymentDate ?: SblTime.today())
        val amountHkd = Money.money(perShare.multiply(BigDecimal(c.quantityOutstanding)).multiply(fx))
        audit.log("CA", c.contractId, "MANUFACTURED_PAYMENT", "SYSTEM",
            newValue = "amount=$amountHkd due=${event.paymentDate}", regulatoryRef = "CA")
        return impact(event, c, CaActionType.MANUFACTURED_PAYMENT,
            paymentAmountHkd = amountHkd, paymentDueDate = event.paymentDate, paymentStatus = "PENDING",
            notes = "Manufactured payment ${perShare} ${event.currency ?: "HKD"}/share")
    }

    private fun quantityAdjustment(event: CaEvent, c: SblContract): CaContractImpact {
        val ratio = event.adjustmentRatio ?: BigDecimal.ONE
        val oldQty = c.quantity
        contractService.adjustQuantity(c.contractId, ratio, "${event.caType} ratio=$ratio")
        val updated = contracts.find(c.contractId)!!
        return impact(event, c, CaActionType.QTY_ADJUSTMENT,
            oldQuantity = oldQty, newQuantity = updated.quantity, status = "PROCESSED",
            notes = "${event.caType} ratio=$ratio")
    }

    private fun mandatoryRecall(event: CaEvent, c: SblContract): CaContractImpact {
        val deadline = event.exDate?.let { previousBusinessDay(it) } ?: SblTime.today()
        val notice = contractService.recall(c.contractId, c.quantityOutstanding,
            RecallReason.CA_EVENT, deadlineOverride = deadline)
        return impact(event, c, CaActionType.RECALL,
            recallId = notice.recallId, status = "PROCESSED",
            notes = "${event.caType} recall by $deadline")
    }

    private fun earlyTermination(event: CaEvent, c: SblContract): CaContractImpact {
        contractService.earlyTerminate(c.contractId, "Delisting of ${event.stockCode}")
        return impact(event, c, CaActionType.EARLY_TERMINATION, status = "PROCESSED",
            notes = "Delisting early termination")
    }

    private fun alertOnly(event: CaEvent, c: SblContract): CaContractImpact {
        audit.log("CA", c.contractId, "CA_ALERT", "SYSTEM",
            newValue = "${event.caType} ${event.description ?: ""}".trim())
        return impact(event, c, CaActionType.ALERT_ONLY, status = "PROCESSED",
            notes = event.description ?: event.caType.name)
    }

    private fun impact(
        event: CaEvent, c: SblContract, action: CaActionType,
        status: String = "PENDING",
        oldQuantity: Long? = null, newQuantity: Long? = null,
        paymentAmountHkd: BigDecimal? = null, paymentDueDate: LocalDate? = null,
        paymentStatus: String? = null, recallId: String? = null, notes: String? = null
    ) = CaContractImpact(
        impactId = Ids.newId(),
        caEventId = event.caEventId,
        contractId = c.contractId,
        actionType = action,
        status = status,
        oldQuantity = oldQuantity,
        newQuantity = newQuantity,
        paymentAmountHkd = paymentAmountHkd,
        paymentDueDate = paymentDueDate,
        paymentStatus = paymentStatus,
        recallId = recallId,
        notes = notes,
        createdAt = SblTime.now(),
        processedAt = if (status == "PROCESSED") SblTime.now() else null
    )

    private fun previousBusinessDay(d: LocalDate): LocalDate {
        var day = d.minusDays(1)
        while (day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY) {
            day = day.minusDays(1)
        }
        return day
    }
}
