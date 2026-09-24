package com.autonomousone.messages.sms

/**
 * What a send must do about the SIM it was asked to use (mission §50).
 *
 * **The defect this exists for.** `SmsSender.resolveSmsManager` had a pre-Android-12 branch that
 * used the platform-default `SmsManager` with the comment *"the per-subscription manager API is no
 * longer exposed by current SDK stubs"*. That claim is false: `SmsManager
 * .getSmsManagerForSubscriptionId(int)` is public since API 22 and present in the current SDK, and
 * this app's `minSdk` is 26 — so on every supported device below Android 12 the user's chosen line
 * was silently ignored, and the send left on whatever SIM the platform considered default.
 *
 * Worse than the wrong SIM, the app then **recorded the requested SIM as if it had been used**:
 * `effectiveSubId` (the request) was passed both to `recordSegmentSubmissions` and into the SENT
 * callbacks, so the per-SIM send ledger attributed the message to a line that never carried it. A
 * fabricated attribution in a durable ledger is worse than a missing one, because it looks like
 * evidence.
 *
 * **The rule.** Mission §50 forbids *silently* sending on another SIM. Two facts are therefore kept
 * strictly apart: the id the user asked for, and the id the manager reports it is actually bound to.
 * The ledger records the second, never the first.
 *
 * Pure and Android-free so the decision can be tested directly — the two cases that must never
 * happen are a fabricated attribution and a send that refuses because the app could not tell.
 */
sealed interface SimDecision {

    /**
     * Proceed, recording [recordedSubscriptionId].
     *
     * That id is what the manager reported, or [SendSimPolicy.UNKNOWN_SUBSCRIPTION_ID] when it
     * reported nothing usable. It is never the id the user asked for unless the manager confirmed
     * it.
     */
    data class Send(val recordedSubscriptionId: Int) : SimDecision

    /**
     * Do not send: the request cannot be honoured and the app can prove it.
     *
     * Refusing is the mission's requirement. Sending anyway would put a message on a line the user
     * did not choose, and a wrong-origin SMS cannot be recalled.
     */
    data class Refuse(val requestedSubscriptionId: Int) : SimDecision
}

object SendSimPolicy {

    /** The platform's INVALID_SUBSCRIPTION_ID, and this app's "unknown SIM" ledger marker. */
    const val UNKNOWN_SUBSCRIPTION_ID = -1

    /**
     * Decide.
     *
     * @param requested the SIM the user asked for, or null when they made no choice at all (the
     *   preference sentinel is `-1`, which means "no selection", not "the subscription with id -1").
     * @param actual the subscription the resolved `SmsManager` reports, or
     *   [UNKNOWN_SUBSCRIPTION_ID] when it does not report one.
     * @param requestedSimActive whether the requested subscription is in the device's active list:
     *   `false` is proof it is gone, `null` means the app could not find out (no `READ_PHONE_STATE`,
     *   or the platform refused), and the two must not be conflated — treating "unknown" as "absent"
     *   would refuse every send on a device that withholds the list.
     */
    fun decide(
        requested: Int?,
        actual: Int,
        requestedSimActive: Boolean?,
    ): SimDecision = when {
        // No choice was made, so the platform default IS what the user asked for.
        requested == null -> SimDecision.Send(actual)

        // Proof, from the device's own active-subscription list, that the chosen line is not there.
        // Nothing about the manager read-back is needed for this and no read-back contradiction can
        // make it untrue.
        requestedSimActive == false -> SimDecision.Refuse(requested)

        // The request was honoured — the only case where the requested id is also a fact.
        actual == requested -> SimDecision.Send(requested)

        // The manager would not say which subscription it is bound to.
        //
        // Deliberately NOT a refusal: refusing here would stop every send on any device or platform
        // version where this read fails, which is a far worse outcome than an unknown label in the
        // ledger. Recording "unknown" is the honest maximum when the platform will not say — and it
        // is emphatically not the requested id, which is what the old code recorded.
        actual == UNKNOWN_SUBSCRIPTION_ID -> SimDecision.Send(UNKNOWN_SUBSCRIPTION_ID)

        // The manager says it is bound elsewhere. Positive evidence of a mismatch, so the safe
        // direction is to refuse: a wrong-origin SMS cannot be recalled.
        else -> SimDecision.Refuse(requested)
    }
}
