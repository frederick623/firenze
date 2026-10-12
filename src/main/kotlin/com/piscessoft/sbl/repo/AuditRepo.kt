package com.piscessoft.sbl.repo

import com.piscessoft.sbl.db.Database
import com.piscessoft.sbl.model.ActorType
import com.piscessoft.sbl.model.AuditEntry
import com.piscessoft.sbl.model.RetentionTier
import com.piscessoft.sbl.util.SblTime
import java.sql.ResultSet

/**
 * Append-only audit repository. Exposes only insert + read operations: there is no
 * update or delete, mirroring the plan's "NO UPDATE or DELETE ever permitted" rule.
 */
class AuditRepo(private val db: Database) {
    fun insert(e: AuditEntry) = db.update(
        """INSERT INTO audit_log (log_id,entity_type,entity_id,event_type,event_timestamp,actor_id,
           actor_type,old_value,new_value,regulatory_ref,retention_tier,retention_expiry,source_ip,
           session_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
        e.logId, e.entityType, e.entityId, e.eventType, SblTime.formatTs(e.eventTimestamp),
        e.actorId, e.actorType.name, e.oldValue, e.newValue, e.regulatoryRef, e.retentionTier.code,
        SblTime.formatDate(e.retentionExpiry), e.sourceIp, e.sessionId
    )

    fun forEntity(entityType: String, entityId: String): List<AuditEntry> =
        db.query("""SELECT * FROM audit_log WHERE entity_type=? AND entity_id=?
                    ORDER BY event_timestamp""", entityType, entityId) { map(it) }

    fun count(): Long = db.queryLong("SELECT COUNT(*) FROM audit_log")

    fun all(): List<AuditEntry> =
        db.query("SELECT * FROM audit_log ORDER BY event_timestamp") { map(it) }

    private fun map(rs: ResultSet) = AuditEntry(
        logId = rs.getString("log_id"),
        entityType = rs.getString("entity_type"),
        entityId = rs.getString("entity_id"),
        eventType = rs.getString("event_type"),
        eventTimestamp = SblTime.parseTs(rs.getString("event_timestamp"))!!,
        actorId = rs.getString("actor_id"),
        actorType = ActorType.valueOf(rs.getString("actor_type")),
        oldValue = rs.getString("old_value"),
        newValue = rs.getString("new_value"),
        regulatoryRef = rs.getString("regulatory_ref"),
        retentionTier = RetentionTier.fromCode(rs.getString("retention_tier")),
        retentionExpiry = SblTime.parseDate(rs.getString("retention_expiry"))!!,
        sourceIp = rs.getString("source_ip"),
        sessionId = rs.getString("session_id")
    )
}
