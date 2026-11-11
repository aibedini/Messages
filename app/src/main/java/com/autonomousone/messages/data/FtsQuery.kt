package com.autonomousone.messages.data

/**
 * Builds a SQLite FTS4 `MATCH` expression from raw user input.
 *
 * Three rules shape it:
 *
 * 1. **Nothing the user types is FTS syntax.** [SearchText] folds the input to letters and digits
 *    before anything is emitted, so quotes, `*`, `OR`, `NEAR` or a stray `(` in the query are
 *    punctuation that became a space — never an operator.
 * 2. **Every token is a PREFIX.** A search for `دختر` must find `دخترها` and `دخترونه`: Persian
 *    builds words with suffixes, and requiring the exact token made the search feel broken. The
 *    index answers prefix queries from the token's B-tree entry, so the cost is the same as an
 *    exact term.
 * 3. **Every token is asked in every plausible spelling** ([SearchText.spellings]): Arabic vs
 *    Persian letter forms and the three digit scripts are the same query to a person, and the FTS
 *    index stores whichever the message used.
 *
 * A multi-token query also asks for the SPACELESS form, because `بی فاصله` and `بی‌فاصله` are one
 * word to the reader and two different tokens to the index.
 */
object FtsQuery {

    /**
     * The strict form: every token must match (each in any of its spellings), OR the whole query
     * matches as one spaceless token.
     *
     * @return "" when the input has no searchable characters; callers must skip execution.
     */
    fun build(raw: String): String = build(raw, requireAll = true)

    /**
     * The recall fallback: ANY token matches.
     *
     * Used only when the strict form found nothing, so "incomplete" queries still find something
     * instead of showing an empty screen — never on the happy path, where it would flood the list.
     */
    fun buildAny(raw: String): String = build(raw, requireAll = false)

    /** How many canonical tokens the query has; callers use it to decide whether a fallback is useful. */
    fun tokenCount(raw: String): Int = SearchText.tokens(raw).size

    private fun build(raw: String, requireAll: Boolean): String {
        val tokens = SearchText.tokens(raw)
        if (tokens.isEmpty()) return ""
        val groups = tokens.map { token -> group(SearchText.spellings(token)) }
        if (tokens.size == 1) return groups.single()

        if (!requireAll) {
            return "(" + groups.joinToString(" OR ") + ")"
        }
        val spaceless = group(SearchText.spellings(tokens.joinToString("")))
        return "(" + groups.joinToString(" ") + ") OR " + spaceless
    }

    /** `(spelling1* OR spelling2* ...)`; parentheses are omitted for the single spelling. */
    private fun group(spellings: List<String>): String {
        val terms = spellings.filter { it.isNotBlank() }.map { it + "*" }
        if (terms.isEmpty()) return ""
        return if (terms.size == 1) terms.single() else "(" + terms.joinToString(" OR ") + ")"
    }
}
