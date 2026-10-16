package com.piscessoft.sbl.model

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * Collateral posted to a counterparty's collateral account. Collateral is not tied to any
 * one contract: it covers the counterparty's whole loan book (netting set).
 */
data class CollateralPosition(
    val positionId: String,
    val counterpartyId: String,
    val collateralType: String,
    val assetId: String,
    var quantity: BigDecimal,
    var marketValueLocal: BigDecimal,
    var fxRateToHkd: BigDecimal,
    var marketValueHkd: BigDecimal,
    var haircutPct: BigDecimal,
    var adjustedValueHkd: BigDecimal,
    var lastMtmDate: LocalDate,
    var status: CollateralStatus = CollateralStatus.ACTIVE,
    var marginCallId: String? = null,
    val createdAt: Instant,
    var updatedAt: Instant
)

/** A call on a counterparty's collateral account; at most one is open per counterparty. */
data class MarginCall(
    val callId: String,
    val counterpartyId: String,
    val callDate: LocalDate,
    var borrowedValueHkd: BigDecimal,
    var requiredCollateralHkd: BigDecimal,
    var currentCollateralHkd: BigDecimal,
    var deficitHkd: BigDecimal,
    var coverageRatio: BigDecimal,
    val deadline: Instant,
    var status: MarginCallStatus,
    var resolvedAt: Instant? = null,
    var resolutionMethod: ResolutionMethod? = null,
    val createdAt: Instant
)

/** Daily snapshot of a counterparty's collateral account. */
data class CollateralMtmHistory(
    val historyId: String,
    val counterpartyId: String,
    val mtmDate: LocalDate,
    val borrowedValueHkd: BigDecimal,
    val requiredCollateralHkd: BigDecimal,
    val actualCollateralHkd: BigDecimal,
    val coverageRatio: BigDecimal,
    val marginCallTriggered: Boolean,
    val createdAt: Instant
)

/** Daily snapshot of one contract's collateral requirement (the negative side of the account). */
data class ContractMtmHistory(
    val historyId: String,
    val contractId: String,
    val mtmDate: LocalDate,
    val closingPrice: BigDecimal,
    val borrowedValueHkd: BigDecimal,
    val requiredCollateralHkd: BigDecimal,
    val createdAt: Instant
)

/** Where a line of collateral in the account comes from. */
enum class CollateralSource {
    /** Collateral posted to the account (cash, bonds, guarantees, pledged equities). */
    POSTED,
    /** A settled client holding in the counterparty's own account at the firm. */
    CLIENT_STOCK
}

/** Positive side: one line of collateral in a counterparty's account. */
data class CollateralLine(
    val source: CollateralSource,
    /** Collateral position id (POSTED) or inventory position id (CLIENT_STOCK). */
    val referenceId: String,
    val collateralType: String,
    val assetId: String,
    val quantity: BigDecimal,
    val marketValueHkd: BigDecimal,
    val haircutPct: BigDecimal,
    val adjustedValueHkd: BigDecimal,
    /** Why this line counts for nothing (wrong-way risk, no price), or null if it counts. */
    val excludedReason: String? = null
)

/** Haircut value of one collateral type and how much of it counts towards cover. */
data class CollateralTypeCover(
    val collateralType: String,
    val adjustedValueHkd: BigDecimal,
    /** Concentration / eligibility limit applied to this type, or null if uncapped. */
    val limitHkd: BigDecimal?,
    val eligibleValueHkd: BigDecimal
)

/** Negative side: one contract's collateral requirement. */
data class ContractRequirement(
    val contractId: String,
    val counterpartyId: String,
    val stockCode: String,
    val quantityOutstanding: Long,
    val shortSale: Boolean,
    val collateralRatio: BigDecimal,
    val closingPrice: BigDecimal?,
    val fxRateToHkd: BigDecimal?,
    val borrowedValueHkd: BigDecimal?,
    val requiredCollateralHkd: BigDecimal?,
    /** No price / FX for the borrowed stock: the requirement is unknown. */
    val priceMissing: Boolean
)

/**
 * A counterparty's collateral account: everything it holds with the firm (posted collateral
 * plus client stock, after haircut) against everything it owes (borrowed value of every
 * contract on loan to it, at each contract's required ratio).
 */
data class CollateralAccount(
    val counterpartyId: String,
    val valuationDate: LocalDate,
    val collateral: List<CollateralLine>,
    val byType: List<CollateralTypeCover>,
    val contracts: List<ContractRequirement>,
    /** Haircut value of all lines that are not excluded. */
    val grossCollateralHkd: BigDecimal,
    /** Value that counts towards cover after concentration and eligibility limits. */
    val eligibleCollateralHkd: BigDecimal,
    /** Borrowed value of the priced contracts. */
    val borrowedValueHkd: BigDecimal,
    /** Required collateral of the priced contracts. */
    val requiredCollateralHkd: BigDecimal,
    /** Eligible collateral minus required: positive is surplus, negative is deficit. */
    val netHkd: BigDecimal,
    val coverageRatio: BigDecimal,
    val unpricedContracts: List<String>,
    /** Some contract could not be valued, so [requiredCollateralHkd] is a lower bound. */
    val priceMissing: Boolean,
    val marginCall: MarginCall?,
    val excessReleaseEligible: Boolean
)
