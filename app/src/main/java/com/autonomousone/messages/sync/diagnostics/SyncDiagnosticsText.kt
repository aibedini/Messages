package com.autonomousone.messages.sync.diagnostics

import com.autonomousone.messages.sync.ReplicationBlocker
import com.autonomousone.messages.sync.ReplicationPrerequisites

/**
 * How a blocker is named to a human and to a machine (mission §57).
 *
 * Mirrors the repo's existing split between a model and its presentation
 * (`GatewayHealthPresentation`), so no user-facing sentence is embedded in the domain enum.
 *
 * The rule this exists to enforce: a diagnostic may never say only `DEGRADED`. Every blocked
 * state must name ONE cause that a person can act on, which is what
 * `docs/gateway-replication-audit.md` found missing (the report prints `Verdict: DEGRADED` plus
 * a prose sentence about the transport, never the prerequisite that stopped it).
 */
object SyncDiagnosticsText {

    /**
     * The stable, machine-readable name of a blocker.
     *
     * This is the blocker's own identity in SCREAMING_SNAKE (`CONSENT_REVOKED`), not its
     * [com.autonomousone.messages.sync.SyncErrorCode], because two blockers legitimately share
     * one code (`GatewayDisabled` and `ConsentRevoked` both report `GATEWAY_DISABLED`) and a
     * diagnostic must not make those indistinguishable.
     */
    fun codeName(blocker: ReplicationBlocker): String =
        blocker::class.simpleName?.let(::screamingSnake) ?: blocker.code.name

    /**
     * What the person reading this should actually do.
     *
     * Phrased as the next action, not as a restatement of the state: "Open Settings and enter
     * your GMweb address" is useful, "configuration missing" is not.
     */
    fun actionHint(blocker: ReplicationBlocker): String = when (blocker) {
        ReplicationBlocker.GatewayDisabled ->
            "Turn the gateway on to resume replication."
        ReplicationBlocker.ConsentRevoked ->
            "Grant gateway consent to resume replication."
        ReplicationBlocker.MissingServerUrl ->
            "Enter your GMweb address in the gateway settings."
        ReplicationBlocker.DeviceRevoked ->
            "This device was revoked. Re-pair it; retrying will not help."
        ReplicationBlocker.IdentityNotRegistered ->
            "Enroll this device's identity with the control plane."
        ReplicationBlocker.AuthenticationRequired ->
            "The control plane rejected this device's credential. Check the device key."
        ReplicationBlocker.MissingCryptoKey ->
            "The encryption key is unavailable, so nothing can be sent. Check the device key store."
        ReplicationBlocker.TelephonyPermissionMissing ->
            "Grant SMS/MMS read permission to scan your history."
        ReplicationBlocker.MissingHistoryGrant ->
            "No linked device has requested full history, so there is nothing to back-fill for."
        ReplicationBlocker.NetworkUnavailable ->
            "No network connection. Replication resumes automatically."
    }

    /**
     * The first line of any sync diagnostic.
     *
     * Prefers realtime: it is the capability a user notices, so when realtime is blocked that is
     * the cause worth naming even if history is blocked by something else too.
     */
    fun blockerLine(prerequisites: ReplicationPrerequisites): String {
        val blocker = prerequisites.primaryBlocker
            ?: return "Replication clear: all prerequisites satisfied."
        return "Replication blocked: ${codeName(blocker)}\n  ${actionHint(blocker)}"
    }

    /**
     * `IdentityNotRegistered` -> `IDENTITY_NOT_REGISTERED`.
     *
     * Only breaks before an uppercase letter that follows a lowercase one or a digit, so
     * `MissingServerUrl` becomes `MISSING_SERVER_URL` and not `MISSING_SERVER_U_RL`.
     */
    internal fun screamingSnake(name: String): String = buildString(name.length + 4) {
        name.forEachIndexed { index, char ->
            if (index > 0 && char.isUpperCase() && !name[index - 1].isUpperCase()) append('_')
            append(char.uppercaseChar())
        }
    }
}
