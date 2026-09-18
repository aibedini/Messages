package com.autonomousone.messages

import com.autonomousone.messages.data.PendingExactRepairs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A failed exact provider read keeps its identity for a bounded retry instead of
 * being discarded (and instead of being misread as a deletion).
 *
 * WRITTEN BUT NOT EXECUTED.
 */
class PendingExactRepairsTest {

    @Before
    fun setUp() {
        PendingExactRepairs.clearForTest()
    }

    @Test
    fun `a failed exact read is retained and becomes due after its backoff`() {
        PendingExactRepairs.note(PendingExactRepairs.Source.SMS, 123, now = 1000)

        assertTrue("not due yet", PendingExactRepairs.due(1000).isEmpty())
        val later = PendingExactRepairs.due(1000 + 120_000)
        assertEquals(1, later.size)
        assertEquals(123L, later[0].providerId)
        assertEquals(PendingExactRepairs.Source.SMS, later[0].source)
    }

    @Test
    fun `sms and mms identities stay independent`() {
        PendingExactRepairs.note(PendingExactRepairs.Source.SMS, 123, 1000)
        PendingExactRepairs.note(PendingExactRepairs.Source.MMS, 123, 1000)

        val due = PendingExactRepairs.due(1000 + 120_000)
        assertEquals(2, due.size)
        assertEquals(
            setOf(PendingExactRepairs.Source.SMS, PendingExactRepairs.Source.MMS),
            due.map { it.source }.toSet()
        )
    }

    @Test
    fun `clearing one identity leaves the other`() {
        PendingExactRepairs.note(PendingExactRepairs.Source.SMS, 123, 1000)
        PendingExactRepairs.note(PendingExactRepairs.Source.MMS, 123, 1000)

        PendingExactRepairs.clear(PendingExactRepairs.Source.SMS, 123)

        val due = PendingExactRepairs.due(1000 + 120_000)
        assertEquals(1, due.size)
        assertEquals(PendingExactRepairs.Source.MMS, due[0].source)
    }

    @Test
    fun `repeated failures back off instead of hot looping`() {
        PendingExactRepairs.note(PendingExactRepairs.Source.SMS, 7, 1000)
        PendingExactRepairs.note(PendingExactRepairs.Source.SMS, 7, 2000)
        PendingExactRepairs.note(PendingExactRepairs.Source.SMS, 7, 3000)

        val entry = PendingExactRepairs.due(1_000_000)[0]
        assertEquals(3, entry.attempts)
        assertTrue("third failure waits longer than the first", entry.nextRetryAt > 3000 + 1000)
    }

    @Test
    fun `non-positive ids are ignored`() {
        PendingExactRepairs.note(PendingExactRepairs.Source.SMS, 0, 1000)
        PendingExactRepairs.note(PendingExactRepairs.Source.MMS, -1, 1000)
        assertFalse(PendingExactRepairs.due(1_000_000).isNotEmpty())
    }
}
