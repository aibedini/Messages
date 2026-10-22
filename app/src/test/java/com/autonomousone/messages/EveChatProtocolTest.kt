package com.autonomousone.messages

import com.autonomousone.messages.evechat.ApiError
import com.autonomousone.messages.evechat.EveChatServerUrl
import com.autonomousone.messages.evechat.model.EveChatConversation
import com.autonomousone.messages.evechat.model.EveChatMessage
import com.autonomousone.messages.evechat.model.EveChatTime
import com.autonomousone.messages.evechat.network.EveChatEventParser
import com.autonomousone.messages.evechat.network.ReconnectBackoff
import com.autonomousone.messages.evechat.network.SocketEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class EveChatProtocolTest {

    @Test
    fun `conversation updated is a partial patch keyed by conversation_id`() {
        val e = EveChatEventParser.parse(
            """{"type":"conversation.updated","data":{"conversation_id":"c1","status":"active",
               "last_message_at":"2026-10-22T10:00:00Z","unread_agent":2,"sender_type":"visitor"},"ts":1}"""
        ) as SocketEvent.ConversationChanged
        assertEquals("c1", e.patch.conversationId)
        assertEquals("active", e.patch.status)
        assertEquals(2, e.patch.unreadAgent)
        assertEquals("visitor", e.patch.senderType)
        assertEquals(EveChatTime.parse("2026-10-22T10:00:00Z"), e.patch.lastMessageAt)
        assertNull(e.patch.assignedAgentId)
    }

    @Test
    fun `conversation new carries flattened conversation, visitor and first message`() {
        val e = EveChatEventParser.parse(
            """{"type":"conversation.new","data":{"id":"c9","visitor_id":"v1","status":"pending","channel":"web",
               "created_at":"2026-10-22T09:00:00Z","visitor":{"id":"v1","name":"Sara"},
               "message":{"id":"m1","conversation_id":"c9","sender_type":"visitor","content":"salam","created_at":"2026-10-22T09:00:01Z"}}}"""
        ) as SocketEvent.ConversationCreated
        assertEquals("c9", e.conversation.id)
        assertEquals("Sara", e.conversation.displayName)
        assertEquals("salam", e.firstMessage?.content)
    }

    @Test
    fun `agent assigned and resolved map to patches`() {
        val a = EveChatEventParser.parse("""{"type":"agent.assigned","data":{"conversation_id":"c1","agent_id":"a7","agent_name":"Ali"}}""")
            as SocketEvent.ConversationChanged
        assertEquals("a7", a.patch.assignedAgentId)
        assertEquals("Ali", a.patch.assignedAgentName)
        val r = EveChatEventParser.parse("""{"type":"conversation.resolved","data":{"conversation_id":"c1","by_agent_id":"a7"}}""")
            as SocketEvent.ConversationChanged
        assertEquals("resolved", r.patch.status)
    }

    @Test
    fun `agent message sent is an ack, not a message`() {
        val e = EveChatEventParser.parse("""{"type":"message.sent","data":{"id":"m5","client_id":"k1","created_at":1}}""")
        assertEquals(SocketEvent.MessageAccepted("m5", "k1"), e)
    }

    @Test
    fun `note added unwraps the message`() {
        val e = EveChatEventParser.parse(
            """{"type":"note.added","data":{"conversation_id":"c1","message":{"id":"n1","conversation_id":"c1","sender_type":"agent","is_internal":true,"content":"x"}}}"""
        ) as SocketEvent.NoteArrived
        assertTrue(e.message.isInternal)
        assertEquals("n1", e.message.id)
    }

    @Test
    fun `ping, typing and junk`() {
        assertEquals(SocketEvent.Ping, EveChatEventParser.parse("""{"type":"ping","ts":5}"""))
        assertEquals(SocketEvent.Typing("c1", true), EveChatEventParser.parse("""{"type":"visitor.typing","data":{"conversation_id":"c1"}}"""))
        assertEquals(SocketEvent.Typing("c1", false), EveChatEventParser.parse("""{"type":"visitor.typing.stop","data":{"conversation_id":"c1"}}"""))
        assertNull(EveChatEventParser.parse("not json"))
        assertNull(EveChatEventParser.parse("""{"type":"stats.tick","data":{}}"""))
        assertNull(EveChatEventParser.parse("""{"type":"conversation.updated","data":{"status":"active"}}"""))
    }

    @Test
    fun `json null never becomes the string null`() {
        val m = EveChatMessage.fromJson(JSONObject("""{"id":"m1","sender_type":"visitor","sender_name":null,"client_message_id":null,"content":"hi"}"""))
        assertNull(m.senderName)
        assertNull(m.clientMessageId)
        val c = EveChatConversation.fromJson(JSONObject("""{"id":"c1","visitor_id":"v","visitor":{"id":"v","name":null,"phone":"0912"}}"""))
        assertEquals("0912", c.displayName)
    }

    @Test
    fun `server times are UTC and parse in every layout the server emits`() {
        val expected = 1_792_663_200_000L
        assertEquals(expected, EveChatTime.parse("2026-10-22T10:00:00Z"))
        assertEquals(expected, EveChatTime.parse("2026-10-22T13:30:00+03:30"))
        assertEquals(expected, EveChatTime.parse("2026-10-22 10:00:00"))
        assertEquals(expected + 123, EveChatTime.parse("2026-10-22T10:00:00.123Z"))
        assertNull(EveChatTime.parse(""))
        assertNull(EveChatTime.parse(null))
    }

    @Test
    fun `backoff grows, is capped and jittered within half to full ceiling`() {
        val r = Random(42)
        assertEquals(0L, ReconnectBackoff.delayFor(0, r))
        repeat(50) {
            val d1 = ReconnectBackoff.delayFor(1, r)
            assertTrue(d1 in 500L..1_000L)
            val d10 = ReconnectBackoff.delayFor(10, r)
            assertTrue(d10 in 15_000L..30_000L)
        }
    }

    @Test
    fun `server url normalisation accepts what people type`() {
        assertEquals(EveChatServerUrl.Result.Ok("https://chat.example.com", false), EveChatServerUrl.normalize(" chat.example.com/ "))
        assertEquals(EveChatServerUrl.Result.Ok("https://chat.example.com", false), EveChatServerUrl.normalize("https://Chat.Example.com/api/v1/"))
        assertEquals(EveChatServerUrl.Result.Ok("http://10.0.0.2:8080/eve", true), EveChatServerUrl.normalize("http://10.0.0.2:8080/eve"))
        assertTrue(EveChatServerUrl.normalize("ftp://x.com") is EveChatServerUrl.Result.Invalid)
        assertTrue(EveChatServerUrl.normalize("") is EveChatServerUrl.Result.Invalid)
    }

    @Test
    fun `server error body is code plus message`() {
        assertEquals("این گفتگو قبلاً برداشته شده", ApiError.parseServerMessage("""{"code":"conflict","message":"این گفتگو قبلاً برداشته شده"}"""))
        assertEquals("bad", ApiError.parseServerMessage("""{"error":"bad"}"""))
        assertNull(ApiError.parseServerMessage("<html>502</html>"))
        assertTrue(ApiError.fromStatus(503, null).retryable)
        assertTrue(ApiError.fromStatus(429, null).retryable)
        assertFalse(ApiError.fromStatus(409, null).retryable)
        assertEquals(ApiError.Kind.UNAUTHORIZED, ApiError.fromStatus(401, null).kind)
    }
}
