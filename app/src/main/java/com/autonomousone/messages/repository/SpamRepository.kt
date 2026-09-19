package com.autonomousone.messages.repository

import com.autonomousone.messages.data.ConversationPreferenceEntity
import com.autonomousone.messages.data.MessageCategory
import com.autonomousone.messages.utils.DiagnosticLog

/**
 * LOCAL spam report / block provenance (v3.4.0 FEATURE 15).
 *
 * WHAT THIS IS — AND IS NOT
 * -------------------------
 * "Mark as spam & block" is a LOCAL action. There is no carrier, Google or
 * server report endpoint in v3.4.0, so nothing here may claim a message was
 * "reported" to anybody: the report is a durable on-device decision. Messages
 * are NEVER deleted by a report — the conversation moves to the Spam category
 * and the sender is blocked.
 *
 * THE PROVENANCE RULE (the reason this class exists)
 * -------------------------------------------------
 * Not-Spam must return the conversation to the Inbox, but it must NOT unblock a
 * number the user had blocked MANUALLY at some earlier point. Blocking and
 * spam-reporting are different user intents that happen to share one blocklist,
 * so the report records whether IT created the block
 * ([ConversationPreferenceEntity.spamBlockedByReport]) exactly once, at report
 * time:
 *
 *   wasBlockedBefore == false → block added by the report   → Not-Spam unblocks
 *   wasBlockedBefore == true  → the user's own earlier block → Not-Spam keeps it
 *
 * The decision is a PURE function ([SpamReportPlan.from]) so it is unit-tested
 * without Room, ContentResolver or Android — the durable writes are then applied
 * through the same field-scoped DAO writers everything else uses, so a spam
 * report can never clobber a mute, a category override or a manual-unread flag.
 */
object SpamReportPolicy {

    /** Category a reported conversation resolves to while the report stands. */
    val REPORTED_CATEGORY: MessageCategory = MessageCategory.SPAM

    /**
     * Effective category for a conversation, applying the user's spam report and
     * category override on top of the automatic classifier result.
     *
     * Precedence, highest first:
     *  1. an explicit spam report (SPAM) — it is a deliberate user decision;
     *  2. the user's category override;
     *  3. the automatic classifier;
     *  4. UNKNOWN.
     */
    fun effectiveCategory(
        preference: ConversationPreferenceEntity?,
        automatic: MessageCategory?
    ): MessageCategory {
        if (preference?.spam == true) return REPORTED_CATEGORY
        val override = preference?.categoryOverride
        if (override != null) return MessageCategory.from(override)
        return automatic ?: MessageCategory.UNKNOWN
    }
}

/**
 * The complete, already-decided effect of ONE user action.
 *
 * Produced by a pure policy function and then applied; keeping the decision and
 * the writing apart is what makes the provenance rules testable.
 */
data class SpamActionPlan(
    /** Normalized address the blocklist must be written with. */
    val normalizedAddress: String,
    /** Add the address to the blocklist. */
    val blockAddress: Boolean,
    /** Remove the address from the blocklist. */
    val unblockAddress: Boolean,
    /** New value of `conversation_preferences.spam`. */
    val spam: Boolean,
    /** New value of `conversation_preferences.spamBlockedByReport`. */
    val blockedByReport: Boolean,
    /**
     * True only when this report created the block, i.e. Undo may remove it.
     * Carried explicitly so the caller never has to re-derive it.
     */
    val clearBlockProvenanceOnUndo: Boolean
) {
    companion object {

        /**
         * Plan for "Mark as spam & block".
         *
         * @param normalizedAddress address normalized by the caller
         *   (`BlocklistRepository.normalize`).
         * @param wasBlockedBefore whether the address was ALREADY on the
         *   user's blocklist before this report. A pre-existing manual block is
         *   preserved as the user's own decision, so the report does not claim
         *   provenance over it.
         */
        fun report(
            normalizedAddress: String,
            wasBlockedBefore: Boolean
        ): SpamActionPlan = SpamActionPlan(
            normalizedAddress = normalizedAddress,
            // Adding an address that is already blocked is a harmless no-op, but
            // it is skipped so a report can never overwrite the manual block's
            // shape (and the system contract write is not re-issued needlessly).
            blockAddress = !wasBlockedBefore,
            unblockAddress = false,
            spam = true,
            blockedByReport = !wasBlockedBefore,
            // Not-Spam may clear the block ONLY when the report created it.
            clearBlockProvenanceOnUndo = !wasBlockedBefore
        )

        /**
         * Plan for "Not spam".
         *
         * @param blockedByReport the PROVENANCE read back from the stored
         *   preference. Only a block the report itself created is removed; a
         *   number the user had blocked manually stays blocked.
         */
        fun notSpam(
            normalizedAddress: String,
            blockedByReport: Boolean
        ): SpamActionPlan = SpamActionPlan(
            normalizedAddress = normalizedAddress,
            blockAddress = false,
            unblockAddress = blockedByReport,
            spam = false,
            blockedByReport = false,
            clearBlockProvenanceOnUndo = blockedByReport
        )

        /**
         * Plan for a plain Block / Unblock from Conversation Info. It touches the
         * blocklist ONLY: an explicit manual block must never be recorded as
         * spam-report provenance, or a later Not-Spam would silently unblock it.
         */
        fun block(normalizedAddress: String, blocked: Boolean): SpamActionPlan =
            SpamActionPlan(
                normalizedAddress = normalizedAddress,
                blockAddress = blocked,
                unblockAddress = !blocked,
                spam = false,
                blockedByReport = false,
                clearBlockProvenanceOnUndo = false
            )
    }
}

