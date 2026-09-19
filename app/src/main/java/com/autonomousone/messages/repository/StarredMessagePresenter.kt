package com.autonomousone.messages.repository

import com.autonomousone.messages.data.StarredMessageRow
import com.autonomousone.messages.utils.formatFullTimestamp
import java.util.Locale

/**
 * A starred message as the Starred screens render it (v3.4.0 FEATURE 7).
 *
 * Identity is the COMPOSITE [key] (`source`, `providerId`), never the row's
 * thread: tapping a starred row must be able to reach the EXACT message, and SMS
 * `_id` 100 and MMS `_id` 100 are different messages.
 */
data class StarredMessageItem(
    val key: MessageIdentity.Key,
    val threadId: Long,
    val body: String,
    val timestamp: Long,
    val formattedDate: String,
    /** Best available display name (contact, else the raw address). */
    val displayName: String,
    val starredAt: Long
) {
    /** Stable list key: identity is composite, so this can never collide. */
    val listKey: String get() = "${key.source}:${key.providerId}"
}

/** Resolves a display name for an address without hitting the network. */
fun interface ContactNameResolver {
    fun displayName(rawAddress: String, normalizedAddress: String): String
}

/**
 * Turns Room's [StarredMessageRow] projection into a render row.
 *
 * Kept out of the composables so the snippet/date/name rules are unit-testable
 * on the JVM. The date formatter is [formatFullTimestamp], the SAME formatter the
 * conversation bubbles and the scheduled screen use, so a starred row's date can
 * never disagree with the message it came from.
 */
object StarredMessagePresenter {

    /** Longest snippet rendered before an ellipsis is appended. */
    const val SNIPPET_MAX = 120

    /** Shown when a starred row carries no text at all (e.g. a bare MMS). */
    const val NO_TEXT = "—"

    fun presentation(
        row: StarredMessageRow,
        contacts: ContactNameResolver
    ): StarredMessageItem = StarredMessageItem(
        key = MessageIdentity.Key(row.source, row.providerId),
        threadId = row.threadId,
        body = snippet(row.body),
        timestamp = row.date,
        formattedDate = formatFullTimestamp(row.date),
        displayName = displayName(row, contacts),
        starredAt = row.starredAt
    )

    fun presentationAll(
        rows: List<StarredMessageRow>,
        contacts: ContactNameResolver
    ): List<StarredMessageItem> = rows.map { presentation(it, contacts) }

    /**
     * Collapses whitespace (a multiline MMS caption must not blow up the row
     * height) and clips to [SNIPPET_MAX] characters. An empty/blank body renders
     * [NO_TEXT] rather than an invisible row.
     */
    fun snippet(body: String, max: Int = SNIPPET_MAX): String {
        val collapsed = body.replace(Regex("\\s+"), " ").trim()
        if (collapsed.isEmpty()) return NO_TEXT
        if (max <= 0) return collapsed
        if (collapsed.length <= max) return collapsed
        // Trim the cut so the ellipsis never floats after a space.
        return collapsed.take(max).trimEnd() + "…"
    }

    /**
     * The row's name. A resolver may legitimately answer with a blank string
     * (contact permission missing, unknown number), so falling back to the raw
     * address is done HERE instead of trusting every caller.
     */
    fun displayName(row: StarredMessageRow, contacts: ContactNameResolver): String {
        val resolved = contacts.displayName(row.rawAddress, row.normalizedAddress)
        if (resolved.isNotBlank()) return resolved
        return row.rawAddress.ifBlank { row.normalizedAddress }
    }

    /** Group-header label for a day bucket (locale-independent key). */
    fun dayKey(timestamp: Long): String {
        if (timestamp <= 0L) return ""
        return String.format(Locale.US, "%tF", java.util.Date(timestamp))
    }
}
