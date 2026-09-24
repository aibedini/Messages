package com.autonomousone.messages.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The prerequisite contract (mission §67).
 *
 * Each test breaks exactly ONE input away from a fully-configured device, so a failure names the
 * prerequisite that changed rather than "something in the evaluator".
 */
class ReplicationPrerequisiteEvaluatorTest {

    private fun inputs(
        gatewayEnabled: Boolean = true,
        consentGranted: Boolean = true,
        serverOriginConfigured: Boolean = true,
        identityRegistered: Boolean = true,
        authRejected: Boolean = false,
        deviceRevoked: Boolean = false,
        cryptoKeyAvailable: Boolean = true,
        telephonyPermissionGranted: Boolean = true,
        historyGrantPresent: Boolean = true,
        networkValidated: Boolean = true
    ): ReplicationInputs = ReplicationInputs(
        gatewayEnabled = gatewayEnabled,
        consentGranted = consentGranted,
        serverOriginConfigured = serverOriginConfigured,
        identityRegistered = identityRegistered,
        authRejected = authRejected,
        deviceRevoked = deviceRevoked,
        cryptoKeyAvailable = cryptoKeyAvailable,
        telephonyPermissionGranted = telephonyPermissionGranted,
        historyGrantPresent = historyGrantPresent,
        networkValidated = networkValidated
    )

    private fun evaluate(vararg overrides: Pair<String, Boolean>): ReplicationPrerequisites {
        var input = inputs()
        overrides.forEach { (name, value) ->
            input = when (name) {
                "gatewayEnabled" -> input.copy(gatewayEnabled = value)
                "consentGranted" -> input.copy(consentGranted = value)
                "serverOriginConfigured" -> input.copy(serverOriginConfigured = value)
                "identityRegistered" -> input.copy(identityRegistered = value)
                "authRejected" -> input.copy(authRejected = value)
                "deviceRevoked" -> input.copy(deviceRevoked = value)
                "cryptoKeyAvailable" -> input.copy(cryptoKeyAvailable = value)
                "telephonyPermissionGranted" -> input.copy(telephonyPermissionGranted = value)
                "historyGrantPresent" -> input.copy(historyGrantPresent = value)
                "networkValidated" -> input.copy(networkValidated = value)
                else -> error("unknown input $name")
            }
        }
        return ReplicationPrerequisiteEvaluator.evaluate(input)
    }

    // ── Healthy ──────────────────────────────────────────────────────────────

    @Test
    fun `aFullyConfiguredDeviceCanDoEverything`() {
        val result = evaluate()

        assertTrue(result.canUploadRealtime)
        assertTrue(result.canUploadHistory)
        assertTrue(result.canReceiveCommands)
        assertTrue(result.blockers.isEmpty())
        assertTrue(result.isFullyClear)
        assertTrue(result.isRealtimeOperational)
        assertTrue(result.isControlPlaneOperational)
        assertNull(result.primaryBlocker)
        assertNull(result.primaryCode)
    }

    // ── Each prerequisite alone ──────────────────────────────────────────────

    @Test
    fun `gatewayDisabledBlocksEverythingAndSaysSo`() {
        val result = evaluate("gatewayEnabled" to false)

        assertFalse(result.canUploadRealtime)
        assertFalse(result.canUploadHistory)
        assertFalse(result.canReceiveCommands)
        assertEquals(ReplicationBlocker.GatewayDisabled, result.primaryBlocker)
        assertEquals(SyncErrorCode.GATEWAY_DISABLED, result.primaryCode)
    }

    @Test
    fun `consentWithdrawnBlocksEverything`() {
        val result = evaluate("consentGranted" to false)

        assertFalse(result.canUploadRealtime)
        assertEquals(ReplicationBlocker.ConsentRevoked, result.realtimeBlocker)
        // Revoking consent is a switch, not a fault: it must not read as an error.
        assertEquals(SyncErrorCode.GATEWAY_DISABLED, result.primaryCode)
    }

