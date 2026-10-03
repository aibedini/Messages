package com.autonomousone.messages.gateway

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The bookkeeping a real device proved broken.
 *
 * The installed 3.4.23 phone showed `attempts = 0`, `failures = 10`, `last attempt = never`. That is
 * impossible, and its cause was structural: the periodic loop recorded the attempt while the awaited
 * path (the manual button, the remote refresh) did not, so ten failures were counted against zero
 * attempts and the card could not say when anything had been tried.
 *
 * These tests pin the invariant the counter must satisfy, and the stage classification that keeps a
 * LOCAL payload exception from being reported as a network failure.
 */
class TelemetryAttemptAccountingTest {

    private fun reporter(
        eligibility: TelemetryEligibility = TelemetryEligibility(true, null),
        transport: TelemetryTransport,
        payload: suspend (TelemetryTrigger) -> JSONObject = { JSONObject() }
    ) = TelemetryReporter(
        eligibility = { eligibility },
        deviceId = { "dev" },
        payload = payload,
        transport = transport
    )

    @Before
    fun setUp() {
        TelemetryHealth.resetForTest()
    }

    @After
    fun tearDown() {
        TelemetryHealth.resetForTest()
    }

    /**
     * The production accounting path: exactly what `DeviceTelemetry.performReport` does (attempt
     * first, then classify), so these assertions are about the shipping behaviour and not a model of
     * it.
     */
    private suspend fun performLikeProduction(
        subject: TelemetryReporter,
        trigger: TelemetryTrigger = TelemetryTrigger.MANUAL_DIAGNOSTIC_REFRESH
    ): TelemetryReportResult {
        TelemetryHealth.onAttempt(trigger)
        val result = subject.perform(trigger)
        when (result) {
            is TelemetryReportResult.Success -> TelemetryHealth.onSuccess(
                at = System.currentTimeMillis(),
                httpStatus = result.httpStatus
            )
            is TelemetryReportResult.Failure -> TelemetryHealth.onFailure(
                at = System.currentTimeMillis(),
                httpStatus = result.httpStatus,
                errorCode = result.code.name,
                stage = result.code.stage,
                detail = result.detail
            )
        }
        return result
    }

    // ── the invariant ────────────────────────────────────────────────────────

    @Test
    fun `one failed report increments attempts AND failures`() = runBlocking {
        val subject = reporter(transport = TelemetryTransport { _, _ -> TelemetryPostOutcome.TransportError("SocketTimeoutException") })

        performLikeProduction(subject)
        val health = TelemetryHealth.snapshot()

        assertEquals(1L, health.attempts)
        assertEquals(1L, health.failures)
        assertEquals(0L, health.successes)
        assertEquals("PERIODIC-like: attempts must equal successes + failures", health.attempts, health.successes + health.failures)
    }

    @Test
    fun `one successful report increments attempts AND successes`() = runBlocking {
        val subject = reporter(transport = TelemetryTransport { _, _ -> TelemetryPostOutcome.Accepted(200) })

        performLikeProduction(subject)
        val health = TelemetryHealth.snapshot()

        assertEquals(1L, health.attempts)
        assertEquals(1L, health.successes)
        assertEquals(0L, health.failures)
        assertEquals(health.attempts, health.successes + health.failures)
    }

    @Test
    fun `ten failures cannot coexist with zero attempts`() = runBlocking {
        // The exact shape the device showed, driven through the production accounting path.
        val subject = reporter(transport = TelemetryTransport { _, _ -> TelemetryPostOutcome.Rejected(503) })

        repeat(10) { performLikeProduction(subject) }
        val health = TelemetryHealth.snapshot()

        assertEquals(10L, health.failures)
        assertEquals(10L, health.attempts)
        assertEquals(health.attempts, health.successes + health.failures)
    }

    @Test
    fun `the manual trigger is recorded as the last trigger and stamps the attempt time`() = runBlocking {
        val subject = reporter(transport = TelemetryTransport { _, _ -> TelemetryPostOutcome.Accepted(200) })
        val before = System.currentTimeMillis()

        performLikeProduction(subject, TelemetryTrigger.MANUAL_DIAGNOSTIC_REFRESH)
        val health = TelemetryHealth.snapshot()

        assertEquals("MANUAL_DIAGNOSTIC_REFRESH", health.lastTrigger)
        assertTrue("last attempt must be stamped", (health.lastAttemptAt ?: 0L) >= before)
        assertTrue("last success must be stamped", (health.lastSuccessAt ?: 0L) >= before)
    }

    @Test
    fun `last attempt can never be older than the last failure`() = runBlocking {
        val subject = reporter(transport = TelemetryTransport { _, _ -> TelemetryPostOutcome.TransportError("x") })

        performLikeProduction(subject)
        val health = TelemetryHealth.snapshot()

        assertTrue(
            "the device showed 'last attempt: never' beside ten failures",
            (health.lastAttemptAt ?: 0L) >= (health.lastSuccessAt ?: 0L)
        )
        assertTrue(health.lastAttemptAt != null)
    }

    // ── stage classification ─────────────────────────────────────────────────

