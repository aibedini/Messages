package com.autonomousone.messages.sync

import org.json.JSONObject

/**
 * `FETCH_THREAD_HISTORY` — the phone's on-demand thread history page.
 *
 * GMweb asks for one bounded page of an existing conversation; the phone reads it from the provider
 * with a keyset cursor and hands the rows to the NORMAL encrypted replication path. Nothing about the
 * history travels back in the command acknowledgement: the ack carries counts and a cursor flag only,
 * never a body, a phone number or a raw sender.
 *
 * The parsing here is pure so the contract can be asserted without a server: a malformed or unbounded
 * request must be refused rather than turned into an expensive provider scan.
 */
object ThreadHistoryCommand {

    const val TYPE = "FETCH_THREAD_HISTORY"

    const val DEFAULT_LIMIT = 20
    const val MAX_LIMIT = 50

    /** A request the phone is willing to execute. */
    data class Request(
        val requestId: String,
        val conversationId: String?,
        val androidThreadId: Long,
        val beforeDateMs: Long?,
        val beforeProviderId: Long?,
        val limit: Int
    )

    /** Why a request was refused, or how execution ended. Machine codes, never prose. */
    enum class Status(val commandCode: String) {
        ROWS_PUBLISHED("ROWS_PUBLISHED"),
        END_OF_THREAD_HISTORY("END_OF_THREAD_HISTORY"),
        THREAD_NOT_FOUND("THREAD_NOT_FOUND"),
        CURSOR_INVALID("CURSOR_INVALID"),
        HISTORY_QUERY_FAILED("HISTORY_QUERY_FAILED"),
        EVENT_ENQUEUE_FAILED("EVENT_ENQUEUE_FAILED");

        val terminalSuccess: Boolean
            get() = this == ROWS_PUBLISHED || this == END_OF_THREAD_HISTORY
    }

    sealed interface ParseResult {
        data class Ok(val request: Request) : ParseResult
        data class Invalid(val status: Status, val detail: String) : ParseResult
    }

    /**
     * Parse and bound a decrypted payload.
     *
     * `androidThreadId` is the authority: history is never resolved by phone-number normalisation, so a
     * Persian-digit number, a short code and an alphanumeric sender are all handled the same way — the
     * thread id that Android itself assigned.
     */
    fun parse(payload: JSONObject): ParseResult {
        val type = payload.optString("type")
        if (type.isNotBlank() && type != TYPE) {
            return ParseResult.Invalid(Status.THREAD_NOT_FOUND, "type_mismatch")
        }
        val requestId = payload.optString("requestId").takeIf { it.isNotBlank() }
            ?: return ParseResult.Invalid(Status.CURSOR_INVALID, "missing_request_id")

        // threadId: accept the documented name and the shorter alias GMweb may send.
        val threadId = when {
            payload.has("androidThreadId") -> payload.optLong("androidThreadId", 0L)
            payload.has("threadId") -> payload.optLong("threadId", 0L)
            else -> 0L
        }
        if (threadId <= 0L) {
            return ParseResult.Invalid(Status.THREAD_NOT_FOUND, "thread_id_missing")
        }

        val before = payload.optJSONObject("before")
        val beforeDate = before?.optLong("dateMs", 0L)?.takeIf { it > 0L }
        val beforeId = before?.optLong("providerId", 0L)?.takeIf { it > 0L }

        // A cursor is all-or-nothing: half a keyset key cannot be compared deterministically, and
        // guessing the missing half is how pages end up duplicated or skipped.
        if (before != null && (beforeDate == null || beforeId == null)) {
            return ParseResult.Invalid(Status.CURSOR_INVALID, "incomplete_cursor")
        }

        val rawLimit = payload.optInt("limit", DEFAULT_LIMIT)
        if (payload.has("limit") && rawLimit <= 0) {
            return ParseResult.Invalid(Status.CURSOR_INVALID, "non_positive_limit")
        }

        return ParseResult.Ok(
            Request(
                requestId = requestId,
                conversationId = payload.optString("conversationId").takeIf { it.isNotBlank() },
                androidThreadId = threadId,
                beforeDateMs = beforeDate,
                beforeProviderId = beforeId,
                // Never unbounded: a request larger than MAX_LIMIT is clamped, not executed.
                limit = rawLimit.coerceIn(1, MAX_LIMIT)
            )
        )
    }

    /**
     * The result body. Deliberately count-and-flag only: the messages themselves were already handed to
     * the encrypted replication path, and an acknowledgement must not become a second, plaintext copy.
     */
    fun resultJson(
        requestId: String,
        status: Status,
        publishedCount: Int,
        hasMore: Boolean,
        cursor: Pair<Long, Long>? = null
    ): JSONObject = JSONObject()
        .put("requestId", requestId)
        .put("status", status.commandCode)
        .put("publishedCount", publishedCount)
        .put("hasMore", hasMore)
        .apply {
            cursor?.let {
                put("nextBefore", JSONObject().put("dateMs", it.first).put("providerId", it.second))
            }
        }
}
