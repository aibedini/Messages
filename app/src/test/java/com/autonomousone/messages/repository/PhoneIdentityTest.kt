package com.autonomousone.messages.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ONE phone-identity policy (v3.4.1).
 *
 * This is the regression guard for the Home contact-name bug: Home resolved names with
 * an EXACT lookup on `normalizePhone(address)`, so a conversation addressed as
 * `09121234567` never matched a contact stored as `+989121234567`, while the chat
 * header (using Android PhoneLookup) did. The fix is a closed alias set, and these
 * tests pin both halves of it: the spellings that MUST collapse together, and the
 * ones that must NEVER be expanded.
 */
class PhoneIdentityTest {

    // ── Formatting ──────────────────────────────────────────────────────────

    @Test
    fun `formatting is stripped to a single leading plus`() {
        assertEquals("09121234567", PhoneIdentity.normalize("0912 123 4567"))
        assertEquals("09121234567", PhoneIdentity.normalize("(0912) 123-4567"))
        assertEquals("+989121234567", PhoneIdentity.normalize("+98 912 123 4567"))
        assertEquals("+989121234567", PhoneIdentity.normalize("  +98-912-123-4567 "))
        assertEquals("", PhoneIdentity.normalize("   "))
        assertEquals("", PhoneIdentity.normalize("no digits here"))
    }

    @Test
    fun `an interior plus can never create two plus signs`() {
        assertEquals("+989121234567", PhoneIdentity.normalize("+98+912+123+4567"))
    }

    // ── The Iranian mobile equivalence (the actual bug) ─────────────────────

    @Test
    fun `every spelling of one iranian mobile shares a key set`() {
        val spellings = listOf(
            "09121234567",
            "+989121234567",
            "00989121234567",
            "989121234567",
            "0912 123 4567",
            "+98 912 123 4567"
        )
        val expected = PhoneIdentity.lookupKeys("09121234567")
        spellings.forEach { spelling ->
            assertEquals(
                "lookup keys for <$spelling> must equal the canonical set",
                expected,
                PhoneIdentity.lookupKeys(spelling)
            )
        }
        assertTrue(expected.contains("09121234567"))
        assertTrue(expected.contains("+989121234567"))
        assertTrue(expected.contains("989121234567"))
        assertTrue(expected.contains("00989121234567"))
    }

    @Test
    fun `matching is symmetric`() {
        assertTrue(PhoneIdentity.matches("09121234567", "+989121234567"))
        assertTrue(PhoneIdentity.matches("+989121234567", "09121234567"))
        assertTrue(PhoneIdentity.matches("0912 123 4567", "00989121234567"))
    }

    // ── The dangerous case: short codes must NOT be expanded ────────────────

    @Test
    fun `service and short codes are never expanded into a mobile`() {
        listOf("112", "110", "115", "125", "100", "911", "12345").forEach { code ->
            val keys = PhoneIdentity.lookupKeys(code)
            assertEquals("a short code must stay itself: $code", setOf(code), keys)
        }
    }

    @Test
    fun `a short code never matches an unrelated contact number`() {
        // The old fuzzy suffix rule in `sameConversation` would happily join these.
        assertFalse(PhoneIdentity.matches("112", "09121234112"))
        assertFalse(PhoneIdentity.matches("112", "+989121234112"))
        assertFalse(PhoneIdentity.matches("110", "09121110000"))
    }

    @Test
    fun `an alphanumeric sender id resolves to nothing`() {
        assertEquals(emptySet<String>(), PhoneIdentity.lookupKeys("AD-BANK"))
        assertEquals(emptySet<String>(), PhoneIdentity.lookupKeys(""))
        assertFalse(PhoneIdentity.matches("AD-BANK", "AD-BANK"))
    }

    // ── Foreign and non-mobile numbers keep their own identity ──────────────

    @Test
    fun `a foreign number is not rewritten into an iranian one`() {
        val keys = PhoneIdentity.lookupKeys("+14155552671")
        assertEquals(setOf("+14155552671"), keys)
    }

    @Test
    fun `an iranian landline is left alone`() {
        // 021… is a Tehran landline, not a mobile: no `9` after the trunk prefix.
        val keys = PhoneIdentity.lookupKeys("02112345678")
        assertEquals(setOf("02112345678"), keys)
    }

    @Test
    fun `a number that is only accidentally mobile-shaped stays untouched`() {
        // 10 digits starting with 9 but no country code and no trunk 0: ambiguous, so
        // the policy does not invent a country code for it.
        val keys = PhoneIdentity.lookupKeys("9121234567")
        assertEquals(setOf("9121234567"), keys)
    }

    // ── The resolver that Home uses ─────────────────────────────────────────

    @Test
    fun `the resolver finds a contact stored under any equivalent spelling`() {
        // A contact stored the international way...
        val storedInternational = mapOf("+989121234567" to "Ali")
        assertEquals("Ali", ContactRepository.displayNameFrom(storedInternational, "09121234567"))
        assertEquals("Ali", ContactRepository.displayNameFrom(storedInternational, "00989121234567"))
        // ...and one stored the local way.
        val storedLocal = mapOf("09121234567" to "Ali")
        assertEquals("Ali", ContactRepository.displayNameFrom(storedLocal, "+989121234567"))
        assertEquals("Ali", ContactRepository.displayNameFrom(storedLocal, "0912 123 4567"))
    }

    @Test
    fun `an unknown sender keeps the number`() {
        val directory = mapOf("+989121234567" to "Ali")
        assertEquals(
            "+989000000000",
            ContactRepository.displayNameFrom(directory, "+989000000000")
        )
    }

    @Test
    fun `the resolver never returns blank for a non-blank address`() {
        assertEquals("112", ContactRepository.displayNameFrom(emptyMap(), "112"))
        assertEquals("", ContactRepository.displayNameFrom(emptyMap(), ""))
    }

    @Test
    fun `a short code is not resolved by a contact number that contains it`() {
        val directory = mapOf("09121234112" to "Someone")
        assertEquals("112", ContactRepository.displayNameFrom(directory, "112"))
    }
}
