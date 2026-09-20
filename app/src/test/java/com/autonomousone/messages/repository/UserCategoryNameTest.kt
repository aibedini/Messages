package com.autonomousone.messages.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.5.0 Phase 4 — the user-category NAME policy.
 *
 * This policy is durable contract, not formatting: [UserCategoryNameValidation.Valid.normalizedName]
 * is what the UNIQUE index on `user_categories.normalizedName` stores, so two names that
 * this function folds together are one category forever, and two names it keeps apart can
 * never collide. Both directions are therefore pinned here — including the ones that are
 * easy to get wrong (Unicode whitespace, NFKC, and code points versus `String.length`).
 */
class UserCategoryNameTest {

    private fun valid(raw: String): UserCategoryNameValidation.Valid {
        val result = UserCategoryName.validate(raw)
        assertTrue(
            "expected a valid name for <$raw> but got $result",
            result is UserCategoryNameValidation.Valid
        )
        return result as UserCategoryNameValidation.Valid
    }

    // ── Rejection ───────────────────────────────────────────────────────────

    @Test
    fun `blankRejected`() {
        assertEquals(UserCategoryNameValidation.Empty, UserCategoryName.validate(""))
        assertEquals(UserCategoryNameValidation.Empty, UserCategoryName.validate("   "))
        assertEquals(UserCategoryNameValidation.Empty, UserCategoryName.validate("\t\n "))
        // A non-breaking space is whitespace for this policy, so it is empty too.
        assertEquals(UserCategoryNameValidation.Empty, UserCategoryName.validate("\u00A0\u3000"))
    }

    // ── Trimming and whitespace ─────────────────────────────────────────────

    @Test
    fun `asciiTrimmed`() {
        assertEquals("VPN", valid(" VPN ").displayName)
        assertEquals("VPN", valid("VPN").displayName)
    }

    @Test
    fun `unicodeWhitespaceCollapsed`() {
        // Interior runs of every whitespace class collapse to ONE ASCII space …
        assertEquals("VPN Clients", valid("VPN   Clients").displayName)
        assertEquals("VPN Clients", valid("VPN\u00A0Clients").displayName)      // NBSP
        assertEquals("VPN Clients", valid("VPN\u3000Clients").displayName)      // ideographic
        assertEquals("VPN Clients", valid("VPN\u202FClients").displayName)      // narrow NBSP
        assertEquals("VPN Clients", valid("VPN\t\r\nClients").displayName)
        // … and leading/trailing runs disappear entirely.
        assertEquals("VPN Clients", valid("\u00A0 VPN \u3000 Clients \u00A0").displayName)
        // No stray non-ASCII space can survive into the stored name or the key.
        val collapsed = valid("VPN\u00A0Clients")
        assertTrue(
            "the stored form must contain only ASCII spaces",
            collapsed.displayName.all { it == ' ' || !UserCategoryName.isUnicodeWhitespace(it) }
        )
        assertEquals("vpn clients", collapsed.normalizedName)
    }

    // ── Unicode form ────────────────────────────────────────────────────────

    @Test
    fun `nfkcApplied`() {
        // Full-width Latin folds onto ASCII: U+FF36/U+FF30/U+FF2E are ＶＰＮ.
        assertEquals("VPN", valid("\uFF36\uFF30\uFF2E").displayName)
        // A compatibility ligature folds onto its letters (U+FB00 is "ff").
        assertEquals("ffice", valid("\uFB00ice").displayName)
        // The Arabic presentation form of ALEF folds onto the base letter, so a name
        // typed with a presentation form is the SAME category as the plain one.
        assertEquals("\u0627", valid("\uFE8D").displayName)
        assertEquals(valid("\u0627").normalizedName, valid("\uFE8D").normalizedName)
    }

    @Test
    fun `displayNameReadable`() {
        // The display form keeps the user's own casing and script.
        assertEquals("VPN Clients", valid("  VPN   Clients ").displayName)
        assertEquals("\u0645\u0634\u062A\u0631\u06CC\u200C\u0647\u0627", valid(" \u0645\u0634\u062A\u0631\u06CC\u200C\u0647\u0627 ").displayName)
        assertEquals("\u2B50 \u0645\u0647\u0645", valid("\u2B50   \u0645\u0647\u0645").displayName)
        // … while the comparison key is case-folded.
        assertEquals("vpn clients", valid("  VPN   Clients ").normalizedName)
    }

    // ── Case folding ────────────────────────────────────────────────────────

