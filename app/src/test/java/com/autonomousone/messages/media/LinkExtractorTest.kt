package com.autonomousone.messages.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The URL extractor / normalizer contract.
 *
 * These are the rules the asset identity depends on. If normalization is not
 * deterministic, re-ingesting a message creates a SECOND asset for the same link;
 * if a phone number or an email address slips through, the Links tab fills with
 * garbage that the user cannot remove.
 */
class LinkExtractorTest {

    private fun one(body: String): String? =
        LinkExtractor.extract(body).map { it.normalized }.singleOrNull()

    // ── Extraction ──────────────────────────────────────────────────────────

    @Test
    fun `extracts a plain http url`() {
        assertEquals("http://example.com/a", one("see http://example.com/a now"))
    }

    @Test
    fun `extracts a bare domain as https`() {
        assertEquals("https://example.com", one("go to example.com"))
    }

    @Test
    fun `extracts www hosts`() {
        assertEquals("https://www.example.com/page", one("www.example.com/page"))
    }

    @Test
    fun `extracts several distinct links in order`() {
        val links = LinkExtractor.extract("a https://one.com b https://two.org c")
        assertEquals(listOf("https://one.com", "https://two.org"), links.map { it.normalized })
    }

    @Test
    fun `collapses two spellings of the same link inside one message`() {
        // The brief: "Never store one asset per redundant duplicate inside the
        // same message."
        val links = LinkExtractor.extract(
            "https://Example.COM/path and example.com/path?utm_source=x"
        )
        assertEquals(1, links.size)
        assertEquals("https://example.com/path", links.single().normalized)
    }

    @Test
    fun `two different links stay two links`() {
        assertEquals(
            listOf("https://a.com", "https://b.com"),
            LinkExtractor.extract("https://a.com https://b.com").map { it.normalized }
        )
    }

    // ── Normalization ───────────────────────────────────────────────────────

    @Test
    fun `normalization is deterministic`() {
        val inputs = listOf(
            "https://Example.com/Path?utm_source=a&id=7#frag",
            "HTTPS://WWW.EXAMPLE.COM:443/",
            "example.com/report.pdf?fbclid=xyz",
            "http://example.com",
            "plus+plus@example.com"
        )
        for (input in inputs) {
            val first = LinkExtractor.normalize(input)
            val second = LinkExtractor.normalize(input)
            assertEquals("normalize must be pure: $input", first, second)
            if (first != null) {
                assertEquals(
                    "normalize must be idempotent: $input",
                    first,
                    LinkExtractor.normalize(first)
                )
            }
        }
    }

    @Test
    fun `lowercases scheme and host but not the path or query values`() {
        assertEquals(
            "https://example.com/CaseSensitive/Path?Token=AbC",
            LinkExtractor.normalize("HTTPS://Example.COM/CaseSensitive/Path?Token=AbC")
        )
    }

    @Test
    fun `strips tracking-only query parameters consistently`() {
        assertEquals(
            "https://example.com/p?id=7",
            LinkExtractor.normalize("https://example.com/p?utm_source=x&id=7&fbclid=y&utm_medium=z")
        )
        assertEquals(
            "https://example.com/p?id=7",
            LinkExtractor.normalize("https://example.com/p?id=7")
        )
    }

    @Test
    fun `drops the fragment and the default port`() {
        assertEquals("https://example.com/a", LinkExtractor.normalize("https://example.com/a#section"))
        assertEquals("https://example.com/a", LinkExtractor.normalize("https://example.com:443/a"))
        assertEquals("http://example.com/a", LinkExtractor.normalize("http://example.com:80/a"))
    }

    @Test
    fun `keeps a non-default port`() {
        assertEquals("http://example.com:8080/a", LinkExtractor.normalize("http://example.com:8080/a"))
    }

