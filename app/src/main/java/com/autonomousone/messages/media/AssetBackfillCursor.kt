package com.autonomousone.messages.media

import com.autonomousone.messages.data.MessageAssetBackfillRow

/**
 * The persisted resumable position of the Media / Links / Files history sweep.
 *
 * It is a KEYSET cursor on the canonical message order
 * (`date DESC, source DESC, providerId DESC`) — the same tuple Room's own
 * `MessageCutoff` and the classifier backfill use — so:
 *
 *  - batch N+1 costs exactly what batch 1 cost (no deep OFFSET re-walk);
 *  - a process death between batches resumes at the last COVERED row, and the
 *    sweep re-covers at most one batch (re-indexing is an idempotent UPSERT, so
 *    re-covering is harmless by construction);
 *  - the sweep terminates, because the cursor advances over `messages` even when
 *    a message legitimately produces ZERO assets (a plain SMS with no link).
 *
 * Pure and Android-free: the whole checkpoint/resume arithmetic is unit-testable.
 */
data class AssetBackfillCursor(
    val date: Long,
    val source: String,
    val providerId: Long
) {

    val isStart: Boolean get() = this == START

    /** Position after covering the OLDEST row of a DESC page. */
    fun advance(lastOfPage: AssetBackfillCursor): AssetBackfillCursor =
        if (!lastOfPage.isBefore(this)) this else lastOfPage

    fun advance(row: MessageAssetBackfillRow): AssetBackfillCursor =
        advance(AssetBackfillCursor(row.date, row.source, row.providerId))

    fun advance(date: Long, source: String, providerId: Long): AssetBackfillCursor =
        advance(AssetBackfillCursor(date, source, providerId))

    /** True when the canonical order would visit [other] strictly BEFORE this. */
    fun isBefore(other: AssetBackfillCursor): Boolean = when {
        date != other.date -> date < other.date
        source != other.source -> source < other.source
        else -> providerId < other.providerId
    }

    companion object {
        /**
         * Highest possible position: `Long.MAX_VALUE` date and `\uFFFF` source, so
         * the very first batch may start at any real row (SQLite compares TEXT
         * with BINARY collation, and `\uFFFF` is above every real source name
         * used by the app: "sms" and "mms").
         */
        const val START_SOURCE = "\uFFFF"

        val START = AssetBackfillCursor(Long.MAX_VALUE, START_SOURCE, Long.MAX_VALUE)

        /** Rows per batch — the brief's 200–500 band, and one Room round trip. */
        const val BATCH_SIZE = 300

        /** Batches per worker run, so one run is bounded and always yields. */
        const val MAX_BATCHES_PER_RUN = 10
    }
}