    @Test
    fun `a payload exception is PAYLOAD_BUILD_FAILED, not a network failure`() = runBlocking {
        val subject = reporter(
            transport = TelemetryTransport { _, _ -> TelemetryPostOutcome.Accepted(200) },
            payload = { throw IllegalStateException("SQLiteException while reading a counter") }
        )

        performLikeProduction(subject)
        val health = TelemetryHealth.snapshot()

        assertEquals(TelemetryFailureCode.PAYLOAD_BUILD_FAILED.name, health.lastErrorCode)
        assertEquals("PAYLOAD", health.lastFailureStage)
        assertTrue(
            "the exception class must survive into diagnostics",
            health.lastFailureDetail!!.contains("payload_build_failed") &&
                health.lastFailureDetail.contains("IllegalStateException")
        )
    }

    @Test
    fun `a signing failure stays a signing failure with its own stage`() = runBlocking {
        val subject = reporter(transport = TelemetryTransport { _, _ -> TelemetryPostOutcome.SigningFailed })
        performLikeProduction(subject)
        val health = TelemetryHealth.snapshot()

        assertEquals(TelemetryFailureCode.SIGNING_FAILED.name, health.lastErrorCode)
        assertEquals("SIGNING", health.lastFailureStage)
    }

    @Test
    fun `a socket exception stays a transport error with the class preserved`() = runBlocking {
        val subject = reporter(
            transport = TelemetryTransport { _, _ -> TelemetryPostOutcome.TransportError("SocketTimeoutException: Read timed out") }
        )
        performLikeProduction(subject)
        val health = TelemetryHealth.snapshot()

        assertEquals(TelemetryFailureCode.TRANSPORT_ERROR.name, health.lastErrorCode)
        assertEquals("NETWORK", health.lastFailureStage)
        assertEquals("SocketTimeoutException", health.lastFailureExceptionClass)
        assertTrue(health.lastFailureDetail!!.contains("Read timed out"))
    }

    @Test
    fun `an http rejection keeps its status and its stage`() = runBlocking {
        val subject = reporter(transport = TelemetryTransport { _, _ -> TelemetryPostOutcome.Rejected(401) })
        performLikeProduction(subject)
        val health = TelemetryHealth.snapshot()

        assertEquals(TelemetryFailureCode.HTTP_ERROR.name, health.lastErrorCode)
        assertEquals("HTTP", health.lastFailureStage)
        assertEquals(401, health.lastHttpStatus)
    }

    @Test
    fun `an insecure origin is not reported as a network failure`() = runBlocking {
        val subject = reporter(transport = TelemetryTransport { _, _ -> TelemetryPostOutcome.InsecureUrl })
        performLikeProduction(subject)
        val health = TelemetryHealth.snapshot()

        assertEquals(TelemetryFailureCode.INSECURE_URL.name, health.lastErrorCode)
        assertEquals("CONFIG", health.lastFailureStage)
        assertTrue(health.lastErrorCode != TelemetryFailureCode.TRANSPORT_ERROR.name)
    }

    @Test
    fun `a failure detail is sanitised and bounded`() {
        val long = "X".repeat(500)
        TelemetryHealth.onAttempt(TelemetryTrigger.PERIODIC)
        TelemetryHealth.onFailure(
            errorCode = TelemetryFailureCode.TRANSPORT_ERROR.name,
            stage = "NETWORK",
            detail = "SocketException: +98 912 000 0000 " + long
        )
        val health = TelemetryHealth.snapshot()

        val detail = health.lastFailureDetail!!
        assertTrue("detail must be bounded", detail.length <= TelemetryHealth.MAX_DETAIL_CHARS)
        assertTrue("a phone-like value must not survive", !detail.contains("912"))
    }

    @Test
    fun `a success clears the previous failure stage and detail`() = runBlocking {
        val failing = reporter(transport = TelemetryTransport { _, _ -> TelemetryPostOutcome.TransportError("boom") })
        performLikeProduction(failing)
        assertTrue(TelemetryHealth.snapshot().lastFailureDetail != null)

        val working = reporter(transport = TelemetryTransport { _, _ -> TelemetryPostOutcome.Accepted(200) })
        performLikeProduction(working)
        val health = TelemetryHealth.snapshot()

        assertNull("a recovered reporter must not still advertise a failure stage", health.lastFailureStage)
        assertNull(health.lastFailureDetail)
        assertNull(health.lastErrorCode)
        assertEquals(2L, health.attempts)
        assertEquals(1L, health.successes)
        assertEquals(1L, health.failures)
    }

    @Test
    fun `every failure code carries a stage`() {
        for (code in TelemetryFailureCode.entries) {
            assertTrue("${code.name} must name a stage", code.stage.isNotBlank())
            assertTrue("${code.name} must have a TELEMETRY_ command code", code.commandCode.startsWith("TELEMETRY_"))
        }
        assertEquals(TelemetryFailureCode.PAYLOAD_BUILD_FAILED.commandCode, "TELEMETRY_PAYLOAD_BUILD_FAILED")
        assertEquals(TelemetryFailureCode.INSECURE_URL.commandCode, "TELEMETRY_INSECURE_URL")
    }
}
