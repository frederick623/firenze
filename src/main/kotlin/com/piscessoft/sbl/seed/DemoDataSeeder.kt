package com.piscessoft.sbl.seed

import com.piscessoft.sbl.app.DbBootstrap
import com.piscessoft.sbl.app.SblServices
import com.piscessoft.sbl.model.*
import com.piscessoft.sbl.util.Ids
import com.piscessoft.sbl.util.Money
import com.piscessoft.sbl.util.SblTime
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * Standalone tool that loads dummy/demo data into an SBL database. It is NOT run by the
 * system entry point; invoke it explicitly (seed.ps1 / seed.sh).
 *
 * Usage: DemoDataSeederKt [--scenario] [--db-connector ..] [--db-url ..] [--db-user ..] [--db-password ..]
 *   default      seeds reference data only (counterparties, SBLAs, securities, prices, rates, inventory)
 *   --scenario   additionally runs the multi-day demo scenario (locates, contracts, collateral, fees,
 *                corporate actions, regulatory reports)
 */
object DemoDataSeeder {

    /** Business date the demo data is anchored to (Monday). */
    val DEMO_DATE: LocalDate = LocalDate.of(2026, 1, 5)

    fun run(args: Array<String>) {
        val withScenario = "--scenario" in args
        DbBootstrap.open(args).use { db ->
            if (db.queryLong("SELECT COUNT(*) FROM counterparty") > 0L) {
                println("Database already contains counterparty data - demo seed skipped.")
                return
            }
            val services = SblServices(db)
            SblTime.freezeAt(DEMO_DATE)
            try {
                seedReferenceData(services, DEMO_DATE)
                println("Demo reference data seeded (business date $DEMO_DATE).")
                if (withScenario) DemoScenario(services).run(DEMO_DATE)
            } finally {
                SblTime.resetToSystem()
            }
        }
    }

    private fun seedReferenceData(s: SblServices, d0: LocalDate) {
        val cps = s.counterparties
        val sblas = s.sblas
        val ds = s.designatedSecurities
        val rates = s.rateSchedules
        val market = s.market
        val now = SblTime.now()

        cps.insert(Counterparty("L1", "XYZ Lending Desk", CounterpartyType.LENDER, createdAt = now))
        cps.insert(Counterparty("A1", "Prime Agent Ltd", CounterpartyType.AGENT, createdAt = now))
        cps.insert(Counterparty("B1", "Alpha Hedge Fund", CounterpartyType.BORROWER, BorrowerTier.TIER_1,
            approvedBorrower = true, creditLimitHkd = BigDecimal("500000000"), createdAt = now))
        cps.insert(Counterparty("B2", "Beta Securities (Regulated)", CounterpartyType.BORROWER, BorrowerTier.TIER_2,
            approvedBorrower = true, creditLimitHkd = BigDecimal("100000000"), regulatedPerson = true, createdAt = now))

        val regAt: Instant = d0.minusDays(30).atStartOfDay(SblTime.HKT).toInstant()
        sblas.insert(Sbla(Ids.newId(), "B1", "L1", "SBA-B1-001", true, regAt, d0.plusYears(1), createdAt = now))
        sblas.insert(Sbla(Ids.newId(), "B2", "L1", "SBA-B2-001", true, regAt, d0.plusYears(1), createdAt = now))

        ds.upsert(DesignatedSecurity("00005", "HSBC Holdings plc", SecurityType.EQTY, 20_000_000_000, updatedAt = now))
        ds.upsert(DesignatedSecurity("00700", "Tencent Holdings Ltd", SecurityType.EQTY, 9_500_000_000, updatedAt = now))
        ds.upsert(DesignatedSecurity("00941", "China Mobile Ltd", SecurityType.EQTY, 8_000_000_000, updatedAt = now))
        ds.upsert(DesignatedSecurity("02800", "Tracker Fund of HK", SecurityType.TRST, 2_000_000_000, updatedAt = now))

        market.putPrice("00005", d0, Money.of("39.50"))
        market.putPrice("00700", d0, Money.of("320.00"))
        market.putPrice("00941", d0, Money.of("52.00"))
        market.putPrice("02800", d0, Money.of("18.00"))
        market.putFx("USD", d0, Money.of("7.80"))
        market.putFx("CNY", d0, Money.of("1.07"))
        market.putIndex(FloatingIndex.HIBOR_3M, d0, Money.of("0.045"))

        // Rate schedules: default 0.5%, hot-stock 00700 2%, TIER_2 floating HIBOR+200bps.
        rates.insert(RateSchedule(Ids.newId(), null, null, FeeType.FIXED, Money.of("0.005"),
            null, null, null, d0.minusDays(30), null, priority = 100, createdAt = now))
        rates.insert(RateSchedule(Ids.newId(), "00700", null, FeeType.FIXED, Money.of("0.02"),
            null, null, null, d0.minusDays(30), null, priority = 10, createdAt = now))
        rates.insert(RateSchedule(Ids.newId(), null, BorrowerTier.TIER_2, FeeType.FLOATING, null,
            FloatingIndex.HIBOR_3M, 200, Money.of("0.01"), d0.minusDays(30), null, priority = 50, createdAt = now))

        // Lender inventory (settled, lendable).
        s.inventory.onPositionSettled("L1", "00005", AccountType.PROPRIETARY, 10_000_000)
        s.inventory.onPositionSettled("L1", "00700", AccountType.LENDING_POOL, 2_000_000)
        s.inventory.onPositionSettled("L1", "00941", AccountType.LENDING_POOL, 3_000_000)
        s.inventory.onPositionSettled("L1", "02800", AccountType.CLIENT_DISCRETIONARY, 5_000_000)
    }
}

fun main(args: Array<String>) {
    DemoDataSeeder.run(args)
}
