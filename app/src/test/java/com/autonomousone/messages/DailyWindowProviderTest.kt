package com.autonomousone.messages

import com.autonomousone.messages.data.DailyWindowProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One source of truth for "today". No sleeps: the clock is injected.
 */
class DailyWindowProviderTest {

    private val tehran = ZoneId.of("Asia/Tehran")
    private val newYork = ZoneId.of("America/New_York")

    private fun at(instant: String, zone: ZoneId = tehran) =
        DailyWindowProvider { Clock.fixed(Instant.parse(instant), zone) }

    private fun startOf(date: LocalDate, zone: ZoneId) =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun windowIsTheLocalCalendarDayNotA24HourBlock() {
        val window = at("2026-09-13T10:30:00Z").currentWindow()
        assertEquals(LocalDate.of(2026, 9, 13), window.date)
        assertEquals(startOf(LocalDate.of(2026, 9, 13), tehran), window.startMillis)
        assertEquals(startOf(LocalDate.of(2026, 9, 14), tehran), window.endMillis)
        assertTrue(window.contains(window.startMillis))
        assertFalse(window.contains(window.endMillis))
    }

    @Test
    fun oneSecondBeforeMidnightIsInTheOldDayAndMidnightIsInTheNewOne() {
        // Tehran is UTC+3:30, so local midnight is 20:30Z on the previous date.
        val lastSecond = at("2026-09-13T20:29:59Z").currentWindow()
        val midnight = at("2026-09-13T20:30:00Z").currentWindow()

        assertEquals(LocalDate.of(2026, 9, 13), lastSecond.date)
        assertEquals(LocalDate.of(2026, 9, 14), midnight.date)
        assertTrue(lastSecond.contains(midnight.startMillis - 1))
        assertFalse(lastSecond.contains(midnight.startMillis))
        assertTrue(midnight.contains(midnight.startMillis))
        assertEquals(lastSecond.endMillis, midnight.startMillis)
    }

    @Test
    fun aWindowLeftOpenAcrossMidnightMovesToTheNewDay() {
        var now = Instant.parse("2026-09-13T20:29:00Z")
        val provider = DailyWindowProvider { Clock.fixed(now, tehran) }

        val before = provider.currentWindow()
        now = Instant.parse("2026-09-13T20:31:00Z")
        val after = provider.currentWindow()

        assertEquals(LocalDate.of(2026, 9, 13), before.date)
        assertEquals(LocalDate.of(2026, 9, 14), after.date)
        assertEquals(before.endMillis, after.startMillis)
    }

    @Test
    fun aTimezoneChangeWhileOpenMovesTheBoundaries() {
        val instant = Instant.parse("2026-09-13T21:30:00Z")
        val openInTehran = DailyWindowProvider { Clock.fixed(instant, tehran) }.currentWindow()
        val afterChange = DailyWindowProvider { Clock.fixed(instant, ZoneId.of("UTC")) }.currentWindow()

        assertNotEquals(openInTehran.startMillis, afterChange.startMillis)
        assertEquals(LocalDate.of(2026, 9, 14), openInTehran.date)
        assertEquals(LocalDate.of(2026, 9, 13), afterChange.date)
    }

    @Test
    fun springForwardDayIs23Hours() {
        // US DST starts 2026-03-08.
        val window = at("2026-03-08T12:00:00Z", newYork).currentWindow()
        assertEquals(LocalDate.of(2026, 3, 8), window.date)
        assertEquals(23 * 3_600_000L, window.lengthMillis)
    }

    @Test
    fun fallBackDayIs25Hours() {
        // US DST ends 2026-11-01.
        val window = at("2026-11-01T12:00:00Z", newYork).currentWindow()
        assertEquals(LocalDate.of(2026, 11, 1), window.date)
        assertEquals(25 * 3_600_000L, window.lengthMillis)
    }
}
