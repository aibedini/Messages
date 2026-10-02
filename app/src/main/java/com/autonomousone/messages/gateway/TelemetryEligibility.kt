package com.autonomousone.messages.gateway

/**
 * Whether device telemetry may be transmitted right now — and, when it may not, WHICH condition is
 * stopping it.
 *
 * **Why this exists.** Telemetry was gated by three prefs and the gate's answer was a bare Boolean,
 * so a device that never sent a single telemetry report looked exactly like a device that had
 * nothing to report. Production saw ZERO `/api/v1/agent/device-telemetry` requests for a week while
 * the same process was uploading events and polling the pull bridge with HTTP 200 — and the app
 * could not say why, because the only place the decision existed threw its reason away.
 *
 * A gate with three inputs and one output is a gate that cannot be diagnosed. This one names the
 * blocking condition, the name travels to diagnostics and the device log, and the decision itself is
 * pure so every branch is asserted by a test instead of inferred from a device.
 */
data class TelemetryEligibility(val eligible: Boolean, val reason: String?) {

    companion object {

        /** The user switched the gateway off (or consent is absent). */
        const val GATEWAY_DISABLED = "GATEWAY_DISABLED"

        /** The device has never completed identity enrollment, so it cannot sign a request. */
        const val IDENTITY_NOT_REGISTERED = "IDENTITY_NOT_REGISTERED"

        /** No GMweb origin is configured, so there is nowhere to send the report. */
        const val NO_GMWEB_ORIGIN = "NO_GMWEB_ORIGIN"

        private val READY = TelemetryEligibility(true, null)

        /**
         * The gate.
         *
         * Order matters for the reason a reader sees: "the gateway is off" is a more useful first
         * answer than "no server configured", and a device that is switched off should not be told
         * it is misconfigured.
         */
        fun evaluate(
            isEnabled: Boolean,
            identityRegistered: Boolean,
            serverOrigin: String
        ): TelemetryEligibility = when {
            !isEnabled -> TelemetryEligibility(false, GATEWAY_DISABLED)
            !identityRegistered -> TelemetryEligibility(false, IDENTITY_NOT_REGISTERED)
            serverOrigin.isBlank() -> TelemetryEligibility(false, NO_GMWEB_ORIGIN)
            else -> READY
        }
    }
}

/**
 * The live eligibility of the running reporter, for diagnostics.
 *
 * Kept beside the rule so the report and the gate can never describe different conditions: the
 * reporter writes what it evaluated, and the diagnostic reads it rather than re-deriving it from
 * prefs at render time (a diagnostic that derives its own answer can disagree with the code it is
 * diagnosing, which is exactly how "the phone is online" and "no telemetry arrived" coexisted).
 */
object TelemetryEligibilityState {

    @Volatile
    private var current: TelemetryEligibility? = null

    fun record(eligibility: TelemetryEligibility) {
        current = eligibility
    }

    fun current(): TelemetryEligibility? = current

    internal fun resetForTest() {
        current = null
    }
}
