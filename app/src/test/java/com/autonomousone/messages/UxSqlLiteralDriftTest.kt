package com.autonomousone.messages

import com.autonomousone.messages.data.COUNT_STARRED_IN_THREAD_SQL
import com.autonomousone.messages.data.DELETE_ORPHANS_SQL
import com.autonomousone.messages.data.MessageAssetSql
import com.autonomousone.messages.data.SET_KEEP_FROM_OTP_CLEANUP_SQL
import com.autonomousone.messages.data.SET_STARRED_SQL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Anti-drift guard for the provenance-table SQL (v3.4.0).
 *
 * THE PROBLEM THIS SOLVES
 * -----------------------
 * Room's KSP processor rejects a string TEMPLATE as an annotation argument
 * (`@Query("${MessageAssetSql.X}")` / `@Query("$SET_STARRED_SQL")`) with
 *
 *     No property named value was found in annotation Query
 *
 * which fails the entire code-generation step, so the DAO annotations must hold
 * the statements as INLINE LITERALS. Sibling tests (`MessageAssetSqlTest`,
 * `MessageUserStateSqlTest`) exercise the constants against a real SQLite
 * engine — and those constants are now referenced only from tests, not by the
 * DAO. Without this guard, editing a DAO literal (or a constant) would leave the
 * tested statement and the shipped statement disagreeing while every test still
 * passed: exactly the failure mode the constants were introduced to prevent.
 *
 * This test therefore pins the two copies together, character for character.
 * It is intentionally a SOURCE check rather than an execution check: no engine
 * can see a Kotlin annotation literal.
 */
class UxSqlLiteralDriftTest {

    private val sourceDir = "src/main/java/com/autonomousone/messages/data"

    private fun source(name: String): String {
        val file = java.io.File(sourceDir, name)
        assertTrue("missing source file ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    /**
     * Verifies one `@Query( ... )` annotation body placed directly above
     * [functionName] matches [expected] once Kotlin's `+` concatenation and
     * whitespace are normalised the way the compiler's constant folding does.
     */
    private fun assertQueryMatches(
        file: String,
        functionName: String,
        expected: String
    ) {
        val text = source(file)
        val marker = "suspend fun $functionName("
        val markerAlt = "fun $functionName("
        val at = text.indexOf(marker).takeIf { it >= 0 } ?: text.indexOf(markerAlt)
        assertTrue("function $functionName not found in $file", at >= 0)

        // Walk BACKWARDS from the declaration to the nearest @Query( ... ).
        val queryAt = text.lastIndexOf("@Query(", at)
        assertTrue("no @Query above $functionName", queryAt >= 0)

        // The annotation body is the balanced parenthesised expression after it.
        //
        // The scan is STRING-AWARE on purpose: SQL contains parentheses of its own
        // (`ON CONFLICT(source, providerId)`, `COUNT(*)`, trigger-like text), and a
        // naive depth counter stops at the first `)` INSIDE the literal — which
        // silently truncated the extracted SQL and made this guard report drift that
        // did not exist. Characters inside a "..." or """...""" literal are skipped.
        val open = text.indexOf('(', queryAt)
        var depth = 0
        var close = -1
        var i = open
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' && text.startsWith("\"\"\"", i) -> {
                    val end = text.indexOf("\"\"\"", i + 3)
                    if (end < 0) break
                    i = end + 3
                    continue
                }
                c == '"' -> {
                    i++
                    while (i < text.length && text[i] != '"') {
                        if (text[i] == '\\') i++
                        i++
                    }
                }
                c == '(' -> depth++
                c == ')' -> {
                    depth--
                    if (depth == 0) {
                        close = i
                        break
                    }
                }
            }
            i++
        }
        assertTrue("unbalanced @Query for $functionName", close > open)

