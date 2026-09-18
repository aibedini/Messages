package com.autonomousone.messages.diagnostics

import android.os.Trace

/**
 * Phase 17.12: the single vocabulary of trace section names used across the
 * SMS pipeline. Callers in other packages should use these constants rather
 * than string literals so a systrace/Perfetto query stays stable.
 *
 * Usage from hot paths (exception-safe and cheap when tracing is off):
 *
 *  ```
 *  TraceSections.begin(TraceSections.ROOM_INGEST)
 *  try { ... } finally { TraceSections.end() }
 *  ```
 *
 *  or, preferably, the bracket helper:
 *
 *  ```
 *  TraceSections.trace(TraceSections.TAIL_DELTA) { ... }
 *  ```
 *
 * Notes:
 *  - android.os.Trace.beginSection/endSection are already no-ops when tracing
 *    is not enabled; [enabled] adds an explicit switch on top so a caller can
 *    turn the instrumentation off entirely (tests, low-end devices);
 *  - sections must be opened and closed on the SAME thread, hence `end()` takes
 *    no section name;
 *  - section names are limited to 127 characters by the platform; every
 *    constant here is far shorter.
 */
object TraceSections {

    /** Incoming SMS/MMS dispatch from the provider observer into the pipeline. */
    const val SMS_INCOMING_DISPATCH = "SMS_INCOMING_DISPATCH"

    /** One exact provider read (cursor query + parse for a thread/window). */
    const val PROVIDER_EXACT_READ = "PROVIDER_EXACT_READ"

    /** Room ingest of provider rows (insert/update transaction). */
    const val ROOM_INGEST = "ROOM_INGEST"

    /** Room projection rebuild for the conversation list. */
    const val ROOM_PROJECTION = "ROOM_PROJECTION"

    /** A Home screen Room emission being observed and mapped. */
    const val HOME_ROOM_EMIT = "HOME_ROOM_EMIT"

    /** Opening a conversation: pager/cache warm-up until first bubbles. */
    const val CONVERSATION_OPEN = "CONVERSATION_OPEN"

    /** Mark-thread-read request through to unread=0 UI state. */
    const val MARK_THREAD_READ = "MARK_THREAD_READ"

    /** TailDelta: provider delta for the newest window of a conversation. */
    const val TAIL_DELTA = "TAIL_DELTA"

    /** ForThread: targeted provider read for one thread. */
    const val FOR_THREAD = "FOR_THREAD"

    /** One exact-repair execution for a divergent message. */
    const val EXACT_REPAIR = "EXACT_REPAIR"

    /** One integrity sweep batch. */
    const val INTEGRITY_BATCH = "INTEGRITY_BATCH"

    /** One history backfill batch. */
    const val HISTORY_BACKFILL_BATCH = "HISTORY_BACKFILL_BATCH"

    /**
     * Explicit kill switch. Defaults to on; the platform still ignores
     * sections unless a trace is being captured.
     */
    @Volatile
    var enabled: Boolean = true

    /** Opens a trace section. Swallows any platform failure. */
    fun begin(section: String) {
        if (!enabled) return
        try {
            Trace.beginSection(section)
        } catch (_: Throwable) {
            // Tracing must never break a hot path.
        }
    }

    /** Closes the current thread's innermost trace section. */
    fun end() {
        if (!enabled) return
        try {
            Trace.endSection()
        } catch (_: Throwable) {
            // Tracing must never break a hot path.
        }
    }

    /**
     * Runs [block] inside [section], closing the section even when [block]
     * throws, and rethrowing the original failure.
     */
    inline fun <T> trace(section: String, block: () -> T): T {
        begin(section)
        try {
            return block()
        } finally {
            end()
        }
    }
}
