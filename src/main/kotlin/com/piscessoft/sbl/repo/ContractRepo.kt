package com.piscessoft.sbl.repo

import com.piscessoft.sbl.db.Database
import com.piscessoft.sbl.db.boolean
import com.piscessoft.sbl.model.*
import com.piscessoft.sbl.util.Money
import com.piscessoft.sbl.util.SblTime
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.LocalDate

class ContractRepo(private val db: Database) {
    fun insert(c: SblContract) = db.update(
        """INSERT INTO sbl_contract (contract_id,status,trade_type,transaction_model,lender_id,
           borrower_id,agent_id,stock_code,quantity,quantity_returned,quantity_outstanding,open_date,
           close_date,actual_close_date,fee_rate,fee_type,fee_rate_effective_date,floating_index,
           floating_spread_bps,collateral_ratio_required,is_short_sale_borrow,sbla_id,locate_id,
           assurance_id,assurance_basis,agent_split_pct,regulated_person_flag,regulated_person_deadline,
           regulated_person_deployed,regulated_person_purpose,potential_non_exempt,non_exempt_reason,
           ird_category,cumulative_fee_hkd,book_side,version,created_at,updated_at)
           VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
        c.contractId, c.status.name, c.tradeType.name, c.transactionModel.name, c.lenderId,
        c.borrowerId, c.agentId, c.stockCode, c.quantity, c.quantityReturned, c.quantityOutstanding,
        SblTime.formatDate(c.openDate), SblTime.formatDate(c.closeDate),
        SblTime.formatDate(c.actualCloseDate), Money.toDb(c.feeRate), c.feeType.name,
        SblTime.formatDate(c.feeRateEffectiveDate), c.floatingIndex?.name, c.floatingSpreadBps,
        Money.toDb(c.collateralRatioRequired), c.isShortSaleBorrow, c.sblaId, c.locateId,
        c.assuranceId, c.assuranceBasis?.name, Money.toDb(c.agentSplitPct), c.regulatedPersonFlag,
        SblTime.formatDate(c.regulatedPersonDeadline), c.regulatedPersonDeployed,
        c.regulatedPersonPurpose, c.potentialNonExempt, c.nonExemptReason, c.irdCategory,
        Money.toDb(c.cumulativeFeeHkd), c.bookSide.name, c.version, SblTime.formatTs(c.createdAt),
        SblTime.formatTs(c.updatedAt)
    )

    /** Optimistic-locked update: bumps version, fails if version drifted. */
    fun update(c: SblContract): Boolean {
        val rows = db.update(
            """UPDATE sbl_contract SET status=?, quantity=?, quantity_returned=?,
               quantity_outstanding=?, close_date=?, actual_close_date=?, fee_rate=?,
               fee_rate_effective_date=?, regulated_person_deployed=?, regulated_person_purpose=?,
               potential_non_exempt=?, non_exempt_reason=?, ird_category=?, cumulative_fee_hkd=?,
               version=version+1, updated_at=? WHERE contract_id=? AND version=?""",
            c.status.name, c.quantity, c.quantityReturned, c.quantityOutstanding,
            SblTime.formatDate(c.closeDate), SblTime.formatDate(c.actualCloseDate),
            Money.toDb(c.feeRate), SblTime.formatDate(c.feeRateEffectiveDate),
            c.regulatedPersonDeployed, c.regulatedPersonPurpose, c.potentialNonExempt,
            c.nonExemptReason, c.irdCategory, Money.toDb(c.cumulativeFeeHkd),
            SblTime.formatTs(SblTime.now()), c.contractId, c.version
        )
        if (rows == 1) c.version += 1
        return rows == 1
    }

    fun find(id: String): SblContract? =
        db.queryOne("SELECT * FROM sbl_contract WHERE contract_id=?", id) { map(it) }

    /** Persist a revaluation. Not a business change, so the optimistic-lock version is untouched. */
    fun updateMtm(c: SblContract) = db.update(
        """UPDATE sbl_contract SET mtm_price=?, mtm_fx_rate=?, mtm_value_hkd=?, last_mtm_date=?
           WHERE contract_id=?""",
        Money.toDb(c.mtmPrice), Money.toDb(c.mtmFxRate), Money.toDb(c.mtmValueHkd),
        SblTime.formatDate(c.lastMtmDate), c.contractId
    )

    /** Contracts whose OPEN leg has settled and that still have stock on loan. */
    fun active(): List<SblContract> =
        db.query("""SELECT * FROM sbl_contract WHERE status IN
                    ('ACTIVE','RECALLED','RETURN_PENDING','PARTIALLY_RETURNED','RERATED','ROLLED_OVER','CA_ADJUSTED')""") { map(it) }

    /** Active contracts plus contracts booked but awaiting OPEN-leg settlement (CONFIRMED). */
    fun booked(): List<SblContract> =
        db.query("""SELECT * FROM sbl_contract WHERE status IN
                    ('CONFIRMED','ACTIVE','RECALLED','RETURN_PENDING','PARTIALLY_RETURNED','RERATED','ROLLED_OVER','CA_ADJUSTED')""") { map(it) }

    fun bookedForStock(stockCode: String): List<SblContract> =
        booked().filter { it.stockCode == stockCode }

    fun byStatus(vararg statuses: ContractStatus): List<SblContract> {
        val ph = statuses.joinToString(",") { "?" }
        return db.query("SELECT * FROM sbl_contract WHERE status IN ($ph)",
            *statuses.map { it.name }.toTypedArray()) { map(it) }
    }

    fun activeForStock(stockCode: String): List<SblContract> =
        active().filter { it.stockCode == stockCode }

    fun all(): List<SblContract> = db.query("SELECT * FROM sbl_contract") { map(it) }

    /** On-loan (loan-out) quantity for a stock, including booked contracts still pending delivery. */
    fun onLoanQty(stockCode: String): Long =
        bookedForStock(stockCode).filter { it.bookSide == BookSide.LOAN_OUT }.sumOf { it.quantityOutstanding }

    /** Street borrow-ins for a stock that are booked or active. */
    fun bookedBorrowIns(stockCode: String): List<SblContract> =
        bookedForStock(stockCode).filter { it.bookSide == BookSide.BORROW_IN }

    private fun map(rs: ResultSet) = SblContract(
        contractId = rs.getString("contract_id"),
        status = ContractStatus.valueOf(rs.getString("status")),
        tradeType = TradeType.valueOf(rs.getString("trade_type")),
        transactionModel = TransactionModel.valueOf(rs.getString("transaction_model")),
        lenderId = rs.getString("lender_id"),
        borrowerId = rs.getString("borrower_id"),
        agentId = rs.getString("agent_id"),
        stockCode = rs.getString("stock_code"),
        quantity = rs.getLong("quantity"),
        quantityReturned = rs.getLong("quantity_returned"),
        quantityOutstanding = rs.getLong("quantity_outstanding"),
        openDate = SblTime.parseDate(rs.getString("open_date"))!!,
        closeDate = SblTime.parseDate(rs.getString("close_date")),
        actualCloseDate = SblTime.parseDate(rs.getString("actual_close_date")),
        feeRate = BigDecimal(rs.getString("fee_rate")),
        feeType = FeeType.valueOf(rs.getString("fee_type")),
        feeRateEffectiveDate = SblTime.parseDate(rs.getString("fee_rate_effective_date")),
        floatingIndex = rs.getString("floating_index")?.let { FloatingIndex.valueOf(it) },
        floatingSpreadBps = rs.getString("floating_spread_bps")?.toInt(),
        collateralRatioRequired = BigDecimal(rs.getString("collateral_ratio_required")),
        isShortSaleBorrow = rs.boolean("is_short_sale_borrow"),
        sblaId = rs.getString("sbla_id"),
        locateId = rs.getString("locate_id"),
        assuranceId = rs.getString("assurance_id"),
        assuranceBasis = rs.getString("assurance_basis")?.let { AssuranceBasis.valueOf(it) },
        agentSplitPct = Money.fromDb(rs.getString("agent_split_pct")),
        regulatedPersonFlag = rs.boolean("regulated_person_flag"),
        regulatedPersonDeadline = SblTime.parseDate(rs.getString("regulated_person_deadline")),
        regulatedPersonDeployed = rs.getString("regulated_person_deployed")?.let { it == "1" || it.equals("true", true) },
        regulatedPersonPurpose = rs.getString("regulated_person_purpose"),
        potentialNonExempt = rs.boolean("potential_non_exempt"),
        nonExemptReason = rs.getString("non_exempt_reason"),
        irdCategory = rs.getInt("ird_category"),
        cumulativeFeeHkd = BigDecimal(rs.getString("cumulative_fee_hkd")),
        bookSide = BookSide.valueOf(rs.getString("book_side")),
        version = rs.getInt("version"),
        createdAt = SblTime.parseTs(rs.getString("created_at"))!!,
        updatedAt = SblTime.parseTs(rs.getString("updated_at"))!!,
        mtmPrice = Money.fromDb(rs.getString("mtm_price")),
        mtmFxRate = Money.fromDb(rs.getString("mtm_fx_rate")),
        mtmValueHkd = Money.fromDb(rs.getString("mtm_value_hkd")),
        lastMtmDate = SblTime.parseDate(rs.getString("last_mtm_date"))
    )
}

class LifecycleEventRepo(private val db: Database) {
    fun insert(e: LifecycleEvent) = db.update(
        """INSERT INTO lifecycle_event (event_id,contract_id,event_type,from_status,to_status,
           detail,event_ts) VALUES (?,?,?,?,?,?,?)""",
        e.eventId, e.contractId, e.eventType, e.fromStatus?.name, e.toStatus?.name, e.detail,
        SblTime.formatTs(e.eventTs)
    )

    fun forContract(contractId: String): List<LifecycleEvent> =
        db.query("SELECT * FROM lifecycle_event WHERE contract_id=? ORDER BY event_ts", contractId) {
            LifecycleEvent(
                eventId = it.getString("event_id"),
                contractId = it.getString("contract_id"),
                eventType = it.getString("event_type"),
                fromStatus = it.getString("from_status")?.let { s -> ContractStatus.valueOf(s) },
                toStatus = it.getString("to_status")?.let { s -> ContractStatus.valueOf(s) },
                detail = it.getString("detail"),
                eventTs = SblTime.parseTs(it.getString("event_ts"))!!
            )
        }
}

class ContractRateHistoryRepo(private val db: Database) {
    fun insert(h: ContractRateHistory) = db.update(
        """INSERT INTO contract_rate_history (rate_history_id,contract_id,fee_type,annual_rate,
           floating_index,floating_spread_bps,effective_from,effective_to,reason,created_at)
           VALUES (?,?,?,?,?,?,?,?,?,?)""",
        h.rateHistoryId, h.contractId, h.feeType.name, Money.toDb(h.annualRate),
        h.floatingIndex?.name, h.floatingSpreadBps, SblTime.formatDate(h.effectiveFrom),
        SblTime.formatDate(h.effectiveTo), h.reason, SblTime.formatTs(h.createdAt)
    )

    /** Close the currently-open rate row for a contract the day before [nextFrom] takes effect. */
    fun closeOpen(contractId: String, nextFrom: LocalDate) = db.update(
        """UPDATE contract_rate_history SET effective_to=?
           WHERE contract_id=? AND effective_to IS NULL""",
        SblTime.formatDate(nextFrom.minusDays(1)), contractId
    )

    fun forContract(contractId: String): List<ContractRateHistory> =
        db.query("""SELECT * FROM contract_rate_history WHERE contract_id=?
                    ORDER BY effective_from""", contractId) { map(it) }

    /** The rate in force on [date], i.e. effective_from <= date <= effective_to (or still open). */
    fun rateOn(contractId: String, date: LocalDate): ContractRateHistory? =
        forContract(contractId).lastOrNull { h ->
            !date.isBefore(h.effectiveFrom) && (h.effectiveTo == null || !date.isAfter(h.effectiveTo))
        }

    private fun map(rs: ResultSet) = ContractRateHistory(
        rateHistoryId = rs.getString("rate_history_id"),
        contractId = rs.getString("contract_id"),
        feeType = FeeType.valueOf(rs.getString("fee_type")),
        annualRate = Money.fromDb(rs.getString("annual_rate")),
        floatingIndex = rs.getString("floating_index")?.let { FloatingIndex.valueOf(it) },
        floatingSpreadBps = rs.getString("floating_spread_bps")?.toInt(),
        effectiveFrom = SblTime.parseDate(rs.getString("effective_from"))!!,
        effectiveTo = SblTime.parseDate(rs.getString("effective_to")),
        reason = rs.getString("reason"),
        createdAt = SblTime.parseTs(rs.getString("created_at"))!!
    )
}

class RecallRepo(private val db: Database) {
    fun insert(r: RecallNotice) = db.update(
        """INSERT INTO recall_notice (recall_id,contract_id,recall_qty,recall_reason,
           notice_served_at,return_deadline,status,returned_qty,returned_at,created_at)
           VALUES (?,?,?,?,?,?,?,?,?,?)""",
        r.recallId, r.contractId, r.recallQty, r.recallReason.name,
        SblTime.formatTs(r.noticeServedAt), SblTime.formatDate(r.returnDeadline), r.status.name,
        r.returnedQty, SblTime.formatTs(r.returnedAt), SblTime.formatTs(r.createdAt)
    )

    fun update(r: RecallNotice) = db.update(
        "UPDATE recall_notice SET status=?, returned_qty=?, returned_at=? WHERE recall_id=?",
        r.status.name, r.returnedQty, SblTime.formatTs(r.returnedAt), r.recallId
    )

    fun find(id: String): RecallNotice? =
        db.queryOne("SELECT * FROM recall_notice WHERE recall_id=?", id) { map(it) }

    fun open(): List<RecallNotice> =
        db.query("""SELECT * FROM recall_notice WHERE status IN ('PENDING','PARTIALLY_RETURNED','OVERDUE')
                    ORDER BY return_deadline""") { map(it) }

    fun forContract(contractId: String): List<RecallNotice> =
        db.query("SELECT * FROM recall_notice WHERE contract_id=?", contractId) { map(it) }

    private fun map(rs: ResultSet) = RecallNotice(
        recallId = rs.getString("recall_id"),
        contractId = rs.getString("contract_id"),
        recallQty = rs.getLong("recall_qty"),
        recallReason = RecallReason.valueOf(rs.getString("recall_reason")),
        noticeServedAt = SblTime.parseTs(rs.getString("notice_served_at"))!!,
        returnDeadline = SblTime.parseDate(rs.getString("return_deadline"))!!,
        status = RecallStatus.valueOf(rs.getString("status")),
        returnedQty = rs.getLong("returned_qty"),
        returnedAt = SblTime.parseTs(rs.getString("returned_at")),
        createdAt = SblTime.parseTs(rs.getString("created_at"))!!
    )
}

class SettlementLegRepo(private val db: Database) {
    fun insert(l: SettlementLeg) = db.update(
        """INSERT INTO settlement_leg (leg_id,contract_id,leg_type,quantity,trade_date,
           intended_settle_date,actual_settle_date,status,external_ref,fail_reason,created_at,updated_at)
           VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""",
        l.legId, l.contractId, l.legType.name, l.quantity, SblTime.formatDate(l.tradeDate),
        SblTime.formatDate(l.intendedSettleDate), SblTime.formatDate(l.actualSettleDate),
        l.status.name, l.externalRef, l.failReason, SblTime.formatTs(l.createdAt),
        SblTime.formatTs(l.updatedAt)
    )

    fun update(l: SettlementLeg) = db.update(
        """UPDATE settlement_leg SET status=?, actual_settle_date=?, external_ref=?, fail_reason=?,
           updated_at=? WHERE leg_id=?""",
        l.status.name, SblTime.formatDate(l.actualSettleDate), l.externalRef, l.failReason,
        SblTime.formatTs(l.updatedAt), l.legId
    )

    fun find(id: String): SettlementLeg? =
        db.queryOne("SELECT * FROM settlement_leg WHERE leg_id=?", id) { map(it) }

    fun findByExternalRef(ref: String): SettlementLeg? =
        db.queryOne("SELECT * FROM settlement_leg WHERE external_ref=?", ref) { map(it) }

    fun forContract(contractId: String): List<SettlementLeg> =
        db.query("SELECT * FROM settlement_leg WHERE contract_id=? ORDER BY created_at, leg_id", contractId) { map(it) }

    /** The contract's OPEN (delivery) leg, or null for legacy contracts booked before legs existed. */
    fun openLeg(contractId: String): SettlementLeg? =
        db.queryOne("SELECT * FROM settlement_leg WHERE contract_id=? AND leg_type='OPEN'", contractId) { map(it) }

    /** Legs not yet settled (PENDING or FAILED), oldest intended date first. */
    fun unsettled(): List<SettlementLeg> =
        db.query("""SELECT * FROM settlement_leg WHERE status IN ('PENDING','FAILED')
                    ORDER BY intended_settle_date, created_at""") { map(it) }

    fun list(status: LegStatus? = null, intendedOn: LocalDate? = null): List<SettlementLeg> =
        db.query("""SELECT * FROM settlement_leg ORDER BY intended_settle_date, created_at""") { map(it) }
            .filter { (status == null || it.status == status) && (intendedOn == null || it.intendedSettleDate == intendedOn) }

    /** Quantity instructed for return but not yet settled. */
    fun pendingReturnQty(contractId: String): Long =
        db.queryLong("""SELECT COALESCE(SUM(quantity),0) FROM settlement_leg
                        WHERE contract_id=? AND leg_type='RETURN' AND status IN ('PENDING','FAILED')""", contractId)

    private fun map(rs: ResultSet) = SettlementLeg(
        legId = rs.getString("leg_id"),
        contractId = rs.getString("contract_id"),
        legType = LegType.valueOf(rs.getString("leg_type")),
        quantity = rs.getLong("quantity"),
        tradeDate = SblTime.parseDate(rs.getString("trade_date"))!!,
        intendedSettleDate = SblTime.parseDate(rs.getString("intended_settle_date"))!!,
        status = LegStatus.valueOf(rs.getString("status")),
        actualSettleDate = SblTime.parseDate(rs.getString("actual_settle_date")),
        externalRef = rs.getString("external_ref"),
        failReason = rs.getString("fail_reason"),
        createdAt = SblTime.parseTs(rs.getString("created_at"))!!,
        updatedAt = SblTime.parseTs(rs.getString("updated_at"))!!
    )
}
