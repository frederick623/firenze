package com.piscessoft.sbl

import com.piscessoft.sbl.db.Database
import com.piscessoft.sbl.model.*
import com.piscessoft.sbl.repo.*
import com.piscessoft.sbl.service.*
import com.piscessoft.sbl.util.Ids
import com.piscessoft.sbl.util.Money
import com.piscessoft.sbl.util.SblTime
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Self-contained assertion runner (no JUnit dependency). Exercises the key algorithms of
 * every module against an in-memory SQLite database. Exits non-zero if any check fails.
 */
object SblTests {

    private var passed = 0
    private var failed = 0

    private fun check(name: String, cond: Boolean) {
        if (cond) { passed++; println("  PASS  $name") }
        else { failed++; println("  FAIL  $name") }
    }

    private fun eqBd(name: String, actual: BigDecimal, expected: String) =
        check("$name (=$expected, got ${actual.toPlainString()})", actual.compareTo(BigDecimal(expected)) == 0)

    private val D0: LocalDate = LocalDate.of(2026, 1, 5) // Monday

    @JvmStatic
    fun main(args: Array<String>) {
        println("SBL Core - test suite\n")
        try {
            testMoney()
            testBusinessDays()
            testLendablePool()
            testLocateApprovalReducesPool()
            testLocateShortInterestLimit()
            testRateResolution()
            testContractLifecycle()
            testCollateralMtmAndMarginCall()
            testCollateralSubstitutionGuard()
            testCollateralReleaseGuard()
            testWrongWayCollateral()
            testMarginCallRestated()
            testFeeAccrualAndSplits()
            testRerateEffectiveDate()
            testStatementNoDoubleBilling()
            testCorporateActions()
            testCaExDateEntitlement()
            testBuyInEscalation()
            testSprsThresholdAndCsv()
            testIrdClassification()
            testAuditAppendOnly()
        } finally {
            SblTime.resetToSystem()
        }
        println("\n${passed} passed, ${failed} failed")
        if (failed > 0) kotlin.system.exitProcess(1)
    }

    // ---- util ----

    private fun testMoney() {
        section("util.Money")
        eqBd("rate scale 6", Money.rate(BigDecimal("0.05")), "0.050000")
        eqBd("money scale 4 half-up", Money.money(BigDecimal("1.23455")), "1.2346")
        eqBd("ratio 10/4", Money.ratio(BigDecimal(10), BigDecimal(4)), "2.50000000")
        eqBd("ratio div-by-zero -> 0", Money.ratio(BigDecimal.ONE, BigDecimal.ZERO), "0.0000")
    }

    private fun testBusinessDays() {
        section("util.SblTime business days")
        check("Sat is not a business day", !SblTime.isBusinessDay(LocalDate.of(2026, 1, 10)))
        check("Mon +5 business days -> next Mon",
            SblTime.addBusinessDays(D0, 5) == LocalDate.of(2026, 1, 12))
        check("business days Fri->Mon == 1",
            SblTime.businessDaysBetween(LocalDate.of(2026, 1, 9), LocalDate.of(2026, 1, 12)) == 1)
    }

    // ---- module 2 ----

    private fun testLendablePool() {
        section("M2 lendable pool")
        val c = newCtx(); SblTime.freezeAt(D0)
        c.seedParties(); c.seedSecurity("00005", 20_000_000_000, SecurityType.EQTY)
        c.price("00005", "40.00"); c.seedDefaultRate("0.005")
        c.inventory.onPositionSettled("L1", "00005", AccountType.PROPRIETARY, 1_000_000)
        c.inventory.onPositionSettled("L1", "00005", AccountType.PLEDGED, 500_000) // not lendable
        val pool = c.inventory.lendablePool("00005")
        check("gross excludes pledged", pool.grossLendable == 1_000_000L)
        check("net == gross when nothing committed", pool.netAvailable == 1_000_000L)
        c.db.close()
    }

    private fun testLocateApprovalReducesPool() {
        section("M1 approval commits & reduces pool")
        val c = newCtx(); SblTime.freezeAt(D0)
        c.seedParties(); c.seedSecurity("00005", 20_000_000_000, SecurityType.EQTY)
        c.price("00005", "40.00"); c.seedDefaultRate("0.005")
        c.inventory.onPositionSettled("L1", "00005", AccountType.PROPRIETARY, 1_000_000)
        val d = c.locate.requestLocate("B1", "00005", 200_000)
        check("approved", d.outcome == LocateStatus.APPROVED)
        check("net reduced by committed", c.inventory.netAvailableToLend("00005") == 800_000L)
        c.db.close()
    }

