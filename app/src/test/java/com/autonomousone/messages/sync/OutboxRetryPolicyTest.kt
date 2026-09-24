package com.autonomousone.messages.sync

import com.autonomousone.messages.gateway.health.GatewayFailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The batch-failure rule (mission §17, §67 "Retry").
 *
 * Every status the mission names is asserted here, because the previous rule was a bare boolean
 * in the upload loop and its most damaging case — a 401 dead-lettering the whole batch — was not
 * visible to any test.
 */
class OutboxRetryPolicyTest {

    private fun action(status: Int?) =
        OutboxRetryPolicy.actionFor(status, GatewayFailureKind.classify(httpStatus = status))

    // ── The cases that must never kill a row ─────────────────────────────────

    @Test
    fun `arejectedCredentialNeverDeadLettersTheBatch`() {
        // Mission §17: set BLOCKED_AUTH and wait. A 401 says nothing about the events.
        assertEquals(OutboxRetryPolicy.Action.RETRY, action(401))
        assertEquals(OutboxRetryPolicy.Action.RETRY, action(403))
    }

    @Test
    fun `payloadTooLargeIsRetriedSoTheNextBatchCanBeSmaller`() {
        // 413 means the batch is too big, not that the events are unacceptable.
        assertEquals(OutboxRetryPolicy.Action.RETRY, action(413))
    }

    @Test
    fun `rateLimitingAndServerFailureAreRetried`() {
        assertEquals(OutboxRetryPolicy.Action.RETRY, action(429))
        assertEquals(OutboxRetryPolicy.Action.RETRY, action(500))
        assertEquals(OutboxRetryPolicy.Action.RETRY, action(503))
    }

    @Test
    fun `aConflictIsRetriedBecauseTheServerMayAlreadyHaveTheState`() {
        // 409 is the duplicate-ish case; killing the row would discard an accepted event.
        assertEquals(OutboxRetryPolicy.Action.RETRY, action(409))
    }

    @Test
    fun `aTransportFailureIsAlwaysRetryable`() {
        // Nothing about the request was judged, so nothing may be concluded from it.
        listOf(
            GatewayFailureKind.DNS,
            GatewayFailureKind.TCP_CONNECT,
            GatewayFailureKind.TLS,
            GatewayFailureKind.READ_TIMEOUT,
            GatewayFailureKind.WRITE_TIMEOUT,
            GatewayFailureKind.NETWORK_OFFLINE
        ).forEach { kind ->
            assertEquals(kind.name, OutboxRetryPolicy.Action.RETRY, OutboxRetryPolicy.actionFor(null, kind))
        }
    }

    // ── The cases that may ───────────────────────────────────────────────────

    @Test
    fun `aContractFailureTheServerWillRepeatDeadLetters`() {
        // 400/404/405/422: resending the identical batch cannot succeed.
        listOf(400, 404, 405, 422, 451).forEach { status ->
            assertEquals("HTTP $status", OutboxRetryPolicy.Action.DEAD_LETTER, action(status))
        }
    }

    @Test
    fun `anUnclassified2xxIsNotAVerdictOnTheEvents`() {
        assertEquals(OutboxRetryPolicy.Action.RETRY, action(200))
        assertEquals(OutboxRetryPolicy.Action.RETRY, action(204))
    }

    // ── The attempt cap ──────────────────────────────────────────────────────

    @Test
    fun `retriesAreBoundedButGenerous`() {
        assertFalse(OutboxRetryPolicy.exhausted(0))
        assertFalse(OutboxRetryPolicy.exhausted(OutboxRetryPolicy.MAX_ATTEMPTS - 1))
        assertTrue(OutboxRetryPolicy.exhausted(OutboxRetryPolicy.MAX_ATTEMPTS))
        assertTrue(OutboxRetryPolicy.exhausted(OutboxRetryPolicy.MAX_ATTEMPTS + 5))
        // With a five-minute backoff cap this spans hours, so a real outage is ridden out.
        assertTrue(OutboxRetryPolicy.MAX_ATTEMPTS >= 20)
    }

    // ── Codes ────────────────────────────────────────────────────────────────

    @Test
    fun `eachFailureRecordsAStructuredCode`() {
        assertEquals(SyncErrorCode.AUTH_REQUIRED, OutboxRetryPolicy.codeFor(401, GatewayFailureKind.HTTP_AUTH))
        assertEquals(SyncErrorCode.DEVICE_REVOKED, OutboxRetryPolicy.codeFor(403, GatewayFailureKind.HTTP_FORBIDDEN))
        assertEquals(SyncErrorCode.PAYLOAD_TOO_LARGE, OutboxRetryPolicy.codeFor(413, GatewayFailureKind.HTTP_BAD_REQUEST))
        assertEquals(SyncErrorCode.RATE_LIMITED, OutboxRetryPolicy.codeFor(429, GatewayFailureKind.HTTP_RATE_LIMITED))
        assertEquals(SyncErrorCode.SERVER_5XX, OutboxRetryPolicy.codeFor(503, GatewayFailureKind.HTTP_SERVER))
        assertEquals(
            SyncErrorCode.NETWORK_UNAVAILABLE,
            OutboxRetryPolicy.codeFor(null, GatewayFailureKind.NETWORK_OFFLINE)
        )
    }

    @Test
    fun `aShippedRegressionIsPinned`() {
        // The exact defect from the audit: 401 must not be treated as permanent.
        val previousRule = { status: Int? -> status != null && status in 400..499 && status != 429 }
        assertTrue("the old rule killed the batch on 401", previousRule(401))
        assertEquals(OutboxRetryPolicy.Action.RETRY, action(401))
    }
}
