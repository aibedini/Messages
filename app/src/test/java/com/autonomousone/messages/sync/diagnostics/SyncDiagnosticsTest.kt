package com.autonomousone.messages.sync.diagnostics

import com.autonomousone.messages.sync.CommandSyncState
import com.autonomousone.messages.sync.HistorySyncState
import com.autonomousone.messages.sync.ReplicationBlocker
import com.autonomousone.messages.sync.ReplicationInputs
import com.autonomousone.messages.sync.ReplicationPrerequisiteEvaluator
import com.autonomousone.messages.sync.ReplicationState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Diagnostics V2 (mission §56-§58).
 *
 * The two properties worth defending here are that a blocked device always names a cause, and
 * that the export cannot carry sensitive data.
 */
class SyncDiagnosticsTest {

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
    ) = ReplicationInputs(
        gatewayEnabled, consentGranted, serverOriginConfigured, identityRegistered, authRejected,
        deviceRevoked, cryptoKeyAvailable, telephonyPermissionGranted, historyGrantPresent,
        networkValidated
    )

    private fun diagnostics(
        inputs: ReplicationInputs = inputs(),
        deviceIdToken: String? = "abcdef1234567890"
    ): SyncDiagnostics {
        val prerequisites = ReplicationPrerequisiteEvaluator.evaluate(inputs)
        return SyncDiagnostics(
            generatedAt = 1_700_000_000_000L,
            application = SyncDiagnostics.ApplicationSection(
                appVersion = "3.4.9",
                versionCode = 116,
                deviceIdToken = deviceIdToken
            ),
            identity = SyncDiagnostics.IdentitySection(
                registered = true, accountLinked = true, authorized = true, revoked = false
            ),
            gateway = SyncDiagnostics.GatewaySection(
                desired = true, controlConnected = true, lastHeartbeatAt = 1L, lastReconnectAt = 2L
            ),
            replication = SyncDiagnostics.ReplicationSection(
                state = ReplicationState.READY,
                readyCount = 3, inFlightCount = 0, retryWaitCount = 1, deadLetterCount = 0,
                ackedCount = 309, oldestPendingAgeMs = 4_000L,
                lastUploadAttemptAt = 5L, lastSuccessfulUploadAt = 6L, lastHttpStatus = 200,
                lastBatchSize = 100, lastAccepted = 98, lastDuplicates = 2, lastRejected = 0
            ),
            history = SyncDiagnostics.HistorySection(
                state = HistorySyncState.SCANNING,
                grant = "FULL_HISTORY",
                keyVersion = 3,
                sessionId = "session-1",
                sources = listOf(
                    SyncDiagnostics.HistorySourceSection(
                        source = "sms", scanned = 1_000, enqueued = 900, acked = 850,
                        pending = 50, skipped = 100, failed = 0,
                        checkpointDate = 1_600_000_000_000L, checkpointProviderId = 4242L,
                        scanComplete = false
                    )
                )
            ),
            commands = SyncDiagnostics.CommandSection(
                state = CommandSyncState.READY,
                received = 4, claimed = 3, executing = 0, completed = 3, failed = 0,
                lastCommandAt = 7L, lastCommandType = "SEND_SMS"
            ),
            prerequisites = prerequisites
        )
    }

    // ── §57: one actionable blocker, never a bare DEGRADED ──────────────────

    @Test
    fun `aClearDeviceSaysSo`() {
        val line = diagnostics().blockerLine()

        assertTrue(line, line.startsWith("Replication clear"))
        assertNull(diagnostics().currentBlocker)
    }

    @Test
    fun `everyBlockerProducesANamedCauseAndAnAction`() {
        val cases = listOf(
            inputs(gatewayEnabled = false),
            inputs(consentGranted = false),
            inputs(serverOriginConfigured = false),
            inputs(deviceRevoked = true),
            inputs(identityRegistered = false),
            inputs(authRejected = true),
            inputs(cryptoKeyAvailable = false),
            inputs(telephonyPermissionGranted = false),
            inputs(historyGrantPresent = false),
            inputs(networkValidated = false)
        )

        cases.forEach { case ->
            val line = diagnostics(case).blockerLine()
            assertTrue("a blocker must be named: $line", line.contains("Replication blocked: "))
            // The failure this guards: a diagnostic that reports a state without a cause.
            assertFalse("must not be a bare state: $line", line.trim() == "DEGRADED")
            // Every blocked line must carry a second, actionable line.
            assertTrue("must hint an action: $line", line.contains("\n  "))
        }
    }

    @Test
    fun `theBlockerLineNamesTheRealtimeCausesFirst`() {
        // Identity blocks realtime; the history grant blocks history. Realtime wins.
        val line = diagnostics(inputs(identityRegistered = false, historyGrantPresent = false))
            .blockerLine()

        assertTrue(line, line.contains("IDENTITY_NOT_REGISTERED"))
    }

    @Test
    fun `historyOnlyProblemsAreNamedWhenRealtimeIsFine`() {
        val line = diagnostics(inputs(telephonyPermissionGranted = false)).blockerLine()

        assertTrue(line, line.contains("TELEPHONY_PERMISSION_MISSING"))
    }

    // ── Blocker naming ───────────────────────────────────────────────────────

    @Test
    fun `everyBlockerHasAStableScreamingSnakeCodeName`() {
        val expected = mapOf(
            ReplicationBlocker.GatewayDisabled to "GATEWAY_DISABLED",
            ReplicationBlocker.ConsentRevoked to "CONSENT_REVOKED",
            ReplicationBlocker.MissingServerUrl to "MISSING_SERVER_URL",
            ReplicationBlocker.DeviceRevoked to "DEVICE_REVOKED",
            ReplicationBlocker.IdentityNotRegistered to "IDENTITY_NOT_REGISTERED",
            ReplicationBlocker.AuthenticationRequired to "AUTHENTICATION_REQUIRED",
            ReplicationBlocker.MissingCryptoKey to "MISSING_CRYPTO_KEY",
            ReplicationBlocker.TelephonyPermissionMissing to "TELEPHONY_PERMISSION_MISSING",
            ReplicationBlocker.MissingHistoryGrant to "MISSING_HISTORY_GRANT",
            ReplicationBlocker.NetworkUnavailable to "NETWORK_UNAVAILABLE"
        )

        assertEquals(ReplicationBlocker.ALL.toSet(), expected.keys)
        expected.forEach { (blocker, name) ->
            assertEquals(name, SyncDiagnosticsText.codeName(blocker))
        }
    }

    @Test
    fun `sharingAnErrorCodeDoesNotMakeTwoBlockersIndistinguishable`() {
        // Both are "the gateway is off" to a user, but support needs to tell them apart.
        assertEquals(
            ReplicationBlocker.GatewayDisabled.code,
            ReplicationBlocker.ConsentRevoked.code
        )
        assertTrue(
            SyncDiagnosticsText.codeName(ReplicationBlocker.GatewayDisabled) !=
                SyncDiagnosticsText.codeName(ReplicationBlocker.ConsentRevoked)
        )
    }

    @Test
    fun `screamingSnakeDoesNotSplitAnAcronymOrTrailingCapital`() {
        assertEquals("MISSING_SERVER_URL", SyncDiagnosticsText.screamingSnake("MissingServerUrl"))
        assertEquals("IDENTITY_NOT_REGISTERED", SyncDiagnosticsText.screamingSnake("IdentityNotRegistered"))
        assertEquals("SIMPLE", SyncDiagnosticsText.screamingSnake("Simple"))
    }

    // ── §58: the export cannot leak ─────────────────────────────────────────

    @Test
    fun `theExportContainsTheMissionSections`() {
        val json = JSONObject(diagnostics().toSanitizedJson())

        listOf("application", "identity", "gateway", "replication", "history", "commands", "blockers")
            .forEach { key -> assertTrue("missing section $key", json.has(key)) }
        assertEquals(
            "SCANNING",
            json.getJSONObject("history").getString("state")
        )
        assertEquals(
            "IDENTITY_NOT_REGISTERED",
            JSONObject(diagnostics(inputs(identityRegistered = false)).toSanitizedJson())
                .getJSONObject("blockers").getString("primary")
        )
    }

    @Test
    fun `theExportTruncatesTheDeviceToken`() {
        val json = JSONObject(diagnostics(deviceIdToken = "abcdef1234567890").toSanitizedJson())
        val exported = json.getJSONObject("application").getString("deviceId")

        assertEquals("abcdef12", exported)
        assertFalse(
            "the full token must never be exported",
            json.toString().contains("abcdef1234567890")
        )
    }

    @Test
    fun `theExportCannotCarryMessageContentCredentialsOrPhoneNumbers`() {
        val json = diagnostics().toSanitizedJson().lowercase()

        // Nothing in the model can hold these, so this guards against a future field being added
        // with one of these names rather than against a formatting mistake.
        listOf(
            "body", "ciphertext", "address", "phone", "msisdn", "token", "apikey",
            "api_key", "secret", "signature", "privatekey", "plaintext"
        ).forEach { forbidden ->
            assertFalse("export must not contain `$forbidden`", json.contains(forbidden))
        }
    }

    @Test
    fun `unknownIsExportedAsNullAndNeverAsZero`() {
        val unknown = SyncDiagnostics(
            generatedAt = 1L,
            application = SyncDiagnostics.ApplicationSection(null, null, null),
            identity = SyncDiagnostics.IdentitySection(null, null, null, null),
            gateway = SyncDiagnostics.GatewaySection(null, null, null, null),
            replication = SyncDiagnostics.ReplicationSection(
                state = ReplicationState.READY,
                readyCount = null, inFlightCount = null, retryWaitCount = null,
                deadLetterCount = null, ackedCount = null, oldestPendingAgeMs = null,
                lastUploadAttemptAt = null, lastSuccessfulUploadAt = null, lastHttpStatus = null,
                lastBatchSize = null, lastAccepted = null, lastDuplicates = null, lastRejected = null
            ),
            history = SyncDiagnostics.HistorySection(
                state = HistorySyncState.NOT_STARTED,
                grant = null,
                keyVersion = null,
                sessionId = null,
                sources = emptyList()
            ),
            commands = SyncDiagnostics.CommandSection(
                state = CommandSyncState.READY,
                received = null, claimed = null, executing = null, completed = null, failed = null,
                lastCommandAt = null, lastCommandType = null
            ),
            prerequisites = ReplicationPrerequisiteEvaluator.evaluate(inputs())
        )

        val json = JSONObject(unknown.toSanitizedJson())
        assertTrue(json.getJSONObject("replication").isNull("readyCount"))
        assertTrue(json.getJSONObject("identity").isNull("registered"))
        assertTrue(json.getJSONObject("gateway").isNull("lastHeartbeatAt"))
        assertTrue(json.getJSONObject("application").isNull("deviceId"))
        // A measured zero must remain distinguishable from unknown.
        val measured = JSONObject(diagnostics().toSanitizedJson())
        assertEquals(0, measured.getJSONObject("replication").getInt("inFlightCount"))
    }

    @Test
    fun `theExportIsDeterministic`() {
        assertEquals(diagnostics().toSanitizedJson(), diagnostics().toSanitizedJson())
    }
}
