package com.piscessoft.sbl.app

import com.piscessoft.sbl.db.Database
import com.piscessoft.sbl.db.DbConnectionConfig
import com.piscessoft.sbl.db.DbConnector
import com.piscessoft.sbl.model.*
import com.piscessoft.sbl.repo.*
import com.piscessoft.sbl.service.*
import com.piscessoft.sbl.util.Ids
import com.piscessoft.sbl.util.Money
import com.piscessoft.sbl.util.SblTime
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * End-to-end demonstration of the SBL Core system.
 *
 * Seeds reference data, then drives a multi-day scenario exercising all eight modules:
 * locate -> contract -> collateral/MTM/margin call -> fees -> corporate actions ->
 * regulatory reporting, with every state change captured in the audit trail.
 */
object App {

    // Wiring
    private lateinit var db: Database
    private lateinit var audit: AuditService
    private lateinit var inventory: InventoryService
    private lateinit var locateSvc: LocateService
    private lateinit var contractSvc: ContractService
    private lateinit var collateralSvc: CollateralService
    private lateinit var feeSvc: FeeService
    private lateinit var caSvc: CorporateActionService
    private lateinit var reportingSvc: ReportingService
    private lateinit var market: MarketDataRepo
    private lateinit var contracts: ContractRepo

    fun run(args: Array<String> = emptyArray()) {
        db = openDatabase(args)

        wire()
        val d0 = LocalDate.of(2026, 1, 5) // Monday
        SblTime.freezeAt(d0)

        seedReferenceData(d0)
        banner("SBL CORE - HK Securities Borrowing & Lending")
        println("Business date frozen at $d0 (HKT)\n")

        // ---- Module 1 & 3: Locates -> Contracts ----
        banner("MODULE 1+3  Locate & Contract Opening")
        val c1 = borrow("B1", "00005", 1_000_000, lender = "L1")               // margin call + rights recall
        val c2 = borrow("B1", "00700", 1_000_000, lender = "L1",               // hot stock, dividend + split
            agent = "A1", agentSplit = BigDecimal("0.15"))
        val c3 = borrow("B2", "00941", 500_000, lender = "L1")                 // regulated person, floating rate
        val c4 = borrow("B1", "02800", 500_000, lender = "L1")                 // ETF, delisting termination
        // Demonstrate limit-driven partial fill on the hot stock for another borrower.
        demoLocate("B2", "00700", 3_000_000)

        // ---- Module 4: Post collateral ----
        banner("MODULE 4  Collateral Posting")
        postCash(c1, 45_000_000)
        postCash(c2, 340_000_000)
        postCash(c3, 28_000_000)
        postCash(c4, 10_000_000)

        // ---- Daily cycle: fees + MTM ----
        cycle(d0)
        advanceTo(d0.plusDays(1)); cycle(SblTime.today())

        // ---- Module 4: price shock -> margin call ----
        banner("MODULE 4  Price Shock -> Margin Call")
        advanceTo(d0.plusDays(2))
        market.putPrice("00005", SblTime.today(), Money.of("46.00"))
        val mtm = collateralSvc.runDailyMtm(SblTime.today())
        feeSvc.accrueDaily(SblTime.today())
        mtm.filter { it.marginCall != null }.forEach {
            println("  Margin call on ${it.contractId.take(8)}: coverage=${it.coverageRatio} deficit=${Money.format(it.marginCall!!.deficitHkd)} HKD")
            postCash(it.contractId, 6_000_000)
            val call = com.piscessoft.sbl.repo.MarginCallRepo(db).pendingForContract(it.contractId)
            if (call != null) collateralSvc.resolveMarginCall(call.callId, ResolutionMethod.CASH_TOPUP)
            println("  -> topped up & resolved via CASH_TOPUP")
        }

        // ---- Module 6: Corporate actions ----
        banner("MODULE 6  Corporate Actions")
        advanceTo(d0.plusDays(3))
        // Cash dividend on 00700 -> manufactured payment.
        caSvc.ingest(caEvent("00700", CaType.CASH_DIVIDEND, cash = "2.50", exDate = SblTime.today().plusDays(1),
            payDate = SblTime.today().plusDays(10)))
        // 2:1 stock split on 00700 -> quantity adjustment.
        caSvc.ingest(caEvent("00700", CaType.STOCK_SPLIT, ratio = "2.0", exDate = SblTime.today().plusDays(2)))
        // Rights issue on 00005 -> mandatory recall.
        caSvc.ingest(caEvent("00005", CaType.RIGHTS_ISSUE, exDate = SblTime.today().plusDays(3)))
        // Delisting of 02800 -> early termination.
        caSvc.ingest(caEvent("02800", CaType.DELISTING, exDate = SblTime.today().plusDays(1)))
        val impacts = caSvc.processAll()
        impacts.forEach { println("  ${it.actionType} on ${it.contractId.take(8)} - ${it.notes}") }

        // Return the recalled 00005 borrow.
        banner("MODULE 3  Recall Return")
        contractSvc.processReturn(c1, contracts.find(c1)!!.quantityOutstanding)
        println("  Contract ${c1.take(8)} returned & closed")

        // ---- Module 3: regulated-person deadline breach (Cap.571X) ----
        banner("MODULE 3+7  Regulated Person 5-Business-Day Timer")
        advanceTo(d0.plusDays(9))
        val flagged = contractSvc.checkRegulatedPersonDeadlines()
        flagged.forEach { println("  Deemed acquisition: ${it.contractId.take(8)} (${it.stockCode}) - ${it.nonExemptReason}") }

        // ---- Module 5: billing statement ----
        banner("MODULE 5  Fee Billing")
        cycle(SblTime.today())
        val stmt = feeSvc.generateStatement("B1", d0, SblTime.today())
        feeSvc.sendStatement(stmt.statementId)
        feeSvc.agreeStatement(stmt.statementId)
        feeSvc.settleStatement(stmt.statementId)
        println("  B1 statement ${Money.format(stmt.totalFeeHkd)} HKD - settled")

        // ---- Module 7: regulatory reporting ----
        banner("MODULE 7  Regulatory Reporting")
        reportingSvc.generateShortPositionReports(SblTime.today())
        val csvFiles = reportingSvc.generateSprsCsv(SblTime.today(), "SPRID12345", "XYZ HK Ltd")
        csvFiles.forEach { f ->
            println("  SPRS CSV: ${f.name}")
            f.readLines().forEach { println("    $it") }
        }
        reportingSvc.generateCap571xDisclosures()
        val sbul1 = reportingSvc.generateSbul1(2026)
        println("  IRD SBUL 1 (2026): ${sbul1.size} entries; non-exempt categories: " +
            sbul1.filter { it.category > 0 }.joinToString(", ") { "${it.contractId.take(8)}=cat${it.category}" })

        // ---- Dashboards ----
        printDashboards()

        banner("MODULE 8  Audit Trail")
        println("  Immutable audit records written: ${audit.total()}")

        db.close()
        SblTime.resetToSystem()
    }

