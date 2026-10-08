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
     * What a send that named NO line must use (mission §25, MODE A `PHONE_DEFAULT`).
     *
     * The distinction this exists for: "the platform says there is no default SMS subscription" is a
     * fact the user must be told about, while "the app could not read the platform's answer" is not —
     * refusing every default-line send because a permission is missing would be worse than the
     * failure it guards. Three states, so neither can be mistaken for the other, and never "pick a
     * SIM at random" (mission §25 explicitly forbids falling back to SIM 1).
     */
    sealed interface DefaultSimResolution {
        /** Send on exactly [subscriptionId]. */
        data class Use(val subscriptionId: Int) : DefaultSimResolution

        /** The platform answered: there is NO default SMS subscription. Fail closed. */
        data object NoDefault : DefaultSimResolution

        /** The platform's answer could not be read; the legacy platform-default manager applies. */
        data object Unknown : DefaultSimResolution
    }

    /**
     * Resolve a no-explicit-choice send against the CURRENT system default SMS subscription.
     *
     * Called at EXECUTION time, never from telemetry: a cached default line can be minutes or hours
     * stale, and MODE A's whole purpose is that the phone's default at the moment of sending is what
     * decides the line.
     *
     * @param platformDefaultSmsSubscriptionId `SubscriptionManager.getDefaultSmsSubscriptionId()`,
     *   or null when the call could not be made/answered at all.
     */
    fun resolveDefault(platformDefaultSmsSubscriptionId: Int?): DefaultSimResolution = when {
        platformDefaultSmsSubscriptionId == null -> DefaultSimResolution.Unknown
        platformDefaultSmsSubscriptionId == UNKNOWN_SUBSCRIPTION_ID -> DefaultSimResolution.NoDefault
        platformDefaultSmsSubscriptionId < 0 -> DefaultSimResolution.NoDefault
        else -> DefaultSimResolution.Use(platformDefaultSmsSubscriptionId)
    }

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

        // An explicit line choice cannot be verified when the manager reports
        // no bound subscription. Fail before modem submission.
        actual == UNKNOWN_SUBSCRIPTION_ID -> SimDecision.Refuse(requested)

        // The manager says it is bound elsewhere. Positive evidence of a mismatch, so the safe
        // direction is to refuse: a wrong-origin SMS cannot be recalled.
        else -> SimDecision.Refuse(requested)
    }
}

/**
 * The sticky per-conversation SIM rule, as a pure decision.
 *
 * ## Precedence (mission §19/§20/§30/§31)
 *
 * ```text
 * explicit one-shot override  >  conversation preferred simRef  >  Android default
 * ```
 *
 * ## The distinction that must never collapse
 *
 * ```text
 * NO PREFERENCE             => the default line is allowed
 * PREFERENCE EXISTS, UNRESOLVED => the send is BLOCKED
 * ```
 *
 * These look similar and are opposite in effect. A conversation that never named a SIM follows the
 * phone, which is normal. A conversation that named a SIM the device can no longer find must NOT
 * quietly fall back to whatever line is available — that is the silent-wrong-SIM defect, and the user
 * would never learn their choice was ignored. Sending nothing is recoverable (the user reselects);
 * sending on the wrong line is not.
 *
 * ## Why Android enforces this even when GMweb sends no ref (mission §30)
 *
 * The preference is device state. A client that forgot, or is an older version, must not be able to
 * bypass it by simply omitting a field, so the rule is applied at execution time on the device.
 */
object SendSimPreferencePolicy {

    /** Typed reasons. Stable codes, because these travel to the web UI. */
    enum class Block {
        /**
         * The conversation has a preference and no active subscription matches it.
         *
         * Covers both "the SIM was removed or replaced" and "the device secret was regenerated": in
         * neither case may another line be chosen.
         */
        PREFERRED_SIM_UNAVAILABLE,

        /** The thread's preference and the request's explicit assertion are both real and disagree. */
        SIM_PREFERENCE_CONFLICT,

        /** A reference was supplied (as preference or assertion) that is not a well-formed `simRef`. */
        INVALID_SIM_REFERENCE,

        /** The request asserted a line that is not active. */
        ASSERTED_SIM_UNAVAILABLE
    }

    /** What an asserted (request- or preference-supplied) reference resolved to on this device. */
    sealed interface Assertion {
        /** A well-formed ref that matches exactly one active subscription. */
        data class Resolved(val subscriptionId: Int) : Assertion

        /** A well-formed ref that matches no active subscription: the line is gone. */
        data object NotActive : Assertion

        /** The device could not read its SIM inventory, so the question was not answered. */
        data object InventoryUnavailable : Assertion
    }

    sealed interface Decision {
        /** Send on exactly [subscriptionId], and record it as the line that carried the message. */
        data class Send(val subscriptionId: Int) : Decision

        /** Do not hand anything to `SmsManager`. */
        data class Blocked(val reason: Block) : Decision

        /**
         * No line can be decided without the platform default, so the caller must resolve it (its
         * "no default"/"unreadable" answers are already modelled by [SendSimPolicy.resolveDefault]).
         */
        data object UsePlatformDefault : Decision
    }

