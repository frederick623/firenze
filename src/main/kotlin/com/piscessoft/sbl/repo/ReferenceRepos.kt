package com.piscessoft.sbl.repo

import com.piscessoft.sbl.db.Database
import com.piscessoft.sbl.db.boolean
import com.piscessoft.sbl.model.*
import com.piscessoft.sbl.util.Money
import com.piscessoft.sbl.util.SblTime
import java.math.BigDecimal
import java.sql.ResultSet

class CounterpartyRepo(private val db: Database) {
    fun insert(c: Counterparty) = db.update(
        """INSERT INTO counterparty (id,name,cp_type,tier,approved_borrower,credit_limit_hkd,
           fee_settlement_basis,regulated_person,created_at) VALUES (?,?,?,?,?,?,?,?,?)""",
        c.id, c.name, c.type.name, c.tier?.name, c.approvedBorrower, Money.toDb(c.creditLimitHkd),
        c.feeSettlementBasis.name, c.regulatedPerson, SblTime.formatTs(c.createdAt)
    )

    fun find(id: String): Counterparty? =
        db.queryOne("SELECT * FROM counterparty WHERE id=?", id) { map(it) }

    fun all(): List<Counterparty> = db.query("SELECT * FROM counterparty") { map(it) }

    private fun map(rs: ResultSet) = Counterparty(
        id = rs.getString("id"),
        name = rs.getString("name"),
        type = CounterpartyType.valueOf(rs.getString("cp_type")),
        tier = rs.getString("tier")?.let { BorrowerTier.valueOf(it) },
        approvedBorrower = rs.boolean("approved_borrower"),
        creditLimitHkd = Money.fromDb(rs.getString("credit_limit_hkd")),
        feeSettlementBasis = FeeSettlementBasis.valueOf(rs.getString("fee_settlement_basis")),
        regulatedPerson = rs.boolean("regulated_person"),
        createdAt = SblTime.parseTs(rs.getString("created_at"))!!
    )
}

class SblaRepo(private val db: Database) {
    fun insert(s: Sbla) = db.update(
        """INSERT INTO sbla (id,borrower_id,lender_id,sba_number,registered_with_ird,
           registered_at,valid_until,status,created_at) VALUES (?,?,?,?,?,?,?,?,?)""",
        s.id, s.borrowerId, s.lenderId, s.sbaNumber, s.registeredWithIrd,
        SblTime.formatTs(s.registeredAt), SblTime.formatDate(s.validUntil), s.status,
        SblTime.formatTs(s.createdAt)
    )

    fun find(id: String): Sbla? = db.queryOne("SELECT * FROM sbla WHERE id=?", id) { map(it) }

    fun forBorrower(borrowerId: String): List<Sbla> =
        db.query("SELECT * FROM sbla WHERE borrower_id=?", borrowerId) { map(it) }

    private fun map(rs: ResultSet) = Sbla(
        id = rs.getString("id"),
        borrowerId = rs.getString("borrower_id"),
        lenderId = rs.getString("lender_id"),
        sbaNumber = rs.getString("sba_number"),
        registeredWithIrd = rs.boolean("registered_with_ird"),
        registeredAt = SblTime.parseTs(rs.getString("registered_at"))!!,
        validUntil = SblTime.parseDate(rs.getString("valid_until")),
        status = rs.getString("status"),
        createdAt = SblTime.parseTs(rs.getString("created_at"))!!
    )
}

class DesignatedSecurityRepo(private val db: Database) {
    fun upsert(d: DesignatedSecurity) = db.update(
        """INSERT INTO designated_security (stock_code,stock_name,security_type,issued_shares,
           eligible,ca_restricted,updated_at) VALUES (?,?,?,?,?,?,?)
           ON CONFLICT(stock_code) DO UPDATE SET stock_name=excluded.stock_name,
           security_type=excluded.security_type, issued_shares=excluded.issued_shares,
           eligible=excluded.eligible, ca_restricted=excluded.ca_restricted,
           updated_at=excluded.updated_at""",
        d.stockCode, d.stockName, d.securityType.name, d.issuedShares,
        d.eligible, d.caRestricted, SblTime.formatTs(d.updatedAt)
    )

    fun find(stockCode: String): DesignatedSecurity? =
        db.queryOne("SELECT * FROM designated_security WHERE stock_code=?", stockCode) { map(it) }

    fun all(): List<DesignatedSecurity> = db.query("SELECT * FROM designated_security") { map(it) }

    fun setEligible(stockCode: String, eligible: Boolean) =
        db.update("UPDATE designated_security SET eligible=?, updated_at=? WHERE stock_code=?",
            eligible, SblTime.formatTs(SblTime.now()), stockCode)

    fun setCaRestricted(stockCode: String, restricted: Boolean) =
        db.update("UPDATE designated_security SET ca_restricted=?, updated_at=? WHERE stock_code=?",
            restricted, SblTime.formatTs(SblTime.now()), stockCode)

    private fun map(rs: ResultSet) = DesignatedSecurity(
        stockCode = rs.getString("stock_code"),
        stockName = rs.getString("stock_name"),
        securityType = SecurityType.valueOf(rs.getString("security_type")),
        issuedShares = rs.getLong("issued_shares"),
        eligible = rs.boolean("eligible"),
        caRestricted = rs.boolean("ca_restricted"),
        updatedAt = SblTime.parseTs(rs.getString("updated_at"))!!
    )
}