    @Test
    fun `missingServerUrlBlocksOutboundAndIsAConfigurationProblem`() {
        val result = evaluate("serverOriginConfigured" to false)

        assertFalse(result.canUploadRealtime)
        assertEquals(ReplicationBlocker.MissingServerUrl, result.realtimeBlocker)
        assertEquals(SyncErrorCode.CONFIG_MISSING_SERVER_URL, result.primaryCode)
    }

    @Test
    fun `missingIdentityBlocksOutbound`() {
        val result = evaluate("identityRegistered" to false)

        assertFalse(result.canUploadRealtime)
        assertEquals(ReplicationBlocker.IdentityNotRegistered, result.realtimeBlocker)
        assertEquals(SyncErrorCode.IDENTITY_NOT_REGISTERED, result.primaryCode)
    }

    @Test
    fun `aRejectedCredentialBlocksOutbound`() {
        val result = evaluate("authRejected" to true)

        assertFalse(result.canUploadRealtime)
        assertEquals(ReplicationBlocker.AuthenticationRequired, result.realtimeBlocker)
        assertEquals(SyncErrorCode.AUTH_REQUIRED, result.primaryCode)
    }

    @Test
    fun `aRevokedDeviceIsReportedAsRevokedNotAsMissingIdentity`() {
        // A revoked device that still holds an enrollment must not be told to re-enroll.
        val result = evaluate("deviceRevoked" to true)

        assertFalse(result.canUploadRealtime)
        assertEquals(ReplicationBlocker.DeviceRevoked, result.realtimeBlocker)
        assertEquals(SyncErrorCode.DEVICE_REVOKED, result.primaryCode)
    }

    @Test
    fun `missingKeyBlocksAndIsNeverResolvedByDowngradingCrypto`() {
        val result = evaluate("cryptoKeyAvailable" to false)

        assertFalse(result.canUploadRealtime)
        assertEquals(ReplicationBlocker.MissingCryptoKey, result.realtimeBlocker)
        assertEquals(SyncErrorCode.CRYPTO_KEY_UNAVAILABLE, result.primaryCode)
        // The key block is not transient: waiting cannot conjure key material.
        assertFalse(result.primaryCode!!.isTransient)
    }

    @Test
    fun `networkUnavailableIsTheOnlyBlockerThatResolvesByWaiting`() {
        val result = evaluate("networkValidated" to false)

        assertFalse(result.canUploadRealtime)
        assertEquals(ReplicationBlocker.NetworkUnavailable, result.realtimeBlocker)
        assertEquals(SyncErrorCode.NETWORK_UNAVAILABLE, result.primaryCode)
        assertTrue(result.primaryCode!!.isTransient)
    }

    // ── History-only prerequisites ───────────────────────────────────────────

    @Test
    fun `missingTelephonyPermissionBlocksOnlyHistory`() {
        val result = evaluate("telephonyPermissionGranted" to false)

        // History cannot be read, but realtime upload does not need READ_SMS.
        assertTrue(result.canUploadRealtime)
        assertTrue(result.canReceiveCommands)
        assertFalse(result.canUploadHistory)
        assertEquals(ReplicationBlocker.TelephonyPermissionMissing, result.historyBlocker)
        assertEquals(SyncErrorCode.TELEPHONY_PERMISSION_MISSING, result.primaryCode)
    }

    @Test
    fun `missingHistoryGrantBlocksOnlyHistory`() {
        val result = evaluate("historyGrantPresent" to false)

        assertTrue(result.canUploadRealtime)
        assertFalse(result.canUploadHistory)
        assertEquals(ReplicationBlocker.MissingHistoryGrant, result.historyBlocker)
        assertEquals(SyncErrorCode.HISTORY_GRANT_MISSING, result.primaryCode)
    }

