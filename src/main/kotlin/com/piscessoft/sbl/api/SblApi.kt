package com.piscessoft.sbl.api

import com.piscessoft.sbl.app.SblServices
import com.piscessoft.sbl.model.*
import com.piscessoft.sbl.repo.*
import com.piscessoft.sbl.util.Ids
import com.piscessoft.sbl.util.SblTime
import java.math.BigDecimal

/**
 * REST resources for the eight SBL modules, mounted under `/api/v1`.
 *
 * Access model:
 *  - OPERATOR keys reach every endpoint (reference data, market data, batch jobs,
 *    corporate actions, regulatory reporting, audit).
 *  - USER keys are bound to one counterparty and only see / act on records where that
 *    counterparty is a party (borrower, lender or agent). Records belonging to other
 *    counterparties are reported as 404 so their existence is not disclosed.
 */
class SblApi(private val s: SblServices) {

    private val locates = LocateRepo(s.db)
    private val events = LifecycleEventRepo(s.db)
    private val recalls = RecallRepo(s.db)
    private val rateHistory = ContractRateHistoryRepo(s.db)
    private val collateral = CollateralPositionRepo(s.db)
    private val mtmHistory = MtmHistoryRepo(s.db)
    private val contractMtmHistory = ContractMtmHistoryRepo(s.db)
    private val collateralTypes = CollateralTypeConfigRepo(s.db)
    private val accruals = FeeAccrualRepo(s.db)
    private val statements = FeeStatementRepo(s.db)
    private val caEvents = CaEventRepo(s.db)
    private val caImpacts = CaImpactRepo(s.db)
    private val positions = PositionRepo(s.db)

    fun routes(): List<Route> = RouteBuilder("/api/v1").apply {
        system()
        referenceData()
        marketData()
        inventory()
        locates()
        contracts()
        collateral()
        settlement()
        fees()
        corporateActions()
        reporting()
        operations()
        audit()
    }.routes

    // ------------------------------------------------------------------ system

    private fun RouteBuilder.system() {
        get("/health", Access.PUBLIC) {
            mapOf("status" to "UP", "businessDate" to SblTime.today(), "time" to SblTime.now())
        }
        get("/me") { it.principal }
    }

    // ------------------------------------------------------------------ reference data

