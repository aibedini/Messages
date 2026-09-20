package com.autonomousone.messages.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scope a user-category membership is stored under (v3.5.0).
 *
 * This is the contract that makes "if I assign this number to VPN, it stays in VPN"
 * true even when the provider recreates the thread: a one-to-one conversation is keyed
 * by the stable PHONE identity, a group by its thread, and an alphanumeric sender by
 * its own exact id. Nothing here is fuzzy.
 */
class UserCategoryScopeTest {

    // ── One-to-one conversations use the phone identity, not the thread ─────

    @Test
    fun `every spelling of one iranian mobile resolves to the same address scope`() {
        val expected = UserCategoryScope.Address("+989121234567")

        listOf("09121234567", "+989121234567", "989121234567", "00989121234567", "0912 123 4567")
            .forEach { spelling ->
                assertEquals(
                    "scope for <$spelling>",
                    expected,
                    ConversationCategoryScopeResolver.resolve(1L, spelling)
                )
            }
    }

    @Test
    fun `a recreated thread keeps the same address scope`() {
        val first = ConversationCategoryScopeResolver.resolve(10L, "09121234567")
        // The provider deletes thread 10 and hands the same correspondence back as a
        // new thread with the international spelling.
        val recreated = ConversationCategoryScopeResolver.resolve(99L, "+989121234567")

        assertEquals(
            "the membership must survive thread recreation",
            first,
            recreated
        )
    }

    @Test
    fun `the raw and normalized address agree`() {
        assertEquals(
            ConversationCategoryScopeResolver.resolve(5L, "0912 123 4567", "+989121234567"),
            ConversationCategoryScopeResolver.resolve(5L, "0912 123 4567")
        )
    }

    // ── Short codes and foreign numbers keep their own identity ─────────────

    @Test
    fun `a short code is its own exact scope`() {
        assertEquals(
            UserCategoryScope.Address("112"),
            ConversationCategoryScopeResolver.resolve(7L, "112")
        )
        assertEquals(
            UserCategoryScope.Address("110"),
            ConversationCategoryScopeResolver.resolve(7L, "110")
        )
    }

    @Test
    fun `a short code never shares a scope with a number that contains it`() {
        val shortCode = ConversationCategoryScopeResolver.resolve(7L, "112")
        val contact = ConversationCategoryScopeResolver.resolve(8L, "09121234112")

        assertTrue(shortCode != contact)
    }

    @Test
    fun `a foreign number keeps its own identity`() {
        assertEquals(
            UserCategoryScope.Address("+14155552671"),
            ConversationCategoryScopeResolver.resolve(9L, "+14155552671")
        )
    }

    // ── Alphanumeric sender ids ─────────────────────────────────────────────

    @Test
    fun `an alphanumeric sender id is categorisable under its own exact key`() {
        assertEquals(
            UserCategoryScope.Address("sender:bank"),
            ConversationCategoryScopeResolver.resolve(3L, "BANK")
        )
    }

    @Test
    fun `sender id matching is case and whitespace insensitive but never fuzzy`() {
        val a = ConversationCategoryScopeResolver.resolve(3L, "Bank")
        val b = ConversationCategoryScopeResolver.resolve(3L, "  BANK  ")

        assertEquals(a, b)
        assertTrue(
            "an unrelated sender id must not share a scope",
            a != ConversationCategoryScopeResolver.resolve(3L, "BANK2")
        )
    }

    @Test
    fun `a sender id never collides with a phone scope`() {
        val sender = ConversationCategoryScopeResolver.resolve(3L, "BANK")
        val phone = ConversationCategoryScopeResolver.resolve(4L, "09121234567")

        assertTrue(sender != phone)
        assertTrue(sender.toString().startsWith("Address(key=sender:"))
    }

    @Test
    fun `digits inside a sender id do not become a phone scope`() {
        // "IR-MCI1" is not a phone number; it must not be treated as one.
        assertEquals(
            UserCategoryScope.Address("sender:ir-mci1"),
            ConversationCategoryScopeResolver.resolve(3L, "IR-MCI1")
        )
    }

    // ── Groups fall back to the thread ──────────────────────────────────────

    @Test
    fun `a multi-recipient address resolves to a thread scope`() {
        assertEquals(
            UserCategoryScope.Thread(500L),
            ConversationCategoryScopeResolver.resolve(500L, "+989120000001, +989120000002")
        )
        assertEquals(
            UserCategoryScope.Thread(501L),
            ConversationCategoryScopeResolver.resolve(501L, "+989120000001;+989120000002")
        )
    }

    @Test
    fun `two groups never share a scope just because they share a member`() {
        val groupA = ConversationCategoryScopeResolver.resolve(500L, "+989120000001, +989120000002")
        val groupB = ConversationCategoryScopeResolver.resolve(501L, "+989120000001, +989120000003")

        assertTrue("group membership must be thread-scoped", groupA != groupB)
    }

    @Test
    fun `a group is never keyed by one of its participants`() {
        val group = ConversationCategoryScopeResolver.resolve(500L, "+989120000001, +989120000002")

        assertTrue(group is UserCategoryScope.Thread)
        assertTrue(
            "one participant must not become the group's identity",
            group != ConversationCategoryScopeResolver.resolve(500L, "+989120000001")
        )
    }

    // ── Nothing stable to key on ────────────────────────────────────────────

    @Test
    fun `an unusable address with no thread yields no scope`() {
        assertNull(ConversationCategoryScopeResolver.resolve(0L, ""))
        assertNull(ConversationCategoryScopeResolver.resolve(0L, "   "))
        assertNull(ConversationCategoryScopeResolver.resolve(-1L, ""))
    }

    @Test
    fun `a group address on a zero thread yields no scope rather than a guess`() {
        assertNull(ConversationCategoryScopeResolver.resolve(0L, "a@b, c@d"))
    }

    @Test
    fun `an address with no digits and no usable text falls back to the thread`() {
        // "..." is neither a phone nor a usable sender id, so the thread carries it.
        assertEquals(
            UserCategoryScope.Thread(42L),
            ConversationCategoryScopeResolver.resolve(42L, "...")
        )
    }

    // ── The user's headline scenario, end to end ────────────────────────────

    @Test
    fun `assign by local spelling and read back by international spelling`() {
        val assignedScope = ConversationCategoryScopeResolver.resolve(10L, "09121234567")!!
        val laterThreadScope = ConversationCategoryScopeResolver.resolve(99L, "+989121234567")!!

        assertEquals(assignedScope, laterThreadScope)
    }
}
