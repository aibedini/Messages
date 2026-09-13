package com.autonomousone.messages

import com.autonomousone.messages.eve.EveSmsQueue
import com.autonomousone.messages.gateway.GmwebTaskValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Transport-level behaviour of the mandatory final pre-send validator.
 *
 * Every failure mode must FAIL CLOSED: it may never be reported as "valid",
 * because "valid" is what authorises a physical SMS.
 */
class GmwebTaskValidatorTest {

    private class Recorder {
        var calls = 0
        var lastUrl: String? = null
        var lastApiKey: String? = null
        var lastBody: String? = null
        var lastConnectTimeout: Int = -1
        var lastReadTimeout: Int = -1
    }

    // "respond" is intentionally last so call sites can use a trailing lambda.
    private fun validator(
        rec: Recorder,
        online: Boolean = true,
        canTransmit: Boolean = true,
        url: String = "https://gmweb.example.com",
        respond: (String) -> GmwebTaskValidator.Result
    ): GmwebTaskValidator = GmwebTaskValidator(
        config = GmwebTaskValidator.Config(
            gmwebUrl = { url },
            apiKey = { "gw_test_key" },
            canTransmit = { canTransmit },
            isOnline = { online }
        ),
        transport = object : GmwebTaskValidator.Transport {
            override fun postJson(
                url: String,
                apiKey: String,
                body: String,
                connectTimeoutMs: Int,
                readTimeoutMs: Int
            ): GmwebTaskValidator.Result {
                rec.calls++
                rec.lastUrl = url
                rec.lastApiKey = apiKey
                rec.lastBody = body
                rec.lastConnectTimeout = connectTimeoutMs
                rec.lastReadTimeout = readTimeoutMs
                return respond(body)
            }
        }
    )

    private fun record(gatewayRequestId: String?) = EveSmsQueue.Record(
        requestId = "sms_local_1",
        jobId = "job_1",
        to = "+989120000001",
        text = "volume ended",
        priority = "critical",
        priorityLevel = 1,
        status = EveSmsQueue.Status.QUEUED,
        createdAt = 1L,
        gatewayRequestId = gatewayRequestId,
        requiresValidation = true
    )

    @Test
    fun validResponseAuthorisesTheSend() {
        val rec = Recorder()
        val v = validator(rec) { GmwebTaskValidator.Result(200, """{"valid":true,"status":"valid","reason":null}""") }

        val decision = v.validateRequestId("gw-1")

        assertTrue(decision is EveSmsQueue.ValidationDecision.Valid)
        assertEquals("https://gmweb.example.com/gateway/validate", rec.lastUrl)
        assertEquals("gw_test_key", rec.lastApiKey)
        assertTrue(rec.lastBody!!.contains("\"requestId\":\"gw-1\""))
    }

    @Test
    fun requestUsesBoundedTimeouts() {
        val rec = Recorder()
        val v = validator(rec) { GmwebTaskValidator.Result(200, """{"valid":true}""") }

        v.validateRequestId("gw-timeouts")

        assertEquals(GmwebTaskValidator.CONNECT_TIMEOUT_MS, rec.lastConnectTimeout)
        assertEquals(GmwebTaskValidator.READ_TIMEOUT_MS, rec.lastReadTimeout)
        assertTrue("timeouts must be bounded", rec.lastReadTimeout in 1..30_000)
    }

    @Test
    fun supersededResponseCarriesTheBusinessReason() {
        val rec = Recorder()
        val v = validator(rec) {
            GmwebTaskValidator.Result(200, """{"valid":false,"status":"superseded","reason":"renewed"}""")
        }

        val decision = v.validateRequestId("gw-2")

        assertTrue(decision is EveSmsQueue.ValidationDecision.Superseded)
        assertEquals("renewed", (decision as EveSmsQueue.ValidationDecision.Superseded).reason)
    }

    @Test
    fun missingValidFlagIsTreatedAsSupersededNeverAsValid() {
        val rec = Recorder()
        val v = validator(rec) { GmwebTaskValidator.Result(200, """{"status":"unknown","reason":null}""") }

        val decision = v.validateRequestId("gw-3")

        assertTrue(decision is EveSmsQueue.ValidationDecision.Superseded)
    }

    @Test
    fun serverErrorFailsClosed() {
        val rec = Recorder()
        val v = validator(rec) { GmwebTaskValidator.Result(500, "boom") }

        val decision = v.validateRequestId("gw-4")

        assertTrue(decision is EveSmsQueue.ValidationDecision.Unavailable)
        assertEquals("http_500", (decision as EveSmsQueue.ValidationDecision.Unavailable).reason)
    }

