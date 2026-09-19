package com.autonomousone.messages.repository

/**
 * v3.4.0 FEATURE 1 — which characters of a search hit are the match.
 *
 * Android-free so the range arithmetic is unit-testable
 * ([InConversationSearchHighlightTest]); the Compose layer only turns the
 * returned offsets into an `AnnotatedString` span. No regex is built from user
 * input (a query is data, never a pattern), which is also why this cannot be
 * an injection vector the way a naive `Regex(query)` would be.
 */
object ConversationSearchHighlight {

    /** Half-open [start, end) range into the message body. */
    data class Range(val start: Int, val end: Int)

    /**
     * @param query the RAW user query (the same string the FTS MATCH was built
     *   from), split on whitespace exactly like [FtsQuery.build].
     * @return ascending, non-overlapping, non-empty ranges.
     */
    fun ranges(body: String, query: String): List<Range> {
        if (body.isEmpty() || query.isBlank()) return emptyList()
        val tokens = query.trim().split(WHITESPACE).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()

        val found = ArrayList<Range>()
        tokens.forEach { token ->
            collect(body, token, found)
            // Persian/Arabic text is written with ZWNJ (U+200C) between word
            // parts, and users type the query without it (or with it). FTS4's
            // tokenizer folds the two together; the highlight must agree or a
            // result would render with no visible match at all.
            //
            // The fold has to happen on BOTH sides: the stored body may contain the
            // ZWNJ and the query may not. [collectFolded] matches the folded token
            // against the folded body and maps the hit back to real offsets, so the
            // highlighted span covers the ORIGINAL text (including the ZWNJ) and the
            // stored body is never rewritten.
            collectFolded(body, token, found)
        }
        if (found.isEmpty()) return emptyList()

        found.sortWith(compareBy({ it.start }, { it.end }))
        val merged = ArrayList<Range>(found.size)
        var current: Range = found.first()
        for (i in 1 until found.size) {
            val next: Range = found[i]
            if (next.start <= current.end) {
                current = Range(current.start, maxOf(current.end, next.end))
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)
        return merged
    }

    /** Convenience for the UI: is there anything to highlight at all? */
    fun hasMatch(body: String, query: String): Boolean = ranges(body, query).isNotEmpty()

    /**
     * Non-overlapping occurrences of [token].
     *
     * The scan advances by the token LENGTH, so "or" in "either this or that" yields
     * the two real words and not a cascade of overlapping sub-matches.
     *
     * Case-insensitivity is implemented by [indexOfIgnoringCase] rather than
     * `String.indexOf(ignoreCase = true)`: the platform method uses Unicode
     * case folding, which maps the Turkish dotless `ı`/`İ` onto `i`, so on a
     * Turkish-locale device it skipped real matches (a body containing `ı` before the
     * query made the NEXT occurrence the first hit). Comparing lower-cased characters
     * one at a time is locale-stable and never rewrites stored text.
     */
    private fun collect(body: String, token: String, into: MutableList<Range>) {
        if (token.isEmpty()) return
        var from = 0
        val last = body.length - token.length
        while (from <= last) {
            val at = matchAt(body, token, from)
            if (at < 0) return
            into.add(Range(at, at + token.length))
            from = at + token.length
        }
    }

    /**
     * First index at or after [from] where [token] occurs, compared
     * case-insensitively ONE CHARACTER AT A TIME with [Char.equals].
     *
     * `String.indexOf(ignoreCase = true)` is deliberately NOT used: it performs
     * Unicode case folding, which the platform maps `I`/`i` through in some locales,
     * and it silently failed to find a plain ASCII match (`"or"` inside
     * `"either this or that"`). Comparing character-by-character is locale-stable and
     * cannot rewrite or re-index the stored text.
     */
    private fun matchAt(body: String, token: String, from: Int): Int {
        val last = body.length - token.length
        var start = from
        while (start <= last) {
            var offset = 0
            var mismatch = false
            while (offset < token.length) {
                if (!body[start + offset].equals(token[offset], ignoreCase = true)) {
                    mismatch = true
                    break
                }
                offset++
            }
            if (!mismatch) return start
            start++
        }
        return -1
    }

    /**
     * First index at or after [from] where [token] occurs, comparing characters
     * individually with [Char.lowercaseChar]. Returns -1 when there is no occurrence.
     */
    private fun indexOfIgnoringCase(body: String, token: String, from: Int): Int {
        val last = body.length - token.length
        var start = from
        while (start <= last) {
            var matched = true
            var offset = 0
            while (offset < token.length) {
                val a = body[start + offset]
                val b = token[offset]
                if (a != b && a.lowercaseChar() != b.lowercaseChar()) {
                    matched = false
                    break
                }
                offset++
            }
            if (matched) return start
            start++
        }
        return -1
    }

    /**
     * Occurrences of [token] after folding zero-width marks out of BOTH strings.
     *
     * A body that contains ZWNJ and a query that does not (or vice versa) must still
     * highlight, and the returned range must be expressed in ORIGINAL body offsets —
     * the UI spans the real text, ZWNJ included. Walking the body once and recording
     * where each kept character came from gives that mapping for free, so no
     * character is ever stripped from stored content.
     */
    private fun collectFolded(body: String, token: String, into: MutableList<Range>) {
        val foldedBodyText: String = foldZeroWidth(body)
        val foldedTokenText: String = foldZeroWidth(token)
        if (foldedTokenText.isEmpty()) return
        // Nothing was folded away — [collect] already covered every occurrence.
        if (foldedBodyText.length == body.length && foldedTokenText == token) return
        val originalIndex: IntArray = originalIndices(body)
        var from = 0
        while (from <= foldedBodyText.length - foldedTokenText.length) {
            val at = indexOfIgnoringCase(foldedBodyText, foldedTokenText, from)
            if (at < 0) return
            val originalStart: Int = originalIndex[at]
            val lastMatchedOriginal: Int = originalIndex[at + foldedTokenText.length - 1]
            // End at the character AFTER the last matched one, so an interior ZWNJ
            // (میروم) falls INSIDE the highlighted span.
            val originalEnd: Int = maxOf(lastMatchedOriginal + 1, originalStart + 1)
            into.add(Range(originalStart, originalEnd))
            from = at + foldedTokenText.length
        }
    }

    /** Zero-width marks removed, everything else preserved verbatim. */
    private fun foldZeroWidth(value: String): String {
        val builder = StringBuilder(value.length)
        for (i in 0 until value.length) {
            val c = value[i]
            if (!isZeroWidth(c)) builder.append(c)
        }
        return builder.toString()
    }

    /** For each character of [foldZeroWidth] output, its index in the original. */
    private fun originalIndices(value: String): IntArray {
        val indices = ArrayList<Int>(value.length)
        for (i in 0 until value.length) {
            if (!isZeroWidth(value[i])) indices.add(i)
        }
        return indices.toIntArray()
    }

    private fun isZeroWidth(c: Char): Boolean =
        c == '\u200C' || c == '\u200D' || c == '\u200E' || c == '\u200F'

    private val WHITESPACE = Regex("\\s+")
}