    private fun RouteBuilder.referenceData() {
        get("/counterparties", Access.OPERATOR) { s.counterparties.all() }

        post("/counterparties", Access.OPERATOR) { c ->
            val id = c.str("id").also { requireId("id", it) }
            if (s.counterparties.find(id) != null) throw ApiException(409, "Counterparty $id already exists")
            val cp = Counterparty(
                id = id,
                name = c.str("name"),
                type = c.enumOf("type"),
                tier = c.optEnum<BorrowerTier>("tier"),
                approvedBorrower = c.optBool("approvedBorrower") ?: false,
                creditLimitHkd = c.optDecimal("creditLimitHkd")?.nonNegative("creditLimitHkd"),
                feeSettlementBasis = c.optEnum<FeeSettlementBasis>("feeSettlementBasis") ?: FeeSettlementBasis.MONTHLY,
                regulatedPerson = c.optBool("regulatedPerson") ?: false,
                rebateSpreadBps = c.optInt("rebateSpreadBps")?.also {
                    if (it !in -10_000..10_000) badRequest("'rebateSpreadBps' must be between -10000 and 10000")
                },
                createdAt = SblTime.now()
            )
            s.counterparties.insert(cp)
            created(cp)
        }

        get("/counterparties/{id}") { c ->
            val id = c.path("id")
            if (!c.canSee(id)) notFound("Counterparty")
            s.counterparties.find(id) ?: notFound("Counterparty")
        }

        get("/counterparties/{id}/sblas") { c ->
            val id = c.path("id")
            if (!c.canSee(id)) notFound("Counterparty")
            s.sblas.forBorrower(id)
        }

        post("/counterparties/{id}/sblas", Access.OPERATOR) { c ->
            val borrower = s.counterparties.find(c.path("id")) ?: notFound("Counterparty")
            val lenderId = c.optStr("lenderId")?.also { requireCounterparty(it, "lenderId") }
            val sbla = Sbla(
                id = Ids.newId(),
                borrowerId = borrower.id,
                lenderId = lenderId,
                sbaNumber = c.str("sbaNumber"),
                registeredWithIrd = c.optBool("registeredWithIrd") ?: true,
                registeredAt = c.optDate("registeredOn")?.atStartOfDay(SblTime.HKT)?.toInstant() ?: SblTime.now(),
                validUntil = c.optDate("validUntil"),
                createdAt = SblTime.now()
            )
            s.sblas.insert(sbla)
            created(sbla)
        }

        get("/securities") { s.designatedSecurities.all() }

        get("/securities/{stockCode}") { c ->
            s.designatedSecurities.find(c.path("stockCode")) ?: notFound("Security")
        }

        put("/securities/{stockCode}", Access.OPERATOR) { c ->
            val code = c.path("stockCode").also { requireId("stockCode", it) }
            val currency = (c.optStr("currency") ?: "HKD").uppercase()
            if (!Regex("[A-Z]{3}").matches(currency)) badRequest("'currency' must be a 3-letter ISO code")
            s.inventory.upsertDesignatedSecurity(
                stockCode = code,
                name = c.str("stockName"),
                issuedShares = c.long("issuedShares").positive("issuedShares"),
                securityType = c.optEnum<SecurityType>("securityType") ?: SecurityType.EQTY,
                eligible = c.optBool("eligible") ?: true,
                issuerId = c.optStr("issuerId")?.also { requireId("issuerId", it) },
                currency = currency
            )
            s.designatedSecurities.find(code)
        }

        put("/securities/{stockCode}/ca-restriction", Access.OPERATOR) { c ->
            val code = c.path("stockCode")
            s.designatedSecurities.find(code) ?: notFound("Security")
            s.inventory.onCaRestriction(code, c.optBool("restricted") ?: badRequest("'restricted' is required"))
            s.designatedSecurities.find(code)
        }

        get("/collateral-types") { collateralTypes.all() }

        get("/rate-schedules", Access.OPERATOR) { s.rateSchedules.all() }

        post("/rate-schedules", Access.OPERATOR) { c ->
            val feeType = c.enumOf<FeeType>("feeType")
            val annualRate = c.optDecimal("annualRate")?.nonNegative("annualRate")
            val index = c.optEnum<FloatingIndex>("floatingIndex")
            when (feeType) {
                FeeType.FLOATING -> if (index == null) badRequest("'floatingIndex' is required for FLOATING")
                else -> if (annualRate == null) badRequest("'annualRate' is required for $feeType")
            }
            val from = c.optDate("effectiveFrom") ?: SblTime.today()
            val to = c.optDate("effectiveTo")
            if (to != null && to.isBefore(from)) badRequest("'effectiveTo' is before 'effectiveFrom'")
            val schedule = RateSchedule(
                scheduleId = Ids.newId(),
                stockCode = c.optStr("stockCode"),
                borrowerTier = c.optEnum<BorrowerTier>("borrowerTier"),
                feeType = feeType,
                annualRate = annualRate,
                floatingIndex = index,
                floatingSpreadBps = c.optInt("floatingSpreadBps"),
                minRate = c.optDecimal("minRate")?.nonNegative("minRate"),
                effectiveFrom = from,
                effectiveTo = to,
                priority = c.optInt("priority") ?: 100,
                createdAt = SblTime.now()
            )
            s.rateSchedules.insert(schedule)
            created(schedule)
        }

        // Indicative rate a borrower would get on a locate today.
        get("/rates/quote") { c ->
            val stock = c.query("stockCode") ?: badRequest("'stockCode' is required")
            val borrower = c.actingCounterparty(c.query("borrowerId"), "borrowerId")
            s.locateSvc.resolveRate(stock, borrower) ?: notFound("Applicable rate")
        }
    }

    // ------------------------------------------------------------------ market data

