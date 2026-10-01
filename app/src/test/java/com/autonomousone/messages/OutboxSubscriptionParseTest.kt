package com.autonomousone.messages

import com.autonomousone.messages.gateway.OutboxPoller
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Per-message SIM selection on the pull contract.
 *
 * The task's SIM used to be dropped on the floor: `Task` had no field for it and
 * the gateway queue-send seam passed a hard-coded `subscriptionIdOverride = null`,
 * so a line chosen in GMweb silently became "the user's default line".
 */
class OutboxSubscriptionParseTest {

    /** One GMweb pull payload; [configure] adds/overrides fields on the task. */
    private fun pullTask(configure: JSONObject.() -> Unit = {}): JSONObject {
        val task = JSONObject()
            .put("requestId", "gw-sim-1")
            .put("to", "+989120000001")
            .put("text", "line1\nline2")
            .put("priority", "critical")
            .apply(configure)
        return JSONObject().put("task", task)
    }

    private fun parse(configure: JSONObject.() -> Unit = {}) =
        OutboxPoller.parseTask(pullTask(configure))!!

    // ── canonical field ──────────────────────────────────────────────────────

    @Test
    fun `canonical subscriptionId is parsed`() {
        val task = parse { put("subscriptionId", 7) }

        assertEquals(7, task.subscriptionId)
        assertNull(task.subscriptionError)
    }

    @Test
    fun `zero is a valid explicit choice, not a missing one`() {
        val task = parse { put("subscriptionId", 0) }

        assertEquals(0, task.subscriptionId)
        assertNull(task.subscriptionError)
    }

    @Test
    fun `absent subscriptionId stays null - no explicit choice was made`() {
        val task = parse()

        assertNull(task.subscriptionId)
        assertNull(task.subscriptionError)
    }

    @Test
    fun `explicit json null is treated as no choice instead of an error`() {
        val task = parse { put("subscriptionId", JSONObject.NULL) }

        assertNull(task.subscriptionId)
        assertNull(task.subscriptionError)
    }

    // ── legacy spellings ─────────────────────────────────────────────────────

    @Test
    fun `legacy snake case field is accepted`() {
        assertEquals(7, parse { put("subscription_id", 7) }.subscriptionId)
    }

    @Test
    fun `meta subscriptionId is accepted for older senders`() {
        val task = parse { put("meta", JSONObject().put("source", "eve").put("subscriptionId", 7)) }

        assertEquals(7, task.subscriptionId)
        assertEquals("eve", task.meta!!.source)
    }

    @Test
    fun `meta snake case field is accepted`() {
        assertEquals(7, parse { put("meta", JSONObject().put("subscription_id", 7)) }.subscriptionId)
    }

    @Test
    fun `the canonical key wins over the legacy ones`() {
        val task = parse {
            put("subscriptionId", 2)
            put("subscription_id", 9)
            put("meta", JSONObject().put("subscriptionId", 5))
        }

        assertEquals(2, task.subscriptionId)
    }

    @Test
    fun `a numeric string is accepted because json senders disagree on quoting`() {
        assertEquals(7, parse { put("subscriptionId", "7") }.subscriptionId)
    }

    // ── unusable values must fail closed, never fall back ────────────────────

    @Test
    fun `a non-numeric value is an explicit invalid_subscription, not a missing sim`() {
        val task = parse { put("subscriptionId", "slot-2") }

        assertNull("an unusable id must never become a send", task.subscriptionId)
        assertEquals("invalid_subscription", task.subscriptionError)
    }

    @Test
    fun `negative ids are invalid - the sentinel is never sent to the radio`() {
        assertNull(parse { put("subscriptionId", -1) }.subscriptionId)
        assertEquals("invalid_subscription", parse { put("subscriptionId", -1) }.subscriptionError)
        assertEquals("invalid_subscription", parse { put("subscriptionId", -99) }.subscriptionError)
    }

    @Test
    fun `fractional and boolean and array values are invalid rather than coerced`() {
        assertEquals("invalid_subscription", parse { put("subscriptionId", 7.5) }.subscriptionError)
        assertEquals("invalid_subscription", parse { put("subscriptionId", true) }.subscriptionError)
        assertEquals(
            "invalid_subscription",
            parse { put("subscriptionId", org.json.JSONArray()) }.subscriptionError
        )
    }

    // ── nothing else about the task changed ──────────────────────────────────

    @Test
    fun `the body is still taken verbatim from the payload`() {
        assertEquals("line1\nline2", parse { put("subscriptionId", 7) }.text)
    }

    @Test
    fun `a persisted-looking long body is not trimmed or flattened`() {
        val body = "\nfirst\n\nthird\n"
        assertEquals(body, parse { put("text", body) }.text)
    }

    @Test
    fun `a task without a sim keeps every legacy field intact`() {
        val task = OutboxPoller.parseTask(
            JSONObject()
                .put("task", JSONObject()
                    .put("requestId", "legacy-9")
                    .put("to", "+989120000002")
                    .put("text", "hello")
                    .put("priority", "announcement")
                    .put("meta", JSONObject().put("requiresValidation", true)))
        )!!

        assertEquals("legacy-9", task.requestId)
        assertEquals("+989120000002", task.to)
        assertEquals("hello", task.text)
        assertNull(task.subscriptionId)
        assertNull(task.subscriptionError)
        assertEquals(true, task.meta!!.requiresValidation)
    }
}
