package com.piscessoft.sbl.model

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** A stored closing price for an instrument (listed stock, bond or other collateral asset). */
data class InstrumentPrice(
    val instrumentId: String,
    val priceDate: LocalDate,
    val closingPrice: BigDecimal,
    val source: String,
    val updatedAt: Instant?
)

/** Price and FX used to value an instrument on [asOf]; [stale] when the price predates [asOf]. */
data class PriceQuote(
    val instrumentId: String,
    val asOf: LocalDate,
    val currency: String,
    val price: BigDecimal,
    val priceDate: LocalDate,
    val source: String,
    val fxRateToHkd: BigDecimal,
    val stale: Boolean
)

/** Outcome of one revaluation run over contracts, inventory positions and posted collateral. */
data class RevaluationResult(
    val valuationDate: LocalDate,
    /** Instrument the run was restricted to, or null for everything. */
    val instrumentId: String?,
    val contractsValued: Int,
    val positionsValued: Int,
    val collateralValued: Int,
    val contractsValueHkd: BigDecimal,
    val inventoryValueHkd: BigDecimal,
    val collateralValueHkd: BigDecimal,
    /** Instruments with no price / FX on or before [valuationDate]; holdings keep their last MTM. */
    val unpricedInstruments: List<String>,
    /** Instruments valued on a price older than [valuationDate]. */
    val staleInstruments: List<String>
)
