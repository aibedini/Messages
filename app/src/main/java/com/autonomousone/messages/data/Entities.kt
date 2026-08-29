package com.autonomousone.messages.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One message (SMS or MMS) mirrored from Telephony into the app's local
 * read-SSOT database.
 *
 * Key: (source, providerId) composite — SMS and MMS _id sequences overlap, so
 * the provider id alone is NOT unique across both tables.
 *
 * Dates are ALWAYS milliseconds in this table; MMS rows (seconds in the
 * provider) are normalized by the sync layer on write.
 */
@Entity(
    tableName = "messages",
    primaryKeys = ["source", "providerId"],
    indices = [
        // Conversation window + paging order.
        Index("threadId", "date", "providerId"),
        // Per-contact lookups (phone-only route before a thread is resolved).
        Index("normalizedAddress", "date"),
        // Fast dedupe during incremental syncs.
        Index("date"),
        // O(unread_count) SQL COUNT for thread unread badges — declared here so
        // FRESH installs (Room-managed) and UPGRADES (MIGRATION_3_4) converge
        // to exactly the same schema. The old hand-rolled PARTIAL index
        // (idx_messages_thread_unread) could not be declared in Room and was
        // silently missing on fresh installs.
        Index(value = ["threadId", "read", "type"])
    ]
)
data class MessageEntity(
    /** "sms" | "mms" */
    val source: String,
    /** Row id inside its native provider table. */
    val providerId: Long,
    val threadId: Long,
    /** Normalized counterpart address (ContactRepository.normalizePhone). */
    val normalizedAddress: String,
    /** Address exactly as the provider stored it (display fallback). */
    val rawAddress: String,
    val body: String,
    /** Epoch millis (MMS seconds × 1000 at sync time). */
    val date: Long,
    /** 1 = inbox, 2 = sent/outgoing (mirrors Sms.MESSAGE_TYPE_*). */
    val type: Int,
    /** Telephony.Sms.STATUS (-1/0/32/64). */
    val status: Int = -1,
    /** Delivery timestamp (epoch ms); 0 until SENT/DELIVERED fills it. */
    val dateSent: Long = 0,
    val read: Boolean,
    /** Sync bookkeeping. */
    val syncState: String = SYNC_STATE_SYNCED
) {
    companion object {
        const val SOURCE_SMS = "sms"
        const val SOURCE_MMS = "mms"
        const val SYNC_STATE_SYNCED = "synced"
        const val SYNC_STATE_PENDING = "pending"
    }

    /**
     * View-model row shape used across the UI (provider-compatible fields).
     *
     * UI identity mirrors the provider reader convention (SmsRepository):
     * SMS ids are positive, MMS ids are NEGATED. `id > 0` == SMS, `id < 0` ==
     * MMS — so `distinctBy { it.id }` and Compose keys can never collide when
     * the provider's `_id` sequences overlap (SMS id 52 and MMS id 52 would
     * both map to 52 otherwise).
     */
    fun toSms() = com.autonomousone.messages.model.Sms(
        id = if (source == SOURCE_MMS) -providerId else providerId,
        threadId = threadId,
        sender = rawAddress.ifBlank { normalizedAddress },
        message = body,
        date = date,
        unread = !read,
        type = type,
        status = status,
        dateSent = dateSent
    )
}

/** Per-conversation projection kept up to date by the sync engine. */
@Entity(
    tableName = "conversations",
    indices = [Index("lastMessageDate")]
)
data class ConversationEntity(
    @PrimaryKey val threadId: Long,
    val normalizedAddress: String,
    /** Address exactly as the provider stored it (display + matching fallback). */
    val rawAddress: String = "",
    /** Display snippet = newest message body. */
    val snippet: String,
    val lastMessageDate: Long,
    val unreadCount: Int,
    /** Type of the newest message (1 incoming, 2 outgoing…) so Home can
     *  render the "You:" marker without an O(N) probe. v6. */
    val lastMessageType: Int = 1,
    val pinned: Boolean = false,
    val archived: Boolean = false
)

/**
 * One row per synced source+window so incremental syncs know where they are.
 *
 * Dual watermarks:
 *  - newestDate/newestId: incoming direction (new messages from above)
 *  - oldestDate/oldestId: backfill direction (history from below)
 *
 * These two watermarks move independently and must not interfere.
 */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val source: String, // "sms" | "mms"

    // ── Newest watermark (incoming direction) ──
    /** Newest provider date (ms) already mirrored. */
    val newestDate: Long,
    val newestId: Long = 0L,

    // ── Oldest watermark (backfill direction) ──
    val oldestDate: Long = Long.MAX_VALUE,
    val oldestId: Long = Long.MAX_VALUE,

    // ── State flags ──
    /** True once the initial window (first 200-500 messages) is loaded. */
    val initialWindowReady: Boolean = false,
    /** True once the full history backfill completed. */
    val historyBackfillComplete: Boolean = false,

    // ── Repair bookkeeping ──
    val lastReconcileAt: Long = 0L,
    val schemaVersion: Int = 1
)