/**
 * The blocklist + user-state writes a plan needs. Deliberately narrow so the
 * planner is testable and so this feature can never reach around the existing
 * `BlocklistRepository` / `ConversationPreferenceRepository` authorities.
 */
interface SpamBlockPort {
    fun isBlocked(normalizedAddress: String): Boolean
    fun block(address: String)
    fun unblock(address: String)
    suspend fun markSpam(threadId: Long, blockedByReport: Boolean, now: Long)
    suspend fun clearSpam(threadId: Long, clearBlockProvenance: Boolean, now: Long)
}

/**
 * Applies [SpamActionPlan]s. Every write goes through the existing authorities:
 * the ONE blocklist (`BlocklistRepository`) and the field-scoped
 * `ConversationPreferenceDao` writers. Nothing here deletes a message.
 */
class SpamRepository(
    private val port: SpamBlockPort,
    private val now: () -> Long = System::currentTimeMillis
) {

    /**
     * "Mark as spam & block".
     *
     * Reads the pre-existing block state FIRST (before any write), then applies
     * the plan. Reading first is what makes the provenance trustworthy: once the
     * report has added the block, "was it already blocked?" is unanswerable.
     */
    suspend fun reportSpam(threadId: Long, address: String): SpamReportOutcome {
        val normalized = BlocklistRepository.normalize(address)
        if (normalized.isBlank()) {
            return SpamReportOutcome.InvalidAddress
        }
        val wasBlockedBefore = port.isBlocked(normalized)
        val plan = SpamActionPlan.report(normalized, wasBlockedBefore)

        // NOTE: the category override is deliberately NOT cleared. A report is a
        // reversible user action, and destroying an unrelated user preference to
        // make the report visible would lose data on Undo. SpamReportPolicy
        // .effectiveCategory instead ranks an active report ABOVE the override.
        if (plan.blockAddress) port.block(address)
        port.markSpam(threadId, plan.blockedByReport, now())

        DiagnosticLog.event(
            "SPAM_ACTION",
            "thread=$threadId reported=1 blockedByReport=${plan.blockedByReport} " +
                "preExistingBlock=$wasBlockedBefore"
        )
        return SpamReportOutcome.Reported(plannedUnblockOnUndo = plan.clearBlockProvenanceOnUndo)
    }

    /** "Not spam": returns the conversation to the Inbox without deleting anything. */
    suspend fun notSpam(threadId: Long, address: String, blockedByReport: Boolean): SpamReportOutcome {
        val normalized = BlocklistRepository.normalize(address)
        if (normalized.isBlank()) {
            return SpamReportOutcome.InvalidAddress
        }
        val plan = SpamActionPlan.notSpam(normalized, blockedByReport)
        if (plan.unblockAddress) port.unblock(address)
        port.clearSpam(threadId, plan.clearBlockProvenanceOnUndo, now())

        DiagnosticLog.event(
            "SPAM_ACTION",
            "thread=$threadId reported=0 unblocked=${plan.unblockAddress}"
        )
        return SpamReportOutcome.NotSpam(unblocked = plan.unblockAddress)
    }

    /**
     * Plain manual Block / Unblock. Never touches the spam flag, so a manual
     * block performed in Conversation Info stays the user's own decision.
     */
    suspend fun setBlocked(address: String, blocked: Boolean): SpamReportOutcome {
        val normalized = BlocklistRepository.normalize(address)
        if (normalized.isBlank()) {
            return SpamReportOutcome.InvalidAddress
        }
        val plan = SpamActionPlan.block(normalized, blocked)
        if (plan.blockAddress) port.block(address) else port.unblock(address)

        DiagnosticLog.event("SPAM_ACTION", "manual_block=$blocked")
        return SpamReportOutcome.BlockChanged(blocked = blocked)
    }

    /** The address whose active notification must be cancelled after a report. */
    fun notificationIdentity(address: String): Int = address.hashCode()
}

/** Result of a spam/block action, so the UI never has to guess what happened. */
sealed interface SpamReportOutcome {
    /** This report created the block, so Undo will unblock. */
    data class Reported(val plannedUnblockOnUndo: Boolean) : SpamReportOutcome

    data class NotSpam(val unblocked: Boolean) : SpamReportOutcome

    data class BlockChanged(val blocked: Boolean) : SpamReportOutcome

    /** Address was blank/unusable — nothing was written. */
    data object InvalidAddress : SpamReportOutcome
}
