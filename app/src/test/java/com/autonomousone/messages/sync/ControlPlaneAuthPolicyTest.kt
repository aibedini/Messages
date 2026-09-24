package com.autonomousone.messages.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mission §44/§56: what a control-plane answer means, and what may be done about it.
 *
 * The defect this pins is destructive and live: `HeartbeatManager` branched on `isAuthError`, which is
 * `401 || 403`, and then always ran `clearCloudCredentials()` + `register()`. A single 403 therefore
 * deleted the device id, the gateway token and the registered marker — the enrollment — in response to
 * a server answer that meant "I know who you are and I refuse you". Re-enrolling is not the fix for a
 * revocation, and the device's own credentials were not the problem.
 */
class ControlPlaneAuthPolicyTest {

    private val now = 1_700_000_000_000L

    private fun decide(
        previous: ControlPlaneAuthState = ControlPlaneAuthState(),
        signal: ControlPlaneAuthSignal,
        httpStatus: Int? = null,
    ) = ControlPlaneAuthPolicy.decide(previous, signal, httpStatus, now)

    // ── 403 must not destroy the enrollment ──────────────────────────────────

    @Test
    fun `aForbiddenAnswerNeverReplacesTheCredential`() {
        // The exact act that was wrong. A refused device holds a perfectly good credential; what it
        // needs is for a human to un-revoke it.
        val decision = decide(signal = ControlPlaneAuthSignal.FORBIDDEN, httpStatus = 403)

        assertFalse(
            "a device the server REFUSES must keep its credentials",
            decision.replaceCredentialAndReEnroll
        )
    }

    @Test
    fun `aForbiddenAnswerIsNotYetCalledRevokedOnTheFirstSighting`() {
        // One 403 can come from an intercepting proxy or a misrouted host. Holding all replication and
        // telling the owner to intervene on that evidence alone is the more expensive error, and the
        // next heartbeat settles it seconds later — so the threshold is two.
        val first = decide(signal = ControlPlaneAuthSignal.FORBIDDEN, httpStatus = 403)

        assertFalse(first.revoked)
        assertEquals(1, first.state.consecutiveRejections)
        assertEquals(0L, first.state.revokedAt)
    }

    @Test
    fun `twoConsecutiveForbiddensAreARevocation`() {
        val first = decide(signal = ControlPlaneAuthSignal.FORBIDDEN, httpStatus = 403)
        val second = decide(first.state, ControlPlaneAuthSignal.FORBIDDEN, 403)

        assertTrue(second.revoked)
        assertEquals(now, second.state.revokedAt)
        assertEquals(2, second.state.consecutiveRejections)
    }

    @Test
    fun `aRevocationKeepsItsOriginalTimestampWhenItIsConfirmedAgain`() {
        // So "revoked since when?" stays answerable; re-stamping it on every heartbeat would turn a
        // standing verdict into a rolling one.
        val revoked = decide(
            ControlPlaneAuthState(revokedAt = now - 10_000, consecutiveRejections = 2),
            ControlPlaneAuthSignal.FORBIDDEN,
            403
        )

        assertEquals(now - 10_000, revoked.state.revokedAt)
    }

    // ── 401 keeps its existing meaning ───────────────────────────────────────

    @Test
    fun `anUnauthorizedAnswerReplacesTheCredentialAndReEnrolls`() {
        val decision = decide(signal = ControlPlaneAuthSignal.UNAUTHORIZED, httpStatus = 401)

        assertTrue(decision.replaceCredentialAndReEnroll)
        assertFalse("a stale credential says nothing about revocation", decision.revoked)
    }

    @Test
    fun `anUnauthorizedAnswerDoesNotLiftAnExistingRevocation`() {
        // Only a success may clear a revocation. "Your credential is stale" is not evidence that the
        // device is allowed again, and treating it as such would silently resume uploading to a server
        // that has refused this device.
        val revoked = ControlPlaneAuthState(revokedAt = now - 1_000, consecutiveRejections = 2)

        val decision = decide(revoked, ControlPlaneAuthSignal.UNAUTHORIZED, 401)

        assertTrue(decision.revoked)
        assertEquals(now - 1_000, decision.state.revokedAt)
    }

    @Test
    fun `anUnauthorizedAnswerResetsTheForbiddenCounter`() {
        // "403, 401, 403" is not two consecutive refusals. Without the reset, a flapping proxy that
        // alternates its answers would accumulate its way to a revocation.
        val first = decide(signal = ControlPlaneAuthSignal.FORBIDDEN, httpStatus = 403)
        val afterUnauthorized = decide(first.state, ControlPlaneAuthSignal.UNAUTHORIZED, 401)
        val thirdForbidden = decide(afterUnauthorized.state, ControlPlaneAuthSignal.FORBIDDEN, 403)

        assertEquals(0, afterUnauthorized.state.consecutiveRejections)
        assertEquals(1, thirdForbidden.state.consecutiveRejections)
        assertFalse("one real refusal, not two", thirdForbidden.revoked)
    }

