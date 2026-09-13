package com.autonomousone.messages

import com.autonomousone.messages.eve.EveSmsQueue
import com.autonomousone.messages.gateway.OutboxPoller
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-level contract of the GMweb bridge: task.meta parsing (backward
 * compatible with servers that send no meta at all) and the /gateway/ack body.
 */
class OutboxPollerPayloadTest {

    private val pullWithMeta = JSONObject(
        """
        {
          "task": {
            "requestId": "gw-request-1",
            "to": "+989120000001",
            "text": "your volume has ended",
            "priority": "critical",
            "meta": {
              "source": "eve",
              "serviceKey": "eve:server-1:client-1",
              "notificationKind": "volume_ended",
              "generation": 17,
              "correlationId": "3f1c0a2e-0000-4000-8000-000000000000",
              "requiresValidation": true
            }
          }
        }
        """.trimIndent()
    )

    private val legacyPull = JSONObject(
        """
        {"task":{"requestId":"legacy-1","to":"+989120000002","text":"hello","priority":"announcement"}}
        """.trimIndent()
    )

    // ── pull parsing ─────────────────────────────────────────────────────────

    @Test
    fun pullPreservesTaskMeta() {
        val task = OutboxPoller.parseTask(pullWithMeta)!!

        assertEquals("gw-request-1", task.requestId)
        assertEquals("+989120000001", task.to)
        assertEquals("critical", task.priority)
        val meta = task.meta!!
        assertEquals("eve", meta.source)
        assertEquals("eve:server-1:client-1", meta.serviceKey)
        assertEquals("volume_ended", meta.notificationKind)
        assertEquals(17, meta.generation)
        assertEquals("3f1c0a2e-0000-4000-8000-000000000000", meta.correlationId)
        assertTrue(meta.requiresValidation)
    }

    @Test
    fun legacyTaskWithoutMetaStaysBackwardCompatible() {
        val task = OutboxPoller.parseTask(legacyPull)!!

        assertEquals("legacy-1", task.requestId)
        assertNull("no meta means no validation gate", task.meta)

        val decision = OutboxPoller.parseMeta(null)
        assertNull(decision)
    }

    @Test
    fun metaWithoutRequiresValidationIsParsedButDoesNotGate() {
        val meta = OutboxPoller.parseMeta(
            JSONObject("""{"source":"eve","serviceKey":"eve:s:c","notificationKind":"renew"}""")
        )!!

        assertEquals("renew", meta.notificationKind)
        assertFalse(meta.requiresValidation)
    }

    @Test
    fun emptyLongPollResponseYieldsNoTask() {
        assertNull(OutboxPoller.parseTask(JSONObject("""{"task":null}""")))
        assertNull(OutboxPoller.parseTask(JSONObject("{}")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedTaskThrowsInsteadOfSilentlyDropping() {
        OutboxPoller.parseTask(JSONObject("""{"task":{"requestId":"","to":"+1","text":"x"}}"""))
    }

    // ── ack payloads ─────────────────────────────────────────────────────────

    @Test
    fun successAckReportsOutcomeSent() {
        val ack = OutboxPoller.ackPayload("gw-request-1", EveSmsQueue.OUTCOME_SENT, null, 1_700_000_000_000L)

        assertEquals("gw-request-1", ack.getString("requestId"))
        assertTrue(ack.getBoolean("ok"))
        assertEquals("sent", ack.getString("outcome"))
        // Legacy servers read sentAt; it is always present.
        assertEquals(1_700_000_000_000L, ack.getLong("sentAt"))
    }

    @Test
    fun supersededAckIsDistinguishableFromDeviceFailure() {
        val ack = OutboxPoller.ackPayload(
            "gw-request-1", EveSmsQueue.OUTCOME_SUPERSEDED, "renewed", 1_700_000_000_000L
        )

        assertFalse(ack.getBoolean("ok"))
        assertEquals("superseded", ack.getString("outcome"))
        assertEquals("renewed", ack.getString("reason"))
        assertFalse(
            "business invalidation must not be reported as a device failure",
            ack.getString("outcome") == EveSmsQueue.OUTCOME_FAILED
        )
        assertFalse(ack.getString("reason") == "device_send_failed")
    }

    @Test
    fun deviceFailureAckKeepsTheLegacyReason() {
        val ack = OutboxPoller.ackPayload(
            "gw-request-1", EveSmsQueue.OUTCOME_FAILED, "device_send_failed", 1L
        )

        assertFalse(ack.getBoolean("ok"))
        assertEquals("device_send_failed", ack.getString("outcome"))
        assertEquals("device_send_failed", ack.getString("reason"))
    }

    @Test
    fun everyNonSentOutcomeIsNotOk() {
        for (outcome in listOf(
            EveSmsQueue.OUTCOME_SUPERSEDED,
            EveSmsQueue.OUTCOME_FAILED,
            EveSmsQueue.OUTCOME_CANCELLED,
            EveSmsQueue.OUTCOME_DEFERRED
        )) {
            val ack = OutboxPoller.ackPayload("r", outcome, "why", 1L)
            assertFalse(ack.getBoolean("ok"))
            assertEquals(outcome, ack.getString("outcome"))
        }
    }

    @Test
    fun supersededOutcomeMapsFromTheQueueStatus() {
        val base = EveSmsQueue.Record(
            requestId = "r", jobId = "j", to = "+1", text = "t",
            priority = "critical", priorityLevel = 1,
            status = EveSmsQueue.Status.QUEUED, createdAt = 1L
        )
        assertEquals("sent", base.copy(status = EveSmsQueue.Status.SENT).outcome)
        assertEquals("superseded", base.copy(status = EveSmsQueue.Status.SUPERSEDED).outcome)
        assertEquals("device_send_failed", base.copy(status = EveSmsQueue.Status.FAILED).outcome)
        assertEquals("deferred", base.copy(status = EveSmsQueue.Status.DEFERRED).outcome)
        assertEquals("pending", base.copy(status = EveSmsQueue.Status.ACTIVE).outcome)
    }

    @Test
    fun supersededIsTerminalButNotSuccessfulAndNotRetryable() {
        val rec = EveSmsQueue.Record(
            requestId = "r", jobId = "j", to = "+1", text = "t",
            priority = "critical", priorityLevel = 1,
            status = EveSmsQueue.Status.SUPERSEDED, createdAt = 1L
        )
        assertTrue(rec.terminal)
        assertFalse(rec.successful)
        assertTrue(rec.superseded)
        assertFalse(rec.deferred)
        assertFalse("physical send never started", rec.submittedOnce)
        assertEquals(0L, rec.nativeSubmitStartedAt)
    }
}
