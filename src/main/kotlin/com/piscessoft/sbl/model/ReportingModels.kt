package com.piscessoft.sbl.model

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

data class ShortPositionReport(
    val reportId: String,
    val reportDate: LocalDate,
    val stockCode: String,
    val stockName: String,
    val netShortShares: Long,
    val netShortValueHkd: BigDecimal,
    val reportable: Boolean,
    var submissionId: String? = null,
    val createdAt: Instant
)

data class SprsSubmission(
    val submissionId: String,
    val reportDate: LocalDate,
    val frequency: String,   // WEEKLY | DAILY
    val filename: String,
    val rowCount: Int,
    var submissionRef: String? = null,
    var status: String = "GENERATED",
    val createdAt: Instant
)

data class IrdSbulTransaction(
    val entryId: String,
    val sblaId: String,
    val contractId: String,
    val taxYear: Int,
    val category: Int,       // 0 = exempt; 1..4 = non-exempt schedule
    val stampable: Boolean,
    val reason: String?,
    val createdAt: Instant
)

data class Cap571xDisclosure(
    val disclosureId: String,
    val personId: String,
    val contractId: String?,
    val eventType: String,   // BORROW | RETURN | DEPLOYMENT | DEEMED_ACQUISITION
    val eventDate: LocalDate,
    val stockCode: String,
    val quantity: Long,
    val prescribedPurpose: String?,
    val disclosureDeadline: LocalDate,
    var status: String = "PENDING",
    val createdAt: Instant
)
