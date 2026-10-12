package com.piscessoft.sbl.util

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Money / decimal helpers. All monetary amounts are HKD unless stated otherwise and
 * are persisted as TEXT to preserve exact [BigDecimal] precision in SQLite.
 */
object Money {
    val ZERO: BigDecimal = BigDecimal.ZERO.setScale(4)
    val MONEY_SCALE = 4
    val RATE_SCALE = 6
    val ROUNDING: RoundingMode = RoundingMode.HALF_UP

    fun of(v: String): BigDecimal = BigDecimal(v).setScale(MONEY_SCALE, ROUNDING)
    fun of(v: Double): BigDecimal = BigDecimal.valueOf(v).setScale(MONEY_SCALE, ROUNDING)

    fun money(v: BigDecimal): BigDecimal = v.setScale(MONEY_SCALE, ROUNDING)
    fun rate(v: BigDecimal): BigDecimal = v.setScale(RATE_SCALE, ROUNDING)

    /** Ratio a/b as a high-precision decimal (avoids ArithmeticException on non-terminating). */
    fun ratio(a: BigDecimal, b: BigDecimal): BigDecimal =
        if (b.signum() == 0) ZERO else a.divide(b, 8, ROUNDING)

    fun format(v: BigDecimal): String =
        "%,.2f".format(v.setScale(2, ROUNDING))

    // TEXT persistence
    fun toDb(v: BigDecimal?): String? = v?.toPlainString()
    fun fromDb(s: String?): BigDecimal? = s?.let { BigDecimal(it) }
    fun fromDbOrZero(s: String?): BigDecimal = s?.let { BigDecimal(it) } ?: ZERO
}
