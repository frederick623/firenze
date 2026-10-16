package com.piscessoft.sbl.service

import com.piscessoft.sbl.model.*
import com.piscessoft.sbl.repo.*
import com.piscessoft.sbl.util.Ids
import com.piscessoft.sbl.util.Money
import com.piscessoft.sbl.util.SblTime
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Module 5 - Fee & Billing.
 *
 * Daily fee accrual (closing price x outstanding qty x annual rate / 365), lender/agent
 * fee splits, and billing-statement generation, agreement and settlement (plan section 6).
 *
 * Cash collateral earns a rebate: each day the firm owes the counterparty interest on the
 * cash in its collateral account at `benchmark - spread`, which is netted against the fee
 * on the counterparty's statement.
 */
class FeeService(
    private val accruals: FeeAccrualRepo,
    private val splits: FeeSplitRepo,
    private val statements: FeeStatementRepo,
    private val contracts: ContractRepo,
    private val market: MarketDataRepo,
    private val rateHistory: ContractRateHistoryRepo,
    private val audit: AuditService,
    private val legs: SettlementLegRepo,
    private val allocations: AllocationService,
    private val rebates: RebateAccrualRepo,
    private val collateral: CollateralPositionRepo,
    private val collateralTypes: CollateralTypeConfigRepo,
    private val counterparties: CounterpartyRepo,
    /** Rebate spread (bps below the benchmark) for counterparties without their own. */
    private val defaultRebateSpreadBps: Int = 25,
    /** Benchmark a cash currency's rebate is priced against; cash in other currencies earns no rebate. */
    private val rebateBenchmarks: Map<String, FloatingIndex> = mapOf(
        "HKD" to FloatingIndex.HIBOR_1M,
        "USD" to FloatingIndex.SOFR,
        "EUR" to FloatingIndex.EURIBOR
    ),
    private val dayCount: Int = 365
) {

    /**
     * Accrue one day of fees for every open contract (idempotent per contract/date).
     *
     * Fees run from the OPEN leg's settlement date (inclusive) to the RETURN leg's settlement
     * date (exclusive): only ACTIVE-family contracts are accrued, and a settled return has
     * already reduced outstanding quantity before the day's accrual (settlement runs first at EOD).
     */
    fun accrueDaily(date: LocalDate = SblTime.today()): List<FeeAccrual> {
        val out = mutableListOf<FeeAccrual>()
        for (c in contracts.active()) {
            if (c.quantityOutstanding <= 0) continue
            val accrualStart = legs.openLeg(c.contractId)?.actualSettleDate ?: c.openDate
            if (date.isBefore(accrualStart)) continue
            if (accruals.existsFor(c.contractId, date)) continue
            val price = market.latestPrice(c.stockCode, date) ?: continue

            val (annualRate, indexRate, spreadBps) = effectiveRate(c, date)
            val notional = price.multiply(BigDecimal(c.quantityOutstanding))
            val dailyFee = Money.money(
                notional.multiply(annualRate).divide(BigDecimal(dayCount), Money.MONEY_SCALE, Money.ROUNDING)
            )
            val cumulative = Money.money(c.cumulativeFeeHkd.add(dailyFee))

            val accrual = FeeAccrual(
                accrualId = Ids.newId(),
                contractId = c.contractId,
                accrualDate = date,
                closingPrice = price,
                qtyOutstanding = c.quantityOutstanding,
                annualRate = Money.rate(annualRate),
                dailyFeeHkd = dailyFee,
                cumulativeFeeHkd = cumulative,
                indexRate = indexRate?.let { Money.rate(it) },
                spreadBps = spreadBps,
                createdAt = SblTime.now()
            )
            accruals.insert(accrual)
            createSplits(c, accrual, dailyFee)

            c.cumulativeFeeHkd = cumulative
            persistCumulative(c)
            out += accrual
        }
        return out
    }

    /**
     * Accrue one day of rebate on every ACTIVE cash collateral position (idempotent per
     * position/date). Rate = benchmark of the cash currency on [date] - the counterparty's
     * spread, so it is negative when the spread exceeds the benchmark.
     *
     * Internal-desk accounts are skipped: their cash is posted to street lenders, and the
     * per-lender ledger needed to receive that rebate is not modelled. Positions whose
     * benchmark or FX rate is missing are audited and not accrued.
     */
    fun accrueRebates(date: LocalDate = SblTime.today()): List<RebateAccrual> {
        val out = mutableListOf<RebateAccrual>()
        for (cpId in collateral.counterpartiesWithActive()) {
            val cp = counterparties.find(cpId) ?: continue
            if (cp.type == CounterpartyType.INTERNAL) continue
            val spreadBps = cp.rebateSpreadBps ?: defaultRebateSpreadBps

            for (p in collateral.activeForCounterparty(cpId)) {
                if (!p.collateralType.startsWith("CASH_")) continue
                if (p.createdAt.atZone(SblTime.HKT).toLocalDate().isAfter(date)) continue
                if (rebates.existsFor(p.positionId, date)) continue
                val currency = collateralTypes.find(p.collateralType)?.currency ?: continue

                val benchmark = rebateBenchmarks[currency]
                val indexRate = benchmark?.let { market.index(it, date) }
                val fx = market.fxOrNull(currency, date)
                if (benchmark == null || indexRate == null || fx == null) {
                    audit.log("FEE", cpId, "REBATE_NO_RATE", "SYSTEM",
                        newValue = "position=${p.positionId} $currency benchmark=${benchmark ?: "none"} " +
                            "index=${indexRate ?: "missing"} fx=${fx ?: "missing"} on $date - not accrued")
                    continue
                }

                val rate = indexRate.subtract(BigDecimal(spreadBps).divide(BigDecimal(10000)))
                val daily = Money.money(p.quantity.multiply(fx).multiply(rate)
                    .divide(BigDecimal(dayCount), Money.MONEY_SCALE, Money.ROUNDING))
                val accrual = RebateAccrual(
                    accrualId = Ids.newId(),
                    counterpartyId = cpId,
                    positionId = p.positionId,
                    accrualDate = date,
                    currency = currency,
                    cashAmount = p.quantity,
                    fxRateToHkd = fx,
                    benchmark = benchmark,
                    indexRate = Money.rate(indexRate),
                    spreadBps = spreadBps,
                    rebateRate = Money.rate(rate),
                    dailyRebateHkd = daily,
                    createdAt = SblTime.now()
                )
                rebates.insert(accrual)
                out += accrual
            }
        }
        return out
    }

    /**
     * Generate a billing statement for a borrower over a period (plan section 6.3).
     *
     * Only accruals still in ACCRUED state are billed. Accruals already settled under an
     * earlier statement, or waived, are excluded so that regenerating or overlapping a
     * statement period cannot double-bill the borrower. This keeps the statement total
     * consistent with what [settleStatement] will actually settle. Cash-collateral rebates
     * for the same period are netted against the fee.
     */
    fun generateStatement(borrowerId: String, from: LocalDate, to: LocalDate): FeeStatement {
        val fee = Money.money(billableAccruals(borrowerId, from, to)
            .fold(BigDecimal.ZERO) { acc, a -> acc.add(a.dailyFeeHkd) })
        val rebate = Money.money(rebates.billable(borrowerId, from, to)
            .fold(BigDecimal.ZERO) { acc, a -> acc.add(a.dailyRebateHkd) })
        val statement = FeeStatement(
            statementId = Ids.newId(),
            counterpartyId = borrowerId,
            periodFrom = from,
            periodTo = to,
            totalFeeHkd = fee,
            totalRebateHkd = rebate,
            netAmountHkd = Money.money(fee.subtract(rebate)),
            status = StatementStatus.DRAFT,
            createdAt = SblTime.now()
        )
        statements.insert(statement)
        audit.log("FEE", statement.statementId, "STATEMENT_CREATED", "SYSTEM",
            newValue = "borrower=$borrowerId fee=${statement.totalFeeHkd} rebate=${statement.totalRebateHkd} " +
                "net=${statement.netAmountHkd}")
        return statement
    }

    fun sendStatement(statementId: String): FeeStatement =
        transition(statementId, StatementStatus.SENT) { it.sentAt = SblTime.now() }

    fun agreeStatement(statementId: String): FeeStatement =
        transition(statementId, StatementStatus.AGREED) { it.agreedAt = SblTime.now() }

    fun disputeStatement(statementId: String, reason: String): FeeStatement =
        transition(statementId, StatementStatus.DISPUTED) { it.disputeReason = reason }

    /** Settle all agreed accruals into a settlement batch (plan section 6.4). */
    fun settleStatement(statementId: String): FeeStatement {
        val s = statements.find(statementId) ?: error("Statement $statementId not found")
        val batchId = Ids.newId()
        val ids = billableAccruals(s.counterpartyId, s.periodFrom, s.periodTo).map { it.accrualId }
        accruals.markSettled(ids, batchId)
        val rebateIds = rebates.billable(s.counterpartyId, s.periodFrom, s.periodTo).map { it.accrualId }
        rebates.markSettled(rebateIds, batchId)
        s.status = StatementStatus.SETTLED
        statements.update(s)
        audit.log("FEE", statementId, "STATEMENT_SETTLED", "SYSTEM",
            newValue = "batch=$batchId accruals=${ids.size} rebates=${rebateIds.size}")
        return s
    }

    // ---- internals ----

    /** Unsettled accruals for a borrower's contracts within an inclusive date range. */
    private fun billableAccruals(borrowerId: String, from: LocalDate, to: LocalDate): List<FeeAccrual> {
        val contractIds = contracts.all()
            .filter { it.borrowerId == borrowerId }
            .map { it.contractId }
            .toSet()
        return contractIds.flatMap { accruals.forContract(it) }
            .filter {
                it.settlementStatus == FeeSettlementStatus.ACCRUED &&
                    !it.accrualDate.isBefore(from) && !it.accrualDate.isAfter(to)
            }
    }

    /**
     * The annualised rate that actually applied on [date].
     *
     * Resolved from the contract's rate history so that a rerate booked with a future
     * effective date does not reprice earlier days, and a back-dated accrual is priced
     * with the rate in force on that day rather than the contract's latest rate.
     * Floating rates are fixed against the index level of the accrual date.
     */
    private fun effectiveRate(c: SblContract, date: LocalDate): Triple<BigDecimal, BigDecimal?, Int?> {
        val h = rateHistory.rateOn(c.contractId, date)
        val feeType = h?.feeType ?: c.feeType
        val fixedRate = h?.annualRate ?: c.feeRate
        val index = h?.floatingIndex ?: c.floatingIndex
        val spreadBps = h?.floatingSpreadBps ?: c.floatingSpreadBps

        return when (feeType) {
            FeeType.FLOATING -> {
                val idx = index?.let { market.index(it, date) } ?: BigDecimal.ZERO
                val spread = spreadBps ?: 0
                val rate = idx.add(BigDecimal(spread).divide(BigDecimal(10000)))
                Triple(rate, idx, spread)
            }
            else -> Triple(fixedRate, null, null)
        }
    }

    /**
     * Split the day's fee: agent cut first (agency trades), then a pass-through to the owners
     * of client-margin / third-party stock the lender on-lent (pro rata to the allocation on
     * the accrual date, at the owner's fee share). The lender keeps the remainder.
     */
    private fun createSplits(c: SblContract, accrual: FeeAccrual, dailyFee: BigDecimal) {
        var lenderAmt = dailyFee
        var lenderPct = BigDecimal.ONE
        val agentPct = c.agentSplitPct
        if (c.agentId != null && agentPct != null && agentPct.signum() > 0) {
            val agentAmt = Money.money(dailyFee.multiply(agentPct))
            splits.insert(FeeSplit(Ids.newId(), accrual.accrualId, c.agentId, "AGENT", agentPct, agentAmt))
            lenderAmt = lenderAmt.subtract(agentAmt)
            lenderPct = lenderPct.subtract(agentPct)
        }
        val lenderBase = lenderAmt
        val lenderBasePct = lenderPct
        for (share in allocations.feeShares(c, accrual.accrualDate)) {
            val pct = Money.rate(lenderBasePct.multiply(share.fractionOfFee))
            val amt = Money.money(lenderBase.multiply(share.fractionOfFee))
            if (amt.signum() == 0) continue
            splits.insert(FeeSplit(Ids.newId(), accrual.accrualId, share.ownerId, share.sourceType.name, pct, amt))
            lenderAmt = lenderAmt.subtract(amt)
            lenderPct = lenderPct.subtract(pct)
        }
        splits.insert(FeeSplit(Ids.newId(), accrual.accrualId, c.lenderId, "LENDER",
            lenderPct, Money.money(lenderAmt)))
    }

    private fun transition(statementId: String, to: StatementStatus, mutate: (FeeStatement) -> Unit): FeeStatement {
        val s = statements.find(statementId) ?: error("Statement $statementId not found")
        s.status = to
        mutate(s)
        statements.update(s)
        audit.log("FEE", statementId, "STATEMENT_$to", "USER", ActorType.USER)
        return s
    }

    private fun persistCumulative(c: SblContract) {
        if (!contracts.update(c)) {
            val fresh = contracts.find(c.contractId) ?: return
            c.version = fresh.version
            contracts.update(c)
        }
    }
}