    // ---- scenario helpers ----

    private data class DbCliArgs(
        val connector: String? = null,
        val url: String? = null,
        val user: String? = null,
        val password: String? = null
    )

    private fun openDatabase(args: Array<String>): Database {
        val cli = parseDbCliArgs(args)
        val connector = DbConnector.parse(cli.connector ?: System.getenv("SBL_DB_CONNECTOR"))
        val url = cli.url ?: System.getenv("SBL_DB_URL")
        val user = cli.user ?: System.getenv("SBL_DB_USER")
        val password = cli.password ?: System.getenv("SBL_DB_PASSWORD")

        val cfg = when {
            !url.isNullOrBlank() -> DbConnectionConfig(connector, url, user, password)
            connector == DbConnector.SQLITE -> {
                val dbFile = File("out/sbl-demo.db")
                dbFile.parentFile?.mkdirs()
                if (dbFile.exists()) dbFile.delete()
                DbConnectionConfig(connector, "jdbc:sqlite:${dbFile.path}")
            }
            else -> error(
                "JDBC URL is required for ${connector.id}. " +
                    "Provide --db-url or SBL_DB_URL."
            )
        }
        println("Using DB connector=${cfg.connector.id} url=${cfg.jdbcUrl}")
        return Database.open(cfg)
    }

