package com.autonomousone.messages.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The upload loop's hold decision.
 *
 * This replaces `EventUploaderGateTest`, which pinned a private enum's tag strings. The ordering
 * it cared about (a disabled gateway outranks a missing URL, which outranks a missing identity) is
 * still asserted — now as blocker priority, which is the one place that ordering lives.
 *
 * The most important test here is [the gate agrees with the evaluator for every input
 * combination]: it is what makes "consume the evaluator" true rather than aspirational, and it
 * fails if the gate ever starts re-deriving its own answer.
 */
class ReplicationUploadGateTest {

    private fun inputs(
        gatewayDesired: Boolean = true,
        consentGranted: Boolean = true,
        serverOriginConfigured: Boolean = true,
        identityRegistered: Boolean = true,
        supervisorOffline: Boolean = false,
        authenticationRejected: Boolean = false
    ) = UploadGateInputs(
        gatewayDesired = gatewayDesired,
        consentGranted = consentGranted,
        serverOriginConfigured = serverOriginConfigured,
        identityRegistered = identityRegistered,
        supervisorOffline = supervisorOffline,
        authenticationRejected = authenticationRejected
    )

    // ── The happy path ───────────────────────────────────────────────────────

    @Test
    fun `aReadyLoopIsNotHeld`() {
        assertNull(ReplicationUploadGate.hold(inputs()))
    }

    // ── Each condition, in the order the old gate reported them ──────────────

    @Test
    fun `aGatewayTheUserTurnedOffIsTheFirstReportedCause`() {
        val hold = ReplicationUploadGate.hold(
            inputs(gatewayDesired = false, serverOriginConfigured = false, identityRegistered = false)
        )

        assertEquals(ReplicationBlocker.GatewayDisabled, hold)
    }

    @Test
    fun `withdrawnConsentHoldsTheLoop`() {
        assertEquals(
            ReplicationBlocker.ConsentRevoked,
            ReplicationUploadGate.hold(inputs(consentGranted = false))
        )
    }

    @Test
    fun `aBlankOriginIsReportedOnceTheGatewayIsOn`() {
        assertEquals(
            ReplicationBlocker.MissingServerUrl,
            ReplicationUploadGate.hold(inputs(serverOriginConfigured = false))
        )
    }

    @Test
    fun `anUnenrolledDeviceIsReportedAfterTheOriginIsSet`() {
        assertEquals(
            ReplicationBlocker.IdentityNotRegistered,
            ReplicationUploadGate.hold(inputs(identityRegistered = false))
        )
    }

    @Test
    fun `offlineHoldsTheLoopSoNoRequestsLeaveTheDevice`() {
        // The supervisor leaves the uploader running while offline and relies on a transmission
        // gate to keep the radio quiet, so this must hold.
        assertEquals(
            ReplicationBlocker.NetworkUnavailable,
            ReplicationUploadGate.hold(inputs(supervisorOffline = true))
        )
        assertTrue(ReplicationBlocker.NetworkUnavailable.category in ReplicationUploadGate.HOLDING_CATEGORIES)
    }

    @Test
    fun `aRejectedCredentialHoldsTheLoopInsteadOfDeadLetteringBatches`() {
        assertEquals(
            ReplicationBlocker.AuthenticationRequired,
            ReplicationUploadGate.hold(inputs(authenticationRejected = true))
        )
    }

    // ── The exclusions are real, not assumed ─────────────────────────────────

    @Test
    fun `theExcludedCategoriesCannotAffectTheDecision`() {
        // KEY / PERMISSION / GRANT are excluded because they are expensive or history-only. That
        // is only safe if their inputs are genuinely unable to influence the result, which is what
        // this asserts: `hold` never consults them because their categories are not in the set.
        val excluded = setOf(
            BlockerCategory.KEY,
            BlockerCategory.PERMISSION,
            BlockerCategory.GRANT
        )
        assertTrue(ReplicationUploadGate.HOLDING_CATEGORIES.intersect(excluded).isEmpty())

        // And every blocking input the gate DOES accept maps to an included category.
        val reachable = listOf(
            inputs(gatewayDesired = false),
            inputs(consentGranted = false),
            inputs(serverOriginConfigured = false),
            inputs(identityRegistered = false),
            inputs(supervisorOffline = true),
            inputs(authenticationRejected = true)
        ).mapNotNull { ReplicationUploadGate.hold(it) }

        assertTrue(reachable.isNotEmpty())
        reachable.forEach { blocker ->
            assertTrue(
                "${blocker::class.simpleName} must be a holding category",
                blocker.category in ReplicationUploadGate.HOLDING_CATEGORIES
            )
        }
    }

