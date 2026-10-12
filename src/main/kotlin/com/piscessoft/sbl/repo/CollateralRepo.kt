package com.piscessoft.sbl.repo

import com.piscessoft.sbl.db.Database
import com.piscessoft.sbl.db.boolean
import com.piscessoft.sbl.model.*
import com.piscessoft.sbl.util.Money
import com.piscessoft.sbl.util.SblTime
import java.math.BigDecimal
import java.sql.ResultSet

class CollateralPositionRepo(private val db: Database) {
    fun insert(p: CollateralPosition) = db.update(
        """INSERT INTO collateral_position (position_id,contract_id,collateral_type,asset_id,quantity,
           market_value_local,fx_rate_to_hkd,market_value_hkd,haircut_pct,adjusted_value_hkd,
           last_mtm_date,status,margin_call_id,created_at,updated_at)
           VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
        p.positionId, p.contractId, p.collateralType, p.assetId, Money.toDb(p.quantity),
        Money.toDb(p.marketValueLocal), Money.toDb(p.fxRateToHkd), Money.toDb(p.marketValueHkd),
        Money.toDb(p.haircutPct), Money.toDb(p.adjustedValueHkd), SblTime.formatDate(p.lastMtmDate),
        p.status.name, p.marginCallId, SblTime.formatTs(p.createdAt), SblTime.formatTs(p.updatedAt)
    )

    fun update(p: CollateralPosition) = db.update(
        """UPDATE collateral_position SET quantity=?, market_value_local=?, fx_rate_to_hkd=?,
           market_value_hkd=?, haircut_pct=?, adjusted_value_hkd=?, last_mtm_date=?, status=?,
           margin_call_id=?, updated_at=? WHERE position_id=?""",
        Money.toDb(p.quantity), Money.toDb(p.marketValueLocal), Money.toDb(p.fxRateToHkd),
        Money.toDb(p.marketValueHkd), Money.toDb(p.haircutPct), Money.toDb(p.adjustedValueHkd),
        SblTime.formatDate(p.lastMtmDate), p.status.name, p.marginCallId,
        SblTime.formatTs(p.updatedAt), p.positionId
    )

    fun find(id: String): CollateralPosition? =
        db.queryOne("SELECT * FROM collateral_position WHERE position_id=?", id) { map(it) }

    fun activeForContract(contractId: String): List<CollateralPosition> =
        db.query("SELECT * FROM collateral_position WHERE contract_id=? AND status='ACTIVE'",
            contractId) { map(it) }

    fun allForContract(contractId: String): List<CollateralPosition> =
        db.query("SELECT * FROM collateral_position WHERE contract_id=?", contractId) { map(it) }

    private fun map(rs: ResultSet) = CollateralPosition(
        positionId = rs.getString("position_id"),
        contractId = rs.getString("contract_id"),
        collateralType = rs.getString("collateral_type"),
        assetId = rs.getString("asset_id"),
        quantity = BigDecimal(rs.getString("quantity")),
        marketValueLocal = BigDecimal(rs.getString("market_value_local")),
        fxRateToHkd = BigDecimal(rs.getString("fx_rate_to_hkd")),
        marketValueHkd = BigDecimal(rs.getString("market_value_hkd")),
        haircutPct = BigDecimal(rs.getString("haircut_pct")),
        adjustedValueHkd = BigDecimal(rs.getString("adjusted_value_hkd")),
        lastMtmDate = SblTime.parseDate(rs.getString("last_mtm_date"))!!,
        status = CollateralStatus.valueOf(rs.getString("status")),
        marginCallId = rs.getString("margin_call_id"),
        createdAt = SblTime.parseTs(rs.getString("created_at"))!!,
        updatedAt = SblTime.parseTs(rs.getString("updated_at"))!!
    )
}

class MarginCallRepo(private val db: Database) {
    fun insert(m: MarginCall) = db.update(
        """INSERT INTO margin_call (call_id,contract_id,call_date,borrowed_value_hkd,
           required_collateral_hkd,current_collateral_hkd,deficit_hkd,coverage_ratio,deadline,status,
           resolved_at,resolution_method,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""",
        m.callId, m.contractId, SblTime.formatDate(m.callDate), Money.toDb(m.borrowedValueHkd),
        Money.toDb(m.requiredCollateralHkd), Money.toDb(m.currentCollateralHkd),
        Money.toDb(m.deficitHkd), Money.toDb(m.coverageRatio), SblTime.formatTs(m.deadline),
        m.status.name, SblTime.formatTs(m.resolvedAt), m.resolutionMethod?.name,
        SblTime.formatTs(m.createdAt)
    )

    fun update(m: MarginCall) = db.update(
        """UPDATE margin_call SET status=?, resolved_at=?, resolution_method=?,
           borrowed_value_hkd=?, required_collateral_hkd=?, current_collateral_hkd=?,
           deficit_hkd=?, coverage_ratio=? WHERE call_id=?""",
        m.status.name, SblTime.formatTs(m.resolvedAt), m.resolutionMethod?.name,
        Money.toDb(m.borrowedValueHkd), Money.toDb(m.requiredCollateralHkd),
        Money.toDb(m.currentCollateralHkd), Money.toDb(m.deficitHkd),
        Money.toDb(m.coverageRatio), m.callId
    )

    fun find(id: String): MarginCall? =
        db.queryOne("SELECT * FROM margin_call WHERE call_id=?", id) { map(it) }

    fun pending(): List<MarginCall> =
        db.query("SELECT * FROM margin_call WHERE status IN ('PENDING','ESCALATED') ORDER BY deadline") { map(it) }

    fun pendingForContract(contractId: String): MarginCall? =
        db.queryOne("""SELECT * FROM margin_call WHERE contract_id=? AND status IN ('PENDING','ESCALATED')
                       ORDER BY created_at DESC LIMIT 1""", contractId) { map(it) }

    private fun map(rs: ResultSet) = MarginCall(
        callId = rs.getString("call_id"),
        contractId = rs.getString("contract_id"),
        callDate = SblTime.parseDate(rs.getString("call_date"))!!,
        borrowedValueHkd = BigDecimal(rs.getString("borrowed_value_hkd")),
        requiredCollateralHkd = BigDecimal(rs.getString("required_collateral_hkd")),
        currentCollateralHkd = BigDecimal(rs.getString("current_collateral_hkd")),
        deficitHkd = BigDecimal(rs.getString("deficit_hkd")),
        coverageRatio = BigDecimal(rs.getString("coverage_ratio")),
        deadline = SblTime.parseTs(rs.getString("deadline"))!!,
        status = MarginCallStatus.valueOf(rs.getString("status")),
        resolvedAt = SblTime.parseTs(rs.getString("resolved_at")),
        resolutionMethod = rs.getString("resolution_method")?.let { ResolutionMethod.valueOf(it) },
        createdAt = SblTime.parseTs(rs.getString("created_at"))!!
    )
}

class MtmHistoryRepo(private val db: Database) {
    fun insert(h: CollateralMtmHistory) = db.update(
        """INSERT INTO collateral_mtm_history (history_id,contract_id,mtm_date,borrowed_value_hkd,
           required_collateral_hkd,actual_collateral_hkd,coverage_ratio,margin_call_triggered,
           closing_price,created_at) VALUES (?,?,?,?,?,?,?,?,?,?)""",
        h.historyId, h.contractId, SblTime.formatDate(h.mtmDate), Money.toDb(h.borrowedValueHkd),
        Money.toDb(h.requiredCollateralHkd), Money.toDb(h.actualCollateralHkd),
        Money.toDb(h.coverageRatio), h.marginCallTriggered, Money.toDb(h.closingPrice),
        SblTime.formatTs(h.createdAt)
    )

    fun forContract(contractId: String): List<CollateralMtmHistory> =
        db.query("SELECT * FROM collateral_mtm_history WHERE contract_id=? ORDER BY mtm_date",
            contractId) { map(it) }

    private fun map(rs: ResultSet) = CollateralMtmHistory(
        historyId = rs.getString("history_id"),
        contractId = rs.getString("contract_id"),
        mtmDate = SblTime.parseDate(rs.getString("mtm_date"))!!,
        borrowedValueHkd = BigDecimal(rs.getString("borrowed_value_hkd")),
        requiredCollateralHkd = BigDecimal(rs.getString("required_collateral_hkd")),
        actualCollateralHkd = BigDecimal(rs.getString("actual_collateral_hkd")),
        coverageRatio = BigDecimal(rs.getString("coverage_ratio")),
        marginCallTriggered = rs.boolean("margin_call_triggered"),
        closingPrice = BigDecimal(rs.getString("closing_price")),
        createdAt = SblTime.parseTs(rs.getString("created_at"))!!
    )
}
