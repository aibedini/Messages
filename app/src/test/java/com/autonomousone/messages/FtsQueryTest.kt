package com.autonomousone.messages

import com.autonomousone.messages.data.FtsQuery
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * FTS MATCH expressions must never break out of the quoted token (operators
 * like `OR`, `*`, `:` are user input, not query syntax). Every token is
 * double-quoted and embedded quotes are escaped by doubling.
 */
class FtsQueryTest {

    @Test
    fun `single token is quoted`() {
        assertEquals("\"کتاب\"", FtsQuery.build("کتاب"))
        assertEquals("\"invoice\"", FtsQuery.build("invoice"))
    }

    @Test
    fun `multi word query ANDs the tokens`() {
        assertEquals("\"hello\" \"world\"", FtsQuery.build("hello world"))
    }

    @Test
    fun `operators inside the query are treated as literal text`() {
        assertEquals("\"OR\"", FtsQuery.build("OR"))
        assertEquals("\"a*b\"", FtsQuery.build("a*b"))
    }

    @Test
    fun `embedded double quotes are escaped`() {
        // "say \"hi\"" splits on whitespace into [say, "hi"] → each token is
        // quoted and embedded quotes are doubled: "say" """hi"""
        assertEquals("\"say\" \"\"\"hi\"\"\"", FtsQuery.build("say \"hi\""))
    }

    @Test
    fun `empty or whitespace-only input yields no query`() {
        assertEquals("", FtsQuery.build(""))
        assertEquals("", FtsQuery.build("   "))
        assertEquals("", FtsQuery.build("\n\t "))
    }

    @Test
    fun `extra whitespace is collapsed`() {
        assertEquals("\"a\" \"b\"", FtsQuery.build("  a \n b "))
    }
}
