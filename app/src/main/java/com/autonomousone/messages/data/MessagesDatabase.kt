package com.autonomousone.messages.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.autonomousone.messages.BuildConfig

/**
 * The app's local read-SSOT (phase-2 architecture): the UI reads from here;
 * TelephonySync keeps it in step with the system provider.
 *
 * v3 adds:
 *  - Partial index for O(unread_count) SQL COUNT
 *  - Keyset pagination index
 *  - Dual watermarks in sync_state (newest/oldest)
 *
 * v4 adds:
 *  - Room-managed (threadId, read, type) index — fresh installs and upgrades
 *    now converge to the SAME schema (the old hand-rolled PARTIAL index was
 *    silently missing on fresh installs).
 *  - FTS4 full-text search over message bodies (360K-scale search no longer
 *    loads every row into Kotlin).
 *
 * v14 adds:
 *  - provider_repair_queue: the DURABLE exact-repair work queue. Failed exact
 *    provider reads are retried by a timer instead of waiting for the next
 *    provider event, survive process death, and can never be silently evicted.
 *
 * v16 adds (v3.4.0 UX / Messaging Intelligence, see UxEntities.kt):
 *  - conversation_preferences, message_user_state, trashed_threads,
 *    message_classification, conversation_classification, message_assets —
 *    ALL ADDITIVE. User-owned state lives HERE, never on MessageEntity, so a
 *    provider Upsert can never overwrite a star, a mute or a manual unread.
 *
 * v17 adds (v3.4.0 FEATURE 11 = Send delay / Undo Send, see
 * PendingDelayedSend.kt):
 *  - pending_delayed_sends — ADDITIVE. The durable undo-send ledger. It exists
 *    because "sending in 10 seconds" is a promise about the FUTURE: the
 *    WorkManager timer says when to try, this table says whether the single
 *    send permission was ever handed out. The job is at-least-once, the
 *    compare-and-set claim is at-most-once, and only the two together are
 *    exactly-once.
 *
 * v7 adds (PR-01 / Messaging Platform durability foundation, see docs/adr/):
 *  - remote_conversation_map, gateway_event_outbox, remote_commands,
 *    remote_command_executions, sync_cursors — all ADDITIVE (no rebuilds);
 *    gateway payload columns are crypto-friendly opaque blobs from day one.
 */
