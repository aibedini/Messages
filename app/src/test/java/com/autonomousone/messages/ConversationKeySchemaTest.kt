package com.autonomousone.messages

import com.autonomousone.messages.data.MessagesDatabase
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ConversationKeySchemaTest {
    @Test fun additiveV9MigrationMatchesRoomGeneratedTableAndIndex() {
        assertEquals(8, MessagesDatabase.MIGRATION_8_9.startVersion)
        assertEquals(9, MessagesDatabase.MIGRATION_8_9.endVersion)
        val json = JSONObject(File("schemas/com.autonomousone.messages.data.MessagesDatabase/9.json").readText())
        val entities = json.getJSONObject("database").getJSONArray("entities")
        val epoch = (0 until entities.length()).map { entities.getJSONObject(it) }.single { it.getString("tableName") == "conversation_key_epochs" }
        fun normalize(sql: String) = sql.replace("\${TABLE_NAME}", "conversation_key_epochs").replace("`", "").filterNot(Char::isWhitespace)
        val expected = listOf(epoch.getString("createSql"), epoch.getJSONArray("indices").getJSONObject(0).getString("createSql")).map(::normalize)
        assertEquals(expected, MessagesDatabase.UPGRADE_TO_V9_SQL.map(::normalize))
        assertTrue(MessagesDatabase.UPGRADE_TO_V9_SQL.all { it.startsWith("CREATE ") })
    }
}
