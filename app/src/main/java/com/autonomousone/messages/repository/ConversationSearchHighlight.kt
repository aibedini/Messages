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
            val stripped = stripZeroWidth(token)
            if (stripped != token && stripped.isNotBlank()) collect(body, stripped, found)
        }
        if (found.isEmpty()) return emptyList()

        found.sortWith(compareBy({ it.start }, { it.end }))
        val merged = ArrayList<Range>(found.size)
        var current = found.first()
        for (i in 1 until found.size) {
            val next = found[i]
            current = if (next.start <= current.end) {
                Range(current.start, maxOf(current.end, next.end))
            } else {
                merged.add(current)
                next
            }
        }
        merged.add(current)
        return merged
    }

    /** Convenience for the UI: is there anything to highlight at all? */
    fun hasMatch(body: String, query: String): Boolean = ranges(body, query).isNotEmpty()

    private fun collect(body: String, token: String, into: MutableList<Range>) {
        if (token.isEmpty()) return
        var from = 0
        while (from <= body.length - token.length) {
            val at = body.indexOf(token, from, ignoreCase = true)
            if (at < 0) return
            into.add(Range(at, at + token.length))
            from = at + 1
        }
    }

    private val WHITESPACE = Regex("\\s+")

    private fun stripZeroWidth(value: String): String =
        value.filterNot { it == '\u200C' || it == '\u200D' || it == '\u200E' || it == '\u200F' }
}