    private fun RouteBuilder.marketData() {
        get("/market/prices/{stockCode}") { c ->
            val code = c.path("stockCode")
            val date = c.queryDate("date") ?: SblTime.today()
            val px = s.pricingSvc.latestPrice(code, date) ?: notFound("Price")
            mapOf("stockCode" to code, "asOf" to date, "price" to px.closingPrice, "priceDate" to px.priceDate,
                "source" to px.source, "stale" to px.priceDate.isBefore(date))
        }

        get("/market/prices/{stockCode}/history") { c ->
            val to = c.queryDate("to") ?: SblTime.today()
            val from = c.queryDate("from") ?: to.minusDays(30)
            if (to.isBefore(from)) badRequest("'to' is before 'from'")
            if (from.plusYears(5).isBefore(to)) badRequest("Range must not exceed 5 years")
            s.pricingSvc.priceHistory(c.path("stockCode"), from, to)
        }

        // Price + FX used to value an instrument (stock, bond or other collateral asset).
        get("/market/quotes/{stockCode}") { c ->
            s.pricingSvc.quote(c.path("stockCode"), c.queryDate("date") ?: SblTime.today()) ?: notFound("Price/FX")
        }

        put("/market/prices/{stockCode}", Access.OPERATOR) { c ->
            val code = c.path("stockCode").also { requireId("stockCode", it) }
            val date = c.optDate("date") ?: SblTime.today()
            val price = c.decimal("price").positive("price")
            val source = c.optStr("source")?.also { requireId("source", it) } ?: "API"
            s.pricingSvc.recordPrice(code, date, price, source)
            val out = linkedMapOf<String, Any?>("stockCode" to code, "date" to date, "price" to price, "source" to source)
            // Optional intraday re-mark of everything holding this instrument.
            if (c.optBool("revalue") == true) out["revaluation"] = s.pricingSvc.revalue(date, code)
            out
        }

        // Bulk load (e.g. EOD closing prices): {"date","source","prices":[{"stockCode","price"}],"revalue"}.
        post("/market/prices", Access.OPERATOR) { c ->
            val date = c.optDate("date") ?: SblTime.today()
            val source = c.optStr("source")?.also { requireId("source", it) } ?: "API"
            val rows = c.body["prices"] as? List<*> ?: badRequest("'prices' must be an array")
            if (rows.isEmpty() || rows.size > MAX_PRICE_ROWS) badRequest("'prices' must hold 1-$MAX_PRICE_ROWS entries")
            val prices = linkedMapOf<String, BigDecimal>()
            rows.forEachIndexed { i, row ->
                val m = row as? Map<*, *> ?: badRequest("'prices[$i]' must be an object")
                val code = (m["stockCode"] as? String)?.trim()?.also { requireId("prices[$i].stockCode", it) }
                    ?: badRequest("'prices[$i].stockCode' is required")
                val price = when (val v = m["price"]) {
                    is BigDecimal -> v
                    is String -> v.trim().toBigDecimalOrNull()
                    else -> null
                } ?: badRequest("'prices[$i].price' must be a number")
                if (price.signum() <= 0) badRequest("'prices[$i].price' must be > 0")
                if (prices.put(code, price) != null) badRequest("Duplicate stockCode '$code' in 'prices'")
            }
            val stored = s.pricingSvc.recordPrices(date, prices, source)
            val revaluation = if (c.optBool("revalue") == true) s.pricingSvc.revalue(date) else null
            created(mapOf("date" to date, "source" to source, "stored" to stored.size, "revaluation" to revaluation))
        }

        put("/market/fx/{currency}", Access.OPERATOR) { c ->
            val ccy = c.path("currency").uppercase().also { requireId("currency", it) }
            val date = c.optDate("date") ?: SblTime.today()
            val rate = c.decimal("rate").positive("rate")
            s.market.putFx(ccy, date, rate)
            mapOf("currency" to ccy, "date" to date, "rateToHkd" to rate)
        }

        put("/market/indices/{index}", Access.OPERATOR) { c ->
            val index = parseEnum<FloatingIndex>("index", c.path("index"))
            val date = c.optDate("date") ?: SblTime.today()
            val rate = c.decimal("rate")
            s.market.putIndex(index, date, rate)
            mapOf("index" to index, "date" to date, "rate" to rate)
        }
    }

    // ------------------------------------------------------------------ module 2: inventory

    private fun RouteBuilder.inventory() {
        get("/inventory/pools/{stockCode}") { c -> s.inventory.lendablePool(c.path("stockCode")) }

        get("/inventory/hot-stocks") { c ->
            val threshold = c.queryDecimal("threshold")?.toDouble() ?: 0.20
            s.inventory.hotStocks(threshold).map { (code, pool) -> mapOf("stockCode" to code, "pool" to pool) }
        }

        post("/inventory/pools/{stockCode}/snapshots", Access.OPERATOR) { c ->
            created(s.inventory.snapshot(c.path("stockCode")))
        }

        get("/positions", Access.OPERATOR) { c ->
            c.query("stockCode")?.let { positions.forStock(it) } ?: positions.all()
        }

        // Inbound settled position (delta) from the settlement system / ops.
        post("/positions", Access.OPERATOR) { c ->
            val qty = c.long("quantity")
            if (qty == 0L) badRequest("'quantity' must be non-zero")
            created(s.inventory.onPositionSettled(
                ownerId = c.str("ownerId"),
                stockCode = c.str("stockCode"),
                accountType = c.enumOf("accountType"),
                qty = qty,
                settlement = c.optEnum<SettlementStatus>("settlementStatus") ?: SettlementStatus.SETTLED,
                source = c.optStr("sourceSystem") ?: "API",
                ref = c.optStr("sourceRef")
            ))
        }

        post("/positions/pledges", Access.OPERATOR) { c ->
            val stock = c.str("stockCode")
            s.inventory.onPositionPledged(c.str("ownerId"), stock, c.long("quantity").positive("quantity"))
            s.inventory.lendablePool(stock)
        }
    }

    // ------------------------------------------------------------------ module 1: locates

