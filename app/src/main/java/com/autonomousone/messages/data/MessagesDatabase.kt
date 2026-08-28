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
        MessageFts::class
    ],
    version = 4,
    exportSchema = true
)
abstract class MessagesDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao
    abstract fun conversationDao(): ConversationDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun messageFtsDao(): MessageFtsDao

    companion object {
        @Volatile
        private var instance: MessagesDatabase? = null

        /**
         * v4 `sync_state` table — EXACT text from 4.json (Room validates
         * createSql verbatim). `sync_state` is sync bookkeeping only (no
         * business data), so the safe repair for any pre-v4 shape is to drop
         * and recreate it; the coordinator then simply re-runs its initial sync.
         */
        private val SYNC_STATE_V4_SQL =
            "CREATE TABLE IF NOT EXISTS `sync_state` (`source` TEXT NOT NULL, `newestDate` INTEGER NOT NULL, `newestId` INTEGER NOT NULL, `oldestDate` INTEGER NOT NULL, `oldestId` INTEGER NOT NULL, `initialWindowReady` INTEGER NOT NULL, `historyBackfillComplete` INTEGER NOT NULL, `lastReconcileAt` INTEGER NOT NULL, `schemaVersion` INTEGER NOT NULL, PRIMARY KEY(`source`))"

        /**
         * Statements that take a v2 OR v3 database to the exact v4 schema, kept
         * in lockstep with
         * `app/schemas/com.autonomousone.messages.data.MessagesDatabase/4.json`
         * (asserted by MigrationToV4SqlTest).
         *
         * A single list is correct for BOTH source versions because the only
         * v2→v3 delta was `sync_state` (wrongly ALTERed in the shipped 2→3
         * migration — `newestSyncedDate` was never renamed to `newestDate`),
         * plus two hand-rolled indexes that v4 no longer declares. Rebuilding
         * sync_state + dropping/creating indexes + creating FTS is idempotent
         * for every possible starting shape.
         */
        internal val UPGRADE_TO_V4_SQL: List<String> = listOf(
            // 1. Rebuild sync_state: v2 used newestSyncedDate/backfillComplete/
            //    lastSyncAt; the shipped 2→3 only ADDed columns, so an upgraded
            //    DB is missing `newestDate` and Room validation crashes.
            "DROP TABLE IF EXISTS `sync_state`",
            SYNC_STATE_V4_SQL,
            // 2. Drop the non-managed indexes (hand-rolled in 2→3; not declared).
            "DROP INDEX IF EXISTS `idx_messages_thread_unread`",
            "DROP INDEX IF EXISTS `idx_messages_thread_date_id`",
            // 3. Room-managed index for O(unread) COUNT.
            "CREATE INDEX IF NOT EXISTS `index_messages_threadId_read_type` ON `messages` (`threadId`, `read`, `type`)",
            // 4. Heal the Room-declared indexes (no-ops on DBs that already have them).
            "CREATE INDEX IF NOT EXISTS `index_messages_threadId_date_providerId` ON `messages` (`threadId`, `date`, `providerId`)",
            "CREATE INDEX IF NOT EXISTS `index_messages_normalizedAddress_date` ON `messages` (`normalizedAddress`, `date`)",
            "CREATE INDEX IF NOT EXISTS `index_messages_date` ON `messages` (`date`)",
            // 5. FTS4 virtual table + content-sync triggers — EXACT text from 4.json.
            "CREATE VIRTUAL TABLE IF NOT EXISTS `messages_fts` USING FTS4(`body` TEXT NOT NULL, content=`messages`)",
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_messages_fts_BEFORE_UPDATE BEFORE UPDATE ON `messages` BEGIN DELETE FROM `messages_fts` WHERE `docid`=OLD.`rowid`; END",
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_messages_fts_BEFORE_DELETE BEFORE DELETE ON `messages` BEGIN DELETE FROM `messages_fts` WHERE `docid`=OLD.`rowid`; END",
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_messages_fts_AFTER_UPDATE AFTER UPDATE ON `messages` BEGIN INSERT INTO `messages_fts`(`docid`, `body`) VALUES (NEW.`rowid`, NEW.`body`); END",
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_messages_fts_AFTER_INSERT AFTER INSERT ON `messages` BEGIN INSERT INTO `messages_fts`(`docid`, `body`) VALUES (NEW.`rowid`, NEW.`body`); END"
        )

        /**
         * Direct v2 → v4. Old users never touch the broken historical 2→3 path.
         */
        val MIGRATION_2_4 = object : Migration(2, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                UPGRADE_TO_V4_SQL.forEach { db.execSQL(it) }
            }
        }

        /**
         * v3 → v4. Also repairs DBs that already went through the broken 2→3
         * (missing `newestDate`, leftover legacy columns) by rebuilding
         * sync_state — bookkeeping only, re-synced by the coordinator.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                UPGRADE_TO_V4_SQL.forEach { db.execSQL(it) }
            }
        }

        fun get(context: Context): MessagesDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MessagesDatabase::class.java,
                    "messages.db"
                )
                    .addMigrations(MIGRATION_2_4, MIGRATION_3_4)
                    // Destructive fallback ONLY when a migration path is missing
                    // (never for a migration that runs but yields a bad schema).
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
    }
}
