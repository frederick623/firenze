package com.piscessoft.sbl.repo

import com.piscessoft.sbl.db.Database
import com.piscessoft.sbl.db.boolean
import com.piscessoft.sbl.model.*
import com.piscessoft.sbl.util.Money
import com.piscessoft.sbl.util.SblTime
import java.sql.ResultSet

class LocateRepo(private val db: Database) {
    fun insert(l: LocateRequest) = db.update(
        """INSERT INTO locate_request (locate_id,status,borrower_id,requesting_system,request_ref,
           stock_code,requested_qty,approved_qty,rejected_qty,reject_reason,fee_rate,fee_type,
           rate_basis,requested_at,approved_at,expires_at,converted_at,contract_id,assurance_type,
           assurance_id,assurance_basis,sbla_id,designated_security,tick_rule_exempt,created_at)
           VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
        l.locateId, l.status.name, l.borrowerId, l.requestingSystem, l.requestRef,
        l.stockCode, l.requestedQty, l.approvedQty, l.rejectedQty, l.rejectReason,
        Money.toDb(l.feeRate), l.feeType?.name, l.rateBasis, SblTime.formatTs(l.requestedAt),
        SblTime.formatTs(l.approvedAt), SblTime.formatTs(l.expiresAt), SblTime.formatTs(l.convertedAt),
        l.contractId, l.assuranceType?.name, l.assuranceId, l.assuranceBasis?.name, l.sblaId,
        l.designatedSecurity, l.tickRuleExempt, SblTime.formatTs(l.createdAt)
    )

    fun update(l: LocateRequest) = db.update(
        """UPDATE locate_request SET status=?, approved_qty=?, rejected_qty=?, reject_reason=?,
           fee_rate=?, fee_type=?, rate_basis=?, approved_at=?, expires_at=?, converted_at=?,
           contract_id=?, assurance_type=?, assurance_id=?, assurance_basis=?, sbla_id=?
           WHERE locate_id=?""",
        l.status.name, l.approvedQty, l.rejectedQty, l.rejectReason, Money.toDb(l.feeRate),
        l.feeType?.name, l.rateBasis, SblTime.formatTs(l.approvedAt), SblTime.formatTs(l.expiresAt),
        SblTime.formatTs(l.convertedAt), l.contractId, l.assuranceType?.name, l.assuranceId,
        l.assuranceBasis?.name, l.sblaId, l.locateId
    )

    fun find(id: String): LocateRequest? =
        db.queryOne("SELECT * FROM locate_request WHERE locate_id=?", id) { map(it) }

    /** Sum of committed (APPROVED/PARTIAL, not yet converted/expired) quantity for a stock. */
    fun committedQty(stockCode: String): Long =
        db.queryLong("""SELECT COALESCE(SUM(approved_qty),0) FROM locate_request
                        WHERE stock_code=? AND status IN ('APPROVED','PARTIAL')""", stockCode)

    fun openCommitments(): List<LocateRequest> =
        db.query("SELECT * FROM locate_request WHERE status IN ('APPROVED','PARTIAL')") { map(it) }

    fun byStatus(vararg statuses: LocateStatus): List<LocateRequest> {
        val placeholders = statuses.joinToString(",") { "?" }
        return db.query("SELECT * FROM locate_request WHERE status IN ($placeholders)",
            *statuses.map { it.name }.toTypedArray()) { map(it) }
    }

    private fun map(rs: ResultSet) = LocateRequest(
        locateId = rs.getString("locate_id"),
        status = LocateStatus.valueOf(rs.getString("status")),
        borrowerId = rs.getString("borrower_id"),
        requestingSystem = rs.getString("requesting_system"),
        requestRef = rs.getString("request_ref"),
        stockCode = rs.getString("stock_code"),
        requestedQty = rs.getLong("requested_qty"),
        approvedQty = rs.getString("approved_qty")?.toLong(),
        rejectedQty = rs.getString("rejected_qty")?.toLong(),
        rejectReason = rs.getString("reject_reason"),
        feeRate = Money.fromDb(rs.getString("fee_rate")),
        feeType = rs.getString("fee_type")?.let { FeeType.valueOf(it) },
        rateBasis = rs.getString("rate_basis"),
        requestedAt = SblTime.parseTs(rs.getString("requested_at"))!!,
        approvedAt = SblTime.parseTs(rs.getString("approved_at")),
        expiresAt = SblTime.parseTs(rs.getString("expires_at")),
        convertedAt = SblTime.parseTs(rs.getString("converted_at")),
        contractId = rs.getString("contract_id"),
        assuranceType = rs.getString("assurance_type")?.let { AssuranceType.valueOf(it) },
        assuranceId = rs.getString("assurance_id"),
        assuranceBasis = rs.getString("assurance_basis")?.let { AssuranceBasis.valueOf(it) },
        sblaId = rs.getString("sbla_id"),
        designatedSecurity = rs.boolean("designated_security"),
        tickRuleExempt = rs.boolean("tick_rule_exempt"),
        createdAt = SblTime.parseTs(rs.getString("created_at"))!!
    )
}

class DocumentaryAssuranceRepo(private val db: Database) {
    fun insert(a: DocumentaryAssurance) = db.update(
        """INSERT INTO documentary_assurance (assurance_id,assurance_type,lender_id,borrower_id,
           stock_code,quantity,assurance_basis,channel,created_ts,expires_at,retention_tier,
           retention_expiry) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""",
        a.assuranceId, a.assuranceType.name, a.lenderId, a.borrowerId, a.stockCode, a.quantity,
        a.assuranceBasis?.name, a.channel, SblTime.formatTs(a.createdTs),
        SblTime.formatTs(a.expiresAt), a.retentionTier.code, SblTime.formatDate(a.retentionExpiry)
    )

    fun find(id: String): DocumentaryAssurance? =
        db.queryOne("SELECT * FROM documentary_assurance WHERE assurance_id=?", id) { map(it) }

    fun all(): List<DocumentaryAssurance> =
        db.query("SELECT * FROM documentary_assurance ORDER BY created_ts") { map(it) }

    private fun map(rs: ResultSet) = DocumentaryAssurance(
        assuranceId = rs.getString("assurance_id"),
        assuranceType = AssuranceType.valueOf(rs.getString("assurance_type")),
        lenderId = rs.getString("lender_id"),
        borrowerId = rs.getString("borrower_id"),
        stockCode = rs.getString("stock_code"),
        quantity = rs.getLong("quantity"),
        assuranceBasis = rs.getString("assurance_basis")?.let { AssuranceBasis.valueOf(it) },
        channel = rs.getString("channel"),
        createdTs = SblTime.parseTs(rs.getString("created_ts"))!!,
        expiresAt = SblTime.parseTs(rs.getString("expires_at")),
        retentionTier = RetentionTier.fromCode(rs.getString("retention_tier")),
        retentionExpiry = SblTime.parseDate(rs.getString("retention_expiry"))!!
    )
}

class BlanketAssuranceRepo(private val db: Database) {
    fun insert(b: BlanketAssurance) = db.update(
        """INSERT INTO blanket_assurance (blanket_id,lender_id,borrower_id,stock_code,
           pool_qty_original,pool_qty_remaining,valid_from,valid_until,channel,tape_reference,
           status,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""",
        b.blanketId, b.lenderId, b.borrowerId, b.stockCode, b.poolQtyOriginal, b.poolQtyRemaining,
        SblTime.formatTs(b.validFrom), SblTime.formatTs(b.validUntil), b.channel, b.tapeReference,
        b.status, SblTime.formatTs(b.createdAt)
    )

    fun update(b: BlanketAssurance) = db.update(
        "UPDATE blanket_assurance SET pool_qty_remaining=?, status=? WHERE blanket_id=?",
        b.poolQtyRemaining, b.status, b.blanketId
    )

    fun findActive(borrowerId: String, stockCode: String): BlanketAssurance? =
        db.queryOne("""SELECT * FROM blanket_assurance WHERE borrower_id=? AND stock_code=?
                       AND status='ACTIVE' AND pool_qty_remaining>0
                       ORDER BY valid_until DESC LIMIT 1""", borrowerId, stockCode) { map(it) }

    private fun map(rs: ResultSet) = BlanketAssurance(
        blanketId = rs.getString("blanket_id"),
        lenderId = rs.getString("lender_id"),
        borrowerId = rs.getString("borrower_id"),
        stockCode = rs.getString("stock_code"),
        poolQtyOriginal = rs.getLong("pool_qty_original"),
        poolQtyRemaining = rs.getLong("pool_qty_remaining"),
        validFrom = SblTime.parseTs(rs.getString("valid_from"))!!,
        validUntil = SblTime.parseTs(rs.getString("valid_until"))!!,
        channel = rs.getString("channel"),
        tapeReference = rs.getString("tape_reference"),
        status = rs.getString("status"),
        createdAt = SblTime.parseTs(rs.getString("created_at"))!!
    )
}
