package com.autonomousone.messages

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ReplicationFixtureTest {
    @Test
    fun `replication v3 fixture pins shared wire metadata`() {
        val fixture = JSONObject(File("../protocol/messages-web-replication-v3.json").readText())
        val event = fixture.getJSONObject("event")
        assertEquals(3, fixture.getInt("protocolVersion"))
        assertEquals("MESSAGE_CREATED", event.getString("type"))
        assertEquals(3, event.getInt("cryptoVersion"))
        assertEquals(2_000, fixture.getJSONObject("priority").getInt("maxPendingBackfill"))
    }
}