        val raw = text.substring(open + 1, close)
        assertEquals(
            "SQL literal for $functionName drifted from its pinned constant",
            normalise(expected),
            normalise(raw)
        )
    }

    /**
     * Flattens a Kotlin string expression into the value the compiler folds it
     * to: strip the surrounding quotes, drop `+`, unescape `\n`/`\"`, collapse
     * runs of whitespace, and trim.
     */
    private fun normalise(expression: String): String {
        val withoutComments = expression
            .lineSequence()
            .joinToString("\n") { line ->
                val i = line.indexOf("//")
                if (i >= 0) line.substring(0, i) else line
            }
        val sb = StringBuilder()
        var i = 0
        while (i < withoutComments.length) {
            val c = withoutComments[i]
            when {
                c == '"' && i + 2 < withoutComments.length && withoutComments.startsWith("\"\"\"", i) -> {
                    val end = withoutComments.indexOf("\"\"\"", i + 3)
                    sb.append(withoutComments, i + 3, if (end < 0) withoutComments.length else end)
                    i = if (end < 0) withoutComments.length else end + 3
                }
                c == '"' -> {
                    i++
                    while (i < withoutComments.length && withoutComments[i] != '"') {
                        if (withoutComments[i] == '\\' && i + 1 < withoutComments.length) {
                            val esc = withoutComments[i + 1]
                            sb.append(
                                when (esc) {
                                    'n' -> '\n'
                                    't' -> '\t'
                                    else -> esc
                                }
                            )
                            i += 2
                        } else {
                            sb.append(withoutComments[i])
                            i++
                        }
                    }
                    i++ // closing quote
                }
                else -> i++
            }
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    @Test
    fun `message_assets page literals match their constants`() {
        assertQueryMatches("UxDaos.kt", "pageByKind", MessageAssetSql.PAGE_BY_KIND_SQL)
        assertQueryMatches("UxDaos.kt", "countByKind", MessageAssetSql.COUNT_BY_KIND_SQL)
        assertQueryMatches(
            "UxDaos.kt",
            "pageWithBodyByKind",
            MessageAssetSql.PAGE_LINKS_WITH_BODY_SQL
        )
        assertQueryMatches("UxDaos.kt", "forMessage", MessageAssetSql.FOR_MESSAGE_SQL)
        assertQueryMatches("UxDaos.kt", "deleteForMessage", MessageAssetSql.DELETE_FOR_MESSAGE_SQL)
        assertQueryMatches(
            "UxDaos.kt",
            "deleteForMessageKind",
            MessageAssetSql.DELETE_FOR_MESSAGE_KIND_SQL
        )
        assertQueryMatches("UxDaos.kt", "backfillBatch", MessageAssetSql.BACKFILL_BATCH_SQL)
        assertQueryMatches("UxDaos.kt", "deleteOrphans", MessageAssetSql.DELETE_ORPHANS_SQL)
    }

    /**
     * The user-state writers/readers whose text does NOT interpolate a shared
     * fragment. `STARRED_PAGE_SQL` / `STARRED_PAGE_IN_THREAD_SQL` are excluded on
     * purpose: their constant interpolates `MessageCutoff.ACTIVE_MESSAGE_FILTER_SQL`
     * while the annotation literal inlines the expanded predicate, so a textual
     * comparison would be comparing two different (correct) forms. Their
     * behaviour is covered by `MessageUserStateSqlTest` instead.
     */
    @Test
    fun `message_user_state literals match their constants`() {
        assertQueryMatches("UxDaos.kt", "setStarred", SET_STARRED_SQL)
        assertQueryMatches(
            "UxDaos.kt",
            "setKeepFromOtpCleanup",
            SET_KEEP_FROM_OTP_CLEANUP_SQL
        )
        assertQueryMatches("UxDaos.kt", "countStarredInThread", COUNT_STARRED_IN_THREAD_SQL)
        assertQueryMatches("UxDaos.kt", "deleteOrphans", DELETE_ORPHANS_SQL)
    }
}
