package com.autonomousone.messages

import com.autonomousone.messages.evechat.push.EveChatPush
import com.autonomousone.messages.evechat.push.PushDeviceRegistration
import com.autonomousone.messages.evechat.push.PushRegistrationPolicy
import com.autonomousone.messages.evechat.push.PushRegistrationRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EveChatPushTest {

    private val base = mapOf(
        "source" to "evechat",
        "v" to "1",
        "conversation_id" to "c1",
        "message_id" to "m1",
        "agent_id" to "a1",
        "sent_at" to "1792663200000"
    )

    @Test
    fun `message push is parsed with all fields`() {
        val p = EveChatPush.parse(base + ("type" to "message.received") + ("title" to " Sara ") + ("body" to "salam"))!!
        assertEquals(EveChatPush.Type.MESSAGE, p.type)
        assertEquals("c1", p.conversationId)
        assertEquals("m1", p.messageId)
        assertEquals("a1", p.agentId)
        assertEquals(1_792_663_200_000L, p.sentAt)
        assertEquals("Sara", p.title)
        assertEquals("salam", p.body)
    }

    @Test
    fun `event types map to handling kinds`() {
        fun type(t: String) = EveChatPush.parse(base + ("type" to t))!!.type
        assertEquals(EveChatPush.Type.NEW_CONVERSATION, type("conversation.new"))
        assertEquals(EveChatPush.Type.CONVERSATION_UPDATED, type("conversation.updated"))
        assertEquals(EveChatPush.Type.CONVERSATION_UPDATED, type("conversation.resolved"))
        assertEquals(EveChatPush.Type.CONVERSATION_UPDATED, type("agent.assigned"))
        assertEquals(EveChatPush.Type.SYNC, type("sync"))
        assertEquals(EveChatPush.Type.SYNC, type("something.new"))
    }

    @Test
    fun `foreign or malformed messages are ignored`() {
        assertNull(EveChatPush.parse(mapOf("type" to "message.received", "conversation_id" to "c1")))
        assertNull(EveChatPush.parse(mapOf("source" to "other", "type" to "message.received")))
        assertNull(EveChatPush.parse(mapOf("source" to "evechat")))
        assertNull(EveChatPush.parse(emptyMap()))
    }

    @Test
    fun `newer payload versions degrade to a full sync`() {
        val p = EveChatPush.parse(base + ("type" to "message.received") + ("v" to "2"))!!
        assertEquals(EveChatPush.Type.SYNC, p.type)
    }

    @Test
    fun `blank fields become null and long text is capped`() {
        val p = EveChatPush.parse(base + ("type" to "message.received") + ("message_id" to "  ") + ("body" to "x".repeat(1000)))!!
        assertNull(p.messageId)
        assertEquals(300, p.body!!.length)
    }

    @Test
    fun `registration body carries the contract fields`() {
        val json = PushDeviceRegistration("d1", "tok", "3.9.0", 155, "Android 14 (34)", "Pixel 8", "fa-IR", "Asia/Tehran").toJson()
        assertEquals("d1", json.getString("device_id"))
        assertEquals("android", json.getString("platform"))
        assertEquals("fcm", json.getString("provider"))
        assertEquals("tok", json.getString("token"))
        assertEquals(155, json.getInt("app_build"))
        assertEquals("Asia/Tehran", json.getString("timezone"))
    }

    @Test
    fun `record round-trips and rejects garbage`() {
        val r = PushRegistrationRecord("tok", "https://chat.example.com", "a1", 155, 1_000L)
        assertEquals(r, PushRegistrationRecord.fromJson(r.toJson()))
        assertNull(PushRegistrationRecord.fromJson("{oops"))
        assertNull(PushRegistrationRecord.fromJson(null))
    }

    @Test
    fun `re-registration happens on any identity change or after the refresh interval`() {
        val now = 10_000_000_000L
        val r = PushRegistrationRecord("tok", "https://s", "a1", 155, now - 1_000)
        fun needs(record: PushRegistrationRecord? = r, token: String? = "tok", server: String = "https://s", agent: String? = "a1", build: Int = 155, at: Long = now) =
            PushRegistrationPolicy.needsRegistration(record, token, server, agent, build, at)

        assertFalse(needs())
        assertFalse(needs(token = null, agent = null))
        assertTrue(needs(record = null))
        assertTrue(needs(token = "tok2"))
        assertTrue(needs(server = "https://other"))
        assertTrue(needs(agent = "a2"))
        assertTrue(needs(build = 156))
        assertTrue(needs(at = now + PushRegistrationPolicy.REFRESH_INTERVAL_MS))
    }

    @Test
    fun `only missing endpoints count as unsupported`() {
        assertTrue(PushRegistrationPolicy.isUnsupported(404))
        assertTrue(PushRegistrationPolicy.isUnsupported(405))
        assertTrue(PushRegistrationPolicy.isUnsupported(501))
        assertFalse(PushRegistrationPolicy.isUnsupported(500))
        assertFalse(PushRegistrationPolicy.isUnsupported(401))
        assertFalse(PushRegistrationPolicy.isUnsupported(null))
    }
}
