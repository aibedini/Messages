package com.autonomousone.messages.sync

/**
 * What kind of sender identity this is, and what may be done with it.
 *
 * `MessageEntity` carries BOTH `rawAddress` (the provider's exact string) and `normalizedAddress` (a
 * phone-matching helper). They are not interchangeable, and using the helper as the universal identity
 * is the bug this file exists to prevent: `PARSIANBANK` normalises to an empty string, so cloud events
 * were published with `address = ""` and GMweb could only render "Unknown number / Unknown
 * conversation" for every branded sender, short code and alphanumeric id.
 *
 * The rule:
 *
 * ```text
 * rawAddress        = presentation / sender identity truth  → what leaves the device
 * normalizedAddress = phone matching helper ONLY           → never a display identity
 * ```
 */
enum class SenderKind {
    /** A real subscriber number: contact lookup is meaningful, replies are meaningful. */
    PHONE,

    /** A short/long code (e.g. `3000`, `10005`): digits, but not a subscriber number. */
    SHORT_CODE,

    /** A branded/alphanumeric sender id (e.g. `PARSIANBANK`, `Ssh3-652`, `Google`). */
    ALPHANUMERIC,

    /** The provider genuinely gave nothing usable. A branded sender is NOT this. */
    UNKNOWN
}

object SenderIdentity {

    /** Digits below this are not a subscriber number (the project's existing matching threshold). */
    private const val MIN_PHONE_DIGITS = 7

    /**
     * Classify a sender. Classification is a decision, not persisted state: it is derived from the two
     * addresses every time, so it cannot go stale when a row is repaired.
     */
    fun classify(rawAddress: String?, normalizedAddress: String? = null): SenderKind {
        val raw = rawAddress?.trim().orEmpty()
        val normalized = normalizedAddress?.trim().orEmpty()
        // The display identity is the raw provider value; the helper is only a fallback when the
        // provider row itself carried nothing.
        val display = raw.ifBlank { normalized }
        if (display.isBlank()) return SenderKind.UNKNOWN
        // Any letter means this is a branded/alphanumeric sender, whatever else it contains.
        if (display.any { it.isLetter() }) return SenderKind.ALPHANUMERIC
        val digits = display.count { it.isDigit() }
        return when {
            digits >= MIN_PHONE_DIGITS -> SenderKind.PHONE
            digits > 0 -> SenderKind.SHORT_CODE
            // Punctuation/symbols only: nothing a human or GMweb can display as a sender.
            else -> SenderKind.UNKNOWN
        }
    }

    /**
     * The address that belongs in the encrypted event payload: the RAW provider value, byte for byte.
     *
     * No lowercasing, no letter removal, no `-` stripping, no digit conversion — `PARSIANBANK`,
     * `ResalatBank`, `Google`, `Ssh3-652`, `3000`, `09121234567`, `+989121234567` and Persian-digit
     * senders all survive exactly as the provider wrote them. `normalizedAddress` is used only when the
     * raw value is genuinely absent.
     */
    fun eventAddress(rawAddress: String?, normalizedAddress: String? = null): String {
        val raw = rawAddress?.trim().orEmpty()
        if (raw.isNotBlank()) return raw
        return normalizedAddress?.trim().orEmpty()
    }

    /** Whether a contact lookup is worth attempting — and safe. */
    fun allowsContactLookup(kind: SenderKind): Boolean = kind == SenderKind.PHONE

    /**
     * The key to use for a contact lookup, or null when no lookup should happen.
     *
     * Contacts are matched for real phone numbers only: looking up `PARSIANBANK` or `Google` in the
     * address book is meaningless, and the normalized helper is empty for them anyway — which is how an
     * "unknown" name got attached to senders that were perfectly well known.
     */
    fun contactLookupKey(rawAddress: String?, normalizedAddress: String? = null): String? {
        val kind = classify(rawAddress, normalizedAddress)
        if (!allowsContactLookup(kind)) return null
        val normalized = normalizedAddress?.trim().orEmpty()
        return normalized.ifBlank { rawAddress?.trim().orEmpty() }.takeIf { it.isNotBlank() }
    }

    /** True when a reply to this sender makes sense (short codes and brands usually reject them). */
    fun isReplyable(kind: SenderKind): Boolean = kind == SenderKind.PHONE
}
