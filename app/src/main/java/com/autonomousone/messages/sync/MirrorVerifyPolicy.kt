package com.autonomousone.messages.sync

import com.autonomousone.messages.data.MirrorReconcileRow

/**
 * A resumable verification sweep of the mirror (mission §35).
 *
 * **What this adds that the recent window cannot.** `reconcileMissingEvents` looks at the last 48
 * hours, which is the right shape for "something just arrived and did not replicate". It can never
 * find a gap that is *older* than its window: a message whose event was lost three weeks ago is
 * inside no 48-hour window, and once the history scan has finished it never looks at those rows
 * again either. So the app's answer to "is the whole mirror replicated?" rested on two passes that
 * both decline to ask the question.
 *
 * This is the pass that asks it, once, over the entire mirror, resumably and in bounded pages.
 *
 * Pure, because the properties that matter are properties of the WALK rather than of any page:
 * every row is examined exactly once, no row is skipped, and the cursor strictly advances so the
 * sweep terminates instead of looping on a boundary. The classic failure of a keyset walk is
 * skipping the rows that share the boundary date with the last row of the previous page, and it is
 * invisible in any single-page test.
 */
data class MirrorVerifyCursor(
    /**
     * The next page is strictly OLDER than this pair.
     *
     * `Long.MAX_VALUE`/`Long.MAX_VALUE` is the "start at the newest row" sentinel, matching the
     * `cursorDate` convention the history checkpoint already uses.
     */
    val date: Long = Long.MAX_VALUE,
    val providerId: Long = Long.MAX_VALUE,
) {
    companion object {
        val START = MirrorVerifyCursor()
    }
}

/**
 * What one page of the sweep found.
 *
 * Every examined row is accounted for, the same discipline mission §70 requires of a history scan:
 * `examined = alreadyReplicated + recovered + skippedNoDirection + skippedNoProviderId`.
 * `alreadyReplicated` is the overwhelmingly common case and is named rather than implied — a page
 * where most rows have events is the healthy outcome, and an arithmetic that omitted them would show
 * a residual on every good page and teach a reader to ignore it.
 *
 * `skippedNoProviderId` exists because a row with no provider id is never looked up at all
 * (`MirrorProviderPolicy.candidates` excludes it — `eventUuidFor` is meaningless without a real
 * provider row). Folding those into `alreadyReplicated` would be a claim about rows nobody checked,
 * which is the kind of number this whole effort exists to stop printing.
 */
data class MirrorVerifyPage(
    val examined: Int,
    val alreadyReplicated: Int,
    val recovered: Int,
    val skippedNoDirection: Int,
    val skippedNoProviderId: Int,
    /** The cursor to persist for the next page. */
    val next: MirrorVerifyCursor,
    /** True when this page reached the end of the source. */
    val complete: Boolean,
) {
    val balances: Boolean
        get() = examined == alreadyReplicated + recovered + skippedNoDirection + skippedNoProviderId
}

/** The durable progress of one source's sweep. */
data class MirrorVerifyProgress(
    val source: String,
    val cursor: MirrorVerifyCursor,
    val startedAt: Long,
    val updatedAt: Long,
    /** 0 while the sweep is still running. */
    val completedAt: Long,
    val examined: Long,
    val alreadyReplicated: Long,
    val recovered: Long,
    val skippedNoDirection: Long,
    val skippedNoProviderId: Long,
) {
    val complete: Boolean get() = completedAt > 0

    val accounted: Long
        get() = alreadyReplicated + recovered + skippedNoDirection + skippedNoProviderId

    /** `examined` must equal the parts. A residual means the tally is wrong, not that a row is lost. */
    val balances: Boolean get() = examined == accounted

    val residual: Long get() = examined - accounted
}

object MirrorVerifyPolicy {

    /**
     * Rows per page.
     *
     * Deliberately the same order of magnitude as the history batch: this sweep reads the same index
     * the history producer walks, and a page large enough to be efficient is also large enough to
     * hold a transaction open on a 360k-row table for an uncomfortable time.
     */
    const val PAGE_LIMIT = 500

    /**
     * The cursor for the next page.
     *
     * The page is ordered `date DESC, providerId DESC`, so the LAST row is the oldest one examined.
     * Using it — rather than the first row — is what makes the walk advance at all; using `date`
     * alone would re-examine every row that shares that date, and using `date - 1` would skip them.
     *
     * @return null when the page is empty, which means there is nothing older to walk to and the
     *   caller should mark the sweep complete instead of persisting a cursor.
     */
    fun nextCursor(page: List<MirrorReconcileRow>): MirrorVerifyCursor? {
        val last = page.lastOrNull() ?: return null
        return MirrorVerifyCursor(date = last.date, providerId = last.providerId)
    }

