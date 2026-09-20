package com.autonomousone.messages.repository

import java.text.Normalizer
import java.util.Locale

/**
 * The identity a USER CATEGORY membership is attached to.
 *
 * WHY THIS IS NOT JUST A threadId
 * -------------------------------
 * The user's requirement is "if I put this number in VPN, that number stays in VPN".
 * A Telephony `threadId` does not satisfy that: the provider can delete and recreate a
 * thread for the same correspondence, and the group/one-to-one shape can differ
 * between two rows of the same conversation. So a one-to-one conversation is keyed by
 * the **stable phone identity** of its counterpart, and only a conversation that has
 * no single safe address (a group / multi-recipient thread) falls back to the thread.
 *
 * There is deliberately NO suffix or "last N digits" matching anywhere in this file:
 * a short code (`112`, `110`) must never alias a contact, and two unrelated numbers
 * must never share a category.
 */
sealed interface UserCategoryScope {

    /** [key] is [PhoneIdentity.stableKey] output, or a `sender:` id for alphanumeric senders. */
    data class Address(val key: String) : UserCategoryScope

    /** Group / multi-recipient conversations, which have no single identifying address. */
    data class Thread(val threadId: Long) : UserCategoryScope {
        init {
            require(threadId > 0L) { "a thread scope requires a real threadId" }
        }
    }
}

/**
 * Resolves ONE conversation to the scope its category memberships are stored under.
 *
 * EVERY assignment path goes through this — Conversation Info, home multi-select,
 * the category filter, and the unread-badge projection — so a conversation can never
 * be assignable under one identity and filterable under another.
 *
 * The decisions, and why:
 *
 *  - an address carrying MULTIPLE recipients (`,` / `;`, which is how this app already
 *    represents a non-dialable group address) → [UserCategoryScope.Thread]. A group
 *    must not be identified by one arbitrary participant: two groups that happen to
 *    share a member would otherwise merge into one category membership.
 *  - a blank or unusable address, or a non-positive thread id → null (the caller has
 *    nothing stable to key on, so it must not write a membership at all rather than
 *    write one under a guessed key).
 *  - a numeric address → [UserCategoryScope.Address] with
 *    [PhoneIdentity.stableKey], so `09121234567` and `+989121234567` are ONE scope.
 *  - an ALPHANUMERIC sender id (`BANK`, `Digikala`, `IR-MCI`) → still an Address, keyed
 *    by [senderKey]. These are real SMS senders that can be categorised; they simply
 *    have no phone identity. Matching stays exact (NFKC + case-fold), never fuzzy.
 */
object ConversationCategoryScopeResolver {

    /** Prefix that keeps a sender-id key from ever colliding with a phone key. */
    private const val SENDER_PREFIX = "sender:"

    /** Characters that may appear around the digits of a phone-shaped value. */
    private const val PHONE_FORMATTING = "+-().\u00A0"

    fun resolve(
        threadId: Long,
        rawAddress: String?,
        normalizedAddress: String? = null
    ): UserCategoryScope? {
        val candidates = listOfNotNull(rawAddress, normalizedAddress)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        // Multi-recipient (group) addresses first, and from EITHER representation: a
        // group signal is about the conversation shape, so it must not depend on which
        // column happened to carry it.
        if (candidates.any { isMultiRecipient(it) }) {
            return if (threadId > 0L) UserCategoryScope.Thread(threadId) else null
        }

        // PRECEDENCE IS EXPLICIT HERE, because once memberships are persisted this
        // becomes durable contract:
        //
        //   1. the RAW representation wins whenever it yields any usable identity. It is
        //      what the provider actually stored, so a sender id like `IR-MCI1` must not
        //      be displaced by a misleading numeric normalization (`1`).
        //   2. only when RAW yields NOTHING usable (e.g. punctuation, `...`) may the
        //      NORMALIZED representation be used. Dropping that fallback would push a
        //      conversation with a perfectly good normalized number onto a THREAD scope,
        //      which is exactly the thread-keyed membership this design exists to avoid.
        val ordered = candidates.distinct()
        val primary = ordered.firstOrNull()
        primary?.let { identityOf(it) }?.let { return it }
        ordered.drop(1).forEach { fallback ->
            identityOf(fallback)?.let { return it }
        }

        // No stable identity at all: only a thread can carry the membership.
        return if (threadId > 0L) UserCategoryScope.Thread(threadId) else null
    }

    /**
     * The identity ONE address representation can produce, or null when it produces none.
     *
     * PHONE FIRST, but only for a value that is actually phone-shaped: at least one digit
     * and nothing but digits/punctuation once formatted. This ordering matters for `112`
     * (a short code with a real phone identity) versus `IR-MCI1` (an alphanumeric sender
     * that merely contains a digit, and must NOT be reduced to the number `1`).
     */
    private fun identityOf(address: String): UserCategoryScope.Address? {
        if (isPhoneShaped(address)) {
            PhoneIdentity.stableKey(address)?.let { return UserCategoryScope.Address(it) }
        }
        senderKey(address)?.let { return UserCategoryScope.Address(it) }
        return null
    }

    /**
     * True when [address] is a phone-shaped value: it has at least one digit and every
     * remaining character is formatting (`+ - ( ) .`, or any whitespace). Letters make
     * it a sender id instead, so a value like `IR-MCI1` is never read as the phone
     * number `1`.
     *
     * Whitespace is matched with `Char.isWhitespace()` rather than an explicit list, so
     * a pasted non-breaking space or an ideographic space cannot silently turn a real
     * number into a "sender id".
     */
    fun isPhoneShaped(address: String): Boolean {
        val trimmed = address.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.none { it.isDigit() }) return false
        return trimmed.all { it.isDigit() || it.isWhitespace() || it in PHONE_FORMATTING }
    }

    /**
     * The canonical form of an alphanumeric sender id, or null when unusable.
     *
     * NFKC + whitespace collapse + `Locale.ROOT` case folding means `Bank`, `BANK` and
     * `Bank ` are one identity, while two unrelated senders are never merged: the
     * comparison is EQUALITY on this form, never a prefix/suffix match.
     *
     * A phone-shaped value (see [isPhoneShaped]) is rejected here so the caller keys it
     * by [PhoneIdentity] instead of inventing a second identity for it. A value with no
     * letters or digits at all (punctuation only) is unusable and also rejected, which
     * is what lets such an address fall back to its thread.
     */
    fun senderKey(rawAddress: String): String? {
        val normalized = normalizeSenderId(rawAddress) ?: return null
        return SENDER_PREFIX + normalized
    }

    /** True when [address] carries more than one recipient. */
    fun isMultiRecipient(address: String): Boolean =
        address.contains(',') || address.contains(';')

    /** The canonical form of an alphanumeric sender id, or null when unusable. */
    fun normalizeSenderId(rawAddress: String): String? {
        if (isPhoneShaped(rawAddress)) return null
        val collapsed = rawAddress
            .trim()
            .replace(Regex("\\s+"), " ")
        if (collapsed.none { it.isLetterOrDigit() }) return null
        val nfkc = Normalizer.normalize(collapsed, Normalizer.Form.NFKC)
        return nfkc.lowercase(Locale.ROOT).ifEmpty { null }
    }
}
