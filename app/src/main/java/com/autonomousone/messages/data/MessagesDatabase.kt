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
 */
@Database(
    entities = [MessageEntity::class, ConversationEntity::class, SyncStateEntity::class],
    version = 3,
    exportSchema = true
)
abstract class MessagesDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao
    abstract fun conversationDao(): ConversationDao
    abstract fun syncStateDao(): SyncStateDao

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

        fun get(context: Context): MessagesDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MessagesDatabase::class.java,
                    "messages.db"
                )
                    .addMigrations(MIGRATION_2_3)
                    // Destructive fallback ONLY for pre-release installs.
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
    }
}