    @Test
    fun `historyOnlyProblemsNeverHoldRealtimeUpload`() {
        // Telephony permission and the history grant gate HISTORY. Realtime upload never reads
        // the Provider, so neither may stop it — asserted through the evaluator, which is the
        // same source the gate uses.
        val prerequisites = ReplicationPrerequisiteEvaluator.evaluate(
            ReplicationInputs(
                gatewayEnabled = true, consentGranted = true, serverOriginConfigured = true,
                identityRegistered = true, authRejected = false, deviceRevoked = false,
                cryptoKeyAvailable = false, telephonyPermissionGranted = false,
                historyGrantPresent = false, networkValidated = true
            )
        )

        // The evaluator DOES report those blockers...
        assertTrue(prerequisites.blockers.contains(ReplicationBlocker.MissingCryptoKey))
        assertTrue(prerequisites.blockers.contains(ReplicationBlocker.TelephonyPermissionMissing))
        assertTrue(prerequisites.blockers.contains(ReplicationBlocker.MissingHistoryGrant))
        // ...and none of them is a category the gate acts on.
        listOf(
            ReplicationBlocker.MissingCryptoKey,
            ReplicationBlocker.TelephonyPermissionMissing,
            ReplicationBlocker.MissingHistoryGrant
        ).forEach { blocker ->
            assertFalse(
                "${blocker::class.simpleName} must not hold realtime upload",
                blocker.category in ReplicationUploadGate.HOLDING_CATEGORIES
            )
        }
    }

    // ── Consistency with the one evaluator ───────────────────────────────────

    @Test
    fun `theGateAgreesWithTheEvaluatorForEveryInputCombination`() {
        val booleans = listOf(false, true)
        var checked = 0

        for (desired in booleans) {
            for (consent in booleans) {
                for (origin in booleans) {
                    for (identity in booleans) {
                        for (offline in booleans) {
                            for (rejected in booleans) {
                                val gateInputs = inputs(
                                    gatewayDesired = desired, consentGranted = consent,
                                    serverOriginConfigured = origin, identityRegistered = identity,
                                    supervisorOffline = offline, authenticationRejected = rejected
                                )

                                val expected = ReplicationPrerequisiteEvaluator.evaluate(
                                    ReplicationInputs(
                                        gatewayEnabled = desired, consentGranted = consent,
                                        serverOriginConfigured = origin,
                                        identityRegistered = identity, authRejected = rejected,
                                        deviceRevoked = false, cryptoKeyAvailable = true,
                                        telephonyPermissionGranted = true,
                                        historyGrantPresent = true,
                                        networkValidated = !offline
                                    )
                                ).realtimeBlocker?.takeIf {
                                    it.category in ReplicationUploadGate.HOLDING_CATEGORIES
                                }

                                assertEquals(
                                    "gate disagreed with the evaluator for $gateInputs",
                                    expected,
                                    ReplicationUploadGate.hold(gateInputs)
                                )
                                checked++
                            }
                        }
                    }
                }
            }
        }

        assertEquals(64, checked)
    }

    @Test
    fun `theHoldingCategoriesAreStableAndDeliberate`() {
        // Tripwire: widening or narrowing what may stop the upload loop is a behaviour change and
        // must be a conscious one.
        assertEquals(
            setOf(
                BlockerCategory.SWITCHED_OFF,
                BlockerCategory.CONFIGURATION,
                BlockerCategory.IDENTITY,
                BlockerCategory.AUTHORIZATION,
                BlockerCategory.NETWORK
            ),
            ReplicationUploadGate.HOLDING_CATEGORIES
        )
    }
}
