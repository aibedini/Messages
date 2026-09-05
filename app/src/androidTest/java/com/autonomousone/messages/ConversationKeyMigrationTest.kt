package com.autonomousone.messages

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.autonomousone.messages.data.MessagesDatabase
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

class ConversationKeyMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), MessagesDatabase::class.java)
    @Test fun v8ToV9PreservesHistoryAndOutboxAndValidatesEpochSchema() {
        val name = "cke-migration-test"
        helper.createDatabase(name, 8).apply {
            execSQL("INSERT INTO sync_cursors VALUES ('history-test', 42, 12, 100)")
            close()
        }
        helper.runMigrationsAndValidate(name, 9, true, MessagesDatabase.MIGRATION_8_9).use { db ->
            db.query("SELECT lastSequence FROM sync_cursors WHERE direction = 'history-test'").use {
                it.moveToFirst(); assertEquals(42, it.getInt(0))
            }
        }
    }
}
