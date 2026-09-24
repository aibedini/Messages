package com.autonomousone.messages.sync

/**
 * The single answer to "can this device replicate right now, and if not, why?" (mission §8).
 *
 * Every consumer that today re-derives its own conditions — `EventUploader`'s private
 * `UploadGate`, `OutboxPoller`'s consent check, `SecureCommandPoller`'s URL check,
 * `ContactsSyncPublisher`, `DeviceTelemetry`, `HeartbeatManager`, `TrustStatementPublisher`,
 * `BootGatewayReceiver` — must read this instead
 * (`docs/gateway-replication-audit.md`, Blocker 2: the same gates are re-derived in eight files
 * with different combinations, which is how `SecureCommandPoller` came to omit consent).
 *
 * The three capabilities are reported separately because they genuinely differ: uploading does
 * not need READ_SMS, and history needs a consumer that holds FULL_HISTORY. Collapsing them into
 * one boolean is what makes a device with working realtime look fully broken because history
 * cannot start.
 *
 * [realtimeBlocker] / [historyBlocker] / [commandBlocker] are the *earliest* cause for each
 * capability. [primaryBlocker] exists to satisfy the mission's §57 requirement that diagnostics
 * return ONE actionable blocker, and it prefers realtime deliberately: realtime is what a user
 * notices, so it is the one worth naming first.
 */
data class ReplicationPrerequisites(
    val canUploadRealtime: Boolean,
    val canUploadHistory: Boolean,
    val canReceiveCommands: Boolean,
    val blockers: Set<ReplicationBlocker>,
    val realtimeBlocker: ReplicationBlocker? = null,
    val historyBlocker: ReplicationBlocker? = null,
    val commandBlocker: ReplicationBlocker? = null
) {

    /** The one blocker to report, or null when realtime replication is clear. */
    val primaryBlocker: ReplicationBlocker?
        get() = realtimeBlocker ?: historyBlocker ?: commandBlocker

    /** The structured code for [primaryBlocker]. */
    val primaryCode: SyncErrorCode?
        get() = primaryBlocker?.code

    /** True when the device can put a new message on the wire. The headline capability. */
    val isRealtimeOperational: Boolean
        get() = canUploadRealtime

    /** True only when nothing at all is blocked. History being blocked still counts as blocked. */
    val isFullyClear: Boolean
        get() = blockers.isEmpty()

    /** True when realtime and commands work, whatever history is doing. */
    val isControlPlaneOperational: Boolean
        get() = canUploadRealtime && canReceiveCommands

    companion object {
        /** Nothing is possible and nothing is known: the state before any input is gathered. */
        val BLOCKED_UNKNOWN: ReplicationPrerequisites = ReplicationPrerequisites(
            canUploadRealtime = false,
            canUploadHistory = false,
            canReceiveCommands = false,
            blockers = setOf(ReplicationBlocker.GatewayDisabled)
        )
    }
}
