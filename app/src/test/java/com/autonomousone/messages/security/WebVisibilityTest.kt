package com.autonomousone.messages.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Hide from web" must match the same contact written in any provider spelling, and must never
 * let a blank/alphanumeric sender accidentally match unrelated conversations.
 */
class WebVisibilityTest {

    @Test
    fun `any spelling of the same Iranian mobile is hidden`() {
        val hidden = WebVisibility.hideKeys("+98 912 123 4567")

        assertTrue(WebVisibility.isHidden(hidden, "09121234567"))
        assertTrue(WebVisibility.isHidden(hidden, "989121234567"))
        assertTrue(WebVisibility.isHidden(hidden, "00989121234567"))
        assertTrue(WebVisibility.isHidden(hidden, "+989121234567"))
    }

    @Test
    fun `a different number is not hidden`() {
        val hidden = WebVisibility.hideKeys("+989121234567")

        assertFalse(WebVisibility.isHidden(hidden, "09121234568"))
        assertFalse(WebVisibility.isHidden(hidden, ""))
    }

    @Test
    fun `alphanumeric senders are matched case-insensitively`() {
        val hidden = WebVisibility.hideKeys("ParsianBank")

        assertTrue(WebVisibility.isHidden(hidden, "parsianbank"))
        assertTrue(WebVisibility.isHidden(hidden, " PARSIANBANK "))
        assertFalse(WebVisibility.isHidden(hidden, "Google"))
    }

    @Test
    fun `blank addresses produce no hide key`() {
        assertTrue(WebVisibility.hideKeys("").isEmpty())
        assertTrue(WebVisibility.hideKeys("   ").isEmpty())
        assertFalse(WebVisibility.isConversationHidden(emptySet(), ""))
    }

    @Test
    fun `conversation ids match exactly`() {
        val hidden = setOf("conv-1")

        assertTrue(WebVisibility.isConversationHidden(hidden, "conv-1"))
        assertFalse(WebVisibility.isConversationHidden(hidden, "conv-2"))
    }
}
