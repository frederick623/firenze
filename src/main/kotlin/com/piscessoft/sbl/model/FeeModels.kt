package com.piscessoft.sbl.model

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

data class FeeAccrual(
    val accrualId: String,
    val contractId: String,
    val accrualDate: LocalDate,
    val closingPrice: BigDecimal,
    val qtyOutstanding: Long,
    val annualRate: BigDecimal,
    val dailyFeeHkd: BigDecimal,
    val cumulativeFeeHkd: BigDecimal,
    val indexRate: BigDecimal? = null,
    val spreadBps: Int? = null,
    var settlementStatus: FeeSettlementStatus = FeeSettlementStatus.ACCRUED,
    var settlementBatchId: String? = null,
    val createdAt: Instant
)

data class FeeSplit(
    val splitId: String,
    val accrualId: String,
    val recipientId: String,
    val recipientRole: String,   // LENDER | AGENT | CLIENT_MARGIN | THIRD_PARTY
    val splitPct: BigDecimal,
    val splitAmountHkd: BigDecimal
)

/**
 * One day's rebate on one cash collateral position: interest the firm owes the counterparty
 * on its cash at `benchmark - spread`. Negative when the spread exceeds the benchmark
 * (the counterparty then pays the firm to hold its cash).
 */
data class RebateAccrual(
    val accrualId: String,
    val counterpartyId: String,
    val positionId: String,
    val accrualDate: LocalDate,
    val currency: String,
    val cashAmount: BigDecimal,
    val fxRateToHkd: BigDecimal,
    val benchmark: FloatingIndex,
    val indexRate: BigDecimal,
    val spreadBps: Int,
    val rebateRate: BigDecimal,
    val dailyRebateHkd: BigDecimal,
    var settlementStatus: FeeSettlementStatus = FeeSettlementStatus.ACCRUED,
    var settlementBatchId: String? = null,
    val createdAt: Instant
)

data class FeeStatement(
    val statementId: String,
    val counterpartyId: String,
    val periodFrom: LocalDate,
    val periodTo: LocalDate,
    val totalFeeHkd: BigDecimal,
    /** Cash-collateral rebate owed to the counterparty over the period. */
    val totalRebateHkd: BigDecimal,
    /** Fee minus rebate: positive = counterparty pays the firm, negative = firm pays the counterparty. */
    val netAmountHkd: BigDecimal,
    var status: StatementStatus,
    var sentAt: Instant? = null,
    var agreedAt: Instant? = null,
    var disputeReason: String? = null,
    val createdAt: Instant
)