    @Test
    fun serverErrorWithValidLookingBodyStillFailsClosed() {
        val rec = Recorder()
        val v = validator(rec) { GmwebTaskValidator.Result(503, """{"valid":true}""") }

        val decision = v.validateRequestId("gw-5")

        assertTrue(decision is EveSmsQueue.ValidationDecision.Unavailable)
        assertEquals("http_503", (decision as EveSmsQueue.ValidationDecision.Unavailable).reason)
    }

    @Test
    fun timeoutFailsClosed() {
        val rec = Recorder()
        val v = validator(rec) { throw SocketTimeoutException("read timed out") }

        val decision = v.validateRequestId("gw-6")

        assertTrue(decision is EveSmsQueue.ValidationDecision.Unavailable)
        assertEquals("timeout", (decision as EveSmsQueue.ValidationDecision.Unavailable).reason)
    }

    @Test
    fun gatewayTimeoutStatusFailsClosedAsTimeout() {
        val rec = Recorder()
        val v = validator(rec) { GmwebTaskValidator.Result(504, "gateway timeout") }

        val decision = v.validateRequestId("gw-7")

        assertEquals("timeout", (decision as EveSmsQueue.ValidationDecision.Unavailable).reason)
    }

    @Test
    fun rateLimitedFailsClosedWithoutHammering() {
        val rec = Recorder()
        val v = validator(rec) { GmwebTaskValidator.Result(429, "slow down") }

        val decision = v.validateRequestId("gw-8")

        assertEquals("rate_limited", (decision as EveSmsQueue.ValidationDecision.Unavailable).reason)
    }

    @Test
    fun networkErrorFailsClosed() {
        val rec = Recorder()
        val v = validator(rec) { throw IOException("connection reset") }

        val decision = v.validateRequestId("gw-9")

        assertEquals("network_error", (decision as EveSmsQueue.ValidationDecision.Unavailable).reason)
    }

    @Test
    fun invalidJsonBodyFailsClosed() {
        val rec = Recorder()
        val v = validator(rec) { GmwebTaskValidator.Result(200, "<html>not json</html>") }

        val decision = v.validateRequestId("gw-10")

        assertEquals("invalid_response", (decision as EveSmsQueue.ValidationDecision.Unavailable).reason)
    }

    @Test
    fun emptyBodyFailsClosed() {
        val rec = Recorder()
        val v = validator(rec) { GmwebTaskValidator.Result(200, null) }

        val decision = v.validateRequestId("gw-11")

        assertEquals("empty_response", (decision as EveSmsQueue.ValidationDecision.Unavailable).reason)
    }

    @Test
    fun offlineSkipsTheRequestEntirely() {
        val rec = Recorder()
        val v = validator(rec, online = false) { GmwebTaskValidator.Result(200, """{"valid":true}""") }

        val decision = v.validateRequestId("gw-12")

        assertEquals(0, rec.calls)
        assertEquals("offline", (decision as EveSmsQueue.ValidationDecision.Unavailable).reason)
    }

    @Test
    fun revokedConsentSkipsTheRequestEntirely() {
        val rec = Recorder()
        val v = validator(rec, canTransmit = false) { GmwebTaskValidator.Result(200, """{"valid":true}""") }

        val decision = v.validateRequestId("gw-13")

        assertEquals(0, rec.calls)
        assertEquals("gateway_inactive", (decision as EveSmsQueue.ValidationDecision.Unavailable).reason)
    }

    @Test
    fun unconfiguredGmwebUrlFailsClosed() {
        val rec = Recorder()
        val v = validator(rec, url = "") { GmwebTaskValidator.Result(200, """{"valid":true}""") }

        val decision = v.validateRequestId("gw-14")

        assertEquals(0, rec.calls)
        assertEquals("gmweb_not_configured", (decision as EveSmsQueue.ValidationDecision.Unavailable).reason)
    }

    @Test
    fun recordWithoutGatewayRequestIdFailsClosed() {
        val rec = Recorder()
        val v = validator(rec) { GmwebTaskValidator.Result(200, """{"valid":true}""") }

        val decision = v.validate(record(null))

        assertEquals(0, rec.calls)
        assertEquals(
            "missing_gateway_request_id",
            (decision as EveSmsQueue.ValidationDecision.Unavailable).reason
        )
    }

    @Test
    fun recordWithGatewayRequestIdIsValidatedByThatId() {
        val rec = Recorder()
        val v = validator(rec) { GmwebTaskValidator.Result(200, """{"valid":true}""") }

        val decision = v.validate(record("gw-from-record"))

        assertTrue(decision is EveSmsQueue.ValidationDecision.Valid)
        assertTrue(rec.lastBody!!.contains("gw-from-record"))
        assertFalse(rec.lastBody!!.contains("sms_local_1"))
    }
}
