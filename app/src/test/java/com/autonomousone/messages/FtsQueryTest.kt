package com.autonomousone.messages

import com.autonomousone.messages.data.FtsQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MATCH expression the search actually sends to SQLite.
 *
 * The contract changed on purpose:
 *  - every token is a PREFIX, so `دختر` finds `دخترها`/`دخترونه`;
 *  - every token is asked in every plausible spelling (Arabic/Persian letters, three digit
 *    scripts), because those are the same word to a person and different tokens to the index;
 *  - a multi-token query also asks for the spaceless form, because `بی فاصله` and `بی‌فاصله`
 *    are one word to the reader;
 *  - user input can still never become FTS syntax: folding reduces it to letters and digits.
 */
class FtsQueryTest {

    @Test
    fun `every token is a prefix`() {
        assertEquals("hello*", FtsQuery.build("hello"))
        assertTrue(FtsQuery.build("کتاب").startsWith("("))
        assertTrue(FtsQuery.build("کتاب").contains("کتاب*"))
    }

    @Test
    fun `multi word query ANDs the tokens and also asks for the spaceless form`() {
        assertEquals("(hello* world*) OR helloworld*", FtsQuery.build("hello world"))
    }

    @Test
    fun `Arabic and Persian letter forms are one query`() {
        val match = FtsQuery.build("علی")
        assertTrue(match, match.contains("علی*"))
        assertTrue(match, match.contains("علي*"))
    }

    @Test
    fun `a digit typed in any script is asked in all three`() {
        assertEquals("(0912* OR ۰۹۱۲* OR ٠٩١٢*)", FtsQuery.build("۰۹۱۲"))
        assertEquals("(0912* OR ۰۹۱۲* OR ٠٩١٢*)", FtsQuery.build("0912"))
    }

    @Test
    fun `the spaceless variant survives ZWNJ and spaces in the input`() {
        val match = FtsQuery.build("بی‌ فاصله")
        assertTrue(match, match.contains("بیفاصله*"))
    }

    @Test
    fun `operators in the input are punctuation, never syntax`() {
        // OR folds to a lowercase term; '*' is punctuation, so it splits the token in two.
        assertEquals("or*", FtsQuery.build("OR"))
        assertEquals("(a* b*) OR ab*", FtsQuery.build("a*b"))
    }

    @Test
    fun `quotes and parentheses cannot escape the query`() {
        val match = FtsQuery.build("say \"hi\"")
        assertEquals("(say* hi*) OR sayhi*", match)
        assertEquals("(a* b*) OR ab*", FtsQuery.build("(a) (b)"))
    }

    @Test
    fun `empty or punctuation-only input yields no query`() {
        assertEquals("", FtsQuery.build(""))
        assertEquals("", FtsQuery.build("   "))
        assertEquals("", FtsQuery.build("!؟،؛…"))
        assertEquals(0, FtsQuery.tokenCount("!?"))
    }

    @Test
    fun `extra whitespace is collapsed`() {
        assertEquals("(a* b*) OR ab*", FtsQuery.build("  a \n b "))
    }

    @Test
    fun `the recall fallback ORs the tokens`() {
        assertEquals("(hello* OR world*)", FtsQuery.buildAny("hello world"))
        assertEquals("hello*", FtsQuery.buildAny("hello"))
        assertEquals("", FtsQuery.buildAny("   "))
    }

    @Test
    fun `no term is ever left unquoted without a prefix`() {
        // Everything the builder emits must be letters/digits followed by '*', or syntax it added.
        val match = FtsQuery.build("سلام دنیا 0912")
        assertFalse(match.contains("\""))
        val terms = match.replace("(", " ").replace(")", " ").split(" ")
            .filter { it.isNotBlank() && it != "OR" }
        assertTrue(terms.isNotEmpty())
        terms.forEach { term ->
            assertTrue("term without prefix: $term", term.endsWith("*"))
            assertTrue("empty term: $term", term.length > 1)
        }
    }
}
