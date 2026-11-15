package com.autonomousone.messages.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One delivered suggestion batch for one conversation, sealed at rest (XR-GA-017, Room v29).
 *
 * ## Why one row per (thread, anchor message) instead of one row per card
 *
 * The command delivers a *set* of two to three alternatives for ONE inbound message. They are only
 * meaningful together — showing card 2 of a payload whose card 1 was rejected would be a UI the
 * sender never described — so the payload is one atomic blob keyed by the pair that identifies it.
 * A newer command for the same anchor simply REPLACES the row; a command for a newer anchor is a
 * different row, and the older one is deleted by the executor.
 *
 * ## Why the payload is a BLOB and not text
 *
 * Suggestion text is message content: it describes what the AI thinks the user should say back, and
 * in many threads it echoes the inbound message. It is therefore subject to the same rule as message
 * bodies (ADR-002/ADR-006): never readable in SQLite. [payloadCiphertext] is AES-256-GCM under a
 * dedicated AndroidKeystore key ([com.autonomousone.messages.security.SuggestionCardVault]), with the
 * thread id as associated data so a blob cannot be replayed into another conversation.
 *
 * ## Why `for_message_id` is the canonical message identity
 *
 * The executor normalises whatever the sender used (`messageId`, `sms:123`, …) into the same stable
 * message id Android publishes in MESSAGE_CREATED events
 * ([com.autonomousone.messages.data.GatewayEventFactory.messageIdFor]). Visibility then compares ONE
 * vocabulary: "is the row's anchor still the newest inbound message?" is an equality test, not a
 * format negotiation.
 */
@Entity(
    tableName = "suggestion_cards",
    primaryKeys = ["thread_id", "for_message_id"],
    indices = [
        // Required by the UI: the conversation reads its own cards on every open.
        Index("thread_id"),
        // The expiry sweep is global and must not scan the table.
        Index("expires_at")
    ]
)
data class SuggestionCardEntity(
    /** Telephony thread id — the conversation this card belongs to. */
    @ColumnInfo(name = "thread_id") val threadId: Long,
    /** Canonical message identity of the inbound message these suggestions answer. */
    @ColumnInfo(name = "for_message_id") val forMessageId: String,
    /** AES-256-GCM sealed canonical payload; never readable text. */
    @ColumnInfo(name = "payload_ciphertext") val payloadCiphertext: ByteArray,
    /** Epoch millis after which the cards must not be shown (0 = never, which the executor refuses). */
    @ColumnInfo(name = "expires_at") val expiresAt: Long,
    /** When this device accepted the command. Drives the long-press "received" line and pruning. */
    @ColumnInfo(name = "received_at") val receivedAt: Long,
    /** The sender's conversation generation, kept for diagnosis of a stale/fresh decision. */
    @ColumnInfo(name = "generation") val generation: Int
)

@Dao
interface SuggestionCardDao {

    /** The cards for one conversation. Few rows by construction (one per anchor message). */
    @Query("SELECT * FROM suggestion_cards WHERE thread_id = :threadId ORDER BY received_at DESC")
    fun observeForThread(threadId: Long): Flow<List<SuggestionCardEntity>>

    @Query("SELECT * FROM suggestion_cards WHERE thread_id = :threadId ORDER BY received_at DESC")
    suspend fun forThread(threadId: Long): List<SuggestionCardEntity>

    /**
     * `REPLACE`, not `IGNORE`: a redelivered-but-fresher payload for the same anchor is newer
     * information, and the primary key already makes "one batch per anchor" the invariant.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(card: SuggestionCardEntity): Long

    @Query("DELETE FROM suggestion_cards WHERE thread_id = :threadId")
    suspend fun deleteForThread(threadId: Long): Int

    @Query("DELETE FROM suggestion_cards WHERE for_message_id = :forMessageId AND thread_id = :threadId")
    suspend fun deleteCard(threadId: Long, forMessageId: String): Int

    /**
     * Automatic cleanup of expired rows.
     *
     * `expires_at > 0` is a safety net, not a business rule: the executor refuses a zero expiry, so a
     * zero here can only be a row written by a future/older build and is left alone rather than
     * deleted by an inference.
     */
    @Query("DELETE FROM suggestion_cards WHERE expires_at > 0 AND expires_at <= :now")
    suspend fun deleteExpired(now: Long): Int

    @Query("SELECT COUNT(*) FROM suggestion_cards")
    suspend fun count(): Int
}
