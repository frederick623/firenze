package com.piscessoft.sbl.model

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

data class SblContract(
    val contractId: String,
    var status: ContractStatus,
    val tradeType: TradeType,
    val transactionModel: TransactionModel,
    val lenderId: String,
    val borrowerId: String,
    val agentId: String? = null,
    val stockCode: String,
    var quantity: Long,
    var quantityReturned: Long = 0,
    var quantityOutstanding: Long = quantity,
    val openDate: LocalDate,
    var closeDate: LocalDate? = null,
    var actualCloseDate: LocalDate? = null,
    var feeRate: BigDecimal,
    val feeType: FeeType,
    var feeRateEffectiveDate: LocalDate? = null,
    val floatingIndex: FloatingIndex? = null,
    val floatingSpreadBps: Int? = null,
    val collateralRatioRequired: BigDecimal,
    val isShortSaleBorrow: Boolean = false,
    val sblaId: String? = null,
    val locateId: String? = null,
    val assuranceId: String? = null,
    val assuranceBasis: AssuranceBasis? = null,
    val agentSplitPct: BigDecimal? = null,
    val regulatedPersonFlag: Boolean = false,
    var regulatedPersonDeadline: LocalDate? = null,
    var regulatedPersonDeployed: Boolean? = null,
    var regulatedPersonPurpose: String? = null,
    var potentialNonExempt: Boolean = false,
    var nonExemptReason: String? = null,
    var irdCategory: Int = 0,
    var cumulativeFeeHkd: BigDecimal = BigDecimal.ZERO,
    val bookSide: BookSide = BookSide.LOAN_OUT,
    var version: Int = 1,
    val createdAt: Instant,
    var updatedAt: Instant
)

/**
 * One slice of the inventory supplying a loan-out: a firm, client-margin or third-party
 * position, or a street borrow-in contract. Effective-dated: a change closes the current
 * row ([effectiveTo], exclusive) and opens a new one, so fee pass-through for any past
 * date uses the sources that actually applied on that date.
 */
data class ContractAllocation(
    val allocationId: String,
    val contractId: String,
    val stockCode: String,
    val sourceType: AllocationSource,
    val sourceOwnerId: String,
    val positionId: String? = null,
    val sourceContractId: String? = null,
    var quantity: Long,
    /** Portion of [quantity] under a recall issued because this source fell short. */
    var recalledQty: Long = 0,
    val effectiveFrom: LocalDate,
    var effectiveTo: LocalDate? = null,
    val createdAt: Instant,
    var updatedAt: Instant
)

/**
 * Point-in-time record of the fee rate in force on a contract. A rerate closes the
 * current row and opens a new one, so a fee accrual for any past date can be priced
 * with the rate that actually applied on that date rather than today's rate.
 */
data class ContractRateHistory(
    val rateHistoryId: String,
    val contractId: String,
    val feeType: FeeType,
    val annualRate: BigDecimal?,
    val floatingIndex: FloatingIndex?,
    val floatingSpreadBps: Int?,
    val effectiveFrom: LocalDate,
    var effectiveTo: LocalDate?,
    val reason: String?,
    val createdAt: Instant
)

data class LifecycleEvent(
    val eventId: String,
    val contractId: String,
    val eventType: String,
    val fromStatus: ContractStatus?,
    val toStatus: ContractStatus?,
    val detail: String?,
    val eventTs: Instant
)

data class RecallNotice(
    val recallId: String,
    val contractId: String,
    val recallQty: Long,
    val recallReason: RecallReason,
    val noticeServedAt: Instant,
    val returnDeadline: LocalDate,
    var status: RecallStatus,
    var returnedQty: Long = 0,
    var returnedAt: Instant? = null,
    val createdAt: Instant
)

data class SettlementLeg(
    val legId: String,
    val contractId: String,
    val legType: LegType,
    val quantity: Long,
    val tradeDate: LocalDate,
    val intendedSettleDate: LocalDate,
    var status: LegStatus,
    var actualSettleDate: LocalDate? = null,
    var externalRef: String? = null,
    var failReason: String? = null,
    val createdAt: Instant,
    var updatedAt: Instant
)

data class SettlementRun(
    val businessDate: LocalDate,
    val settled: List<SettlementLeg>,
    val failed: List<SettlementLeg>
)
