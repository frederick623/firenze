package com.piscessoft.sbl

import com.piscessoft.sbl.api.ApiKeyAuth
import com.piscessoft.sbl.api.ApiServer
import com.piscessoft.sbl.api.Json
import com.piscessoft.sbl.api.Principal
import com.piscessoft.sbl.api.Role
import com.piscessoft.sbl.api.SblApi
import com.piscessoft.sbl.app.SblServices
import com.piscessoft.sbl.db.Database
import com.piscessoft.sbl.model.*
import com.piscessoft.sbl.repo.*
import com.piscessoft.sbl.service.*
import com.piscessoft.sbl.util.Ids
import com.piscessoft.sbl.util.Money
import com.piscessoft.sbl.util.SblTime
import java.io.File
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
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
            testSettlementLegs()
            testCollateralMtmAndMarginCall()
            testAccountNetting()
            testClientStockCollateral()
            testCollateralSubstitutionGuard()
            testCollateralReleaseGuard()
            testWrongWayCollateral()
            testMarginCallRestated()
            testCollateralRatioFloor()
            testCollateralSystemPricing()
            testCollateralEligibility()
            testCollateralConcentrationCap()
            testSameIssuerWrongWay()
            testUnpricedAndForeignBorrow()
            testMarginCallEscalation()
            testFeeAccrualAndSplits()
            testRerateEffectiveDate()
            testStatementNoDoubleBilling()
            testCashCollateralRebate()
            testCorporateActions()
            testCaExDateEntitlement()
            testBuyInEscalation()
            testAllocationSourceMapping()
            testAllocationFirmFirst()
            testAllocationReleaseOrder()
            testAllocationSubstitutionAndRecall()
            testAllocationShortfallBeforeDelivery()
            testAllocationFeePassThrough()
            testAllocationAgency()
            testAllocationRescaleAndTerminate()
            testStreetBorrowInMatchedBook()
            testBorrowInPairing()
            testSprsThresholdAndCsv()
            testIrdClassification()
            testAuditAppendOnly()
            testRestApi()
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
        c.price("00700", "320.00"); c.index(FloatingIndex.HIBOR_3M, "0.045")
        c.seedDefaultRate("0.005")
        c.rates.insert(RateSchedule(Ids.newId(), "00700", null, FeeType.FIXED, Money.of("0.02"),
            null, null, null, D0.minusDays(10), null, 10, SblTime.now()))
        c.rates.insert(RateSchedule(Ids.newId(), null, BorrowerTier.TIER_2, FeeType.FLOATING, null,
            FloatingIndex.HIBOR_3M, 200, Money.of("0.01"), D0.minusDays(10), null, 50, SblTime.now()))
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
        val contractId = c.approveAndOpen("B1", "00005", 100_000, settled = false)
        val opened = c.contracts.find(contractId)!!
        check("outstanding == qty on open", opened.quantityOutstanding == 100_000L)
        check("version starts at 1", opened.version == 1)
        c.settle(contractId)
        check("active once open leg settles", c.contracts.find(contractId)!!.status == ContractStatus.ACTIVE)
        c.contractSvc.processReturn(contractId, 40_000)
        check("partial return status", c.contracts.find(contractId)!!.status == ContractStatus.PARTIALLY_RETURNED)
        c.contractSvc.processReturn(contractId, 60_000)
        val closed = c.contracts.find(contractId)!!
        check("closed when fully returned", closed.status == ContractStatus.CLOSED)
        check("outstanding zero", closed.quantityOutstanding == 0L)
        check("version bumped by updates", closed.version > 1)
        c.db.close()
    }

    /**
     * OPEN/RETURN settlement legs: T+2 for external parties, T+0 for internal desks,
     * collateral before delivery, and fees from open-leg to return-leg settlement.
     */
    private fun testSettlementLegs() {
        section("M3 settlement legs (T+2 / T+0, collateral first, fee window)")
        val c = newCtx(); SblTime.freezeAt(D0)
        c.seedParties(); c.seedSecurity("00005", 20_000_000_000, SecurityType.EQTY)
        c.price("00005", "10.00"); c.seedDefaultRate("0.0365") // daily 100.00 on 1,000,000 notional
        c.inventory.onPositionSettled("L1", "00005", AccountType.PROPRIETARY, 1_000_000)

        val d = c.locate.requestLocate("B1", "00005", 100_000)
        val k = c.contractSvc.openFromLocate(d.locate.locateId, "L1")
        val openLeg = c.legRepo.openLeg(k.contractId)!!
        check("external counterparty defaults to T+2", openLeg.intendedSettleDate == D0.plusDays(2))
        check("booked as CONFIRMED", k.status == ContractStatus.CONFIRMED)
        check("pending delivery still reserves inventory", c.inventory.lendablePool("00005").onLoan == 100_000L)
        check("no fee before open leg settles", c.feeSvc.accrueDaily(D0).none { it.contractId == k.contractId })

        // Intended date reached without collateral -> settlement fail.
        SblTime.freezeAt(D0.plusDays(2)); c.price("00005", "10.00")
        val run = c.settlementSvc.settleDue(D0.plusDays(2))
        check("open leg fails without collateral", run.failed.any { it.legId == openLeg.legId })
        check("contract not active while undelivered", c.contracts.find(k.contractId)!!.status == ContractStatus.CONFIRMED)
        check("no fee while open leg failed", c.feeSvc.accrueDaily(D0.plusDays(2)).none { it.contractId == k.contractId })

        // Collateral arrives next day -> delivery settles and fees start that day.
        SblTime.freezeAt(D0.plusDays(3)); c.price("00005", "10.00")
        c.collateralSvc.postCollateral("B1", "CASH_HKD", "HKD", BigDecimal("1100000"))
        check("open leg settles once collateral covers",
            c.settlementSvc.trySettleNow(k.contractId).any { it.legId == openLeg.legId })
        check("contract ACTIVE after delivery", c.contracts.find(k.contractId)!!.status == ContractStatus.ACTIVE)
        check("actual settle date recorded", c.legRepo.find(openLeg.legId)!!.actualSettleDate == D0.plusDays(3))
        eqBd("fee accrues from open settle date",
            c.feeSvc.accrueDaily(D0.plusDays(3)).first { it.contractId == k.contractId }.dailyFeeHkd, "100.0000")

        // Recall (deadline D0+7); return instructed but settling D0+8.
        val notice = c.contractSvc.recall(k.contractId, 100_000, RecallReason.LENDER_REQUEST)
        c.contractSvc.instructReturn(k.contractId, 100_000, D0.plusDays(8))
        val pending = c.contracts.find(k.contractId)!!
        check("return instructed -> RETURN_PENDING", pending.status == ContractStatus.RETURN_PENDING)
        check("outstanding unchanged until return settles", pending.quantityOutstanding == 100_000L)

        SblTime.freezeAt(D0.plusDays(7)); c.price("00005", "10.00")
        c.settlementSvc.settleDue(D0.plusDays(7))
        eqBd("fee still accrues while return pending",
            c.feeSvc.accrueDaily(D0.plusDays(7)).first { it.contractId == k.contractId }.dailyFeeHkd, "100.0000")

        SblTime.freezeAt(D0.plusDays(8)); c.price("00005", "10.00")
        c.contractSvc.checkOverdueRecalls()
        check("unsettled return does not satisfy recall",
            c.recallRepo.find(notice.recallId)!!.status == RecallStatus.OVERDUE)
        c.settlementSvc.settleDue(D0.plusDays(8))
        val closed = c.contracts.find(k.contractId)!!
        check("closed when return settles", closed.status == ContractStatus.CLOSED && closed.quantityOutstanding == 0L)
        check("close date = return settle date", closed.actualCloseDate == D0.plusDays(8))
        check("recall satisfied by settled return", c.recallRepo.find(notice.recallId)!!.status == RecallStatus.RETURNED)
        check("no fee on return settle date", c.feeSvc.accrueDaily(D0.plusDays(8)).none { it.contractId == k.contractId })

        // Internal desk -> T+0; external confirmations are idempotent on their reference.
        // k's 1,100,000 stayed in B1's account after k closed, so it covers k2 (525,000).
        c.seedInternalDesk("D1")
        val d2 = c.locate.requestLocate("B1", "00005", 50_000)
        val k2 = c.contractSvc.openFromLocate(d2.locate.locateId, "D1")
        val leg2 = c.legRepo.openLeg(k2.contractId)!!
        check("internal desk settles T+0", leg2.intendedSettleDate == D0.plusDays(8))
        c.settlementSvc.confirm(leg2.legId, D0.plusDays(8), "CCASS-1")
        check("account surplus from a closed loan covers a new one",
            c.contracts.find(k2.contractId)!!.status == ContractStatus.ACTIVE)
        val again = c.settlementSvc.confirm(leg2.legId, D0.plusDays(8), "CCASS-1")
        check("duplicate confirmation is idempotent",
            again.status == LegStatus.SETTLED && c.contracts.find(k2.contractId)!!.status == ContractStatus.ACTIVE)
        var conflict = false
        try { c.settlementSvc.confirm(leg2.legId, D0.plusDays(8), "CCASS-2") } catch (e: IllegalStateException) { conflict = true }
        check("conflicting re-confirmation rejected", conflict)

        // A further loan takes the account short: 525,000 + 1,050,000 > 1,100,000.
        val d3 = c.locate.requestLocate("B1", "00005", 100_000)
        val k3 = c.contractSvc.openFromLocate(d3.locate.locateId, "D1")
        val leg3 = c.legRepo.openLeg(k3.contractId)!!
        var gated = false
        try { c.settlementSvc.confirm(leg3.legId, D0.plusDays(8), "CCASS-3") } catch (e: IllegalArgumentException) { gated = true }
        check("confirmation refused when the account would fall short", gated)
        c.collateralSvc.postCollateral("B1", "CASH_HKD", "HKD", BigDecimal("500000"))
        c.settlementSvc.confirm(leg3.legId, D0.plusDays(8), "CCASS-3")
        check("settles once the account is topped up",
            c.contracts.find(k3.contractId)!!.status == ContractStatus.ACTIVE)
        c.db.close()
    }

    // ---- module 4 ----

    private fun testCollateralMtmAndMarginCall() {
        section("M4 MTM coverage & margin call")
        val c = newCtx(); SblTime.freezeAt(D0)
        c.price("00005", "10.00")
        val contractId = c.approveAndOpen("B1", "00005", 100_000, settled = false) // borrowed 1,000,000; required 1.05 = 1,050,000
        c.collateralSvc.postCollateral("B1", "CASH_HKD", "HKD", BigDecimal("1100000"))
        c.settlementSvc.trySettleNow(contractId)
        val ok = c.collateralSvc.valuateAccount("B1", D0)
        check("no margin call when covered", ok.marginCall == null)
        eqBd("coverage 1.1", ok.coverageRatio, "1.100000")
        eqBd("account net surplus", ok.netHkd, "50000.0000")
        // Price jump -> under-collateralised.
        c.price("00005", "12.00")
        val breach = c.collateralSvc.valuateAccount("B1", D0)
        check("margin call raised", breach.marginCall != null)
        eqBd("deficit = 1.05*1.2m - 1.1m", breach.marginCall!!.deficitHkd, "160000.0000")
        c.db.close()
    }

    /** One account covers the whole book: surplus on one loan offsets a shortfall on another. */
    private fun testAccountNetting() {
        section("M4 account netting across contracts")
        val c = newCtx(); SblTime.freezeAt(D0)
        val a = c.approveAndOpen("B1", "00005", 100_000, settled = false) // required 1,050,000
        val b = c.approveAndOpen("B1", "00011", 50_000, settled = false)  // required 525,000
        c.collateralSvc.postCollateral("B1", "CASH_HKD", "HKD", BigDecimal("1600000"))
        c.settlementSvc.trySettleNow(a); c.settlementSvc.trySettleNow(b)
        check("single posting settles both loans", listOf(a, b).all {
            c.contracts.find(it)!!.status == ContractStatus.ACTIVE })
        val acct = c.collateralSvc.valuateAccount("B1", D0)
        check("account lists both contracts", acct.contracts.map { it.contractId }.toSet() == setOf(a, b))
        eqBd("required summed across the book", acct.requiredCollateralHkd, "1575000.0000")
        eqBd("net surplus", acct.netHkd, "25000.0000")

        c.price("00005", "11.00")                       // 1,155,000 + 525,000 = 1,680,000
        c.collateralSvc.runDailyMtm(D0)
        eqBd("one call for the account deficit",
            c.marginRepo.pendingForCounterparty("B1")!!.deficitHkd, "80000.0000")
        check("other accounts unaffected", c.marginRepo.pendingForCounterparty("B2") == null)

        c.price("00011", "6.00")                        // 1,155,000 + 315,000 = 1,470,000
        val offset = c.collateralSvc.valuateAccount("B1", D0)
        check("fall on one loan offsets rise on the other", offset.marginCall == null)
        eqBd("net surplus after offset", offset.netHkd, "130000.0000")
        c.db.close()
    }

    /** Settled client stock in the borrower's own accounts counts at the MARGIN_EQTY haircut and cap. */
    private fun testClientStockCollateral() {
        section("M4 client stock counts as collateral")
        val c = newCtx(); SblTime.freezeAt(D0)
        val contractId = c.approveAndOpen("B1", "00005", 100_000, settled = false) // required 1,050,000
        c.price("00700", "10.00")
        c.inventory.onPositionSettled("B1", "00700", AccountType.CLIENT_DISCRETIONARY, 100_000) // mv 1,000,000
        c.collateralSvc.postCollateral("B1", "CASH_HKD", "HKD", BigDecimal("800000"))
        c.settlementSvc.trySettleNow(contractId)
        check("client stock makes up the cash shortfall",
            c.contracts.find(contractId)!!.status == ContractStatus.ACTIVE)

        val acct = c.collateralSvc.valuateAccount("B1", D0)
        val stock = acct.collateral.first { it.source == CollateralSource.CLIENT_STOCK }
        check("client line for the holding", stock.assetId == "00700" && stock.excludedReason == null)
        eqBd("client stock after 25% haircut", stock.adjustedValueHkd, "750000.0000")
        eqBd("gross collateral", acct.grossCollateralHkd, "1550000.0000")
        eqBd("eligible = cash + 30% cap", acct.eligibleCollateralHkd, "1115000.0000")

        // Holding the borrowed stock is wrong-way: it must not count.
        c.inventory.onPositionSettled("B1", "00005", AccountType.CLIENT_NON_DISC, 50_000)
        val ww = c.collateralSvc.valuateAccount("B1", D0).collateral.first {
            it.source == CollateralSource.CLIENT_STOCK && it.assetId == "00005" }
        check("borrowed stock held by the client excluded", ww.excludedReason != null)
        eqBd("eligible unchanged by wrong-way holding",
            c.collateralSvc.valuateAccount("B1", D0).eligibleCollateralHkd, "1115000.0000")
        c.db.close()
    }

    private fun testCollateralSubstitutionGuard() {
        section("M4 substitution must not reduce value")
        val c = newCtx(); SblTime.freezeAt(D0)
        c.price("00005", "10.00")
        val contractId = c.approveAndOpen("B1", "00005", 100_000, settled = false)
        val pos = c.collateralSvc.postCollateral("B1", "CASH_HKD", "HKD", BigDecimal("100000"))
        var threw = false
        try {
            c.collateralSvc.substitute(pos.positionId, "CASH_HKD", "HKD", BigDecimal("90000"))
        } catch (e: IllegalArgumentException) { threw = true }
        check("rejects lower-value substitute", threw)
        val good = c.collateralSvc.substitute(pos.positionId, "CASH_HKD", "HKD", BigDecimal("120000"))
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
        c.approveAndOpen("B1", "00005", 100_000, settled = false) // borrowed 1,000,000; required 1,050,000
        val before = c.collateralRepo.activeForCounterparty("B1").size
        var threw = false
        try {
            c.collateralSvc.substitute(
                c.collateralSvc.postCollateral("B1", "CASH_HKD", "HKD",
                    BigDecimal("100000")).positionId,
                "CASH_HKD", "HKD", BigDecimal("90000")
            )
        } catch (e: IllegalArgumentException) { threw = true }
        check("rejected substitution throws", threw)
        check("rejected substitution leaves no orphan collateral",
            c.collateralRepo.activeForCounterparty("B1").size == before + 1)

        // Post exactly enough, then try to give it back while the borrow is still open.
        val needed = c.collateralSvc.postCollateral("B1", "CASH_HKD", "HKD",
            BigDecimal("1050000"))
        var refused = false
        try { c.collateralSvc.releaseCollateral(needed.positionId) }
        catch (e: IllegalArgumentException) { refused = true }
        check("refuses release that would breach required ratio", refused)
        check("position still ACTIVE after refusal",
            c.collateralRepo.find(needed.positionId)!!.status == CollateralStatus.ACTIVE)

        // Surplus on top of full cover may be returned.
        val surplus = c.collateralSvc.postCollateral("B1", "CASH_HKD", "HKD",
            BigDecimal("500000"))
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
        c.approveAndOpen("B1", "00005", 100_000, settled = false)
        var threw = false
        try {
            c.collateralSvc.postCollateral("B1", "MARGIN_EQTY", "00005", BigDecimal("200000"))
        } catch (e: IllegalArgumentException) { threw = true }
        check("rejects the borrowed stock as its own collateral", threw)
        check("nothing persisted", c.collateralRepo.activeForCounterparty("B1").isEmpty())
        // A different security is acceptable collateral.
        c.price("00700", "10.00")
        val other = c.collateralSvc.postCollateral("B1", "MARGIN_EQTY", "00700", BigDecimal("200000"))
        check("accepts an unrelated security", other.status == CollateralStatus.ACTIVE)
        // Borrowing that security later makes the pledge wrong-way: it stops counting.
        c.approveAndOpen("B1", "00700", 10_000)
        val line = c.collateralSvc.valuateAccount("B1", D0).collateral.first { it.referenceId == other.positionId }
        check("pledge excluded once its stock is borrowed", line.excludedReason != null)
        c.db.close()
    }

    /** A worsening deficit must restate the open call rather than leave a stale amount. */
    private fun testMarginCallRestated() {
        section("M4 margin call restated when deficit worsens")
        val c = newCtx(); SblTime.freezeAt(D0)
        c.price("00005", "10.00")
        val contractId = c.approveAndOpen("B1", "00005", 100_000, settled = false)
        c.collateralSvc.postCollateral("B1", "CASH_HKD", "HKD", BigDecimal("1100000"))
        c.settlementSvc.trySettleNow(contractId)
        check("open leg settled once covered", c.contracts.find(contractId)!!.status == ContractStatus.ACTIVE)
        c.price("00005", "12.00")                       // required 1,260,000 vs 1,100,000
        c.collateralSvc.runDailyMtm(D0)
        val first = c.marginRepo.pendingForCounterparty("B1")!!
        eqBd("initial deficit", first.deficitHkd, "160000.0000")

        c.price("00005", "14.00")                       // required 1,470,000 vs 1,100,000
        c.collateralSvc.runDailyMtm(D0)
        val open = c.marginRepo.pendingForCounterparty("B1")!!
        check("still a single open call", open.callId == first.callId)
        eqBd("deficit restated upward", open.deficitHkd, "370000.0000")
        check("call deadline is not in the past", !open.deadline.isBefore(SblTime.now()))
        c.db.close()
    }

    /** The required ratio has a regulatory floor; a general borrow uses 1.00, a short sale 1.05. */
    private fun testCollateralRatioFloor() {
        section("M4 collateral ratio floor")
        val c = newCtx(); SblTime.freezeAt(D0)
        var threw = false
        try { c.approveAndOpen("B1", "00005", 100_000, settled = false, ratio = BigDecimal("1.00")) }
        catch (e: IllegalArgumentException) { threw = true }
        check("short sale below 1.05 rejected", threw)
        val general = c.contracts.find(c.approveAndOpen("B1", "00011", 100_000, settled = false, shortSale = false))!!
        eqBd("general borrow defaults to 1.00", general.collateralRatioRequired, "1.00")
        check("general borrow flagged non-short", !general.isShortSaleBorrow)
        threw = false
        try { c.approveAndOpen("B1", "00012", 100_000, settled = false, shortSale = false, ratio = BigDecimal("0.90")) }
        catch (e: IllegalArgumentException) { threw = true }
        check("general borrow below 1.00 rejected", threw)
        val higher = c.contracts.find(c.approveAndOpen("B1", "00013", 100_000, settled = false, ratio = BigDecimal("1.20")))!!
        eqBd("ratio above the floor accepted", higher.collateralRatioRequired, "1.20")
        c.db.close()
    }

    /** Collateral is valued from system prices / FX, never from a caller-supplied price. */
    private fun testCollateralSystemPricing() {
        section("M4 collateral valued by the system")
        val c = newCtx(); SblTime.freezeAt(D0)
        c.approveAndOpen("B1", "00005", 100_000, settled = false)
        var threw = false
        try { c.collateralSvc.postCollateral("B1", "MARGIN_EQTY", "00700", BigDecimal("100000")) }
        catch (e: IllegalArgumentException) { threw = true }
        check("equity without a market price rejected", threw)
        threw = false
        try { c.collateralSvc.postCollateral("B1", "CASH_USD", "USD", BigDecimal("10000")) }
        catch (e: IllegalArgumentException) { threw = true }
        check("foreign cash without an FX rate rejected", threw)
        check("nothing persisted", c.collateralRepo.activeForCounterparty("B1").isEmpty())

        c.price("00700", "20.00")
        val eq = c.collateralSvc.postCollateral("B1", "MARGIN_EQTY", "00700", BigDecimal("100000"))
        eqBd("equity valued at market price", eq.marketValueLocal, "2000000")
        eqBd("equity haircut 25%", eq.adjustedValueHkd, "1500000")
        c.market.putFx("USD", D0, BigDecimal("7.8"))
        val usd = c.collateralSvc.postCollateral("B1", "CASH_USD", "USD", BigDecimal("10000"))
        eqBd("USD cash at face x FX", usd.marketValueHkd, "78000")
        eqBd("USD cash haircut 0.8%", usd.adjustedValueHkd, "77376")
        c.db.close()
    }

    /** A type counts only against the kinds of borrow it is eligible for; ineligible-for-all is rejected. */
    private fun testCollateralEligibility() {
        section("M4 collateral eligibility")
        val c = newCtx(); SblTime.freezeAt(D0)
        c.db.update("UPDATE collateral_type_config SET eligible_short_sale=0 WHERE collateral_type='BANK_GUAR'")
        c.db.update("UPDATE collateral_type_config SET eligible_short_sale=0, eligible_general=0 WHERE collateral_type='CASH_RMB'")
        val shortId = c.approveAndOpen("B1", "00005", 100_000, settled = false)
        var threw = false
        try { c.collateralSvc.postCollateral("B1", "CASH_RMB", "CNY", BigDecimal("1000")) }
        catch (e: IllegalArgumentException) { threw = true }
        check("type eligible for no borrow rejected", threw)
        val g = c.collateralSvc.postCollateral("B1", "BANK_GUAR", "GUAR-1", BigDecimal("1100000"))
        check("general-only type accepted into the account", g.status == CollateralStatus.ACTIVE)
        c.settlementSvc.trySettleNow(shortId)
        check("it does not cover a short-sale borrow",
            c.contracts.find(shortId)!!.status == ContractStatus.CONFIRMED)
        val generalId = c.approveAndOpen("B1", "00011", 100_000, settled = false, shortSale = false)
        c.settlementSvc.trySettleNow(generalId)
        check("same guarantee covers a general borrow",
            c.contracts.find(generalId)!!.status == ContractStatus.ACTIVE)
        val cover = c.collateralSvc.valuateAccount("B1", D0).byType.first { it.collateralType == "BANK_GUAR" }
        eqBd("counted up to the general requirement", cover.eligibleValueHkd, "1000000.0000")
        c.db.close()
    }

    /** A type with a concentration limit counts only up to that share of required collateral. */
    private fun testCollateralConcentrationCap() {
        section("M4 collateral concentration cap")
        val c = newCtx(); SblTime.freezeAt(D0)
        val contractId = c.approveAndOpen("B1", "00005", 100_000, settled = false) // required 1,050,000
        val k = c.contracts.find(contractId)!!
        c.price("00700", "10.00")
        c.collateralSvc.postCollateral("B1", "MARGIN_EQTY", "00700", BigDecimal("200000")) // adj 1,500,000
        val capped = c.collateralSvc.valuateAccount("B1", D0, include = k)
        eqBd("equities capped at 30% of required", capped.eligibleCollateralHkd, "315000")
        check("margin call despite raw over-cover", capped.marginCall != null)

        val cash = c.collateralSvc.postCollateral("B1", "CASH_HKD", "HKD", BigDecimal("735000"))
        val covered = c.collateralSvc.valuateAccount("B1", D0, include = k)
        eqBd("cash + capped equities", covered.eligibleCollateralHkd, "1050000")
        check("covered once cash tops up", covered.marginCall == null)

        val before = c.collateralRepo.activeForCounterparty("B1").size
        var threw = false
        try { c.collateralSvc.substitute(cash.positionId, "MARGIN_EQTY", "00700", BigDecimal("100000")) }
        catch (e: IllegalArgumentException) { threw = true }
        check("substitution beyond the cap rejected", threw)
        check("no orphan after rejected substitution", c.collateralRepo.activeForCounterparty("B1").size == before)
        c.db.close()
    }

    /** Wrong-way risk also covers other lines of the borrowed security's issuer. */
    private fun testSameIssuerWrongWay() {
        section("M4 same-issuer wrong-way collateral rejected")
        val c = newCtx(); SblTime.freezeAt(D0)
        c.approveAndOpen("B1", "00005", 100_000, settled = false)
        c.inventory.upsertDesignatedSecurity("00005", "HSBC ord", 20_000_000_000, issuerId = "HSBC")
        c.inventory.upsertDesignatedSecurity("04338", "HSBC pref", 1_000_000_000, issuerId = "hsbc")
        c.price("04338", "100.00")
        var threw = false
        try { c.collateralSvc.postCollateral("B1", "MARGIN_EQTY", "04338", BigDecimal("20000")) }
        catch (e: IllegalArgumentException) { threw = true }
        check("same issuer rejected", threw)
        check("nothing persisted", c.collateralRepo.activeForCounterparty("B1").isEmpty())
        c.db.close()
    }

    /** No price/FX must be visible (audited, release refused); foreign stocks are valued in HKD. */
    private fun testUnpricedAndForeignBorrow() {
        section("M4 unpriced MTM and non-HKD borrowed value")
        val c = newCtx(); SblTime.freezeAt(D0)
        val contractId = c.approveAndOpen("B1", "09988", 100_000) // ACTIVE, cash 1,100,000
        val cash = c.collateralRepo.activeForCounterparty("B1").first()
        c.inventory.upsertDesignatedSecurity("09988", "USD line", 20_000_000_000, currency = "USD")

        val unpriced = c.collateralSvc.runDailyMtm(D0).first { it.counterpartyId == "B1" }
        check("contract reported as unpriced", unpriced.priceMissing && contractId in unpriced.unpricedContracts)
        check("unpriced MTM audited",
            c.audit.history("COLLATERAL", contractId).any { it.eventType == "MTM_NO_PRICE" })
        check("no margin call on missing price", c.marginRepo.pendingForCounterparty("B1") == null)
        var refused = false
        try { c.collateralSvc.releaseCollateral(cash.positionId) } catch (e: IllegalStateException) { refused = true }
        check("release refused without a price", refused)

        c.market.putFx("USD", D0, BigDecimal("7.8"))
        val valued = c.collateralSvc.runDailyMtm(D0).first { it.counterpartyId == "B1" }
        eqBd("borrowed converted to HKD", valued.borrowedValueHkd, "7800000")
        eqBd("deficit in HKD", c.marginRepo.pendingForCounterparty("B1")!!.deficitHkd, "7090000")
        c.db.close()
    }

    /** PENDING calls past their deadline are escalated, and can still be resolved. */
    private fun testMarginCallEscalation() {
        section("M4 margin call escalation")
        val c = newCtx(); SblTime.freezeAt(D0)
        c.approveAndOpen("B1", "00005", 100_000)
        c.price("00005", "12.00")
        c.collateralSvc.runDailyMtm(D0)
        val call = c.marginRepo.pendingForCounterparty("B1")!!
        check("nothing escalated before deadline", c.collateralSvc.escalateOverdueMarginCalls().isEmpty())
        val late = D0.atTime(18, 0).atZone(SblTime.HKT).toInstant()
        val escalated = c.collateralSvc.escalateOverdueMarginCalls(late)
        check("overdue call escalated", escalated.map { it.callId } == listOf(call.callId) &&
            c.marginRepo.find(call.callId)!!.status == MarginCallStatus.ESCALATED)
        check("escalation audited",
            c.audit.history("COLLATERAL", "B1").any { it.eventType == "MARGIN_CALL_ESCALATED" })
        check("escalation is idempotent", c.collateralSvc.escalateOverdueMarginCalls(late).isEmpty())
        val resolved = c.collateralSvc.resolveMarginCall(call.callId, ResolutionMethod.CASH_TOPUP)
        check("escalated call can be resolved", resolved.status == MarginCallStatus.RESOLVED)
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

    /** Cash collateral earns benchmark - spread, netted against the fee on the statement. */
    private fun testCashCollateralRebate() {
        section("M5 cash-collateral rebate")
        val c = newCtx(); SblTime.freezeAt(D0)
        c.price("00005", "10.00"); c.seedDefaultRate("0.0365")   // fee 100.00/day on 1,000,000
        c.approveAndOpen("B1", "00005", 100_000)                   // posts 1,100,000 HKD cash
        c.index(FloatingIndex.HIBOR_1M, "0.0390")                  // 3.90% - 25bps default = 3.65%
        c.feeSvc.accrueDaily(D0)

        val b1 = c.feeSvc.accrueRebates(D0).single { it.counterpartyId == "B1" }
        eqBd("rebate rate = HIBOR - default spread", b1.rebateRate, "0.0365")
        eqBd("daily rebate on 1.1m HKD", b1.dailyRebateHkd, "110.0000")

        // Spread above the benchmark -> negative rebate; internal desks and cash with no benchmark fixing skipped.
        c.counterpartyRepo.insert(Counterparty("B3", "Borrower3", CounterpartyType.BORROWER,
            rebateSpreadBps = 500, createdAt = SblTime.now()))
        c.collateralSvc.postCollateral("B3", "CASH_HKD", "HKD", BigDecimal("365000"))
        c.seedInternalDesk("D1")
        c.collateralSvc.postCollateral("D1", "CASH_HKD", "HKD", BigDecimal("1000000"))
        c.market.putFx("USD", D0, BigDecimal("7.80"))
        c.collateralSvc.postCollateral("B1", "CASH_USD", "USD", BigDecimal("10000"))

        val rerun = c.feeSvc.accrueRebates(D0)
        check("already-accrued position not re-accrued", rerun.none { it.positionId == b1.positionId })
        eqBd("spread above benchmark gives negative rebate",
            rerun.single { it.counterpartyId == "B3" }.dailyRebateHkd, "-11.0000")
        check("internal desk cash earns no rebate", rerun.none { it.counterpartyId == "D1" })
        check("USD cash without SOFR fixing not accrued", rerun.none { it.currency == "USD" })
        check("missing benchmark audited",
            c.audit.history("FEE", "B1").any { it.eventType == "REBATE_NO_RATE" })

        val first = c.feeSvc.generateStatement("B1", D0, D0)
        eqBd("statement fee", first.totalFeeHkd, "100.0000")
        eqBd("statement rebate", first.totalRebateHkd, "110.0000")
        eqBd("net: firm owes borrower", first.netAmountHkd, "-10.0000")
        c.feeSvc.settleStatement(first.statementId)
        eqBd("settled rebate not re-billed", c.feeSvc.generateStatement("B1", D0, D0).totalRebateHkd, "0.0000")
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

    // ---- inventory sourcing (contract allocations) ----

    /** Account types map to allocation sources; only settled, consented accounts carry capacity. */
    private fun testAllocationSourceMapping() {
        section("ALLOC account type -> source mapping")
        val c = newCtx(); SblTime.freezeAt(D0)
        val a = c.allocationSvc
        check("PROPRIETARY -> FIRM", a.sourceTypeOf(AccountType.PROPRIETARY) == AllocationSource.FIRM)
        check("CLIENT_DISCRETIONARY -> CLIENT_MARGIN",
            a.sourceTypeOf(AccountType.CLIENT_DISCRETIONARY) == AllocationSource.CLIENT_MARGIN)
        check("LENDING_POOL -> THIRD_PARTY", a.sourceTypeOf(AccountType.LENDING_POOL) == AllocationSource.THIRD_PARTY)
        check("CLIENT_NON_DISC is not a source (no consent)", a.sourceTypeOf(AccountType.CLIENT_NON_DISC) == null)
        check("PLEDGED is not a source", a.sourceTypeOf(AccountType.PLEDGED) == null)
        c.seedParties(); c.seedOwner("C1")
        val pending = c.inventory.onPositionSettled("C1", "00005", AccountType.CLIENT_DISCRETIONARY, 50_000,
            SettlementStatus.PENDING_T2)
        check("unsettled position has no capacity", a.positionCapacity(pending) == 0L)
        val nonDisc = c.inventory.onPositionSettled("C1", "00005", AccountType.CLIENT_NON_DISC, 50_000)
        check("non-discretionary client stock has no capacity", a.positionCapacity(nonDisc) == 0L)
        c.db.close()
    }

    /** Firm stock is used before client margin; a principal desk may draw on any owner's stock. */
    private fun testAllocationFirmFirst() {
        section("ALLOC open sources firm first, then client margin")
        val c = allocCtx(); SblTime.freezeAt(D0)
        c.seedMarket("00005")
        c.seedInternalDesk("D1"); c.seedOwner("C1")
        c.inventory.onPositionSettled("D1", "00005", AccountType.PROPRIETARY, 60_000)
        c.inventory.onPositionSettled("C1", "00005", AccountType.CLIENT_DISCRETIONARY, 100_000)
        c.inventory.onPositionSettled("C1", "00005", AccountType.CLIENT_NON_DISC, 1_000_000)
        val loan = c.openLoan("B1", "00005", 100_000, "D1")
        val rows = c.allocationRepo.currentForContract(loan)
        check("two allocations", rows.size == 2)
        check("firm stock exhausted first", c.allocQty(loan, AllocationSource.FIRM) == 60_000L)
        check("client margin tops up the rest", c.allocQty(loan, AllocationSource.CLIENT_MARGIN) == 40_000L)
        check("allocations sum to contract qty", rows.sumOf { it.quantity } == 100_000L)
        check("client slice linked to the discretionary position",
            rows.first { it.sourceType == AllocationSource.CLIENT_MARGIN }.positionId ==
                c.positionRepo.findKey("C1", "00005", AccountType.CLIENT_DISCRETIONARY)!!.positionId)
        check("allocation audited", c.audit.history("CONTRACT", loan).any { it.eventType == "ALLOCATED" })
        c.db.close()
    }

    /** Partial returns release client margin before firm stock; history stays point-in-time. */
    private fun testAllocationReleaseOrder() {
        section("ALLOC partial return releases client margin first")
        val c = allocCtx(); SblTime.freezeAt(D0)
        c.seedMarket("00005")
        c.seedInternalDesk("D1"); c.seedOwner("C1")
        c.inventory.onPositionSettled("D1", "00005", AccountType.PROPRIETARY, 60_000)
        c.inventory.onPositionSettled("C1", "00005", AccountType.CLIENT_DISCRETIONARY, 100_000)
        val loan = c.openLoan("B1", "00005", 100_000, "D1")
        c.settle(loan)

        val d1 = D0.plusDays(1)
        SblTime.freezeAt(d1); c.price("00005", "10.00")
        c.contractSvc.processReturn(loan, 30_000)
        check("client margin released first", c.allocQty(loan, AllocationSource.CLIENT_MARGIN) == 10_000L)
        check("firm untouched while client stock is out", c.allocQty(loan, AllocationSource.FIRM) == 60_000L)
        val onD0 = c.allocationRepo.activeOn(loan, D0)
        check("history keeps the D0 split (60k firm / 40k client)",
            onD0.sumOf { it.quantity } == 100_000L &&
                onD0.single { it.sourceType == AllocationSource.CLIENT_MARGIN }.quantity == 40_000L)
        check("superseded row ends on the return settle date",
            c.allocationRepo.forContract(loan).any {
                it.sourceType == AllocationSource.CLIENT_MARGIN && it.quantity == 40_000L && it.effectiveTo == d1
            })

        c.contractSvc.processReturn(loan, 20_000)
        check("client fully released before firm", c.allocQty(loan, AllocationSource.CLIENT_MARGIN) == 0L)
        check("firm released for the balance", c.allocQty(loan, AllocationSource.FIRM) == 50_000L)
        check("current allocations match outstanding",
            c.allocationRepo.currentForContract(loan).sumOf { it.quantity } ==
                c.contracts.find(loan)!!.quantityOutstanding)
        c.contractSvc.processReturn(loan, 50_000)
        check("closed contract has no current allocations", c.allocationRepo.currentForContract(loan).isEmpty())
        c.db.close()
    }

    /** A client sale is covered from other free stock first; whatever is left is recalled. */
    private fun testAllocationSubstitutionAndRecall() {
        section("ALLOC client sale -> substitute, then recall shortfall")
        val c = allocCtx(); SblTime.freezeAt(D0)
        c.seedMarket("00005")
        c.seedInternalDesk("D1"); c.seedOwner("C1"); c.seedOwner("C2")
        c.inventory.onPositionSettled("D1", "00005", AccountType.PROPRIETARY, 60_000)
        c.inventory.onPositionSettled("C1", "00005", AccountType.CLIENT_DISCRETIONARY, 100_000)
        c.inventory.onPositionSettled("C2", "00005", AccountType.CLIENT_DISCRETIONARY, 30_000)
        val loan = c.openLoan("B1", "00005", 100_000, "D1")
        c.settle(loan)
        check("largest client position used first", c.allocOwner(loan, "C1") == 40_000L && c.allocOwner(loan, "C2") == 0L)

        // C1 sells 80,000: holds 20,000 against 40,000 on loan -> 20,000 moved to C2.
        c.inventory.onPositionSettled("C1", "00005", AccountType.CLIENT_DISCRETIONARY, -80_000)
        check("deficit substituted from another client",
            c.allocOwner(loan, "C1") == 20_000L && c.allocOwner(loan, "C2") == 20_000L)
        check("no recall while substitution covers", c.recallRepo.forContract(loan).isEmpty())
        check("substitution audited",
            c.audit.history("CONTRACT", loan).any { it.eventType == "ALLOCATION_SUBSTITUTED" })

        // C1 sells the rest: only 10,000 free elsewhere -> 10,000 recalled from the borrower.
        c.inventory.onPositionSettled("C1", "00005", AccountType.CLIENT_DISCRETIONARY, -20_000)
        check("free stock moved first", c.allocOwner(loan, "C2") == 30_000L)
        val recalls = c.recallRepo.forContract(loan)
        check("shortfall recalled (SOURCE_SOLD)", recalls.size == 1 && recalls.first().recallQty == 10_000L &&
            recalls.first().recallReason == RecallReason.SOURCE_SOLD)
        check("contract RECALLED", c.contracts.find(loan)!!.status == ContractStatus.RECALLED)
        val c1Row = c.allocationRepo.currentForContract(loan).single { it.sourceOwnerId == "C1" }
        check("deficient allocation marked under recall", c1Row.quantity == 10_000L && c1Row.recalledQty == 10_000L)
        val line = c.allocationSvc.clientAssetsOnLoan("C1").single()
        check("client-asset view: C1 holds 0, 10,000 on loan, all under recall",
            line.positionQty == 0L && line.onLoanQty == 10_000L && line.underRecallQty == 10_000L &&
                line.uncoveredQty == 0L && line.loans.single().underRecallQty == 10_000L)

        // A further position event must not recall the same shortfall twice.
        c.inventory.onPositionSettled("C1", "00005", AccountType.CLIENT_DISCRETIONARY, 0)
        check("no duplicate recall", c.recallRepo.forContract(loan).size == 1)

        // Returning the recalled qty releases the deficient (C1) slice first.
        c.contractSvc.processReturn(loan, 10_000)
        check("recalled allocation released first", c.allocOwner(loan, "C1") == 0L &&
            c.allocOwner(loan, "C2") == 30_000L && c.allocQty(loan, AllocationSource.FIRM) == 60_000L)
        check("recall satisfied", c.recallRepo.forContract(loan).single().status == RecallStatus.RETURNED)
        check("C1 drops out of the client-asset view", c.allocationSvc.clientAssetsOnLoan("C1").isEmpty())
        c.db.close()
    }

    /** A source shortfall on a loan not yet delivered cannot be recalled; it is flagged instead. */
    private fun testAllocationShortfallBeforeDelivery() {
        section("ALLOC shortfall on undelivered loan is flagged, not recalled")
        val c = allocCtx(); SblTime.freezeAt(D0)
        c.seedMarket("00005")
        c.seedInternalDesk("D1"); c.seedOwner("C1")
        c.inventory.onPositionSettled("C1", "00005", AccountType.CLIENT_DISCRETIONARY, 50_000)
        val loan = c.openLoan("B1", "00005", 50_000, "D1")
        c.inventory.onPositionSettled("C1", "00005", AccountType.CLIENT_DISCRETIONARY, -50_000)
        check("no recall on a CONFIRMED loan", c.recallRepo.forContract(loan).isEmpty())
        check("shortfall flagged for operations",
            c.audit.history("CONTRACT", loan).any { it.eventType == "SOURCE_SHORTFALL_UNRESOLVED" })
        check("client-asset view shows the uncovered qty",
            c.allocationSvc.clientAssetsOnLoan("C1").single().uncoveredQty == 50_000L)
        c.db.close()
    }

    /** Client / third-party owners get their fee share pro rata to the allocation in force each day. */
    private fun testAllocationFeePassThrough() {
        section("ALLOC fee pass-through (per-owner %, global default)")
        val c = allocCtx(); SblTime.freezeAt(D0)
        c.seedMarket("00005") // 10.00 x 100,000 x 3.65% / 365 = 100.00 per day
        c.seedInternalDesk("D1"); c.seedOwner("C1", feeSharePct = "0.30"); c.seedOwner("T1")
        c.inventory.onPositionSettled("D1", "00005", AccountType.PROPRIETARY, 50_000)
        c.inventory.onPositionSettled("C1", "00005", AccountType.CLIENT_DISCRETIONARY, 30_000)
        c.inventory.onPositionSettled("T1", "00005", AccountType.LENDING_POOL, 20_000)
        val loan = c.openLoan("B1", "00005", 100_000, "D1")
        c.settle(loan)
        check("sourced firm -> client margin -> third party",
            c.allocQty(loan, AllocationSource.FIRM) == 50_000L &&
                c.allocQty(loan, AllocationSource.CLIENT_MARGIN) == 30_000L &&
                c.allocQty(loan, AllocationSource.THIRD_PARTY) == 20_000L)

        fun splitsOn(date: LocalDate): Map<String, BigDecimal> {
            val accrual = c.feeSvc.accrueDaily(date).first { it.contractId == loan }
            return c.splitRepo.forAccrual(accrual.accrualId)
                .associate { "${it.recipientRole}:${it.recipientId}" to it.splitAmountHkd }
        }
        val day0 = splitsOn(D0)
        eqBd("C1 gets its 30% on a 30% slice", day0.getValue("CLIENT_MARGIN:C1"), "9.0000")
        eqBd("T1 gets the 50% default on a 20% slice", day0.getValue("THIRD_PARTY:T1"), "10.0000")
        eqBd("lender keeps the rest", day0.getValue("LENDER:D1"), "81.0000")

        val d1 = D0.plusDays(1)
        SblTime.freezeAt(d1); c.price("00005", "10.00")
        c.contractSvc.processReturn(loan, 20_000) // client first: C1 30,000 -> 10,000
        val day1 = splitsOn(d1)                   // 80.00 on 80,000 outstanding
        eqBd("C1 share follows the reduced allocation", day1.getValue("CLIENT_MARGIN:C1"), "3.0000")
        eqBd("T1 share on 20,000 of 80,000", day1.getValue("THIRD_PARTY:T1"), "10.0000")
        eqBd("lender remainder", day1.getValue("LENDER:D1"), "67.0000")
        val backDated = c.allocationSvc.feeShares(c.contracts.find(loan)!!, D0)
        eqBd("back-dated share uses the D0 allocation", backDated.single { it.ownerId == "C1" }.fractionOfFee, "0.09")
        c.db.close()
    }

    /** A non-principal (agency) lender lends only its own stock: one allocation, no pass-through. */
    private fun testAllocationAgency() {
        section("ALLOC agency loan uses only the lender's own position")
        val c = allocCtx(); SblTime.freezeAt(D0)
        c.seedMarket("00005"); c.seedOwner("C1")
        c.inventory.onPositionSettled("L1", "00005", AccountType.LENDING_POOL, 100_000)
        c.inventory.onPositionSettled("C1", "00005", AccountType.CLIENT_DISCRETIONARY, 1_000_000)
        val loan = c.openLoan("B1", "00005", 100_000, "L1", model = TransactionModel.AGENCY)
        val row = c.allocationRepo.currentForContract(loan).single()
        check("single allocation to the lender's lending-pool position",
            row.sourceType == AllocationSource.THIRD_PARTY && row.sourceOwnerId == "L1" && row.quantity == 100_000L &&
                row.positionId == c.positionRepo.findKey("L1", "00005", AccountType.LENDING_POOL)!!.positionId)
        c.settle(loan)
        val accrual = c.feeSvc.accrueDaily(D0).first { it.contractId == loan }
        check("no pass-through when the source owner is the lender",
            c.splitRepo.forAccrual(accrual.accrualId).map { it.recipientRole } == listOf("LENDER"))

        var threw = false
        try { c.openLoan("B1", "00005", 50_000, "L1") } catch (e: IllegalArgumentException) { threw = true }
        check("agency lender cannot draw on other owners' stock", threw)
        check("client stock untouched", c.allocationRepo.current().none { it.sourceOwnerId == "C1" })
        c.db.close()
    }

    /** Corporate-action quantity changes rescale allocations; early termination releases them. */
    private fun testAllocationRescaleAndTerminate() {
        section("ALLOC CA rescale and early termination")
        val c = allocCtx(); SblTime.freezeAt(D0)
        c.seedMarket("00005")
        c.seedInternalDesk("D1"); c.seedOwner("C1")
        c.inventory.onPositionSettled("D1", "00005", AccountType.PROPRIETARY, 60_000)
        c.inventory.onPositionSettled("C1", "00005", AccountType.CLIENT_DISCRETIONARY, 100_000)
        val loan = c.openLoan("B1", "00005", 100_000, "D1")
        c.settle(loan)
        c.contractSvc.adjustQuantity(loan, BigDecimal("2"), "2:1 split")
        check("firm slice rescaled", c.allocQty(loan, AllocationSource.FIRM) == 120_000L)
        check("client slice rescaled", c.allocQty(loan, AllocationSource.CLIENT_MARGIN) == 80_000L)
        check("allocations match new outstanding",
            c.allocationRepo.currentForContract(loan).sumOf { it.quantity } == 200_000L)
        c.contractSvc.earlyTerminate(loan, "delisting")
        check("termination releases all allocations", c.allocationRepo.currentForContract(loan).isEmpty())
        c.db.close()
    }

    /** Street borrow-in booking, matched-book funding and spread, and the street-recall cascade. */
    private fun testStreetBorrowInMatchedBook() {
        section("ALLOC street borrow-in funds loan-outs (matched book)")
        val c = allocCtx(); SblTime.freezeAt(D0)
        c.seedMarket("00005", rate = "0.05")
        c.seedInternalDesk("D1"); c.seedOwner("S1")

        var threw = false
        try { c.contractSvc.bookBorrowIn("S1", "B1", "00005", 100_000, BigDecimal("0.02")) }
        catch (e: IllegalArgumentException) { threw = true }
        check("borrow-in must be booked by an INTERNAL entity", threw)
        threw = false
        try { c.contractSvc.bookBorrowIn("D1", "D1", "00005", 100_000, BigDecimal("0.02")) }
        catch (e: IllegalArgumentException) { threw = true }
        check("street lender must be external", threw)

        val bi = c.contractSvc.bookBorrowIn("S1", "D1", "00005", 100_000, BigDecimal("0.02"), settlementDate = D0)
        check("borrow-in booked CONFIRMED on the BORROW_IN side",
            bi.status == ContractStatus.CONFIRMED && bi.bookSide == BookSide.BORROW_IN)
        val pool = c.inventory.lendablePool("00005")
        check("borrowed-in qty counts as lendable supply", pool.qtyBorrowedIn == 100_000L && pool.grossLendable == 100_000L)
        check("borrow-in is not counted as on loan", pool.onLoan == 0L)
        c.settle(bi.contractId)
        check("borrow-in ACTIVE once delivered", c.contracts.find(bi.contractId)!!.status == ContractStatus.ACTIVE)

        threw = false
        try { c.allocationSvc.plan("00005", "L1", 80_000, preferredBorrowInId = bi.contractId) }
        catch (e: IllegalArgumentException) { threw = true }
        check("only a principal (INTERNAL) lender may on-lend a borrow-in", threw)

        val loan = c.openLoan("B1", "00005", 80_000, "D1", borrowInId = bi.contractId)
        val row = c.allocationRepo.currentForContract(loan).single()
        check("loan funded from the borrow-in", row.sourceType == AllocationSource.STREET_BORROW &&
            row.sourceContractId == bi.contractId && row.sourceOwnerId == "S1" && row.quantity == 80_000L)
        c.settle(loan)

        threw = false
        try { c.contractSvc.instructReturn(bi.contractId, 30_000, D0) } catch (e: IllegalArgumentException) { threw = true }
        check("on-lent borrowed shares cannot go back to the street", threw)

        val mb = c.allocationSvc.matchedBook("00005").single()
        check("matched book pairs the loan", mb.allocatedQty == 80_000L && mb.unpairedQty == 20_000L &&
            mb.pairs.single().loanOutId == loan && mb.streetLenderId == "S1")
        eqBd("spread = 5% - 2%", mb.pairs.single().spreadBps, "300.00")
        eqBd("daily spread on 80,000", mb.pairs.single().dailySpreadHkd!!, "65.7534")
        eqBd("net = spread - cost of unpaired 20,000", mb.netDailyHkd!!, "54.7945")

        // Street recalls everything: 30,000 firm stock substitutes, 50,000 is recalled from the borrower.
        c.inventory.onPositionSettled("D1", "00005", AccountType.PROPRIETARY, 30_000)
        c.contractSvc.recall(bi.contractId, 100_000, RecallReason.LENDER_REQUEST)
        check("firm stock substitutes part of the recalled borrow", c.allocQty(loan, AllocationSource.FIRM) == 30_000L)
        val street = c.allocationRepo.currentForContract(loan).single { it.sourceType == AllocationSource.STREET_BORROW }
        check("remaining street slice under recall", street.quantity == 50_000L && street.recalledQty == 50_000L)
        val cascade = c.recallRepo.forContract(loan).single()
        check("cascade recall issued (SOURCE_RECALLED)",
            cascade.recallQty == 50_000L && cascade.recallReason == RecallReason.SOURCE_RECALLED)
        check("recalled borrow-in adds no free supply", c.allocationSvc.borrowedInSupply("00005") == 0L)

        c.contractSvc.processReturn(loan, 50_000)
        check("client return frees the borrow-in", c.allocationSvc.allocatedFromBorrowIn(bi.contractId) == 0L)
        check("loan now fully on firm stock",
            c.allocationRepo.currentForContract(loan).map { it.sourceType } == listOf(AllocationSource.FIRM))
        check("cascade recall satisfied", c.recallRepo.find(cascade.recallId)!!.status == RecallStatus.RETURNED)
        c.contractSvc.processReturn(bi.contractId, 100_000)
        check("borrow-in returned to the street and closed",
            c.contracts.find(bi.contractId)!!.status == ContractStatus.CLOSED)
        check("street recall satisfied", c.recallRepo.forContract(bi.contractId).single().status == RecallStatus.RETURNED)
        c.db.close()
    }

    /** Pair an existing loan onto a borrow-in to free firm stock; explicit borrow-in preference on open. */
    private fun testBorrowInPairing() {
        section("ALLOC matched-book pairing")
        val c = allocCtx(); SblTime.freezeAt(D0)
        c.seedMarket("00005", rate = "0.05")
        c.seedInternalDesk("D1"); c.seedOwner("S1")
        c.inventory.onPositionSettled("D1", "00005", AccountType.PROPRIETARY, 100_000)
        val loan = c.openLoan("B1", "00005", 100_000, "D1")
        check("loan initially on firm stock", c.allocQty(loan, AllocationSource.FIRM) == 100_000L)

        val bi = c.contractSvc.bookBorrowIn("S1", "D1", "00005", 60_000, BigDecimal("0.02"), settlementDate = D0)
        val moved = c.contractSvc.pairWithBorrowIn(loan, bi.contractId, 60_000)
        check("pairing moves the requested qty", moved == 60_000L)
        check("loan split firm 40,000 / street 60,000",
            c.allocQty(loan, AllocationSource.FIRM) == 40_000L &&
                c.allocQty(loan, AllocationSource.STREET_BORROW) == 60_000L)
        check("pairing audited", c.audit.history("CONTRACT", loan).any { it.eventType == "PAIRED" })
        check("borrow-in fully on-lent", c.allocationSvc.allocatedFromBorrowIn(bi.contractId) == 60_000L)

        // An explicit borrow-in overrides firm-first; without one freed firm stock goes first.
        val bi2 = c.contractSvc.bookBorrowIn("S1", "D1", "00005", 40_000, BigDecimal("0.02"), settlementDate = D0)
        val viaStreet = c.openLoan("B1", "00005", 30_000, "D1", borrowInId = bi2.contractId)
        check("preferred borrow-in used first",
            c.allocationRepo.currentForContract(viaStreet).single().sourceContractId == bi2.contractId)
        val viaFirm = c.openLoan("B1", "00005", 20_000, "D1")
        check("without preference firm stock goes first", c.allocQty(viaFirm, AllocationSource.FIRM) == 20_000L)

        var threw = false
        try { c.contractSvc.pairWithBorrowIn(loan, viaFirm, 10_000) } catch (e: IllegalArgumentException) { threw = true }
        check("pairing with a loan-out is rejected", threw)
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
            agentId = "A1", agentSplitPct = BigDecimal("0.25"), settlementDate = D0)
        c.settle(contract.contractId)
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

    // ---- REST API ----

    private fun testRestApi() {
        section("API REST endpoints")
        val c = newCtx(); SblTime.freezeAt(D0)
        c.seedParties(); c.seedSecurity("00700", 9_000_000_000, SecurityType.EQTY)
        c.price("00700", "300.00"); c.seedDefaultRate("0.005")
        c.inventory.onPositionSettled("L1", "00700", AccountType.PROPRIETARY, 1_000_000)

        val opKey = ApiKeyAuth.randomKey()
        val b1Key = ApiKeyAuth.randomKey()
        val b2Key = ApiKeyAuth.randomKey()
        val auth = ApiKeyAuth.of(
            opKey to Principal("ops", Role.OPERATOR, null),
            b1Key to Principal("b1", Role.USER, "B1"),
            b2Key to Principal("b2", Role.USER, "B2"))
        val services = SblServices(c.db)
        val server = ApiServer(c.db, services.audit, auth, SblApi(services).routes())
        val port = server.start("127.0.0.1", 0).port
        val http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build()

        fun call(method: String, path: String, key: String?, body: String? = null,
                 contentType: String = "application/json"): Pair<Int, Any?> {
            val b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/v1$path"))
            if (key != null) b.header("X-API-Key", key)
            if (body != null) b.header("Content-Type", contentType)
            b.method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
            val r = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
            return r.statusCode() to r.body().takeIf { it.isNotBlank() }?.let { Json.parse(it) }
        }
        fun Any?.f(key: String): Any? = (this as? Map<*, *>)?.get(key)

        try {
            check("health is public", call("GET", "/health", null).first == 200)
            check("missing key -> 401", call("GET", "/me", null).first == 401)
            check("unknown key -> 401", call("GET", "/me", ApiKeyAuth.randomKey()).first == 401)
            check("/me returns caller", call("GET", "/me", b1Key).second.f("counterpartyId") == "B1")
            check("user on operator endpoint -> 403", call("GET", "/counterparties", b1Key).first == 403)
            check("unknown path -> 404", call("GET", "/nope", opKey).first == 404)
            check("wrong method -> 405", call("DELETE", "/locates", b1Key).first == 405)
            check("malformed JSON -> 400", call("POST", "/locates", b1Key, "{oops").first == 400)
            check("non-JSON body -> 415", call("POST", "/locates", b1Key, "x", "text/plain").first == 415)
            check("missing field -> 400", call("POST", "/locates", b1Key, """{"stockCode":"00700"}""").first == 400)

            // Booking flow as borrower B1: locate -> contract -> collateral.
            check("user cannot act for another borrower -> 403", call("POST", "/locates", b1Key,
                """{"borrowerId":"B2","stockCode":"00700","quantity":100000}""").first == 403)
            val (ls, decision) = call("POST", "/locates", b1Key, """{"stockCode":"00700","quantity":100000}""")
            check("locate created (201)", ls == 201)
            check("locate approved", decision.f("outcome") == "APPROVED")
            check("locate booked for caller", decision.f("locate").f("borrowerId") == "B1")
            val locateId = decision.f("locate").f("locateId") as String

            check("other user cannot book on B1's locate", call("POST", "/contracts", b2Key,
                """{"locateId":"$locateId","lenderId":"L1"}""").first == 404)
            check("borrower cannot set collateralRatio -> 403", call("POST", "/contracts", b1Key,
                """{"locateId":"$locateId","lenderId":"L1","collateralRatio":0.5}""").first == 403)
            check("borrower cannot declare a general borrow -> 403", call("POST", "/contracts", b1Key,
                """{"locateId":"$locateId","lenderId":"L1","shortSale":false}""").first == 403)
            val (cs, contract) = call("POST", "/contracts", b1Key, """{"locateId":"$locateId","lenderId":"L1"}""")
            check("contract opened (201)", cs == 201)
            val contractId = contract.f("contractId") as String
            check("contract hidden from other user", call("GET", "/contracts/$contractId", b2Key).first == 404)
            check("owner sees contract", call("GET", "/contracts/$contractId", b1Key).first == 200)
            check("contract list scoped to caller",
                (call("GET", "/contracts", b1Key).second as List<*>).size == 1 &&
                (call("GET", "/contracts", b2Key).second as List<*>).isEmpty())

            // Collateral is posted to the borrower's account, not to a contract.
            check("unknown collateral type -> 422", call("POST", "/counterparties/B1/collateral", b1Key,
                """{"collateralType":"GOLD","assetId":"X","quantity":1}""").first == 422)
            check("user cannot post to another account -> 404", call("POST", "/counterparties/B2/collateral", b1Key,
                """{"collateralType":"CASH_HKD","assetId":"HKD","quantity":1}""").first == 404)
            val (ps, _) = call("POST", "/counterparties/B1/collateral", b1Key,
                """{"collateralType":"CASH_HKD","assetId":"HKD","quantity":40000000}""")
            check("collateral posted (201)", ps == 201)
            check("collateral listed", (call("GET", "/counterparties/B1/collateral", b1Key).second as List<*>).size == 1)
            check("account hidden from other user",
                call("GET", "/counterparties/B1/collateral-account", b2Key).first == 404)

            // Settlement legs: booked CONFIRMED with a T+2 OPEN leg.
            check("contract booked CONFIRMED", contract.f("status") == "CONFIRMED")
            val legs = call("GET", "/contracts/$contractId/legs", b1Key).second as List<*>
            check("open leg pending for T+2", legs.size == 1 && legs.first().f("legType") == "OPEN" &&
                legs.first().f("status") == "PENDING" && legs.first().f("intendedSettleDate") == D0.plusDays(2).toString())
            check("user cannot list all legs -> 403", call("GET", "/settlement/legs", b1Key).first == 403)
            check("cannot return before delivery -> 422", call("POST", "/contracts/$contractId/returns", b1Key,
                """{"quantity":1000}""").first == 422)

            check("user cannot run EOD -> 403", call("POST", "/operations/eod", b1Key).first == 403)
            val (es, eod) = call("POST", "/operations/eod", opKey)
            check("operator EOD runs", es == 200 && eod.f("contractsValued") != null)

            // T+2: EOD settles the covered open leg; a return then opens a T+2 RETURN leg.
            SblTime.freezeAt(D0.plusDays(2)); c.price("00700", "300.00")
            val (_, eod2) = call("POST", "/operations/eod", opKey)
            check("EOD settles due open leg", (eod2.f("legsSettled") as List<*>).size == 1)
            check("contract ACTIVE after settlement", call("GET", "/contracts/$contractId", b1Key).second.f("status") == "ACTIVE")
            val (as1, acct) = call("GET", "/counterparties/B1/collateral-account", b1Key)
            check("account valuation nets the book", as1 == 200 &&
                (acct.f("requiredCollateralHkd") as Number).toDouble() == 31_500_000.0 &&
                (acct.f("eligibleCollateralHkd") as Number).toDouble() == 40_000_000.0 &&
                acct.f("marginCall") == null)
            val (rs, ret) = call("POST", "/contracts/$contractId/returns", b1Key, """{"quantity":40000}""")
            check("return instructed (201)", rs == 201 && ret.f("legType") == "RETURN" &&
                ret.f("intendedSettleDate") == D0.plusDays(4).toString())
            val afterReturn = call("GET", "/contracts/$contractId", b1Key).second
            check("outstanding unchanged until return settles",
                afterReturn.f("status") == "RETURN_PENDING" && (afterReturn.f("quantityOutstanding") as Number).toLong() == 100_000L)
            val (cs2, conf) = call("POST", "/settlement/legs/${ret.f("legId")}/confirm", opKey, """{"externalRef":"CCASS-9"}""")
            check("operator confirms return leg", cs2 == 200 && conf.f("status") == "SETTLED")
            check("settled return reduces outstanding",
                (call("GET", "/contracts/$contractId", b1Key).second.f("quantityOutstanding") as Number).toLong() == 60_000L)
            check("API calls are audited",
                services.audit.history("API", "POST /api/v1/locates").any { it.actorId == "b1" })
        } finally {
            server.stop()
            c.db.close()
        }
    }

    private fun section(title: String) = println("\n[$title]")

    private fun newCtx() = Ctx(Database.openInMemory())

    /** Allocation scenarios lend a whole pool to one borrower, so lift the per-borrower concentration cap. */
    private fun allocCtx() = Ctx(Database.openInMemory(), LocateLimits(concentrationCapPct = BigDecimal.ONE))

    /** Minimal wiring + seeding harness for a single test's in-memory database. */
    private class Ctx(val db: Database, locateLimits: LocateLimits = LocateLimits()) {
        val audit = AuditService(AuditRepo(db))
        val market = MarketDataRepo(db)
        val contracts = ContractRepo(db)
        val collateralRepo = CollateralPositionRepo(db)
        val splitRepo = FeeSplitRepo(db)
        val rates = RateScheduleRepo(db)
        val positionRepo = PositionRepo(db)
        private val locateRepo = LocateRepo(db)
        private val designatedRepo = DesignatedSecurityRepo(db)
        val counterpartyRepo = CounterpartyRepo(db)
        private val sblaRepo = SblaRepo(db)
        private val assuranceRepo = DocumentaryAssuranceRepo(db)
        private val eventRepo = LifecycleEventRepo(db)
        val recallRepo = RecallRepo(db)
        val rateHistoryRepo = ContractRateHistoryRepo(db)
        val legRepo = SettlementLegRepo(db)
        val allocationRepo = ContractAllocationRepo(db)
        val allocationSvc = AllocationService(allocationRepo, positionRepo, contracts, counterpartyRepo,
            recallRepo, legRepo, market, audit)

        val inventory = InventoryService(positionRepo, locateRepo, contracts,
            LendablePoolSnapshotRepo(db), designatedRepo, audit, allocationSvc)
        val locate = LocateService(locateRepo, counterpartyRepo, sblaRepo, designatedRepo, rates, market,
            assuranceRepo, contracts, inventory, audit, locateLimits)
        val contractSvc = ContractService(contracts, eventRepo, recallRepo, rateHistoryRepo, counterpartyRepo,
            assuranceRepo, locate, locateRepo, inventory, legRepo, allocationSvc, audit)
            .also { svc -> inventory.positionListener = { svc.onSourcePositionChanged(it) } }
        val marginRepo = MarginCallRepo(db)
        val collateralSvc = CollateralService(collateralRepo, marginRepo, MtmHistoryRepo(db),
            ContractMtmHistoryRepo(db), contracts, CollateralTypeConfigRepo(db), market, audit, designatedRepo,
            positionRepo, counterpartyRepo)
        val settlementSvc = SettlementService(legRepo, contracts, contractSvc, collateralSvc, audit)
        val accrualRepo = FeeAccrualRepo(db)
        val feeSvc = FeeService(accrualRepo, splitRepo, FeeStatementRepo(db), contracts, market,
            rateHistoryRepo, audit, legRepo, allocationSvc, RebateAccrualRepo(db), collateralRepo,
            CollateralTypeConfigRepo(db), counterpartyRepo)
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

        fun seedInternalDesk(id: String) {
            counterpartyRepo.insert(Counterparty(id, "Internal desk $id", CounterpartyType.INTERNAL, createdAt = SblTime.now()))
        }

        /** A position owner / client / street lender, optionally with its own fee-share %. */
        fun seedOwner(id: String, type: CounterpartyType = CounterpartyType.LENDER, feeSharePct: String? = null) {
            counterpartyRepo.insert(Counterparty(id, "Owner $id", type,
                feeSharePct = feeSharePct?.let { BigDecimal(it) }, createdAt = SblTime.now()))
        }

        /** Parties, security, today's price and a default fixed rate for [stock]. */
        fun seedMarket(stock: String, px: String = "10.00", rate: String = "0.0365") {
            seedParties()
            seedSecurity(stock, 20_000_000_000, SecurityType.EQTY)
            price(stock, px)
            seedDefaultRate(rate)
        }

        /** Approve a locate for exactly [qty] and open a loan-out from [lender] settling today. */
        fun openLoan(borrower: String, stock: String, qty: Long, lender: String, borrowInId: String? = null,
                     model: TransactionModel = TransactionModel.PRINCIPAL): String {
            val d = locate.requestLocate(borrower, stock, qty)
            require(d.locate.approvedQty == qty) { "Locate for $qty $stock not fully approved (${d.outcome})" }
            return contractSvc.openFromLocate(d.locate.locateId, lender, transactionModel = model,
                settlementDate = SblTime.today(), borrowInContractId = borrowInId).contractId
        }

        fun allocQty(contractId: String, type: AllocationSource): Long =
            allocationRepo.currentForContract(contractId).filter { it.sourceType == type }.sumOf { it.quantity }

        fun allocOwner(contractId: String, ownerId: String): Long =
            allocationRepo.currentForContract(contractId).filter { it.sourceOwnerId == ownerId }.sumOf { it.quantity }

        fun price(code: String, p: String) = market.putPrice(code, SblTime.today(), Money.of(p))
        fun index(name: FloatingIndex, r: String) = market.putIndex(name, SblTime.today(), Money.of(r))

        fun seedDefaultRate(rate: String) {
            rates.insert(RateSchedule(Ids.newId(), null, null, FeeType.FIXED, Money.of(rate),
                null, null, null, D0.minusDays(10), null, 100, SblTime.now()))
        }

        /**
         * Approve a locate and open a contract settling today. When [settled], cash collateral
         * (110% of value) is posted and the OPEN leg settled so the contract is ACTIVE.
         */
        fun approveAndOpen(borrower: String, stock: String, qty: Long, issued: Long = 20_000_000_000,
                           settled: Boolean = true, shortSale: Boolean = true, ratio: BigDecimal? = null): String {
            seedParties()
            seedSecurity(stock, issued, SecurityType.EQTY)
            if (rates.all().isEmpty()) seedDefaultRate("0.005")
            if (market.latestPrice(stock, SblTime.today()) == null) price(stock, "10.00")
            inventory.onPositionSettled("L1", stock, AccountType.PROPRIETARY, qty * 4)
            val d = locate.requestLocate(borrower, stock, qty)
            val id = contractSvc.openFromLocate(d.locate.locateId, "L1", settlementDate = SblTime.today(),
                collateralRatio = ratio, shortSale = shortSale).contractId
            if (settled) settle(id)
            return id
        }

        /** Post 110% cash cover to the borrower's account and settle the contract's OPEN leg today. */
        fun settle(contractId: String) {
            val k = contracts.find(contractId)!!
            val value = market.latestPrice(k.stockCode, SblTime.today())!!.multiply(BigDecimal(k.quantityOutstanding))
            collateralSvc.postCollateral(k.borrowerId, "CASH_HKD", "HKD", value.multiply(BigDecimal("1.10")))
            settlementSvc.trySettleNow(contractId)
        }
    }
}
