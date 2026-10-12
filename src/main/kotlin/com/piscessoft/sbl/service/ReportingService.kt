package com.piscessoft.sbl.service

import com.piscessoft.sbl.model.*
import com.piscessoft.sbl.repo.*
import com.piscessoft.sbl.util.Ids
import com.piscessoft.sbl.util.Money
import com.piscessoft.sbl.util.SblTime
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Summary row for the lender daily dashboard (plan section 8.4). */
data class LenderSummaryRow(
    val lenderId: String,
    val openContracts: Int,
    val onLoanValueHkd: BigDecimal,
    val accruedFeeHkd: BigDecimal
)

/**
 * Module 7 - Regulatory Reporting.
 *
 * SFC Short Position Reporting (SPRS) with threshold filtering and CSV generation,
 * IRD Form SBUL 1 four-category classification, and Cap.571X disclosure records
 * (plan section 8).
 */
class ReportingService(
    private val contracts: ContractRepo,
    private val designated: DesignatedSecurityRepo,
    private val market: MarketDataRepo,
    private val spReports: ShortPositionReportRepo,
    private val sprsSubs: SprsSubmissionRepo,
    private val ird: IrdRepo,
    private val cap571x: Cap571xRepo,
    private val sblas: SblaRepo,
    private val recalls: RecallRepo,
    private val events: LifecycleEventRepo,
    private val feeAccruals: FeeAccrualRepo,
    private val audit: AuditService,
    private val outputDir: File = File("out/reports")
) {
    private val issuedPct = BigDecimal("0.0002")               // 0.02% of issued shares
    private val valueCapHkd = BigDecimal("30000000")           // HK$30M
    private val maxRowsPerFile = 5000
    private val fileDateFmt = DateTimeFormatter.ofPattern("yyyyMMdd")

    // ---- 8.1 SFC Short Position Reporting ----

    /** Net short position (shares) for a stock: outstanding short-sale borrows plus external short. */
    fun netShortShares(stockCode: String, externalShort: Long = 0, offsettingLong: Long = 0): Long =
        (contracts.activeForStock(stockCode).filter { it.isShortSaleBorrow }.sumOf { it.quantityOutstanding }
            + externalShort - offsettingLong).coerceAtLeast(0)

    /** SPRS threshold: lower of 0.02% of market cap and HK$30M (TRST: HK$30M only). */
    fun sprsThreshold(security: DesignatedSecurity, price: BigDecimal): BigDecimal {
        val cap = valueCapHkd
        if (security.securityType == SecurityType.TRST) return cap
        val pct = Money.money(BigDecimal(security.issuedShares).multiply(price).multiply(issuedPct))
        return if (pct < cap) pct else cap
    }

    /** Compute and persist short-position reports for all designated securities on [date]. */
    fun generateShortPositionReports(
        date: LocalDate = SblTime.today(),
        externalShorts: Map<String, Long> = emptyMap()
    ): List<ShortPositionReport> {
        val reports = mutableListOf<ShortPositionReport>()
        for (sec in designated.all()) {
            val price = market.latestPrice(sec.stockCode, date) ?: continue
            val shares = netShortShares(sec.stockCode, externalShorts[sec.stockCode] ?: 0)
            if (shares <= 0) continue
            val value = Money.money(price.multiply(BigDecimal(shares)))
            val reportable = value >= sprsThreshold(sec, price)
            val report = ShortPositionReport(
                reportId = Ids.newId(),
                reportDate = date,
                stockCode = sec.stockCode,
                stockName = sec.stockName,
                netShortShares = shares,
                netShortValueHkd = value,
                reportable = reportable,
                createdAt = SblTime.now()
            )
            spReports.insert(report)
            reports += report
        }
        audit.log("REPORT", "SPRS:$date", "SHORT_POSITIONS_CALCULATED", "SYSTEM",
            newValue = "${reports.count { it.reportable }} reportable of ${reports.size}",
            regulatoryRef = "SF (Short Position Reporting) Rules")
        return reports
    }

    /**
     * Generate SPRS CSV file(s) for [date]. No header row; stock codes without leading zeros;
     * value to 3 decimals; max 5,000 rows per file (split into multiple otherwise).
     */
    fun generateSprsCsv(
        date: LocalDate,
        sprid: String,
        legalEntityName: String,
        frequency: String = "WEEKLY"
    ): List<File> {
        val reportable = spReports.forDate(date).filter { it.reportable }
        if (reportable.isEmpty()) return emptyList()
        outputDir.mkdirs()
        val files = mutableListOf<File>()
        reportable.chunked(maxRowsPerFile).forEachIndexed { idx, chunk ->
            val suffix = if (idx == 0) "" else "_part${idx + 1}"
            val filename = "${fileDateFmt.format(date)}_$sprid$suffix.csv"
            val file = File(outputDir, filename)
            file.writeText(chunk.joinToString("\r\n") { r ->
                listOf(
                    sprid,
                    legalEntityName,
                    r.stockCode.trimStart('0').ifEmpty { "0" },
                    r.stockName,
                    r.netShortShares.toString(),
                    r.netShortValueHkd.setScale(3, RoundingMode.HALF_UP).toPlainString()
                ).joinToString(",")
            } + "\r\n")
            val submission = SprsSubmission(
                submissionId = Ids.newId(),
                reportDate = date,
                frequency = frequency,
                filename = filename,
                rowCount = chunk.size,
                status = "GENERATED",
                createdAt = SblTime.now()
            )
            sprsSubs.insert(submission)
            audit.log("REPORT", submission.submissionId, "SPRS_CSV_GENERATED", "SYSTEM",
                newValue = "$filename rows=${chunk.size}", regulatoryRef = "SF (Short Position Reporting) Rules",
                retentionTier = RetentionTier.SEVEN_YEAR)
            files += file
        }
        return files
    }

    // ---- 8.2 IRD Form SBUL 1 ----

    /** Classify a contract into IRD SBUL 1 category (0 = exempt; 1..4 = non-exempt). */
    fun classify(contract: SblContract): Pair<Int, String?> {
        val sbla = contract.sblaId?.let { sblas.find(it) }
        // Category 1 - effected before SBLA registration.
        if (sbla != null) {
            val registeredOn = SblTime.nowHkt().toLocalDate() // fallback if null
            val regDate = try { sbla.registeredAt.atZone(SblTime.HKT).toLocalDate() } catch (e: Exception) { registeredOn }
            if (contract.openDate.isBefore(regDate)) return 1 to "Effected before SBLA registration"
        }
        // Category 3 - not returned at end of agreed term or on demand.
        val overdue = recalls.forContract(contract.contractId).any { it.status == RecallStatus.OVERDUE }
        val termLapsed = contract.closeDate?.let { SblTime.today().isAfter(it) && contract.quantityOutstanding > 0 } ?: false
        if (overdue || termLapsed) return 3 to "Not returned at end of term or on demand"
        // Category 4 - settled by means other than stock return (e.g. delisting termination).
        val earlyTerminated = events.forContract(contract.contractId).any { it.eventType == "EARLY_TERMINATION" }
        if (earlyTerminated) return 4 to "Settled by means other than stock return"
        // Category 2 - borrowed stock not used for specified purposes.
        if (contract.potentialNonExempt) return 2 to (contract.nonExemptReason ?: "Not used for specified purposes")
        return 0 to null
    }

    /** Generate SBUL 1 ledger entries for a tax year (plan section 8.2). */
    fun generateSbul1(taxYear: Int): List<IrdSbulTransaction> {
        val entries = mutableListOf<IrdSbulTransaction>()
        for (c in contracts.all()) {
            val sblaId = c.sblaId ?: continue
            if (c.openDate.year != taxYear) continue
            val (category, reason) = classify(c)
            val entry = IrdSbulTransaction(
                entryId = Ids.newId(),
                sblaId = sblaId,
                contractId = c.contractId,
                taxYear = taxYear,
                category = category,
                stampable = category > 0,
                reason = reason,
                createdAt = SblTime.now()
            )
            ird.insert(entry)
            entries += entry
        }
        val nonExempt = entries.count { it.category > 0 }
        audit.log("REPORT", "SBUL1:$taxYear", "SBUL1_GENERATED", "SYSTEM",
            newValue = "${entries.size} entries, $nonExempt non-exempt",
            regulatoryRef = "SDO Cap.117 s.19(13)", retentionTier = RetentionTier.SEVEN_YEAR)
        return entries
    }

    // ---- 8.3 Cap.571X disclosures ----

    /** Create disclosure records for contracts flagged as deemed acquisitions (plan section 8.3). */
    fun generateCap571xDisclosures(disclosureWindowBusinessDays: Int = 3): List<Cap571xDisclosure> {
        val out = mutableListOf<Cap571xDisclosure>()
        for (c in contracts.active()) {
            if (!c.potentialNonExempt) continue
            if (cap571x.forContract(c.contractId).any { it.eventType == "DEEMED_ACQUISITION" }) continue
            val eventDate = SblTime.today()
            val disclosure = Cap571xDisclosure(
                disclosureId = Ids.newId(),
                personId = c.borrowerId,
                contractId = c.contractId,
                eventType = "DEEMED_ACQUISITION",
                eventDate = eventDate,
                stockCode = c.stockCode,
                quantity = c.quantityOutstanding,
                prescribedPurpose = c.regulatedPersonPurpose,
                disclosureDeadline = SblTime.addBusinessDays(eventDate, disclosureWindowBusinessDays),
                status = "PENDING",
                createdAt = SblTime.now()
            )
            cap571x.insert(disclosure)
            audit.log("REPORT", disclosure.disclosureId, "CAP571X_DISCLOSURE", "SYSTEM",
                newValue = "${c.stockCode} qty=${c.quantityOutstanding}",
                regulatoryRef = "Cap.571X Rules", retentionTier = RetentionTier.THREE_YEAR)
            out += disclosure
        }
        return out
    }

    // ---- 8.4 Lender daily summary ----

    fun lenderDailySummary(date: LocalDate = SblTime.today()): List<LenderSummaryRow> =
        contracts.active().groupBy { it.lenderId }.map { (lenderId, list) ->
            val onLoanValue = list.fold(BigDecimal.ZERO) { acc, c ->
                val price = market.latestPrice(c.stockCode, date) ?: BigDecimal.ZERO
                acc.add(price.multiply(BigDecimal(c.quantityOutstanding)))
            }
            val fee = list.fold(BigDecimal.ZERO) { acc, c -> acc.add(c.cumulativeFeeHkd) }
            LenderSummaryRow(lenderId, list.size, Money.money(onLoanValue), Money.money(fee))
        }
}
