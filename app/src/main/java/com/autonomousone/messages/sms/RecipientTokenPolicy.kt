package com.autonomousone.messages.sms

import com.autonomousone.messages.utils.PhoneToken

/**
 * v3.4.0 FEATURE 11 — the recipient cross-check between a durable job and the
 * undo-send ledger.
 *
 * A delayed send is dispatched by a WorkManager job that was written minutes (or
 * a reboot) ago, while the user's pending bubble was rendered from a database
 * row. Those are two durable copies of one intent, so they must be checked
 * against each other before anything is submitted to the radio: if they ever name
 * different recipients, sending "the job's copy" would deliver a message to a
 * number the user was never shown, and no Undo could reach it.
 *
 * Pure and JVM-only by design — this is a security decision, so it is tested
 * directly rather than through a worker.
 */
object RecipientTokenPolicy {

    /** Outcome of comparing a job's recipient with the ledger row's token. */
    enum class Verdict {
        /** Same recipient: the send may proceed (subject to the claim). */
        MATCH,

        /**
         * Different recipient: never send. The intent is failed so the state is
         * terminal and visible instead of silently disappearing.
         */
        MISMATCH,

        /**
         * The row carries no token (a row written by an older build). The job is
         * the only evidence available, so it is accepted — refusing here would
         * strand a legitimate message, and the token is a diagnostic aid, not
         * the message's identity.
         */
        UNKNOWN_ROW_TOKEN
    }

    const val CODE_RECIPIENT_MISMATCH = "RECIPIENT_MISMATCH"

    fun verify(phone: String, rowToken: String): Verdict = when {
        rowToken.isBlank() -> Verdict.UNKNOWN_ROW_TOKEN
        PhoneToken.of(phone) == rowToken -> Verdict.MATCH
        else -> Verdict.MISMATCH
    }

    /** True only for the verdicts that permit a send. */
    fun allowsSend(verdict: Verdict): Boolean = verdict != Verdict.MISMATCH
}
