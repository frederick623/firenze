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
 */
class FeeService(
    private val accruals: FeeAccrualRepo,
    private val splits: FeeSplitRepo,
    private val statements: FeeStatementRepo,
    private val contracts: ContractRepo,
    private val market: MarketDataRepo,
    private val rateHistory: ContractRateHistoryRepo,
    private val audit: AuditService,
    private val dayCount: Int = 365
) {

    /** Accrue one day of fees for every open contract (idempotent per contract/date). */
    fun accrueDaily(date: LocalDate = SblTime.today()): List<FeeAccrual> {
        val out = mutableListOf<FeeAccrual>()
        for (c in contracts.active()) {
            if (c.quantityOutstanding <= 0) continue
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
     * Generate a billing statement for a borrower over a period (plan section 6.3).
     *
     * Only accruals still in ACCRUED state are billed. Accruals already settled under an
     * earlier statement, or waived, are excluded so that regenerating or overlapping a
     * statement period cannot double-bill the borrower. This keeps the statement total
     * consistent with what [settleStatement] will actually settle.
     */
    fun generateStatement(borrowerId: String, from: LocalDate, to: LocalDate): FeeStatement {
        val total = billableAccruals(borrowerId, from, to)
            .fold(BigDecimal.ZERO) { acc, a -> acc.add(a.dailyFeeHkd) }
        val statement = FeeStatement(
            statementId = Ids.newId(),
            counterpartyId = borrowerId,
            periodFrom = from,
            periodTo = to,
            totalFeeHkd = Money.money(total),
            status = StatementStatus.DRAFT,
            createdAt = SblTime.now()
        )
        statements.insert(statement)
        audit.log("FEE", statement.statementId, "STATEMENT_CREATED", "SYSTEM",
            newValue = "borrower=$borrowerId total=${statement.totalFeeHkd}")
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
        s.status = StatementStatus.SETTLED
        statements.update(s)
        audit.log("FEE", statementId, "STATEMENT_SETTLED", "SYSTEM",
            newValue = "batch=$batchId accruals=${ids.size}")
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

    private fun createSplits(c: SblContract, accrual: FeeAccrual, dailyFee: BigDecimal) {
        val agentPct = c.agentSplitPct
        if (c.agentId != null && agentPct != null && agentPct.signum() > 0) {
            val agentAmt = Money.money(dailyFee.multiply(agentPct))
            val lenderAmt = Money.money(dailyFee.subtract(agentAmt))
            splits.insert(FeeSplit(Ids.newId(), accrual.accrualId, c.agentId, "AGENT", agentPct, agentAmt))
            splits.insert(FeeSplit(Ids.newId(), accrual.accrualId, c.lenderId, "LENDER",
                BigDecimal.ONE.subtract(agentPct), lenderAmt))
        } else {
            splits.insert(FeeSplit(Ids.newId(), accrual.accrualId, c.lenderId, "LENDER",
                BigDecimal.ONE, dailyFee))
        }
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
