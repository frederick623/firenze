package com.piscessoft.sbl.repo

import com.piscessoft.sbl.db.Database
import com.piscessoft.sbl.model.*
import com.piscessoft.sbl.util.Money
import com.piscessoft.sbl.util.SblTime
import java.math.BigDecimal
import java.sql.ResultSet

class FeeAccrualRepo(private val db: Database) {
    fun insert(a: FeeAccrual) = db.update(
        """INSERT INTO fee_accrual (accrual_id,contract_id,accrual_date,closing_price,qty_outstanding,
           annual_rate,daily_fee_hkd,cumulative_fee_hkd,index_rate,spread_bps,settlement_status,
           settlement_batch_id,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""",
        a.accrualId, a.contractId, SblTime.formatDate(a.accrualDate), Money.toDb(a.closingPrice),
        a.qtyOutstanding, Money.toDb(a.annualRate), Money.toDb(a.dailyFeeHkd),
        Money.toDb(a.cumulativeFeeHkd), Money.toDb(a.indexRate), a.spreadBps,
        a.settlementStatus.name, a.settlementBatchId, SblTime.formatTs(a.createdAt)
    )

    fun existsFor(contractId: String, date: java.time.LocalDate): Boolean =
        db.queryLong("SELECT COUNT(*) FROM fee_accrual WHERE contract_id=? AND accrual_date=?",
            contractId, SblTime.formatDate(date)) > 0

    fun unsettledFor(counterpartyRole: String): List<FeeAccrual> =
        db.query("SELECT * FROM fee_accrual WHERE settlement_status='ACCRUED'") { map(it) }

    fun forContract(contractId: String): List<FeeAccrual> =
        db.query("SELECT * FROM fee_accrual WHERE contract_id=? ORDER BY accrual_date", contractId) { map(it) }

    fun markSettled(accrualIds: List<String>, batchId: String) {
        accrualIds.forEach {
            db.update("UPDATE fee_accrual SET settlement_status='SETTLED', settlement_batch_id=? WHERE accrual_id=?",
                batchId, it)
        }
    }

    private fun map(rs: ResultSet) = FeeAccrual(
        accrualId = rs.getString("accrual_id"),
        contractId = rs.getString("contract_id"),
        accrualDate = SblTime.parseDate(rs.getString("accrual_date"))!!,
        closingPrice = BigDecimal(rs.getString("closing_price")),
        qtyOutstanding = rs.getLong("qty_outstanding"),
        annualRate = BigDecimal(rs.getString("annual_rate")),
        dailyFeeHkd = BigDecimal(rs.getString("daily_fee_hkd")),
        cumulativeFeeHkd = BigDecimal(rs.getString("cumulative_fee_hkd")),
        indexRate = Money.fromDb(rs.getString("index_rate")),
        spreadBps = rs.getString("spread_bps")?.toInt(),
        settlementStatus = FeeSettlementStatus.valueOf(rs.getString("settlement_status")),
        settlementBatchId = rs.getString("settlement_batch_id"),
        createdAt = SblTime.parseTs(rs.getString("created_at"))!!
    )
}

class FeeSplitRepo(private val db: Database) {
    fun insert(s: FeeSplit) = db.update(
        """INSERT INTO fee_split (split_id,accrual_id,recipient_id,recipient_role,split_pct,
           split_amount_hkd) VALUES (?,?,?,?,?,?)""",
        s.splitId, s.accrualId, s.recipientId, s.recipientRole, Money.toDb(s.splitPct),
        Money.toDb(s.splitAmountHkd)
    )

    fun forAccrual(accrualId: String): List<FeeSplit> =
        db.query("SELECT * FROM fee_split WHERE accrual_id=?", accrualId) {
            FeeSplit(
                splitId = it.getString("split_id"),
                accrualId = it.getString("accrual_id"),
                recipientId = it.getString("recipient_id"),
                recipientRole = it.getString("recipient_role"),
                splitPct = BigDecimal(it.getString("split_pct")),
                splitAmountHkd = BigDecimal(it.getString("split_amount_hkd"))
            )
        }
}

class FeeStatementRepo(private val db: Database) {
    fun insert(s: FeeStatement) = db.update(
        """INSERT INTO fee_statement (statement_id,counterparty_id,period_from,period_to,
           total_fee_hkd,total_rebate_hkd,net_amount_hkd,status,sent_at,agreed_at,dispute_reason,created_at)
           VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""",
        s.statementId, s.counterpartyId, SblTime.formatDate(s.periodFrom),
        SblTime.formatDate(s.periodTo), Money.toDb(s.totalFeeHkd), Money.toDb(s.totalRebateHkd),
        Money.toDb(s.netAmountHkd), s.status.name,
        SblTime.formatTs(s.sentAt), SblTime.formatTs(s.agreedAt), s.disputeReason,
        SblTime.formatTs(s.createdAt)
    )