    private fun parseDbCliArgs(args: Array<String>): DbCliArgs {
        var connector: String? = null
        var url: String? = null
        var user: String? = null
        var password: String? = null
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                arg.startsWith("--db-connector=") -> connector = arg.substringAfter("=")
                arg == "--db-connector" -> {
                    i += 1
                    connector = requireArgValue(args, i, "--db-connector")
                }
                arg.startsWith("--db-url=") -> url = arg.substringAfter("=")
                arg == "--db-url" -> {
                    i += 1
                    url = requireArgValue(args, i, "--db-url")
                }
                arg.startsWith("--db-user=") -> user = arg.substringAfter("=")
                arg == "--db-user" -> {
                    i += 1
                    user = requireArgValue(args, i, "--db-user")
                }
                arg.startsWith("--db-password=") -> password = arg.substringAfter("=")
                arg == "--db-password" -> {
                    i += 1
                    password = requireArgValue(args, i, "--db-password")
                }
            }
            i += 1
        }
        return DbCliArgs(connector, url, user, password)
    }

    private fun requireArgValue(args: Array<String>, index: Int, flag: String): String {
        if (index >= args.size) error("Missing value for $flag")
        return args[index]
    }

    private fun borrow(
        borrower: String, stock: String, qty: Long, lender: String,
        agent: String? = null, agentSplit: BigDecimal? = null
    ): String {
        val decision = locateSvc.requestLocate(borrower, stock, qty)
        println("  Locate $borrower $stock x$qty -> ${decision.outcome} (${decision.message})")
        val contract = contractSvc.openFromLocate(
            decision.locate.locateId, lender, agentId = agent, agentSplitPct = agentSplit
        )
        println("  Contract ${contract.contractId.take(8)} OPEN - ${stock} x${contract.quantity} @ ${contract.feeRate} (${contract.feeType})")
        return contract.contractId
    }

    private fun demoLocate(borrower: String, stock: String, qty: Long) {
        val d = locateSvc.requestLocate(borrower, stock, qty)
        println("  Locate $borrower $stock x$qty -> ${d.outcome} (${d.message})")
        if (d.outcome != LocateStatus.REJECTED) locateSvc.cancel(d.locate.locateId)
    }

    private fun postCash(contractId: String, amount: Long) {
        collateralSvc.postCollateral(contractId, "CASH_HKD", "HKD", BigDecimal(amount), BigDecimal.ONE)
    }

    private fun cycle(date: LocalDate) {
        feeSvc.accrueDaily(date)
        collateralSvc.runDailyMtm(date)
    }

    private fun advanceTo(date: LocalDate) {
        SblTime.freezeAt(date)
    }

    private fun caEvent(
        stock: String, type: CaType, cash: String? = null, ratio: String? = null,
        exDate: LocalDate? = null, payDate: LocalDate? = null
    ) = CaEvent(
        caEventId = Ids.newId(),
        stockCode = stock,
        caType = type,
        exDate = exDate,
        recordDate = exDate,
        paymentDate = payDate,
        adjustmentRatio = ratio?.let { BigDecimal(it) },
        cashAmount = cash?.let { Money.of(it) },
        currency = if (cash != null) "HKD" else null,
        description = type.name,
        createdAt = SblTime.now()
    )

    // ---- dashboards ----

    private fun printDashboards() {
        banner("MODULE 2  Lendable Pool Dashboard")
        for (stock in listOf("00005", "00700", "00941", "02800")) {
            val p = inventory.lendablePool(stock)
            println("  $stock  gross=${p.grossLendable}  committed=${p.locateCommitted}  onLoan=${p.onLoan}  net=${p.netAvailable}")
        }

        banner("Loan Book")
        contracts.all().sortedBy { it.stockCode }.forEach {
            println("  ${it.contractId.take(8)}  ${it.stockCode}  ${it.borrowerId}  qty=${it.quantityOutstanding}/${it.quantity}  ${it.status}  fee=${Money.format(it.cumulativeFeeHkd)}")
        }

        banner("Lender Daily Summary")
        reportingSvc.lenderDailySummary().forEach {
            println("  ${it.lenderId}  contracts=${it.openContracts}  onLoan=${Money.format(it.onLoanValueHkd)} HKD  fees=${Money.format(it.accruedFeeHkd)} HKD")
        }
    }

    // ---- seed ----

    private fun seedReferenceData(d0: LocalDate) {
        val cps = CounterpartyRepo(db)
        val sblas = SblaRepo(db)
        val ds = DesignatedSecurityRepo(db)
        val rates = RateScheduleRepo(db)
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
        market.putIndex("HIBOR_3M", d0, Money.of("0.045"))

        // Rate schedules: default 0.5%, hot-stock 00700 2%, TIER_2 floating HIBOR+200bps.
        rates.insert(RateSchedule(Ids.newId(), null, null, FeeType.FIXED, Money.of("0.005"),
            null, null, null, d0.minusDays(30), null, priority = 100, createdAt = now))
        rates.insert(RateSchedule(Ids.newId(), "00700", null, FeeType.FIXED, Money.of("0.02"),
            null, null, null, d0.minusDays(30), null, priority = 10, createdAt = now))
        rates.insert(RateSchedule(Ids.newId(), null, BorrowerTier.TIER_2, FeeType.FLOATING, null,
            "HIBOR_3M", 200, Money.of("0.01"), d0.minusDays(30), null, priority = 50, createdAt = now))

        // Lender inventory (settled, lendable).
        inventory.onPositionSettled("L1", "00005", AccountType.PROPRIETARY, 10_000_000)
        inventory.onPositionSettled("L1", "00700", AccountType.LENDING_POOL, 2_000_000)
        inventory.onPositionSettled("L1", "00941", AccountType.LENDING_POOL, 3_000_000)
        inventory.onPositionSettled("L1", "02800", AccountType.CLIENT_DISCRETIONARY, 5_000_000)
    }

    private fun wire() {
        val auditRepo = AuditRepo(db)
        audit = AuditService(auditRepo)
        market = MarketDataRepo(db)
        contracts = ContractRepo(db)

        val positionRepo = PositionRepo(db)
        val locateRepo = LocateRepo(db)
        val snapshotRepo = LendablePoolSnapshotRepo(db)
        val designatedRepo = DesignatedSecurityRepo(db)
        val counterpartyRepo = CounterpartyRepo(db)
        val sblaRepo = SblaRepo(db)
        val rateRepo = RateScheduleRepo(db)
        val assuranceRepo = DocumentaryAssuranceRepo(db)
        val eventRepo = LifecycleEventRepo(db)
        val recallRepo = RecallRepo(db)
        val rateHistoryRepo = ContractRateHistoryRepo(db)
        val collateralRepo = CollateralPositionRepo(db)
        val marginRepo = MarginCallRepo(db)
        val mtmRepo = MtmHistoryRepo(db)
        val configRepo = CollateralTypeConfigRepo(db)
        val accrualRepo = FeeAccrualRepo(db)
        val splitRepo = FeeSplitRepo(db)
        val statementRepo = FeeStatementRepo(db)
        val caEventRepo = CaEventRepo(db)
        val caImpactRepo = CaImpactRepo(db)
        val spReportRepo = ShortPositionReportRepo(db)
        val sprsRepo = SprsSubmissionRepo(db)
        val irdRepo = IrdRepo(db)
        val cap571xRepo = Cap571xRepo(db)

        inventory = InventoryService(positionRepo, locateRepo, contracts, snapshotRepo, designatedRepo, audit)
        locateSvc = LocateService(locateRepo, counterpartyRepo, sblaRepo, designatedRepo, rateRepo, market,
            assuranceRepo, contracts, inventory, audit)
        contractSvc = ContractService(contracts, eventRepo, recallRepo, rateHistoryRepo, counterpartyRepo,
            assuranceRepo, locateSvc, locateRepo, inventory, audit)
        collateralSvc = CollateralService(collateralRepo, marginRepo, mtmRepo, contracts, configRepo, market, audit)
        feeSvc = FeeService(accrualRepo, splitRepo, statementRepo, contracts, market, rateHistoryRepo, audit)
        caSvc = CorporateActionService(caEventRepo, caImpactRepo, contracts, contractSvc, market, audit)
        reportingSvc = ReportingService(contracts, designatedRepo, market, spReportRepo, sprsRepo, irdRepo,
            cap571xRepo, sblaRepo, recallRepo, eventRepo, accrualRepo, audit)
    }

    private fun banner(title: String) {
        println("\n" + "=".repeat(60))
        println("  $title")
        println("=".repeat(60))
    }
}

fun main(args: Array<String>) {
    App.run(args)
}
