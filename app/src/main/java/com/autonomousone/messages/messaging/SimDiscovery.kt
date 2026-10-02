package com.autonomousone.messages.messaging

/**
 * What the device can say about its SIMs right now — as three DIFFERENT facts.
 *
 * **The defect this exists for.** `SimManager.getActiveSims()` caught every exception and returned
 * `emptyList()`. That collapsed three unrelated states into one observation:
 *
 *  - `READ_PHONE_STATE` is not granted → the list is empty;
 *  - the permission is granted and the device genuinely has no active subscription → the list is
 *    empty;
 *  - the platform call threw (subscription service down, OEM restriction, security exception on a
 *    specific device) → the list is empty.
 *
 * Telemetry then reported `available=false` and `items=[]` for all three, so GMweb could not tell
 * "the user has not granted Phone permission" from "this phone has no SIM" from "we failed to ask".
 * The first is fixable by the user in ten seconds; the second is a phone with no service; the third
 * is a bug. They must not look the same.
 *
 * [PermissionMissing] and [Failed] deliberately carry NO exception text: a platform exception
 * message can name internal component classes, and this value travels to GMweb. Only a stable code
 * crosses the boundary; the detail stays in the local log.
 */
sealed interface SimDiscoveryResult {

    /** The device answered. [sims] may legitimately be empty (no active subscription). */
    data class Available(val sims: List<SimInfo>) : SimDiscoveryResult

    /** `READ_PHONE_STATE` is not granted, so the question cannot be asked at all. */
    data object PermissionMissing : SimDiscoveryResult

    /** The permission is granted and the platform still refused to answer. */
    data class Failed(val reason: String) : SimDiscoveryResult
}

/**
 * The pure part of SIM discovery: turn "was asked, could ask, what came back" into the three
 * states above. Android-free so every branch is unit-testable.
 */
object SimDiscovery {

    /** The permission is missing. */
    const val REASON_PERMISSION_MISSING = "READ_PHONE_STATE_MISSING"

    /** The platform call threw while the permission was granted. */
    const val REASON_PLATFORM_FAILURE = "SUBSCRIPTION_API_FAILURE"

    /**
     * @param permissionGranted whether `READ_PHONE_STATE` is held.
     * @param load the platform query. Only called when [permissionGranted] is true, so a missing
     *   permission can never be reported as a failure.
     */
    fun classify(permissionGranted: Boolean, load: () -> List<SimInfo>): SimDiscoveryResult {
        if (!permissionGranted) return SimDiscoveryResult.PermissionMissing
        return try {
            SimDiscoveryResult.Available(load())
        } catch (e: Exception) {
            // The exception is NOT swallowed silently: the caller logs it, and only this stable
            // code travels onward.
            SimDiscoveryResult.Failed(REASON_PLATFORM_FAILURE)
        }
    }

    /**
     * The `reason` string for telemetry, or null when the device answered.
     *
     * Kept beside the sealed type so the wire vocabulary has exactly one definition.
     */
    fun reasonOf(result: SimDiscoveryResult): String? = when (result) {
        is SimDiscoveryResult.Available -> null
        SimDiscoveryResult.PermissionMissing -> REASON_PERMISSION_MISSING
        is SimDiscoveryResult.Failed -> result.reason
    }
}
