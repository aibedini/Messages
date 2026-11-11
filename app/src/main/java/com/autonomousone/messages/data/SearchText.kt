package com.autonomousone.messages.data

import java.util.Locale

/**
 * The ONE folding the search applies to what the user types.
 *
 * Persian users type the same word in several legitimate ways — Arabic vs Persian letter forms
 * (`ي`/`ی`, `ك`/`ک`), with or without diacritics, with or without ZWNJ, Latin vs Persian digits —
 * and every one of them is "the same query" to a person. The FTS index stores the raw message body,
 * so the query side has to meet it halfway: fold what was typed, then ask for every plausible
 * spelling of it ([spellings]) instead of just the literal one.
 *
 * The fold is deliberately LOSSY and one-way (it never changes stored text), so it cannot alter what
 * a message says; it only widens what counts as a match.
 */
object SearchText {

    /** Folds one query string into canonical, whitespace-separated tokens. */
    fun fold(raw: String): String {
        val out = StringBuilder(raw.length)
        var lastWasSpace = true
        for (ch in raw) {
            val mapped = mapChar(ch)
            when {
                mapped == null -> Unit // dropped: a combining mark
                mapped == ' ' -> {
                    if (!lastWasSpace) {
                        out.append(' ')
                        lastWasSpace = true
                    }
                }
                else -> {
                    out.append(mapped)
                    lastWasSpace = false
                }
            }
        }
        return out.toString().trim()
    }

    /** The canonical tokens of a raw query. Empty for punctuation-only input. */
    fun tokens(raw: String): List<String> =
        fold(raw).split(' ').filter { it.isNotEmpty() }.take(MAX_TOKENS)

    /**
     * The spellings a person could have meant for one canonical token, most-literal first.
     *
     * Bounded on purpose: a token with many variant characters would otherwise explode
     * combinatorially, so the expansion stops at [MAX_SPELLINGS] and the canonical form is always
     * first (it is the one the FTS index most often holds).
     */
    fun spellings(token: String, max: Int = MAX_SPELLINGS): List<String> {
        if (token.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>(max)
        fun add(value: String) {
            if (out.size < max) out += value
        }
        // 1. What was typed, folded. Always first: it is what the index holds in the common case.
        add(token)
        // 2. The same number in the other digit scripts.
        if (token.any { it in '0'..'9' }) {
            add(digitsIn(token, '۰'))
            add(digitsIn(token, '٠'))
        }
        // 3. The word joined with a ZWNJ, and the Arabic letter forms. Persian compounds are
        //    written both ways (`بی‌فاصله` / `بیفاصله`), and the index keeps the ZWNJ inside the
        //    token, so a query without it can only reach the message through one of these.
        if (token.any { it in '\u0600'..'\u06FF' }) {
            zwnjVariants(token, ZWNJ_VARIANTS).forEach(::add)
            arabicForm(token)?.let(::add)
            alefForms(token).forEach(::add)
        }
        return out.toList()
    }

    /** Every ASCII digit replaced by the same digit in [base]'s script. */
    private fun digitsIn(form: String, base: Char): String = buildString(form.length) {
        for (ch in form) append(if (ch in '0'..'9') base + (ch - '0') else ch)
    }

    /** The word with ONE ZWNJ inserted at each inner position, bounded by [limit]. */
    private fun zwnjVariants(form: String, limit: Int): List<String> {
        if (limit <= 0 || form.length < 3) return emptyList()
        val out = ArrayList<String>(minOf(limit, form.length - 1))
        for (position in 1 until form.length) {
            if (out.size >= limit) break
            out += form.substring(0, position) + '\u200C' + form.substring(position)
        }
        return out
    }

    /** The same word with the Arabic counterparts of the Persian letters that have one. */
    private fun arabicForm(form: String): String? {
        val mapped = buildString(form.length) {
            for (ch in form) append(when (ch) { 'ی' -> 'ي'; 'ک' -> 'ك'; 'ه' -> 'ة'; else -> ch })
        }
        return mapped.takeIf { it != form }
    }

    /** Alef written as آ / أ / إ, one substitution per inner alef (bounded to three). */
    private fun alefForms(form: String): List<String> {
        val out = ArrayList<String>(3)
        var seen = 0
        for (index in form.indices) {
            if (form[index] != 'ا') continue
            val replacement = when (seen) { 0 -> 'آ'; 1 -> 'أ'; else -> 'إ' }
            out += form.substring(0, index) + replacement + form.substring(index + 1)
            seen++
            if (seen == 3) break
        }
        return out
    }

    /** True when the query has enough content to execute. */
    fun isExecutable(raw: String): Boolean = tokens(raw).isNotEmpty()

    // ── folding primitives ───────────────────────────────────────────────────

    private fun mapChar(ch: Char): Char? {
        if (ch == 'ـ') return null // tatweel: a decorative elongation, not content
        val type = Character.getType(ch)
        if (type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt()
        ) {
            return null
        }
        if (type == Character.FORMAT.toInt()) return ' ' // ZWNJ, ZWJ, RLM/LRM, soft hyphen
        val digit = digitOrNull(ch)
        if (digit != null) return digit

        val folded = when (ch) {
            'ي', 'ئ', 'ى' -> 'ی'
            'ك' -> 'ک'
            'ة' -> 'ه'
            'أ', 'إ', 'آ', 'ٱ' -> 'ا'
            'ؤ' -> 'و'
            'ﻻ' -> 'ل' // the Arabic ligature is a presentation form, not a letter
            else -> ch
        }
        if (!Character.isLetterOrDigit(folded)) return ' '
        return Character.toLowerCase(folded)
    }

    private fun digitOrNull(ch: Char): Char? = when (ch) {
        in '۰'..'۹' -> '0' + (ch - '۰') // Extended Arabic-Indic (Persian)
        in '٠'..'٩' -> '0' + (ch - '٠') // Arabic-Indic
        in '0'..'9' -> ch
        else -> null
    }

    private const val MAX_TOKENS = 8
    private const val MAX_SPELLINGS = 12
    private const val ZWNJ_VARIANTS = 4
}
