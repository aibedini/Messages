package com.autonomousone.messages.utils

/**
 * Standards-compliant SMS segment counting (GSM 03.38 / 3GPP TS 23.038).
 *
 * Two encodings exist, chosen automatically per message:
 *  - GSM-7 : 160 chars for a single SMS, 153 per part when concatenated.
 *            Some chars live in the "extended table" (£ @ € …) and consume 2.
 *  - UCS-2 : used when ANY character falls outside GSM-7 (Persian/Arabic/
 *            emoji/CJK…). 70 chars single, 67 per part.
 */
object SmsSegmentCounter {

    enum class Encoding { GSM7, UCS2 }

    data class Info(
        val segments: Int,
        val encoding: Encoding,
        val charsPerSegment: Int,
        val charsRemainingInLast: Int
    )

    /** Chars that cost 2 units in GSM-7 (escape-table prefix '). */
    private const val GSM7_SINGLE =
        "@£\$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?" +
                "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà"

    private val GSM7_EXTENDED = setOf('^', '{', '}', '\\', '[', '~', ']', '|', '€')

    fun gsm7Compatible(text: String): Boolean =
        text.all { it in GSM7_SINGLE || it in GSM7_EXTENDED }

    fun count(text: String): Info {
        if (text.isEmpty()) {
            return Info(0, Encoding.GSM7, 160, 160)
        }

        val unicode = !gsm7Compatible(text)
        return if (unicode) {
            // UCS-2: every char is exactly one unit (surrogate pairs still
            // occupy 2 UTF-16 units — matches how modems actually split).
            val len = text.length
            if (len <= 70) Info(1, Encoding.UCS2, 70, 70 - len)
            else {
                val per = 67
                val segs = ceilDiv(len, per)
                Info(segs, Encoding.UCS2, per, segs * per - len)
            }
        } else {
            var units = 0
            for (ch in text) units += if (ch in GSM7_EXTENDED) 2 else 1
            if (units <= 160) Info(1, Encoding.GSM7, 160, 160 - units)
            else {
                val per = 153
                val segs = ceilDiv(units, per)
                Info(segs, Encoding.GSM7, per, segs * per - units)
            }
        }
    }

    private fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b
}
