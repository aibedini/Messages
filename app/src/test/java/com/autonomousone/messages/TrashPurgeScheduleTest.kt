package com.autonomousone.messages

import com.autonomousone.messages.trash.TrashPurgeScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The purge SCHEDULING policy (v3.4.0 FEATURE 8), pinned as a pure function.
 *
 * The policy is what keeps the ONE unique worker honest:
 *  - it always points at the earliest persisted deadline (never a guess);
 *  - a deadline in the past runs NOW rather than being dropped;
 *  - a FAILED provider delete is retried after a bounded backoff instead of in a
 *    tight loop (its tombstone keeps a `purgeAt` in the past, so scheduling from
 *    the raw deadline would spin);
 *  - an empty Trash cancels the pending job instead of leaving a no-op run.
 */
class TrashPurgeScheduleTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `an empty trash schedules nothing`() {
        assertNull(TrashPurgeScheduler.nextRunAt(earliestPurgeAt = null, failed = 0, now = now))
    }

    @Test
    fun `a future deadline is scheduled exactly at that deadline`() {
        val deadline = now + 30L * 24 * 60 * 60 * 1000
        assertEquals(deadline, TrashPurgeScheduler.nextRunAt(deadline, failed = 0, now = now))
    }

    @Test
    fun `an elapsed deadline runs immediately instead of being dropped`() {
        assertEquals(now, TrashPurgeScheduler.nextRunAt(now - 1, failed = 0, now = now))
        assertEquals(now, TrashPurgeScheduler.nextRunAt(now, failed = 0, now = now))
    }

    @Test
    fun `a failed purge is retried after the bounded backoff, never immediately`() {
        assertEquals(
            now + TrashPurgeScheduler.RETRY_BACKOFF_MS,
            TrashPurgeScheduler.nextRunAt(earliestPurgeAt = now - 1, failed = 1, now = now)
        )
        assertEquals(
            "the same backoff applies even when nothing else is due",
            TrashPurgeScheduler.RETRY_BACKOFF_MS,
            TrashPurgeScheduler.nextRunAt(earliestPurgeAt = null, failed = 2, now = now)!! - now
        )
    }

    @Test
    fun `the backoff is long enough to be a delay and short enough to be a retry`() {
        assertEquals(15L * 60 * 1000, TrashPurgeScheduler.RETRY_BACKOFF_MS)
        assertEquals("one unique work name for the whole queue", "trash_purge", TrashPurgeScheduler.WORK_NAME)
        assertEquals(
            "the worker batches exactly like the repository so one run stays bounded",
            com.autonomousone.messages.repository.TrashRepository.PURGE_BATCH_LIMIT,
            TrashPurgeScheduler.BATCH_LIMIT
        )
    }
}
