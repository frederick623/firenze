package com.piscessoft.sbl.service

import com.piscessoft.sbl.model.ActorType
import com.piscessoft.sbl.model.AuditEntry
import com.piscessoft.sbl.model.RetentionTier
import com.piscessoft.sbl.repo.AuditRepo
import com.piscessoft.sbl.util.Ids
import com.piscessoft.sbl.util.SblTime

/**
 * Module 8 - Audit Trail & Record Keeping.
 *
 * Every state change routes through [log], producing an immutable audit record with the
 * correct regulatory reference and retention tier (plan section 9). The store is
 * append-only; there is no update/delete path.
 */
class AuditService(private val repo: AuditRepo) {

    fun log(
        entityType: String,
        entityId: String,
        eventType: String,
        actorId: String,
        actorType: ActorType = ActorType.SYSTEM,
        oldValue: String? = null,
        newValue: String? = null,
        regulatoryRef: String? = null,
        retentionTier: RetentionTier = RetentionTier.SEVEN_YEAR
    ): AuditEntry {
        val now = SblTime.now()
        val entry = AuditEntry(
            logId = Ids.newId(),
            entityType = entityType,
            entityId = entityId,
            eventType = eventType,
            eventTimestamp = now,
            actorId = actorId,
            actorType = actorType,
            oldValue = oldValue,
            newValue = newValue,
            regulatoryRef = regulatoryRef,
            retentionTier = retentionTier,
            retentionExpiry = SblTime.today().plusYears(retentionTier.years.toLong())
        )
        repo.insert(entry)
        return entry
    }

    fun history(entityType: String, entityId: String): List<AuditEntry> =
        repo.forEntity(entityType, entityId)

    fun total(): Long = repo.count()

    /**
     * SFC on-demand export (plan section 9.3). Returns the full audit trail for an entity;
     * the caller is expected to already hold the COMPLIANCE_OFFICER role. The export action
     * is itself logged.
     */
    fun exportForEntity(entityType: String, entityId: String, officerId: String): List<AuditEntry> {
        val records = repo.forEntity(entityType, entityId)
        log(
            entityType = "REPORT",
            entityId = "$entityType:$entityId",
            eventType = "SFC_EXPORT",
            actorId = officerId,
            actorType = ActorType.USER,
            regulatoryRef = "SFO s.130",
            newValue = "exported ${records.size} audit records"
        )
        return records
    }
}
