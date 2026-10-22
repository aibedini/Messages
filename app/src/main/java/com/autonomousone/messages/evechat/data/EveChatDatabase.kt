package com.autonomousone.messages.evechat.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Upsert
import com.autonomousone.messages.evechat.model.ConvStatus
import com.autonomousone.messages.evechat.model.ConversationPatch
import com.autonomousone.messages.evechat.model.EveChatConversation
import com.autonomousone.messages.evechat.model.EveChatMessage
import com.autonomousone.messages.evechat.model.SendState
import com.autonomousone.messages.evechat.model.SenderType
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray

class EveChatConverters {
    @TypeConverter
    fun fromList(value: List<String>): String = JSONArray(value).toString()

    @TypeConverter
    fun toList(value: String): List<String> = runCatching {
        val arr = JSONArray(value)
        List(arr.length()) { arr.getString(it) }
    }.getOrDefault(emptyList())
}

@Dao
abstract class EveChatDao {

    // ── conversations ────────────────────────────────────────────────────

    @Query("SELECT * FROM conversations WHERE status IN (:statuses) ORDER BY COALESCE(lastMessageAt, createdAt) DESC")
    abstract fun observeByStatus(statuses: List<String>): Flow<List<EveChatConversation>>

    @Query(
        "SELECT * FROM conversations WHERE assignedAgentId = :agentId AND status IN ('pending','active') " +
            "ORDER BY COALESCE(lastMessageAt, createdAt) DESC"
    )
    abstract fun observeAssignedTo(agentId: String): Flow<List<EveChatConversation>>

    @Query("SELECT * FROM conversations ORDER BY COALESCE(lastMessageAt, createdAt) DESC")
    abstract fun observeAll(): Flow<List<EveChatConversation>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    abstract fun observeConversation(id: String): Flow<EveChatConversation?>

    @Query("SELECT * FROM conversations WHERE id = :id")
    abstract suspend fun conversation(id: String): EveChatConversation?

    @Query("SELECT COALESCE(SUM(unreadAgent), 0) FROM conversations WHERE status IN ('pending','active')")
    abstract fun observeUnreadTotal(): Flow<Int>

    @Query("SELECT * FROM conversations WHERE status IN ('pending','active')")
    abstract suspend fun openConversations(): List<EveChatConversation>

    @Query("SELECT * FROM conversations WHERE unreadAgent > 0 AND status IN ('pending','active')")
    abstract suspend fun unreadConversations(): List<EveChatConversation>

    @Upsert
    protected abstract suspend fun upsertConversationRows(rows: List<EveChatConversation>)

    @Query("UPDATE conversations SET unreadAgent = 0 WHERE id = :id")
    abstract suspend fun clearUnread(id: String)

    @Query("UPDATE conversations SET unreadAgent = unreadAgent + 1 WHERE id = :id")
    abstract suspend fun incrementUnread(id: String)

    @Query("UPDATE conversations SET historyComplete = :complete WHERE id = :id")
    abstract suspend fun setHistoryComplete(id: String, complete: Boolean)

    @Query(
        "UPDATE conversations SET lastMessagePreview = :preview, lastMessageSenderType = :senderType, " +
            "lastMessageAt = MAX(COALESCE(lastMessageAt, 0), :at) WHERE id = :id AND COALESCE(lastMessageAt, 0) <= :at"
    )
    abstract suspend fun updatePreview(id: String, preview: String, senderType: String, at: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    protected abstract suspend fun deleteConversationRow(id: String)

    /**
     * Server snapshots never carry the preview or local history flags, and may be older than a
     * live event that landed while the request was in flight: keep the newer activity time.
     */
    @Transaction
    open suspend fun mergeConversations(incoming: List<EveChatConversation>) {
        val merged = incoming.map { c ->
            val old = conversation(c.id) ?: return@map c
            c.copy(
                visitorName = c.visitorName ?: old.visitorName,
                visitorEmail = c.visitorEmail ?: old.visitorEmail,
                visitorPhone = c.visitorPhone ?: old.visitorPhone,
                assignedAgentName = if (c.assignedAgentId == old.assignedAgentId) {
                    c.assignedAgentName ?: old.assignedAgentName
                } else c.assignedAgentName,
                lastMessageAt = maxOfNullable(c.lastMessageAt, old.lastMessageAt),
                lastMessagePreview = old.lastMessagePreview,
                lastMessageSenderType = old.lastMessageSenderType,
                historyComplete = old.historyComplete
            )
        }
        upsertConversationRows(merged)
    }

    /** @return false when the conversation is unknown locally and must be fetched. */
    @Transaction
    open suspend fun applyPatch(patch: ConversationPatch, isActive: Boolean): Boolean {
        val old = conversation(patch.conversationId) ?: return false
        val unread = when {
            isActive -> 0
            patch.senderType == SenderType.AGENT -> 0
            patch.senderType == SenderType.VISITOR && patch.unreadAgent != null ->
                // The server reports the count read BEFORE its own increment.
                maxOf(old.unreadAgent, patch.unreadAgent + 1)
            else -> old.unreadAgent
        }
        upsertConversationRows(
            listOf(
                old.copy(
                    status = patch.status ?: old.status,
                    assignedAgentId = patch.assignedAgentId ?: old.assignedAgentId,
                    assignedAgentName = when {
                        patch.assignedAgentId == null -> old.assignedAgentName
                        patch.assignedAgentId == old.assignedAgentId -> patch.assignedAgentName ?: old.assignedAgentName
                        else -> patch.assignedAgentName
                    },
                    lastMessageAt = maxOfNullable(patch.lastMessageAt, old.lastMessageAt),
                    unreadAgent = unread
                )
            )
        )
        return true
    }

    @Transaction
    open suspend fun deleteConversation(id: String) {
        deleteMessagesOf(id)
        deleteConversationRow(id)
    }

    // ── messages ─────────────────────────────────────────────────────────

    /** Sent history in time order, then the outbox in the order it was written (WhatsApp-style). */
    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId " +
            "ORDER BY CASE sendState WHEN 'SENT' THEN 0 ELSE 1 END, createdAt, id"
    )
    abstract fun observeMessages(conversationId: String): Flow<List<EveChatMessage>>

