package com.autonomousone.messages.messaging

/**
 * Display formatting for a SIM's phone number.
 *
 * This is presentation only: [SimInfo.number] never participates in routing, and a number is never
 * allowed on the gateway/telemetry paths (SIM identity there is [SimInfo.simRef], which is derived
 * from the subscription id and contains none of this).
 *
 * Iranian carriers return the same line in three shapes — `+98933…`, `98933…`, `0933…` — and some
 * SIMs return Persian or Arabic digits. The UI needs one predictable, grouped spelling:
 * `0933 442 2769`.
 */
object SimNumberFormat {

    private const val NATIONAL_LENGTH = 11
    private const val MOBILE_PREFIX = "09"

    /** Returns `0933 442 2769`, or null when the input is not a usable Iranian mobile number. */
    fun format(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        var digits = latinDigits(text).filter { it in '0'..'9' }
        if (digits.isEmpty()) return null

        // Country-code forms. The leading `+` itself carries no information once the digits are
        // known, so all three accepted shapes converge on the national number.
        digits = when {
            digits.startsWith("0098") && digits.length == 14 -> digits.drop(4)
            digits.startsWith("98") && digits.length == 12 -> digits.drop(2)
            else -> digits
        }

        if (digits.length == 10 && digits.startsWith("9")) {
            digits = "0$digits"
        }
        if (digits.length != NATIONAL_LENGTH || !digits.startsWith(MOBILE_PREFIX)) return null

        val national = digits.substring(1)
        return buildString {
            append(digits.take(4)).append(' ')
            append(national.substring(3, 6)).append(' ')
            append(national.substring(6))
        }
    }

    /** The last four digits, for a chip that has no room for the whole number. */
    fun lastFour(raw: String?): String? = format(raw)?.takeLast(4)

    /**
     * Wraps a Latin number in LRI/PDI so an RTL paragraph cannot reorder its digit groups.
     *
     * Kept here (rather than only in Compose) so the isolation is part of the display contract and is
     * covered by the formatter test.
     */
    fun isolate(text: String): String = "\u2066$text\u2069"

    /**
     * Converts Persian (`۰۱۲…`) and Arabic (`٠١٢…`) digits to ASCII.
     *
     * Deliberately a character map rather than `Character.digit`: the map is explicit about which
     * scripts are accepted, and it cannot silently accept a script the UI did not intend.
     */
    internal fun latinDigits(text: String): String = buildString(text.length) {
        for (ch in text) {
            val mapped = when (ch) {
                in '\u06F0'..'\u06F9' -> '0' + (ch - '\u06F0')
                in '\u0660'..'\u0669' -> '0' + (ch - '\u0660')
                else -> ch
            }
            append(mapped)
        }
    }
}

/**
 * The display priority for a SIM line: a user-entered number wins over the carrier/SIM value, and
 * neither is invented when both are absent.
 */
object SimNumberDisplay {

    fun resolve(manual: String?, system: String?): String? {
        val entered = manual?.trim()?.takeIf { it.isNotEmpty() }
        if (entered != null) {
            // Keep what the user typed if it cannot be parsed; the manual value is authoritative for
            // DISPLAY, and silently replacing it with the system value would hide the typo.
            return SimNumberFormat.format(entered) ?: entered
        }
        return SimNumberFormat.format(system)
    }
}
