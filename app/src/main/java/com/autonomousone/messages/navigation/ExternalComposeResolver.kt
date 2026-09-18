package com.autonomousone.messages.navigation

import android.content.Context
import com.autonomousone.messages.data.ConversationEntity
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.repository.ContactRepository
import com.autonomousone.messages.utils.DiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface ExternalComposeTarget {
    val phone: String
    val draft: String

    data class ExistingConversation(
        val threadId: Long,
        override val phone: String,
        val displayName: String,
        override val draft: String
    ) : ExternalComposeTarget

    data class NewConversation(
        override val phone: String,
        override val draft: String
    ) : ExternalComposeTarget
}

/** Resolves external Dialer/Call Log compose requests entirely from Room. */
class ExternalComposeResolver(context: Context) {
    private val appContext = context.applicationContext
    private val conversations = MessagesDatabase.get(appContext).conversationDao()

    suspend fun resolve(phone: String, draft: String): ExternalComposeTarget =
        withContext(Dispatchers.IO) {
            val normalized = ContactRepository.normalizePhone(phone)
            if (normalized.isBlank()) {
                return@withContext ExternalComposeTarget.NewConversation(phone, draft)
            }

            val exact = conversations.newestByNormalizedAddress(normalized)
            val match = exact ?: if (normalized.length >= MIN_MATCH_LENGTH) {
                conversations.recentByAddressSuffix(
                    suffix = normalized.takeLast(MIN_MATCH_LENGTH),
                    minimumLength = MIN_MATCH_LENGTH,
                    limit = MAX_SUFFIX_CANDIDATES
                ).firstOrNull { candidateMatches(normalized, it) }
            } else null

            val result = targetFor(phone, draft, match)
            DiagnosticLog.event(
                "EXTERNAL_COMPOSE",
                "phone=${DiagnosticLog.phoneToken(phone)} " +
                    "resolution=${if (result is ExternalComposeTarget.ExistingConversation) "existing" else "new"} " +
                    "threadId=${match?.threadId ?: 0L}"
            )
            result
        }

    companion object {
        const val MIN_MATCH_LENGTH = 7
        private const val MAX_SUFFIX_CANDIDATES = 20

        internal fun candidateMatches(
            normalizedPhone: String,
            candidate: ConversationEntity
        ): Boolean {
            val candidatePhone = candidate.normalizedAddress.ifBlank { candidate.rawAddress }
            if (ContactRepository.sameConversation(normalizedPhone, candidatePhone)) return true

            // The existing matcher deliberately compares whole normalized
            // suffixes. External Dialer intents also need the safe +98/09
            // equivalence requested here, so compare only the already-bounded
            // subscriber suffix used by the DAO lookup. Never do this for
            // short/service codes.
            val requested = ContactRepository.normalizePhone(normalizedPhone)
            val stored = ContactRepository.normalizePhone(candidatePhone)
            return requested.length >= MIN_MATCH_LENGTH &&
                stored.length >= MIN_MATCH_LENGTH &&
                requested.takeLast(MIN_MATCH_LENGTH) == stored.takeLast(MIN_MATCH_LENGTH)
        }

        internal fun targetFor(
            phone: String,
            draft: String,
            match: ConversationEntity?
        ): ExternalComposeTarget = if (match != null) {
            ExternalComposeTarget.ExistingConversation(
                threadId = match.threadId,
                phone = phone,
                displayName = match.rawAddress.ifBlank { phone },
                draft = draft
            )
        } else {
            ExternalComposeTarget.NewConversation(phone, draft)
        }
    }
}