    @Query("SELECT MAX(createdAt) FROM messages WHERE conversationId = :conversationId AND sendState = 'SENT'")
    abstract suspend fun newestSentAt(conversationId: String): Long?

    @Query("SELECT MIN(createdAt) FROM messages WHERE conversationId = :conversationId AND sendState = 'SENT'")
    abstract suspend fun oldestSentAt(conversationId: String): Long?

    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId AND senderType = 'visitor' " +
            "AND sendState = 'SENT' AND createdAt > :after ORDER BY createdAt"
    )
    abstract suspend fun visitorMessagesAfter(conversationId: String, after: Long): List<EveChatMessage>

    @Query(
        "SELECT id FROM messages WHERE conversationId = :conversationId AND sendState = 'SENT' " +
            "ORDER BY createdAt DESC, id DESC LIMIT 1"
    )
    abstract suspend fun newestSentId(conversationId: String): String?

    @Query("SELECT * FROM messages WHERE sendState = 'PENDING' ORDER BY createdAt, id")
    abstract suspend fun outbox(): List<EveChatMessage>

    @Query("SELECT * FROM messages WHERE id = :id")
    abstract suspend fun message(id: String): EveChatMessage?

    @Query("SELECT COUNT(*) FROM messages WHERE id IN (:ids)")
    abstract suspend fun countExisting(ids: List<String>): Int

    @Upsert
    abstract suspend fun upsertMessageRows(rows: List<EveChatMessage>)

    @Query("UPDATE messages SET sendState = :state, lastError = :error, attempts = attempts + :attemptDelta WHERE id = :id")
    abstract suspend fun updateSendState(id: String, state: SendState, error: String?, attemptDelta: Int)

    @Query("DELETE FROM messages WHERE clientMessageId = :clientMessageId AND id != :keepId")
    protected abstract suspend fun deleteLocalCopy(clientMessageId: String, keepId: String)

    @Query("DELETE FROM messages WHERE id = :id")
    abstract suspend fun deleteMessage(id: String)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    protected abstract suspend fun deleteMessagesOf(conversationId: String)

    /**
     * The single reconciliation point for server messages: whichever arrives first — the REST
     * response or the socket echo — replaces the optimistic outbox row carrying the same
     * client id, so a message can never be shown twice.
     */
    @Transaction
    open suspend fun upsertServerMessages(messages: List<EveChatMessage>) {
        if (messages.isEmpty()) return
        val rows = messages.map { m ->
            m.clientMessageId?.let { deleteLocalCopy(it, m.id) }
            m.copy(sendState = SendState.SENT, lastError = null)
        }
        upsertMessageRows(rows)
    }

    @Query("DELETE FROM messages")
    protected abstract suspend fun deleteAllMessages()

    @Query("DELETE FROM conversations")
    protected abstract suspend fun deleteAllConversations()

    @Transaction
    open suspend fun clearAll() {
        deleteAllMessages()
        deleteAllConversations()
    }

    private fun maxOfNullable(a: Long?, b: Long?): Long? = when {
        a == null -> b
        b == null -> a
        else -> maxOf(a, b)
    }
}

@Database(
    entities = [EveChatConversation::class, EveChatMessage::class],
    version = 1,
    exportSchema = true
)
@TypeConverters(EveChatConverters::class)
abstract class EveChatDatabase : RoomDatabase() {
    abstract fun dao(): EveChatDao

    companion object {
        private const val NAME = "evechat.db"

        /**
         * Server state is re-fetched on demand, so a destructive schema change only costs a
         * re-sync. It also drops the unsent outbox, so a version bump should ship a Migration.
         */
        fun build(context: Context): EveChatDatabase =
            Room.databaseBuilder(context.applicationContext, EveChatDatabase::class.java, NAME)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
