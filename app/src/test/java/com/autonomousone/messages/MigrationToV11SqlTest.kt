package com.autonomousone.messages

import com.autonomousone.messages.data.MessagesDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationToV11SqlTest {
    @Test
    fun `v11 adds ACK checkpoint metadata without rebuilding outbox`() {
        val sql = MessagesDatabase.UPGRADE_TO_V11_SQL
        assertEquals(7, sql.size)
        assertEquals(5, sql.count { it.startsWith("ALTER TABLE `gateway_event_outbox` ADD COLUMN") })
        assertTrue(sql.any { it.contains("cloud_history_checkpoint") })
        assertTrue(sql.none { it.contains("DROP TABLE") })
    }
}