    /**
     * @param conversationPreferredRef the conversation's stored `simRef`, or null/blank when it has
     *   none.
     * @param preferredAssertion what [conversationPreferredRef] resolved to, or null when there is no
     *   preference to resolve.
     * @param preferredRefWellFormed whether the stored preference is a parseable `simRef`. A stored
     *   value that is NOT parseable is a corrupted row, not a missing SIM, and is reported as such —
     *   but only when it is genuinely malformed, never merely because it matched no active line.
     * @param oneShotOverride an explicit per-message line choice, or null. Non-null means the caller
     *   has deliberately marked this send a one-shot override.
     * @param assertedRef a `simRef` carried as a ROUTING ASSERTION on the request, or null when the
     *   request asserts nothing.
     * @param assertedAssertion what [assertedRef] resolved to, or null when no ref was asserted.
     * @param assertedRefWellFormed false when [assertedRef] is supplied but not parseable. Reported
     *   separately from "not active" because a malformed ref is a caller defect, not a missing SIM.
     */
    fun decide(
        conversationPreferredRef: String?,
        preferredAssertion: Assertion?,
        preferredRefWellFormed: Boolean = true,
        oneShotOverride: Int? = null,
        assertedRef: String? = null,
        assertedAssertion: Assertion? = null,
        assertedRefWellFormed: Boolean = true
    ): Decision {
        // A malformed ref is never silently ignored: it cannot be honoured, and treating it as
        // "no assertion" would let a typo become a send on the wrong line.
        if (assertedRef != null && !assertedRefWellFormed) return Decision.Blocked(Block.INVALID_SIM_REFERENCE)

        val hasPreference = !conversationPreferredRef.isNullOrBlank()

        // A corrupted stored preference is reported as invalid rather than as an absent SIM: the two
        // need different repairs, and "unavailable" would invite the user to reselect, which succeeds
        // and hides a bug that would recur on the next send.
        if (hasPreference && !preferredRefWellFormed) {
            return Decision.Blocked(Block.INVALID_SIM_REFERENCE)
        }

        // 1. An explicit one-shot override wins for THIS message. It never edits the conversation's
        //    stored preference (mission §20) — that is the caller's obligation, and this function only
        //    chooses the line.
        if (oneShotOverride != null) {
            return Decision.Send(oneShotOverride)
        }

        // 2. Nothing pinned this conversation: the phone's default line is what the user chose.
        if (!hasPreference) {
            return when (assertedRef) {
                null -> Decision.UsePlatformDefault
                else -> when (assertedAssertion) {
                    is Assertion.Resolved -> Decision.Send(assertedAssertion.subscriptionId)
                    Assertion.NotActive -> Decision.Blocked(Block.ASSERTED_SIM_UNAVAILABLE)
                    // An unreadable inventory is not proof the asserted line is gone, so this falls
                    // back to the default rather than refusing a send the app cannot disprove.
                    Assertion.InventoryUnavailable, null -> Decision.UsePlatformDefault
                }
            }
        }

        // 3. The conversation HAS a preference. If it cannot be resolved, the send is BLOCKED. This is
        //    the branch that must never be softened into "use the default": the user chose a line, and
        //    the app cannot prove which one, so any send would be a guess.
        val preferredSubId = when (preferredAssertion) {
            is Assertion.Resolved -> preferredAssertion.subscriptionId
            Assertion.NotActive -> return Decision.Blocked(Block.PREFERRED_SIM_UNAVAILABLE)
            Assertion.InventoryUnavailable, null -> return Decision.Blocked(Block.PREFERRED_SIM_UNAVAILABLE)
        }

        // 4. The request asserted a line for a persistent-preference send. Silent selection is
        //    forbidden (mission §31); a one-shot override is the only sanctioned way to differ, and it
        //    was handled in step 1.
        if (assertedRef != null) {
            when (assertedAssertion) {
                is Assertion.Resolved ->
                    if (assertedAssertion.subscriptionId != preferredSubId) {
                        return Decision.Blocked(Block.SIM_PREFERENCE_CONFLICT)
                    }
                Assertion.NotActive -> return Decision.Blocked(Block.ASSERTED_SIM_UNAVAILABLE)
                Assertion.InventoryUnavailable, null -> Unit
            }
        }

        return Decision.Send(preferredSubId)
    }

    /**
     * The durable failure reason for a refusal.
     *
     * A blocked send never reaches the radio, and the user must still be told WHY across a restart, so
     * the reason is mapped to a persisted [SmsSendFailure] code. All four map to
     * [SmsSendFailure.SimUnavailable] because that is the honest transport-layer fact in each case: no
     * usable line was selected for this message. The distinction between them survives in the log and
     * in the ACK code, where it is actionable, rather than being invented as four transport-level
     * conditions that the radio would never produce.
     */
    fun failureFor(reason: Block): SmsSendFailure = when (reason) {
        Block.PREFERRED_SIM_UNAVAILABLE,
        Block.ASSERTED_SIM_UNAVAILABLE,
        Block.SIM_PREFERENCE_CONFLICT,
        Block.INVALID_SIM_REFERENCE -> SmsSendFailure.SimUnavailable
    }

    /**
     * True when a decision refuses the send BEFORE any line is resolved.
     *
     * This is the zero-physical-send guarantee in one predicate: a blocked decision is handled by the
     * caller before `SmsManager` is touched, so the number of modem submissions for a blocked
     * preference is exactly zero. Asserted in tests rather than assumed from call-site ordering.
     */
    fun blocksBeforeAnySubmission(decision: Decision): Boolean = decision is Decision.Blocked
}
