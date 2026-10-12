package com.piscessoft.sbl.model

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

data class LocateRequest(
    val locateId: String,
    var status: LocateStatus,
    val borrowerId: String,
    val requestingSystem: String,
    val requestRef: String?,
    val stockCode: String,
    val requestedQty: Long,
    var approvedQty: Long? = null,
    var rejectedQty: Long? = null,
    var rejectReason: String? = null,
    var feeRate: BigDecimal? = null,
    var feeType: FeeType? = null,
    var rateBasis: String? = null,
    val requestedAt: Instant,
    var approvedAt: Instant? = null,
    var expiresAt: Instant? = null,
    var convertedAt: Instant? = null,
    var contractId: String? = null,
    var assuranceType: AssuranceType? = null,
    var assuranceId: String? = null,
    var assuranceBasis: AssuranceBasis? = null,
    var sblaId: String? = null,
    val designatedSecurity: Boolean = false,
    val tickRuleExempt: Boolean = false,
    val createdAt: Instant
)

data class DocumentaryAssurance(
    val assuranceId: String,
    val assuranceType: AssuranceType,
    val lenderId: String?,
    val borrowerId: String,
    val stockCode: String,
    val quantity: Long,
    val assuranceBasis: AssuranceBasis?,
    val channel: String?,
    val createdTs: Instant,
    val expiresAt: Instant?,
    val retentionTier: RetentionTier,
    val retentionExpiry: LocalDate
)

data class BlanketAssurance(
    val blanketId: String,
    val lenderId: String,
    val borrowerId: String,
    val stockCode: String,
    val poolQtyOriginal: Long,
    var poolQtyRemaining: Long,
    val validFrom: Instant,
    val validUntil: Instant,
    val channel: String,
    val tapeReference: String?,
    var status: String = "ACTIVE",
    val createdAt: Instant
)

/** Result of the locate decision workflow (plan section 2.1). */
data class LocateDecision(
    val locate: LocateRequest,
    val outcome: LocateStatus,
    val availableQty: Long,
    val message: String
)
