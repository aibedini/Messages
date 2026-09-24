package com.autonomousone.messages.sync

/**
 * The upload loop's hold decision, derived from the one evaluator rather than re-derived
 * locally.
 *
 * WHAT THIS REPLACES: `EventUploader` had a private four-value `UploadGate` enum computed from
 * three conditions, held in a RAM field and invisible to diagnostics
 * (`docs/gateway-replication-audit.md`, Blocker 2). Every caller that re-derives its own gates is
 * how `SecureCommandPoller` came to omit consent entirely, so the decision now comes from
 * [ReplicationPrerequisiteEvaluator] and the answer is a [ReplicationBlocker] — a named cause
 * rather than a private tag.
 *
 * WHY THE INPUT SET IS NARROWER THAN [ReplicationInputs]: this is evaluated on every loop
 * iteration, so it may only use inputs that are cheap to read. The excluded ones are not
 * "assumed fine" — [HOLDING_CATEGORIES] deliberately omits their categories, so their values
 * cannot affect the result at all. A test proves that by varying them.
 *
 * What is excluded and why:
 *
 *  - **KEY.** Reading it means probing the Android Keystore (three alias lookups plus three key
 *    fetches) on every iteration. It is also nearly moot: a missing key already fails at signing
 *    time, and encryption happens at ENQUEUE, so a missing key usually means the outbox is empty
 *    rather than that uploads are being refused.
 *  - **PERMISSION / GRANT.** These gate HISTORY only. Realtime upload does not read the
 *    Telephony Provider, so neither may stop it.
 */
object ReplicationUploadGate {

    /**
     * The blocker categories that hold the upload loop.
     *
     * [BlockerCategory.NETWORK] is included because offline must keep meaning "no HTTP": the
     * supervisor deliberately leaves the uploader component running while offline and relies on a
     * transmission gate to keep the radio quiet (`ConnectionSupervisor.kt:326`). Dropping NETWORK
     * from this set would start attempting uploads on an offline device.
     *
     * [BlockerCategory.AUTHORIZATION] is included as the one deliberate behaviour change: when the
     * control plane has actually rejected this device's credential, pausing is what the mission
     * asks for (§17, 401/403 ⇒ BLOCKED_AUTH) and it stops the current code from dead-lettering a
     * whole batch on every retry. Events stay PENDING, so nothing is lost either way.
     *
     * KEY/PERMISSION/GRANT are excluded — see the class KDoc.
     */
    val HOLDING_CATEGORIES: Set<BlockerCategory> = setOf(
        BlockerCategory.SWITCHED_OFF,
        BlockerCategory.CONFIGURATION,
        BlockerCategory.IDENTITY,
        BlockerCategory.AUTHORIZATION,
        BlockerCategory.NETWORK
    )

    /**
     * The early blocker that should hold the loop, or null when the loop may proceed.
     *
     * Only inputs cheap enough to read every iteration.
     */
    fun hold(inputs: UploadGateInputs): ReplicationBlocker? {
        val prerequisites = ReplicationPrerequisiteEvaluator.evaluate(
            ReplicationInputs(
                gatewayEnabled = inputs.gatewayDesired,
                consentGranted = inputs.consentGranted,
                serverOriginConfigured = inputs.serverOriginConfigured,
                identityRegistered = inputs.identityRegistered,
                authRejected = inputs.authenticationRejected,
                // Excluded categories; see the class KDoc. Values cannot affect `hold`.
                deviceRevoked = false,
                cryptoKeyAvailable = true,
                telephonyPermissionGranted = true,
                historyGrantPresent = true,
                networkValidated = !inputs.supervisorOffline
            )
        )
        val blocker = prerequisites.realtimeBlocker ?: return null
        return blocker.takeIf { it.category in HOLDING_CATEGORIES }
    }
}

/**
 * The cheap, always-available inputs for the upload gate.
 *
 * [gatewayDesired] is the USER's intent (`prefs.gatewayDesiredEnabled`), not the supervisor's
 * derived transmission gate (`prefs.isEnabled`), which the supervisor clears while offline.
 * Passing the derived value here would report an offline device as "the user turned the gateway
 * off" — a false cause, and exactly the class of mistake this subsystem exists to remove.
 *
 * [supervisorOffline] is the supervisor's own declaration
 * (`ConnectionSupervisor.State.WAITING_FOR_NETWORK`), which is the authoritative offline signal:
 * the health registry's `validatedInternet` is only published on the online path.
 */
data class UploadGateInputs(
    val gatewayDesired: Boolean,
    val consentGranted: Boolean,
    val serverOriginConfigured: Boolean,
    val identityRegistered: Boolean,
    val supervisorOffline: Boolean,
    val authenticationRejected: Boolean
)
