package com.piscessoft.sbl.model

import java.time.Instant
import java.time.LocalDate

data class Position(
    val positionId: String,
    val ownerId: String,
    val stockCode: String,
    val accountType: AccountType,
    var quantity: Long,
    var settlementStatus: SettlementStatus,
    var lendable: Boolean,
    var asOfDate: LocalDate,
    var updatedAt: Instant,
    val sourceSystem: String,
    val sourceRef: String? = null
)

/** Computed lendable-pool breakdown for a stock (plan section 3.2). */
data class LendablePool(
    val stockCode: String,
    val grossLendable: Long,
    val locateCommitted: Long,
    val onLoan: Long,
    val netAvailable: Long,
    val qtyProprietary: Long,
    val qtyClientDisc: Long,
    val qtyLendingPool: Long,
    val qtyRecalled: Long,
    /** Street borrow-in quantity usable as supply (included in [grossLendable]). */
    val qtyBorrowedIn: Long = 0
)

data class LendablePoolSnapshot(
    val snapshotId: String,
    val stockCode: String,
    val asOfDatetime: Instant,
    val grossLendable: Long,
    val locateCommitted: Long,
    val onLoan: Long,
    val netAvailable: Long,
    val qtyProprietary: Long?,
    val qtyClientDisc: Long?,
    val qtyLendingPool: Long?,
    val qtyRecalled: Long?,
    val createdAt: Instant
)
