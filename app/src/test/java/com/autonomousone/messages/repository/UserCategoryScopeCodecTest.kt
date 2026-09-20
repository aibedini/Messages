package com.autonomousone.messages.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The persistence boundary of a category scope (v3.5.0 Phase 3).
 *
 * A scope is in-memory until it is written, so this codec decides what durable category
 * membership actually means. Two properties matter and are pinned here:
 *
 *  1. the round-trip is exact, including the alphanumeric sender key that must never be
 *     confused with a phone number;
 *  2. an unknown or malformed stored value decodes to NULL instead of being
 *     reinterpreted — a row written by a different build must be ignored, never read as
 *     some other scope.
 */
class UserCategoryScopeCodecTest {

    // ── Round trip ──────────────────────────────────────────────────────────

    @Test
    fun `a canonical phone address round-trips`() {
        val scope = UserCategoryScope.Address("+989121234567")

        val stored = UserCategoryScopeCodec.encode(scope)

        assertEquals(UserCategoryScopeCodec.ADDRESS, stored.scopeType)
        assertEquals("+989121234567", stored.scopeKey)
        assertEquals(scope, UserCategoryScopeCodec.decode(stored.scopeType, stored.scopeKey))
    }

    @Test
    fun `an alphanumeric sender address round-trips without looking like a phone`() {
        val scope = UserCategoryScope.Address("sender:bank")

        val stored = UserCategoryScopeCodec.encode(scope)

        assertEquals(UserCategoryScopeCodec.ADDRESS, stored.scopeType)
        assertEquals("sender:bank", stored.scopeKey)
        assertEquals(scope, UserCategoryScopeCodec.decode(stored.scopeType, stored.scopeKey))
        assertTrue("the sender key must survive verbatim", stored.scopeKey.startsWith("sender:"))
    }

    @Test
    fun `a thread scope round-trips as a decimal id`() {
        val scope = UserCategoryScope.Thread(500L)

        val stored = UserCategoryScopeCodec.encode(scope)

        assertEquals(UserCategoryScopeCodec.THREAD, stored.scopeType)
        assertEquals("500", stored.scopeKey)
        assertEquals(scope, UserCategoryScopeCodec.decode(stored.scopeType, stored.scopeKey))
    }

    @Test
    fun `a scope never stores a thread id`() {
        // The whole point of ADDRESS scoping: no thread identity is involved, so a
        // recreated thread cannot orphan the membership.
        val stored = UserCategoryScopeCodec.encode(UserCategoryScope.Address("+989121234567"))

        assertFalse(stored.scopeKey.contains("500"))
        assertEquals("+989121234567", stored.scopeKey)
    }

    // ── Rejection: never reinterpret stored data ────────────────────────────

    @Test
    fun `an unknown scope type decodes to null`() {
        assertNull(UserCategoryScopeCodec.decode("CONTACT", "+989121234567"))
        assertNull(UserCategoryScopeCodec.decode("address", "+989121234567"))
        assertNull(UserCategoryScopeCodec.decode("", "+989121234567"))
        assertNull(UserCategoryScopeCodec.decode("THREAD_ID", "500"))
    }

    @Test
    fun `a blank address key decodes to null`() {
        assertNull(UserCategoryScopeCodec.decode(UserCategoryScopeCodec.ADDRESS, ""))
        assertNull(UserCategoryScopeCodec.decode(UserCategoryScopeCodec.ADDRESS, "   "))
    }

    @Test
    fun `a thread key must be a positive decimal id`() {
        assertNull(UserCategoryScopeCodec.decode(UserCategoryScopeCodec.THREAD, "0"))
        assertNull(UserCategoryScopeCodec.decode(UserCategoryScopeCodec.THREAD, "-5"))
        assertNull(UserCategoryScopeCodec.decode(UserCategoryScopeCodec.THREAD, "abc"))
        assertNull(UserCategoryScopeCodec.decode(UserCategoryScopeCodec.THREAD, ""))
        assertNull(UserCategoryScopeCodec.decode(UserCategoryScopeCodec.THREAD, "5.0"))
        assertNull(UserCategoryScopeCodec.decode(UserCategoryScopeCodec.THREAD, "500 "))
    }

    @Test
    fun `a valid thread key decodes`() {
        assertEquals(
            UserCategoryScope.Thread(1L),
            UserCategoryScopeCodec.decode(UserCategoryScopeCodec.THREAD, "1")
        )
        assertEquals(
            UserCategoryScope.Thread(Long.MAX_VALUE),
            UserCategoryScopeCodec.decode(UserCategoryScopeCodec.THREAD, Long.MAX_VALUE.toString())
        )
    }

    @Test
    fun `known scope types are exactly address and thread`() {
        assertEquals(setOf("ADDRESS", "THREAD"), UserCategoryScopeCodec.SCOPE_TYPES)
        assertTrue(UserCategoryScopeCodec.isKnownScopeType(UserCategoryScopeCodec.ADDRESS))
        assertTrue(UserCategoryScopeCodec.isKnownScopeType(UserCategoryScopeCodec.THREAD))
        assertTrue(!UserCategoryScopeCodec.isKnownScopeType("SENDER"))
    }

    // ── Storage vocabulary is not a Kotlin class name ───────────────────────

    @Test
    fun `the persisted vocabulary is stable text, not a class name`() {
        // Renaming or moving `UserCategoryScope` must never invalidate stored rows.
        val types = UserCategoryScopeCodec.SCOPE_TYPES
        assertTrue(types.none { it.contains(".") })
        assertTrue(types.none { it.contains("UserCategoryScope") })
        assertTrue(types.all { it == it.uppercase() })
    }
}