    @Test
    fun `realtimeStaysOperationalWhileHistoryIsBlockedOnBothHistoryInputs`() {
        val result = evaluate(
            "telephonyPermissionGranted" to false,
            "historyGrantPresent" to false
        )

        assertTrue(result.isRealtimeOperational)
        assertFalse(result.canUploadHistory)
        // Both history causes are reported, not just the first one found.
        assertTrue(result.blockers.contains(ReplicationBlocker.TelephonyPermissionMissing))
        assertTrue(result.blockers.contains(ReplicationBlocker.MissingHistoryGrant))
        // Per the documented priority, permission is the more fundamental thing to fix.
        assertEquals(ReplicationBlocker.TelephonyPermissionMissing, result.historyBlocker)
    }

    // ── Ordering ─────────────────────────────────────────────────────────────

    @Test
    fun `primaryBlockerPrefersRealtimeOverHistory`() {
        val result = evaluate(
            "identityRegistered" to false,
            "historyGrantPresent" to false
        )

        assertEquals(ReplicationBlocker.IdentityNotRegistered, result.realtimeBlocker)
        assertEquals(ReplicationBlocker.IdentityNotRegistered, result.historyBlocker)
        assertEquals(ReplicationBlocker.IdentityNotRegistered, result.primaryBlocker)
    }

    @Test
    fun `theEarliestBlockerIsStableWhenSeveralApply`() {
        val result = evaluate(
            "serverOriginConfigured" to false,
            "identityRegistered" to false,
            "networkValidated" to false
        )

        // Configuration outranks identity outranks network, regardless of set iteration order.
        assertEquals(ReplicationBlocker.MissingServerUrl, result.realtimeBlocker)
        assertEquals(SyncErrorCode.CONFIG_MISSING_SERVER_URL, result.primaryCode)
    }

    @Test
    fun `blockedAndClearAreMutuallyExclusive`() {
        assertTrue(evaluate().isFullyClear)
        assertFalse(evaluate("networkValidated" to false).isFullyClear)
        // A history-only block still counts as not-clear, even though realtime works.
        assertFalse(evaluate("historyGrantPresent" to false).isFullyClear)
    }

    // ── Tripwires ────────────────────────────────────────────────────────────

    @Test
    fun `theBlockerListIsOrderedAndComplete`() {
        val priorities = ReplicationBlocker.ALL.map { it.priority }
        assertEquals(
            "ALL must be declared in resolution order",
            priorities.sorted(),
            priorities
        )
        // Tripwire: adding a blocker must force a conscious update here and in ALL.
        assertEquals(10, ReplicationBlocker.ALL.size)
        // The only codes two blockers may share are the switched-off pair: from the user's point
        // of view "the gateway is off" and "consent was withdrawn" are the same headline, and the
        // blocker itself carries the distinction for diagnostics.
        val sharedCodes = ReplicationBlocker.ALL.groupBy { it.code }.filterValues { it.size > 1 }
        assertEquals(setOf(SyncErrorCode.GATEWAY_DISABLED), sharedCodes.keys)
    }

    @Test
    fun `everyDeclaredBlockerIsProducibleBySomeInput`() {
        val produced = setOf(
            evaluate("gatewayEnabled" to false).realtimeBlocker,
            evaluate("consentGranted" to false).realtimeBlocker,
            evaluate("serverOriginConfigured" to false).realtimeBlocker,
            evaluate("deviceRevoked" to true).realtimeBlocker,
            evaluate("identityRegistered" to false).realtimeBlocker,
            evaluate("authRejected" to true).realtimeBlocker,
            evaluate("cryptoKeyAvailable" to false).realtimeBlocker,
            evaluate("telephonyPermissionGranted" to false).historyBlocker,
            evaluate("historyGrantPresent" to false).historyBlocker,
            evaluate("networkValidated" to false).realtimeBlocker
        )

        assertEquals(
            "every blocker in ALL must be reachable from some input",
            ReplicationBlocker.ALL.toSet(),
            produced
        )
    }
}
