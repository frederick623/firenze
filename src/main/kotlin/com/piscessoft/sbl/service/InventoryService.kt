package com.piscessoft.sbl.service

import com.piscessoft.sbl.model.*
import com.piscessoft.sbl.repo.*
import com.piscessoft.sbl.util.Ids
import com.piscessoft.sbl.util.SblTime

/**
 * Module 2 - Position & Inventory Management.
 *
 * Maintains positions and computes the real-time lendable pool that feeds Locate
 * (Module 1) and Contract Lifecycle (Module 3). Only SETTLED positions in lendable
 * account types count toward gross lendable; locate commitments and on-loan quantity
 * are then deducted (plan section 3.2).
 */
class InventoryService(
    private val positions: PositionRepo,
    private val locates: LocateRepo,
    private val contracts: ContractRepo,
    private val snapshots: LendablePoolSnapshotRepo,
    private val designated: DesignatedSecurityRepo,
    private val audit: AuditService,
    private val allocations: AllocationService,
    /** Optional Schedule-3.2 inclusion of recalled (expected-back) qty in net available. */
    private val includeRecalledInPool: Boolean = false
) {

    /**
     * Called after a position changes so loans sourced from it can be re-sourced or recalled.
     * Wired to [ContractService.onSourcePositionChanged] at bootstrap (avoids a constructor cycle).
     */
    var positionListener: ((Position) -> Unit)? = null

    private val lendableAccounts = setOf(
        AccountType.PROPRIETARY, AccountType.CLIENT_DISCRETIONARY, AccountType.LENDING_POOL
    )

    // ---- Position event handlers (inbound from Settlement System, plan section 3.3) ----

    fun onPositionSettled(
        ownerId: String, stockCode: String, accountType: AccountType, qty: Long,
        settlement: SettlementStatus = SettlementStatus.SETTLED,
        source: String = "SETTLEMENT_SYSTEM", ref: String? = null
    ): Position {
        val p = upsert(ownerId, stockCode, accountType, qty, settlement, source, ref)
        audit.log("POSITION", p.positionId, "POSITION_SETTLED", source, ActorType.EXTERNAL,
            newValue = "$stockCode $accountType $qty $settlement")
        positionListener?.invoke(p)
        return p
    }

    /** Move quantity from a lendable account to PLEDGED (posted as collateral elsewhere). */
    fun onPositionPledged(ownerId: String, stockCode: String, qty: Long) {
        val src = moveBetweenAccounts(ownerId, stockCode, AccountType.PROPRIETARY, AccountType.PLEDGED, qty)
        audit.log("POSITION", "$ownerId:$stockCode", "POSITION_PLEDGED", "SETTLEMENT_SYSTEM",
            ActorType.EXTERNAL, newValue = "pledged $qty")
        positionListener?.invoke(src)
    }

    /** Apply / lift a corporate-action restriction on a security (plan section 3.3). */
    fun onCaRestriction(stockCode: String, restricted: Boolean) {
        designated.setCaRestricted(stockCode, restricted)
        audit.log("POSITION", stockCode, if (restricted) "CA_RESTRICTED_ON" else "CA_RESTRICTED_OFF",
            "SYSTEM", regulatoryRef = "CA restriction")
    }

    fun onCollateralReceived(ownerId: String, assetCode: String, qty: Long) {
        upsert(ownerId, assetCode, AccountType.COLLATERAL_HELD, qty, SettlementStatus.SETTLED,
            "SETTLEMENT_SYSTEM", null)
    }

    // ---- Lendable pool computation ----

    /** Settled positions in lendable accounts plus usable street borrow-ins. */
    fun grossLendable(stockCode: String): Long =
        positions.forStock(stockCode)
            .filter { it.settlementStatus == SettlementStatus.SETTLED && it.accountType in lendableAccounts }
            .sumOf { it.quantity } + allocations.borrowedInSupply(stockCode)

    fun recalledQty(stockCode: String): Long =
        positions.forStock(stockCode)
            .filter { it.accountType == AccountType.RECALLED }
            .sumOf { it.quantity }

    fun lendablePool(stockCode: String): LendablePool {
        val stockPositions = positions.forStock(stockCode)
        fun sumOf(t: AccountType) = stockPositions
            .filter { it.settlementStatus == SettlementStatus.SETTLED && it.accountType == t }
            .sumOf { it.quantity }

        val gross = grossLendable(stockCode)
        val committed = locates.committedQty(stockCode)
        val onLoan = contracts.onLoanQty(stockCode)
        val recalled = recalledQty(stockCode)
        val net = (gross - committed - onLoan + if (includeRecalledInPool) recalled else 0)
            .coerceAtLeast(0)
        return LendablePool(
            stockCode = stockCode,
            grossLendable = gross,
            locateCommitted = committed,
            onLoan = onLoan,
            netAvailable = net,
            qtyProprietary = sumOf(AccountType.PROPRIETARY),
            qtyClientDisc = sumOf(AccountType.CLIENT_DISCRETIONARY),
            qtyLendingPool = sumOf(AccountType.LENDING_POOL),
            qtyRecalled = recalled,
            qtyBorrowedIn = allocations.borrowedInSupply(stockCode)
        )
    }

    fun netAvailableToLend(stockCode: String): Long = lendablePool(stockCode).netAvailable

    /** Persist a point-in-time lendable pool snapshot (plan section 3.6). */
    fun snapshot(stockCode: String): LendablePoolSnapshot {
        val pool = lendablePool(stockCode)
        val snap = LendablePoolSnapshot(
            snapshotId = Ids.newId(),
            stockCode = stockCode,
            asOfDatetime = SblTime.now(),
            grossLendable = pool.grossLendable,
            locateCommitted = pool.locateCommitted,
            onLoan = pool.onLoan,
            netAvailable = pool.netAvailable,
            qtyProprietary = pool.qtyProprietary,
            qtyClientDisc = pool.qtyClientDisc,
            qtyLendingPool = pool.qtyLendingPool,
            qtyRecalled = pool.qtyRecalled,
            createdAt = SblTime.now()
        )
        snapshots.insert(snap)
        return snap
    }

    // ---- Designated Securities (plan section 3.5) ----

    fun upsertDesignatedSecurity(
        stockCode: String, name: String, issuedShares: Long,
        securityType: SecurityType = SecurityType.EQTY, eligible: Boolean = true,
        issuerId: String? = null, currency: String = "HKD"
    ) = designated.upsert(
        DesignatedSecurity(stockCode, name, securityType, issuedShares, eligible, false, SblTime.now(),
            issuerId, currency)
    )

    /** Daily sync: securities not present in the new HKEX list become ineligible. */
    fun syncDesignatedSecurities(currentList: Set<String>) {
        for (sec in designated.all()) {
            val stillListed = sec.stockCode in currentList
            if (sec.eligible != stillListed) {
                designated.setEligible(sec.stockCode, stillListed)
                audit.log("REPORT", sec.stockCode,
                    if (stillListed) "DESIGNATED_ADDED" else "DESIGNATED_REMOVED",
                    "SYSTEM", regulatoryRef = "HKEX Designated Securities")
            }
        }
    }

    // ---- Dashboards (plan section 3.4) ----

    fun hotStocks(thresholdPct: Double = 0.20): List<Pair<String, LendablePool>> =
        positions.distinctStocks().map { it to lendablePool(it) }
            .filter { (_, pool) ->
                pool.grossLendable > 0 &&
                    pool.netAvailable.toDouble() / pool.grossLendable.toDouble() < thresholdPct
            }

    // ---- internals ----

    private fun upsert(
        ownerId: String, stockCode: String, accountType: AccountType, deltaQty: Long,
        settlement: SettlementStatus, source: String, ref: String?
    ): Position {
        val existing = positions.findKey(ownerId, stockCode, accountType)
        return if (existing == null) {
            val p = Position(
                positionId = Ids.newId(), ownerId = ownerId, stockCode = stockCode,
                accountType = accountType, quantity = deltaQty, settlementStatus = settlement,
                lendable = accountType.lendable && settlement == SettlementStatus.SETTLED,
                asOfDate = SblTime.today(), updatedAt = SblTime.now(),
                sourceSystem = source, sourceRef = ref
            )
            positions.insert(p); p
        } else {
            existing.quantity += deltaQty
            existing.settlementStatus = settlement
            existing.lendable = accountType.lendable && settlement == SettlementStatus.SETTLED
            existing.asOfDate = SblTime.today()
            existing.updatedAt = SblTime.now()
            positions.update(existing); existing
        }
    }

    private fun moveBetweenAccounts(
        ownerId: String, stockCode: String, from: AccountType, to: AccountType, qty: Long
    ): Position {
        val src = positions.findKey(ownerId, stockCode, from)
            ?: error("No $from position for $ownerId/$stockCode to move")
        require(src.quantity >= qty) { "Insufficient $from qty to move ($qty > ${src.quantity})" }
        src.quantity -= qty
        src.updatedAt = SblTime.now()
        positions.update(src)
        upsert(ownerId, stockCode, to, qty, SettlementStatus.SETTLED, src.sourceSystem, src.sourceRef)
        return src
    }
}
