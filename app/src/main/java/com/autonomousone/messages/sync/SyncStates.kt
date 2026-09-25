package com.autonomousone.messages.sync

/**
 * The four independent states the mission requires to stay separate (mission §7).
 *
 * They are separate because they fail separately, and collapsing them is what lets a phone whose
 * uploads are stalled report a green "CONNECTED": `ConnectionSupervisor.State.CONNECTED` means
 * only that components were *started* (audit Blockers 2 and 21), the notification says "GMweb
 * bridge: live" from the poller's loop being alive, and the legacy card says "Online" from
 * heartbeat freshness. None of those is a statement about messages.
 *
 * Do NOT add a combined `CONNECTED | DEGRADED` state. If a caller wants one headline, it should
 * read [ReplicationPrerequisites.isRealtimeOperational], which is defined in terms of the
 * readiness evaluator rather than invented per screen.
 */

/** Whether the control channel (heartbeat / pull loop) is up. Says nothing about messages. */
enum class ControlChannelState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DEGRADED
}

/** Whether this device can move its own events to GMweb, and if not, what class of thing blocks. */
enum class ReplicationState {
    /** Switched off by the user, or consent withdrawn. Not a fault. */
    DISABLED,

    /** Something must be configured — most often the GMweb origin. */
    BLOCKED_CONFIGURATION,

    /** Identity enrollment has never completed. */
    BLOCKED_IDENTITY,

    /** A credential was rejected or the device was revoked. */
    BLOCKED_AUTH,

    /** Key material is missing. Never resolved by sending plaintext. */
    BLOCKED_KEY,

    /** Everything is in place and the uploader is idle, waiting for work. */
    READY,

    /** A batch is in flight. */
    UPLOADING,

    /** Retrying after a failure, with a due time. */
    BACKING_OFF,

    /** Nothing is misconfigured; the network (or the server) simply is not available yet. */
    DEGRADED
}

/** How far history has come. SCAN and SYNC are deliberately distinct (mission §26). */
enum class HistorySyncState {
    NOT_STARTED,

    /** READ_SMS (or the MMS equivalent) is not granted. */
    WAITING_FOR_PERMISSION,

    /** No authorized consumer holds FULL_HISTORY, so backfill has no destination. */
    WAITING_FOR_HISTORY_GRANT,

    /** The Telephony Provider is being read, page by page. */
    SCANNING,

    /** Rows are enqueued and being uploaded. */
    UPLOADING,

    /** The scan is DONE but not everything is ACKed yet. Distinct from CAUGHT_UP. */
    CATCHING_UP,

    /**
     * Every history event is resolved: acknowledged, or permanently failed and counted.
     *
     * "Nothing left to do" rather than "everything delivered" — a permanent failure is a human's to
     * act on, so it must not block the terminal state, and the diagnostics report the failure count
     * beside this state so it cannot read as an unqualified success.
     */
    CAUGHT_UP,
    PAUSED,

    FAILED
}

/** Whether web commands are being received and executed. */
enum class CommandSyncState {
    DISABLED,
    READY,
    PULLING,
    EXECUTING,

    /** The control plane rejected our credential, so commands cannot be claimed. */
    BLOCKED_AUTH,

    /** Nothing misconfigured; waiting on the network or the server. */
    DEGRADED
}

/**
 * Activity, as opposed to readiness.
 *
 * Readiness answers "may we?"; activity answers "are we, right now?". Keeping them apart is what
 * lets `READY` mean idle-and-fine instead of being overloaded to also mean "working".
 */
data class SyncActivity(
    val uploadInFlight: Boolean = false,
    val backingOff: Boolean = false,
    val historyScanning: Boolean = false,
    val historyScanComplete: Boolean = false,
    val historyUploading: Boolean = false,
    /**
     * Every history source is RESOLVED: nothing is left to do.
     *
     * Named for what it measures. It used to be `historyAcknowledgedAll`, which said "everything was
     * accepted by the server" and made [HistorySyncState.CAUGHT_UP] unreachable for any source that
     * had permanently failed one event. A permanent failure is waiting on a person, not on this
     * device, so it must not block the terminal state — and it must not be hidden either, which is
     * why the diagnostics report the dead-letter count beside it.
     */
    val historyResolvedAll: Boolean = false,
    val historyPaused: Boolean = false,
    val historyFailed: Boolean = false,
    /** True once a history session exists, so a block reports PAUSED rather than NOT_STARTED. */
    val historySessionExists: Boolean = false,
    val commandPulling: Boolean = false,
    val commandExecuting: Boolean = false
)

