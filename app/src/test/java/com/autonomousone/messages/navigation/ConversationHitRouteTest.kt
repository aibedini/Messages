package com.autonomousone.messages.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.4.0 FEATURE 7 → FEATURE 1 bridge: the route the Starred browsers use to open
 * a conversation AND land on one exact message.
 *
 * WHY THIS IS ITS OWN ROUTE HELPER (and its own test)
 * ---------------------------------------------------
 * A starred row carries the composite identity `(source, providerId)`. The naive
 * shortcut — pass the numeric provider id — is WRONG in this codebase: Telephony
 * SMS `_id` 100 and MMS `_id` 100 are two different messages with independent
 * sequences, so a bare Long opens the wrong message roughly half the time. The
 * source is therefore part of the route, and these tests pin it.
 */
class ConversationHitRouteTest {

    @Test
    fun `hit route carries thread source and provider id`() {
        val route = Screen.Conversation.createHitRoute(
            threadId = 42L,
            source = "sms",
            providerId = 100L
        )
        assertTrue("route must target the conversation", route.startsWith("conversation/42?"))
        assertTrue(route.contains("hitSource=sms"))
        assertTrue(route.contains("hitProviderId=100"))
    }

    @Test
    fun `sms 100 and mms 100 produce different routes`() {
        val sms = Screen.Conversation.createHitRoute(42L, "sms", 100L)
        val mms = Screen.Conversation.createHitRoute(42L, "mms", 100L)

        assertNotEquals(
            "SMS 100 and MMS 100 are different messages and must not share a route",
            sms,
            mms
        )
        assertTrue(sms.contains("hitSource=sms"))
        assertTrue(mms.contains("hitSource=mms"))
    }

    @Test
    fun `hit route keeps the normal-open arguments present`() {
        // The conversation destination declares phone/name/forward/draft, so the
        // deep link must supply them (empty) rather than omit them: a missing
        // argument in a typed NavHost route is a crash, not a default.
        val route = Screen.Conversation.createHitRoute(7L, "mms", 3L)
        listOf("phone=", "name=", "forward=", "draft=").forEach { arg ->
            assertTrue("missing $arg in $route", route.contains(arg))
        }
    }

    @Test
    fun `normal conversation route still omits hit arguments`() {
        // Backward compatibility: every existing caller (notifications, Home,
        // external compose, drafts) uses createRoute/createNewRoute and must keep
        // working unchanged — the hit args are optional with defaults in the graph.
        val normal = Screen.Conversation.createRoute(threadId = 5L, phone = "+989120000000", name = "x")
        assertTrue(!normal.contains("hitSource="))
        assertTrue(!normal.contains("hitProviderId="))
    }

    @Test
    fun `hit source is percent-encoded like every other route argument`() {
        // Defensive: the source is a fixed vocabulary ("sms"/"mms") today, but the
        // route must never allow a raw '&'/'=' from a caller to split query args.
        val hostile = "sms&x=1"
        val route = Screen.Conversation.createHitRoute(1L, hostile, 9L)

        assertTrue("raw & leaked into route", !route.contains("&x=1"))
        val value = route.substringAfter("hitSource=").substringBefore("&")
        assertEquals(
            hostile,
            java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8.name())
        )
    }

    @Test
    fun `route pattern declares both hit arguments`() {
        // The graph and the pattern must agree, or the args silently never arrive.
        val pattern = Screen.Conversation.route
        assertTrue(
            "pattern must declare hitSource as a query argument: $pattern",
            pattern.contains("hitSource={hitSource}")
        )
        assertTrue(
            "pattern must declare hitProviderId as a query argument: $pattern",
            pattern.contains("hitProviderId={hitProviderId}")
        )
    }
}
