package com.autonomousone.messages.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The state models (mission §7) and the two distinctions that matter most:
 * SCAN_COMPLETE is not SYNC_COMPLETE (§26), and "offline" is not "misconfigured".
 */
class SyncStatesTest {

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

    private fun prerequisites(inputs: ReplicationInputs = inputs()) =
        ReplicationPrerequisiteEvaluator.evaluate(inputs)

    // ── Replication ──────────────────────────────────────────────────────────

    @Test
    fun `anIdleClearDeviceIsReadyNotUploading`() {
        assertEquals(
            ReplicationState.READY,
            SyncStateMachine.replication(prerequisites())
        )
    }

    @Test
    fun `activityDistinguishesReadyFromUploadingAndBackingOff`() {
        val ready = prerequisites()
        assertEquals(
            ReplicationState.UPLOADING,
            SyncStateMachine.replication(ready, SyncActivity(uploadInFlight = true))
        )
        assertEquals(
            ReplicationState.BACKING_OFF,
            SyncStateMachine.replication(ready, SyncActivity(backingOff = true))
        )
    }

    @Test
    fun `eachBlockerCategoryMapsToItsOwnReplicationState`() {
        assertEquals(
            ReplicationState.DISABLED,
            SyncStateMachine.replication(prerequisites(inputs(gatewayEnabled = false)))
        )
        assertEquals(
            ReplicationState.DISABLED,
            SyncStateMachine.replication(prerequisites(inputs(consentGranted = false)))
        )
        assertEquals(
            ReplicationState.BLOCKED_CONFIGURATION,
            SyncStateMachine.replication(prerequisites(inputs(serverOriginConfigured = false)))
        )
        assertEquals(
            ReplicationState.BLOCKED_IDENTITY,
            SyncStateMachine.replication(prerequisites(inputs(identityRegistered = false)))
        )
        assertEquals(
            ReplicationState.BLOCKED_AUTH,
            SyncStateMachine.replication(prerequisites(inputs(authRejected = true)))
        )
        assertEquals(
            ReplicationState.BLOCKED_AUTH,
            SyncStateMachine.replication(prerequisites(inputs(deviceRevoked = true)))
        )
        assertEquals(
            ReplicationState.BLOCKED_KEY,
            SyncStateMachine.replication(prerequisites(inputs(cryptoKeyAvailable = false)))
        )
    }

    @Test
    fun `noNetworkIsDegradedRatherThanAMisconfiguration`() {
        // Offline is a wait. Reporting it as BLOCKED_CONFIGURATION would send a user to Settings
        // to fix a URL that is already correct.
        assertEquals(
            ReplicationState.DEGRADED,
            SyncStateMachine.replication(prerequisites(inputs(networkValidated = false)))
        )
    }

    @Test
    fun `aHistoryOnlyBlockerLeavesRealtimeReady`() {
        val result = prerequisites(inputs(historyGrantPresent = false))

        assertEquals(ReplicationState.READY, SyncStateMachine.replication(result))
    }

    // ── History: the §26 distinction ─────────────────────────────────────────

    @Test
    fun `aFinishedScanWithUnackedWorkIsCatchingUpNotCaughtUp`() {
        val result = prerequisites()

        assertEquals(
            HistorySyncState.CATCHING_UP,
            SyncStateMachine.history(
                result,
                SyncActivity(historyScanComplete = true, historyAcknowledgedAll = false)
            )
        )
    }

    @Test
    fun `onlyAcknowledgementOfEverythingIsCaughtUp`() {
        val result = prerequisites()

        assertEquals(
            HistorySyncState.CAUGHT_UP,
            SyncStateMachine.history(
                result,
                SyncActivity(historyScanComplete = true, historyAcknowledgedAll = true)
            )
        )
    }

    @Test
    fun `scanningIsReportedWhileTheProviderIsBeingRead`() {
        assertEquals(
            HistorySyncState.SCANNING,
            SyncStateMachine.history(prerequisites(), SyncActivity(historyScanning = true))
        )
    }

    @Test
    fun `historyBlockersHaveTheirOwnWaitingStates`() {
        assertEquals(
            HistorySyncState.WAITING_FOR_PERMISSION,
            SyncStateMachine.history(prerequisites(inputs(telephonyPermissionGranted = false)))
        )
        assertEquals(
            HistorySyncState.WAITING_FOR_HISTORY_GRANT,
            SyncStateMachine.history(prerequisites(inputs(historyGrantPresent = false)))
        )
    }

    @Test
    fun `aDisabledGatewayLeavesHistoryNotStartedUnlessASessionExists`() {
        val disabled = prerequisites(inputs(gatewayEnabled = false))

        assertEquals(
            HistorySyncState.NOT_STARTED,
            SyncStateMachine.history(disabled, SyncActivity(historySessionExists = false))
        )
        // A session that already made progress is PAUSED, not never-started.
        assertEquals(
            HistorySyncState.PAUSED,
            SyncStateMachine.history(disabled, SyncActivity(historySessionExists = true))
        )
    }

    @Test
    fun `aFailedHistorySessionIsReportedAsFailedWhenNothingBlocks`() {
        assertEquals(
            HistorySyncState.FAILED,
            SyncStateMachine.history(prerequisites(), SyncActivity(historyFailed = true))
        )
    }

    // ── Commands ─────────────────────────────────────────────────────────────

    @Test
    fun `commandsAreReadyWhenNothingBlocksAndReflectActivity`() {
        val result = prerequisites()

        assertEquals(CommandSyncState.READY, SyncStateMachine.commands(result))
        assertEquals(
            CommandSyncState.PULLING,
            SyncStateMachine.commands(result, SyncActivity(commandPulling = true))
        )
        assertEquals(
            CommandSyncState.EXECUTING,
            SyncStateMachine.commands(result, SyncActivity(commandExecuting = true))
        )
    }

    @Test
    fun `aRevokedDeviceBlocksCommandsAsAuthRatherThanGenericDegraded`() {
        assertEquals(
            CommandSyncState.BLOCKED_AUTH,
            SyncStateMachine.commands(prerequisites(inputs(deviceRevoked = true)))
        )
    }

    @Test
    fun `aDisabledGatewayDisablesCommands`() {
        assertEquals(
            CommandSyncState.DISABLED,
            SyncStateMachine.commands(prerequisites(inputs(gatewayEnabled = false)))
        )
    }

    // ── The four states are genuinely independent ────────────────────────────

    @Test
    fun `controlChannelHealthSaysNothingAboutReplication`() {
        // The whole point of §7: an idle READY uploader and a CONNECTED control channel are
        // separate facts, and a caller must not derive one from the other.
        val result = prerequisites()
        assertEquals(ReplicationState.READY, SyncStateMachine.replication(result))
        assertEquals(CommandSyncState.READY, SyncStateMachine.commands(result))
        // Same prerequisites, and history is still blocked independently — and it names the
        // reason rather than falling back to a generic not-started.
        assertEquals(
            HistorySyncState.WAITING_FOR_HISTORY_GRANT,
            SyncStateMachine.history(prerequisites(inputs(historyGrantPresent = false)))
        )
    }
}
