package com.piscessoft.sbl.repo

import com.piscessoft.sbl.db.Database
import com.piscessoft.sbl.db.boolean
import com.piscessoft.sbl.model.*
import com.piscessoft.sbl.util.Money
import com.piscessoft.sbl.util.SblTime
import java.math.BigDecimal
import java.sql.ResultSet

class ShortPositionReportRepo(private val db: Database) {
    fun insert(r: ShortPositionReport) = db.update(
        """INSERT INTO short_position_report (report_id,report_date,stock_code,stock_name,
           net_short_shares,net_short_value_hkd,reportable,submission_id,created_at)
           VALUES (?,?,?,?,?,?,?,?,?)""",
        r.reportId, SblTime.formatDate(r.reportDate), r.stockCode, r.stockName, r.netShortShares,
        Money.toDb(r.netShortValueHkd), r.reportable, r.submissionId, SblTime.formatTs(r.createdAt)
    )

    fun forDate(date: java.time.LocalDate): List<ShortPositionReport> =
        db.query("SELECT * FROM short_position_report WHERE report_date=?",
            SblTime.formatDate(date)) { map(it) }

    private fun map(rs: ResultSet) = ShortPositionReport(
        reportId = rs.getString("report_id"),
        reportDate = SblTime.parseDate(rs.getString("report_date"))!!,
        stockCode = rs.getString("stock_code"),
        stockName = rs.getString("stock_name"),
        netShortShares = rs.getLong("net_short_shares"),
        netShortValueHkd = BigDecimal(rs.getString("net_short_value_hkd")),
        reportable = rs.boolean("reportable"),
        submissionId = rs.getString("submission_id"),
        createdAt = SblTime.parseTs(rs.getString("created_at"))!!
    )
}

class SprsSubmissionRepo(private val db: Database) {
    fun insert(s: SprsSubmission) = db.update(
        """INSERT INTO sprs_submission (submission_id,report_date,frequency,filename,row_count,
           submission_ref,status,created_at) VALUES (?,?,?,?,?,?,?,?)""",
        s.submissionId, SblTime.formatDate(s.reportDate), s.frequency, s.filename, s.rowCount,
        s.submissionRef, s.status, SblTime.formatTs(s.createdAt)
    )

    fun find(id: String): SprsSubmission? =
        db.queryOne("SELECT * FROM sprs_submission WHERE submission_id=?", id) {
            SprsSubmission(
                submissionId = it.getString("submission_id"),
                reportDate = SblTime.parseDate(it.getString("report_date"))!!,
                frequency = it.getString("frequency"),
                filename = it.getString("filename"),
                rowCount = it.getInt("row_count"),
                submissionRef = it.getString("submission_ref"),
                status = it.getString("status"),
                createdAt = SblTime.parseTs(it.getString("created_at"))!!
            )
        }
}

class IrdRepo(private val db: Database) {
    fun insert(t: IrdSbulTransaction) = db.update(
        """INSERT INTO ird_sbul_transaction (entry_id,sbla_id,contract_id,tax_year,category,
           stampable,reason,created_at) VALUES (?,?,?,?,?,?,?,?)""",
        t.entryId, t.sblaId, t.contractId, t.taxYear, t.category, t.stampable, t.reason,
        SblTime.formatTs(t.createdAt)
    )

    fun forYear(year: Int): List<IrdSbulTransaction> =
        db.query("SELECT * FROM ird_sbul_transaction WHERE tax_year=?", year) {
            IrdSbulTransaction(
                entryId = it.getString("entry_id"),
                sblaId = it.getString("sbla_id"),
                contractId = it.getString("contract_id"),
                taxYear = it.getInt("tax_year"),
                category = it.getInt("category"),
                stampable = it.boolean("stampable"),
                reason = it.getString("reason"),
                createdAt = SblTime.parseTs(it.getString("created_at"))!!
            )
        }
}

class Cap571xRepo(private val db: Database) {
    fun insert(d: Cap571xDisclosure) = db.update(
        """INSERT INTO cap571x_disclosure (disclosure_id,person_id,contract_id,event_type,event_date,
           stock_code,quantity,prescribed_purpose,disclosure_deadline,status,created_at)
           VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
        d.disclosureId, d.personId, d.contractId, d.eventType, SblTime.formatDate(d.eventDate),
        d.stockCode, d.quantity, d.prescribedPurpose, SblTime.formatDate(d.disclosureDeadline),
        d.status, SblTime.formatTs(d.createdAt)
    )

    fun all(): List<Cap571xDisclosure> =
        db.query("SELECT * FROM cap571x_disclosure ORDER BY event_date") { map(it) }

    fun forContract(contractId: String): List<Cap571xDisclosure> =
        db.query("SELECT * FROM cap571x_disclosure WHERE contract_id=?", contractId) { map(it) }

    private fun map(rs: ResultSet) = Cap571xDisclosure(
        disclosureId = rs.getString("disclosure_id"),
        personId = rs.getString("person_id"),
        contractId = rs.getString("contract_id"),
        eventType = rs.getString("event_type"),
        eventDate = SblTime.parseDate(rs.getString("event_date"))!!,
        stockCode = rs.getString("stock_code"),
        quantity = rs.getLong("quantity"),
        prescribedPurpose = rs.getString("prescribed_purpose"),
        disclosureDeadline = SblTime.parseDate(rs.getString("disclosure_deadline"))!!,
        status = rs.getString("status"),
        createdAt = SblTime.parseTs(rs.getString("created_at"))!!
    )
}
