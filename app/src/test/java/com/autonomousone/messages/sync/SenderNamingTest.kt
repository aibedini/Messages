package com.autonomousone.messages.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Producer-side rules for the versioned sender-naming contract. */
class SenderNamingTest {

    @Test
    fun `a contact name is CONTACT and never replaces the raw address`() {
        val naming = SenderNaming.resolve("+98 912 123 4567", "09121234567", "Ali")

        assertEquals("+98 912 123 4567", naming.address)
        assertEquals("Ali", naming.displayName)
        assertEquals(SenderNaming.SOURCE_CONTACT, naming.displayNameSource)
        assertEquals(SenderNaming.KIND_PHONE, naming.senderKind)
    }

    @Test
    fun `a subscriber number without a contact falls back to the address`() {
        val naming = SenderNaming.resolve("09121234567", "09121234567", null)

        assertEquals("09121234567", naming.displayName)
        assertEquals(SenderNaming.SOURCE_ADDRESS, naming.displayNameSource)
        assertEquals(SenderNaming.KIND_PHONE, naming.senderKind)
    }

    @Test
    fun `a bank or brand is ALPHANUMERIC and still gets a non-empty fallback`() {
        val naming = SenderNaming.resolve("PARSIANBANK", null, null)

        assertEquals("PARSIANBANK", naming.address)
        assertEquals("PARSIANBANK", naming.displayName)
        assertEquals(SenderNaming.SOURCE_ADDRESS, naming.displayNameSource)
        assertEquals(SenderNaming.KIND_ALPHANUMERIC, naming.senderKind)
    }

    @Test
    fun `a short code is SHORT_CODE and is rendered as the code when unnamed`() {
        val naming = SenderNaming.resolve("3000", null, null)

        assertEquals("3000", naming.displayName)
        assertEquals(SenderNaming.SOURCE_ADDRESS, naming.displayNameSource)
        assertEquals(SenderNaming.KIND_SHORT_CODE, naming.senderKind)
    }

    @Test
    fun `no address at all is the only UNKNOWN case and still has a label`() {
        val naming = SenderNaming.resolve("", null, null)

        assertEquals("", naming.address)
        assertEquals("Unknown", naming.displayName)
        assertEquals(SenderNaming.SOURCE_UNKNOWN, naming.displayNameSource)
        assertEquals(SenderNaming.KIND_UNKNOWN, naming.senderKind)
        assertFalse(naming.displayName.isBlank())
    }

    @Test
    fun `a blank contact name is not a human name`() {
        val naming = SenderNaming.resolve("PARSIANBANK", null, "   ")

        assertEquals(SenderNaming.SOURCE_ADDRESS, naming.displayNameSource)
        assertEquals("PARSIANBANK", naming.displayName)
    }

    @Test
    fun `the directory and user sources are reserved for consumers`() {
        assertTrue(SenderNaming.SOURCE_USER !in setOf(SenderNaming.SOURCE_CONTACT, SenderNaming.SOURCE_ADDRESS))
        assertTrue(SenderNaming.SOURCE_DIRECTORY !in setOf(SenderNaming.SOURCE_CONTACT, SenderNaming.SOURCE_ADDRESS))
    }
}
