package com.autonomousone.messages

import com.autonomousone.messages.data.MessagesDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationToV10SqlTest {
    @Test
    fun `v10 adds replication metadata without rebuilding the outbox`() {
        val sql = MessagesDatabase.UPGRADE_TO_V10_SQL
        assertEquals(5, sql.size)
        assertEquals(4, sql.count { it.startsWith("ALTER TABLE `gateway_event_outbox` ADD COLUMN") })
        assertTrue(sql.last().contains("state_priority_nextAttemptAt"))
        assertTrue(sql.none { it.contains("DROP TABLE") })
    }
}
