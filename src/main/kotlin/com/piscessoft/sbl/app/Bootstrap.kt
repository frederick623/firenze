package com.piscessoft.sbl.app

import com.piscessoft.sbl.db.Database
import com.piscessoft.sbl.db.DbConnectionConfig
import com.piscessoft.sbl.db.DbConnector
import com.piscessoft.sbl.repo.*
import com.piscessoft.sbl.service.*
import java.io.File

/**
 * Opens the SBL database from CLI flags / environment variables. Shared by the system
 * entry point ([main]) and the separate demo data seeder.
 */
object DbBootstrap {

    const val DEFAULT_SQLITE_PATH = "out/sbl.db"

    private data class DbCliArgs(
        val connector: String? = null,
        val url: String? = null,
        val user: String? = null,
        val password: String? = null
    )

    fun open(args: Array<String>): Database {
        val cli = parseDbCliArgs(args)
        val connector = DbConnector.parse(cli.connector ?: System.getenv("SBL_DB_CONNECTOR"))
        val url = cli.url ?: System.getenv("SBL_DB_URL")
        val user = cli.user ?: System.getenv("SBL_DB_USER")
        val password = cli.password ?: System.getenv("SBL_DB_PASSWORD")

        val cfg = when {
            !url.isNullOrBlank() -> DbConnectionConfig(connector, url, user, password)
            connector == DbConnector.SQLITE -> {
                val dbFile = File(DEFAULT_SQLITE_PATH)
                dbFile.parentFile?.mkdirs()
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
}

/** Wires repositories and the eight business-module services on top of a [Database]. */
class SblServices(val db: Database) {
    val audit = AuditService(AuditRepo(db))
    val market = MarketDataRepo(db)
    val contracts = ContractRepo(db)
    val counterparties = CounterpartyRepo(db)
    val sblas = SblaRepo(db)
    val designatedSecurities = DesignatedSecurityRepo(db)
    val rateSchedules = RateScheduleRepo(db)
    val marginCalls = MarginCallRepo(db)

    val inventory: InventoryService
    val locateSvc: LocateService
    val contractSvc: ContractService
    val collateralSvc: CollateralService
    val feeSvc: FeeService
    val caSvc: CorporateActionService
    val reportingSvc: ReportingService

    init {
        val positionRepo = PositionRepo(db)
        val locateRepo = LocateRepo(db)
        val snapshotRepo = LendablePoolSnapshotRepo(db)
        val assuranceRepo = DocumentaryAssuranceRepo(db)
        val eventRepo = LifecycleEventRepo(db)
        val recallRepo = RecallRepo(db)
        val rateHistoryRepo = ContractRateHistoryRepo(db)
        val collateralRepo = CollateralPositionRepo(db)
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

        inventory = InventoryService(positionRepo, locateRepo, contracts, snapshotRepo, designatedSecurities, audit)
        locateSvc = LocateService(locateRepo, counterparties, sblas, designatedSecurities, rateSchedules, market,
            assuranceRepo, contracts, inventory, audit)
        contractSvc = ContractService(contracts, eventRepo, recallRepo, rateHistoryRepo, counterparties,
            assuranceRepo, locateSvc, locateRepo, inventory, audit)
        collateralSvc = CollateralService(collateralRepo, marginCalls, mtmRepo, contracts, configRepo, market, audit)
        feeSvc = FeeService(accrualRepo, splitRepo, statementRepo, contracts, market, rateHistoryRepo, audit)
        caSvc = CorporateActionService(caEventRepo, caImpactRepo, contracts, contractSvc, market, audit)
        reportingSvc = ReportingService(contracts, designatedSecurities, market, spReportRepo, sprsRepo, irdRepo,
            cap571xRepo, sblas, recallRepo, eventRepo, accrualRepo, audit)
    }
}
