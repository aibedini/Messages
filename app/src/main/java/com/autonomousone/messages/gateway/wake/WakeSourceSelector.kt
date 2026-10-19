package com.autonomousone.messages.gateway.wake

/**
 * Resolves the configured mode to the source that will actually run.
 *
 * Pure and Android-free, so the ladder is pinned by a test instead of being spread across runtime
 * branches — the same style as `PollStatePolicy` and `WakeSourceSelector`'s sibling rules.
 *
 * **The fallback is always reported, never silent.** A user whose chosen mode degraded to polling
 * must be able to see it on the diagnostic card; a silent degrade is how this programme's own
 * regression would return unnoticed.
 */
object WakeSourceSelector {

    data class Inputs(
        /** AUTO | POLLING | SOCKET | FCM */
        val configured: WakeMode,
        val fcmAvailable: Boolean,
        val socketAvailable: Boolean,
    )

    data class Resolution(
        val chosen: WakeSourceId,
        /** Set only when an EXPLICITLY chosen mode was unavailable and we fell back to POLLING. */
        val fellBackFrom: WakeSourceId? = null,
        /** Why the fallback happened, when it did. */
        val unavailable: WakeUnavailable? = null,
    )

    /**
     * AUTO ladder: FCM → SOCKET → POLLING.
     *
     * AUTO is not a choice the user made about a specific mechanism, so a capability being absent is
     * not a fallback to report — it is the ladder working. Explicit modes are a promise, and breaking
     * one is reported with [Resolution.fellBackFrom].
     */
    fun resolve(inputs: Inputs): Resolution = when (inputs.configured) {
        WakeMode.AUTO -> when {
            inputs.fcmAvailable -> Resolution(WakeSourceId.FCM)
            inputs.socketAvailable -> Resolution(WakeSourceId.SOCKET)
            else -> Resolution(WakeSourceId.POLLING)
        }

        WakeMode.POLLING -> Resolution(WakeSourceId.POLLING)

        WakeMode.SOCKET -> if (inputs.socketAvailable) {
            Resolution(WakeSourceId.SOCKET)
        } else {
            Resolution(
                chosen = WakeSourceId.POLLING,
                fellBackFrom = WakeSourceId.SOCKET,
                unavailable = WakeUnavailable.NOT_CONFIGURED
            )
        }

        WakeMode.FCM -> if (inputs.fcmAvailable) {
            Resolution(WakeSourceId.FCM)
        } else {
            Resolution(
                chosen = WakeSourceId.POLLING,
                fellBackFrom = WakeSourceId.FCM,
                unavailable = WakeUnavailable.NO_PLATFORM_SUPPORT
            )
        }
    }
}