    private fun testLocateShortInterestLimit() {
        section("M1 short-interest cap -> PARTIAL")
        val c = newCtx(); SblTime.freezeAt(D0)
        c.seedParties(); c.seedSecurity("00005", 1_000_000, SecurityType.EQTY) // tiny float
        c.price("00005", "40.00"); c.seedDefaultRate("0.005")
        c.inventory.onPositionSettled("L1", "00005", AccountType.PROPRIETARY, 500_000)
        // 10% short-interest cap of 1,000,000 issued = 100,000.
        val d = c.locate.requestLocate("B1", "00005", 200_000)
        check("partial", d.outcome == LocateStatus.PARTIAL)
        check("approved capped at 10% issued", d.locate.approvedQty == 100_000L)
        c.db.close()
    }

    private fun testRateResolution() {
        section("M1 rate resolution (most specific wins)")
        val c = newCtx(); SblTime.freezeAt(D0)
        c.seedParties(); c.seedSecurity("00700", 9_500_000_000, SecurityType.EQTY)
        c.price("00700", "320.00"); c.index("HIBOR_3M", "0.045")
        c.seedDefaultRate("0.005")
        c.rates.insert(RateSchedule(Ids.newId(), "00700", null, FeeType.FIXED, Money.of("0.02"),
            null, null, null, D0.minusDays(10), null, 10, SblTime.now()))
        c.rates.insert(RateSchedule(Ids.newId(), null, BorrowerTier.TIER_2, FeeType.FLOATING, null,
            "HIBOR_3M", 200, Money.of("0.01"), D0.minusDays(10), null, 50, SblTime.now()))
        val stockRate = c.locate.resolveRate("00700", "B1")!!
        eqBd("stock-specific beats default", stockRate.feeRate, "0.020000")
        val floatRate = c.locate.resolveRate("00005ZZ", "B2") // B2 is TIER_2 -> floating default
        eqBd("floating = index + spread", floatRate!!.feeRate, "0.065000")
        c.db.close()
    }

    // ---- module 3 ----

    private fun testContractLifecycle() {
        section("M3 open -> return -> close")
        val c = newCtx(); SblTime.freezeAt(D0)
        val contractId = c.approveAndOpen("B1", "00005", 100_000)
        val opened = c.contracts.find(contractId)!!
        check("outstanding == qty on open", opened.quantityOutstanding == 100_000L)
        check("version starts at 1", opened.version == 1)
        c.contractSvc.processReturn(contractId, 40_000)
        check("partial return status", c.contracts.find(contractId)!!.status == ContractStatus.PARTIALLY_RETURNED)
        c.contractSvc.processReturn(contractId, 60_000)
        val closed = c.contracts.find(contractId)!!
        check("closed when fully returned", closed.status == ContractStatus.CLOSED)
        check("outstanding zero", closed.quantityOutstanding == 0L)
        check("version bumped by updates", closed.version > 1)
        c.db.close()
    }

    // ---- module 4 ----

    private fun testCollateralMtmAndMarginCall() {
        section("M4 MTM coverage & margin call")
        val c = newCtx(); SblTime.freezeAt(D0)
        c.price("00005", "10.00")
        val contractId = c.approveAndOpen("B1", "00005", 100_000) // borrowed 1,000,000; required 1.05 = 1,050,000
        c.collateralSvc.postCollateral(contractId, "CASH_HKD", "HKD", BigDecimal("1100000"), BigDecimal.ONE)
        val ok = c.collateralSvc.valuate(c.contracts.find(contractId)!!, D0)
        check("no margin call when covered", ok.marginCall == null)
        eqBd("coverage 1.1", ok.coverageRatio, "1.100000")
        // Price jump -> under-collateralised.
        c.price("00005", "12.00")
        val breach = c.collateralSvc.valuate(c.contracts.find(contractId)!!, D0)
        check("margin call raised", breach.marginCall != null)
        eqBd("deficit = 1.05*1.2m - 1.1m", breach.marginCall!!.deficitHkd, "160000.0000")
        c.db.close()
    }