    private fun RouteBuilder.locates() {
        // Step 1 of booking a deal: request a locate.
        post("/locates") { c ->
            val borrower = c.actingCounterparty(c.optStr("borrowerId"), "borrowerId")
            val decision = s.locateSvc.requestLocate(
                borrowerId = borrower,
                stockCode = c.str("stockCode"),
                requestedQty = c.long("quantity").positive("quantity"),
                requestingSystem = "API",
                requestRef = c.optStr("requestRef"),
                isShortSale = c.optBool("shortSale") ?: true
            )
            created(decision)
        }

        get("/locates") { c ->
            val statuses = c.queryEnum<LocateStatus>("status")?.let { arrayOf(it) } ?: LocateStatus.entries.toTypedArray()
            val stock = c.query("stockCode")
            locates.byStatus(*statuses)
                .filter { c.canSee(it.borrowerId) }
                .filter { stock == null || it.stockCode == stock }
                .sortedByDescending { it.requestedAt }
        }

        get("/locates/{locateId}") { c -> c.loadLocate() }

        post("/locates/{locateId}/cancel") { c ->
            val l = c.loadLocate()
            check(l.status in setOf(LocateStatus.REQUESTED, LocateStatus.APPROVED, LocateStatus.PARTIAL)) {
                "Locate is ${l.status}, cannot cancel"
            }
            s.locateSvc.cancel(l.locateId)
            locates.find(l.locateId)
        }
    }

    // ------------------------------------------------------------------ module 3: contracts

    private fun RouteBuilder.contracts() {
        // Step 2 of booking a deal: convert an approved locate into a borrow contract.
        post("/contracts") { c ->
            val locate = locates.find(c.str("locateId"))?.takeIf { c.canSee(it.borrowerId) } ?: notFound("Locate")
            val expires = locate.expiresAt
            if (expires != null && !expires.isAfter(SblTime.now())) throw ApiException(409, "Locate has expired")
            val lenderId = c.str("lenderId").also { requireCounterparty(it, "lenderId") }
            val agentId = c.optStr("agentId")?.also { requireCounterparty(it, "agentId") }
            val split = c.optDecimal("agentSplitPct")?.also {
                if (it.signum() < 0 || it > BigDecimal.ONE) badRequest("'agentSplitPct' must be between 0 and 1")
            }
            if (split != null && agentId == null) badRequest("'agentSplitPct' requires 'agentId'")
            // Collateral terms are set by the firm, not the borrower; the service enforces the
            // regulatory floor (1.05 short-sale / 1.00 general) even for operators.
            val ratio = c.optDecimal("collateralRatio")?.positive("collateralRatio")
            val shortSale = c.optBool("shortSale") ?: true
            if ((ratio != null || !shortSale) && !c.principal.isOperator) {
                forbidden("Only operators may set 'collateralRatio' or 'shortSale'")
            }
            created(s.contractSvc.openFromLocate(
                locateId = locate.locateId,
                lenderId = lenderId,
                transactionModel = c.optEnum<TransactionModel>("transactionModel") ?: TransactionModel.PRINCIPAL,
                tradeType = c.optEnum<TradeType>("tradeType") ?: TradeType.FIXED,
                closeDate = c.optDate("closeDate"),
                collateralRatio = ratio,
                agentId = agentId,
                agentSplitPct = split,
                settlementDate = c.optDate("settlementDate"),
                shortSale = shortSale
            ))
        }

        get("/contracts") { c ->
            val status = c.queryEnum<ContractStatus>("status")
            val stock = c.query("stockCode")
            val borrower = c.query("borrowerId")
            val lender = c.query("lenderId")
            (if (status != null) s.contracts.byStatus(status) else s.contracts.all())
                .filter { c.canSee(it.borrowerId, it.lenderId, it.agentId) }
                .filter { stock == null || it.stockCode == stock }
                .filter { borrower == null || it.borrowerId == borrower }
                .filter { lender == null || it.lenderId == lender }
                .sortedByDescending { it.createdAt }
        }

        get("/contracts/{contractId}") { c -> c.loadContract() }
        get("/contracts/{contractId}/events") { c -> events.forContract(c.loadContract().contractId) }
        get("/contracts/{contractId}/recalls") { c -> recalls.forContract(c.loadContract().contractId) }
        get("/contracts/{contractId}/rate-history") { c -> rateHistory.forContract(c.loadContract().contractId) }
        get("/contracts/{contractId}/legs") { c -> s.settlementLegs.forContract(c.loadContract().contractId) }

        // Instructs a RETURN leg (T+2, or T+0 for internal desks); outstanding falls when it settles.
        post("/contracts/{contractId}/returns") { c ->
            val k = c.borrowerContract()
            val leg = s.contractSvc.instructReturn(k.contractId, c.long("quantity").positive("quantity"),
                c.optDate("settlementDate"))
            s.settlementSvc.trySettleNow(k.contractId)
            created(s.settlementLegs.find(leg.legId))
        }

        post("/contracts/{contractId}/close") { c ->
            val k = c.borrowerContract()
            val leg = s.contractSvc.close(k.contractId, c.optDate("settlementDate"))
            s.settlementSvc.trySettleNow(k.contractId)
            created(s.settlementLegs.find(leg.legId))
        }

        post("/contracts/{contractId}/recalls") { c ->
            val k = c.loadContract()
            if (!c.principal.isOperator && k.lenderId != c.principal.counterpartyId) {
                forbidden("Only the lender or an operator may recall")
            }
            // Lenders always recall at their own request; other reasons are operator-driven.
            val reason = if (c.principal.isOperator) c.optEnum<RecallReason>("reason") ?: RecallReason.LENDER_REQUEST
                         else RecallReason.LENDER_REQUEST
            created(s.contractSvc.recall(k.contractId, c.long("quantity").positive("quantity"), reason))
        }

        post("/contracts/{contractId}/deployment") { c ->
            s.contractSvc.markDeployed(c.borrowerContract().contractId, c.str("purpose"))
        }

        post("/contracts/{contractId}/rerate", Access.OPERATOR) { c ->
            val k = c.loadContract()
            s.contractSvc.rerate(k.contractId, c.decimal("rate").nonNegative("rate"),
                c.optDate("effectiveDate") ?: SblTime.today())
        }

        post("/contracts/{contractId}/rollover", Access.OPERATOR) { c ->
            s.contractSvc.rollover(c.loadContract().contractId, c.date("closeDate"))
        }

        post("/contracts/{contractId}/terminate", Access.OPERATOR) { c ->
            s.contractSvc.earlyTerminate(c.loadContract().contractId, c.str("reason"))
        }
    }

