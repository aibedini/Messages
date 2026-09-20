package com.autonomousone.messages.repository

import java.text.Normalizer
import java.util.Locale

/**
 * The outcome of validating a user-typed category name.
 *
 * The two valid fields are different things and must not be confused:
 *
 *  - [Valid.displayName] is what the user sees and what is STORED in
 *    `user_categories.name`. It is the readable form: NFKC-normalized, trimmed and
 *    whitespace-collapsed, but with its original casing and scripts intact.
 *  - [Valid.normalizedName] is the comparison form only. It is what the UNIQUE index
 *    on `user_categories.normalizedName` enforces, so `VPN`, `vpn` and ` Vpn ` are
 *    one category — while `مشتری‌ها` and `مشتری ها` remain whatever the user typed.
 *
 * Renaming never changes identity: the category is its UUID, and these strings are
 * presentation plus duplicate protection.
 */
sealed interface UserCategoryNameValidation {

    data class Valid(
        val displayName: String,
        val normalizedName: String
    ) : UserCategoryNameValidation

    /** Nothing but whitespace after normalization. */
    data object Empty : UserCategoryNameValidation

    /** More than [UserCategoryName.MAX_CODE_POINTS] Unicode code points. */
    data object TooLong : UserCategoryNameValidation
}

/**
 * The ONE name policy for user categories (v3.5.0 Phase 4).
 *
 * Pure and Android-free: no context, no database, no locale from the device. The
 * normalization is a fixed pipeline so the same text always produces the same stored
 * value and the same duplicate key on every device and in every test:
 *
 * ```text
 * NFKC → trim → collapse runs of Unicode whitespace into ONE ASCII space
 *      → displayName
 *      → lowercase(Locale.ROOT)
 *      → normalizedName
 * ```
 *
 * WHY NFKC AND NOT NFD/NFC: the point of this form is duplicate protection, and NFKC
 * is the compatibility form — it folds full-width `ＶＰＮ` onto `VPN` and the Arabic
 * presentation forms onto their base letters, which is exactly the class of "the user
 * typed the same word twice and did not notice" mistakes.
 *
 * WHY `Locale.ROOT`: a Turkish device must not fold `I` to `ı`. The storage key must
 * not depend on the handset's locale.
 *
 * WHY NOT `Regex("\\s+")`: Kotlin's `\s` is ASCII-only by default, so a pasted
 * non-breaking space (U+00A0), an ideographic space (U+3000) or a narrow no-break
 * space (U+202F) would survive INTO the stored name and into the comparison key, and
 * `"VPN\u00A0Clients"` would not collide with `"VPN Clients"`. Every code unit is
 * therefore classified with [Character.isWhitespace] **or** [Character.isSpaceChar]
 * — the two are different sets: U+00A0 and U+3000 are space chars but NOT whitespace,
 * while U+200B is neither. Surrogate pairs are preserved because only whitespace code
 * units are ever rewritten.
 */
object UserCategoryName {

    /**
     * Maximum length in Unicode CODE POINTS, not `String.length`.
     *
     * A single emoji is one code point but two UTF-16 chars, so a `String.length`
     * limit would silently give an emoji name half the room of a Latin one. The value
     * is never truncated: a longer name is rejected so the user can decide what to cut.
     */
    const val MAX_CODE_POINTS = 40

    fun validate(raw: String): UserCategoryNameValidation {
        val displayName = normalizeDisplay(raw)
        if (displayName.isEmpty()) return UserCategoryNameValidation.Empty
        if (displayName.codePointCount(0, displayName.length) > MAX_CODE_POINTS) {
            return UserCategoryNameValidation.TooLong
        }
        return UserCategoryNameValidation.Valid(
            displayName = displayName,
            normalizedName = normalizeKey(displayName)
        )
    }

    /**
     * The readable stored form: NFKC, then leading/trailing Unicode whitespace removed
     * and every interior run of Unicode whitespace replaced by exactly one ASCII space.
     */
    fun normalizeDisplay(raw: String): String {
        val nfkc = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        val out = StringBuilder(nfkc.length)
        var pendingSpace = false
        for (unit in nfkc) {
            if (isUnicodeWhitespace(unit)) {
                // A leading run produces nothing; interior and trailing runs collapse
                // into the single space that is emitted before the next real code unit.
                if (out.isNotEmpty()) pendingSpace = true
                continue
            }
            if (pendingSpace) {
                out.append(' ')
                pendingSpace = false
            }
            out.append(unit)
        }
        return out.toString()
    }

    /**
     * The duplicate-protection key. Always derived from [normalizeDisplay] output, so
     * the key and the stored name can never be normalized by two different rules.
     */
    fun normalizeKey(displayName: String): String = displayName.lowercase(Locale.ROOT)

    /** Convenience for callers that only need the comparison form. */
    fun keyOf(raw: String): String? =
        (validate(raw) as? UserCategoryNameValidation.Valid)?.normalizedName

    /**
     * True for every Unicode space the user could paste. The two predicates are
     * complementary, not redundant: `isWhitespace` excludes the no-break spaces and
     * `isSpaceChar` excludes the line separators.
     */
    fun isUnicodeWhitespace(unit: Char): Boolean =
        Character.isWhitespace(unit) || Character.isSpaceChar(unit)
}
