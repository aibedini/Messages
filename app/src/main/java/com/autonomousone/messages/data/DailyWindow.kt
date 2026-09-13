package com.autonomousone.messages.data

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * A half-open local calendar day `[startMillis, endMillis)` expressed in UTC
 * epoch millis, plus the date and zone it was derived from.
 */
data class DailyWindow(
    val date: LocalDate,
    val zone: ZoneId,
    val startMillis: Long,
    val endMillis: Long
) {
    /** Length in millis — 23h/25h on DST transition days, 24h otherwise. */
    val lengthMillis: Long get() = endMillis - startMillis

    fun contains(epochMillis: Long): Boolean =
        epochMillis >= startMillis && epochMillis < endMillis
}

/**
 * The single source of truth for "today" boundaries.
 *
 * Timestamps in the ledger are stored as UTC epoch millis; a timezone is used
 * ONLY here, to turn a local calendar day into a half-open epoch-millis range.
 *
 * The end of the day is the START OF TOMORROW in the same zone —
 * `today.plusDays(1).atStartOfDay(zone)` — never `start + 24h`. Under a DST
 * transition those differ by an hour, and a fixed 24h window silently moves a
 * chunk of the day's segments into the neighbouring day.
 *
 * The clock is supplied through [clockSource] so that:
 *  * production always re-reads the CURRENT system zone (a timezone change
 *    while the app is open must move the boundaries);
 *  * tests inject a fixed [Clock] and get deterministic, sleep-free results.
 */
class DailyWindowProvider(
    private val clockSource: () -> Clock = { Clock.systemDefaultZone() }
) {

    /** Window that contains "now", derived from the current zone. */
    fun currentWindow(): DailyWindow {
        val clock = clockSource()
        return windowFor(clock.instant(), clock.zone)
    }

    /** Window containing [instant] as seen in [zone]. */
    fun windowFor(instant: Instant, zone: ZoneId): DailyWindow =
        windowFor(instant.atZone(zone).toLocalDate(), zone)

    /** Window for one local calendar [date] in [zone]. */
    fun windowFor(date: LocalDate, zone: ZoneId): DailyWindow = DailyWindow(
        date = date,
        zone = zone,
        startMillis = date.atStartOfDay(zone).toInstant().toEpochMilli(),
        endMillis = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    )
}
