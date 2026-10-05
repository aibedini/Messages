package com.autonomousone.messages.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `FETCH_THREAD_HISTORY` request contract and page semantics.
 *
 * Pure tests: a malformed or unbounded request must be refused, the cursor must be all-or-nothing, and
 * the acknowledgement must never carry message content.
 */
class ThreadHistoryCommandTest {

    private fun payload(
        type: String = ThreadHistoryCommand.TYPE,
        requestId: String = "req-1",
        threadId: Long = 12345L,
        before: JSONObject? = null,
        limit: Int? = 20
    ) = JSONObject().apply {
        put("type", type)
        put("requestId", requestId)
        put("androidThreadId", threadId)
        put("conversationId", "gm-1")
        before?.let { put("before", it) }
        limit?.let { put("limit", it) }
    }

    private fun ok(json: JSONObject): ThreadHistoryCommand.Request =
        (ThreadHistoryCommand.parse(json) as ThreadHistoryCommand.ParseResult.Ok).request

    private fun invalid(json: JSONObject): ThreadHistoryCommand.Status =
        (ThreadHistoryCommand.parse(json) as ThreadHistoryCommand.ParseResult.Invalid).status

    // ── parsing ──────────────────────────────────────────────────────────────

    @Test
    fun `a well formed request parses with the thread id as authority`() {
        val request = ok(payload(before = JSONObject().put("dateMs", 1791000000000L).put("providerId", 987654L)))

        assertEquals("req-1", request.requestId)
        assertEquals(12345L, request.androidThreadId)
        assertEquals(1791000000000L, request.beforeDateMs)
        assertEquals(987654L, request.beforeProviderId)
        assertEquals(20, request.limit)
    }

    @Test
    fun `the default limit is 20 and the maximum is 50`() {
        assertEquals(20, ok(payload(limit = null)).limit)
        assertEquals(50, ok(payload(limit = 50)).limit)
    }

    @Test
    fun `an unbounded request is clamped, never executed as asked`() {
        assertEquals(50, ok(payload(limit = 5000)).limit)
        assertEquals(50, ok(payload(limit = Int.MAX_VALUE)).limit)
    }

    @Test
    fun `a non-positive limit is refused rather than defaulted`() {
        assertEquals(ThreadHistoryCommand.Status.CURSOR_INVALID, invalid(payload(limit = 0)))
        assertEquals(ThreadHistoryCommand.Status.CURSOR_INVALID, invalid(payload(limit = -5)))
    }

    @Test
    fun `a missing or zero thread id is refused — history is never resolved heuristically`() {
        assertEquals(ThreadHistoryCommand.Status.THREAD_NOT_FOUND, invalid(payload(threadId = 0L)))
        assertEquals(
            ThreadHistoryCommand.Status.THREAD_NOT_FOUND,
            invalid(JSONObject().put("type", ThreadHistoryCommand.TYPE).put("requestId", "r"))
        )
    }

    @Test
    fun `half a cursor is refused, because guessing it duplicates or skips pages`() {
        assertEquals(
            ThreadHistoryCommand.Status.CURSOR_INVALID,
            invalid(payload(before = JSONObject().put("dateMs", 1791000000000L)))
        )
        assertEquals(
            ThreadHistoryCommand.Status.CURSOR_INVALID,
            invalid(payload(before = JSONObject().put("providerId", 987654L)))
        )
    }

    @Test
    fun `a missing request id is refused`() {
        assertEquals(ThreadHistoryCommand.Status.CURSOR_INVALID, invalid(payload(requestId = " ")))
    }

    @Test
    fun `a plaintext type that disagrees with the envelope is refused`() {
        assertEquals(
            ThreadHistoryCommand.Status.THREAD_NOT_FOUND,
            invalid(payload(type = "SEND_SMS"))
        )
    }

    // ── result vocabulary ────────────────────────────────────────────────────

    @Test
    fun `only publishing a page or proving the end of history is success`() {
        assertTrue(ThreadHistoryCommand.Status.ROWS_PUBLISHED.terminalSuccess)
        assertTrue(ThreadHistoryCommand.Status.END_OF_THREAD_HISTORY.terminalSuccess)
        for (failure in listOf(
            ThreadHistoryCommand.Status.THREAD_NOT_FOUND,
            ThreadHistoryCommand.Status.CURSOR_INVALID,
            ThreadHistoryCommand.Status.HISTORY_QUERY_FAILED,
            ThreadHistoryCommand.Status.EVENT_ENQUEUE_FAILED
        )) {
            assertFalse(failure.terminalSuccess)
        }
    }

    @Test
    fun `every status has a distinct machine code`() {
        val codes = ThreadHistoryCommand.Status.entries.map { it.commandCode }

        assertEquals(codes.size, codes.toSet().size)
        assertTrue(codes.all { it.isNotBlank() && it == it.uppercase() })
    }

    @Test
    fun `the acknowledgement is count-and-flag only, never message content`() {
        val json = ThreadHistoryCommand.resultJson(
            requestId = "req-1",
            status = ThreadHistoryCommand.Status.ROWS_PUBLISHED,
            publishedCount = 20,
            hasMore = true,
            cursor = 1791000000000L to 987654L
        )

        assertEquals(20, json.getInt("publishedCount"))
        assertTrue(json.getBoolean("hasMore"))
        assertEquals("ROWS_PUBLISHED", json.getString("status"))
        assertEquals(1791000000000L, json.getJSONObject("nextBefore").getLong("dateMs"))

        // No message content, no address, no body — the rows travel encrypted, not here.
        val text = json.toString()
        for (forbidden in listOf("body", "phone", "sender", "address", "text", "messages")) {
            assertFalse("the ack must not carry $forbidden", text.contains("\"$forbidden\""))
        }
    }
}
