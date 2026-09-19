package com.autonomousone.messages.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Paging bounds for the three tabs — the boundary behaviour, as values. */
class AssetPagingTest {

    @Test
    fun `page size is bounded`() {
        assertEquals(AssetPaging.PAGE_SIZE, AssetPaging.limit(AssetPaging.PAGE_SIZE))
        assertEquals(AssetPaging.MAX_PAGE_SIZE, AssetPaging.limit(Int.MAX_VALUE))
        assertEquals(AssetPaging.MAX_PAGE_SIZE, AssetPaging.limit(AssetPaging.MAX_PAGE_SIZE + 1))
        assertEquals(1, AssetPaging.limit(0))
        assertEquals(1, AssetPaging.limit(-5))
    }

    @Test
    fun `offset advances by the rows actually loaded`() {
        assertEquals(0, AssetPaging.nextOffset(0))
        assertEquals(60, AssetPaging.nextOffset(60))
        assertEquals(120, AssetPaging.nextOffset(120))
        // A short page still advances by what it returned (the last page).
        assertEquals(130, AssetPaging.nextOffset(130))
        assertEquals(0, AssetPaging.nextOffset(-3))
    }

    @Test
    fun `a short page is the last page`() {
        assertTrue(AssetPaging.reachedEnd(0, 60))
        assertTrue(AssetPaging.reachedEnd(59, 60))
    }

    @Test
    fun `a full page is not the last page`() {
        assertFalse(AssetPaging.reachedEnd(60, 60))
    }

    @Test
    fun `reachedEnd clamps an absurd request the same way limit does`() {
        // limit() caps at MAX_PAGE_SIZE, so a 500-row request measuring 200 rows
        // is a full (not final) page.
        assertFalse(AssetPaging.reachedEnd(AssetPaging.MAX_PAGE_SIZE, 500))
        assertTrue(AssetPaging.reachedEnd(AssetPaging.MAX_PAGE_SIZE - 1, 500))
    }
}
