package com.piscessoft.sbl.repo

import com.piscessoft.sbl.db.Database
import com.piscessoft.sbl.db.boolean
import com.piscessoft.sbl.model.*
import com.piscessoft.sbl.util.Money
import com.piscessoft.sbl.util.SblTime
import java.sql.ResultSet

class PositionRepo(private val db: Database) {
    fun insert(p: Position) = db.update(
        """INSERT INTO position (position_id,owner_id,stock_code,account_type,quantity,
           settlement_status,lendable,as_of_date,updated_at,source_system,source_ref)
           VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
        p.positionId, p.ownerId, p.stockCode, p.accountType.name, p.quantity,
        p.settlementStatus.name, p.lendable, SblTime.formatDate(p.asOfDate),
        SblTime.formatTs(p.updatedAt), p.sourceSystem, p.sourceRef
    )

    fun update(p: Position) = db.update(
        """UPDATE position SET quantity=?, settlement_status=?, lendable=?, account_type=?,
           as_of_date=?, updated_at=? WHERE position_id=?""",
        p.quantity, p.settlementStatus.name, p.lendable, p.accountType.name,
        SblTime.formatDate(p.asOfDate), SblTime.formatTs(p.updatedAt), p.positionId
    )

    fun find(id: String): Position? =
        db.queryOne("SELECT * FROM position WHERE position_id=?", id) { map(it) }

    /** Persist a revaluation (price, FX and HKD market value) without touching quantities. */
    fun updateMtm(p: Position) = db.update(
        """UPDATE position SET mtm_price=?, mtm_fx_rate=?, mtm_value_hkd=?, last_mtm_date=?
           WHERE position_id=?""",
        Money.toDb(p.mtmPrice), Money.toDb(p.mtmFxRate), Money.toDb(p.mtmValueHkd),
        SblTime.formatDate(p.lastMtmDate), p.positionId
    )

    fun forStock(stockCode: String): List<Position> =
        db.query("SELECT * FROM position WHERE stock_code=?", stockCode) { map(it) }

    fun forOwner(ownerId: String): List<Position> =
        db.query("SELECT * FROM position WHERE owner_id=?", ownerId) { map(it) }

    /** First position matching owner/stock/account_type (positions are aggregated per key). */
    fun findKey(ownerId: String, stockCode: String, accountType: AccountType): Position? =
        db.queryOne("SELECT * FROM position WHERE owner_id=? AND stock_code=? AND account_type=?",
            ownerId, stockCode, accountType.name) { map(it) }

    fun all(): List<Position> = db.query("SELECT * FROM position") { map(it) }

    fun distinctStocks(): List<String> =
        db.query("SELECT DISTINCT stock_code FROM position") { it.getString(1) }

    private fun map(rs: ResultSet) = Position(
        positionId = rs.getString("position_id"),
        ownerId = rs.getString("owner_id"),
        stockCode = rs.getString("stock_code"),
        accountType = AccountType.valueOf(rs.getString("account_type")),
        quantity = rs.getLong("quantity"),
        settlementStatus = SettlementStatus.valueOf(rs.getString("settlement_status")),
        lendable = rs.boolean("lendable"),
        asOfDate = SblTime.parseDate(rs.getString("as_of_date"))!!,
        updatedAt = SblTime.parseTs(rs.getString("updated_at"))!!,
        sourceSystem = rs.getString("source_system"),
        sourceRef = rs.getString("source_ref"),
        mtmPrice = Money.fromDb(rs.getString("mtm_price")),
        mtmFxRate = Money.fromDb(rs.getString("mtm_fx_rate")),
        mtmValueHkd = Money.fromDb(rs.getString("mtm_value_hkd")),
        lastMtmDate = SblTime.parseDate(rs.getString("last_mtm_date"))
    )
}

class LendablePoolSnapshotRepo(private val db: Database) {
    fun insert(s: LendablePoolSnapshot) = db.update(
        """INSERT INTO lendable_pool_snapshot (snapshot_id,stock_code,as_of_datetime,gross_lendable,
           locate_committed,on_loan,net_available,qty_proprietary,qty_client_disc,qty_lending_pool,
           qty_recalled,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""",
        s.snapshotId, s.stockCode, SblTime.formatTs(s.asOfDatetime), s.grossLendable,
        s.locateCommitted, s.onLoan, s.netAvailable, s.qtyProprietary, s.qtyClientDisc,
        s.qtyLendingPool, s.qtyRecalled, SblTime.formatTs(s.createdAt)
    )

    fun latest(stockCode: String): LendablePoolSnapshot? =
        db.queryOne("""SELECT * FROM lendable_pool_snapshot WHERE stock_code=?
                       ORDER BY as_of_datetime DESC LIMIT 1""", stockCode) { map(it) }

    private fun map(rs: ResultSet) = LendablePoolSnapshot(
        snapshotId = rs.getString("snapshot_id"),
        stockCode = rs.getString("stock_code"),
        asOfDatetime = SblTime.parseTs(rs.getString("as_of_datetime"))!!,
        grossLendable = rs.getLong("gross_lendable"),
        locateCommitted = rs.getLong("locate_committed"),
        onLoan = rs.getLong("on_loan"),
        netAvailable = rs.getLong("net_available"),
        qtyProprietary = rs.getLong("qty_proprietary"),
        qtyClientDisc = rs.getLong("qty_client_disc"),
        qtyLendingPool = rs.getLong("qty_lending_pool"),
        qtyRecalled = rs.getLong("qty_recalled"),
        createdAt = SblTime.parseTs(rs.getString("created_at"))!!
    )
}
