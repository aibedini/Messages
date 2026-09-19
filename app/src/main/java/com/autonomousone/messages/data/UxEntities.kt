package com.autonomousone.messages.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.security.MessageDigest

/**
 * v3.4.0 UX user-state schema (Room v16).
 *
 * WHY THESE ARE SEPARATE TABLES
 * -----------------------------
 * Telephony is the durable external truth for message CONTENT, and
 * TelephonySyncCoordinator upserts `messages` rows from it. Any user-owned flag
 * stored as a column on [MessageEntity] would be destroyed by the next provider
 * sync — so "user-owned metadata must NOT be overwritten by provider sync" is
 * only satisfiable in dedicated user-state tables.
 *
 * Every table here is:
 *  - ADDITIVE (fresh CREATEs in MIGRATION_15_16; nothing is rebuilt or dropped);
 *  - keyed by the app identity vocabulary (source, providerId) — NEVER by body,
 *    timestamp, or a raw provider id alone, because SMS 100 and MMS 100 are
 *    different messages;
 *  - small (one row per conversation / per touched message), so it stays cheap
 *    on devices holding 360K messages.
 */

/**
 * Local, on-device classification vocabulary.
 *
 * Persisted as the enum NAME so a future reordering of this enum can never
 * silently reinterpret stored rows.
 */
enum class MessageCategory {
    PERSONAL,
    OTP,
    TRANSACTION,
    PROMOTION,
    SPAM,
    UNKNOWN;

