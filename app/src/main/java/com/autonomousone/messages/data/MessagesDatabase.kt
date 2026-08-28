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
         * Migration v2 → v3:
         *  1. Partial index for unread count: O(unread) instead of O(total)
         *  2. Keyset pagination index for deep conversation scroll
         *  3. Dual watermarks in sync_state
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Partial index for unread count — makes COUNT(*)
                //    WHERE threadId = ? AND read = 0 AND type = 1
                //    O(unread_count) instead of O(total_messages_in_thread).
                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS idx_messages_thread_unread
                    ON messages(threadId)
                    WHERE read = 0 AND type = 1
                """)

                // 2. Keyset pagination index — makes
                //    WHERE threadId = ? AND date < ? ORDER BY date DESC
                //    O(page_size) regardless of scroll depth.
                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS idx_messages_thread_date_id
                    ON messages(threadId, date DESC, providerId DESC)
                """)

                // 3. Expand sync_state with dual watermarks.
                db.execSQL("ALTER TABLE sync_state ADD COLUMN newestId INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sync_state ADD COLUMN oldestDate INTEGER NOT NULL DEFAULT 9223372036854775807")
                db.execSQL("ALTER TABLE sync_state ADD COLUMN oldestId INTEGER NOT NULL DEFAULT 9223372036854775807")
                db.execSQL("ALTER TABLE sync_state ADD COLUMN initialWindowReady INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sync_state ADD COLUMN historyBackfillComplete INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sync_state ADD COLUMN lastReconcileAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sync_state ADD COLUMN schemaVersion INTEGER NOT NULL DEFAULT 1")
            }
        }

        /**
         * Exact SQL for v3 → v4, kept in lockstep with
         * `app/schemas/com.autonomousone.messages.data.MessagesDatabase/4.json`
         * (asserted by MigrationV3V4SqlTest). Executing these statements makes
         * an upgraded database byte-for-byte equal to a fresh v4 install:
         *  - Room-MANAGED indexes (the old hand-rolled PARTIAL index was
         *    silently missing on fresh installs — fresh vs upgrade now converge);
         *  - the FTS4 virtual table + Room's content-sync triggers.
         */
        internal val MIGRATION_3_4_SQL: List<String> = listOf(
            // 1a. Drop the non-managed indexes.
            "DROP INDEX IF EXISTS `idx_messages_thread_unread`",
            "DROP INDEX IF EXISTS `idx_messages_thread_date_id`",
            // 1b. Room-managed index for O(unread) COUNT.
            "CREATE INDEX IF NOT EXISTS `index_messages_threadId_read_type` ON `messages` (`threadId`, `read`, `type`)",
            // 1c. Heal the Room-declared indexes (defensive; no-ops on DBs that already have them).
            "CREATE INDEX IF NOT EXISTS `index_messages_threadId_date_providerId` ON `messages` (`threadId`, `date`, `providerId`)",
            "CREATE INDEX IF NOT EXISTS `index_messages_normalizedAddress_date` ON `messages` (`normalizedAddress`, `date`)",
            "CREATE INDEX IF NOT EXISTS `index_messages_date` ON `messages` (`date`)",
            // 2. FTS4 virtual table + content-sync triggers — EXACT text from
            //    the generated 4.json (Room validates trigger SQL verbatim).
            "CREATE VIRTUAL TABLE IF NOT EXISTS `messages_fts` USING FTS4(`body` TEXT NOT NULL, content=`messages`)",
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_messages_fts_BEFORE_UPDATE BEFORE UPDATE ON `messages` BEGIN DELETE FROM `messages_fts` WHERE `docid`=OLD.`rowid`; END",
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_messages_fts_BEFORE_DELETE BEFORE DELETE ON `messages` BEGIN DELETE FROM `messages_fts` WHERE `docid`=OLD.`rowid`; END",
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_messages_fts_AFTER_UPDATE AFTER UPDATE ON `messages` BEGIN INSERT INTO `messages_fts`(`docid`, `body`) VALUES (NEW.`rowid`, NEW.`body`); END",
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_messages_fts_AFTER_INSERT AFTER INSERT ON `messages` BEGIN INSERT INTO `messages_fts`(`docid`, `body`) VALUES (NEW.`rowid`, NEW.`body`); END"
        )

        /**
         * Migration v3 → v4.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_3_4_SQL.forEach { db.execSQL(it) }
            }
        }

        fun get(context: Context): MessagesDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MessagesDatabase::class.java,
                    "messages.db"
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                    // Destructive fallback ONLY for pre-release installs.
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
    }
}
