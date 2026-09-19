package com.autonomousone.messages.messaging

import com.autonomousone.messages.data.MessageClassificationEntity
import com.autonomousone.messages.data.MessageUserStateEntity

/**
 * THE single source of truth for GLOBAL OTP retention (v3.4.0 FEATURE 14).
 *
 * PURE BY DESIGN: nothing here touches Android, Room, WorkManager or the clock.
 * Every scheduling and eligibility decision in the app goes through these two
 * functions, so the "what gets cleaned and when" contract is unit-testable on
 * the JVM and can never drift between the ingest path, the settings screen and
 * the cleanup worker.
 *
 * HARD RULES ENCODED HERE (see docs/agents/v340-briefing.md)
 * --------------------------------------------------------
 *  1. OFF BY DEFAULT. With retention disabled nothing is ever enrolled, so
 *     nothing is ever cleaned — even messages that carry a stale deadline from
 *     an earlier enrollment are refused when the setting is off.
 *  2. A message is eligible only when ALL hold:
 *       isOtp AND confidence >= [HIGH_CONFIDENCE] AND INCOMING (type 1)
 *       AND NOT starred AND NOT keepFromOtpCleanup AND NOT already trashed.
 *  3. Cleanup means MOVE TO TRASH. The policy decides only WHETHER and WHEN;
 *     it never deletes anything.
 *  4. The absolute floor is a REAL system-clock timestamp supplied by the
 *     caller — no device-uptime or `System.currentTimeMillis()` shortcut ever
 *     sneaks in here, because WorkManager delays are wall-clock based.
 *
 * WHY THE ABSOLUTE FLOOR EXISTS
 * -----------------------------
 * A deadline that has already passed by more than [MAX_OVERDUE_MILLIS] can only
 * mean one thing: the enrollment timestamp came from a WRONG CLOCK (a manual
 * date change, a restored backup, a phone that booted with 1970). Trashing
 * months of OTPs on the next worker tick would look like data loss, so such an
 * enrollment is REFUSED and reported as [EligibilityReason.CLOCK_SKEW]. The
 * deadline stays in the table, so the next successful reschedule heals it.
 */
object OtpRetentionPolicy {

    /**
     * The defined HIGH-confidence bar for automatic OTP cleanup.
     *
     * DELEGATED, not redefined: [OtpDetector.HIGH_CONFIDENCE] is the one
     * threshold the unified detector and Smart Categories already share, and a
     * second constant here would be exactly the kind of drift that lets a weak
     * "code is …" match become auto-deletable.
     *
     * [OtpDetector] rests at exactly 0.9f for a STRONG context (an explicit
     * "verification code" / "رمز پویا" marker) and caps a weak keyword below the
     * floor, so automatic cleanup only ever touches messages that carry a real
     * OTP marker.
     */
    const val HIGH_CONFIDENCE: Float = OtpDetector.HIGH_CONFIDENCE

    /**
     * Telephony type of an INCOMING message. Outgoing messages are never
     * cleaned: the user typed them, and a sent OTP is a record of a real action.
     */
    const val TYPE_INCOMING: Int = 1

    /**
     * How far in the past a computed deadline may already be before it is
     * treated as CLOCK SKEW instead of "due now". One day is longer than any
     * delay Android can impose on a worker, so a genuinely due OTP is never
     * refused by it.
     */
    const val MAX_OVERDUE_MILLIS: Long = 24L * 60 * 60 * 1000

    /** Why a message is (not) enrolled — diagnostics vocabulary, never content. */
    enum class EligibilityReason {
        ELIGIBLE,
        RETENTION_DISABLED,
        NOT_OTP,
        LOW_CONFIDENCE,
        NOT_INCOMING,
        STARRED,
        KEPT_BY_USER,
        ALREADY_TRASHED,
        CLOCK_SKEW;

        /** True only for [ELIGIBLE]; named for readable call sites. */
        val isEligible: Boolean get() = this == ELIGIBLE
    }

    /**
     * The decision for ONE message.
     *
     * [eligibleAt] is non-null exactly when [eligible] is true, so a caller can
     * never accidentally schedule "cleanup at epoch 0".
     */
    data class Plan(
        val eligible: Boolean,
        val eligibleAt: Long?,
        val reason: EligibilityReason
    ) {
        companion object {
            fun refuse(reason: EligibilityReason): Plan = Plan(false, null, reason)
        }
    }

