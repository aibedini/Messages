package com.autonomousone.messages.data

import androidx.room.ColumnInfo
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
        // The per-source keyset walk: the history producer's page query and the full-mirror
        // verification sweep both filter `source` and range an ordered `(date, providerId)` pair.
        // Without it each page range-scans `date` and filters the source out of every row it reads,
        // which doubles the work of a walk that is already the heaviest read in the app.
        Index("source", "date", "providerId"),
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
    val syncState: String = SYNC_STATE_SYNCED,
    /**
     * APP-OWNED transport verdict for an outgoing message — a [com.autonomousone.messages.sms
     * .SendTransportState] name, or null for a row that never went through this app's transport path
     * (inbound, or historical).
     *
     * Additive and NULLABLE on purpose: every message that already exists migrates to "no app-owned
     * transport state", which the presentation mapper renders exactly as it does today. A default
     * would have to invent a verdict, and an invented "Sent" is the false-success bug this field
     * exists to end.
     *
     * DELIBERATELY SEPARATE from [status]: `Telephony.Sms.STATUS_PENDING` means both "still waiting"
     * and "ambiguous result received", and those must stay distinguishable across a restart.
     */
    val sendTransportState: String? = null,
    /**
     * Stable [com.autonomousone.messages.sms.SmsSendFailure.code] for the verdict above, or null.
     *
     * A CODE, never translated UI text, so the reason survives process death, reboot and a locale
     * change and is turned into copy at render time.
     */
    val sendFailureCode: String? = null,
    /** Raw SENT-callback result code, for the diagnostics section. Diagnostic only. */
    val sendResultCode: Int? = null,
    /** Raw SENT-callback radio error code, for the diagnostics section. Diagnostic only. */
    val sendRadioErrorCode: Int? = null,
    /**
     * Epoch millis at which [sendTransportState] last changed. 0 = never.
     *
     * `DEFAULT 0` is declared because the column is NOT NULL: adding a NOT NULL column to an existing
     * table requires a default, and Room validates the on-disk default against this declaration, so
     * the two must agree exactly or every upgraded install fails to open.
     */
    @ColumnInfo(defaultValue = "0")
    val sendStateUpdatedAt: Long = 0L,
    /**
     * Epoch millis of the last DELIVERY callback, or 0 when none arrived.
     *
     * Kept SEPARATE from [sendStateUpdatedAt] on purpose: a delivery report and a send verdict are two
     * different pieces of evidence about the same message, and overwriting one timestamp with the
     * other destroys the ability to say which happened when.
     */
    @ColumnInfo(defaultValue = "0")
    val deliveryCallbackAt: Long = 0L,
    /**
     * Raw 3GPP/3GPP2 TP-Status from the SMS-STATUS-REPORT, or null when there was no parseable report.
     *
     * Persisted raw and un-interpreted so a user can inspect the exact carrier value after a restart,
     * and so a future interpretation change does not require re-collecting the evidence. It is never
     * invented: null means "no report was parsed", not "status zero".
     */
    val deliveryTpStatus: Int? = null,
    /**
     * The classification [com.autonomousone.messages.sms.SmsStatusPolicy.DeliveryEvidence] name for
     * [deliveryTpStatus] — DELIVERED / TEMPORARY / FAILED / UNKNOWN — or null when no report arrived.
     *
     * Stored beside the raw code rather than instead of it: the classification is the app's reading,
     * the TP-Status is the carrier's own statement, and a diagnostic screen must be able to show both.
     */
    val deliveryEvidence: String? = null,
    /**
     * The raw delivery callback result code, for the technical details section.
     *
     * DIAGNOSTIC ONLY. A negative delivery report is never a send failure — it is the carrier saying
     * the message was not delivered, which is a different fact from the handset failing to submit it.
     */
    val deliveryResultCode: Int? = null,
    /**
     * Monotonic strength of the delivery evidence above, as a
     * [com.autonomousone.messages.sms.DeliveryEvidenceRank] ordinal. 0 = nothing recorded.
     *
     * Stored so the "never downgrade a better answer" rule can be enforced INSIDE the UPDATE
     * statement, which is what makes it hold when two delivery callbacks race — a read-then-write in
     * Kotlin would let a late UNKNOWN report overwrite a DELIVERED one between the read and the write,
     * turning a confirmed delivery into "unknown" with nothing to show for it.
     */
    @ColumnInfo(defaultValue = "0")
    val deliveryEvidenceRank: Int = 0
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
        dateSent = dateSent,
        // The chat bubble reads its own verdict from here rather than re-deriving from [status],
        // which cannot distinguish "still waiting" from "ambiguous" and holds no carrier TP-Status.
        // Derived by the SAME mapper the conversation list uses, so the two cannot disagree.
        uiState = com.autonomousone.messages.sms.SendStateDerivation
            .evidenceFromPersisted(
                message = this,
                isOutgoing = type == android.provider.Telephony.Sms.MESSAGE_TYPE_SENT
            )
            .let { com.autonomousone.messages.sms.SmsStatusPresentationMapper.present(it) }
            .state
            .takeIf {
                it.needsAttention ||
                    it == com.autonomousone.messages.sms.SmsUiState.DELIVERED ||
                    it == com.autonomousone.messages.sms.SmsUiState.DELIVERY_UNKNOWN
            }
            ?.name
    )
}