    private fun testCollateralSubstitutionGuard() {
        section("M4 substitution must not reduce value")
        val c = newCtx(); SblTime.freezeAt(D0)
        c.price("00005", "10.00")
        val contractId = c.approveAndOpen("B1", "00005", 100_000)
        val pos = c.collateralSvc.postCollateral(contractId, "CASH_HKD", "HKD", BigDecimal("100000"), BigDecimal.ONE)
        var threw = false
        try {
            c.collateralSvc.substitute(pos.positionId, "CASH_HKD", "HKD", BigDecimal("90000"), BigDecimal.ONE)
        } catch (e: IllegalArgumentException) { threw = true }
        check("rejects lower-value substitute", threw)
        val good = c.collateralSvc.substitute(pos.positionId, "CASH_HKD", "HKD", BigDecimal("120000"), BigDecimal.ONE)
        check("accepts higher-value substitute", good.adjustedValueHkd.compareTo(BigDecimal("120000")) == 0)
        check("old position marked substituted",
            c.collateralRepo.find(pos.positionId)!!.status == CollateralStatus.SUBSTITUTED)
        c.db.close()
    }

    /**
     * A rejected substitution must not leave the candidate collateral behind: the
     * replacement is valued before anything is written.
     */
    private fun testCollateralReleaseGuard() {
        section("M4 release must not breach coverage")
        val c = newCtx(); SblTime.freezeAt(D0)
        c.price("00005", "10.00")
        val contractId = c.approveAndOpen("B1", "00005", 100_000) // borrowed 1,000,000; required 1,050,000
        val before = c.collateralRepo.activeForContract(contractId).size
        var threw = false
        try {
            c.collateralSvc.substitute(
                c.collateralSvc.postCollateral(contractId, "CASH_HKD", "HKD",
                    BigDecimal("100000"), BigDecimal.ONE).positionId,
                "CASH_HKD", "HKD", BigDecimal("90000"), BigDecimal.ONE
            )
        } catch (e: IllegalArgumentException) { threw = true }
        check("rejected substitution throws", threw)
        check("rejected substitution leaves no orphan collateral",
            c.collateralRepo.activeForContract(contractId).size == before + 1)

        // Post exactly enough, then try to give it back while the borrow is still open.
        val needed = c.collateralSvc.postCollateral(contractId, "CASH_HKD", "HKD",
            BigDecimal("1050000"), BigDecimal.ONE)
        var refused = false
        try { c.collateralSvc.releaseCollateral(needed.positionId) }
        catch (e: IllegalArgumentException) { refused = true }
        check("refuses release that would breach required ratio", refused)
        check("position still ACTIVE after refusal",
            c.collateralRepo.find(needed.positionId)!!.status == CollateralStatus.ACTIVE)

        // Surplus on top of full cover may be returned.
        val surplus = c.collateralSvc.postCollateral(contractId, "CASH_HKD", "HKD",
            BigDecimal("500000"), BigDecimal.ONE)
        c.collateralSvc.releaseCollateral(surplus.positionId)
        check("releases genuine excess",
            c.collateralRepo.find(surplus.positionId)!!.status == CollateralStatus.RELEASED)
        c.db.close()
    }

    /** The borrowed security must not be pledged back as collateral for its own borrow. */
    private fun testWrongWayCollateral() {
        section("M4 wrong-way collateral rejected")
        val c = newCtx(); SblTime.freezeAt(D0)
        c.price("00005", "10.00")
        val contractId = c.approveAndOpen("B1", "00005", 100_000)
        var threw = false
        try {
            c.collateralSvc.postCollateral(contractId, "MARGIN_EQTY", "00005",
                BigDecimal("200000"), BigDecimal("10.00"))
        } catch (e: IllegalArgumentException) { threw = true }
        check("rejects the borrowed stock as its own collateral", threw)
        check("nothing persisted", c.collateralRepo.activeForContract(contractId).isEmpty())
        // A different security is acceptable collateral.
        val other = c.collateralSvc.postCollateral(contractId, "MARGIN_EQTY", "00700",
            BigDecimal("200000"), BigDecimal("10.00"))
        check("accepts an unrelated security", other.status == CollateralStatus.ACTIVE)
        c.db.close()
    }

