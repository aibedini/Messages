package com.autonomousone.messages.data

/**
 * Builds a safe SQLite FTS4 `MATCH` expression from raw user input.
 *
 * Every token is wrapped in double quotes (FTS4 quoted strings make operators
 * and special characters literal) and embedded quotes are escaped by doubling.
 * An empty / whitespace-only query yields "" — callers must skip execution.
 */
object FtsQuery {

    fun build(raw: String): String {
        val tokens = raw.trim().split(WHITESPACE).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return ""
        return tokens.joinToString(" ") { token ->
            "\"" + token.replace("\"", "\"\"") + "\""
        }
    }

    private val WHITESPACE = Regex("\\s+")
}