/**
 * Readiness + activity to a state, once per capability.
 *
 * Every mapping is a pure function so the "SCAN_COMPLETE is not SYNC_COMPLETE" rule and the
 * "offline is DEGRADED, not BLOCKED_CONFIGURATION" rule are asserted in tests rather than
 * discovered from a support log.
 */
object SyncStateMachine {

    fun replication(
        prerequisites: ReplicationPrerequisites,
        activity: SyncActivity = SyncActivity()
    ): ReplicationState {
        val blocker = prerequisites.realtimeBlocker
        if (blocker != null) return replicationBlockedState(blocker)
        return when {
            activity.uploadInFlight -> ReplicationState.UPLOADING
            activity.backingOff -> ReplicationState.BACKING_OFF
            else -> ReplicationState.READY
        }
    }

    private fun replicationBlockedState(blocker: ReplicationBlocker): ReplicationState =
        when (blocker.category) {
            BlockerCategory.SWITCHED_OFF -> ReplicationState.DISABLED
            BlockerCategory.CONFIGURATION -> ReplicationState.BLOCKED_CONFIGURATION
            BlockerCategory.IDENTITY -> ReplicationState.BLOCKED_IDENTITY
            BlockerCategory.AUTHORIZATION -> ReplicationState.BLOCKED_AUTH
            BlockerCategory.KEY -> ReplicationState.BLOCKED_KEY
            // History-only categories can never be the realtime blocker; if one ever is, the
            // honest answer is "not configured well enough to say", which is DEGRADED.
            BlockerCategory.PERMISSION,
            BlockerCategory.GRANT -> ReplicationState.DEGRADED
            // The only category that resolves by waiting.
            BlockerCategory.NETWORK -> ReplicationState.DEGRADED
        }

    fun history(
        prerequisites: ReplicationPrerequisites,
        activity: SyncActivity = SyncActivity()
    ): HistorySyncState {
        val blocker = prerequisites.historyBlocker
        if (blocker != null) return historyBlockedState(blocker, activity)
        // Scan and sync are different facts. Reporting CAUGHT_UP from a finished scan is the
        // exact conflation mission §26 forbids, and it is what the current diagnostics do
        // (audit Blocker 9).
        return when {
            activity.historyFailed -> HistorySyncState.FAILED
            activity.historyPaused -> HistorySyncState.PAUSED
            activity.historyScanning -> HistorySyncState.SCANNING
            activity.historyResolvedAll -> HistorySyncState.CAUGHT_UP
            activity.historyUploading -> HistorySyncState.UPLOADING
            activity.historyScanComplete -> HistorySyncState.CATCHING_UP
            activity.historySessionExists -> HistorySyncState.CATCHING_UP
            else -> HistorySyncState.NOT_STARTED
        }
    }

    private fun historyBlockedState(
        blocker: ReplicationBlocker,
        activity: SyncActivity
    ): HistorySyncState = when (blocker.category) {
        BlockerCategory.PERMISSION -> HistorySyncState.WAITING_FOR_PERMISSION
        BlockerCategory.GRANT -> HistorySyncState.WAITING_FOR_HISTORY_GRANT
        // Everything else stops history too, but the state depends on whether it had started:
        // a session that already made progress is PAUSED, not never-started.
        BlockerCategory.SWITCHED_OFF,
        BlockerCategory.CONFIGURATION,
        BlockerCategory.IDENTITY,
        BlockerCategory.AUTHORIZATION,
        BlockerCategory.KEY,
        BlockerCategory.NETWORK ->
            if (activity.historySessionExists) HistorySyncState.PAUSED else HistorySyncState.NOT_STARTED
    }

    fun commands(
        prerequisites: ReplicationPrerequisites,
        activity: SyncActivity = SyncActivity()
    ): CommandSyncState {
        val blocker = prerequisites.commandBlocker
        if (blocker != null) return commandBlockedState(blocker)
        return when {
            activity.commandExecuting -> CommandSyncState.EXECUTING
            activity.commandPulling -> CommandSyncState.PULLING
            else -> CommandSyncState.READY
        }
    }

    private fun commandBlockedState(blocker: ReplicationBlocker): CommandSyncState =
        when (blocker.category) {
            BlockerCategory.SWITCHED_OFF -> CommandSyncState.DISABLED
            BlockerCategory.AUTHORIZATION -> CommandSyncState.BLOCKED_AUTH
            BlockerCategory.CONFIGURATION,
            BlockerCategory.IDENTITY,
            BlockerCategory.KEY,
            BlockerCategory.PERMISSION,
            BlockerCategory.GRANT,
            BlockerCategory.NETWORK -> CommandSyncState.DEGRADED
        }
}