    /** A worsening deficit must restate the open call rather than leave a stale amount. */
    private fun testMarginCallRestated() {
        section("M4 margin call restated when deficit worsens")
        val c = newCtx(); SblTime.freezeAt(D0)
        c.price("00005", "10.00")
        val contractId = c.approveAndOpen("B1", "00005", 100_000)
        c.collateralSvc.postCollateral(contractId, "CASH_HKD", "HKD", BigDecimal("1100000"), BigDecimal.ONE)

        c.price("00005", "12.00")                       // required 1,260,000 vs 1,100,000
        c.collateralSvc.runDailyMtm(D0)
        val first = c.marginRepo.pendingForContract(contractId)!!
        eqBd("initial deficit", first.deficitHkd, "160000.0000")

        c.price("00005", "14.00")                       // required 1,470,000 vs 1,100,000
        c.collateralSvc.runDailyMtm(D0)
        val open = c.marginRepo.pendingForContract(contractId)!!
        check("still a single open call", open.callId == first.callId)
        eqBd("deficit restated upward", open.deficitHkd, "370000.0000")
        check("call deadline is not in the past", !open.deadline.isBefore(SblTime.now()))
        c.db.close()
    }

    /** A rerate effective tomorrow must not reprice today's accrual. */
    private fun testRerateEffectiveDate() {
        section("M5 rerate respects its effective date")
        val c = newCtx(); SblTime.freezeAt(D0)
        c.price("00005", "10.00"); c.seedDefaultRate("0.0365") // daily 100.00 on 1,000,000 notional
        val contractId = c.approveAndOpen("B1", "00005", 100_000)

        // Double the rate, but only from D0+2.
        c.contractSvc.rerate(contractId, BigDecimal("0.0730"), D0.plusDays(2))

        val day0 = c.feeSvc.accrueDaily(D0).first { it.contractId == contractId }
        eqBd("day 0 keeps the original rate", day0.dailyFeeHkd, "100.0000")

        SblTime.freezeAt(D0.plusDays(1)); c.price("00005", "10.00")
        val day1 = c.feeSvc.accrueDaily(D0.plusDays(1)).first { it.contractId == contractId }
        eqBd("day before effective date still original", day1.dailyFeeHkd, "100.0000")

        SblTime.freezeAt(D0.plusDays(2)); c.price("00005", "10.00")
        val day2 = c.feeSvc.accrueDaily(D0.plusDays(2)).first { it.contractId == contractId }
        eqBd("new rate applies from effective date", day2.dailyFeeHkd, "200.0000")
        c.db.close()
    }

    /** Re-running a statement over a settled period must not bill the same accrual twice. */
    private fun testStatementNoDoubleBilling() {
        section("M5 settled accruals are not re-billed")
        val c = newCtx(); SblTime.freezeAt(D0)
        c.price("00005", "10.00"); c.seedDefaultRate("0.0365")
        c.approveAndOpen("B1", "00005", 100_000)
        c.feeSvc.accrueDaily(D0)

        val first = c.feeSvc.generateStatement("B1", D0, D0)
        eqBd("first statement bills the accrual", first.totalFeeHkd, "100.0000")
        c.feeSvc.settleStatement(first.statementId)

        val second = c.feeSvc.generateStatement("B1", D0, D0)
        eqBd("re-issued statement bills nothing", second.totalFeeHkd, "0.0000")
        c.db.close()
    }

    /** Shares borrowed on/after ex-date carry no entitlement. */
    private fun testCaExDateEntitlement() {
        section("M6 entitlement follows the ex-date")
        val c = newCtx(); SblTime.freezeAt(D0)
        c.price("00005", "10.00")
        val cumContract = c.approveAndOpen("B1", "00005", 100_000)   // opened D0, before ex-date

        // Second borrow opened on the ex-date itself -> trades ex-entitlement.
        val exDate = D0.plusDays(1)
        SblTime.freezeAt(exDate); c.price("00005", "10.00")
        val exContract = c.approveAndOpen("B2", "00005", 100_000)

        c.caSvc.ingest(CaEvent(Ids.newId(), "00005", CaType.CASH_DIVIDEND, exDate, exDate,
            D0.plusDays(5), null, Money.of("1.50"), "HKD", "div", createdAt = SblTime.now()))
        val impacts = c.caSvc.processAll()

        check("contract open before ex-date owes a manufactured payment",
            impacts.any { it.contractId == cumContract && it.actionType == CaActionType.MANUFACTURED_PAYMENT })
        check("contract opened on ex-date owes nothing",
            impacts.none { it.contractId == exContract })
        c.db.close()
    }