    // ------------------------------------------------------------------ module 4: collateral

    private fun RouteBuilder.collateral() {
        // Collateral is held per counterparty and covers its whole loan book. The system values
        // it (face value for cash/guarantees, otherwise the latest market price); no client price.
        post("/counterparties/{id}/collateral") { c ->
            val cp = c.accountOwner()
            val type = c.str("collateralType")
            collateralTypes.find(type) ?: throw ApiException(422, "Unknown collateralType '$type'")
            val position = s.collateralSvc.postCollateral(
                counterpartyId = cp,
                collateralType = type,
                assetId = c.str("assetId"),
                quantity = c.decimal("quantity").positive("quantity")
            )
            // Collateral-first: due OPEN legs settle as soon as the account covers them (AUTO mode).
            s.settlementSvc.trySettleForBorrower(cp)
            created(position)
        }

        get("/counterparties/{id}/collateral") { c -> collateral.allForCounterparty(c.visibleAccount()) }
        get("/counterparties/{id}/collateral-account") { c -> s.collateralSvc.valuateAccount(c.visibleAccount()) }
        get("/counterparties/{id}/mtm-history") { c -> mtmHistory.forCounterparty(c.visibleAccount()) }

        // The contract's own requirement (negative side of its borrower's account).
        get("/contracts/{contractId}/valuation") { c -> s.collateralSvc.requirementFor(c.loadContract()) }
        get("/contracts/{contractId}/mtm-history") { c -> contractMtmHistory.forContract(c.loadContract().contractId) }

        post("/collateral/{positionId}/substitute") { c ->
            val p = c.borrowerCollateral()
            val type = c.str("collateralType")
            collateralTypes.find(type) ?: throw ApiException(422, "Unknown collateralType '$type'")
            created(s.collateralSvc.substitute(
                oldPositionId = p.positionId,
                newType = type,
                newAssetId = c.str("assetId"),
                newQuantity = c.decimal("quantity").positive("quantity")
            ))
        }

        post("/collateral/{positionId}/release") { c ->
            val p = c.borrowerCollateral()
            val force = c.optBool("force") ?: false
            if (force && !c.principal.isOperator) forbidden("Only operators may force a release")
            s.collateralSvc.releaseCollateral(p.positionId, force)
        }

        get("/margin-calls") { c ->
            s.marginCalls.pending().filter { mc -> c.canSee(mc.counterpartyId) }
        }

        get("/margin-calls/{callId}") { c -> c.loadMarginCall() }

        post("/margin-calls/{callId}/resolve", Access.OPERATOR) { c ->
            val mc = c.loadMarginCall()
            check(mc.status == MarginCallStatus.PENDING || mc.status == MarginCallStatus.ESCALATED) {
                "Margin call is ${mc.status}"
            }
            s.collateralSvc.resolveMarginCall(mc.callId, c.enumOf("method"))
        }
    }

    // ------------------------------------------------------------------ module 5: fees & billing

