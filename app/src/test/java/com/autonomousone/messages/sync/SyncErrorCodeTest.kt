package com.autonomousone.messages.sync

import com.autonomousone.messages.gateway.health.GatewayFailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The outcome taxonomy (mission §44).
 *
 * The status mapping is the part with a known, expensive history: v3.4.6 shipped a diagnostic
 * that rendered HTTP 400 as "GMweb rejected this device's key", which was false and cost a
 * device-acceptance round. These tests exist so that can never be reintroduced.
 */
class SyncErrorCodeTest {

    private companion object {
        /**
         * The mission's minimum code list (§44), transcribed rather than derived.
         *
         * Written out on purpose: this is the contract the mission states, so the test can tell a
         * deliberate extension apart from a code that appeared by accident. Anything not listed
         * here must be listed as a deliberate addition, or the tripwire fires.
         */
        val MISSION_MINIMUM = setOf(
            SyncErrorCode.GATEWAY_DISABLED,
            SyncErrorCode.CONFIG_MISSING_SERVER_URL,
            SyncErrorCode.IDENTITY_NOT_REGISTERED,
            SyncErrorCode.AUTH_REQUIRED,
            SyncErrorCode.AUTH_EXPIRED,
            SyncErrorCode.DEVICE_REVOKED,
            SyncErrorCode.NETWORK_UNAVAILABLE,
            SyncErrorCode.NETWORK_TIMEOUT,
            SyncErrorCode.SERVER_4XX,
            SyncErrorCode.SERVER_5XX,
            SyncErrorCode.RATE_LIMITED,
            SyncErrorCode.INVALID_EVENT_SCHEMA,
            SyncErrorCode.PAYLOAD_TOO_LARGE,
            SyncErrorCode.HISTORY_GRANT_MISSING,
            SyncErrorCode.HISTORY_KEY_MISSING,
            SyncErrorCode.TELEPHONY_PERMISSION_MISSING,
            SyncErrorCode.TELEPHONY_QUERY_FAILED,
            SyncErrorCode.SIM_NOT_AVAILABLE,
            SyncErrorCode.SMS_SEND_FAILED,
            SyncErrorCode.UNKNOWN
        )
    }

    @Test
    fun `only401IsAnAuthProblemAndOnly403IsAnAuthorizationProblem`() {
        assertEquals(SyncErrorCode.AUTH_REQUIRED, SyncErrorCode.fromHttpStatus(401))
        assertEquals(SyncErrorCode.DEVICE_REVOKED, SyncErrorCode.fromHttpStatus(403))
    }

    @Test
    fun `a400IsNeverReportedAsARejectedCredential`() {
        // The v3.4.6 defect: a malformed REQUEST reported as a rejected KEY.
        val code = SyncErrorCode.fromHttpStatus(400)

        assertEquals(SyncErrorCode.SERVER_4XX, code)
        assertTrue(code != SyncErrorCode.AUTH_REQUIRED)
        assertTrue(code != SyncErrorCode.DEVICE_REVOKED)
        assertTrue(code != SyncErrorCode.AUTH_EXPIRED)
    }

    @Test
    fun `everyOther4xxIsAContractFailureNotAnAuthFailure`() {
        listOf(404, 405, 409, 410, 422, 451).forEach { status ->
            val code = SyncErrorCode.fromHttpStatus(status)
            assertFalse(
                "HTTP $status must not be reported as an auth failure",
                code == SyncErrorCode.AUTH_REQUIRED ||
                    code == SyncErrorCode.DEVICE_REVOKED ||
                    code == SyncErrorCode.AUTH_EXPIRED
            )
        }
    }

    @Test
    fun `413IsPayloadTooLargeAnd422IsInvalidSchema`() {
        assertEquals(SyncErrorCode.PAYLOAD_TOO_LARGE, SyncErrorCode.fromHttpStatus(413))
        assertEquals(SyncErrorCode.INVALID_EVENT_SCHEMA, SyncErrorCode.fromHttpStatus(422))
    }

