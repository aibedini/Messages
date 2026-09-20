package com.autonomousone.messages.ui.home

import com.autonomousone.messages.data.MessageCategory

/**
 * v3.5.0 — the identity of ONE chip in Home's category row.
 *
 * Home has TWO independent category axes and they must never be confused:
 *
 *  - [System] is the automatic Smart Category (`PERSONAL`, `OTP`, `TRANSACTION`,
 *    `PROMOTION`, `SPAM`), resolved by the classifier plus the user's single-valued
 *    `categoryOverride`. It is exactly today's chip row and it is NOT touched by this
 *    feature.
 *  - [Custom] is one of the user's OWN categories, identified by its UUID. A
 *    conversation can be in several of them at once, and the membership hangs on the
 *    durable conversation scope (the phone identity for a one-to-one chat), not on the
 *    thread.
 *
 * A conversation can therefore be `TRANSACTION` and simultaneously belong to `VPN`,
 * `مشتری‌ها` and `مهم`. That is why the row is a list of keys and not an enum.
 */
sealed interface HomeCategoryKey {

    data class System(val category: MessageCategory) : HomeCategoryKey

    data class Custom(val categoryId: String) : HomeCategoryKey
}

/** The Smart Category a [HomeCategoryKey.System] key renders as, or null if it has no chip. */
fun HomeCategoryKey.System.asCategoryFilter(): CategoryFilter? =
    CategoryFilter.displayOrder.firstOrNull { it.category == category }

/**
 * One resolved chip.
 *
 * @param label the chip's own text for a USER category (its stored name), and null for a
 *   Smart Category chip, whose label is a localized string resource reached through
 *   [HomeCategoryKey.System.asCategoryFilter]. Keeping the name on the chip means the row
 *   is self-contained: the UI never has to join the chip list back to the category table.
 * @param conversations how many conversations the chip matches. For a [HomeCategoryKey.System]
 *   chip this is the DB-WIDE membership, exactly what the existing chip row already shows — and
 *   it must stay DB-wide, because it is also what keeps the SPAM chip reachable while a reported
 *   conversation is hidden from the inbox. For a [HomeCategoryKey.Custom] chip it is the
 *   membership found in the CURRENT context.
 * @param unreadConversations how many of those are UNREAD CONVERSATIONS — never messages. Seven
 *   unread SMS from one person is ONE unread conversation.
 */
data class HomeCategoryChip(
    val key: HomeCategoryKey,
    val label: String? = null,
    val conversations: Int,
    val unreadConversations: Int
) {
    /** The badge text, or null when no badge must be drawn at all. */
    val badge: String? get() = HomeCategoryBadge.format(unreadConversations)

    /** True when a badge is drawn. */
    val hasBadge: Boolean get() = badge != null
}

/**
 * The badge rules (v3.5.0).
 *
 * THREE rules, and each one is a user-visible promise:
 *
 *  1. A badge counts CONVERSATIONS, never messages. The number is "how many chats are
 *     waiting for me", which is what the user acts on; a count of raw SMS makes a single
 *     chatty thread look like a backlog.
 *  2. ZERO draws NOTHING. A "0" bubble is noise, and it makes the row wider for no
 *     information.
 *  3. Over [CAP] it saturates at `99+`. Beyond that the exact number stops changing any
 *     decision the user makes, but it does keep changing the chip's width.
 *
 * Formatting lives here rather than in the composable so the three rules are pinned by a
 * JVM test instead of by a screenshot.
 */
object HomeCategoryBadge {

    /** Values above this render as `99+`. */
    const val CAP = 99

    fun format(unreadConversations: Int): String? = when {
        unreadConversations <= 0 -> null
        unreadConversations > CAP -> "$CAP+"
        else -> unreadConversations.toString()
    }
}
