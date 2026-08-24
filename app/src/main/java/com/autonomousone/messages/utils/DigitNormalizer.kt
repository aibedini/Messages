package com.autonomousone.messages.utils

/**
 * Persian/Arabic digit normalization — the phone-number Swiss army knife.
 *
 * Users type and receive numbers with Persian digits (۰۱۲۳۴۵۶۷۸۹), Arabic-Indic
 * digits (٠١٢٣٤٥٦٧٨٩), separators (– — ‐ ‒ −) and ZWNJ. Everything funnels
 * through [toAsciiDigits] so comparisons, dialing and API calls always see
 * plain ASCII digits.
 */
object DigitNormalizer {

    private val PERSIAN = mapOf(
        '۰' to '0', '۱' to '1', '۲' to '2', '۳' to '3', '۴' to '4',
        '۵' to '5', '۶' to '6', '۷' to '7', '۸' to '8', '۹' to '9',
        '٠' to '0', '١' to '1', '٢' to '2', '٣' to '3', '٤' to '4',
        '٥' to '5', '٦' to '6', '٧' to '7', '٨' to '8', '٩' to '9',
        // Persian decimal separator & thousands separator
        '٫' to '.', '٬' to ',',
        // Common dash variants used inside numbers
        '–' to '-', '—' to '-', '‐' to '-', '‒' to '-', '−' to '-',
        // Arabic comma often typed between digits
        '،' to ','
    )

    /** Converts every Persian/Arabic digit (and dash variants) to ASCII. */
    fun toAsciiDigits(text: String): String =
        buildString(text.length) {
            for (ch in text) append(PERSIAN[ch] ?: ch)
        }

    /**
     * True when [text] contains at least one non-ASCII digit
     * (i.e. it was written in Persian/Arabic numerals).
     */
    fun hasNonAsciiDigits(text: String): Boolean =
        text.any { it in PERSIAN && PERSIAN[it]!!.isDigit() }
}