    /** An unsatisfied recall past its grace period escalates to buy-in. */
    private fun testBuyInEscalation() {
        section("M3 failed return escalates to buy-in")
        val c = newCtx(); SblTime.freezeAt(D0)
        c.price("00005", "10.00")
        val contractId = c.approveAndOpen("B1", "00005", 100_000)
        val notice = c.contractSvc.recall(contractId, 100_000, RecallReason.LENDER_REQUEST)

        // Deadline passes with nothing returned.
        SblTime.freezeAt(notice.returnDeadline.plusDays(1))
        c.contractSvc.checkOverdueRecalls()
        check("recall marked overdue",
            c.recallRepo.find(notice.recallId)!!.status == RecallStatus.OVERDUE)
        check("no buy-in before the grace period expires",
            c.contractSvc.escalateBuyIns().isEmpty())

        // Grace period (3 business days after the deadline) elapses.
        SblTime.freezeAt(SblTime.addBusinessDays(notice.returnDeadline, 4))
        val escalated = c.contractSvc.escalateBuyIns()
        check("buy-in triggered", escalated.any { it.recallId == notice.recallId })
        check("recall marked defaulted",
            c.recallRepo.find(notice.recallId)!!.status == RecallStatus.DEFAULTED)
        c.db.close()
    }

    // ---- module 5 ----

    private fun testFeeAccrualAndSplits() {
        section("M5 daily accrual = price*qty*rate/365")
        val c = newCtx(); SblTime.freezeAt(D0)
        c.price("00005", "10.00"); c.seedDefaultRate("0.0365") // -> daily 100.00 on 1,000,000 notional
        c.seedParties(); c.seedSecurity("00005", 20_000_000_000, SecurityType.EQTY)
        c.inventory.onPositionSettled("L1", "00005", AccountType.PROPRIETARY, 1_000_000)
        val d = c.locate.requestLocate("B1", "00005", 100_000)
        val contract = c.contractSvc.openFromLocate(d.locate.locateId, "L1",
            agentId = "A1", agentSplitPct = BigDecimal("0.25"))
        val accruals = c.feeSvc.accrueDaily(D0)
        val accrual = accruals.first { it.contractId == contract.contractId }
        eqBd("daily fee", accrual.dailyFeeHkd, "100.0000")
        val splits = c.splitRepo.forAccrual(accrual.accrualId)
        val sum = splits.fold(BigDecimal.ZERO) { a, s -> a.add(s.splitAmountHkd) }
        eqBd("splits sum to daily fee", Money.money(sum), "100.0000")
        check("agent gets 25%", splits.any { it.recipientRole == "AGENT" && it.splitAmountHkd.compareTo(BigDecimal("25.0000")) == 0 })
        c.db.close()
    }

    // ---- module 6 ----

    private fun testCorporateActions() {
        section("M6 qty adjustment & manufactured payment")
        val c = newCtx(); SblTime.freezeAt(D0)
        c.price("00005", "10.00")
        val contractId = c.approveAndOpen("B1", "00005", 100_000)
        // 2:1 split.
        c.caSvc.ingest(CaEvent(Ids.newId(), "00005", CaType.STOCK_SPLIT, D0.plusDays(1), D0.plusDays(1),
            null, BigDecimal("2.0"), null, null, "split", createdAt = SblTime.now()))
        // Cash dividend 1.50/share.
        c.caSvc.ingest(CaEvent(Ids.newId(), "00005", CaType.CASH_DIVIDEND, D0.plusDays(1), D0.plusDays(1),
            D0.plusDays(5), null, Money.of("1.50"), "HKD", "div", createdAt = SblTime.now()))
        val impacts = c.caSvc.processAll()
        check("qty adjusted to 200,000", c.contracts.find(contractId)!!.quantity == 200_000L)
        val payment = impacts.first { it.actionType == CaActionType.MANUFACTURED_PAYMENT }
        // Dividend computed on outstanding at processing time (already split to 200,000): 1.50 * 200,000.
        eqBd("manufactured payment", payment.paymentAmountHkd!!, "300000.0000")
        c.db.close()
    }