    fun update(s: FeeStatement) = db.update(
        "UPDATE fee_statement SET status=?, sent_at=?, agreed_at=?, dispute_reason=? WHERE statement_id=?",
        s.status.name, SblTime.formatTs(s.sentAt), SblTime.formatTs(s.agreedAt), s.disputeReason,
        s.statementId
    )

    fun find(id: String): FeeStatement? =
        db.queryOne("SELECT * FROM fee_statement WHERE statement_id=?", id) { map(it) }

    private fun map(rs: ResultSet): FeeStatement {
        val fee = BigDecimal(rs.getString("total_fee_hkd"))
        val rebate = Money.fromDbOrZero(rs.getString("total_rebate_hkd"))
        return FeeStatement(
            statementId = rs.getString("statement_id"),
            counterpartyId = rs.getString("counterparty_id"),
            periodFrom = SblTime.parseDate(rs.getString("period_from"))!!,
            periodTo = SblTime.parseDate(rs.getString("period_to"))!!,
            totalFeeHkd = fee,
            totalRebateHkd = rebate,
            // Statements created before rebates existed have no stored net amount.
            netAmountHkd = Money.fromDb(rs.getString("net_amount_hkd")) ?: Money.money(fee.subtract(rebate)),
            status = StatementStatus.valueOf(rs.getString("status")),
            sentAt = SblTime.parseTs(rs.getString("sent_at")),
            agreedAt = SblTime.parseTs(rs.getString("agreed_at")),
            disputeReason = rs.getString("dispute_reason"),
            createdAt = SblTime.parseTs(rs.getString("created_at"))!!
        )
    }
}

class RebateAccrualRepo(private val db: Database) {
    fun insert(a: RebateAccrual) = db.update(
        """INSERT INTO rebate_accrual (accrual_id,counterparty_id,position_id,accrual_date,currency,
           cash_amount,fx_rate_to_hkd,benchmark,index_rate,spread_bps,rebate_rate,daily_rebate_hkd,
           settlement_status,settlement_batch_id,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
        a.accrualId, a.counterpartyId, a.positionId, SblTime.formatDate(a.accrualDate), a.currency,
        Money.toDb(a.cashAmount), Money.toDb(a.fxRateToHkd), a.benchmark.name, Money.toDb(a.indexRate),
        a.spreadBps, Money.toDb(a.rebateRate), Money.toDb(a.dailyRebateHkd), a.settlementStatus.name,
        a.settlementBatchId, SblTime.formatTs(a.createdAt)
    )

    fun existsFor(positionId: String, date: java.time.LocalDate): Boolean =
        db.queryLong("SELECT COUNT(*) FROM rebate_accrual WHERE position_id=? AND accrual_date=?",
            positionId, SblTime.formatDate(date)) > 0

    fun forCounterparty(counterpartyId: String): List<RebateAccrual> =
        db.query("SELECT * FROM rebate_accrual WHERE counterparty_id=? ORDER BY accrual_date, position_id",
            counterpartyId) { map(it) }

    /** Unsettled accruals for a counterparty within an inclusive date range. */
    fun billable(counterpartyId: String, from: java.time.LocalDate, to: java.time.LocalDate): List<RebateAccrual> =
        db.query("""SELECT * FROM rebate_accrual WHERE counterparty_id=? AND settlement_status='ACCRUED'
                    AND accrual_date>=? AND accrual_date<=? ORDER BY accrual_date, position_id""",
            counterpartyId, SblTime.formatDate(from), SblTime.formatDate(to)) { map(it) }

    fun markSettled(accrualIds: List<String>, batchId: String) {
        accrualIds.forEach {
            db.update("UPDATE rebate_accrual SET settlement_status='SETTLED', settlement_batch_id=? WHERE accrual_id=?",
                batchId, it)
        }
    }

    private fun map(rs: ResultSet) = RebateAccrual(
        accrualId = rs.getString("accrual_id"),
        counterpartyId = rs.getString("counterparty_id"),
        positionId = rs.getString("position_id"),
        accrualDate = SblTime.parseDate(rs.getString("accrual_date"))!!,
        currency = rs.getString("currency"),
        cashAmount = BigDecimal(rs.getString("cash_amount")),
        fxRateToHkd = BigDecimal(rs.getString("fx_rate_to_hkd")),
        benchmark = FloatingIndex.valueOf(rs.getString("benchmark")),
        indexRate = BigDecimal(rs.getString("index_rate")),
        spreadBps = rs.getInt("spread_bps"),
        rebateRate = BigDecimal(rs.getString("rebate_rate")),
        dailyRebateHkd = BigDecimal(rs.getString("daily_rebate_hkd")),
        settlementStatus = FeeSettlementStatus.valueOf(rs.getString("settlement_status")),
        settlementBatchId = rs.getString("settlement_batch_id"),
        createdAt = SblTime.parseTs(rs.getString("created_at"))!!
    )
}
