package com.autonomousone.messages

import com.autonomousone.messages.repository.ContactRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Routing predicate used by IncomingMessageDispatcher to decide whether an
 * incoming message belongs to the conversation the user is currently viewing.
 */
class SameConversationRoutingTest {

    private fun same(a: String, b: String) = ContactRepository.sameConversation(a, b)

    @Test
    fun `identical numbers match`() {
        assertTrue(same("+989121234567", "+989121234567"))
    }

    @Test
    fun `formatted number matches raw digits`() {
        // Users paste/display numbers with spaces — normalization strips them.
        assertTrue(same("+98 912 123 4567", "+989121234567"))
        assertTrue(same("(021) 8877-6655", "02188776655"))
    }

    @Test
    fun `national form matches international form by suffix`() {
        assertTrue(same("9121234567", "+989121234567"))
    }

    @Test
    fun `blank side never matches`() {
        assertFalse(same("", "+989121234567"))
        assertFalse(same("+989121234567", ""))
    }

    @Test
    fun `different numbers never match`() {
        assertFalse(same("+989121234567", "+989351112222"))
    }

    @Test
    fun `short number fragments never suffix-match`() {
        // A short code like "12" must not claim every conversation ending in 12
        assertFalse(same("12", "+989121234512"))
        assertFalse(same("4321", "+989121234567"))
    }

    @Test
    fun `full short codes can still be equal`() {
        // Bank/OTP short codes compare by exact equality regardless of length.
        assertTrue(same("50001", "50001"))
    }
}
