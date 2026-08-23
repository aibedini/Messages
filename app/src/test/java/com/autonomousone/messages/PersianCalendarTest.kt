package com.autonomousone.messages

import com.autonomousone.messages.utils.PersianCalendar
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class PersianCalendarTest {

    private fun gregorian(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, 12, 0, 0) // noon avoids DST/day-boundary issues
        }.timeInMillis

    @Test fun `nowruz 1405`() {
        // 1 Farvardin 1405 == March 21, 2026
        assertEquals("۱۴۰۵/۱/۱", PersianCalendar.format(gregorian(2026, 3, 21), "yyyy/M/d"))
    }

    @Test fun `nowruz 1403`() {
        // 1 Farvardin 1403 == March 20, 2024
        assertEquals("۱۴۰۳/۱/۱", PersianCalendar.format(gregorian(2024, 3, 20), "yyyy/M/d"))
    }

    @Test fun `august 23 2026 is shahrivar 1`() {
        assertEquals("۱۴۰۵/۶/۱", PersianCalendar.format(gregorian(2026, 8, 23), "yyyy/M/d"))
    }

    @Test fun `revolution day 22 bahman 1357`() {
        // February 11, 1979 == 22 Bahman 1357
        assertEquals("۱۳۵۷/۱۱/۲۲", PersianCalendar.format(gregorian(1979, 2, 11), "yyyy/M/d"))
    }

    @Test fun `month names render in persian`() {
        assertEquals("شهریور ۱", PersianCalendar.format(gregorian(2026, 8, 23), "MMMM d"))
    }

    @Test fun `time tokens are preserved`() {
        val noonPlusHalf = gregorian(2026, 8, 23) + 30 * 60 * 1000L
        assertEquals("۱۲:۳۰", PersianCalendar.format(noonPlusHalf, "HH:mm"))
    }
}