    @Test
    fun `strips trailing prose punctuation and an unbalanced bracket`() {
        assertEquals("https://example.com/a", LinkExtractor.normalize("https://example.com/a."))
        assertEquals("https://example.com/a", LinkExtractor.normalize("(https://example.com/a),"))
        assertEquals("https://example.com/a", LinkExtractor.normalize("«https://example.com/a»"))
        assertEquals("https://example.com/a", LinkExtractor.normalize("https://example.com/a،"))
    }

    @Test
    fun `strips a trailing slash so one resource has one identity`() {
        assertEquals(
            LinkExtractor.normalize("https://example.com/a"),
            LinkExtractor.normalize("https://example.com/a/")
        )
    }

    @Test
    fun `an empty query is not part of the identity`() {
        assertEquals("https://example.com/a", LinkExtractor.normalize("https://example.com/a?"))
    }

    @Test
    fun `www is kept - it can be a different site`() {
        assertTrue(
            LinkExtractor.normalize("https://www.example.com") !=
                LinkExtractor.normalize("https://example.com")
        )
    }

    // ── Not-a-link rules ────────────────────────────────────────────────────

    @Test
    fun `a phone number is never a url`() {
        val phones = listOf(
            "+989121234567",
            "09123456789",
            "0912 123 4567",
            "+98-912-123-4567",
            "0912.123.4567",
            "(0912) 123-4567"
        )
        for (phone in phones) {
            assertNull("phone must not be a link: $phone", LinkExtractor.normalize(phone))
            assertTrue(
                "phone must not be extracted: $phone",
                LinkExtractor.extract("call me on $phone please").isEmpty()
            )
        }
    }

    @Test
    fun `a tel or sms uri is never a link`() {
        assertEquals(0, LinkExtractor.extract("tel:+989121234567").size)
        assertEquals(0, LinkExtractor.extract("sms:+989121234567").size)
        assertEquals(0, LinkExtractor.extract("mailto:user@example.com").size)
    }

    @Test
    fun `an email address is not a link - documented rule`() {
        assertEquals(0, LinkExtractor.extract("write to user@example.com").size)
        assertEquals(0, LinkExtractor.extract("user.name+tag@mail.example.co.uk").size)
    }

    @Test
    fun `an explicit-scheme url with userinfo is still a link`() {
        assertNotNull(LinkExtractor.normalize("https://user@example.com/path"))
    }

    @Test
    fun `version strings and bare ips are not links`() {
        assertNull(LinkExtractor.normalize("3.14"))
        assertNull(LinkExtractor.normalize("192.168.1.1"))
        assertEquals(0, LinkExtractor.extract("version 3.14 released").size)
        assertEquals(0, LinkExtractor.extract("server 10.0.0.5 is down").size)
    }

    @Test
    fun `a file name is not a link`() {
        assertEquals(0, LinkExtractor.extract("see report.pdf attached").size)
    }

    @Test
    fun `an over-long candidate is rejected rather than stored`() {
        val monster = "https://example.com/" + "a".repeat(3000)
        assertNull(LinkExtractor.normalize(monster))
    }

    // ── Host + snippet ──────────────────────────────────────────────────────

    @Test
    fun `hostOf returns the bare lower-case host`() {
        assertEquals("example.com", LinkExtractor.hostOf("https://example.com/a?b=c"))
        assertEquals("example.com", LinkExtractor.hostOf("http://example.com:8080/a"))
        assertEquals("sub.example.co.uk", LinkExtractor.hostOf("https://sub.example.co.uk"))
    }

    @Test
    fun `snippet removes urls and collapses whitespace`() {
        val snippet = LinkExtractor.snippetFor("Check   this out https://example.com/a?token=secret\nthanks")
        assertEquals("Check this out thanks", snippet)
    }

    @Test
    fun `snippet is truncated with an ellipsis`() {
        val snippet = LinkExtractor.snippetFor("x".repeat(400))
        assertEquals(LinkExtractor.SNIPPET_MAX_LENGTH + 1, snippet.length)
        assertTrue(snippet.endsWith("…"))
    }

    @Test
    fun `snippet of a link-only message is empty, never the url`() {
        assertEquals("", LinkExtractor.snippetFor("https://example.com/a?token=secret"))
    }
}
