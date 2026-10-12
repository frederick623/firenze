package com.piscessoft.sbl.util

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Time utilities for the SBL system. All business timing is expressed in Hong Kong
 * time (HKT). A mutable [clock] lets tests and the demo drive deterministic "now".
 */
object SblTime {

    val HKT: ZoneId = ZoneId.of("Asia/Hong_Kong")

    /** Overridable clock. Defaults to the real system clock in the HKT zone. */
    @Volatile
    var clock: java.time.Clock = java.time.Clock.system(HKT)

    fun now(): Instant = Instant.now(clock)

    fun nowHkt(): ZonedDateTime = ZonedDateTime.ofInstant(now(), HKT)

    fun today(): LocalDate = nowHkt().toLocalDate()

    /** Freeze the clock at a specific HKT wall-clock instant (used by demo/tests). */
    fun freezeAt(date: LocalDate, time: LocalTime = LocalTime.of(9, 0)) {
        val instant = date.atTime(time).atZone(HKT).toInstant()
        clock = java.time.Clock.fixed(instant, HKT)
    }

    /** Advance a frozen clock by whole days (keeps the same wall time). */
    fun advanceDays(days: Long) {
        val next = nowHkt().plusDays(days).toInstant()
        clock = java.time.Clock.fixed(next, HKT)
    }

    fun resetToSystem() {
        clock = java.time.Clock.system(HKT)
    }

    // ---- Business-day helpers (weekend-only calendar; holidays out of scope) ----

    fun isBusinessDay(d: LocalDate): Boolean =
        d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY

    fun addBusinessDays(start: LocalDate, n: Int): LocalDate {
        var d = start
        var remaining = n
        while (remaining > 0) {
            d = d.plusDays(1)
            if (isBusinessDay(d)) remaining--
        }
        return d
    }

    fun nextBusinessDay(d: LocalDate): LocalDate = addBusinessDays(d, 1)

    /** Business days between two dates (exclusive of start, inclusive of end). */
    fun businessDaysBetween(start: LocalDate, end: LocalDate): Int {
        if (!end.isAfter(start)) return 0
        var d = start
        var count = 0
        while (d.isBefore(end)) {
            d = d.plusDays(1)
            if (isBusinessDay(d)) count++
        }
        return count
    }

    // ---- ISO-8601 formatting for TEXT persistence ----

    private val TS: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT
    private val DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    fun formatTs(i: Instant?): String? = i?.let { TS.format(it) }
    fun parseTs(s: String?): Instant? = s?.let { Instant.parse(it) }
    fun formatDate(d: LocalDate?): String? = d?.let { DATE.format(it) }
    fun parseDate(s: String?): LocalDate? = s?.let { LocalDate.parse(it) }
}