    /**
     * Whether a page ended the sweep.
     *
     * A short page means the query ran out of rows, not that the caller should ask again: asking
     * again would be correct too, but treating short-as-final bounds the pass to one query per sweep
     * when the source is smaller than a page.
     */
    fun isComplete(pageSize: Int, limit: Int = PAGE_LIMIT): Boolean = pageSize < limit

    /**
     * Tally one page.
     *
     * @param examined the page size — every row the query returned was looked at.
     * @param eligible how many of them could be looked up at all (`MirrorReconcilePolicy.candidates`).
     * @param missing how many eligible rows had no durable event.
     * @param skippedNoDirection how many of [missing] were skipped for having no cloud direction.
     */
    fun page(
        examined: Int,
        eligible: Int,
        missing: Int,
        skippedNoDirection: Int,
        next: MirrorVerifyCursor?,
        limit: Int = PAGE_LIMIT,
    ): MirrorVerifyPage {
        val complete = isComplete(examined, limit)
        val recovered = (missing - skippedNoDirection).coerceAtLeast(0)
        return MirrorVerifyPage(
            examined = examined,
            // Of the rows that COULD be checked, the ones that already had an event.
            alreadyReplicated = (eligible - missing).coerceAtLeast(0),
            recovered = recovered,
            skippedNoDirection = skippedNoDirection,
            // Rows with no provider id were never looked up, so they are named as such rather than
            // counted as replicated. Absence of evidence is not evidence of replication.
            skippedNoProviderId = (examined - eligible).coerceAtLeast(0),
            // A complete sweep has no next cursor: keeping the last one would make a resumed sweep
            // re-walk the oldest page forever.
            next = if (complete) MirrorVerifyCursor.START else (next ?: MirrorVerifyCursor.START),
            complete = complete
        )
    }

    /** Fold a page into the durable progress. Increments, so a multi-page sweep accumulates. */
    fun apply(
        progress: MirrorVerifyProgress,
        page: MirrorVerifyPage,
        now: Long,
    ): MirrorVerifyProgress = progress.copy(
        cursor = page.next,
        updatedAt = now,
        completedAt = if (page.complete) now else 0,
        examined = progress.examined + page.examined,
        alreadyReplicated = progress.alreadyReplicated + page.alreadyReplicated,
        recovered = progress.recovered + page.recovered,
        skippedNoDirection = progress.skippedNoDirection + page.skippedNoDirection,
        skippedNoProviderId = progress.skippedNoProviderId + page.skippedNoProviderId,
    )

    /** A fresh progress row for a source that has never been swept. */
    fun start(source: String, now: Long) = MirrorVerifyProgress(
        source = source,
        cursor = MirrorVerifyCursor.START,
        startedAt = now,
        updatedAt = now,
        completedAt = 0,
        examined = 0,
        alreadyReplicated = 0,
        recovered = 0,
        skippedNoDirection = 0,
        skippedNoProviderId = 0,
    )

    /**
     * The sentence a human needs, or null when there is nothing worth saying.
     *
     * Precedence, and why it is this order: a tally that does not balance is reported FIRST, because
     * `recovered` is derived from that same tally — announcing "N messages were lost" from books that
     * do not add up would be a fabricated conclusion. Then a real recovery, which is the finding this
     * sweep exists to produce. Then incompleteness, which is the honest limit. A finished sweep that
     * recovered nothing says nothing at all: that is the good outcome, and a diagnostic that always
     * speaks is one nobody reads.
     */
    fun alarm(progress: MirrorVerifyProgress): String? = when {
        !progress.balances ->
            "the sweep accounts for ${progress.accounted} of ${progress.examined} message(s) it read " +
                "(residual ${progress.residual}), so its own tally cannot be trusted and neither can " +
                "the recovery count it would report."
        progress.recovered > 0 ->
            "${progress.recovered} message(s) had NO durable event and were re-enqueued. The history " +
                "scan had already finished, so nothing else was going to find them."
        progress.complete -> null
        else ->
            "the full-mirror verification has not finished (${progress.examined} message(s) walked " +
                "so far), so 'no messages lost' is not yet a verified claim for ${progress.source}."
    }
}
