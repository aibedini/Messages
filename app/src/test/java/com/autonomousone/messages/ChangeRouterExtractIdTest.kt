package com.autonomousone.messages

import com.autonomousone.messages.data.ChangeRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The ContentObserver fires on the MAIN looper. ChangeRouter must recognize
 * URI paths that carry a row id (O(1) targeted mutation) vs. generic ones
 * (bounded reconcile) — and it must never touch the provider on the caller's
 * thread (the read itself is offloaded to Dispatchers.IO in route()).
 */
class ChangeRouterExtractIdTest {

    @Test
    fun `single-row sms path extracts the id`() {
        assertEquals(348_201L, ChangeRouter.extractRowIdFromPath("//sms/348201"))
    }

    @Test
    fun `single-row mms path extracts the id`() {
        assertEquals(777L, ChangeRouter.extractRowIdFromPath("//mms/777"))
    }

    @Test
    fun `table-level path has no id`() {
        assertNull(ChangeRouter.extractRowIdFromPath("//sms"))
        assertNull(ChangeRouter.extractRowIdFromPath("//mms"))
    }

    @Test
    fun `non-numeric last segment has no id`() {
        assertNull(ChangeRouter.extractRowIdFromPath("//sms/conversation"))
        assertNull(ChangeRouter.extractRowIdFromPath("//sms/12a"))
    }

    @Test
    fun `null or blank path has no id`() {
        assertNull(ChangeRouter.extractRowIdFromPath(null))
        assertNull(ChangeRouter.extractRowIdFromPath(""))
    }

    @Test
    fun `thread URIs are NOT row ids`() {
        // content://sms/thread/123 → reading 123 as _ID would upsert a
        // random unrelated message; the router must fall through instead.
        assertNull(ChangeRouter.extractRowIdFromPath("//sms/thread/123"))
        assertNull(ChangeRouter.extractRowIdFromPath("//sms/thread"))
    }
}