    private fun RouteBuilder.fees() {
        get("/contracts/{contractId}/accruals") { c -> accruals.forContract(c.loadContract().contractId) }

        post("/statements", Access.OPERATOR) { c ->
            val borrower = c.str("borrowerId").also { requireCounterparty(it, "borrowerId") }
            val from = c.date("from")
            val to = c.date("to")
            if (to.isBefore(from)) badRequest("'to' is before 'from'")
            created(s.feeSvc.generateStatement(borrower, from, to))
        }

        get("/statements/{statementId}") { c -> c.loadStatement() }

        post("/statements/{statementId}/send", Access.OPERATOR) { c ->
            val st = c.loadStatement().requireStatus(StatementStatus.DRAFT, StatementStatus.DISPUTED)
            s.feeSvc.sendStatement(st.statementId)
        }

        post("/statements/{statementId}/agree") { c ->
            val st = c.loadStatement().requireStatus(StatementStatus.SENT)
            s.feeSvc.agreeStatement(st.statementId)
        }

        post("/statements/{statementId}/dispute") { c ->
            val st = c.loadStatement().requireStatus(StatementStatus.SENT)
            s.feeSvc.disputeStatement(st.statementId, c.str("reason"))
        }

        post("/statements/{statementId}/settle", Access.OPERATOR) { c ->
            val st = c.loadStatement().requireStatus(StatementStatus.AGREED)
            s.feeSvc.settleStatement(st.statementId)
        }
    }

    // ------------------------------------------------------------------ module 6: corporate actions

    private fun RouteBuilder.corporateActions() {
        post("/corporate-actions", Access.OPERATOR) { c ->
            val stock = c.str("stockCode")
            s.designatedSecurities.find(stock) ?: throw ApiException(422, "Unknown stockCode '$stock'")
            val type = c.enumOf<CaType>("caType")
            val cash = c.optDecimal("cashAmount")?.nonNegative("cashAmount")
            val ratio = c.optDecimal("adjustmentRatio")?.positive("adjustmentRatio")
            when (type) {
                CaType.CASH_DIVIDEND, CaType.SCRIP_DIVIDEND ->
                    if (cash == null) badRequest("'cashAmount' is required for $type")
                CaType.STOCK_SPLIT, CaType.CONSOLIDATION, CaType.STOCK_DIVIDEND ->
                    if (ratio == null) badRequest("'adjustmentRatio' is required for $type")
                else -> Unit
            }
            val exDate = c.optDate("exDate")
            created(s.caSvc.ingest(CaEvent(
                caEventId = Ids.newId(),
                stockCode = stock,
                caType = type,
                exDate = exDate,
                recordDate = c.optDate("recordDate") ?: exDate,
                paymentDate = c.optDate("paymentDate"),
                adjustmentRatio = ratio,
                cashAmount = cash,
                currency = c.optStr("currency")?.uppercase() ?: if (cash != null) "HKD" else null,
                description = c.optStr("description") ?: type.name,
                source = c.optStr("source") ?: "API",
                createdAt = SblTime.now()
            )))
        }

        get("/corporate-actions/pending", Access.OPERATOR) { caEvents.unprocessed() }

        post("/corporate-actions/process", Access.OPERATOR) { s.caSvc.processAll() }

        get("/corporate-actions/{caEventId}", Access.OPERATOR) { c ->
            caEvents.find(c.path("caEventId")) ?: notFound("Corporate action")
        }

        get("/corporate-actions/{caEventId}/impacts", Access.OPERATOR) { c ->
            val e = caEvents.find(c.path("caEventId")) ?: notFound("Corporate action")
            caImpacts.forEvent(e.caEventId)
        }

        post("/corporate-actions/{caEventId}/process", Access.OPERATOR) { c ->
            val e = caEvents.find(c.path("caEventId")) ?: notFound("Corporate action")
            if (e.processed) throw ApiException(409, "Corporate action already processed")
            s.caSvc.process(e)
        }
    }

    // ------------------------------------------------------------------ module 7: reporting

    private fun RouteBuilder.reporting() {
        post("/reports/short-positions", Access.OPERATOR) { c ->
            s.reportingSvc.generateShortPositionReports(c.optDate("date") ?: SblTime.today())
        }

        post("/reports/sprs", Access.OPERATOR) { c ->
            // sprid becomes part of the output filename and the legal name a CSV field:
            // both are strictly validated to block path traversal and CSV/formula injection.
            val sprid = c.str("sprid")
            if (!SPRID.matches(sprid)) badRequest("'sprid' must be 1-32 letters, digits, '_' or '-'")
            val name = c.str("legalEntityName")
            if (!LEGAL_NAME.matches(name)) badRequest("'legalEntityName' contains unsupported characters")
            val frequency = c.optStr("frequency")?.uppercase() ?: "WEEKLY"
            if (!FREQUENCY.matches(frequency)) badRequest("'frequency' is invalid")
            val date = c.optDate("date") ?: SblTime.today()
            val files = s.reportingSvc.generateSprsCsv(date, sprid, name, frequency)
            mapOf("reportDate" to date, "files" to files.map { it.name })
        }

        post("/reports/sbul1", Access.OPERATOR) { c ->
            val year = c.optInt("taxYear") ?: SblTime.today().year
            if (year !in 2000..2100) badRequest("'taxYear' is out of range")
            s.reportingSvc.generateSbul1(year)
        }

        post("/reports/cap571x", Access.OPERATOR) { c ->
            val window = c.optInt("windowBusinessDays") ?: 3
            if (window !in 1..30) badRequest("'windowBusinessDays' must be between 1 and 30")
            s.reportingSvc.generateCap571xDisclosures(window)
        }

        // Lenders see only their own row; operators see all lenders.
        get("/reports/lender-summary") { c ->
            s.reportingSvc.lenderDailySummary(c.queryDate("date") ?: SblTime.today())
                .filter { c.canSee(it.lenderId) }
        }
    }