    @Test
    fun `latinCaseInsensitiveKey`() {
        val keys = listOf("VPN", "vpn", "Vpn", " vPn ").map { valid(it).normalizedName }
        assertEquals(1, keys.distinct().size)
        assertEquals("vpn", keys.first())
    }

    // ── Scripts and emoji ───────────────────────────────────────────────────

    @Test
    fun `persianAccepted`() {
        val name = valid("\u0645\u0634\u062A\u0631\u06CC\u200C\u0647\u0627")
        assertEquals("\u0645\u0634\u062A\u0631\u06CC\u200C\u0647\u0627", name.displayName)
        assertEquals("\u0645\u0634\u062A\u0631\u06CC\u200C\u0647\u0627", name.normalizedName)

        // Two genuinely different Persian names stay different categories: the
        // zero-width non-joiner is NOT whitespace, and the space is.
        assertNotEquals(
            valid("\u0645\u0634\u062A\u0631\u06CC\u200C\u0647\u0627").normalizedName,
            valid("\u0645\u0634\u062A\u0631\u06CC \u0647\u0627").normalizedName
        )
    }

    @Test
    fun `emojiAccepted`() {
        val name = valid("\u2B50 \u0645\u0647\u0645")
        assertEquals("\u2B50 \u0645\u0647\u0645", name.displayName)
        assertEquals("\u2B50 \u0645\u0647\u0645", name.normalizedName)
    }

    @Test
    fun `emojiOnlyAccepted`() {
        val name = valid("\u2B50")
        assertEquals("\u2B50", name.displayName)
        assertEquals("\u2B50", name.normalizedName)
    }

    // ── Length is CODE POINTS, never String.length ──────────────────────────

    @Test
    fun `40CodePointsAccepted`() {
        assertEquals(40, "a".repeat(40).codePointCount(0, 40))
        assertTrue(UserCategoryName.validate("a".repeat(40)) is UserCategoryNameValidation.Valid)

        // 40 astral emoji: 40 code points but 80 UTF-16 chars.
        val emoji = "\uD83D\uDE00".repeat(40)
        assertEquals(80, emoji.length)
        assertEquals(40, emoji.codePointCount(0, emoji.length))
        val validEmoji = valid(emoji)
        assertEquals(40, validEmoji.displayName.codePointCount(0, validEmoji.displayName.length))
    }

    @Test
    fun `41Rejected`() {
        assertEquals(UserCategoryNameValidation.TooLong, UserCategoryName.validate("a".repeat(41)))
        // 41 astral emoji are 41 code points even though String.length says 82.
        assertEquals(
            UserCategoryNameValidation.TooLong,
            UserCategoryName.validate("\uD83D\uDE00".repeat(41))
        )
        // The limit is not silently applied as a truncation: nothing is returned at all.
        assertEquals(
            UserCategoryNameValidation.TooLong,
            UserCategoryName.validate("a".repeat(400))
        )
    }

    @Test
    fun `surrogateEmojiCountsOne`() {
        val single = "\uD83D\uDE00"
        assertEquals(2, single.length)
        assertEquals(1, single.codePointCount(0, single.length))

        val name = valid(single)
        assertEquals(1, name.displayName.codePointCount(0, name.displayName.length))
        assertEquals(single, name.displayName)

        // A 39-char ASCII name plus that emoji is exactly 40 code points: accepted.
        assertTrue(
            UserCategoryName.validate("a".repeat(39) + single) is UserCategoryNameValidation.Valid
        )
        // … and 40 ASCII plus the emoji is 41: rejected, even though String.length is 42.
        assertEquals(
            UserCategoryNameValidation.TooLong,
            UserCategoryName.validate("a".repeat(40) + single)
        )
    }

    // ── Whitespace classification contract ──────────────────────────────────

    @Test
    fun `whitespaceClassificationUsesBothUnicodePredicates`() {
        // The two predicates are complementary; a policy that used only `Regex("\\s+")`
        // would keep every one of these in the stored name.
        assertTrue(UserCategoryName.isUnicodeWhitespace(' '))
        assertTrue(UserCategoryName.isUnicodeWhitespace('\u00A0'))   // space char only
        assertTrue(UserCategoryName.isUnicodeWhitespace('\u3000'))   // space char only
        assertTrue(UserCategoryName.isUnicodeWhitespace('\t'))       // whitespace only
        assertTrue(UserCategoryName.isUnicodeWhitespace('\u2028'))   // whitespace only

        // A zero-width non-joiner is real content in Persian, not whitespace.
        assertFalse(UserCategoryName.isUnicodeWhitespace('\u200C'))
        assertFalse(UserCategoryName.isUnicodeWhitespace('a'))
    }
}
