package com.piscessoft.sbl.model

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

data class Counterparty(
    val id: String,
    val name: String,
    val type: CounterpartyType,
    val tier: BorrowerTier? = null,
    val approvedBorrower: Boolean = false,
    val creditLimitHkd: BigDecimal? = null,
    val feeSettlementBasis: FeeSettlementBasis = FeeSettlementBasis.MONTHLY,
    val regulatedPerson: Boolean = false,
    val createdAt: Instant
)

data class Sbla(
    val id: String,
    val borrowerId: String,
    val lenderId: String? = null,
    val sbaNumber: String,
    val registeredWithIrd: Boolean = true,
    val registeredAt: Instant,
    val validUntil: LocalDate? = null,
    val status: String = "ACTIVE",
    val createdAt: Instant
)

data class DesignatedSecurity(
    val stockCode: String,
    val stockName: String,
    val securityType: SecurityType = SecurityType.EQTY,
    val issuedShares: Long,
    val eligible: Boolean = true,
    val caRestricted: Boolean = false,
    val updatedAt: Instant
)

data class CollateralTypeConfig(
    val collateralType: String,
    val description: String?,
    val currency: String,
    val haircutPct: BigDecimal,
    val eligibleGeneral: Boolean,
    val eligibleShortSale: Boolean,
    val maxConcentration: BigDecimal?,
    val notes: String?
)

data class RateSchedule(
    val scheduleId: String,
    val stockCode: String?,
    val borrowerTier: BorrowerTier?,
    val feeType: FeeType,
    val annualRate: BigDecimal?,
    val floatingIndex: String?,
    val floatingSpreadBps: Int?,
    val minRate: BigDecimal?,
    val effectiveFrom: LocalDate,
    val effectiveTo: LocalDate?,
    val priority: Int,
    val createdAt: Instant
)
