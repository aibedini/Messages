package com.autonomousone.messages.media

import com.autonomousone.messages.data.MessageAssetBackfillRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The resumable-checkpoint arithmetic of the history sweep.
 *
 * A 360K-message history is only safe to sweep because the cursor is a KEYSET on
 * the canonical order and is persisted after every batch. These tests pin the
 * properties that make that true: START is above every real row, the cursor never
 * moves forward, and a resumed sweep re-covers nothing it already covered.
 */
class AssetBackfillCursorTest {

    private fun row(date: Long, source: String, id: Long) =
        MessageAssetBackfillRow(source, id, threadId = 3L, body = "", date = date)

    @Test
    fun `start is above every real row`() {
        val real = listOf(
            AssetBackfillCursor(Long.MAX_VALUE - 1, "sms", Long.MAX_VALUE),
            AssetBackfillCursor(1_700_000_000_000L, "mms", 999L),
            AssetBackfillCursor(0L, "sms", 1L)
        )
        // "START is not before X" == "START covers X", for every real position.
        real.forEach { assertFalse(AssetBackfillCursor.START.isBefore(it)) }
        assertTrue(AssetBackfillCursor.START.isStart)
    }

    @Test
    fun `advance moves strictly backwards`() {
        var cursor = AssetBackfillCursor.START
        cursor = cursor.advance(row(1_000L, "sms", 5L))
        assertEquals(AssetBackfillCursor(1_000L, "sms", 5L), cursor)
        cursor = cursor.advance(row(900L, "mms", 7L))
        assertEquals(AssetBackfillCursor(900L, "mms", 7L), cursor)
    }

    @Test
    fun `advance can never move the cursor forward`() {
        val cursor = AssetBackfillCursor(500L, "sms", 10L)
        // A page whose last row is NEWER than the cursor (impossible in a proper
        // keyset walk) must not rewind the sweep.
        assertEquals(cursor, cursor.advance(row(600L, "sms", 1L)))
        assertEquals(cursor, cursor.advance(row(500L, "sms", 99L)))
    }

    @Test
    fun `the cursor separates rows on the same date by source then providerId`() {
        val base = AssetBackfillCursor(100L, "sms", 5L)
        assertTrue(AssetBackfillCursor(100L, "sms", 4L).isBefore(base))
        assertTrue(AssetBackfillCursor(100L, "mms", 9L).isBefore(base))
        assertFalse(AssetBackfillCursor(100L, "sms", 6L).isBefore(base))
        assertFalse(AssetBackfillCursor(101L, "sms", 1L).isBefore(base))
    }

    @Test
    fun `advance from a whole page lands on the oldest row of that page`() {
        val page = listOf(
            row(9_000L, "sms", 30L),
            row(9_000L, "mms", 20L),
            row(8_000L, "sms", 10L)
        )
        val next = AssetBackfillCursor.START.advance(page.last())
        assertEquals(AssetBackfillCursor(8_000L, "sms", 10L), next)
    }

    @Test
    fun `the batch size stays inside the bounded 200 to 500 band`() {
        assertTrue(AssetBackfillCursor.BATCH_SIZE in 200..500)
        assertTrue(AssetBackfillCursor.MAX_BATCHES_PER_RUN >= 1)
    }
}