/**
 * Projection DTO: the unread message count of ONE thread.
 *
 * Exists so a full projection rebuild can read every thread's unread count in a
 * single aggregate query instead of one COUNT per conversation (the N+1 shape).
 * The predicate mirrors MessageDao.countUnread exactly - `read = 0 AND type = 1` -
 * so the batch and the single-thread query can never disagree about what
 * "unread" means.
 */
data class ThreadUnreadCount(
    val threadId: Long,
    val unreadCount: Int
)

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
    val archived: Boolean = false,
    /**
     * The newest message's app-owned UI state, as a [com.autonomousone.messages.sms.SmsUiState] name,
     * or null when it needs no attention (success states, inbound, or a row with no app state).
     *
     * Denormalized onto the conversation so Home can render "⚠ Not sent · snippet" for a whole list
     * without a per-row probe into `send_segments` and `messages` — the N+1 shape this projection
     * exists to avoid.
     *
     * MESSAGE-OWNED, not conversation-owned: it is recomputed from the newest message on every
     * projection, exactly like [snippet] and [lastMessageDate]. It must therefore never be carried
     * forward blindly by a rebuild — a stale "Not sent" after a newer incoming message would be a
     * visible lie, and `conversationSummary` returns null for success states so a recovered send
     * clears it.
     */
    val lastMessageUiState: String? = null
)

/**
 * DURABLE work queue for exact provider identities whose STRICT read failed.
 *
 * This replaces the in-process PendingExactRepairs map, which had four
 * architectural defects:
 *
 *  1. retries only happened when ANOTHER provider event arrived, so a read that
 *     failed while the provider was quiet was never retried at all;
 *  2. due() did not claim, so the same identity could be read concurrently by
 *     two workers;
 *  3. it was in-process, so process death silently dropped correctness work;
 *  4. it had a fixed capacity of 256 and evicted the OLDEST entry, i.e. it
 *     silently discarded correctness work under load.
 *
 * Rules encoded here:
 *  - Identity is the composite (source, providerId): SMS 123 and MMS 123 are
 *    different rows and must never share work.
 *  - A NEW provider event for the same identity bumps [generation] and resets
 *    the row to PENDING. An in-flight read ACKs the generation it claimed, so an
 *    older successful read can never consume newer work.
 *  - [state] is IN_FLIGHT only while a worker holds [leaseUntil]. An expired
 *    lease is reclaimed at startup, so a crashed worker cannot strand a row.
 *  - There is NO capacity limit and NO eviction. A correctness queue bounds its
 *    processing rate, never its state.
 */
/**
 * WHY a provider identity is queued for repair.
 *
 * The queue used to be identity-only, and its consumer treated ANY successful
 * absence as a proven delete. That is valid for a mature exact observer event
 * and WRONG for every other producer:
 *
 *   RECONCILE_EXACT         a mature exact ContentObserver identity. Absence is a
 *                           considered observation, so a proven delete is allowed.
 *   EXPECT_EXISTS           providerRowChanged() immediately after an outgoing
 *                           insert. The row is EXPECTED to exist and is often not
 *                           query-visible yet, so absence must NEVER delete.
 *   REFRESH_STATUS          a delivery/status callback for a row we know about.
 *                           Absence must never delete a valid message.
 *   VERIFY_DELETE_CANDIDATE a row that is already a delete CANDIDATE (bounded
 *                           overlap or integrity audit). A second, independent
 *                           strict read proving absence may delete it.
 *
 * Inferring intent from the caller at claim time is not allowed: it is persisted
 * with the durable row.
 */
