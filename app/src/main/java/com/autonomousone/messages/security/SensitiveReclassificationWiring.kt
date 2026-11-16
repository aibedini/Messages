package com.autonomousone.messages.security

import android.content.Context
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.utils.DiagnosticLog

/**
 * LDV-004 PR-E (slice 2c) — production wiring for the classification → revocation seam.
 *
 * The pure [SensitiveReclassificationHook] gets the two real ports it needs:
 *
 *  - **thread id → conversation id**: the opaque cloud identity from `remote_conversation_map`
 *    (the same id a grant names — `ConversationScopeViewModel` uses it too). A thread that was
 *    never synced has no mapping and is skipped: no grant can name it.
 *  - **revoke**: [ConversationGrantCoordinator.revokeForSensitiveReclassification] through the
 *    normal [ConversationGrantWiring], so the tombstone signatures come from the same
 *    non-exportable Keystore root as every grant.
 *
 * Built once per `ClassificationRepository`, which is itself process-cached by
 * `MessageClassificationService`; the coordinator is stateless apart from its decision mutex.
 *
 * Diagnostics are counts and a hashed conversation reference only
 * ([conversationRef]); the raw conversation id, message bodies, addresses and keys never reach
 * a log line through this file. Failures are reported, never thrown: a classification write
 * must not fail because a revocation could not be attempted.
 */
object SensitiveReclassificationWiring {
    private const val TAG = "CONV_SCOPE"

    fun create(context: Context): SensitiveReclassificationHook {
        val appContext = context.applicationContext
        val coordinator = ConversationGrantWiring.create(appContext)
        return SensitiveReclassificationHook(
            conversationIdOf = { threadId ->
                MessagesDatabase.get(appContext)
                    .remoteConversationMapDao()
                    .getByThreadId(threadId)
                    ?.conversationId
            },
            revoke = { conversationId, category ->
                coordinator.revokeForSensitiveReclassification(conversationId, category)
            },
            onOutcome = ::log,
        )
    }

    private fun log(outcome: SensitiveReclassificationOutcome) {
        val counts = "conv=${outcome.conversationRef} category=${outcome.category} " +
            "devices=${outcome.devices} revoked=${outcome.revoked} " +
            "already=${outcome.alreadyRevoked} notGranted=${outcome.notGranted} " +
            "failed=${outcome.failed}"
        when {
            outcome.error != null -> DiagnosticLog.event(
                TAG,
                "sensitive-revoke-error $counts",
                outcome.error,
            )

            outcome.failed > 0 || outcome.enumerationFailed ->
                DiagnosticLog.w(TAG, "sensitive-revoke-incomplete $counts")

            // Nothing to do is the steady state for a non-sensitive classification.
            outcome.skipped -> Unit

            else -> DiagnosticLog.event(TAG, "sensitive-revoke $counts")
        }
    }
}
