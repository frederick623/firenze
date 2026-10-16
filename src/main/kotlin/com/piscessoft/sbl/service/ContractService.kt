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
    private val legs: SettlementLegRepo,
    private val allocations: AllocationService,
    private val audit: AuditService,
    /** Standard settlement cycle for recall returns (T+2). */
    private val recallSettlementDays: Int = 2,
    /** SFO deployment window for regulated persons (plan section 4.5). */
    private val regulatedPersonWindowDays: Int = 5,
    /** Business days after a missed recall deadline before a buy-in is triggered. */
    private val buyInGraceDays: Int = 3,
    /** Settlement cycle for loans to / returns from external counterparties and clients (T+2). */
    private val standardSettlementDays: Int = 2,
    /** Settlement cycle when either side is an INTERNAL desk of the firm (T+0). */
    private val internalSettlementDays: Int = 0
) {
    companion object {
        /** Minimum collateral ratio for a borrow covering a short sale (plan section 5). */
        val MIN_RATIO_SHORT_SALE = BigDecimal("1.05")
        /** Minimum collateral ratio for a general (non-short-sale) borrow. */
        val MIN_RATIO_GENERAL = BigDecimal("1.00")
    }

    /**
     * Open a borrow contract by converting an APPROVED/PARTIAL locate (plan section 4.1).
     *
     * The contract is booked as CONFIRMED with a PENDING OPEN settlement leg. It only becomes
     * ACTIVE (and starts accruing fees) once the OPEN leg settles - see [SettlementService].
     * [settlementDate] defaults to T+2, or T+0 when either party is an INTERNAL desk.
     *
     * The full quantity must be sourced up front (see [AllocationService.plan]); pass
     * [borrowInContractId] to fund the loan from a specific street borrow-in first.
     */
    fun openFromLocate(
        locateId: String,
        lenderId: String,
        transactionModel: TransactionModel = TransactionModel.PRINCIPAL,
        tradeType: TradeType = TradeType.FIXED,
        closeDate: LocalDate? = null,
        collateralRatio: BigDecimal? = null,
        agentId: String? = null,
        agentSplitPct: BigDecimal? = null,
        settlementDate: LocalDate? = null,
        borrowInContractId: String? = null,
        /** Borrow covers a short sale (1.05 floor). false = general borrow (1.00 floor). */
        shortSale: Boolean = true
    ): SblContract {
        val locate = locates.find(locateId) ?: error("Locate $locateId not found")
        require(locate.status == LocateStatus.APPROVED || locate.status == LocateStatus.PARTIAL) {
            "Locate $locateId is ${locate.status}, cannot open a contract"
        }
        val qty = locate.approvedQty ?: error("Locate $locateId has no approved quantity")
        val borrower = counterparties.find(locate.borrowerId) ?: error("Unknown borrower")
        val isShort = shortSale
        val minRatio = if (isShort) MIN_RATIO_SHORT_SALE else MIN_RATIO_GENERAL
        val ratio = collateralRatio ?: minRatio
        require(ratio >= minRatio) {
            "Collateral ratio $ratio is below the ${if (isShort) "short-sale" else "general"} minimum $minRatio"
        }
        val now = SblTime.now()
        val today = SblTime.today()
        val settleDate = settlementDate ?: defaultSettlementDate(lenderId, locate.borrowerId, today)
        require(!settleDate.isBefore(today)) { "Settlement date $settleDate is before trade date $today" }

        val regulated = borrower.regulatedPerson
        val deadline = if (regulated) SblTime.addBusinessDays(today, regulatedPersonWindowDays) else null

        val contract = SblContract(
            contractId = Ids.newId(),
            status = ContractStatus.CONFIRMED,
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
        val slices = allocations.plan(contract.stockCode, lenderId, qty, borrowInContractId)
        val sourced = slices.sumOf { it.quantity }
        require(sourced == qty) {
            "Cannot source $qty ${contract.stockCode} for lender $lenderId (only $sourced free)"
        }
        contracts.insert(contract)
        allocations.apply(contract, slices, today)
        recordRate(contract, today, "OPEN")
        val openLeg = newLeg(contract.contractId, LegType.OPEN, qty, today, settleDate)
        legs.insert(openLeg)

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
        event(contract, "OPEN", null, ContractStatus.CONFIRMED, "qty=$qty rate=${contract.feeRate} settle=$settleDate")
        audit.log("CONTRACT", contract.contractId, "OPEN", lenderId, ActorType.SYSTEM,
            newValue = "${locate.stockCode} qty=$qty rate=${contract.feeRate} settle=$settleDate leg=${openLeg.legId}",
            regulatoryRef = if (regulated) "Cap.571X" else null,
            retentionTier = RetentionTier.THREE_YEAR)
        if (regulated) {
            audit.log("CONTRACT", contract.contractId, "REGULATED_PERSON_TIMER_START", "SYSTEM",
                newValue = "deploy by $deadline", regulatoryRef = "Cap.571X")
        }
        return contract
    }

    /**
     * Book a street borrow-in: an INTERNAL firm entity borrows [quantity] shares from an
     * external lender to fund loan-outs (matched book). Booked CONFIRMED with a PENDING OPEN
     * leg on the standard cycle; once booked its quantity counts as lendable supply.
     */
    fun bookBorrowIn(
        streetLenderId: String,
        firmBorrowerId: String,
        stockCode: String,
        quantity: Long,
        feeRate: BigDecimal,
        feeType: FeeType = FeeType.FIXED,
        floatingIndex: FloatingIndex? = null,
        floatingSpreadBps: Int? = null,
        collateralRatio: BigDecimal? = null,
        closeDate: LocalDate? = null,
        settlementDate: LocalDate? = null,
        sblaId: String? = null
    ): SblContract {
        require(quantity > 0) { "Quantity must be positive" }
        require(feeRate.signum() >= 0) { "Fee rate must not be negative" }
        val lender = counterparties.find(streetLenderId)
            ?: throw IllegalArgumentException("Unknown street lender $streetLenderId")
        require(lender.type != CounterpartyType.INTERNAL) { "Street lender must be an external counterparty" }
        val firm = counterparties.find(firmBorrowerId)
            ?: throw IllegalArgumentException("Unknown firm entity $firmBorrowerId")
        require(firm.type == CounterpartyType.INTERNAL) { "Borrow-ins are booked by an INTERNAL firm entity" }
        if (feeType == FeeType.FLOATING) require(floatingIndex != null) { "Floating borrow-in needs an index" }
        val now = SblTime.now()
        val today = SblTime.today()
        val settleDate = settlementDate ?: SblTime.addBusinessDays(today, standardSettlementDays)
        require(!settleDate.isBefore(today)) { "Settlement date $settleDate is before trade date $today" }

        val contract = SblContract(
            contractId = Ids.newId(),
            status = ContractStatus.CONFIRMED,
            tradeType = TradeType.NEGOTIATED,
            transactionModel = TransactionModel.PRINCIPAL,
            lenderId = streetLenderId,
            borrowerId = firmBorrowerId,
            stockCode = stockCode,
            quantity = quantity,
            openDate = today,
            closeDate = closeDate,
            feeRate = Money.rate(feeRate),
            feeType = feeType,
            feeRateEffectiveDate = today,
            floatingIndex = floatingIndex,
            floatingSpreadBps = floatingSpreadBps,
            collateralRatioRequired = collateralRatio ?: BigDecimal("1.05"),
            isShortSaleBorrow = false,
            sblaId = sblaId,
            bookSide = BookSide.BORROW_IN,
            createdAt = now,
            updatedAt = now
        )
        contracts.insert(contract)
        recordRate(contract, today, "OPEN")
        val openLeg = newLeg(contract.contractId, LegType.OPEN, quantity, today, settleDate)
        legs.insert(openLeg)
        assurances.insert(
            DocumentaryAssurance(
                assuranceId = Ids.newId(),
                assuranceType = AssuranceType.BORROW,
                lenderId = streetLenderId,
                borrowerId = firmBorrowerId,
                stockCode = stockCode,
                quantity = quantity,
                assuranceBasis = null,
                channel = "BORROW_IN",
                createdTs = now,
                expiresAt = null,
                retentionTier = RetentionTier.THREE_YEAR,
                retentionExpiry = today.plusYears(RetentionTier.THREE_YEAR.years.toLong())
            )
        )
        event(contract, "BORROW_IN", null, ContractStatus.CONFIRMED,
            "qty=$quantity rate=${contract.feeRate} settle=$settleDate")
        audit.log("CONTRACT", contract.contractId, "BORROW_IN", firmBorrowerId, ActorType.USER,
            newValue = "$stockCode qty=$quantity from=$streetLenderId rate=${contract.feeRate} settle=$settleDate",
            retentionTier = RetentionTier.THREE_YEAR)
        return contract
    }

    /** Fund [qty] of an existing loan-out from a street borrow-in, moving it off its current sources. */
    fun pairWithBorrowIn(loanOutId: String, borrowInId: String, qty: Long): Long {
        val loan = load(loanOutId)
        val borrowIn = load(borrowInId)
        require(loan.status.isOpen() || loan.status == ContractStatus.CONFIRMED) { "Loan $loanOutId is ${loan.status}" }
        require(qty in 1..loan.quantityOutstanding) { "Invalid qty $qty (outstanding ${loan.quantityOutstanding})" }
        val moved = allocations.pair(loan, borrowIn, qty)
        audit.log("CONTRACT", loanOutId, "PAIRED", "USER", ActorType.USER,
            newValue = "borrowIn=$borrowInId qty=$moved of $qty")
        return moved
    }

    /**
     * A source position changed (client sale, pledge, settlement status): move affected loans to
     * other free inventory and recall whatever cannot be re-sourced.
     */
    fun onSourcePositionChanged(position: Position): List<RecallNotice> =
        recallShortfalls(allocations.rebalancePosition(position.positionId, SblTime.today()), RecallReason.SOURCE_SOLD)
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
        // A street recall of a borrow-in cascades: re-source the loans it funds, recall the rest.
        if (c.bookSide == BookSide.BORROW_IN) {
            recallShortfalls(allocations.rebalanceBorrowIn(c, SblTime.today()), RecallReason.SOURCE_RECALLED)
        }
        return notice
    }

    /**
     * Instruct a return of [qty] shares as a PENDING RETURN leg. Outstanding quantity (and
     * fee accrual) only reduces once the leg settles. [settlementDate] defaults to T+2,
     * or T+0 for internal desks.
     */
    fun instructReturn(contractId: String, qty: Long, settlementDate: LocalDate? = null): SettlementLeg {
        val c = load(contractId)
        require(c.status.isOpen()) { "Contract $contractId is ${c.status}, cannot return" }
        // Borrowed-in shares still on-lent to clients cannot go back to the street.
        val onLent = if (c.bookSide == BookSide.BORROW_IN) allocations.allocatedFromBorrowIn(contractId) else 0
        val returnable = c.quantityOutstanding - legs.pendingReturnQty(contractId) - onLent
        require(qty in 1..returnable) { "Invalid return qty $qty (returnable $returnable)" }
        val today = SblTime.today()
        val settleDate = settlementDate ?: if (c.bookSide == BookSide.BORROW_IN)
            SblTime.addBusinessDays(today, standardSettlementDays)
        else defaultSettlementDate(c.lenderId, c.borrowerId, today)
        require(!settleDate.isBefore(today)) { "Settlement date $settleDate is before trade date $today" }

        val leg = newLeg(contractId, LegType.RETURN, qty, today, settleDate)
        legs.insert(leg)
        transition(c, ContractStatus.RETURN_PENDING, "RETURN_INSTRUCTED", "qty=$qty settle=$settleDate")
        audit.log("CONTRACT", contractId, "RETURN_INSTRUCTED", "SYSTEM",
            newValue = "qty=$qty settle=$settleDate leg=${leg.legId}")
        return leg
    }

    /** Same-day return: instruct and settle immediately (manual back-office booking). */
    fun processReturn(contractId: String, qty: Long): SblContract {
        val today = SblTime.today()
        return applySettlement(instructReturn(contractId, qty, today), today)
    }

    /** Full close: instruct a return of everything not already pending return. */
    fun close(contractId: String, settlementDate: LocalDate? = null): SettlementLeg {
        val c = load(contractId)
        val onLent = if (c.bookSide == BookSide.BORROW_IN) allocations.allocatedFromBorrowIn(contractId) else 0
        val returnable = c.quantityOutstanding - legs.pendingReturnQty(contractId) - onLent
        require(returnable > 0) { "Nothing left to return on $contractId" }
        return instructReturn(contractId, returnable, settlementDate)
    }

    /**
     * Apply a settled leg to its contract. OPEN: CONFIRMED -> ACTIVE. RETURN: reduce
     * outstanding, satisfy recalls, close when fully returned (close date = [settledOn]).
     * Preconditions (e.g. collateral) are enforced by [SettlementService].
     */
    fun applySettlement(leg: SettlementLeg, settledOn: LocalDate, externalRef: String? = null): SblContract {
        check(leg.status == LegStatus.PENDING || leg.status == LegStatus.FAILED) {
            "Leg ${leg.legId} is ${leg.status}, cannot settle"
        }
        val c = load(leg.contractId)
        when (leg.legType) {
            LegType.OPEN -> check(c.status == ContractStatus.CONFIRMED) {
                "Contract ${c.contractId} is ${c.status}, OPEN leg cannot settle"
            }
            LegType.RETURN -> check(c.status.isOpen()) {
                "Contract ${c.contractId} is ${c.status}, RETURN leg cannot settle"
            }
        }
        leg.status = LegStatus.SETTLED
        leg.actualSettleDate = settledOn
        if (externalRef != null) leg.externalRef = externalRef
        leg.failReason = null
        leg.updatedAt = SblTime.now()
        legs.update(leg)

        when (leg.legType) {
            LegType.OPEN -> {
                transition(c, ContractStatus.ACTIVE, "OPEN_SETTLED", "qty=${leg.quantity} settled $settledOn")
                audit.log("CONTRACT", c.contractId, "OPEN_SETTLED", "SYSTEM",
                    newValue = "leg=${leg.legId} settled $settledOn ref=${leg.externalRef}")
            }
            LegType.RETURN -> settleReturn(c, leg, settledOn)
        }
        return c
    }

    private fun settleReturn(c: SblContract, leg: SettlementLeg, settledOn: LocalDate) {
        val qty = leg.quantity
        val now = SblTime.now()
        c.quantityReturned += qty
        c.quantityOutstanding -= qty
        if (c.bookSide == BookSide.LOAN_OUT) allocations.release(c, qty, settledOn)

        // Apply against any open recall for this contract.
        recalls.forContract(c.contractId)
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
            c.actualCloseDate = settledOn
            persist(c, "CLOSE", "fully returned, settled $settledOn")
            audit.log("CONTRACT", c.contractId, "CLOSE", "SYSTEM",
                newValue = "fully returned, leg=${leg.legId} settled $settledOn")
        } else {
            c.status = if (legs.pendingReturnQty(c.contractId) > 0) ContractStatus.RETURN_PENDING
                       else ContractStatus.PARTIALLY_RETURNED
            persist(c, "PARTIAL_RETURN", "returned=$qty outstanding=${c.quantityOutstanding} settled $settledOn")
            audit.log("CONTRACT", c.contractId, "PARTIAL_RETURN", "SYSTEM",
                newValue = "returned=$qty outstanding=${c.quantityOutstanding} leg=${leg.legId} settled $settledOn")
        }
    }

    /** T+0 if either party is an internal desk, else the standard T+2 cycle. */
    fun defaultSettlementDate(lenderId: String, borrowerId: String, tradeDate: LocalDate = SblTime.today()): LocalDate {
        val internal = listOf(lenderId, borrowerId).any { counterparties.find(it)?.type == CounterpartyType.INTERNAL }
        return SblTime.addBusinessDays(tradeDate, if (internal) internalSettlementDays else standardSettlementDays)
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
        val oldOutstanding = c.quantityOutstanding
        c.quantity = scale(c.quantity)
        c.quantityReturned = scale(c.quantityReturned)
        c.quantityOutstanding = c.quantity - c.quantityReturned
        c.status = ContractStatus.CA_ADJUSTED
        persist(c, "CA_ADJUSTMENT", "$detail qty $oldQty -> ${c.quantity}")
        if (c.bookSide == BookSide.LOAN_OUT) allocations.rescale(c, oldOutstanding, SblTime.today())
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
        // Pending returns are superseded by the termination.
        legs.forContract(contractId).filter { it.status == LegStatus.PENDING || it.status == LegStatus.FAILED }.forEach {
            it.status = LegStatus.CANCELLED
            it.failReason = "Contract terminated: $reason"
            it.updatedAt = SblTime.now()
            legs.update(it)
        }
        audit.log("CONTRACT", contractId, "EARLY_TERMINATION", "SYSTEM",
            newValue = reason, regulatoryRef = "CA")
        if (c.bookSide == BookSide.LOAN_OUT) {
            allocations.releaseAll(c, SblTime.today())
        } else {
            recallShortfalls(allocations.rebalanceBorrowIn(c, SblTime.today()), RecallReason.SOURCE_RECALLED)
        }
        return c
    }

    // ---- helpers ----

    /**
     * Recall the quantities that could not be re-sourced, one notice per loan, and mark the
     * affected allocations so the eventual returns release the deficient source first.
     * Loans not yet delivered cannot be recalled; they are flagged for operations instead.
     */
    private fun recallShortfalls(
        shortfalls: List<AllocationService.Shortfall>, reason: RecallReason
    ): List<RecallNotice> {
        val notices = mutableListOf<RecallNotice>()
        for ((contractId, items) in shortfalls.groupBy { it.contractId }) {
            val c = load(contractId)
            val qty = items.sumOf { it.quantity }.coerceAtMost(c.quantityOutstanding)
            if (!c.status.isOpen() || qty <= 0) {
                audit.log("CONTRACT", contractId, "SOURCE_SHORTFALL_UNRESOLVED", "SYSTEM",
                    newValue = "qty=$qty status=${c.status} reason=$reason")
                continue
            }
            notices += recall(contractId, qty, reason)
            items.forEach { allocations.markRecalled(it.allocationId, it.quantity) }
        }
        return notices
    }

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

    private fun newLeg(contractId: String, type: LegType, qty: Long, tradeDate: LocalDate, settleDate: LocalDate): SettlementLeg {
        val now = SblTime.now()
        return SettlementLeg(
            legId = Ids.newId(),
            contractId = contractId,
            legType = type,
            quantity = qty,
            tradeDate = tradeDate,
            intendedSettleDate = settleDate,
            status = LegStatus.PENDING,
            createdAt = now,
            updatedAt = now
        )
    }

    private fun ContractStatus.isOpen(): Boolean = this in setOf(
        ContractStatus.ACTIVE, ContractStatus.RECALLED, ContractStatus.RETURN_PENDING,
        ContractStatus.PARTIALLY_RETURNED, ContractStatus.RERATED, ContractStatus.ROLLED_OVER,
        ContractStatus.CA_ADJUSTED
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
