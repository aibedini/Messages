package com.autonomousone.messages.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
 */
@Database(
    entities = [
        MessageEntity::class,
        ConversationEntity::class,
        SyncStateEntity::class,
        MessageFts::class,
        SendSegmentEntity::class
    ],
    version = 6,
    exportSchema = true
)
abstract class MessagesDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao
    abstract fun conversationDao(): ConversationDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun messageFtsDao(): MessageFtsDao
    abstract fun sendSegmentDao(): SendSegmentDao

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

        fun get(context: Context): MessagesDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MessagesDatabase::class.java,
                    "messages.db"
                )
                    .addMigrations(MIGRATION_2_4, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    // Destructive fallback ONLY when a migration path is missing
                    // (never for a migration that runs but yields a bad schema).
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
    }
}