    companion object {
        fun from(value: String?): MessageCategory =
            entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}

/**
 * Canonical conversation-user-state row.
 *
 * Deliberately does NOT carry `pinned` / `archived` — those already exist on
 * [ConversationEntity] and are owned by the existing pin/archive paths.
 */
@Entity(
    tableName = "conversation_preferences",
    indices = [
        Index("spam"),
        Index("manualUnread"),
        Index("mutedUntil")
    ]
)
data class ConversationPreferenceEntity(
    @PrimaryKey
    val threadId: Long,

    /**
     * UX-only unread marker. Telephony READ is NEVER rewritten back to 0 for
     * this: a real unread is provider state, this is a user bookmark.
     */
    val manualUnread: Boolean = false,

    /**
     * 0 = not muted.
     * [MUTE_FOREVER] = muted until the user unmutes.
     * Otherwise an epoch-millis instant that expires BY COMPARISON — no unmute
     * worker is scheduled, so `mutedUntil <= now` is already "unmuted".
     */
    val mutedUntil: Long = 0L,

    /**
     * True once a conversation-specific Android notification channel has been
     * intentionally created for this thread. Android O+ channels are the real
     * authority for custom sound/vibration, so this flag only records that we
     * created one — it never mirrors the system's per-channel choices.
     */
    val customNotificationChannel: Boolean = false,

    /**
     * null = use the automatic category.
     * Otherwise a [MessageCategory] name. The user override ALWAYS wins over
     * the classifier.
     */
    val categoryOverride: String? = null,

    /** User explicitly marked this conversation as spam (local report only). */
    val spam: Boolean = false,

    val spamReportedAt: Long = 0L,

    /**
     * Provenance for the Not-Spam undo: true only when THIS spam report created
     * the block. A number the user had blocked manually before the report must
     * stay blocked when the spam report is undone.
     */
    val spamBlockedByReport: Boolean = false,

    val updatedAt: Long = 0L
) {
    fun isMuted(now: Long): Boolean =
        mutedUntil == MUTE_FOREVER || (mutedUntil > 0L && mutedUntil > now)

    /** Effective category: the user override wins, else the automatic one. */
    fun effectiveCategory(automatic: MessageCategory?): MessageCategory =
        categoryOverride?.let { MessageCategory.from(it) } ?: automatic ?: MessageCategory.UNKNOWN

    companion object {
        /** Sentinel meaning "muted until explicitly unmuted". */
        const val MUTE_FOREVER = Long.MAX_VALUE
    }
}
/**
 * Per-message user state: starred / individually trashed / OTP-cleanup opt-out.
 *
 * Keyed by the composite app identity so SMS 100 and MMS 100 can never share a
 * star or a trash flag.
 *
 * NO FOREIGN KEY to `messages`, deliberately. A composite FK would require
 * `PRAGMA foreign_keys = ON` enforcement across every existing delete/repair
 * path in TelephonySyncCoordinator (including the provider-repair queue's
 * proven-absence deletes), where an FK violation would silently abort a
 * correctness delete. It would ALSO destroy exactly the state this table exists
 * to protect: a provider repair that deletes and re-inserts a row would take the
 * star and the trash flag with it. Cleanup is therefore EXPLICIT and
 * provider-independent (see MessageUserStateDao.deleteOrphans).
 */
@Entity(
    tableName = "message_user_state",
    primaryKeys = ["source", "providerId"],
    indices = [
        Index("threadId"),
        Index("starred"),
        Index("trashedAt"),
        Index("purgeAt")
    ]
)
data class MessageUserStateEntity(
    val source: String,
    val providerId: Long,
    val threadId: Long,

    val starred: Boolean = false,
    val starredAt: Long = 0L,

    /** 0 = active (not trashed). */
    val trashedAt: Long = 0L,

    /** Epoch millis at which permanent provider deletion becomes eligible. */
    val purgeAt: Long = 0L,

    /**
     * Explicit user choice that this OTP must never be removed by the global
     * OTP cleanup. Starring a message protects it too.
     */
    val keepFromOtpCleanup: Boolean = false,

    val updatedAt: Long = 0L
) {
    val isTrashed: Boolean get() = trashedAt > 0L
}

/**
 * Conversation-level TRASH TOMBSTONE.
 *
 * A conversation delete must NOT write one state row per message: a 100K-message
 * conversation would create 100K rows in a single user action. Instead one
 * tombstone hides the deleted SNAPSHOT, identified by the canonical newest row
 * at deletion time:
 *
 *  - every message at-or-before the cutoff is hidden from the ACTIVE UI;
 *  - a genuinely NEW incoming message after the cutoff is NEWER than it, so it
 *    is visible and re-creates the conversation (a threadId is never hidden
 *    "forever", which would silently swallow all future messages);
 *  - the provider rows are untouched, so Restore needs no provider re-insert.
 */
@Entity(
    tableName = "trashed_threads",
    indices = [Index("purgeAt")]
)
data class TrashedThreadEntity(
    @PrimaryKey
    val threadId: Long,

    val deletedAt: Long,
    val purgeAt: Long,

    /** Canonical newest row when the conversation was trashed. */
    val cutoffDate: Long,
    val cutoffSource: String,
    val cutoffProviderId: Long
)

/**
 * Deterministic classification of ONE message.
 *
 * The OTP CODE ITSELF IS DELIBERATELY NOT STORED: it can be re-derived locally
 * from the body whenever the UI needs it, and persisting it would create a
 * second, loggable copy of a secret. Only the cleanup deadline is kept.
 */
@Entity(
    tableName = "message_classification",
    primaryKeys = ["source", "providerId"],
    indices = [
        Index("threadId"),
        Index("category"),
        Index("isOtp"),
        Index("otpDeleteEligibleAt")
    ]
)
data class MessageClassificationEntity(
    val source: String,
    val providerId: Long,
    val threadId: Long,

    /** [MessageCategory] name. */
    val category: String,
    val confidence: Float,

    val isOtp: Boolean = false,

    /** 0 = not scheduled for OTP cleanup. Never holds the code itself. */
    val otpDeleteEligibleAt: Long = 0L,

    val classifiedAt: Long
)

/**
 * Automatically maintained per-conversation projection of the classifier, so
 * Home can filter by category with an indexed lookup instead of scanning
 * messages. The USER OVERRIDE lives in [ConversationPreferenceEntity] and always
 * wins; this table is never authoritative over it.
 */
@Entity(
    tableName = "conversation_classification",
    indices = [Index("category")]
)
data class ConversationClassificationEntity(
    @PrimaryKey
    val threadId: Long,

    /** [MessageCategory] name. */
    val category: String,
    val confidence: Float,
    val updatedAt: Long
)

/** Media / Links / Files browser vocabulary. */
enum class MessageAssetKind {
    MEDIA,
    LINK,
    FILE;

    companion object {
        fun from(value: String?): MessageAssetKind =
            entries.firstOrNull { it.name == value } ?: FILE
    }
}

/**
 * Content metadata for the Media / Links / Files browser inside a conversation.
 *
 * ONLY METADATA: MMS part content URIs, or the normalized URL of a link. No
 * binary is ever copied into Room — a 360K-message database must not turn into a
 * media archive, and Android owns the attachment bytes via the provider.
 */
@Entity(
    tableName = "message_assets",
    indices = [
        Index("threadId", "date"),
        Index("kind"),
        Index(value = ["source", "providerId"])
    ]
)
data class MessageAssetEntity(
    @PrimaryKey
    val assetKey: String,

    val source: String,
    val providerId: Long,
    val threadId: Long,

    /** [MessageAssetKind] name. */
    val kind: String,

    /** content:// URI for an MMS part, or the normalized URL for a LINK. */
    val value: String,

    val mimeType: String = "",
    val displayName: String = "",

    val date: Long
)

/**
 * Deterministic asset identity.
 *
 * Determinism is what makes re-indexing idempotent: the same MMS part produces
 * the same key forever, so re-ingest is an UPSERT instead of a duplicate. Two
 * IDENTICAL urls inside two DIFFERENT messages still get different keys, because
 * (source, providerId) is part of the hash.
 */
object MessageAssetKeys {

