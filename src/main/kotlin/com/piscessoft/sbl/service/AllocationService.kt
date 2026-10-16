package com.piscessoft.sbl.service

import com.piscessoft.sbl.model.*
import com.piscessoft.sbl.repo.*
import com.piscessoft.sbl.util.Ids
import com.piscessoft.sbl.util.Money
import com.piscessoft.sbl.util.SblTime
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * Inventory sourcing for loan-outs.
 *
 * Every loan-out is supplied by one or more [ContractAllocation]s: firm inventory, client
 * margin stock, third-party (agency pool) stock, and/or a street borrow-in. This service
 * picks the sources when a loan opens, releases them on return, substitutes them when a
 * source shrinks (client sale, borrow-in recall) and works out client fee pass-through.
 *
 * Source capacity:
 *  - position: settled quantity in a lendable account (PROPRIETARY / CLIENT_DISCRETIONARY /
 *    LENDING_POOL). CLIENT_DISCRETIONARY is treated as client consent to lend.
 *  - borrow-in: outstanding quantity less anything being returned to, or recalled by, the street.
 *
 * Contracts whose lender is an INTERNAL desk lend as principal from the whole pool;
 * any other lender lends only from its own positions.
 */
class AllocationService(
    private val allocations: ContractAllocationRepo,
    private val positions: PositionRepo,
    private val contracts: ContractRepo,
    private val counterparties: CounterpartyRepo,
    private val recalls: RecallRepo,
    private val legs: SettlementLegRepo,
    private val market: MarketDataRepo,
    private val audit: AuditService,
    /** Fee share passed to a source owner that has no [Counterparty.feeSharePct] configured. */
    val defaultFeeSharePct: BigDecimal = BigDecimal("0.50"),
    private val dayCount: Int = 365
) {

    /** A proposed slice of supply for a contract. */
    data class Slice(
        val sourceType: AllocationSource,
        val ownerId: String,
        val positionId: String?,
        val sourceContractId: String?,
        val quantity: Long
    )

    /** Quantity on [contractId] that could not be re-sourced and must be recalled from the borrower. */
    data class Shortfall(val contractId: String, val allocationId: String, val quantity: Long)

    /** Fraction of a loan-out's daily fee passed through to a source owner. */
    data class FeeShare(val ownerId: String, val sourceType: AllocationSource, val fractionOfFee: BigDecimal)

    data class ClientLoan(val contractId: String, val borrowerId: String, val quantity: Long, val underRecallQty: Long)

    data class ClientAssetLine(
        val ownerId: String,
        val stockCode: String,
        val sourceType: AllocationSource,
        val positionId: String,
        val positionQty: Long,
        val onLoanQty: Long,
        val underRecallQty: Long,
        /** On-loan quantity above what the owner currently holds, not yet covered by a recall. */
        val uncoveredQty: Long,
        val loans: List<ClientLoan>
    )

    data class MatchedPair(
        val loanOutId: String,
        val borrowerId: String,
        val quantity: Long,
        val loanRate: BigDecimal,
        val spreadBps: BigDecimal,
        val dailySpreadHkd: BigDecimal?
    )

    data class MatchedBookLine(
        val borrowInId: String,
        val streetLenderId: String,
        val stockCode: String,
        val status: ContractStatus,
        val quantityOutstanding: Long,
        val borrowRate: BigDecimal,
        val allocatedQty: Long,
        val unpairedQty: Long,
        val pairs: List<MatchedPair>,
        /** Spread earned on paired quantity less the borrow cost of unpaired quantity (null without a price). */
        val netDailyHkd: BigDecimal?
    )

    private val allocationPriority = listOf(
        AllocationSource.FIRM, AllocationSource.CLIENT_MARGIN, AllocationSource.THIRD_PARTY, AllocationSource.STREET_BORROW
    )
    private val releasePriority = listOf(
        AllocationSource.STREET_BORROW, AllocationSource.CLIENT_MARGIN, AllocationSource.THIRD_PARTY, AllocationSource.FIRM
    )
    private val bookedStatuses = setOf(
        ContractStatus.CONFIRMED, ContractStatus.ACTIVE, ContractStatus.RECALLED, ContractStatus.RETURN_PENDING,
        ContractStatus.PARTIALLY_RETURNED, ContractStatus.RERATED, ContractStatus.ROLLED_OVER, ContractStatus.CA_ADJUSTED
    )
    private val openRecallStatuses = setOf(RecallStatus.PENDING, RecallStatus.PARTIALLY_RETURNED, RecallStatus.OVERDUE)

    fun sourceTypeOf(account: AccountType): AllocationSource? = when (account) {
        AccountType.PROPRIETARY -> AllocationSource.FIRM
        AccountType.CLIENT_DISCRETIONARY -> AllocationSource.CLIENT_MARGIN
        AccountType.LENDING_POOL -> AllocationSource.THIRD_PARTY
        else -> null
    }

    // ---- capacity ----

    fun positionCapacity(p: Position): Long =
        if (p.settlementStatus == SettlementStatus.SETTLED && sourceTypeOf(p.accountType) != null)
            p.quantity.coerceAtLeast(0) else 0

    fun borrowInCapacity(k: SblContract): Long {
        if (k.bookSide != BookSide.BORROW_IN || k.status !in bookedStatuses) return 0
        val pendingReturn = legs.pendingReturnQty(k.contractId)
        val recallRemaining = recalls.forContract(k.contractId)
            .filter { it.status in openRecallStatuses }
            .sumOf { (it.recallQty - it.returnedQty).coerceAtLeast(0) }
        return (k.quantityOutstanding - maxOf(pendingReturn, recallRemaining)).coerceAtLeast(0)
    }

    /** Street-borrowed quantity currently usable as lendable supply for [stockCode]. */
    fun borrowedInSupply(stockCode: String): Long =
        contracts.bookedBorrowIns(stockCode).sumOf { borrowInCapacity(it) }

    fun allocatedFromBorrowIn(borrowInId: String): Long =
        allocations.currentForSourceContract(borrowInId).sumOf { it.quantity }

    private fun freeOnPosition(p: Position): Long =
        positionCapacity(p) - allocations.currentForPosition(p.positionId).sumOf { it.quantity }

    private fun freeOnBorrowIn(k: SblContract): Long = borrowInCapacity(k) - allocatedFromBorrowIn(k.contractId)

    // ---- planning ----

    /**
     * Pick sources for [qty] shares of [stockCode] lent by [lenderId], by priority
     * FIRM -> CLIENT_MARGIN -> THIRD_PARTY -> STREET_BORROW (largest free first within a tier).
     * [preferredBorrowInId] is used first when given (explicit matched-book pairing).
     * Returns fewer than [qty] shares when supply runs out; nothing is written.
     */
    fun plan(
        stockCode: String, lenderId: String, qty: Long,
        preferredBorrowInId: String? = null,
        excludePositionId: String? = null,
        excludeBorrowInId: String? = null
    ): List<Slice> {
        val principal = counterparties.find(lenderId)?.type == CounterpartyType.INTERNAL
        val out = mutableListOf<Slice>()
        var remaining = qty

        if (preferredBorrowInId != null) {
            val k = contracts.find(preferredBorrowInId) ?: throw IllegalArgumentException("Borrow-in $preferredBorrowInId not found")
            require(k.bookSide == BookSide.BORROW_IN) { "Contract $preferredBorrowInId is not a borrow-in" }
            require(k.stockCode == stockCode) { "Borrow-in $preferredBorrowInId is for ${k.stockCode}, not $stockCode" }
            require(principal) { "Only an INTERNAL (principal) lender can on-lend a street borrow-in" }
            val take = minOf(remaining, freeOnBorrowIn(k).coerceAtLeast(0))
            if (take > 0) {
                out += Slice(AllocationSource.STREET_BORROW, k.lenderId, null, k.contractId, take)
                remaining -= take
            }
        }
        if (remaining <= 0) return out

        val fromPositions = positions.forStock(stockCode)
            .filter { principal || it.ownerId == lenderId }
            .filter { it.positionId != excludePositionId }
            .mapNotNull { p ->
                val type = sourceTypeOf(p.accountType) ?: return@mapNotNull null
                val free = freeOnPosition(p)
                if (free > 0) Slice(type, p.ownerId, p.positionId, null, free) else null
            }
        val fromStreet = if (!principal) emptyList() else contracts.bookedBorrowIns(stockCode)
            .filter { it.contractId != excludeBorrowInId && it.contractId != preferredBorrowInId }
            .mapNotNull { k ->
                val free = freeOnBorrowIn(k)
                if (free > 0) Slice(AllocationSource.STREET_BORROW, k.lenderId, null, k.contractId, free) else null
            }
        val ordered = (fromPositions + fromStreet).sortedWith(
            compareBy<Slice> { allocationPriority.indexOf(it.sourceType) }.thenByDescending { it.quantity }
        )
        for (s in ordered) {
            if (remaining <= 0) break
            val take = minOf(remaining, s.quantity)
            out += s.copy(quantity = take)
            remaining -= take
        }
        return out
    }

    /** Write [slices] against [c], merging with any current row for the same source. */
    fun apply(c: SblContract, slices: List<Slice>, date: LocalDate = SblTime.today()) {
        if (slices.isEmpty()) return
        val current = allocations.currentForContract(c.contractId)
        for (s in slices) {
            val existing = current.firstOrNull {
                (s.positionId != null && it.positionId == s.positionId) ||
                    (s.sourceContractId != null && it.sourceContractId == s.sourceContractId)
            }
            if (existing != null) {
                resize(existing, existing.quantity + s.quantity, date)
            } else {
                val now = SblTime.now()
                allocations.insert(
                    ContractAllocation(
                        allocationId = Ids.newId(),
                        contractId = c.contractId,
                        stockCode = c.stockCode,
                        sourceType = s.sourceType,
                        sourceOwnerId = s.ownerId,
                        positionId = s.positionId,
                        sourceContractId = s.sourceContractId,
                        quantity = s.quantity,
                        effectiveFrom = date,
                        createdAt = now,
                        updatedAt = now
                    )
                )
            }
        }
        audit.log("CONTRACT", c.contractId, "ALLOCATED", "SYSTEM",
            newValue = slices.joinToString("; ") { "${it.sourceType}:${it.ownerId}:${it.quantity}" })
    }

    // ---- lifecycle hooks ----

    /**
     * Release [qty] returned shares. Sources under a shortfall recall go first, then
     * STREET_BORROW -> CLIENT_MARGIN -> THIRD_PARTY -> FIRM, so client and street stock
     * comes off loan before the firm's own.
     */
    fun release(c: SblContract, qty: Long, date: LocalDate) {
        var remaining = qty
        val rows = allocations.currentForContract(c.contractId).sortedWith(
            compareByDescending<ContractAllocation> { it.recalledQty > 0 }
                .thenBy { releasePriority.indexOf(it.sourceType) }
        )
        val released = mutableListOf<String>()
        for (row in rows) {
            if (remaining <= 0) break
            val take = minOf(remaining, row.quantity)
            resize(row, row.quantity - take, date, (row.recalledQty - take).coerceAtLeast(0))
            released += "${row.sourceType}:${row.sourceOwnerId}:$take"
            remaining -= take
        }
        if (released.isNotEmpty()) {
            audit.log("CONTRACT", c.contractId, "ALLOCATION_RELEASED", "SYSTEM", newValue = released.joinToString("; "))
        }
    }

    fun releaseAll(c: SblContract, date: LocalDate) {
        allocations.currentForContract(c.contractId).forEach { resize(it, 0, date) }
    }

    /** Rescale current rows to the contract's new outstanding quantity (corporate-action adjustment). */
    fun rescale(c: SblContract, oldOutstanding: Long, date: LocalDate) {
        val rows = allocations.currentForContract(c.contractId)
        if (rows.isEmpty() || oldOutstanding <= 0) return
        val ratio = BigDecimal(c.quantityOutstanding).divide(BigDecimal(oldOutstanding), 10, RoundingMode.HALF_UP)
        fun scale(v: Long) = BigDecimal(v).multiply(ratio).setScale(0, RoundingMode.HALF_UP).toLong()
        val newQty = rows.map { scale(it.quantity) }.toMutableList()
        val diff = c.quantityOutstanding - newQty.sum()
        if (diff != 0L) {
            val i = newQty.indices.maxByOrNull { newQty[it] }!!
            newQty[i] += diff
        }
        rows.forEachIndexed { i, row ->
            resize(row, newQty[i], date, scale(row.recalledQty).coerceAtMost(newQty[i]))
        }
    }

    /** Re-source loans after a position shrank (sale, pledge, unsettled). */
    fun rebalancePosition(positionId: String, date: LocalDate): List<Shortfall> {
        val p = positions.find(positionId) ?: return emptyList()
        return rebalance(allocations.currentForPosition(positionId), positionCapacity(p), date,
            excludePositionId = positionId, excludeBorrowInId = null)
    }

    /** Re-source loans after a borrow-in was recalled, returned or terminated. */
    fun rebalanceBorrowIn(k: SblContract, date: LocalDate): List<Shortfall> =
        rebalance(allocations.currentForSourceContract(k.contractId), borrowInCapacity(k), date,
            excludePositionId = null, excludeBorrowInId = k.contractId)

    /** Record that [qty] of an allocation is now covered by a recall issued to the borrower. */
    fun markRecalled(allocationId: String, qty: Long) {
        val row = allocations.find(allocationId) ?: return
        row.recalledQty = (row.recalledQty + qty).coerceAtMost(row.quantity)
        allocations.update(row)
    }

    /**
     * Move a loan-out onto a borrow-in after the fact (e.g. to free firm or client stock).
     * Takes quantity off the loan's existing sources in release order.
     */
    fun pair(loanOut: SblContract, borrowIn: SblContract, qty: Long, date: LocalDate = SblTime.today()): Long {
        require(loanOut.bookSide == BookSide.LOAN_OUT) { "${loanOut.contractId} is not a loan-out" }
        val slices = plan(loanOut.stockCode, loanOut.lenderId, qty, preferredBorrowInId = borrowIn.contractId)
            .filter { it.sourceContractId == borrowIn.contractId }
        val movable = slices.sumOf { it.quantity }
        var remaining = movable
        val rows = allocations.currentForContract(loanOut.contractId)
            .filter { it.sourceContractId != borrowIn.contractId }
            .sortedWith(compareByDescending<ContractAllocation> { it.recalledQty > 0 }
                .thenBy { releasePriority.indexOf(it.sourceType) })
        for (row in rows) {
            if (remaining <= 0) break
            val take = minOf(remaining, row.quantity)
            resize(row, row.quantity - take, date, (row.recalledQty - take).coerceAtLeast(0))
            remaining -= take
        }
        val moved = movable - remaining
        if (moved > 0) apply(loanOut, listOf(slices.first().copy(quantity = moved)), date)
        return moved
    }

    // ---- fees ----

    /** Pass-through shares of a loan-out's fee on [date] for client/third-party stock lent by someone else. */
    fun feeShares(c: SblContract, date: LocalDate): List<FeeShare> {
        if (c.bookSide != BookSide.LOAN_OUT || c.quantityOutstanding <= 0) return emptyList()
        val active = allocations.activeOn(c.contractId, date)
        // Pro rata to the allocation in force on [date], not today's outstanding (back-dated accruals).
        val base = active.sumOf { it.quantity }.takeIf { it > 0 } ?: c.quantityOutstanding
        val outstanding = BigDecimal(base)
        return active
            .filter { it.sourceType == AllocationSource.CLIENT_MARGIN || it.sourceType == AllocationSource.THIRD_PARTY }
            .filter { it.sourceOwnerId != c.lenderId }
            .groupBy { it.sourceOwnerId to it.sourceType }
            .map { (key, rows) ->
                val qty = rows.sumOf { it.quantity }.coerceAtMost(base)
                val pct = counterparties.find(key.first)?.feeSharePct ?: defaultFeeSharePct
                FeeShare(key.first, key.second,
                    BigDecimal(qty).divide(outstanding, 10, RoundingMode.HALF_UP).multiply(pct))
            }
    }

    // ---- reports ----

    fun forContract(contractId: String): List<ContractAllocation> = allocations.forContract(contractId)

    /** Client and third-party stock currently on loan (segregation / client-asset view). */
    fun clientAssetsOnLoan(ownerId: String? = null): List<ClientAssetLine> =
        allocations.current()
            .filter { it.positionId != null }
            .filter { it.sourceType == AllocationSource.CLIENT_MARGIN || it.sourceType == AllocationSource.THIRD_PARTY }
            .filter { ownerId == null || it.sourceOwnerId == ownerId }
            .groupBy { it.positionId!! }
            .map { (positionId, rows) ->
                val p = positions.find(positionId)
                val onLoan = rows.sumOf { it.quantity }
                val underRecall = rows.sumOf { it.recalledQty }
                val held = p?.let { positionCapacity(it) } ?: 0
                ClientAssetLine(
                    ownerId = rows.first().sourceOwnerId,
                    stockCode = rows.first().stockCode,
                    sourceType = rows.first().sourceType,
                    positionId = positionId,
                    positionQty = p?.quantity ?: 0,
                    onLoanQty = onLoan,
                    underRecallQty = underRecall,
                    uncoveredQty = (onLoan - held - underRecall).coerceAtLeast(0),
                    loans = rows.groupBy { it.contractId }.map { (cid, rs) ->
                        ClientLoan(cid, contracts.find(cid)?.borrowerId ?: "?", rs.sumOf { it.quantity }, rs.sumOf { it.recalledQty })
                    }
                )
            }
            .sortedWith(compareBy({ it.ownerId }, { it.stockCode }))

    /** Street borrow-ins with the loan-outs they fund and the spread earned (matched book). */
    fun matchedBook(stockCode: String? = null): List<MatchedBookLine> {
        val today = SblTime.today()
        return contracts.booked()
            .filter { it.bookSide == BookSide.BORROW_IN && (stockCode == null || it.stockCode == stockCode) }
            .map { k ->
                val price = market.latestPrice(k.stockCode, today)
                val borrowRate = currentRate(k)
                val rows = allocations.currentForSourceContract(k.contractId)
                val pairs = rows.groupBy { it.contractId }.map { (cid, rs) ->
                    val loan = contracts.find(cid)!!
                    val qty = rs.sumOf { it.quantity }
                    val loanRate = currentRate(loan)
                    val spread = loanRate.subtract(borrowRate)
                    MatchedPair(cid, loan.borrowerId, qty, loanRate,
                        spread.multiply(BigDecimal(10000)).setScale(2, RoundingMode.HALF_UP),
                        price?.let { daily(it, qty, spread) })
                }
                val allocated = rows.sumOf { it.quantity }
                val unpaired = (k.quantityOutstanding - allocated).coerceAtLeast(0)
                val net = price?.let { px ->
                    pairs.fold(BigDecimal.ZERO) { a, pr -> a.add(pr.dailySpreadHkd!!) }
                        .subtract(daily(px, unpaired, borrowRate))
                }
                MatchedBookLine(k.contractId, k.lenderId, k.stockCode, k.status, k.quantityOutstanding,
                    borrowRate, allocated, unpaired, pairs, net?.let { Money.money(it) })
            }
    }

    // ---- internals ----

    private fun currentRate(c: SblContract): BigDecimal =
        if (c.feeType == FeeType.FLOATING) {
            val idx = c.floatingIndex?.let { market.index(it, SblTime.today()) } ?: BigDecimal.ZERO
            idx.add(BigDecimal(c.floatingSpreadBps ?: 0).divide(BigDecimal(10000)))
        } else c.feeRate

    private fun daily(price: BigDecimal, qty: Long, rate: BigDecimal): BigDecimal = Money.money(
        price.multiply(BigDecimal(qty)).multiply(rate).divide(BigDecimal(dayCount), Money.MONEY_SCALE, Money.ROUNDING)
    )

    /**
     * Cover a source deficit (allocated - capacity - already recalled): move the newest
     * allocations to other free sources first; whatever cannot be moved is a [Shortfall].
     */
    private fun rebalance(
        rows: List<ContractAllocation>, capacity: Long, date: LocalDate,
        excludePositionId: String?, excludeBorrowInId: String?
    ): List<Shortfall> {
        var deficit = rows.sumOf { it.quantity } - capacity - rows.sumOf { it.recalledQty }
        if (deficit <= 0) return emptyList()
        val shortfalls = mutableListOf<Shortfall>()
        for (row in rows.sortedByDescending { it.createdAt }) {
            if (deficit <= 0) break
            val movable = row.quantity - row.recalledQty
            if (movable <= 0) continue
            val need = minOf(deficit, movable)
            val c = contracts.find(row.contractId) ?: continue
            val slices = plan(c.stockCode, c.lenderId, need,
                excludePositionId = excludePositionId, excludeBorrowInId = excludeBorrowInId)
            val moved = slices.sumOf { it.quantity }
            var currentRow: ContractAllocation? = row
            if (moved > 0) {
                currentRow = resize(row, row.quantity - moved, date)
                apply(c, slices, date)
                audit.log("CONTRACT", c.contractId, "ALLOCATION_SUBSTITUTED", "SYSTEM",
                    oldValue = "${row.sourceType}:${row.sourceOwnerId}:$moved",
                    newValue = slices.joinToString("; ") { "${it.sourceType}:${it.ownerId}:${it.quantity}" })
            }
            val unresolved = need - moved
            if (unresolved > 0 && currentRow != null) {
                shortfalls += Shortfall(c.contractId, currentRow.allocationId, unresolved)
            }
            deficit -= need
        }
        return shortfalls
    }

    /**
     * Change a row's quantity effective [date]. Same-day changes amend in place; otherwise
     * the row is closed and a successor opened so history stays point-in-time.
     * Returns the row now carrying the quantity, or null when closed out.
     */
    private fun resize(
        row: ContractAllocation, newQty: Long, date: LocalDate, newRecalled: Long = row.recalledQty
    ): ContractAllocation? {
        if (newQty == row.quantity && newRecalled == row.recalledQty) return row
        if (newQty <= 0) {
            row.effectiveTo = date
            allocations.update(row)
            return null
        }
        if (!row.effectiveFrom.isBefore(date) || newQty == row.quantity) {
            row.quantity = newQty
            row.recalledQty = newRecalled.coerceAtMost(newQty)
            allocations.update(row)
            return row
        }
        row.effectiveTo = date
        allocations.update(row)
        val now = SblTime.now()
        val next = row.copy(
            allocationId = Ids.newId(), quantity = newQty, recalledQty = newRecalled.coerceAtMost(newQty),
            effectiveFrom = date, effectiveTo = null, createdAt = now, updatedAt = now
        )
        allocations.insert(next)
        return next
    }
}
