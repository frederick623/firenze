package com.piscessoft.sbl.model

import java.time.Instant
import java.time.LocalDate

data class AuditEntry(
    val logId: String,
    val entityType: String,
    val entityId: String,
    val eventType: String,
    val eventTimestamp: Instant,
    val actorId: String,
    val actorType: ActorType,
    val oldValue: String?,
    val newValue: String?,
    val regulatoryRef: String?,
    val retentionTier: RetentionTier,
    val retentionExpiry: LocalDate,
    val sourceIp: String? = null,
    val sessionId: String? = null
)
