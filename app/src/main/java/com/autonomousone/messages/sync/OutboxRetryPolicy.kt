package com.autonomousone.messages.sync

import com.autonomousone.messages.gateway.health.GatewayFailureKind

/**
 * What to do with a batch after the server refused it (mission §17).
 *
 * WHY THIS IS A SEPARATE, PURE OBJECT: this decision used to be a bare condition inside
 * `EventUploader` —
 * `status in 400..499 && status != 429` — which dead-lettered the ENTIRE batch on any 4xx,
 * including 401 and 403. A rejected credential says nothing about the events, so that destroyed
 * healthy rows and made a recoverable auth problem look like permanent data damage
 * (`docs/gateway-replication-audit.md`, Blocker 4). The rule now lives here where every status the
 * mission names can be asserted, instead of being read out of a boolean in a 400-line loop.
 */
object OutboxRetryPolicy {

    /** What the uploader should do with the rows the server refused. */
    enum class Action {
        /** Keep the rows and retry later with backoff. The default for anything unclear. */
        RETRY,

        /**
         * The server will repeat this refusal, so the batch cannot succeed as sent. Rows are
         * retained and visible as DEAD_LETTER — never deleted.
         */
        DEAD_LETTER
    }

    /**
     * Attempts after which a row stops retrying and becomes visible as DEAD_LETTER.
     *
     * Nothing bounded retries before: `attemptCount` grew forever and a permanently-failing
     * transport looped for the life of the install. With full-jitter backoff capped at five
     * minutes, 25 attempts spans hours — long enough to ride out a real outage, short enough that
     * a broken event becomes visible instead of looping silently. A dead-lettered row is still
     * recoverable (a successful enrollment resets the cohort), so this loses nothing.
     */
    const val MAX_ATTEMPTS = 25

    /** True when the row has exhausted its attempts and should stop retrying. */
    fun exhausted(attemptCount: Int): Boolean = attemptCount >= MAX_ATTEMPTS

    /**
     * The action for a failed batch.
     *
     * [status] is null for a transport failure (DNS, connect, TLS, timeout) which is always
     * retryable, because nothing about the request was judged.
     */
    fun actionFor(status: Int?, kind: GatewayFailureKind): Action {
        if (status == null) return Action.RETRY
        return when {
            // Stated first, and deliberately: these are the ones that must never kill a row.
            status == 401 -> Action.RETRY
            status == 403 -> Action.RETRY
            // The batch is too large, not wrong. The next claim sends fewer rows.
            status == 413 -> Action.RETRY
            // Rate limiting is retryable by definition.
            status == 429 -> Action.RETRY
            // 409 means the server already has this state (a duplicate). Treating it as fatal
            // would dead-letter events the server has already accepted.
            status == 409 -> Action.RETRY
            // The server itself failed.
            status in 500..599 -> Action.RETRY
            // A contract failure the server will repeat: 400, 404, 405, 422 and friends.
            status in 400..499 -> Action.DEAD_LETTER
            // A 2xx that arrived here is a parse problem, not a verdict on the events.
            else -> Action.RETRY
        }
    }

    /** The structured code to record for a failure, so the row explains itself. */
    fun codeFor(status: Int?, kind: GatewayFailureKind): SyncErrorCode =
        SyncErrorCode.fromFailureKind(kind, httpStatus = status)
}
