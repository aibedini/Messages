package com.autonomousone.messages.gateway

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The telemetry rules that a device could not be trusted to demonstrate.
 *
 * Production produced ZERO telemetry POSTs for a week while every other control-plane leg returned
 * 200, and the app could not say which boundary failed because every outcome collapsed into a
 * Boolean. These tests drive the reporter directly with a fake transport, so each failure stage and
 * the single-POST guarantee are asserted rather than inferred.
 */
class TelemetryReporterTest {

    private fun reporter(
        eligibility: TelemetryEligibility = TelemetryEligibility(true, null),
        transport: TelemetryTransport,
        payload: suspend (TelemetryTrigger) -> JSONObject = { JSONObject().put("deviceId", "dev") },
        clock: () -> Long = { 1_000L }
    ) = TelemetryReporter(
        eligibility = { eligibility },
        deviceId = { "dev-8238ea53" },
        payload = payload,
        transport = transport,
        clock = clock
    )

    private fun accepted(status: Int = 200) = TelemetryTransport { _, _ -> TelemetryPostOutcome.Accepted(status) }

    // ── success means 2xx, nothing weaker ────────────────────────────────────

    @Test
    fun `http 200 is the only success`() = runBlocking {
        val result = reporter(transport = accepted(200)).perform(TelemetryTrigger.PERIODIC)

        assertTrue(result is TelemetryReportResult.Success)
        val success = result as TelemetryReportResult.Success
        assertEquals(200, success.httpStatus)
        assertTrue(success.completedAt >= success.attemptedAt)
        assertEquals(TelemetryTrigger.PERIODIC, success.trigger)
    }

    @Test
    fun `http 401 and 500 are HTTP_ERROR with the status preserved`() = runBlocking {
        for (status in listOf(401, 403, 500, 503)) {
            val result = reporter(
                transport = TelemetryTransport { _, _ -> TelemetryPostOutcome.Rejected(status) }
            ).perform(TelemetryTrigger.PERIODIC)

            assertTrue(result is TelemetryReportResult.Failure)
            val failure = result as TelemetryReportResult.Failure
            assertEquals(TelemetryFailureCode.HTTP_ERROR, failure.code)
            assertEquals(status, failure.httpStatus)
            assertEquals("TELEMETRY_HTTP_ERROR", failure.code.commandCode)
        }
    }

    @Test
    fun `a transport failure is not an http rejection`() = runBlocking {
        val result = reporter(
            transport = TelemetryTransport { _, _ -> TelemetryPostOutcome.TransportError("SocketTimeout") }
        ).perform(TelemetryTrigger.REMOTE_REFRESH)

        val failure = result as TelemetryReportResult.Failure
        assertEquals(TelemetryFailureCode.TRANSPORT_ERROR, failure.code)
        assertEquals("TELEMETRY_TRANSPORT_ERROR", failure.code.commandCode)
    }

    @Test
    fun `a signing failure is its own code and never a success`() = runBlocking {
        val result = reporter(
            transport = TelemetryTransport { _, _ -> TelemetryPostOutcome.SigningFailed }
        ).perform(TelemetryTrigger.REMOTE_REFRESH)

        val failure = result as TelemetryReportResult.Failure
        assertEquals(TelemetryFailureCode.SIGNING_FAILED, failure.code)
        assertFalse(result.succeeded)
    }

    @Test
    fun `a payload that cannot be built is a LOCAL failure, not a network one`() = runBlocking {
        val result = reporter(
            transport = accepted(),
            payload = { throw IllegalStateException("db unavailable") }
        ).perform(TelemetryTrigger.PERIODIC)

        val failure = result as TelemetryReportResult.Failure
        // Corrected expectation: this used to assert TRANSPORT_ERROR, which is the conflation a real
        // device exposed — a local payload exception was being reported as "the network is broken" on
        // a phone whose command channel was answering HTTP 200 on the same host.
        assertEquals(TelemetryFailureCode.PAYLOAD_BUILD_FAILED, failure.code)
        assertEquals("PAYLOAD", failure.code.stage)
        assertTrue(failure.detail!!.startsWith("payload_build_failed"))
    }

    // ── eligibility: named, and still enforced ───────────────────────────────

    @Test
    fun `a disabled gateway is reported as such and sends nothing`() = runBlocking {
        var posted = 0
        val transport = TelemetryTransport { _, _ -> posted++; TelemetryPostOutcome.Accepted(200) }

        val result = reporter(
            eligibility = TelemetryEligibility(false, TelemetryEligibility.GATEWAY_DISABLED),
            transport = transport
        ).perform(TelemetryTrigger.PERIODIC)

        assertEquals(TelemetryFailureCode.GATEWAY_DISABLED, (result as TelemetryReportResult.Failure).code)
        assertEquals(0, posted)
    }

