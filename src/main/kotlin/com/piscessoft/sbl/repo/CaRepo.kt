package com.piscessoft.sbl.repo

import com.piscessoft.sbl.db.Database
import com.piscessoft.sbl.db.boolean
import com.piscessoft.sbl.model.*
import com.piscessoft.sbl.util.Money
import com.piscessoft.sbl.util.SblTime
import java.sql.ResultSet

class CaEventRepo(private val db: Database) {
    fun insert(e: CaEvent) = db.update(
        """INSERT INTO ca_event (ca_event_id,stock_code,ca_type,ex_date,record_date,payment_date,
           adjustment_ratio,cash_amount,currency,description,source,processed,created_at)
           VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""",
        e.caEventId, e.stockCode, e.caType.name, SblTime.formatDate(e.exDate),
        SblTime.formatDate(e.recordDate), SblTime.formatDate(e.paymentDate),
        Money.toDb(e.adjustmentRatio), Money.toDb(e.cashAmount), e.currency, e.description,
        e.source, e.processed, SblTime.formatTs(e.createdAt)
    )

    fun markProcessed(id: String) =
        db.update("UPDATE ca_event SET processed=1 WHERE ca_event_id=?", id)

    fun unprocessed(): List<CaEvent> =
        db.query("SELECT * FROM ca_event WHERE processed=0 ORDER BY ex_date") { map(it) }

    fun find(id: String): CaEvent? =
        db.queryOne("SELECT * FROM ca_event WHERE ca_event_id=?", id) { map(it) }

    private fun map(rs: ResultSet) = CaEvent(
        caEventId = rs.getString("ca_event_id"),
        stockCode = rs.getString("stock_code"),
        caType = CaType.valueOf(rs.getString("ca_type")),
        exDate = SblTime.parseDate(rs.getString("ex_date")),
        recordDate = SblTime.parseDate(rs.getString("record_date")),
        paymentDate = SblTime.parseDate(rs.getString("payment_date")),
        adjustmentRatio = Money.fromDb(rs.getString("adjustment_ratio")),
        cashAmount = Money.fromDb(rs.getString("cash_amount")),
        currency = rs.getString("currency"),
        description = rs.getString("description"),
        source = rs.getString("source"),
        processed = rs.boolean("processed"),
        createdAt = SblTime.parseTs(rs.getString("created_at"))!!
    )
}

class CaImpactRepo(private val db: Database) {
    fun insert(i: CaContractImpact) = db.update(
        """INSERT INTO ca_contract_impact (impact_id,ca_event_id,contract_id,action_type,status,
           old_quantity,new_quantity,payment_amount_hkd,payment_due_date,payment_status,recall_id,
           notes,created_at,processed_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
        i.impactId, i.caEventId, i.contractId, i.actionType.name, i.status, i.oldQuantity,
        i.newQuantity, Money.toDb(i.paymentAmountHkd), SblTime.formatDate(i.paymentDueDate),
        i.paymentStatus, i.recallId, i.notes, SblTime.formatTs(i.createdAt),
        SblTime.formatTs(i.processedAt)
    )

    fun forEvent(caEventId: String): List<CaContractImpact> =
        db.query("SELECT * FROM ca_contract_impact WHERE ca_event_id=?", caEventId) { map(it) }

    fun all(): List<CaContractImpact> =
        db.query("SELECT * FROM ca_contract_impact ORDER BY created_at") { map(it) }

    private fun map(rs: ResultSet) = CaContractImpact(
        impactId = rs.getString("impact_id"),
        caEventId = rs.getString("ca_event_id"),
        contractId = rs.getString("contract_id"),
        actionType = CaActionType.valueOf(rs.getString("action_type")),
        status = rs.getString("status"),
        oldQuantity = rs.getString("old_quantity")?.toLong(),
        newQuantity = rs.getString("new_quantity")?.toLong(),
        paymentAmountHkd = Money.fromDb(rs.getString("payment_amount_hkd")),
        paymentDueDate = SblTime.parseDate(rs.getString("payment_due_date")),
        paymentStatus = rs.getString("payment_status"),
        recallId = rs.getString("recall_id"),
        notes = rs.getString("notes"),
        createdAt = SblTime.parseTs(rs.getString("created_at"))!!,
        processedAt = SblTime.parseTs(rs.getString("processed_at"))
    )
}
