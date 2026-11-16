package com.autonomousone.messages.repository

import android.content.Context
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.security.SuggestionCardVault
import com.autonomousone.messages.sync.SuggestionsReadyCommand
import com.autonomousone.messages.utils.DiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The conversation's suggestions, read back from `suggestion_cards` in a form the UI can render.
 *
 * One suggestion batch is stored as ONE sealed row (see
 * [com.autonomousone.messages.data.SuggestionCardEntity]), so this repository turns rows into the
 * individual alternatives the user sees — after opening the sealed payload.
 *
 * Decryption is Keystore-bound and therefore happens on [Dispatchers.IO], never in composition. A row
 * that cannot be opened (key lost to a restore, corrupt blob, unknown payload version) is DELETED and
 * skipped: it can never become visible, and keeping undecryptable content around has no use. That is
 * also why this read path can fail without failing the screen — an empty list is a valid answer, and
 * the UI falls back to local quick replies.
 */
class SuggestionCardsRepository private constructor(context: Context) {

    companion object {
        @Volatile
        private var instance: SuggestionCardsRepository? = null

        fun get(context: Context): SuggestionCardsRepository =
            instance ?: synchronized(this) {
                instance ?: SuggestionCardsRepository(context.applicationContext).also {
                    instance = it
                }
            }
    }

    private val appContext = context
    private val db = MessagesDatabase.get(appContext)

    /** Decoded, unordered-alternatives-preserved batches for one conversation. */
    fun observe(threadId: Long): Flow<List<DeliveredSuggestionBatch>> =
        db.suggestionCardDao().observeForThread(threadId)
            .map { rows -> rows.mapNotNull { decode(it) } }
            .flowOn(Dispatchers.IO)

    /** Remove every card for a conversation (an explicit dismissal). Returns rows deleted. */
    suspend fun dismiss(threadId: Long): Int = withContext(Dispatchers.IO) {
        db.suggestionCardDao().deleteForThread(threadId)
    }

    /** Automatic cleanup: rows whose `expires_at` has passed can never be shown again. */
    suspend fun clearExpired(now: Long = System.currentTimeMillis()): Int =
        withContext(Dispatchers.IO) { db.suggestionCardDao().deleteExpired(now) }

    private suspend fun decode(
        row: com.autonomousone.messages.data.SuggestionCardEntity
    ): DeliveredSuggestionBatch? {
        val opened = SuggestionCardVault.open(row.payloadCiphertext, row.threadId)
        if (opened == null) {
            // Unreadable (lost Keystore key / corrupt row). Delete rather than leave content that can
            // never be rendered; the user simply sees the quick-reply fallback.
            runCatching { db.suggestionCardDao().deleteCard(row.threadId, row.forMessageId) }
            DiagnosticLog.event("GM_SUGGEST", "undecryptable card removed thread=${row.threadId}")
            return null
        }
        val payload = try {
            SuggestionsReadyCommand.decodePayload(String(opened, Charsets.UTF_8))
        } catch (e: Exception) {
            null
        } finally {
            opened.fill(0)
        }
        if (payload == null) {
            runCatching { db.suggestionCardDao().deleteCard(row.threadId, row.forMessageId) }
            return null
        }
        return DeliveredSuggestionBatch(
            threadId = row.threadId,
            anchorMessageId = row.forMessageId,
            expiresAtMs = row.expiresAt,
            receivedAt = row.receivedAt,
            generation = row.generation,
            reasonRef = payload.reasonRef,
            suggestions = payload.suggestions
        )
    }
}

/**
 * One `SUGGESTIONS_READY` batch as delivered, still unstripped of policy.
 *
 * [anchorMessageId] and [expiresAtMs] travel with the batch because visibility is decided per batch: a
 * suggestion is only meaningful while its anchor is the newest inbound message and the batch has not
 * expired.
 */
data class DeliveredSuggestionBatch(
    val threadId: Long,
    val anchorMessageId: String,
    val expiresAtMs: Long,
    val receivedAt: Long,
    val generation: Int,
    val reasonRef: String?,
    val suggestions: List<SuggestionsReadyCommand.Suggestion>
)