    // ------------------------------------------------------------------ settlement legs

    private fun RouteBuilder.settlement() {
        get("/settlement/legs", Access.OPERATOR) { c ->
            s.settlementLegs.list(c.queryEnum<LegStatus>("status"), c.queryDate("date"))
        }

        // CCASS / custodian confirmation; idempotent when re-sent with the same externalRef.
        post("/settlement/legs/{legId}/confirm", Access.OPERATOR) { c ->
            val leg = s.settlementLegs.find(c.path("legId")) ?: notFound("Settlement leg")
            s.settlementSvc.confirm(leg.legId, c.optDate("settleDate") ?: SblTime.today(), c.optStr("externalRef"))
        }

        post("/settlement/legs/{legId}/fail", Access.OPERATOR) { c ->
            val leg = s.settlementLegs.find(c.path("legId")) ?: notFound("Settlement leg")
            s.settlementSvc.fail(leg.legId, c.str("reason"))
        }
    }

    // ------------------------------------------------------------------ batch / end-of-day

    private fun RouteBuilder.operations() {
        post("/operations/eod", Access.OPERATOR) { c ->
            val date = c.optDate("date") ?: SblTime.today()
            val expired = s.locateSvc.expireLocates()
            // Settlement runs first so accrual starts on the open-leg settle date and
            // stops on the return-leg settle date.
            val settlement = s.settlementSvc.settleDue(date)
            val accrued = s.feeSvc.accrueDaily(date)
            val rebates = s.feeSvc.accrueRebates(date)
            // Escalate calls missed before today's MTM raises any new ones.
            val escalated = s.collateralSvc.escalateOverdueMarginCalls()
            // Mark contracts, inventory and collateral to today's prices before the margin run.
            val revaluation = s.pricingSvc.revalue(date)
            val mtm = s.collateralSvc.runDailyMtm(date)
            val deemed = s.contractSvc.checkRegulatedPersonDeadlines()
            val overdue = s.contractSvc.checkOverdueRecalls()
            val buyIns = s.contractSvc.escalateBuyIns()
            mapOf(
                "businessDate" to date,
                "locatesExpired" to expired,
                "legsSettled" to settlement.settled.map { it.legId },
                "legsFailed" to settlement.failed.map { it.legId },
                "feeAccruals" to accrued.size,
                "rebateAccruals" to rebates.size,
                "revaluation" to revaluation,
                "accountsValued" to mtm.size,
                "contractsValued" to mtm.sumOf { a -> a.contracts.count { !it.priceMissing } },
                "mtmUnpriced" to mtm.flatMap { it.unpricedContracts },
                "marginCalls" to mtm.mapNotNull { it.marginCall },
                "marginCallsEscalated" to escalated.map { it.callId },
                "deemedAcquisitions" to deemed.map { it.contractId },
                "overdueRecalls" to overdue.map { it.recallId },
                "buyIns" to buyIns.map { it.recallId }
            )
        }

        post("/operations/locates/expire", Access.OPERATOR) { mapOf("expired" to s.locateSvc.expireLocates()) }
        post("/operations/settlement", Access.OPERATOR) { c -> s.settlementSvc.settleDue(c.optDate("date") ?: SblTime.today()) }
        post("/operations/fees/accrue", Access.OPERATOR) { c -> s.feeSvc.accrueDaily(c.optDate("date") ?: SblTime.today()) }
        post("/operations/rebates/accrue", Access.OPERATOR) { c -> s.feeSvc.accrueRebates(c.optDate("date") ?: SblTime.today()) }
        post("/operations/collateral/mtm", Access.OPERATOR) { c -> s.collateralSvc.runDailyMtm(c.optDate("date") ?: SblTime.today()) }
        post("/operations/revalue", Access.OPERATOR) { c ->
            s.pricingSvc.revalue(c.optDate("date") ?: SblTime.today(), c.optStr("stockCode"))
        }
        post("/operations/margin-calls/escalate", Access.OPERATOR) { s.collateralSvc.escalateOverdueMarginCalls() }
        post("/operations/contracts/regulated-deadlines", Access.OPERATOR) { s.contractSvc.checkRegulatedPersonDeadlines() }
        post("/operations/recalls/overdue", Access.OPERATOR) { s.contractSvc.checkOverdueRecalls() }
        post("/operations/recalls/buy-ins", Access.OPERATOR) { s.contractSvc.escalateBuyIns() }
    }

