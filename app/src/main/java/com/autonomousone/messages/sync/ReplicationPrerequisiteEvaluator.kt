package com.autonomousone.messages.sync

/**
 * The facts the evaluator needs, gathered by the caller from the places that actually own them.
 *
 * Every field is **required — there are no defaults**, on purpose. The defect this whole
 * subsystem exists to remove is a caller checking three of eight prerequisites and reporting a
 * green gateway (audit Blocker 2: the same gates re-derived in eight files with different
 * combinations). A defaulted field is an omission waiting to happen, and a default that says
 * "fine" is the silent-success bug; a default that says "blocked" trains people to pass `true`
 * without checking. Making them explicit costs a few parameters and removes the choice.
 *
 * [authRejected] must be true ONLY for a real credential rejection (HTTP 401/403). A 400, a 404,
 * a 405 or a timeout is not a rejected credential — conflating them is exactly the v3.4.6 device
 * defect, and `SyncErrorCode.fromHttpStatus` encodes the permitted mapping.
 *
 * [historyGrantPresent] means "an active linked device holds FULL_HISTORY", because in this
 * codebase `historyGrant` is a per-linked-web-device permission. It is not an Android
 * permission; that is [telephonyPermissionGranted].
 */
data class ReplicationInputs(
    val gatewayEnabled: Boolean,
    val consentGranted: Boolean,
    val serverOriginConfigured: Boolean,
    val identityRegistered: Boolean,
    val authRejected: Boolean,
    val deviceRevoked: Boolean,
    val cryptoKeyAvailable: Boolean,
    val telephonyPermissionGranted: Boolean,
    val historyGrantPresent: Boolean,
    val networkValidated: Boolean
)

/**
 * The one place that decides whether replication may proceed, and names the reason when it may
 * not (mission §8).
 *
 * Pure: no Android, no Room, no prefs, no clock. That is what makes every prerequisite
 * combination assertable in a fast JVM test (mission §67) instead of being discovered on a
 * device, and it is why this can be wired into eight callers without any of them changing
 * behaviour by accident — they all get the same answer.
 */
object ReplicationPrerequisiteEvaluator {

    /**
     * Evaluate the prerequisites.
     *
     * Blockers are computed per capability rather than once globally, because they genuinely
     * differ: history also needs telephony permission and an authorized consumer, so a device
     * with working realtime must not be reported as entirely blocked when only history cannot
     * start.
     */
    fun evaluate(inputs: ReplicationInputs): ReplicationPrerequisites {
        val realtime = outboundBlockers(inputs)
        val history = realtime + historyOnlyBlockers(inputs)
        // Commands are claimed and decrypted over the same control plane as an upload, so they
        // share its requirements exactly. Kept as a named list rather than reusing `realtime`
        // inline so that a future divergence has one obvious place to live.
        val commands = outboundBlockers(inputs)

        val all = (realtime + history + commands).toSet()
        return ReplicationPrerequisites(
            canUploadRealtime = realtime.isEmpty(),
            canUploadHistory = history.isEmpty(),
            canReceiveCommands = commands.isEmpty(),
            blockers = all,
            realtimeBlocker = ReplicationBlocker.earliest(realtime.toSet()),
            historyBlocker = ReplicationBlocker.earliest(history.toSet()),
            commandBlocker = ReplicationBlocker.earliest(commands.toSet())
        )
    }

    /**
     * What stops an event reaching GMweb. Ordered by [ReplicationBlocker.priority] so the first
     * entry is the earliest thing a human must fix.
     */
    private fun outboundBlockers(inputs: ReplicationInputs): List<ReplicationBlocker> = buildList {
        if (!inputs.gatewayEnabled) add(ReplicationBlocker.GatewayDisabled)
        if (!inputs.consentGranted) add(ReplicationBlocker.ConsentRevoked)
        if (!inputs.serverOriginConfigured) add(ReplicationBlocker.MissingServerUrl)
        if (inputs.deviceRevoked) add(ReplicationBlocker.DeviceRevoked)
        if (!inputs.identityRegistered) add(ReplicationBlocker.IdentityNotRegistered)
        if (inputs.authRejected) add(ReplicationBlocker.AuthenticationRequired)
        if (!inputs.cryptoKeyAvailable) add(ReplicationBlocker.MissingCryptoKey)
        if (!inputs.networkValidated) add(ReplicationBlocker.NetworkUnavailable)
    }

    /** What additionally stops HISTORY, over and above [outboundBlockers]. */
    private fun historyOnlyBlockers(inputs: ReplicationInputs): List<ReplicationBlocker> =
        buildList {
            // Permission first: history cannot even be read without it, so it is the more
            // fundamental of the two and cheaper to resolve.
            if (!inputs.telephonyPermissionGranted) add(ReplicationBlocker.TelephonyPermissionMissing)
            if (!inputs.historyGrantPresent) add(ReplicationBlocker.MissingHistoryGrant)
        }
}
