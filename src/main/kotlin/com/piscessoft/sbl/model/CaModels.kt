package com.piscessoft.sbl.model

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

data class CaEvent(
    val caEventId: String,
    val stockCode: String,
    val caType: CaType,
    val exDate: LocalDate?,
    val recordDate: LocalDate?,
    val paymentDate: LocalDate?,
    val adjustmentRatio: BigDecimal? = null,
    val cashAmount: BigDecimal? = null,
    val currency: String? = null,
    val description: String? = null,
    val source: String = "HKEX_FEED",
    var processed: Boolean = false,
    val createdAt: Instant
)

data class CaContractImpact(
    val impactId: String,
    val caEventId: String,
    val contractId: String,
    val actionType: CaActionType,
    var status: String = "PENDING",
    val oldQuantity: Long? = null,
    val newQuantity: Long? = null,
    val paymentAmountHkd: BigDecimal? = null,
    val paymentDueDate: LocalDate? = null,
    var paymentStatus: String? = null,
    var recallId: String? = null,
    val notes: String? = null,
    val createdAt: Instant,
    var processedAt: Instant? = null
)