    // ------------------------------------------------------------------ module 8: audit

    private fun RouteBuilder.audit() {
        get("/audit/{entityType}/{entityId}", Access.OPERATOR) { c ->
            s.audit.history(c.path("entityType").uppercase(), c.path("entityId"))
        }

        // SFC on-demand export; the export itself is audited against the calling operator.
        post("/audit/{entityType}/{entityId}/export", Access.OPERATOR) { c ->
            s.audit.exportForEntity(c.path("entityType").uppercase(), c.path("entityId"), c.principal.name)
        }
    }

    // ------------------------------------------------------------------ access helpers

    /** True for operators, or when the caller's counterparty is one of [parties]. */
    private fun Call.canSee(vararg parties: String?): Boolean =
        principal.isOperator || (principal.counterpartyId != null && principal.counterpartyId in parties)

    /**
     * The counterparty an action is performed for. Operators must name it explicitly; a USER
     * always acts as itself and may not name another counterparty.
     */
    private fun Call.actingCounterparty(requested: String?, field: String): String {
        if (principal.isOperator) return requested ?: badRequest("'$field' is required")
        val self = principal.counterpartyId ?: forbidden("Key is not bound to a counterparty")
        if (requested != null && requested != self) forbidden("Cannot act on behalf of another counterparty")
        return self
    }

    private fun Call.loadLocate(): LocateRequest =
        locates.find(path("locateId"))?.takeIf { canSee(it.borrowerId) } ?: notFound("Locate")

    private fun Call.loadContract(): SblContract =
        s.contracts.find(path("contractId"))?.takeIf { canSee(it.borrowerId, it.lenderId, it.agentId) }
            ?: notFound("Contract")

    private fun Call.borrowerContract(): SblContract {
        val k = loadContract()
        if (!principal.isOperator && k.borrowerId != principal.counterpartyId) {
            forbidden("Only the borrower or an operator may do this")
        }
        return k
    }

    /** The counterparty in the path, if the caller may see it (operators, or the counterparty itself). */
    private fun Call.visibleAccount(): String {
        val id = path("id")
        if (!canSee(id)) notFound("Counterparty")
        s.counterparties.find(id) ?: notFound("Counterparty")
        return id
    }

    /** The counterparty in the path, for acting on its collateral account. */
    private fun Call.accountOwner(): String = actingCounterparty(visibleAccount(), "id")

    /** A collateral position in an account the caller owns (or any, for operators). */
    private fun Call.borrowerCollateral(): CollateralPosition =
        collateral.find(path("positionId"))?.takeIf { canSee(it.counterpartyId) }
            ?: notFound("Collateral position")

    private fun Call.loadMarginCall(): MarginCall =
        s.marginCalls.find(path("callId"))?.takeIf { canSee(it.counterpartyId) } ?: notFound("Margin call")

    private fun Call.loadStatement(): FeeStatement =
        statements.find(path("statementId"))?.takeIf { canSee(it.counterpartyId) } ?: notFound("Statement")

    private fun FeeStatement.requireStatus(vararg allowed: StatementStatus): FeeStatement {
        if (status !in allowed) throw ApiException(409, "Statement is $status")
        return this
    }

    private fun requireCounterparty(id: String, field: String) {
        s.counterparties.find(id) ?: throw ApiException(422, "Unknown $field '$id'")
    }

    private fun requireId(field: String, value: String) {
        if (!ID.matches(value)) badRequest("'$field' must be 1-64 letters, digits, '.', '_' or '-'")
    }

    private fun BigDecimal.positive(name: String) = also { if (signum() <= 0) badRequest("'$name' must be > 0") }
    private fun BigDecimal.nonNegative(name: String) = also { if (signum() < 0) badRequest("'$name' must be >= 0") }
    private fun Long.positive(name: String) = also { if (this <= 0) badRequest("'$name' must be > 0") }

    private companion object {
        val ID = Regex("^[A-Za-z0-9._-]{1,64}$")
        val SPRID = Regex("^[A-Za-z0-9_-]{1,32}$")
        val LEGAL_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9 .&()'/-]{0,127}$")
        val FREQUENCY = Regex("^[A-Z_]{1,16}$")
        const val MAX_PRICE_ROWS = 10_000
    }
}
