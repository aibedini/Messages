package com.autonomousone.messages.repository

/**
 * ONE phone-number identity policy for the whole app.
 *
 * WHY THIS EXISTS
 * ---------------
 * Contact display used to be resolved two different ways: the Home list did an
 * EXACT map lookup on `normalizePhone(address)`, while the conversation header used
 * Android's `PhoneLookup`. Those disagree about the most ordinary Iranian case:
 *
 *     09121234567        (how the SMS provider usually stores a local number)
 *     +989121234567      (how a contact is very often stored)
 *     00989121234567
 *     989121234567
 *
 * `normalizePhone` only strips spaces/dashes/parentheses, so the first two stayed
 * different strings — the chat header found a name and Home showed a bare number
 * for the very same conversation.
 *
 * The answer is NOT a fuzzy suffix match (that would let `112` or any short code
 * collide with an unrelated contact). It is a small, CLOSED set of spellings that
 * can only be produced by the same number:
 *
 *  - any formatting is stripped, so `0912 123 4567` and `09121234567` agree;
 *  - for a number that is unambiguously an Iranian MOBILE number the local
 *    (`0912…`), national (`98912…`), plus (`+98912…`) and IDD (`0098912…`) forms
 *    are generated, and looking any one of them up finds a contact stored with any
 *    other.
 *
 * A number is treated as an Iranian mobile only when it is EXACTLY
 * `98` + `9xxxxxxxxx` or `0` + `9xxxxxxxxx`, i.e. 12 or 11 digits. Nothing shorter
 * is ever expanded, which is what keeps `112`, `110`, `100`, USSD codes and short
 * alphanumeric sender IDs from matching a real contact.
 *
 * Pure and Android-free, so the matching rules are unit-tested directly.
 */
object PhoneIdentity {

    /** Iranian country calling code, without the `+`. */
    private const val IRAN_CC = "98"

    /** Maximum number of aliases any one number can produce. */
    private const val MAX_KEYS = 5

    /**
     * Formatting-only normalization: keep a single leading `+`, drop every other
     * non-digit. Used everywhere a phone is stored or compared.
     */
    fun normalize(raw: String): String {
        if (raw.isBlank()) return ""
        val trimmed = raw.trim()
        val digits = trimmed.filter { it.isDigit() }
        if (digits.isEmpty()) return ""
        return if (trimmed.startsWith("+")) "+$digits" else digits
    }

    /**
     * The canonical spelling of [raw], or null when there is no phone identity.
     *
     * THIS IS THE ASSIGNMENT KEY. User categories are attached to a NUMBER, not to a
     * Telephony `threadId`, because the provider can delete and recreate a thread for
     * the same correspondence: assigning `09121234567` to a category and then having
     * the provider hand back `+989121234567` under a new thread must keep the
     * membership.
     *
     * All four Iranian mobile spellings therefore collapse to ONE key:
     *
     *     09121234567 · +989121234567 · 989121234567 · 00989121234567
     *                                        → "+989121234567"
     *
     * Anything that is not safely transformable keeps its formatting-normalized exact
     * value: short codes (`112`, `110`), service numbers, landlines and foreign
     * numbers are their own identity and are NEVER suffix-matched. Returns null for
     * input with no digits at all — an alphanumeric sender id has no phone identity
     * and is keyed by the sender-id policy instead.
     */
    fun stableKey(raw: String): String? {
        val normalized = normalize(raw)
        if (normalized.isEmpty()) return null
        val national = iranianMobileNational(normalized.removePrefix("+"))
            ?: return normalized
        // Canonical Iranian mobile form: +98 followed by the 10-digit national number.
        return "+$IRAN_CC$national"
    }

    /**
     * Every spelling of [raw] that must be considered the same number.
     *
     * Always contains the plain [normalize] result. Never empty for a non-blank
     * input that has at least one digit. Returns an empty set for input with no
     * digits at all, so a blank/alphanumeric sender can never resolve to a contact.
     */
    fun lookupKeys(raw: String): Set<String> {
        val normalized = normalize(raw)
        if (normalized.isEmpty()) return emptySet()

        val keys = LinkedHashSet<String>(MAX_KEYS)
        keys += normalized

        val digits = normalized.removePrefix("+")
        iranianMobileNational(digits)?.let { national ->
            // `national` is the 10-digit `9xxxxxxxxx` subscriber form.
            keys += "0$national"
            keys += IRAN_CC + national
            keys += "+" + IRAN_CC + national
            keys += "00" + IRAN_CC + national
        }
        return keys
    }

    /**
     * The 10-digit national form (`9xxxxxxxxx`) when [digits] is unambiguously an
     * Iranian mobile number, else null.
     *
     * Deliberately strict — only these shapes:
     *   `98`  + `9` + 9 digits   (national, 12 digits)
     *   `0`   + `9` + 9 digits   (local, 11 digits)
     *   `00`  + `98` + `9` + 9   (international IDD prefix, 14 digits)
     *
     * A landline, a service code or any foreign number is left alone, and nothing
     * shorter is ever expanded — that is what keeps `112`, `110`, `100` and short
     * alphanumeric sender IDs from matching a real contact.
     */
    private fun iranianMobileNational(digits: String): String? = when {
        digits.length == 12 && digits.startsWith(IRAN_CC) &&
            digits[2] == '9' -> digits.substring(2)
        digits.length == 11 && digits[0] == '0' && digits[1] == '9' -> digits.substring(1)
        // `00` is the IDD prefix: 00 + 98 + 9xxxxxxxxx.
        digits.length == 14 && digits.startsWith("00$IRAN_CC") &&
            digits[4] == '9' -> digits.substring(4)
        else -> null
    }

    /**
     * True when the two inputs are the same number under this policy. Convenience
     * for callers that only need the boolean.
     */
    fun matches(a: String, b: String): Boolean {
        val left = lookupKeys(a)
        if (left.isEmpty()) return false
        return left.any { it in lookupKeys(b) }
    }
}