@TypeConverters(SegmentCallbackStateConverter::class)
@Database(
    entities = [
        MessageEntity::class,
        ConversationEntity::class,
        SyncStateEntity::class,
        MessageFts::class,
        SendSegmentEntity::class,
        RemoteConversationMapEntity::class,
        GatewayEventOutboxEntity::class,
        RemoteCommandEntity::class,
        RemoteCommandExecutionEntity::class,
        SyncCursorEntity::class,
        TrustedDeviceEntity::class,
        TrustStatementOutboxEntity::class,
        DeviceTelemetryEntity::class,
        ConversationKeyEpochEntity::class,
        CloudHistoryCheckpointEntity::class,
        ProviderRepairEntity::class,
        IntegrityAuditStateEntity::class,
        // v16 — UX user-state (ADDITIVE; see UxEntities.kt).
        ConversationPreferenceEntity::class,
        MessageUserStateEntity::class,
        TrashedThreadEntity::class,
        MessageClassificationEntity::class,
        ConversationClassificationEntity::class,
        MessageAssetEntity::class,
        // v17 — Send delay / Undo Send (ADDITIVE; see PendingDelayedSend.kt).
        PendingDelayedSendEntity::class
    ],
    version = 18,
    exportSchema = true
)
abstract class MessagesDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao
    abstract fun conversationDao(): ConversationDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun messageFtsDao(): MessageFtsDao
    abstract fun sendSegmentDao(): SendSegmentDao
    abstract fun remoteConversationMapDao(): RemoteConversationMapDao
    abstract fun gatewayEventOutboxDao(): GatewayEventOutboxDao
    abstract fun remoteCommandDao(): RemoteCommandDao
    abstract fun remoteCommandExecutionDao(): RemoteCommandExecutionDao
    abstract fun syncCursorDao(): SyncCursorDao
    abstract fun trustedDeviceDao(): TrustedDeviceDao
    abstract fun trustStatementOutboxDao(): TrustStatementOutboxDao
    abstract fun deviceTelemetryDao(): DeviceTelemetryDao
    abstract fun conversationKeyDao(): ConversationKeyDao
    abstract fun cloudHistoryCheckpointDao(): CloudHistoryCheckpointDao
    abstract fun providerRepairDao(): ProviderRepairDao
    abstract fun integrityAuditDao(): IntegrityAuditDao
    // v16 — UX user-state.
    abstract fun conversationPreferenceDao(): ConversationPreferenceDao
    abstract fun messageUserStateDao(): MessageUserStateDao
    abstract fun trashedThreadDao(): TrashedThreadDao
    abstract fun messageClassificationDao(): MessageClassificationDao
    abstract fun conversationClassificationDao(): ConversationClassificationDao
    abstract fun messageAssetDao(): MessageAssetDao
    // v17 — Send delay / Undo Send.
    abstract fun pendingDelayedSendDao(): PendingDelayedSendDao

    companion object {
        @Volatile
        private var instance: MessagesDatabase? = null

        /** Column set of the v4 `sync_state` table (identity of 4.json). */
        internal val V4_SYNC_COLUMNS: Set<String> = setOf(
            "source", "newestDate", "newestId", "oldestDate", "oldestId",
            "initialWindowReady", "historyBackfillComplete", "lastReconcileAt", "schemaVersion"
        )

        /** v4 `sync_state` create — EXACT text from 4.json (Room validates verbatim). */
        private const val SYNC_STATE_V4_CREATE =
            "CREATE TABLE IF NOT EXISTS `sync_state` (`source` TEXT NOT NULL, `newestDate` INTEGER NOT NULL, `newestId` INTEGER NOT NULL, `oldestDate` INTEGER NOT NULL, `oldestId` INTEGER NOT NULL, `initialWindowReady` INTEGER NOT NULL, `historyBackfillComplete` INTEGER NOT NULL, `lastReconcileAt` INTEGER NOT NULL, `schemaVersion` INTEGER NOT NULL, PRIMARY KEY(`source`))"

        /** SQLite INTEGER ceiling — sentinel for "oldest watermark untouched". */
        private const val LONG_MAX = "9223372036854775807"

        /**
         * Data-preserving rebuild of `sync_state` for the shapes observed in
         * the wild, decided from the table's ACTUAL columns (PRAGMA table_info):
         *
         *  - missing table           → plain v4 create.
         *  - exact v4 shape (fresh   → NOTHING. The watermarks are the entire
         *    v3 installs or a DB      point of the table; dropping them made
         *    already repaired by a    every upgrade a 360K-message full rescan.
         *    previous migration)
         *  - legacy `newestSyncedDate` (v2, incl. DBs the broken shipped 2→3
         *    only ALTER-added columns onto) → copy into the new shape:
         *      newestDate        ← newestSyncedDate (or the ALTER-added newestDate)
         *      initialWindowReady← 1 (a legacy row means the old sync ran)
         *      history flags     ← backfillComplete (oldest window fully covered)
         *      lastReconcileAt   ← lastSyncAt
         *    Then swap via DROP + RENAME — standard Room table-rebuild.
         *  - any other unknown shape → last-resort drop+create (coordinator
         *    re-syncs exactly like the old behavior).
         */
        internal fun syncStateRebuildSql(existingColumns: Set<String>): List<String> {
            if (existingColumns.isEmpty()) return listOf(SYNC_STATE_V4_CREATE)
            if (existingColumns.containsAll(V4_SYNC_COLUMNS) &&
                !existingColumns.contains("newestSyncedDate")
            ) {
                return emptyList() // already exactly v4 — preserve everything
            }
            if (!existingColumns.contains("newestSyncedDate")) {
                return listOf("DROP TABLE IF EXISTS `sync_state`", SYNC_STATE_V4_CREATE)
            }
            val has = { col: String -> existingColumns.contains(col) }
            val newestExpr = if (has("newestDate"))
                "CASE WHEN `newestSyncedDate` > 0 THEN `newestSyncedDate` ELSE IFNULL(`newestDate`, 0) END"
            else "IFNULL(`newestSyncedDate`, 0)"
            val newestIdExpr = if (has("newestId")) "IFNULL(`newestId`, 0)" else "0"
            val backfillExpr = if (has("backfillComplete")) "IFNULL(`backfillComplete`, 0)" else "0"
            // When the legacy backfill finished, the whole history is already
            // mirrored: oldest watermark at 0 so no backfill ever re-runs.
            val oldestDateExpr = if (has("oldestDate"))
                "CASE WHEN IFNULL(`oldestDate`, 0) > 0 THEN `oldestDate` WHEN IFNULL(`backfillComplete`, 0) = 1 THEN 0 ELSE $LONG_MAX END"
            else "CASE WHEN $backfillExpr = 1 THEN 0 ELSE $LONG_MAX END"
            val oldestIdExpr = if (has("oldestId"))
                "CASE WHEN IFNULL(`oldestDate`, 0) > 0 THEN IFNULL(`oldestId`, 0) WHEN IFNULL(`backfillComplete`, 0) = 1 THEN 0 ELSE $LONG_MAX END"
            else "CASE WHEN $backfillExpr = 1 THEN 0 ELSE $LONG_MAX END"
            val reconcileExpr = when {
                has("lastReconcileAt") -> "IFNULL(`lastReconcileAt`, 0)"
                has("lastSyncAt") -> "IFNULL(`lastSyncAt`, 0)"
                else -> "0"
            }
            return listOf(
                "CREATE TABLE `sync_state_v4_new` (`source` TEXT NOT NULL, `newestDate` INTEGER NOT NULL, `newestId` INTEGER NOT NULL, `oldestDate` INTEGER NOT NULL, `oldestId` INTEGER NOT NULL, `initialWindowReady` INTEGER NOT NULL, `historyBackfillComplete` INTEGER NOT NULL, `lastReconcileAt` INTEGER NOT NULL, `schemaVersion` INTEGER NOT NULL, PRIMARY KEY(`source`))",
                "INSERT INTO `sync_state_v4_new` (`source`, `newestDate`, `newestId`, `oldestDate`, `oldestId`, `initialWindowReady`, `historyBackfillComplete`, `lastReconcileAt`, `schemaVersion`) SELECT `source`, $newestExpr, $newestIdExpr, $oldestDateExpr, $oldestIdExpr, 1, $backfillExpr, $reconcileExpr, 1 FROM `sync_state`",
                "DROP TABLE `sync_state`",
                "ALTER TABLE `sync_state_v4_new` RENAME TO `sync_state`"
            )
        }

        /** Reads the live column set and applies [syncStateRebuildSql]. */
        internal fun migrateSyncStateDataPreserving(db: SupportSQLiteDatabase) {
            val columns = db.query("PRAGMA table_info(`sync_state`)").use { c ->
                val nameIdx = c.getColumnIndexOrThrow("name")
                buildSet { while (c.moveToNext()) add(c.getString(nameIdx)) }
            }
            syncStateRebuildSql(columns).forEach { db.execSQL(it) }
        }

        /**
         * Statements that take a v2 OR v3 database to the exact v4 schema
         * EXCEPT `sync_state`, which is migrated separately by
         * [migrateSyncStateDataPreserving] so watermarks survive upgrades.
         * Kept in lockstep with
         * `app/schemas/com.autonomousone.messages.data.MessagesDatabase/4.json`
         * (asserted by MigrationToV4SqlTest). All statements are idempotent
         * for every possible starting shape.
         */
        internal val UPGRADE_TO_V4_SQL: List<String> = listOf(
            // 1. Drop the non-managed indexes (hand-rolled in 2→3; not declared).
            "DROP INDEX IF EXISTS `idx_messages_thread_unread`",
            "DROP INDEX IF EXISTS `idx_messages_thread_date_id`",
            // 2. Room-managed index for O(unread) COUNT.
            "CREATE INDEX IF NOT EXISTS `index_messages_threadId_read_type` ON `messages` (`threadId`, `read`, `type`)",
            // 3. Heal the Room-declared indexes (no-ops on DBs that already have them).
            "CREATE INDEX IF NOT EXISTS `index_messages_threadId_date_providerId` ON `messages` (`threadId`, `date`, `providerId`)",
            "CREATE INDEX IF NOT EXISTS `index_messages_normalizedAddress_date` ON `messages` (`normalizedAddress`, `date`)",
            "CREATE INDEX IF NOT EXISTS `index_messages_date` ON `messages` (`date`)",
            // 4. FTS4 virtual table + content-sync triggers — EXACT text from 4.json.
            "CREATE VIRTUAL TABLE IF NOT EXISTS `messages_fts` USING FTS4(`body` TEXT NOT NULL, content=`messages`)",
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_messages_fts_BEFORE_UPDATE BEFORE UPDATE ON `messages` BEGIN DELETE FROM `messages_fts` WHERE `docid`=OLD.`rowid`; END",
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_messages_fts_BEFORE_DELETE BEFORE DELETE ON `messages` BEGIN DELETE FROM `messages_fts` WHERE `docid`=OLD.`rowid`; END",
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_messages_fts_AFTER_UPDATE AFTER UPDATE ON `messages` BEGIN INSERT INTO `messages_fts`(`docid`, `body`) VALUES (NEW.`rowid`, NEW.`body`); END",
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_messages_fts_AFTER_INSERT AFTER INSERT ON `messages` BEGIN INSERT INTO `messages_fts`(`docid`, `body`) VALUES (NEW.`rowid`, NEW.`body`); END"
        )

        /**
         * Direct v2 → v4. Old users never touch the broken historical 2→3 path.
         * sync_state is COPIED (watermarks preserved), never dropped.
         */
        val MIGRATION_2_4 = object : Migration(2, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migrateSyncStateDataPreserving(db)
                UPGRADE_TO_V4_SQL.forEach { db.execSQL(it) }
            }
        }

        /**
         * v3 → v4. Fresh v3 DBs already have the exact v4 sync_state — they
         * keep every watermark (no rescan after app update). DBs that went
         * through the broken shipped 2→3 carry legacy columns and are rebuilt
         * with a data-preserving copy.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migrateSyncStateDataPreserving(db)
                UPGRADE_TO_V4_SQL.forEach { db.execSQL(it) }
            }
        }

        /**
         * v4 → v5: outgoing-send segment ledger (additive only). Text must
         * match the KSP-generated 5.json exactly (Room validates verbatim).
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `send_segments` (`rowId` INTEGER NOT NULL, `partIndex` INTEGER NOT NULL, `partCount` INTEGER NOT NULL, `sentAt` INTEGER NOT NULL, `subscriptionId` INTEGER NOT NULL, `success` INTEGER NOT NULL, PRIMARY KEY(`rowId`, `partIndex`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_send_segments_sentAt` ON `send_segments` (`sentAt`)"
                )
            }
        }

        /**
         * v5 → v6: the conversation projection learns the newest message's
         * TYPE so Home renders "You:" without probing messages (additive
         * only). Backfill runs in the same transaction; rows whose thread
         * has no messages keep the incoming default.
         *
         * Kept as bare SQL so the JVM test (MigrationToV6SqlTest) can pin
         * every statement against the generated 6.json — same pattern as
         * UPGRADE_TO_V4_SQL.
         */
        internal val UPGRADE_TO_V6_SQL: List<String> = listOf(
            "ALTER TABLE `conversations` ADD COLUMN `lastMessageType` INTEGER NOT NULL DEFAULT 1",
            "UPDATE conversations SET lastMessageType = (" +
                "SELECT type FROM messages " +
                "WHERE messages.threadId = conversations.threadId " +
                "ORDER BY date DESC, source DESC, providerId DESC " +
                "LIMIT 1" +
                ") WHERE EXISTS (" +
                "SELECT 1 FROM messages WHERE messages.threadId = conversations.threadId" +
                ")"
        )

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                UPGRADE_TO_V6_SQL.forEach { db.execSQL(it) }
            }
        }

        /**
         * v6 → v7: PR-01 durability tables — ADDITIVE only (fresh CREATEs;
         * text must match the KSP-generated 7.json exactly, pinned by
         * GatewaySyncSchemaTest). Defaults live in the Kotlin entities, so
         * no DEFAULT clauses here (same pattern as MIGRATION_4_5).
         */
        internal val UPGRADE_TO_V7_SQL: List<String> = listOf(
            "CREATE TABLE IF NOT EXISTS `remote_conversation_map` (`conversationId` TEXT NOT NULL, `threadId` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`conversationId`))",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_remote_conversation_map_threadId` ON `remote_conversation_map` (`threadId`)",
            "CREATE TABLE IF NOT EXISTS `gateway_event_outbox` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `eventUuid` TEXT NOT NULL, `eventType` TEXT NOT NULL, `aggregateId` TEXT NOT NULL, `sequenceLocal` INTEGER NOT NULL, `ciphertext` BLOB NOT NULL, `encoding` TEXT NOT NULL, `schemaVersion` INTEGER NOT NULL, `cryptoVersion` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `attemptCount` INTEGER NOT NULL, `nextAttemptAt` INTEGER NOT NULL, `state` TEXT NOT NULL, `serverSequence` INTEGER NOT NULL, `ackedAt` INTEGER NOT NULL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_gateway_event_outbox_eventUuid` ON `gateway_event_outbox` (`eventUuid`)",
            "CREATE INDEX IF NOT EXISTS `index_gateway_event_outbox_state_nextAttemptAt` ON `gateway_event_outbox` (`state`, `nextAttemptAt`)",
            "CREATE INDEX IF NOT EXISTS `index_gateway_event_outbox_aggregateId` ON `gateway_event_outbox` (`aggregateId`)",
            "CREATE TABLE IF NOT EXISTS `remote_commands` (`commandId` TEXT NOT NULL, `type` TEXT NOT NULL, `ciphertext` BLOB NOT NULL, `encoding` TEXT NOT NULL, `schemaVersion` INTEGER NOT NULL, `cryptoVersion` INTEGER NOT NULL, `signature` BLOB NOT NULL, `senderDeviceId` TEXT NOT NULL, `issuedAt` INTEGER NOT NULL, `receivedAt` INTEGER NOT NULL, `expiresAt` INTEGER NOT NULL, `nonce` TEXT NOT NULL, `idempotencyKey` TEXT NOT NULL, `state` TEXT NOT NULL, PRIMARY KEY(`commandId`))",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_remote_commands_idempotencyKey` ON `remote_commands` (`idempotencyKey`)",
            "CREATE INDEX IF NOT EXISTS `index_remote_commands_state_receivedAt` ON `remote_commands` (`state`, `receivedAt`)",
            "CREATE TABLE IF NOT EXISTS `remote_command_executions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `commandId` TEXT NOT NULL, `attempt` INTEGER NOT NULL, `startedAt` INTEGER NOT NULL, `finishedAt` INTEGER NOT NULL, `result` TEXT NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_remote_command_executions_commandId` ON `remote_command_executions` (`commandId`)",
            "CREATE TABLE IF NOT EXISTS `sync_cursors` (`direction` TEXT NOT NULL, `lastSequence` INTEGER NOT NULL, `lastServerAck` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`direction`))"
        )

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                UPGRADE_TO_V7_SQL.forEach { db.execSQL(it) }
            }
        }

        /**
         * v8 — LINKED DEVICE CONTROL: Android's local Trust Registry.
         * New tables only; no existing data touched.
         */
        private val UPGRADE_TO_V8_SQL = listOf(
            """CREATE TABLE IF NOT EXISTS trusted_devices (
                deviceId TEXT NOT NULL PRIMARY KEY,
                accountId TEXT NOT NULL,
                displayName TEXT NOT NULL,
                deviceType TEXT NOT NULL,
                origin TEXT NOT NULL,
                signingPublicKey TEXT NOT NULL,
                encryptionPublicKey TEXT NOT NULL,
                capabilitiesJson TEXT NOT NULL,
                historyGrant TEXT NOT NULL,
                certificateJson TEXT NOT NULL,
                certificateSignature TEXT NOT NULL,
                trustSequence INTEGER NOT NULL,
                status TEXT NOT NULL,
                approvedAt INTEGER NOT NULL,
                expiresAt INTEGER NOT NULL,
                revokedAt INTEGER,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            )""",
            """CREATE TABLE IF NOT EXISTS trust_statement_outbox (
                statementId TEXT NOT NULL PRIMARY KEY,
                trustSequence INTEGER NOT NULL,
                operation TEXT NOT NULL,
                deviceId TEXT NOT NULL,
                payload TEXT NOT NULL,
                rootSignature TEXT NOT NULL,
                state TEXT NOT NULL,
                attemptCount INTEGER NOT NULL,
                createdAt INTEGER NOT NULL,
                ackedAt INTEGER
            )""",
            "CREATE UNIQUE INDEX IF NOT EXISTS index_trust_statement_outbox_trustSequence ON trust_statement_outbox(trustSequence)",
            """CREATE TABLE IF NOT EXISTS device_telemetry (
                deviceId TEXT NOT NULL PRIMARY KEY,
                sessionActive INTEGER NOT NULL,
                lastSeenAt INTEGER,
                sessionExpiresAt INTEGER,
                onlineNow INTEGER NOT NULL,
                telemetryFetchedAt INTEGER NOT NULL
            )"""
        )

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                UPGRADE_TO_V8_SQL.forEach { db.execSQL(it) }
            }
        }

        internal val UPGRADE_TO_V9_SQL = listOf(
            "CREATE TABLE IF NOT EXISTS conversation_key_epochs (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, epochId TEXT NOT NULL, conversationId TEXT NOT NULL, generation INTEGER NOT NULL, historyFloor INTEGER NOT NULL, category TEXT NOT NULL, wrappedKey BLOB NOT NULL, createdAt INTEGER NOT NULL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS index_conversation_key_epochs_conversationId_generation_historyFloor_category ON conversation_key_epochs(conversationId, generation, historyFloor, category)"
        )
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) { UPGRADE_TO_V9_SQL.forEach(db::execSQL) }
        }

        internal val UPGRADE_TO_V10_SQL = listOf(
            "ALTER TABLE `gateway_event_outbox` ADD COLUMN `messageId` TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE `gateway_event_outbox` ADD COLUMN `revision` INTEGER NOT NULL DEFAULT 1",
            "ALTER TABLE `gateway_event_outbox` ADD COLUMN `sortKey` INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE `gateway_event_outbox` ADD COLUMN `priority` TEXT NOT NULL DEFAULT 'REALTIME'",
            "CREATE INDEX IF NOT EXISTS `index_gateway_event_outbox_state_priority_nextAttemptAt` ON `gateway_event_outbox` (`state`, `priority`, `nextAttemptAt`)"
        )
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) { UPGRADE_TO_V10_SQL.forEach(db::execSQL) }
        }

        internal val UPGRADE_TO_V11_SQL = listOf(
            "ALTER TABLE `gateway_event_outbox` ADD COLUMN `historySource` TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE `gateway_event_outbox` ADD COLUMN `historyGeneration` INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE `gateway_event_outbox` ADD COLUMN `historyOrdinal` INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE `gateway_event_outbox` ADD COLUMN `historyDate` INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE `gateway_event_outbox` ADD COLUMN `historyProviderId` INTEGER NOT NULL DEFAULT 0",
            "CREATE INDEX IF NOT EXISTS `index_gateway_event_outbox_historySource_historyGeneration_historyOrdinal` ON `gateway_event_outbox` (`historySource`, `historyGeneration`, `historyOrdinal`)",
            "CREATE TABLE IF NOT EXISTS `cloud_history_checkpoint` (`source` TEXT NOT NULL, `generation` INTEGER NOT NULL, `producerCursorDate` INTEGER NOT NULL, `producerCursorProviderId` INTEGER NOT NULL, `nextOrdinal` INTEGER NOT NULL, `ackedContiguousOrdinal` INTEGER NOT NULL, `ackedCursorDate` INTEGER NOT NULL, `ackedCursorProviderId` INTEGER NOT NULL, `sourceExhausted` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`source`))"
        )
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) { UPGRADE_TO_V11_SQL.forEach(db::execSQL) }
        }

        /**
         * v12 splits the send_segments ledger into an immutable submission half
         * (submittedAt) and a mutable callback half (callbackAt/Result/State).
         *
         * A table rebuild is required because SQLite cannot drop the old
         * sentAt/success columns in place. It is NOT destructive: every row is
         * carried over, submittedAt is mapped from the old sentAt (the best
         * available approximation of when the segment was submitted) and the
         * old success flag becomes the callback verdict.
         */
        internal val UPGRADE_TO_V12_SQL = listOf(
            "CREATE TABLE IF NOT EXISTS `send_segments_new` (`rowId` INTEGER NOT NULL, `partIndex` INTEGER NOT NULL, `partCount` INTEGER NOT NULL, `submittedAt` INTEGER, `subscriptionId` INTEGER NOT NULL, `callbackAt` INTEGER, `callbackResult` INTEGER, `callbackState` TEXT NOT NULL, PRIMARY KEY(`rowId`, `partIndex`))",
            "INSERT INTO `send_segments_new` (`rowId`, `partIndex`, `partCount`, `submittedAt`, `subscriptionId`, `callbackAt`, `callbackResult`, `callbackState`) " +
                "SELECT `rowId`, `partIndex`, `partCount`, `sentAt`, `subscriptionId`, `sentAt`, NULL, " +
                "CASE WHEN `success` = 1 THEN 'CONFIRMED' ELSE 'FAILED' END FROM `send_segments`",
            "DROP TABLE `send_segments`",
            "ALTER TABLE `send_segments_new` RENAME TO `send_segments`",
            "CREATE INDEX IF NOT EXISTS `index_send_segments_submittedAt` ON `send_segments` (`submittedAt`)"
        )
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) { UPGRADE_TO_V12_SQL.forEach(db::execSQL) }
        }

        /**
         * v13 adds the stable typed failure code to the send ledger (ADDITIVE,
         * no rebuild): each part's modem verdict now carries an actionable
         * reason such as NO_SERVICE / RADIO_OFF / MODEM_FAILURE that survives
         * process death, reboot and locale changes.
         */
        internal val UPGRADE_TO_V13_SQL = listOf(
            "ALTER TABLE `send_segments` ADD COLUMN `callbackFailureCode` TEXT"
        )
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) { UPGRADE_TO_V13_SQL.forEach(db::execSQL) }
        }

        /**
         * v14 — DURABLE EXACT-REPAIR QUEUE (ADDITIVE, one new table).
         *
         * Exact provider reads that fail used to be retried from an in-process
         * map with a 256-entry cap that evicted the oldest entry. Correctness
         * work must survive process death and must never be silently discarded,
         * so the queue is a Room table with a generation-scoped claim/ack/nack
         * protocol (see ProviderRepairEntity).
         *
         * No existing table is touched; the exact CREATE text is pinned by
         * ProviderRepairQueueTest against the shape Room generates.
         */
        internal val UPGRADE_TO_V14_SQL = listOf(
            "CREATE TABLE IF NOT EXISTS `provider_repair_queue` (`source` TEXT NOT NULL, `providerId` INTEGER NOT NULL, `generation` INTEGER NOT NULL, `state` TEXT NOT NULL, `attempts` INTEGER NOT NULL, `nextRetryAt` INTEGER NOT NULL, `leaseUntil` INTEGER NOT NULL, `lastFailureReason` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`source`, `providerId`))",
            "CREATE INDEX IF NOT EXISTS `index_provider_repair_queue_state_nextRetryAt` ON `provider_repair_queue` (`state`, `nextRetryAt`)",
            "CREATE INDEX IF NOT EXISTS `index_provider_repair_queue_nextRetryAt` ON `provider_repair_queue` (`nextRetryAt`)"
        )
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) { UPGRADE_TO_V14_SQL.forEach(db::execSQL) }
        }

        /**
         * v15 — REPAIR INTENT (ADDITIVE, no rebuild).
         *
         * v14 queue rows carry no record of WHY the identity was queued, and its
         * consumer treated any successful absence as a proven delete. That is
         * unsafe for providerRowChanged/status work, where the row is expected to
         * exist and is often not query-visible yet.
         *
         * SAFETY: every pre-existing row is migrated to EXPECT_EXISTS, the
         * NON-DESTRUCTIVE intent. A v14 row must never become delete-capable by
         * accident; the two-sided integrity audit resolves such rows deliberately
         * later, through a separate VERIFY_DELETE_CANDIDATE generation.
         *
         * The SQL DEFAULT is declared on the entity via @ColumnInfo, so the ALTER
         * statements below produce exactly the schema Room validates against.
         */
        internal val UPGRADE_TO_V15_SQL = listOf(
            "ALTER TABLE `provider_repair_queue` ADD COLUMN `intent` TEXT NOT NULL DEFAULT 'EXPECT_EXISTS'",
            "ALTER TABLE `provider_repair_queue` ADD COLUMN `intentSince` INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE `provider_repair_queue` ADD COLUMN `absenceCount` INTEGER NOT NULL DEFAULT 0",
            "UPDATE `provider_repair_queue` SET `intentSince` = `updatedAt` WHERE `intentSince` = 0",
            "CREATE TABLE IF NOT EXISTS `integrity_audit_state` (`source` TEXT NOT NULL, `direction` TEXT NOT NULL, `cursorDate` INTEGER NOT NULL, `cursorProviderId` INTEGER NOT NULL, `cycleStartedAt` INTEGER NOT NULL, `lastCompletedAt` INTEGER NOT NULL, `nextRunAt` INTEGER NOT NULL, `state` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`source`, `direction`))"
        )
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) { UPGRADE_TO_V15_SQL.forEach(db::execSQL) }
        }

        /**
         * v15 → v16 — UX USER-STATE (ADDITIVE ONLY, nothing rebuilt or dropped).
         *
         * Six new tables, exactly as declared in UxEntities.kt:
         *
         *   conversation_preferences      per-conversation user state (unread
         *                                 marker, mute, channel flag, category
         *                                 override, spam report provenance)
         *   message_user_state            per-message user state (star, trash,
         *                                 OTP-cleanup opt-out)
         *   trashed_threads               conversation TOMBSTONES — one row per
         *                                 deleted conversation, NEVER one per
         *                                 message (a 100K-message conversation
         *                                 must not create 100K rows)
         *   message_classification        local classification result
         *   conversation_classification   per-conversation category projection
         *   message_assets                Media/Links/Files METADATA only
         *
         * Every table starts EMPTY. In particular: no message becomes starred,
         * trashed, spam or classified, no conversation becomes manually unread or
         * muted, and OTP auto-delete stays OFF — the non-destructive contract
         * asserted by MigrationToV16SqlTest.
         *
         * The CREATE text is generated BY Room from the entities and pinned by
         * that test against 16.json; keep this list and UxEntities.kt in
         * lockstep. There is deliberately NO composite FOREIGN KEY from
         * message_user_state / message_assets to messages — see
         * MessageUserStateEntity for why explicit cleanup is safer here.
         */
        internal val UPGRADE_TO_V16_SQL: List<String> = listOf(
            // ── conversation_preferences ────────────────────────────────────
            "CREATE TABLE IF NOT EXISTS `conversation_preferences` (`threadId` INTEGER NOT NULL, `manualUnread` INTEGER NOT NULL, `mutedUntil` INTEGER NOT NULL, `customNotificationChannel` INTEGER NOT NULL, `categoryOverride` TEXT, `spam` INTEGER NOT NULL, `spamReportedAt` INTEGER NOT NULL, `spamBlockedByReport` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`threadId`))",
            "CREATE INDEX IF NOT EXISTS `index_conversation_preferences_spam` ON `conversation_preferences` (`spam`)",
            "CREATE INDEX IF NOT EXISTS `index_conversation_preferences_manualUnread` ON `conversation_preferences` (`manualUnread`)",
            "CREATE INDEX IF NOT EXISTS `index_conversation_preferences_mutedUntil` ON `conversation_preferences` (`mutedUntil`)",
            // ── message_user_state ──────────────────────────────────────────
            "CREATE TABLE IF NOT EXISTS `message_user_state` (`source` TEXT NOT NULL, `providerId` INTEGER NOT NULL, `threadId` INTEGER NOT NULL, `starred` INTEGER NOT NULL, `starredAt` INTEGER NOT NULL, `trashedAt` INTEGER NOT NULL, `purgeAt` INTEGER NOT NULL, `keepFromOtpCleanup` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`source`, `providerId`))",
            "CREATE INDEX IF NOT EXISTS `index_message_user_state_threadId` ON `message_user_state` (`threadId`)",
            "CREATE INDEX IF NOT EXISTS `index_message_user_state_starred` ON `message_user_state` (`starred`)",
            "CREATE INDEX IF NOT EXISTS `index_message_user_state_trashedAt` ON `message_user_state` (`trashedAt`)",
            "CREATE INDEX IF NOT EXISTS `index_message_user_state_purgeAt` ON `message_user_state` (`purgeAt`)",
            // ── trashed_threads ─────────────────────────────────────────────
            "CREATE TABLE IF NOT EXISTS `trashed_threads` (`threadId` INTEGER NOT NULL, `deletedAt` INTEGER NOT NULL, `purgeAt` INTEGER NOT NULL, `cutoffDate` INTEGER NOT NULL, `cutoffSource` TEXT NOT NULL, `cutoffProviderId` INTEGER NOT NULL, PRIMARY KEY(`threadId`))",
            "CREATE INDEX IF NOT EXISTS `index_trashed_threads_purgeAt` ON `trashed_threads` (`purgeAt`)",
            // ── message_classification ──────────────────────────────────────
            "CREATE TABLE IF NOT EXISTS `message_classification` (`source` TEXT NOT NULL, `providerId` INTEGER NOT NULL, `threadId` INTEGER NOT NULL, `category` TEXT NOT NULL, `confidence` REAL NOT NULL, `isOtp` INTEGER NOT NULL, `otpDeleteEligibleAt` INTEGER NOT NULL, `classifiedAt` INTEGER NOT NULL, PRIMARY KEY(`source`, `providerId`))",
            "CREATE INDEX IF NOT EXISTS `index_message_classification_threadId` ON `message_classification` (`threadId`)",
            "CREATE INDEX IF NOT EXISTS `index_message_classification_category` ON `message_classification` (`category`)",
            "CREATE INDEX IF NOT EXISTS `index_message_classification_isOtp` ON `message_classification` (`isOtp`)",
            "CREATE INDEX IF NOT EXISTS `index_message_classification_otpDeleteEligibleAt` ON `message_classification` (`otpDeleteEligibleAt`)",
            // ── conversation_classification ─────────────────────────────────
            "CREATE TABLE IF NOT EXISTS `conversation_classification` (`threadId` INTEGER NOT NULL, `category` TEXT NOT NULL, `confidence` REAL NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`threadId`))",
            "CREATE INDEX IF NOT EXISTS `index_conversation_classification_category` ON `conversation_classification` (`category`)",
            // ── message_assets ──────────────────────────────────────────────
            "CREATE TABLE IF NOT EXISTS `message_assets` (`assetKey` TEXT NOT NULL, `source` TEXT NOT NULL, `providerId` INTEGER NOT NULL, `threadId` INTEGER NOT NULL, `kind` TEXT NOT NULL, `value` TEXT NOT NULL, `mimeType` TEXT NOT NULL, `displayName` TEXT NOT NULL, `date` INTEGER NOT NULL, PRIMARY KEY(`assetKey`))",
            "CREATE INDEX IF NOT EXISTS `index_message_assets_threadId_date` ON `message_assets` (`threadId`, `date`)",
            "CREATE INDEX IF NOT EXISTS `index_message_assets_kind` ON `message_assets` (`kind`)",
            "CREATE INDEX IF NOT EXISTS `index_message_assets_source_providerId` ON `message_assets` (`source`, `providerId`)"
        )

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                UPGRADE_TO_V16_SQL.forEach(db::execSQL)
            }
        }

        /** Room schema version this build's entity set matches. */
        const val CURRENT_SCHEMA_VERSION = 18

        /** Previous schema version the newest migration starts from. */
        const val PREVIOUS_SCHEMA_VERSION = 17

        /**
         * v16 -> v17 (FEATURE 11, Send delay / Undo Send).
         *
         * ADDITIVE and non-destructive, like every migration here: one fresh
         * CREATE TABLE plus its two indices. No existing table is rebuilt,
         * altered or dropped, so the 360K-message mirror, the send_segments
         * ledger and the v16 user-state tables are untouched.
         *
         * The table starts EMPTY on every upgraded install: an upgrade must
         * never invent a pending send the user did not schedule, and an empty
         * ledger means "nothing is held back", which is exactly the default
         * (delay OFF).
         *
         * `CREATE TABLE IF NOT EXISTS` / `CREATE INDEX IF NOT EXISTS` on
         * purpose — Room re-runs a migration after a partially-failed open.
         */
        internal val UPGRADE_TO_V17_SQL: List<String> = listOf(
            "CREATE TABLE IF NOT EXISTS `pending_delayed_sends` (`intentId` TEXT NOT NULL, `body` TEXT NOT NULL, `phoneToken` TEXT NOT NULL, `threadId` INTEGER NOT NULL, `state` TEXT NOT NULL, `dueAt` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `claimedAt` INTEGER NOT NULL, `sentRowId` INTEGER NOT NULL, `attempts` INTEGER NOT NULL, `failureCode` TEXT, PRIMARY KEY(`intentId`))",
            "CREATE INDEX IF NOT EXISTS `index_pending_delayed_sends_threadId` ON `pending_delayed_sends` (`threadId`)",
            "CREATE INDEX IF NOT EXISTS `index_pending_delayed_sends_dueAt` ON `pending_delayed_sends` (`dueAt`)"
        )

        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                UPGRADE_TO_V17_SQL.forEach(db::execSQL)
            }
        }

        /** v17 -> v18: safe operational metadata for future dead-letter rows. */
        internal val UPGRADE_TO_V18_SQL: List<String> = listOf(
            "ALTER TABLE `gateway_event_outbox` ADD COLUMN `failureCategory` TEXT",
            "ALTER TABLE `gateway_event_outbox` ADD COLUMN `failureHttpStatus` INTEGER",
            "ALTER TABLE `gateway_event_outbox` ADD COLUMN `lastAttemptAt` INTEGER",
            "ALTER TABLE `gateway_event_outbox` ADD COLUMN `deadLetteredAt` INTEGER",
            "ALTER TABLE `gateway_event_outbox` ADD COLUMN `failureAppVersion` TEXT"
        )

        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                UPGRADE_TO_V18_SQL.forEach(db::execSQL)
            }
        }

        fun get(context: Context): MessagesDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context).also { instance = it }
            }

        private fun build(context: Context): MessagesDatabase {
            val builder = Room.databaseBuilder(
                context.applicationContext,
                MessagesDatabase::class.java,
                "messages.db"
            )
                .addMigrations(MIGRATION_2_4, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18)

            // v2.6.10: destructive fallback is a DEBUG-only convenience. In
            // release, a missing migration must fail loudly in QA — never
            // silently wipe the local read model (send_segments ledger, sync
            // state, projections) and force a full Telephony re-crawl on
            // hundreds of thousands of rows.
            if (BuildConfig.DEBUG) {
                builder.fallbackToDestructiveMigration(dropAllTables = true)
            }
            return builder.build()
        }
    }
}