    // ── Only success proves anything, and it is the only thing that clears ────

    @Test
    fun `aSuccessClearsARevocationAndRecordsWhen`() {
        val revoked = ControlPlaneAuthState(revokedAt = now - 5_000, consecutiveRejections = 3)

        val decision = decide(revoked, ControlPlaneAuthSignal.ACCEPTED)

        assertFalse(decision.revoked)
        assertEquals(0L, decision.state.revokedAt)
        assertEquals(0, decision.state.consecutiveRejections)
        assertEquals(now, decision.state.lastAcceptedAt)
        assertEquals("a lifted revocation must be visible as one", now, decision.state.clearedAt)
    }

    @Test
    fun `aSuccessThatLiftsNothingDoesNotRewriteTheClearedTimestamp`() {
        val alreadyFine = ControlPlaneAuthState(clearedAt = now - 99_000)

        val decision = decide(alreadyFine, ControlPlaneAuthSignal.ACCEPTED)

        assertEquals(now - 99_000, decision.state.clearedAt)
    }

    // ── Inconclusive answers change nothing ──────────────────────────────────

    @Test
    fun `anInconclusiveAnswerChangesNothingAtAll`() {
        // A 400, a 5xx or a timeout proves nothing about the credential. It must not revoke, must not
        // re-enroll, and must not reset the evidence either.
        val state = ControlPlaneAuthState(consecutiveRejections = 1, lastRejectionStatus = 403)

        listOf(null, 400, 429, 500, 503).forEach { status ->
            val decision = decide(state, ControlPlaneAuthSignal.fromHttpStatus(status), status)

            assertEquals("HTTP $status must not change the state", state, decision.state)
            assertFalse(decision.replaceCredentialAndReEnroll)
            assertFalse("HTTP $status must not revoke", decision.revoked)
        }
    }

    @Test
    fun `onlyFourOhOneAndFourOhThreeAreAuthSignals`() {
        // The mapping itself, pinned: 403 is the ONLY status that may lead to a revocation, and 401
        // the only one that may lead to a re-enrollment.
        assertEquals(ControlPlaneAuthSignal.UNAUTHORIZED, ControlPlaneAuthSignal.fromHttpStatus(401))
        assertEquals(ControlPlaneAuthSignal.FORBIDDEN, ControlPlaneAuthSignal.fromHttpStatus(403))
        listOf(null, 200, 400, 404, 409, 429, 500, 502).forEach {
            assertEquals(
                "HTTP $it is not an auth answer",
                ControlPlaneAuthSignal.INCONCLUSIVE,
                ControlPlaneAuthSignal.fromHttpStatus(it)
            )
        }
    }

    // ── The property, over the whole space ───────────────────────────────────

    @Test
    fun `aForbiddenAnswerCanNeverCauseACredentialReplacement`() {
        // Stated over every reachable prior state rather than by example, because this is the
        // destructive act and it must be unreachable from a 403 in all of them.
        for (revokedAt in listOf(0L, now - 1)) {
            for (consecutive in 0..5) {
                for (status in listOf(null, 403, 401)) {
                    val previous = ControlPlaneAuthState(
                        revokedAt = revokedAt,
                        consecutiveRejections = consecutive,
                        lastRejectionStatus = status,
                    )
                    val decision = ControlPlaneAuthPolicy.decide(
                        previous,
                        ControlPlaneAuthSignal.FORBIDDEN,
                        httpStatus = 403,
                        now = now,
                    )
                    assertFalse(
                        "403 with prior=$previous must not replace the credential",
                        decision.replaceCredentialAndReEnroll
                    )
                }
            }
        }
    }

    @Test
    fun `aRevocationOnceSetIsNeverUnsetExceptByASuccess`() {
        val revoked = ControlPlaneAuthState(revokedAt = now - 1, consecutiveRejections = 2)

        listOf(
            ControlPlaneAuthSignal.UNAUTHORIZED,
            ControlPlaneAuthSignal.FORBIDDEN,
            ControlPlaneAuthSignal.INCONCLUSIVE
        ).forEach { signal ->
            assertTrue(
                "$signal must not lift a revocation",
                ControlPlaneAuthPolicy.decide(revoked, signal, 403, now).revoked
            )
        }
        assertFalse(
            "only a success lifts it",
            ControlPlaneAuthPolicy.decide(revoked, ControlPlaneAuthSignal.ACCEPTED, 200, now).revoked
        )
    }
}
