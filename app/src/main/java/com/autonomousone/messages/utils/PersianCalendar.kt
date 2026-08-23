package com.autonomousone.messages.utils

import java.util.Calendar

/**
 * Gregorian ↔ Jalali (Persian/Shamsi) conversion using the widely used
 * 33-year-cycle "jalaali" algorithm, plus a minimal pattern renderer that
 * mirrors [java.text.SimpleDateFormat] token letters.
 */
object PersianCalendar {

    val MONTHS = arrayOf(
        "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
        "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"
    )

    private val BREAKS = intArrayOf(
        -61, 9, 38, 199, 426, 686, 756, 818, 1111, 1181, 1210,
        1635, 2060, 2097, 2192, 2262, 2324, 2394, 2456, 3178
    )

    /** Truncating integer division — mirrors the reference algorithm's `~~(a / b)`. */
    private fun div(a: Int, b: Int): Int = a / b

    private fun mod(a: Int, b: Int): Int = a - a / b * b

    /** Julian Day Number of the given Gregorian date. */
    internal fun g2d(gy: Int, gm: Int, gd: Int): Int {
        var d = div((gy + div(gm - 8, 6) + 100100) * 1461, 4) +
                div(153 * mod(gm + 9, 12) + 2, 5) +
                gd - 34840408
        d = d - div(div(gy + 100100 + div(gm - 8, 6), 100) * 3, 4) + 752
        return d
    }

    private data class Gregorian(val gy: Int, val gm: Int, val gd: Int)

    private fun d2g(jdn: Int): Gregorian {
        var j = 4 * jdn + 139361631
        j += div(div(4 * jdn + 183187720, 146097) * 3, 4) * 4 - 3908
        val i = div(mod(j, 1461), 4) * 5 + 308
        val gd = div(mod(i, 153), 5) + 1
        val gm = mod(div(i, 153), 12) + 1
        val gy = div(j, 1461) - 100100 + div(8 - gm, 6)
        return Gregorian(gy, gm, gd)
    }

    /**
     * Returns (leap, march) for the Jalali year [jy]: how many years since the
     * last leap year, and the March day on which Farvardin 1 falls.
     */
    private fun jalCal(jy: Int): Pair<Int, Int> {
        val gy = jy + 621
        var leapJ = -14
        var jp = BREAKS[0]
        var jump = 0
        for (i in 1 until BREAKS.size) {
            val jm = BREAKS[i]
            jump = jm - jp
            if (jy < jm) break
            leapJ += div(jump, 33) * 8 + div(mod(jump, 33), 4)
            jp = jm
        }
        var n = jy - jp

        // Persian-calendar leap years from AD 621 to the start of jy.
        leapJ += div(n, 33) * 8 + div(mod(n, 33) + 3, 4)
        if (mod(jump, 33) == 4 && jump - n == 4) leapJ += 1

        // Same count in the Gregorian calendar until gy.
        val leapG = div(gy, 4) - div((div(gy, 100) + 1) * 3, 4) - 150

        // Gregorian day in March of Farvardin 1.
        val march = 20 + leapJ - leapG

        // Years since the last leap year.
        if (jump - n < 6) n = n - jump + div(jump + 4, 33) * 33
        var leap = mod(mod(n + 1, 33) - 1, 4)
        if (leap == -1) leap = 4

        return Pair(leap, march)
    }

    /** Jalali (year, month 1-12, day 1-31) for the given JDN. */
    internal fun d2j(jdn: Int): Triple<Int, Int, Int> {
        val gy = d2g(jdn).gy
        var jy = gy - 621
        val (leap, march) = jalCal(jy)
        val jdn1f = g2d(gy, 3, march)
        var k = jdn - jdn1f
        if (k >= 0) {
            if (k <= 185) {
                return Triple(jy, 1 + div(k, 31), mod(k, 31) + 1)
            }
            k -= 186
        } else {
            jy -= 1
            k += 179
            if (leap == 1) k += 1
        }
        return Triple(jy, 7 + div(k, 30), mod(k, 30) + 1)
    }

    /**
     * Renders [epochMillis] with a subset of SimpleDateFormat tokens using the
     * Jalali calendar and Persian digits.
     *
     * Supported tokens: yyyy yy MMMM MMM MM M dd d HH H hh h mm ss a EEEE EEE
     * Other characters are copied literally; '…' quotes literal text.
     */
    fun format(epochMillis: Long, pattern: String, localeAmPm: Boolean = false): String {
        val cal = Calendar.getInstance().apply { timeInMillis = epochMillis }
        val (jy, jm, jd) = d2j(
            g2d(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
        )

        val amPm = if (cal.get(Calendar.AM_PM) == Calendar.AM) {
            if (localeAmPm) "AM" else "ق.ظ"
        } else {
            if (localeAmPm) "PM" else "ب.ظ"
        }
        val hour24 = cal.get(Calendar.HOUR_OF_DAY)
        val hour12 = run {
            val h = hour24 % 12
            if (h == 0) 12 else h
        }

        val sb = StringBuilder()
        var i = 0
        while (i < pattern.length) {
            val ch = pattern[i]
            var len = 0
            while (i + len < pattern.length && pattern[i + len] == ch) len++
            when (ch) {
                '\'' -> {
                    val end = pattern.indexOf('\'', i + 1)
                    if (end > i) {
                        sb.append(pattern.substring(i + 1, end))
                        i = end + 1
                        continue
                    }
                    sb.append('\'')
                }
                'y' -> sb.append(num(if (len >= 4) jy else mod(jy, 100)))
                'M' -> if (len >= 3) sb.append(MONTHS[jm - 1]) else sb.append(num(jm))
                'd' -> sb.append(num(jd))
                'H' -> sb.append(pad(hour24, if (len == 2) 2 else 1))
                'h' -> sb.append(pad(hour12, if (len == 2) 2 else 1))
                'm' -> sb.append(pad(cal.get(Calendar.MINUTE), if (len == 2) 2 else 1))
                's' -> sb.append(pad(cal.get(Calendar.SECOND), if (len == 2) 2 else 1))
                'a' -> sb.append(amPm)
                'E' -> sb.append(
                    java.text.SimpleDateFormat(if (len >= 4) "EEEE" else "EEE", java.util.Locale.getDefault())
                        .format(cal.time)
                )
                else -> sb.append(ch.toString().repeat(len))
            }
            i += len
        }
        return sb.toString()
    }

    /** Latin digits → Persian digits. */
    private fun num(value: Int): String = fa(value.toString())

    private fun fa(value: String): String {
        val sb = StringBuilder(value.length)
        for (c in value) {
            sb.append(
                when (c) {
                    '0' -> '۰'
                    '1' -> '۱'
                    '2' -> '۲'
                    '3' -> '۳'
                    '4' -> '۴'
                    '5' -> '۵'
                    '6' -> '۶'
                    '7' -> '۷'
                    '8' -> '۸'
                    '9' -> '۹'
                    else -> c
                }
            )
        }
        return sb.toString()
    }

    private fun pad(value: Int, width: Int): String =
        fa(value.toString().padStart(width, '0'))
}
