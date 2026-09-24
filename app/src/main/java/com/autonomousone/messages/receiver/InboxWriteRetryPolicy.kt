package com.autonomousone.messages.receiver

/**
 * Persisting an inbound SMS to the provider, and what to do when it fails (mission §16/§52).
 *
 * **The defect this exists for.** The default-SMS-app path writes the message to the provider itself
 * and then reads it back. Both outcomes below ended in the same place:
 *
 * ```kotlin
 * val inserted = insertIntoInbox(...)      // returns (-1, null) on ANY failure
 * if (sms == null) { log("decision=defer-to-provider-observer"); return }
 * ```
 *
 * A **write that failed** and a **write that succeeded but is not visible yet** are not the same fact.
 * In the first case the message exists nowhere — the app is the default SMS app, so no other app
 * inserted it — and the ContentObserver that the log line defers to will never find it. The line reads
 * as routine, and an inbound message is gone with no record that it ever arrived.
 *
 * Two things follow, and both are here rather than in the receiver so they can be tested:
 *
 *  - a transient provider failure is worth retrying inside the broadcast window the receiver already
 *    holds, because a locked or momentarily unavailable provider is the likely cause;
 *  - a persistent failure must be reported as a FAILURE, distinctly, so it can never again be read as
 *    a visibility delay.
 */

/** What one write attempt did. */
sealed interface InboxWriteAttempt {
    /** The provider accepted the row and returned its id. */
    data class Wrote(val rowId: Long) : InboxWriteAttempt

    /** The insert threw. [reason] is the exception's message, for the diagnostic only. */
    data class Threw(val reason: String?) : InboxWriteAttempt
}

/** The end of the retry loop. */
sealed interface InboxPersistOutcome {
    data class Persisted(val rowId: Long, val attempts: Int) : InboxPersistOutcome

    /** Every attempt failed. The message is NOT in the provider and never will be by this path. */
    data class Failed(val attempts: Int, val reason: String?) : InboxPersistOutcome
}

object InboxWriteRetryPolicy {

    /**
     * Attempts, including the first.
     *
     * Three, because the receiver runs inside a broadcast (`goAsync`) whose budget is seconds, and the
     * delays below have to fit inside it alongside the read-back and the fan-out. A failure that
     * survives 200 ms of retries is almost certainly not transient.
     */
    const val MAX_ATTEMPTS = 3

    /**
     * The wait before each retry, indexed by the attempt that just failed.
     *
     * Doubling, and short: this runs on a broadcast thread that the system is timing.
     */
    val BACKOFF_MS = listOf(50L, 150L)

    /** The delay before attempt number [attemptsDone] + 1. Never called after the last attempt. */
    fun backoffBefore(attemptsDone: Int): Long =
        BACKOFF_MS[(attemptsDone - 1).coerceIn(0, BACKOFF_MS.lastIndex)]

    /** Whether another attempt is allowed after [attemptsDone] failures. */
    fun shouldRetry(attemptsDone: Int): Boolean = attemptsDone < MAX_ATTEMPTS

    /**
     * Runs [write] until it succeeds or the attempts run out.
     *
     * Pure over the write lambda and the sleep lambda, so the retry behaviour — how many attempts, how
     * long between them, and that the FAILURE reason survives to the end — is asserted directly rather
     * than inferred from a device.
     *
     * [write] takes no attempt number: a retry of an insert is byte-for-byte the same insert, and a
     * caller that varied it by attempt could only produce a different message.
     */
    fun persist(
        write: () -> InboxWriteAttempt,
        sleep: (Long) -> Unit = { Thread.sleep(it) },
        maxAttempts: Int = MAX_ATTEMPTS,
    ): InboxPersistOutcome {
        var attempts = 0
        var lastReason: String? = null
        while (attempts < maxAttempts) {
            attempts++
            when (val result = write()) {
                is InboxWriteAttempt.Wrote -> return InboxPersistOutcome.Persisted(result.rowId, attempts)
                is InboxWriteAttempt.Threw -> lastReason = result.reason
            }
            if (attempts < maxAttempts) sleep(backoffBefore(attempts))
        }
        return InboxPersistOutcome.Failed(attempts = attempts, reason = lastReason)
    }
}
