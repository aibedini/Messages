package com.autonomousone.messages.repository

import com.autonomousone.messages.model.Sms

/**
 * Pure merge helpers for windowed thread loading.
 *
 * The conversation screen keeps a WINDOW of the thread (newest pages) plus a
 * set of optimistic (not-yet-persisted) sends with synthetic ids. Provider
 * refreshes used to replace that whole list — changing what the user saw on
 * every open/close. These helpers make updates MERGE-BASED instead:
 *
 *  - [mergeTail] folds freshly-queried rows into the visible list without
 *    ever removing history already on screen, deduping by id AND by
 *    (body, time-proximity) so provider-confirmed copies of optimistic rows
 *    collapse into one bubble;
 *  - [prependOlder] extends history upward, dropping overlap;
 *  - [tailWindow] caps the list to the newest n messages so long threads
 *    keep a bounded footprint.
 *
 * All functions are pure and unit-testable without Android.
 */
object ThreadMerge {

    /**
     * Two rows are the "same message" when their composite provider identity
     * (source, providerId) matches, or when the body matches within 5 s.
     *
     * The id comparison goes through [MessageIdentity] rather than raw id
     * equality so SMS 52 and MMS 52 (the latter carried as -52 at the UI
     * boundary) can never collapse into one bubble.
     */
    internal fun sameMessage(a: Sms, b: Sms): Boolean =
        MessageIdentity.keyOf(a) == MessageIdentity.keyOf(b) ||
                (a.type == b.type &&
                    a.message == b.message &&
                    kotlin.math.abs(a.date - b.date) < 5000L)

    /**
     * Merges [fresh] (any rows newer or equal to what we show, from the
     * provider) into [existing] and returns a date-sorted list containing
     * everything already visible plus genuinely-new rows. Existing rows are
     * never dropped — only enriched via confirmed replacements when they carry
     * a real id (optimistic → persisted).
     */
    fun mergeTail(existing: List<Sms>, fresh: List<Sms>): List<Sms> {
        if (fresh.isEmpty()) return existing.sortedWith(canonicalChronological)
        val out = existing.toMutableList()
        for (row in fresh.sortedWith(canonicalChronological)) {
            val idx = out.indexOfFirst { sameMessage(it, row) }
            when {
                idx < 0 -> out.add(row)
                // Always adopt the provider copy. Status callbacks update an
                // existing row without changing its id/date, which used to be
                // ignored here and left the bubble permanently on PENDING.
                else -> out[idx] = row.copy(unread = out[idx].unread)
            }
        }
        return out.sortedWith(canonicalChronological)
    }

    /**
     * Prepends an older page to [existing], dropping any rows the user
     * already has on screen (overlapping windows after a refresh).
     */
    fun prependOlder(existing: List<Sms>, olderPage: List<Sms>): List<Sms> {
        val known = existing.toHashSet() // Sms is a data class → value equality
        val novel = olderPage.filter { it !in known && existing.none { e -> sameMessage(e, it) } }
        return (novel + existing).sortedWith(canonicalChronological)
    }

    /**
     * Appends a NEWER page (from the forward crawl after "Go to first
     * message") to [current], replacing any row already on screen with the
     * fresher copy and keeping the canonical oldest→newest order. Symmetric
     * counterpart of [prependOlder].
     */
    fun appendNewer(current: List<Sms>, newer: List<Sms>): List<Sms> {
        if (newer.isEmpty()) return current
        val out = current.toMutableList()
        for (candidate in newer) {
            val idx = out.indexOfFirst { sameMessage(it, candidate) }
            if (idx >= 0) out[idx] = candidate else out.add(candidate)
        }
        return out.sortedWith(canonicalChronological)
    }

    /**
     * The ONE chronological order every window mutation must end in. Defined
     * by [ConversationWindow.canonical]: date, then source, then providerId —
     * the same tie-break Home uses to pick a thread's newest row.
     */
    val canonicalChronological: Comparator<Sms> = ConversationWindow.canonical

    /**
     * Caps [messages] to its newest [n] entries (ascending order preserved).
     */
    fun tailWindow(messages: List<Sms>, n: Int): List<Sms> =
        if (messages.size <= n) messages else messages.subList(messages.size - n, messages.size)
}
