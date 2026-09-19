package com.autonomousone.messages.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.4.0 FEATURE 15 — the reported-spam visibility contract.
 *
 * These are the rules a user can observe directly, and getting either half wrong is
 * a data-loss-shaped bug: hiding a reported conversation EVERYWHERE makes the user's
 * own report look like a delete, while showing it in the inbox makes reporting feel
 * broken.
 */
class HomeSpamVisibilityTest {

    private val all = listOf(10L, 20L, 30L, 40L)
    private val spam = setOf(20L, 40L)

    @Test
    fun `reported conversations are hidden from the inbox`() {
        assertEquals(listOf(10L, 30L), HomeSpamVisibility.inboxThreadIds(all, spam))
    }

    @Test
    fun `reported conversations remain reachable under spam`() {
        assertEquals(listOf(20L, 40L), HomeSpamVisibility.spamThreadIdsInOrder(all, spam))
    }

    @Test
    fun `reported conversations are hidden in the archived tab too`() {
        // The rule is about the CONVERSATION, not about the tab: an archived thread
        // the user then reports must not reappear because they switched tabs.
        assertTrue(HomeSpamVisibility.isHiddenFromCurrentList(20L, spam, spamCategorySelected = false))
    }

    @Test
    fun `the spam chip is the only place a reported conversation is shown`() {
        assertFalse(HomeSpamVisibility.isHiddenFromCurrentList(20L, spam, spamCategorySelected = true))
        assertTrue(HomeSpamVisibility.isHiddenFromCurrentList(20L, spam, spamCategorySelected = false))
    }

    @Test
    fun `unreported conversations are never affected`() {
        listOf(true, false).forEach { chipSelected ->
            assertFalse(HomeSpamVisibility.isHiddenFromCurrentList(10L, spam, chipSelected))
        }
        assertEquals(all, HomeSpamVisibility.inboxThreadIds(all, emptySet()))
        assertEquals(emptyList<Long>(), HomeSpamVisibility.spamThreadIdsInOrder(all, emptySet()))
    }

    @Test
    fun `a report never deletes a row, it only moves where it is listed`() {
        // The union of both views must still be the full set: nothing is lost.
        val inbox = HomeSpamVisibility.inboxThreadIds(all, spam)
        val spamRows = HomeSpamVisibility.spamThreadIdsInOrder(all, spam)
        assertEquals(all.toSet(), (inbox + spamRows).toSet())
        assertEquals("a thread must not be listed twice", all.size, inbox.size + spamRows.size)
    }

    @Test
    fun `not-spam with an empty report set keeps the inbox untouched`() {
        // Undo path: clearing the flag is what restores the row; while the set is
        // empty the visibility rules must be a no-op for every thread.
        all.forEach { threadId ->
            assertFalse(HomeSpamVisibility.isHiddenFromCurrentList(threadId, emptySet(), false))
        }
    }
}
