package com.piscessoft.sbl.model

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

data class CollateralPosition(
    val positionId: String,
    val contractId: String,
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

data class MarginCall(
    val callId: String,
    val contractId: String,
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

data class CollateralMtmHistory(
    val historyId: String,
    val contractId: String,
    val mtmDate: LocalDate,
    val borrowedValueHkd: BigDecimal,
    val requiredCollateralHkd: BigDecimal,
    val actualCollateralHkd: BigDecimal,
    val coverageRatio: BigDecimal,
    val marginCallTriggered: Boolean,
    val closingPrice: BigDecimal,
    val createdAt: Instant
)

/** Outcome of a per-contract MTM valuation. */
data class MtmResult(
    val contractId: String,
    val borrowedValueHkd: BigDecimal,
    val requiredCollateralHkd: BigDecimal,
    val currentCollateralHkd: BigDecimal,
    val surplusDeficitHkd: BigDecimal,
    val coverageRatio: BigDecimal,
    val marginCall: MarginCall?,
    val excessReleaseEligible: Boolean
)
