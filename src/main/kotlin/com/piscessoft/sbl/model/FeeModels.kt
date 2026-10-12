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
    val recipientRole: String,   // LENDER | AGENT
    val splitPct: BigDecimal,
    val splitAmountHkd: BigDecimal
)

data class FeeStatement(
    val statementId: String,
    val counterpartyId: String,
    val periodFrom: LocalDate,
    val periodTo: LocalDate,
    val totalFeeHkd: BigDecimal,
    var status: StatementStatus,
    var sentAt: Instant? = null,
    var agreedAt: Instant? = null,
    var disputeReason: String? = null,
    val createdAt: Instant
)