    @Test
    fun `429And5xxAreRetryableAnd4xxGenerallyIsNot`() {
        assertEquals(SyncErrorCode.RATE_LIMITED, SyncErrorCode.fromHttpStatus(429))
        assertEquals(SyncErrorCode.SERVER_5XX, SyncErrorCode.fromHttpStatus(500))
        assertEquals(SyncErrorCode.SERVER_5XX, SyncErrorCode.fromHttpStatus(503))

        assertTrue(SyncErrorCode.fromHttpStatus(429).isTransient)
        assertTrue(SyncErrorCode.fromHttpStatus(503).isTransient)
        assertFalse(SyncErrorCode.fromHttpStatus(400).isTransient)
        assertFalse(SyncErrorCode.fromHttpStatus(404).isTransient)
    }

    @Test
    fun `anHttpStatusOutranksTheExceptionKind`() {
        // A response that arrived is better evidence than an exception raised on the way.
        assertEquals(
            SyncErrorCode.AUTH_REQUIRED,
            SyncErrorCode.fromFailureKind(GatewayFailureKind.NETWORK_OFFLINE, httpStatus = 401)
        )
        assertEquals(
            SyncErrorCode.NETWORK_UNAVAILABLE,
            SyncErrorCode.fromFailureKind(GatewayFailureKind.NETWORK_OFFLINE, httpStatus = null)
        )
    }

    @Test
    fun `transportKindsMapToOutcomeCodes`() {
        assertEquals(
            SyncErrorCode.NETWORK_UNAVAILABLE,
            SyncErrorCode.fromFailureKind(GatewayFailureKind.DNS)
        )
        assertEquals(
            SyncErrorCode.NETWORK_UNAVAILABLE,
            SyncErrorCode.fromFailureKind(GatewayFailureKind.TCP_CONNECT)
        )
        assertEquals(
            SyncErrorCode.NETWORK_TIMEOUT,
            SyncErrorCode.fromFailureKind(GatewayFailureKind.READ_TIMEOUT)
        )
        assertEquals(
            SyncErrorCode.AUTH_REQUIRED,
            SyncErrorCode.fromFailureKind(GatewayFailureKind.HTTP_AUTH)
        )
        assertEquals(
            SyncErrorCode.DEVICE_REVOKED,
            SyncErrorCode.fromFailureKind(GatewayFailureKind.HTTP_FORBIDDEN)
        )
        assertEquals(
            SyncErrorCode.INVALID_EVENT_SCHEMA,
            SyncErrorCode.fromFailureKind(GatewayFailureKind.VALIDATION_FAILED)
        )
    }

    @Test
    fun `notKnowingIsNotTransient`() {
        // UNKNOWN must not be retried forever by default; the audit's Blocker 6 is exactly an
        // unclassifiable failure looping silently.
        assertFalse(SyncErrorCode.UNKNOWN.isTransient)
        assertFalse(SyncErrorCode.fromFailureKind(GatewayFailureKind.NONE).isTransient)
    }

    @Test
    fun `theTaxonomyIsExactlyTheMissionMinimumPlusTheDeliberateAdditions`() {
        // Tripwire: adding or removing a code must be a conscious act, because every code is
        // something diagnostics can print and support can search for.
        //
        // The mission's minimum, plus the codes this implementation had to add because no minimum
        // code describes the condition honestly:
        //   CRYPTO_KEY_UNAVAILABLE           — a missing device key is not a missing history key
        //   COMMAND_INTERRUPTED_AFTER_SUBMIT — a claimed-then-interrupted send is not a refusal
        //   COMMAND_EXPIRED_BEFORE_CLAIM     — provably never executed is not "we are not sure"
        val deliberateAdditions = setOf(
            SyncErrorCode.CRYPTO_KEY_UNAVAILABLE,
            SyncErrorCode.COMMAND_INTERRUPTED_AFTER_SUBMIT,
            SyncErrorCode.COMMAND_EXPIRED_BEFORE_CLAIM
        )
        assertEquals(
            "the mission's minimum codes plus 3 that had no honest minimum equivalent",
            23,
            SyncErrorCode.entries.size
        )
        assertEquals(
            "and the extras are exactly the ones named above",
            deliberateAdditions,
            SyncErrorCode.entries.toSet() - MISSION_MINIMUM
        )
    }

    @Test
    fun `onlyNetworkShapedCodesAreTransient`() {
        val transient = SyncErrorCode.entries.filter { it.isTransient }.toSet()

        assertEquals(
            setOf(
                SyncErrorCode.NETWORK_UNAVAILABLE,
                SyncErrorCode.NETWORK_TIMEOUT,
                SyncErrorCode.SERVER_5XX,
                SyncErrorCode.RATE_LIMITED
            ),
            transient
        )
    }
}