    /**
     * The state needed to judge one message, built from the classification row
     * plus the user-state row. An ABSENT user-state row means pure defaults —
     * not starred, not kept, not trashed.
     */
    data class MessageState(
        val isOtp: Boolean,
        val confidence: Float,
        val isIncoming: Boolean,
        val starred: Boolean,
        val keepFromOtpCleanup: Boolean,
        val trashed: Boolean
    ) {
        companion object {
            /**
             * Builds the state from the (nullable) persisted rows.
             *
             * [messageType] comes from the `messages` mirror on the ingest path
             * because `message_classification` deliberately does NOT mirror
             * direction (the classification row is derived data; direction is
             * provider truth and is read from whichever row already has it).
             *
             * A `null` type is treated as NOT INCOMING, which FAILS SAFE: an
             * unreadable direction costs nothing (the row is simply left alone)
             * while guessing "incoming" could move an outgoing message to Trash.
             */
            fun of(
                classification: MessageClassificationEntity?,
                userState: MessageUserStateEntity?,
                messageType: Int?
            ): MessageState = MessageState(
                isOtp = classification?.isOtp == true,
                confidence = classification?.confidence ?: 0f,
                isIncoming = messageType == TYPE_INCOMING,
                starred = userState?.starred == true,
                keepFromOtpCleanup = userState?.keepFromOtpCleanup == true,
                trashed = userState?.isTrashed == true
            )
        }
    }

    /**
     * Decides whether [state] may be cleaned and when.
     *
     * @param enabled  the global switch. False ⇒ refuse everything.
     * @param retentionMillis the user's chosen retention. Values below 1 hour
     *        are refused by the settings screen; this function treats any
     *        non-positive value as "no enrolment".
     * @param anchorMillis when retention starts counting for THIS enrollment.
     *        It is the MESSAGE's own timestamp on the ingest path and `now` on
     *        the explicit "apply to existing" path.
     * @param nowMillis the real wall clock. An eligible deadline must be at or
     *        after `now - `[MAX_OVERDUE_MILLIS].
     */
    fun plan(
        state: MessageState,
        enabled: Boolean,
        retentionMillis: Long,
        anchorMillis: Long,
        nowMillis: Long
    ): Plan {
        if (!enabled) return Plan.refuse(EligibilityReason.RETENTION_DISABLED)
        if (!state.isOtp) return Plan.refuse(EligibilityReason.NOT_OTP)
        if (state.confidence < HIGH_CONFIDENCE) return Plan.refuse(EligibilityReason.LOW_CONFIDENCE)
        // USER PROTECTION OUTRANKS ORDINARY ELIGIBILITY. A starred message — or one
        // the user explicitly kept — is protected REGARDLESS of any other rule that
        // would also reject it, so the reported reason is STARRED / KEPT_BY_USER
        // rather than the incidental first failure (NOT_INCOMING, ALREADY_TRASHED, …).
        // This is a diagnostics-ordering fix on top of an unchanged safety outcome:
        // both orders refuse the message, and neither ever auto-cleans it.
        if (state.starred) return Plan.refuse(EligibilityReason.STARRED)
        if (state.keepFromOtpCleanup) return Plan.refuse(EligibilityReason.KEPT_BY_USER)
        if (!state.isIncoming) return Plan.refuse(EligibilityReason.NOT_INCOMING)
        if (state.trashed) return Plan.refuse(EligibilityReason.ALREADY_TRASHED)
        if (retentionMillis <= 0L) return Plan.refuse(EligibilityReason.RETENTION_DISABLED)

        val anchor = if (anchorMillis > 0L) anchorMillis else nowMillis
        val eligibleAt = anchor + retentionMillis
        if (eligibleAt < nowMillis - MAX_OVERDUE_MILLIS) {
            return Plan.refuse(EligibilityReason.CLOCK_SKEW)
        }
        return Plan(true, eligibleAt, EligibilityReason.ELIGIBLE)
    }

    /**
     * The deadline to STORE for one message. [Plan.eligibleAt] when eligible,
     * and 0 (the schema's "not scheduled") otherwise.
     *
     * Persisting an explicit 0 for an ineligible message is deliberate: it also
     * UNENROLLS a message that became protected (starred, kept, or trashed)
     * after it was enrolled, so the worker's index-backed due query can never
     * see it.
     */
    fun deadlineFor(plan: Plan): Long = plan.eligibleAt ?: 0L

    /** True when a persisted deadline still needs to be recomputed. */
    fun needsReschedule(previousDeadline: Long, plan: Plan): Boolean =
        previousDeadline != deadlineFor(plan)
}
