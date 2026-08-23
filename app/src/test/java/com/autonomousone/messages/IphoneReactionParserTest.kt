package com.autonomousone.messages

import com.autonomousone.messages.messaging.IphoneReactionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IphoneReactionParserTest {

    // ── Verbs + straight quotes ─────────────────────────────────────────────

    @Test fun `loved with straight quotes`() {
        val r = IphoneReactionParser.parse("Loved \"See you tomorrow!\"")!!
        assertEquals("❤️", r.emoji)
        assertEquals("See you tomorrow!", r.quotedText)
    }

    @Test fun `liked with straight quotes`() {
        val r = IphoneReactionParser.parse("Liked \"nice shot\"")!!
        assertEquals("👍", r.emoji)
        assertEquals("nice shot", r.quotedText)
    }

    @Test fun `disliked maps to thumbs down`() {
        val r = IphoneReactionParser.parse("Disliked \"that idea\"")!!
        assertEquals("👎", r.emoji)
    }

    @Test fun `laughed at maps to laughing emoji`() {
        val r = IphoneReactionParser.parse("Laughed at \"my joke\"")!!
        assertEquals("😂", r.emoji)
        assertEquals("my joke", r.quotedText)
    }

    @Test fun `laughed without at also parses`() {
        val r = IphoneReactionParser.parse("Laughed \"ha ha\"")!!
        assertEquals("😂", r.emoji)
    }

    @Test fun `emphasized maps to double exclamation`() {
        val r = IphoneReactionParser.parse("Emphasized \"important note\"")!!
        assertEquals("‼️", r.emoji)
    }

    @Test fun `questioned maps to question mark`() {
        val r = IphoneReactionParser.parse("Questioned \"are we still on?\"")!!
        assertEquals("❓", r.emoji)
    }

    // ── Curly quotes ────────────────────────────────────────────────────────

    @Test fun `curly quotes are stripped`() {
        val r = IphoneReactionParser.parse("Loved “سلام، خوبی؟”")!!
        assertEquals("❤️", r.emoji)
        assertEquals("سلام، خوبی؟", r.quotedText)
    }

    // ── Unquoted media nouns ────────────────────────────────────────────────

    @Test fun `liked an image has no quoted text`() {
        val r = IphoneReactionParser.parse("Liked an image")!!
        assertEquals("👍", r.emoji)
        assertNull(r.quotedText)
    }

    @Test fun `loved a photo`() {
        val r = IphoneReactionParser.parse("Loved a photo")!!
        assertEquals("❤️", r.emoji)
        assertNull(r.quotedText)
    }

    @Test fun `questioned an audio message`() {
        val r = IphoneReactionParser.parse("Questioned an audio message")!!
        assertEquals("❓", r.emoji)
    }

    @Test fun `emphasized a video`() {
        val r = IphoneReactionParser.parse("Emphasized a video")!!
        assertEquals("‼️", r.emoji)
    }

    // ── Case / whitespace tolerance ─────────────────────────────────────────

    @Test fun `lowercase verb still parses`() {
        assertTrue(IphoneReactionParser.isReaction("loved \"x\""))
    }

    @Test fun `surrounding whitespace is tolerated`() {
        val r = IphoneReactionParser.parse("   Liked  \"padded\"   ")!!
        assertEquals("padded", r.quotedText)
    }

    // ── Must NOT be classified as reactions ─────────────────────────────────

    @Test fun `ordinary sentence starting with Loved is rejected`() {
        assertNull(IphoneReactionParser.parse("Loved your gift!"))
    }

    @Test fun `laughed out loud is rejected`() {
        assertNull(IphoneReactionParser.parse("Laughed out loud!"))
    }

    @Test fun `verb alone is rejected`() {
        assertNull(IphoneReactionParser.parse("Loved"))
    }

    @Test fun `unknown unquoted object is rejected`() {
        assertNull(IphoneReactionParser.parse("Liked an image!")) // trailing punctuation breaks exact noun match
        assertNull(IphoneReactionParser.parse("Loved my new car"))
    }

    @Test fun `plain message is rejected`() {
        assertNull(IphoneReactionParser.parse("Hey, how are you?"))
    }

    @Test fun `multiline text is rejected`() {
        assertNull(IphoneReactionParser.parse("Loved \"line one\"\nLoved \"line two\""))
    }

    @Test fun `empty text is rejected`() {
        assertNull(IphoneReactionParser.parse(""))
        assertNull(IphoneReactionParser.parse("   "))
    }

    @Test fun `oversized text is rejected`() {
        assertNull(IphoneReactionParser.parse("Liked \"" + "x".repeat(301) + "\""))
    }

    @Test fun `unmatched quotes are rejected`() {
        assertNull(IphoneReactionParser.parse("Loved \"unclosed quote"))
    }
}
