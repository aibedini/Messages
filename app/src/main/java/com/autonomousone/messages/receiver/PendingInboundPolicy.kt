package com.autonomousone.messages.receiver

import com.autonomousone.messages.data.PendingInboundSmsEntity

/**
 * What to do with a held inbound message (mission §16/§52).
 *
 * Pure, because the one decision here is the difference between recovering a message and duplicating it.
 *
 * **Look before writing, every time.** `ContentResolver.insert` can COMMIT and still fail — throw, or
 * return a URI without a usable id — so a row marked PENDING may already be in the provider. Inserting
 * again would put the same message in the provider twice, which is the CASE C duplicate `SmsReceiver`
 * already documents. The retry therefore asks the provider whether the message is there and only inserts
 * when it is not.
 *
 * That check is what makes this safe where the receiver's own path could not be: the receiver has no
 * provider row to look for (it is creating one), while the retry is looking for a row it may already
 * have created.
 */
sealed interface PendingInboundAction {
    /** The message is already in the provider. Record that and stop. */
    data object AlreadyThere : PendingInboundAction

    /** Insert it, then record the outcome. */
    data object Insert : PendingInboundAction

    /**
     * Retries are exhausted, or nothing about this row can still succeed.
     *
     * The row is marked FAILED and KEPT: it is the durable record of a lost message, and deleting it
     * would restore exactly the silence this table exists to remove.
     */
    data class GiveUp(val reason: String) : PendingInboundAction
}

object PendingInboundPolicy {

    /**
     * Why no retry is attempted when the provider cannot be read.
     *
     * A shared constant rather than a literal in two places: the worker branches on this reason, and a
     * typo would silently turn "we do not know" into "this message is lost".
     */
    const val REASON_PROVIDER_UNREADABLE = "provider_unreadable"

    /**
     * Why retries stopped after the attempt cap.
     */
    const val REASON_ATTEMPTS_EXHAUSTED = "insert_attempts_exhausted"

    /**
     * Decide, using only facts a caller has already established.
     *
     * @param foundInProvider whether an exact provider row for this message exists. The caller must not
     *   guess: "we did not look" is not "it is not there", and the safe answer to not looking is to look.
     * @param providerReadable false when the provider could not be queried at all. A failed READ proves
     *   nothing about the row, so it must never be turned into an insert (the duplicate) or into a
     *   give-up (the loss).
     */
    fun decide(
        attempts: Int,
        foundInProvider: Boolean,
        providerReadable: Boolean = true,
    ): PendingInboundAction = when {
        !providerReadable -> PendingInboundAction.GiveUp(REASON_PROVIDER_UNREADABLE)
        foundInProvider -> PendingInboundAction.AlreadyThere
        attempts >= PendingInboundSmsEntity.MAX_ATTEMPTS ->
            PendingInboundAction.GiveUp(REASON_ATTEMPTS_EXHAUSTED)
        else -> PendingInboundAction.Insert
    }

    /**
     * Whether an insert outcome means the message is now in the provider.
     *
     * A rejected insert carries the reason, so the next attempt has something to report rather than a
     * bare count.
     */
    fun isStored(result: InboxWriteAttempt): Boolean = result is InboxWriteAttempt.Wrote

    /** What a finished insert attempt means for the held row. */
    sealed interface AttemptOutcome {
        /** The message is in the provider. */
        data object Done : AttemptOutcome

        /** Still held, with one more attempt recorded. */
        data class Retry(val attempts: Int, val error: String?) : AttemptOutcome

        /** Retries are exhausted. The row is KEPT as the record that this message was lost. */
        data class Failed(val attempts: Int, val error: String?) : AttemptOutcome
    }

    /**
     * The next state of a held row after one insert attempt.
     *
     * Pure and total, because the two ways to get this wrong are both silent: marking a row DONE without
     * storing it loses the message, and marking it FAILED after a single transient error gives up on a
     * message that one more try would have stored.
     *
     * @param attemptsSoFar the attempts recorded BEFORE this one.
     */
    fun afterAttempt(
        attemptsSoFar: Int,
        stored: Boolean,
        error: String?,
    ): AttemptOutcome {
        val attempts = attemptsSoFar + 1
        return when {
            stored -> AttemptOutcome.Done
            attempts >= PendingInboundSmsEntity.MAX_ATTEMPTS ->
                AttemptOutcome.Failed(attempts = attempts, error = error)
            else -> AttemptOutcome.Retry(attempts = attempts, error = error)
        }
    }

    /**
     * The diagnostic line for a row, without the body and without the number.
     *
     * A held message is the one case where a support report has to say "a message was lost" and be
     * believed, so the row id, the attempt count and the reason travel — and nothing that could leak.
     */
    fun describe(row: PendingInboundSmsEntity): String =
        "id=${row.id} attempts=${row.attempts} state=${row.state} " +
            "createdAt=${row.createdAt} reason=${row.lastError ?: "none"}"
}