enum class ProviderRepairIntent {
    RECONCILE_EXACT,
    EXPECT_EXISTS,
    REFRESH_STATUS,
    VERIFY_DELETE_CANDIDATE;

    /** True only for intents where a PROVEN absence may delete the local row. */
    val absenceCanProveDelete: Boolean
        get() = this == RECONCILE_EXACT || this == VERIFY_DELETE_CANDIDATE

    companion object {
        fun from(value: String?): ProviderRepairIntent =
            entries.firstOrNull { it.name == value } ?: EXPECT_EXISTS
    }
}

@Entity(
    tableName = "provider_repair_queue",
    primaryKeys = ["source", "providerId"],
    indices = [
        Index("state", "nextRetryAt"),
        Index("nextRetryAt")
    ]
)
data class ProviderRepairEntity(
    /** "sms" | "mms" - the MessageEntity source vocabulary. */
    val source: String,
    val providerId: Long,
    /** Bumped every time new work arrives for this identity. */
    val generation: Long = 1L,
    val state: String = STATE_PENDING,
    /** Consecutive failures. Never used to abandon the row. */
    val attempts: Int = 0,
    val nextRetryAt: Long = 0L,
    /** Epoch ms until which the current IN_FLIGHT owner may work. */
    val leaseUntil: Long = 0L,
    /** Typed reason from the last failure (ProviderRead.Reason name). */
    val lastFailureReason: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    /**
     * Persisted [ProviderRepairIntent]. A SQL default is declared so the v15
     * ALTER TABLE can add a NOT NULL column to existing rows AND still match the
     * schema Room expects (Room validates the column default verbatim).
     *
     * The default is EXPECT_EXISTS - the NON-DESTRUCTIVE intent - because a row
     * written by v14 has no recorded semantic origin and must never become
     * delete-capable by accident. The two-sided integrity audit resolves such a
     * row later, deliberately.
     */
    @ColumnInfo(defaultValue = "EXPECT_EXISTS")
    val intent: String = ProviderRepairIntent.EXPECT_EXISTS.name,
    /** When the current intent was recorded; the visibility-grace clock. */
    @ColumnInfo(defaultValue = "0")
    val intentSince: Long = 0L,
    /**
     * Consecutive SUCCESSFUL provider-absence observations for THIS generation.
     *
     * Deliberately separate from [attempts]. attempts is the retry/backoff counter
     * and it increments for provider SECURITY/BINDER/PROVIDER_UNAVAILABLE/query
     * failures and for ABSENCE_NOT_MATURED NACKs - none of which is evidence of
     * anything. Using it as absence evidence meant three provider FAILURES plus
     * one later absence could "mature" a row into a delete candidate, which is the
     * opposite of the policy.
     *
     * Reset to 0 on every new generation (enqueue and rearm), so evidence never
     * carries across an intent change or a supersession.
     */
    @ColumnInfo(defaultValue = "0")
    val absenceCount: Int = 0
) {
    companion object {
        const val STATE_PENDING = "PENDING"
        const val STATE_IN_FLIGHT = "IN_FLIGHT"
        const val STATE_BACKOFF = "BACKOFF"
    }
}

enum class IntegrityAuditDirection {
    ROOM_TO_PROVIDER,
    PROVIDER_TO_ROOM
}

/** Durable checkpoint for one side of the two-sided integrity audit. */
@Entity(tableName = "integrity_audit_state", primaryKeys = ["source", "direction"])
data class IntegrityAuditStateEntity(
    val source: String,
    val direction: String,
    val cursorDate: Long = Long.MAX_VALUE,
    val cursorProviderId: Long = Long.MAX_VALUE,
    val cycleStartedAt: Long = 0L,
    val lastCompletedAt: Long = 0L,
    val nextRunAt: Long = 0L,
    val state: String = STATE_IDLE,
    val updatedAt: Long = 0L
) {
    companion object {
        const val STATE_IDLE = "IDLE"
        const val STATE_RUNNING = "RUNNING"
    }
}

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