    // ---- module 7 ----

    private fun testSprsThresholdAndCsv() {
        section("M7 SPRS threshold & CSV format")
        val c = newCtx(); SblTime.freezeAt(D0)
        c.price("00700", "10.00")
        val eqty = DesignatedSecurity("00700", "Tencent Holdings Ltd", SecurityType.EQTY, 1_000_000_000, updatedAt = SblTime.now())
        val trst = DesignatedSecurity("02800", "Tracker Fund of HK", SecurityType.TRST, 2_000_000_000, updatedAt = SblTime.now())
        eqBd("equity threshold = lower of 0.02% mcap and 30M",
            c.reportingSvc.sprsThreshold(eqty, BigDecimal("10")), "2000000.0000") // 0.0002*1e9*10 = 2,000,000
        eqBd("TRST threshold = 30M only",
            c.reportingSvc.sprsThreshold(trst, BigDecimal("10")), "30000000")

        // A short-sale borrow of 5,000,000 -> value 50,000,000 >= 2,000,000 -> reportable.
        val contractId = c.approveAndOpen("B1", "00700", 5_000_000, issued = 1_000_000_000)
        check("net short shares from active short borrows", c.reportingSvc.netShortShares("00700") == 5_000_000L)
        c.reportingSvc.generateShortPositionReports(D0)
        val files = c.reportingSvc.generateSprsCsv(D0, "SPRID999", "XYZ HK Ltd")
        check("one CSV produced", files.size == 1)
        val lines = files.first().readLines().filter { it.isNotBlank() }
        val cols = lines.first().split(",")
        check("no header, first line is data", cols[0] == "SPRID999")
        check("stock code strips leading zeros", cols[2] == "700")
        check("value has 3 decimals", cols[5] == "50000000.000")
        files.first().parentFile.deleteRecursively()
        c.db.close()
    }

    private fun testIrdClassification() {
        section("M7 IRD SBUL1 classification")
        val c = newCtx(); SblTime.freezeAt(D0)
        c.price("00941", "50.00")
        // Category 2: regulated person, not deployed within window.
        val regContract = c.approveAndOpen("B2", "00941", 100_000, issued = 8_000_000_000)
        SblTime.freezeAt(D0.plusDays(10))
        c.contractSvc.checkRegulatedPersonDeadlines()
        val (cat2, _) = c.reportingSvc.classify(c.contracts.find(regContract)!!)
        check("regulated-not-deployed -> category 2", cat2 == 2)
        SblTime.freezeAt(D0)
        // Category 4: early termination.
        c.price("00005", "10.00")
        val termContract = c.approveAndOpen("B1", "00005", 100_000)
        c.contractSvc.earlyTerminate(termContract, "delisting")
        val (cat4, _) = c.reportingSvc.classify(c.contracts.find(termContract)!!)
        check("early-terminated -> category 4", cat4 == 4)
        c.db.close()
    }

    // ---- module 8 ----

    private fun testAuditAppendOnly() {
        section("M8 audit trail append-only")
        val c = newCtx(); SblTime.freezeAt(D0)
        val before = c.audit.total()
        c.audit.log("CONTRACT", "X1", "TEST_EVENT", "tester", ActorType.USER)
        check("audit count increments", c.audit.total() == before + 1)
        val history = c.audit.history("CONTRACT", "X1")
        check("history returns the entry", history.size == 1 && history.first().eventType == "TEST_EVENT")
        c.db.close()
    }

    private fun section(title: String) = println("\n[$title]")

    private fun newCtx() = Ctx(Database.openInMemory())

    /** Minimal wiring + seeding harness for a single test's in-memory database. */
    private class Ctx(val db: Database) {
        val audit = AuditService(AuditRepo(db))
        val market = MarketDataRepo(db)
        val contracts = ContractRepo(db)
        val collateralRepo = CollateralPositionRepo(db)
        val splitRepo = FeeSplitRepo(db)
        val rates = RateScheduleRepo(db)
        private val positionRepo = PositionRepo(db)
        private val locateRepo = LocateRepo(db)
        private val designatedRepo = DesignatedSecurityRepo(db)
        private val counterpartyRepo = CounterpartyRepo(db)
        private val sblaRepo = SblaRepo(db)
        private val assuranceRepo = DocumentaryAssuranceRepo(db)
        private val eventRepo = LifecycleEventRepo(db)
        val recallRepo = RecallRepo(db)
        val rateHistoryRepo = ContractRateHistoryRepo(db)

