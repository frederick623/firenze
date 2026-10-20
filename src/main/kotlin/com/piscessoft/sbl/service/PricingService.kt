package com.piscessoft.sbl.service

import com.piscessoft.sbl.model.*
import com.piscessoft.sbl.repo.*
import com.piscessoft.sbl.util.Money
import com.piscessoft.sbl.util.SblTime
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Pricing: the single place where instrument prices are stored and holdings are marked to
 * market.
 *
 * - Prices: closing prices per instrument and date (listed stocks, bonds and other non-cash
 *   collateral assets share one store, keyed by stock code / asset id) with their source.
 * - Quotes: the latest price at or before a date plus the instrument currency's FX to HKD.
 *   An instrument's currency is its designated-security currency (HKD if unknown); for
 *   posted collateral it is the collateral type's currency.
 * - Revaluation: writes price, FX and HKD market value onto every booked contract (on the
 *   outstanding quantity), every inventory position, and every ACTIVE collateral position
 *   (market value and haircut value). Holdings without a price keep their last MTM.
 */
class PricingService(
    private val market: MarketDataRepo,
    private val securities: DesignatedSecurityRepo,
    private val configs: CollateralTypeConfigRepo,
    private val contracts: ContractRepo,
    private val positions: PositionRepo,
    private val collateral: CollateralPositionRepo,
    private val audit: AuditService
) {

    // ---- prices ----

    /** Store (or correct) a closing price. Audited, since prices drive margin calls. */
    fun recordPrice(instrumentId: String, date: LocalDate, price: BigDecimal, source: String = "MANUAL"): InstrumentPrice {
        require(price.signum() > 0) { "Price for $instrumentId must be > 0" }
        val previous = market.price(instrumentId, date)
        market.putPrice(instrumentId, date, price, source)
        audit.log("PRICE", instrumentId, if (previous == null) "PRICE_RECORDED" else "PRICE_CORRECTED", source,
            oldValue = previous?.let { "$date ${it.toPlainString()}" },
            newValue = "$date ${price.toPlainString()} source=$source")
        return market.latestPriceRecord(instrumentId, date)!!
    }

    /** Store a batch of closing prices (e.g. the EOD feed) for one date. */
    fun recordPrices(date: LocalDate, prices: Map<String, BigDecimal>, source: String = "MANUAL"): List<InstrumentPrice> =
        prices.map { (id, px) -> recordPrice(id, date, px, source) }

    fun latestPrice(instrumentId: String, date: LocalDate = SblTime.today()): InstrumentPrice? =
        market.latestPriceRecord(instrumentId, date)

    fun priceHistory(instrumentId: String, from: LocalDate, to: LocalDate): List<InstrumentPrice> =
        market.priceHistory(instrumentId, from, to)

    fun currencyOf(instrumentId: String): String = securities.find(instrumentId)?.currency ?: "HKD"

    fun fx(currency: String, date: LocalDate): BigDecimal? = market.fxOrNull(currency, date)

    /** Price + FX for valuing [instrumentId] on [date]; null when either is missing. */
    fun quote(instrumentId: String, date: LocalDate = SblTime.today(), currency: String = currencyOf(instrumentId)): PriceQuote? {
        val px = market.latestPriceRecord(instrumentId, date) ?: return null
        val fx = market.fxOrNull(currency, date) ?: return null
        return PriceQuote(instrumentId, date, currency, px.closingPrice, px.priceDate, px.source, fx,
            stale = px.priceDate.isBefore(date))
    }

    // ---- collateral valuation ----

    class CollateralValuation(
        val unitPrice: BigDecimal,
        val fxRateToHkd: BigDecimal,
        val marketValueLocal: BigDecimal,
        val marketValueHkd: BigDecimal,
        val adjustedValueHkd: BigDecimal,
        /** False when no current price / FX was found and the position's last values were reused. */
        val priced: Boolean,
        val stale: Boolean
    )

    /** Face value for nominal types (cash, guarantees); otherwise the latest market price. */
    fun collateralUnitPrice(cfg: CollateralTypeConfig, assetId: String, date: LocalDate): BigDecimal? =
        if (cfg.nominalValued) BigDecimal.ONE else market.latestPrice(assetId, date)

    /**
     * Value a posted collateral position on [date]: face value for nominal types, otherwise
     * the latest market price; falls back to the last system-determined unit price / FX on the
     * position when no current price or rate is available.
     */
    fun valueCollateral(p: CollateralPosition, date: LocalDate): CollateralValuation {
        val cfg = configs.find(p.collateralType)
        val record = if (cfg == null || cfg.nominalValued) null else market.latestPriceRecord(p.assetId, date)
        val currentUnit = when {
            cfg == null -> null
            cfg.nominalValued -> BigDecimal.ONE
            else -> record?.closingPrice
        }
        val currentFx = cfg?.let { market.fxOrNull(it.currency, date) }
        val unit = currentUnit
            ?: if (p.quantity.signum() == 0) BigDecimal.ZERO else Money.ratio(p.marketValueLocal, p.quantity)
        val fx = currentFx ?: p.fxRateToHkd
        val local = Money.money(p.quantity.multiply(unit))
        val hkd = Money.money(local.multiply(fx))
        return CollateralValuation(unit, fx, local, hkd,
            Money.money(hkd.multiply(BigDecimal.ONE.subtract(p.haircutPct))),
            priced = currentUnit != null && currentFx != null,
            stale = record != null && record.priceDate.isBefore(date))
    }

    /** Revalue and persist one collateral position. [CollateralPosition.lastMtmDate] moves only when priced. */
    fun revalueCollateral(p: CollateralPosition, date: LocalDate): CollateralValuation {
        val v = valueCollateral(p, date)
        p.marketValueLocal = v.marketValueLocal
        p.fxRateToHkd = v.fxRateToHkd
        p.marketValueHkd = v.marketValueHkd
        p.adjustedValueHkd = v.adjustedValueHkd
        if (v.priced) p.lastMtmDate = date
        p.updatedAt = SblTime.now()
        collateral.update(p)
        return v
    }

    // ---- revaluation ----

    /** Mark one contract's outstanding quantity to market. Returns the quote used, or null if unpriced. */
    fun revalueContract(k: SblContract, date: LocalDate = SblTime.today()): PriceQuote? {
        val q = quote(k.stockCode, date) ?: return null
        k.mtmPrice = q.price
        k.mtmFxRate = q.fxRateToHkd
        k.mtmValueHkd = Money.money(q.price.multiply(q.fxRateToHkd).multiply(BigDecimal(k.quantityOutstanding)))
        k.lastMtmDate = date
        contracts.updateMtm(k)
        return q
    }

    /** Mark one inventory position to market. Returns the quote used, or null if unpriced. */
    fun revaluePosition(p: Position, date: LocalDate = SblTime.today()): PriceQuote? {
        val q = quote(p.stockCode, date) ?: return null
        p.mtmPrice = q.price
        p.mtmFxRate = q.fxRateToHkd
        p.mtmValueHkd = Money.money(q.price.multiply(q.fxRateToHkd).multiply(BigDecimal(p.quantity)))
        p.lastMtmDate = date
        positions.updateMtm(p)
        return q
    }

    /**
     * Revalue booked contracts, inventory positions and ACTIVE collateral on [date], or only
     * the holdings of [instrumentId] (e.g. right after its price changed intraday).
     */
    fun revalue(date: LocalDate = SblTime.today(), instrumentId: String? = null): RevaluationResult {
        val unpriced = sortedSetOf<String>()
        val stale = sortedSetOf<String>()
        fun track(id: String, q: PriceQuote?) {
            if (q == null) unpriced += id else if (q.stale) stale += id
        }

        var contractCount = 0
        var contractValue = BigDecimal.ZERO
        val book = contracts.booked().filter { it.quantityOutstanding > 0 && (instrumentId == null || it.stockCode == instrumentId) }
        for (k in book) {
            val q = revalueContract(k, date)
            track(k.stockCode, q)
            if (q != null) { contractCount++; contractValue = contractValue.add(k.mtmValueHkd) }
        }

        var positionCount = 0
        var positionValue = BigDecimal.ZERO
        for (p in instrumentId?.let { positions.forStock(it) } ?: positions.all()) {
            val q = revaluePosition(p, date)
            track(p.stockCode, q)
            if (q != null) { positionCount++; positionValue = positionValue.add(p.mtmValueHkd) }
        }

        var collateralCount = 0
        var collateralValue = BigDecimal.ZERO
        for (p in collateral.active(instrumentId)) {
            val v = revalueCollateral(p, date)
            if (!v.priced) unpriced += p.assetId
            else {
                if (v.stale) stale += p.assetId
                collateralCount++
                collateralValue = collateralValue.add(v.marketValueHkd)
            }
        }

        val result = RevaluationResult(
            valuationDate = date,
            instrumentId = instrumentId,
            contractsValued = contractCount,
            positionsValued = positionCount,
            collateralValued = collateralCount,
            contractsValueHkd = Money.money(contractValue),
            inventoryValueHkd = Money.money(positionValue),
            collateralValueHkd = Money.money(collateralValue),
            unpricedInstruments = unpriced.toList(),
            staleInstruments = (stale - unpriced).toList()
        )
        audit.log("PRICING", instrumentId ?: "ALL", "REVALUATION", "SYSTEM",
            newValue = "date=$date contracts=$contractCount positions=$positionCount collateral=$collateralCount " +
                "unpriced=${result.unpricedInstruments}")
        return result
    }
}
