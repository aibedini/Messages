package com.autonomousone.messages

import com.autonomousone.messages.eve.EveQueueCodec
import com.autonomousone.messages.eve.EveSmsQueue
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Persistence contract for the extended queue record.
 *
 * Records written by older app versions carry none of the gateway fields, and
 * a queue written by a different build may even carry a status name this build
 * does not know. Neither may crash the queue or lose the message.
 */
class EveQueueCodecPersistenceTest {

    /** The exact JSON shape shipped before the gateway fields existed. */
    private val legacyRecordJson = """
        {
          "requestId": "sms_legacy_1",
          "jobId": "job_legacy",
          "to": "+989120000001",
          "text": "legacy body",
          "priority": "announcement",
          "priorityLevel": 10,
          "status": "QUEUED",
          "createdAt": 1700000000000,
          "sentAt": 0,
          "failedReason": "",
          "submittedOnce": false,
          "verificationStatus": "",
          "verificationAttempts": 0
        }
    """.trimIndent()

    @Test
    fun legacyRecordWithoutTheNewFieldsStillDeserializes() {
        val rec = EveQueueCodec.decode(JSONObject(legacyRecordJson))

        assertEquals("sms_legacy_1", rec.requestId)
        assertEquals("legacy body", rec.text)
        assertEquals(EveSmsQueue.Status.QUEUED, rec.status)
        // New fields fall back to safe defaults instead of throwing.
        assertNull(rec.gatewayRequestId)
        assertNull(rec.source)
        assertNull(rec.serviceKey)
        assertNull(rec.notificationKind)
        assertNull(rec.correlationId)
        assertEquals(0, rec.generation)
        assertFalse(rec.requiresValidation)
        assertEquals(0L, rec.pulledAt)
        assertEquals(0L, rec.validatedAt)
        assertNull(rec.validationResult)
        assertEquals(0, rec.validationAttempts)
        assertEquals(0L, rec.deferredUntil)
        assertNull(rec.supersededReason)
        assertEquals(0L, rec.nativeSubmitStartedAt)
    }

    @Test
    fun legacyRecordWithoutAnyOptionalFieldsAtAllStillDeserializes() {
        // Only the fields that have always been mandatory.
        val minimal = JSONObject()
            .put("requestId", "sms_min")
            .put("jobId", "job_min")
            .put("to", "+1")
            .put("text", "t")
            .put("priority", "critical")
            .put("priorityLevel", 1)
            .put("status", "QUEUED")
            .put("createdAt", 1L)

        val rec = EveQueueCodec.decode(minimal)

        assertEquals("sms_min", rec.requestId)
        assertFalse(rec.requiresValidation)
        assertEquals(0L, rec.deferredUntil)
        assertFalse(rec.terminal)
    }

    @Test
    fun unknownStatusNameDegradesInsteadOfCrashingTheQueue() {
        val rec = EveQueueCodec.decode(
            JSONObject(legacyRecordJson).put("status", "SOMETHING_FROM_ANOTHER_BUILD")
        )

        assertEquals(EveSmsQueue.Status.FAILED, rec.status)
        assertTrue(rec.terminal)
    }

    @Test
    fun blankStatusDegradesInsteadOfCrashing() {
        val rec = EveQueueCodec.decode(JSONObject(legacyRecordJson).put("status", ""))
        assertEquals(EveSmsQueue.Status.FAILED, rec.status)
    }

    @Test
    fun everyNewFieldSurvivesAnEncodeDecodeRoundTrip() {
        val original = EveSmsQueue.Record(
            requestId = "sms_rt",
            jobId = "job_rt",
            to = "+989120000009",
            text = "volume ended",
            priority = "critical",
            priorityLevel = 1,
            status = EveSmsQueue.Status.SUPERSEDED,
            createdAt = 1700000000000,
            sentAt = 0,
            failedReason = null,
            submittedOnce = false,
            verificationStatus = null,
            verificationAttempts = 0,
            gatewayRequestId = "gw-rt-1",
            source = "eve",
            serviceKey = "eve:srv:cli",
            notificationKind = "volume_ended",
            generation = 17,
            correlationId = "corr-1",
            requiresValidation = true,
            pulledAt = 1700000000001,
            validatedAt = 1700000000002,
            validationResult = "superseded",
            validationAttempts = 2,
            deferredUntil = 1700000000003,
            supersededReason = "renewed",
            nativeSubmitStartedAt = 0
        )

        val restored = EveQueueCodec.decode(EveQueueCodec.encode(original))

        assertEquals(original.gatewayRequestId, restored.gatewayRequestId)
        assertEquals(original.source, restored.source)
        assertEquals(original.serviceKey, restored.serviceKey)
        assertEquals(original.notificationKind, restored.notificationKind)
        assertEquals(original.generation, restored.generation)
        assertEquals(original.correlationId, restored.correlationId)
        assertEquals(original.requiresValidation, restored.requiresValidation)
        assertEquals(original.pulledAt, restored.pulledAt)
        assertEquals(original.validatedAt, restored.validatedAt)
        assertEquals(original.validationResult, restored.validationResult)
        assertEquals(original.validationAttempts, restored.validationAttempts)
        assertEquals(original.deferredUntil, restored.deferredUntil)
        assertEquals(original.supersededReason, restored.supersededReason)
        assertEquals(original.nativeSubmitStartedAt, restored.nativeSubmitStartedAt)
        assertEquals(EveSmsQueue.Status.SUPERSEDED, restored.status)
        assertTrue(restored.terminal)
        assertFalse(restored.successful)
    }

    @Test
    fun deferredAndSupersededStatusesRoundTripByName() {
        val base = EveQueueCodec.decode(JSONObject(legacyRecordJson))
        for (status in listOf(
            EveSmsQueue.Status.SUPERSEDED,
            EveSmsQueue.Status.DEFERRED,
            EveSmsQueue.Status.CANCELLED
        )) {
            val restored = EveQueueCodec.decode(EveQueueCodec.encode(base.copy(status = status)))
            assertEquals(status, restored.status)
        }
    }
}
