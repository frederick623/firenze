package com.piscessoft.sbl.seed

import com.piscessoft.sbl.app.SblServices
import com.piscessoft.sbl.model.*
import com.piscessoft.sbl.util.Ids
import com.piscessoft.sbl.util.Money
import com.piscessoft.sbl.util.SblTime
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Multi-day demo scenario exercising all eight modules on top of the seeded reference data:
 * locate -> contract -> collateral/MTM/margin call -> fees -> corporate actions ->
 * regulatory reporting, with every state change captured in the audit trail.
 */
class DemoScenario(private val s: SblServices) {

    private val market = s.market
    private val contracts = s.contracts
    private val inventory = s.inventory
    private val locateSvc = s.locateSvc
    private val contractSvc = s.contractSvc
    private val collateralSvc = s.collateralSvc
    private val settlementSvc = s.settlementSvc
    private val feeSvc = s.feeSvc
    private val caSvc = s.caSvc
    private val reportingSvc = s.reportingSvc

    fun run(d0: LocalDate) {
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

        // ---- Module 4: Post collateral to each borrower's account (covers its whole book) ----
        banner("MODULE 4  Collateral Posting")
        postCash("B1", 390_000_000)   // B1 book (c1 + c2 + c4) requires ~386.9m
        postCash("B2", 28_000_000)    // B2 book (c3) requires 27.3m

        // ---- Daily cycle: settlement + fees + MTM (nothing accrues until the T+2 open legs settle) ----
        cycle(d0)
        advanceTo(d0.plusDays(1)); cycle(SblTime.today())

        // ---- Settlement: T+2 open legs deliver (collateral already in place) ----
        banner("SETTLEMENT  T+2 Open Legs")
        advanceTo(d0.plusDays(2))
        val run = settlementSvc.settleDue(SblTime.today())
        run.settled.forEach { println("  ${it.legType} leg ${it.legId.take(8)} contract ${it.contractId.take(8)} x${it.quantity} settled ${it.actualSettleDate}") }
        run.failed.forEach { println("  ${it.legType} leg ${it.legId.take(8)} FAILED - ${it.failReason}") }

        // ---- Module 4: price shock -> margin call ----
        banner("MODULE 4  Price Shock -> Margin Call")
        market.putPrice("00005", SblTime.today(), Money.of("46.00"))
        val mtm = collateralSvc.runDailyMtm(SblTime.today())
        feeSvc.accrueDaily(SblTime.today())
        mtm.filter { it.marginCall != null }.forEach {
            println("  Margin call on ${it.counterpartyId} account: required=${Money.format(it.requiredCollateralHkd)} " +
                "eligible=${Money.format(it.eligibleCollateralHkd)} deficit=${Money.format(it.marginCall!!.deficitHkd)} HKD")
            postCash(it.counterpartyId, 6_000_000)
            val call = s.marginCalls.pendingForCounterparty(it.counterpartyId)
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
        println("  Immutable audit records written: ${s.audit.total()}")
    }

    // ---- scenario helpers ----

    private fun borrow(
        borrower: String, stock: String, qty: Long, lender: String,
        agent: String? = null, agentSplit: BigDecimal? = null
    ): String {
        val decision = locateSvc.requestLocate(borrower, stock, qty)
        println("  Locate $borrower $stock x$qty -> ${decision.outcome} (${decision.message})")
        val contract = contractSvc.openFromLocate(
            decision.locate.locateId, lender, agentId = agent, agentSplitPct = agentSplit
        )
        println("  Contract ${contract.contractId.take(8)} ${contract.status} - ${stock} x${contract.quantity} @ ${contract.feeRate} (${contract.feeType}), " +
            "settles ${s.settlementLegs.openLeg(contract.contractId)?.intendedSettleDate}")
        return contract.contractId
    }

    private fun demoLocate(borrower: String, stock: String, qty: Long) {
        val d = locateSvc.requestLocate(borrower, stock, qty)
        println("  Locate $borrower $stock x$qty -> ${d.outcome} (${d.message})")
        if (d.outcome != LocateStatus.REJECTED) locateSvc.cancel(d.locate.locateId)
    }

    private fun postCash(counterpartyId: String, amount: Long) {
        collateralSvc.postCollateral(counterpartyId, "CASH_HKD", "HKD", BigDecimal(amount))
    }

    private fun cycle(date: LocalDate) {
        settlementSvc.settleDue(date)
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

    private fun banner(title: String) {
        println("\n" + "=".repeat(60))
        println("  $title")
        println("=".repeat(60))
    }
}