    fun of(
        source: String,
        providerId: Long,
        kind: MessageAssetKind,
        value: String
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$source|$providerId|${kind.name}|$value".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

/**
 * The canonical conversation ordering used everywhere in this app:
 *
 *     date DESC, source DESC, providerId DESC
 *
 * [TrashedThreadEntity] stores the newest row under this order as its snapshot
 * cutoff, and the ACTIVE-UI predicate below must agree with it exactly, or a
 * trashed conversation would hide the wrong set of rows.
 *
 * CROSS-SOURCE SAFETY (deliberate, documented refinement)
 * ------------------------------------------------------
 * A strict lexicographic `<=` on (date, source, providerId) classifies a NEW SMS
 * as part of the deleted snapshot whenever it shares the exact millisecond of a
 * cutoff row that was an MMS, because "sms" < "mms". Silently hiding a brand-new
 * message is the worst possible failure mode for a messaging app, so a row on
 * the SAME date but a DIFFERENT source is treated as NEW (visible). The cost is
 * that a same-millisecond row from the other source is shown instead of hidden —
 * a cosmetic case that cannot involve the newest row, since the cutoff row WAS
 * the newest row at deletion time.
 */
object MessageCutoff {

    /**
     * SQL predicate: message rows (aliased `m`) that belong to the deleted
     * snapshot of tombstone `t`. `t` is the tombstone alias.
     */
    const val HIDDEN_BY_TOMBSTONE_SQL: String =
        "(m.date < t.cutoffDate" +
            " OR (m.date = t.cutoffDate AND m.source = t.cutoffSource" +
            " AND m.providerId <= t.cutoffProviderId))"

    /** SQL predicate: rows NOT hidden by tombstone `t` (ACTIVE UI). */
    const val VISIBLE_UNDER_TOMBSTONE_SQL: String =
        "(m.date > t.cutoffDate" +
            " OR (m.date = t.cutoffDate AND m.source <> t.cutoffSource)" +
            " OR (m.date = t.cutoffDate AND m.source = t.cutoffSource" +
            " AND m.providerId > t.cutoffProviderId))"

    /** Kotlin mirror of [HIDDEN_BY_TOMBSTONE_SQL]; pinned by MessageCutoffTest. */
    fun isHidden(
        tombstone: TrashedThreadEntity,
        date: Long,
        source: String,
        providerId: Long
    ): Boolean {
        if (date != tombstone.cutoffDate) return date < tombstone.cutoffDate
        if (source != tombstone.cutoffSource) return false
        return providerId <= tombstone.cutoffProviderId
    }

    /** Kotlin mirror of [VISIBLE_UNDER_TOMBSTONE_SQL]. */
    fun isVisible(
        tombstone: TrashedThreadEntity?,
        date: Long,
        source: String,
        providerId: Long
    ): Boolean = tombstone == null || !isHidden(tombstone, date, source, providerId)

    // ── Shared ACTIVE-UI SQL fragments ──────────────────────────────────────
    // Public (not file-private) because several DAO files compose them; Kotlin
    // `private const val` is FILE-scoped, which would silently force each DAO
    // file to re-type the predicate and drift.

    /** Excludes individually-trashed rows. Aliases: `m` = messages. */
    const val NOT_INDIVIDUALLY_TRASHED_SQL: String =
        "NOT EXISTS (SELECT 1 FROM message_user_state us " +
            "WHERE us.source = m.source AND us.providerId = m.providerId AND us.trashedAt > 0)"

    /** Excludes rows inside a trashed conversation's snapshot. Aliases: `m`, `t`. */
    const val NOT_HIDDEN_BY_TOMBSTONE_SQL: String =
        "NOT EXISTS (SELECT 1 FROM trashed_threads t WHERE t.threadId = m.threadId AND " +
            HIDDEN_BY_TOMBSTONE_SQL + ")"

    /** The complete ACTIVE-UI predicate: both halves together. */
    const val ACTIVE_MESSAGE_FILTER_SQL: String =
        NOT_INDIVIDUALLY_TRASHED_SQL + " AND " + NOT_HIDDEN_BY_TOMBSTONE_SQL
}