    @Test
    fun `an unenrolled device is reported as such and sends nothing`() = runBlocking {
        var posted = 0
        val result = reporter(
            eligibility = TelemetryEligibility(false, TelemetryEligibility.IDENTITY_NOT_REGISTERED),
            transport = TelemetryTransport { _, _ -> posted++; TelemetryPostOutcome.Accepted(200) }
        ).perform(TelemetryTrigger.PERIODIC)

        assertEquals(
            TelemetryFailureCode.IDENTITY_NOT_REGISTERED,
            (result as TelemetryReportResult.Failure).code
        )
        assertEquals(0, posted)
    }

    @Test
    fun `no server origin is reported as such and sends nothing`() = runBlocking {
        var posted = 0
        val result = reporter(
            eligibility = TelemetryEligibility(false, TelemetryEligibility.NO_GMWEB_ORIGIN),
            transport = TelemetryTransport { _, _ -> posted++; TelemetryPostOutcome.Accepted(200) }
        ).perform(TelemetryTrigger.PERIODIC)

        assertEquals(
            TelemetryFailureCode.NO_GMWEB_ORIGIN,
            (result as TelemetryReportResult.Failure).code
        )
        assertEquals(0, posted)
    }

    @Test
    fun `every eligibility reason maps to a distinct command code`() {
        val codes = listOf(
            TelemetryEligibility.GATEWAY_DISABLED,
            TelemetryEligibility.IDENTITY_NOT_REGISTERED,
            TelemetryEligibility.NO_GMWEB_ORIGIN
        ).map { TelemetryFailureCode.forEligibility(it)!!.commandCode }

        assertEquals(codes.size, codes.toSet().size)
        assertTrue(codes.all { it.startsWith("TELEMETRY_") })
    }

    // ── one POST at a time ───────────────────────────────────────────────────

    @Test
    fun `a report that arrives while another is in flight never overlaps it`() = runBlocking {
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        val started = AtomicInteger(0)
        val transport = TelemetryTransport { _, _ ->
            val now = inFlight.incrementAndGet()
            maxInFlight.updateAndGet { max -> maxOf(max, now) }
            started.incrementAndGet()
            delay(50) // the first report is still inside the transport here
            inFlight.decrementAndGet()
            TelemetryPostOutcome.Accepted(200)
        }
        val subject = reporter(transport = transport)

        val first = async { subject.perform(TelemetryTrigger.PERIODIC) }
        delay(10)
        val second = async { subject.perform(TelemetryTrigger.REMOTE_REFRESH) }
        val results = listOf(first, second).awaitAll()

        assertEquals("both attempts must complete", 2, started.get())
        assertEquals("at most ONE telemetry POST may ever be in flight", 1, maxInFlight.get())
        assertTrue(results.all { it.succeeded })
    }

    @Test
    fun `many triggers produce serialized reports, never parallel sockets`() = runBlocking {
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        val done = AtomicInteger(0)
        val transport = TelemetryTransport { _, _ ->
            val now = inFlight.incrementAndGet()
            maxInFlight.updateAndGet { max -> maxOf(max, now) }
            delay(5)
            inFlight.decrementAndGet()
            done.incrementAndGet()
            TelemetryPostOutcome.Accepted(204)
        }
        val subject = reporter(transport = transport)

        val results = (1..5).map {
            async { subject.perform(TelemetryTrigger.REMOTE_REFRESH) }
        }.awaitAll()

        assertEquals(5, done.get())
        assertEquals(1, maxInFlight.get())
        assertTrue(results.all { (it as TelemetryReportResult.Success).httpStatus == 204 })
    }

    @Test
    fun `a failing report does not wedge the next one and stays classified`() = runBlocking {
        var call = 0
        val subject = reporter(
            transport = TelemetryTransport { _, _ ->
                call++
                if (call == 1) TelemetryPostOutcome.Rejected(503) else TelemetryPostOutcome.Accepted(200)
            }
        )

        assertEquals(
            TelemetryFailureCode.HTTP_ERROR,
            (subject.perform(TelemetryTrigger.PERIODIC) as TelemetryReportResult.Failure).code
        )
        val second = subject.perform(TelemetryTrigger.PERIODIC)
        assertTrue("the next attempt must still be able to succeed", second.succeeded)
    }

    @Test
    fun `the device id handed to the transport is the identity the body carries`() = runBlocking {
        var bodyDeviceId: String? = null
        var signedDeviceId: String? = null
        val subject = reporter(
            payload = { JSONObject().put("deviceId", "dev-8238ea53") },
            transport = TelemetryTransport { body, deviceId ->
                bodyDeviceId = body.optString("deviceId")
                signedDeviceId = deviceId
                TelemetryPostOutcome.Accepted(200)
            }
        )

        subject.perform(TelemetryTrigger.PERIODIC)

        assertEquals(signedDeviceId, bodyDeviceId)
    }
}