        val inventory = InventoryService(positionRepo, locateRepo, contracts,
            LendablePoolSnapshotRepo(db), designatedRepo, audit)
        val locate = LocateService(locateRepo, counterpartyRepo, sblaRepo, designatedRepo, rates, market,
            assuranceRepo, contracts, inventory, audit)
        val contractSvc = ContractService(contracts, eventRepo, recallRepo, rateHistoryRepo, counterpartyRepo,
            assuranceRepo, locate, locateRepo, inventory, audit)
        val marginRepo = MarginCallRepo(db)
        val collateralSvc = CollateralService(collateralRepo, marginRepo, MtmHistoryRepo(db),
            contracts, CollateralTypeConfigRepo(db), market, audit)
        val accrualRepo = FeeAccrualRepo(db)
        val feeSvc = FeeService(accrualRepo, splitRepo, FeeStatementRepo(db), contracts, market,
            rateHistoryRepo, audit)
        val caSvc = CorporateActionService(CaEventRepo(db), CaImpactRepo(db), contracts, contractSvc, market, audit)
        val reportingSvc = ReportingService(contracts, designatedRepo, market, ShortPositionReportRepo(db),
            SprsSubmissionRepo(db), IrdRepo(db), Cap571xRepo(db), sblaRepo, recallRepo, eventRepo,
            FeeAccrualRepo(db), audit, File("out/test-reports"))

        private var partiesSeeded = false

        fun seedParties() {
            if (partiesSeeded) return
            partiesSeeded = true
            val now = SblTime.now()
            counterpartyRepo.insert(Counterparty("L1", "Lender", CounterpartyType.LENDER, createdAt = now))
            counterpartyRepo.insert(Counterparty("A1", "Agent", CounterpartyType.AGENT, createdAt = now))
            counterpartyRepo.insert(Counterparty("B1", "Borrower1", CounterpartyType.BORROWER, BorrowerTier.TIER_1,
                approvedBorrower = true, creditLimitHkd = BigDecimal("1000000000000"), createdAt = now))
            counterpartyRepo.insert(Counterparty("B2", "Borrower2", CounterpartyType.BORROWER, BorrowerTier.TIER_2,
                approvedBorrower = true, creditLimitHkd = BigDecimal("1000000000000"),
                regulatedPerson = true, createdAt = now))
            val regAt = D0.minusDays(30).atStartOfDay(SblTime.HKT).toInstant()
            sblaRepo.insert(Sbla(Ids.newId(), "B1", "L1", "SBA-B1", true, regAt, D0.plusYears(1), createdAt = now))
            sblaRepo.insert(Sbla(Ids.newId(), "B2", "L1", "SBA-B2", true, regAt, D0.plusYears(1), createdAt = now))
        }

        fun seedSecurity(code: String, issued: Long, type: SecurityType) {
            designatedRepo.upsert(DesignatedSecurity(code, "Security $code", type, issued, updatedAt = SblTime.now()))
        }

        fun price(code: String, p: String) = market.putPrice(code, SblTime.today(), Money.of(p))
        fun index(name: String, r: String) = market.putIndex(name, SblTime.today(), Money.of(r))

        fun seedDefaultRate(rate: String) {
            rates.insert(RateSchedule(Ids.newId(), null, null, FeeType.FIXED, Money.of(rate),
                null, null, null, D0.minusDays(10), null, 100, SblTime.now()))
        }

        /** Approve a locate and open a contract, seeding the minimum required reference data. */
        fun approveAndOpen(borrower: String, stock: String, qty: Long, issued: Long = 20_000_000_000): String {
            seedParties()
            seedSecurity(stock, issued, SecurityType.EQTY)
            if (rates.all().isEmpty()) seedDefaultRate("0.005")
            if (market.latestPrice(stock, SblTime.today()) == null) price(stock, "10.00")
            inventory.onPositionSettled("L1", stock, AccountType.PROPRIETARY, qty * 4)
            val d = locate.requestLocate(borrower, stock, qty)
            return contractSvc.openFromLocate(d.locate.locateId, "L1").contractId
        }
    }
}
