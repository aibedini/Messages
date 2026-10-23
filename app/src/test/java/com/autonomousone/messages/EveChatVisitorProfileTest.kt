package com.autonomousone.messages

import com.autonomousone.messages.evechat.model.EveChatConversation
import com.autonomousone.messages.evechat.model.EveChatSubscription
import com.autonomousone.messages.evechat.model.EveChatTime
import com.autonomousone.messages.evechat.model.EveChatVisitor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The support sheet renders whatever `visitor` the EveChat server attaches to a conversation.
 * These tests pin the mapping the dashboard relies on — a field silently dropped here is a field
 * an agent cannot see while talking to a customer.
 */
class EveChatVisitorProfileTest {

    private val conversationJson = JSONObject(
        """
        {
          "id": "c_123",
          "visitor_id": "v_456",
          "channel": "web",
          "page_url": "https://eve.rootteam.ir/s/9/735x7cci7o5p1qgc",
          "page_title": "خرید اشتراک",
          "referrer": "https://google.com/",
          "status": "active"
        }
        """
    )

    private val visitorJson = JSONObject(
        """
        {
          "id": "v_456",
          "name": "Sara",
          "email": "sara@example.com",
          "phone": "09121234567",
          "eve_mobile": "09121234567",
          "eve_user_id": "u_9001",
          "eve_reseller_id": "r_7",
          "device_type": "desktop",
          "os": "Windows",
          "browser": "Chrome",
          "user_agent": "Mozilla/5.0 …",
          "language": "fa",
          "ip": "2.190.187.77",
          "country": "IR",
          "is_banned": false,
          "first_seen_at": "2026-10-20T08:00:00Z",
          "last_seen_at": "2026-10-23T06:14:00Z",
          "eve_subscription_info": {
            "plan": "S17-Azin",
            "expiry": "2026-11-22",
            "days_left": 30,
            "auto_renew": false
          },
          "metadata": { "utm_source": "telegram", "tier": 2 }
        }
        """
    )

    @Test
    fun `every profile field the dashboard shows is mapped`() {
        val v = EveChatVisitor.fromJson(visitorJson, conversationJson)
        assertEquals("v_456", v.id)
        assertEquals("Sara", v.name)
        assertEquals("09121234567", v.mobile)
        assertEquals("u_9001", v.eveUserId)
        assertEquals("r_7", v.eveResellerId)
        assertEquals("desktop", v.deviceType)
        assertEquals("Windows", v.os)
        assertEquals("Chrome", v.browser)
        assertEquals("fa", v.language)
        assertEquals("2.190.187.77", v.ip)
        assertEquals("IR", v.country)
        assertEquals(EveChatTime.parse("2026-10-20T08:00:00Z"), v.firstSeenAt)
        // Page context lives on the conversation, not the visitor object.
        assertEquals("https://eve.rootteam.ir/s/9/735x7cci7o5p1qgc", v.pageUrl)
        assertEquals("خرید اشتراک", v.pageTitle)
        assertEquals("https://google.com/", v.referrer)
        assertEquals("web", v.channel)
        assertTrue(v.hasAnything)
    }

    @Test
    fun `subscription keeps unknown keys instead of dropping them`() {
        val sub = EveChatVisitor.fromJson(visitorJson, conversationJson).subscription!!
        assertEquals("S17-Azin", sub.plan)
        assertEquals("2026-11-22", sub.expiry)
        assertEquals(EveChatTime.parse("2026-11-22"), sub.expiryAt)
        assertEquals(30, sub.serverDaysLeft)
        assertEquals(listOf("auto_renew" to "false"), sub.extra)
        assertFalse(sub.isExpired)
    }

    @Test
    fun `subscription accepts the alias spellings the widget integrations send`() {
        val sub = EveChatSubscription.fromJson(
            JSONObject("""{"subscription_plan":"Pro","subscription_expiry":"2026-12-01T10:00:00Z"}""")
        )!!
        assertEquals("Pro", sub.plan)
        assertEquals(EveChatTime.parse("2026-12-01T10:00:00Z"), sub.expiryAt)
    }

    @Test
    fun `epoch seconds and placeholder plans are normalised`() {
        val seconds = EveChatSubscription.fromJson(JSONObject("""{"expiry":1792627200}"""))!!
        assertEquals(1_792_627_200_000L, seconds.expiryAt)

        // "none" is how some integrations say "no subscription"; it must not render as a plan.
        assertNull(EveChatSubscription.fromJson(JSONObject("""{"plan":"none"}""")))
        assertNull(EveChatSubscription.fromJson(JSONObject("{}")))
    }

    @Test
    fun `days left is derived when the server only sends a date`() {
        val sub = EveChatSubscription(plan = "Pro", expiry = "2026-11-22", expiryAt = 1_795_276_800_000L)
        assertEquals(2, sub.daysLeft(now = 1_795_276_800_000L - 2 * 86_400_000L))
        assertTrue(sub.copy(expiryAt = 1_700_000_000_000L).isExpired)
    }

    @Test
    fun `metadata is sorted by key and renders scalars as text`() {
        val v = EveChatVisitor.fromJson(visitorJson, conversationJson)
        // Sorted by key, so rows never jump between fetches.
        assertEquals(listOf("tier", "utm_source"), v.metadata.map { it.first })
        assertEquals("2", v.metadata[0].second)
        assertEquals("telegram", v.metadata[1].second)
    }

    @Test
    fun `anonymous visitor has no baked-in label`() {
        // The old model returned the Persian word "بازدیدکننده" here, which leaked into English builds.
        val c = EveChatConversation.fromJson(JSONObject("""{"id":"c1","visitor_id":"v1","visitor":{"id":"v1"}}"""))
        assertEquals("", c.displayName)
        val v = EveChatVisitor.fromJson(JSONObject("""{"id":"v1"}"""))
        assertEquals("", v.displayName)
        assertFalse(v.hasAnything)
    }

    @Test
    fun `json nulls never become the string null`() {
        val v = EveChatVisitor.fromJson(
            JSONObject("""{"id":"v1","name":null,"phone":null,"device_type":null,"eve_subscription_info":null}""")
        )
        assertNull(v.name)
        assertNull(v.phone)
        assertNull(v.deviceType)
        assertNull(v.subscription)
    }
}