class CollateralTypeConfigRepo(private val db: Database) {
    fun find(type: String): CollateralTypeConfig? =
        db.queryOne("SELECT * FROM collateral_type_config WHERE collateral_type=?", type) { map(it) }

    fun all(): List<CollateralTypeConfig> =
        db.query("SELECT * FROM collateral_type_config") { map(it) }

    private fun map(rs: ResultSet) = CollateralTypeConfig(
        collateralType = rs.getString("collateral_type"),
        description = rs.getString("description"),
        currency = rs.getString("currency"),
        haircutPct = BigDecimal(rs.getString("haircut_pct")),
        eligibleGeneral = rs.boolean("eligible_general"),
        eligibleShortSale = rs.boolean("eligible_short_sale"),
        maxConcentration = Money.fromDb(rs.getString("max_concentration")),
        notes = rs.getString("notes")
    )
}

class RateScheduleRepo(private val db: Database) {
    fun insert(r: RateSchedule) = db.update(
        """INSERT INTO rate_schedule (schedule_id,stock_code,borrower_tier,fee_type,annual_rate,
           floating_index,floating_spread_bps,min_rate,effective_from,effective_to,priority,created_at)
           VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""",
        r.scheduleId, r.stockCode, r.borrowerTier?.name, r.feeType.name, Money.toDb(r.annualRate),
        r.floatingIndex, r.floatingSpreadBps, Money.toDb(r.minRate),
        SblTime.formatDate(r.effectiveFrom), SblTime.formatDate(r.effectiveTo), r.priority,
        SblTime.formatTs(r.createdAt)
    )

    fun all(): List<RateSchedule> =
        db.query("SELECT * FROM rate_schedule ORDER BY priority ASC") { map(it) }

    private fun map(rs: ResultSet) = RateSchedule(
        scheduleId = rs.getString("schedule_id"),
        stockCode = rs.getString("stock_code"),
        borrowerTier = rs.getString("borrower_tier")?.let { BorrowerTier.valueOf(it) },
        feeType = FeeType.valueOf(rs.getString("fee_type")),
        annualRate = Money.fromDb(rs.getString("annual_rate")),
        floatingIndex = rs.getString("floating_index"),
        floatingSpreadBps = rs.getString("floating_spread_bps")?.toInt(),
        minRate = Money.fromDb(rs.getString("min_rate")),
        effectiveFrom = SblTime.parseDate(rs.getString("effective_from"))!!,
        effectiveTo = SblTime.parseDate(rs.getString("effective_to")),
        priority = rs.getInt("priority"),
        createdAt = SblTime.parseTs(rs.getString("created_at"))!!
    )
}

/** Market data: closing prices, FX, floating index rates. */
class MarketDataRepo(private val db: Database) {
    fun putPrice(stockCode: String, date: java.time.LocalDate, price: BigDecimal) = db.update(
        """INSERT INTO price_snapshot (stock_code,price_date,closing_price) VALUES (?,?,?)
           ON CONFLICT(stock_code,price_date) DO UPDATE SET closing_price=excluded.closing_price""",
        stockCode, SblTime.formatDate(date), Money.toDb(price)
    )

    fun price(stockCode: String, date: java.time.LocalDate): BigDecimal? =
        db.queryOne("SELECT closing_price FROM price_snapshot WHERE stock_code=? AND price_date=?",
            stockCode, SblTime.formatDate(date)) { BigDecimal(it.getString(1)) }

    /** Latest price at or before [date]. */
    fun latestPrice(stockCode: String, date: java.time.LocalDate): BigDecimal? =
        db.queryOne("""SELECT closing_price FROM price_snapshot WHERE stock_code=? AND price_date<=?
                       ORDER BY price_date DESC LIMIT 1""",
            stockCode, SblTime.formatDate(date)) { BigDecimal(it.getString(1)) }

    fun putFx(currency: String, date: java.time.LocalDate, rate: BigDecimal) = db.update(
        """INSERT INTO fx_rate (currency,rate_date,rate_to_hkd) VALUES (?,?,?)
           ON CONFLICT(currency,rate_date) DO UPDATE SET rate_to_hkd=excluded.rate_to_hkd""",
        currency, SblTime.formatDate(date), Money.toDb(rate)
    )

    fun fx(currency: String, date: java.time.LocalDate): BigDecimal {
        if (currency == "HKD") return BigDecimal.ONE
        return db.queryOne("""SELECT rate_to_hkd FROM fx_rate WHERE currency=? AND rate_date<=?
                              ORDER BY rate_date DESC LIMIT 1""",
            currency, SblTime.formatDate(date)) { BigDecimal(it.getString(1)) } ?: BigDecimal.ONE
    }

    fun putIndex(index: String, date: java.time.LocalDate, rate: BigDecimal) = db.update(
        """INSERT INTO floating_index (index_name,rate_date,annual_rate) VALUES (?,?,?)
           ON CONFLICT(index_name,rate_date) DO UPDATE SET annual_rate=excluded.annual_rate""",
        index, SblTime.formatDate(date), Money.toDb(rate)
    )

    fun index(index: String, date: java.time.LocalDate): BigDecimal? =
        db.queryOne("""SELECT annual_rate FROM floating_index WHERE index_name=? AND rate_date<=?
                       ORDER BY rate_date DESC LIMIT 1""",
            index, SblTime.formatDate(date)) { BigDecimal(it.getString(1)) }
}
