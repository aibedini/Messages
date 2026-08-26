package com.autonomousone.messages.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The app's local read-SSOT (phase-2 architecture): the UI reads from here;
 * TelephonySync keeps it in step with the system provider. v1 is the initial
 * schema — future schema changes bump the version and add a Migration.
 */
@Database(
    entities = [MessageEntity::class, ConversationEntity::class, SyncStateEntity::class],
    version = 1,
    exportSchema = true
)
abstract class MessagesDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao
    abstract fun conversationDao(): ConversationDao
    abstract fun syncStateDao(): SyncStateDao

    companion object {
        @Volatile
        private var instance: MessagesDatabase? = null

        fun get(context: Context): MessagesDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MessagesDatabase::class.java,
                    "messages.db"
                )
                    // ponytail: destructive fallback for pre-release installs
                    // only; once v2.3.x ships broadly this must become a real
                    // Migration path (schema is exported for that purpose).
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
    }
}
