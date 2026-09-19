package com.autonomousone.messages

import com.autonomousone.messages.data.MessageEntity
import com.autonomousone.messages.data.TrashedThreadEntity
import com.autonomousone.messages.repository.TrashRepository
import com.autonomousone.messages.repository.TrashStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The TRASH contract (v3.4.0 FEATURE 8), exercised against the REAL
 * [TrashRepository] with an in-memory store and a fake provider purger.
 *
 * What these tests exist to prevent:
 *
 *  1. a "delete" that destroys provider rows at trash time (the old 4-second
 *     flow) — `moveToTrash`/`restore` must perform ZERO provider writes;
 *  2. trash state that only lives in memory — a repository rebuilt over the SAME
 *     store (a new process) must still see the tombstone;
 *  3. a purge that clears Room state (or claims success) while the provider
 *     delete FAILED;
 *  4. a purge that removes messages NEWER than the tombstone cutoff.
 */
class TrashRepositoryTest {

    // ── Fakes ──────────────────────────────────────────────────────────────

    /** In-memory tombstone table. `deleteSnapshot` records the purge order. */
    private class FakeTrashStore : TrashStore {
        val tombstones = LinkedHashMap<Long, TrashedThreadEntity>()
        val snapshotDeletes = mutableListOf<Long>()
        var failSnapshotDelete = false

        private val flow = MutableStateFlow<List<TrashedThreadEntity>>(emptyList())

        private fun publish() {
            flow.value = tombstones.values.sortedByDescending { it.deletedAt }
        }

        override fun observeAll(): Flow<List<TrashedThreadEntity>> = flow

        override suspend fun get(threadId: Long): TrashedThreadEntity? = tombstones[threadId]

        override fun observe(threadId: Long): Flow<TrashedThreadEntity?> =
            flow.map { list -> list.firstOrNull { it.threadId == threadId } }

        override suspend fun allNewestFirst(): List<TrashedThreadEntity> =
            tombstones.values.sortedByDescending { it.deletedAt }

        override suspend fun trashedThreadIds(): List<Long> = tombstones.keys.toList()

        override suspend fun dueForPurge(now: Long, limit: Int): List<TrashedThreadEntity> =
            tombstones.values.filter { it.purgeAt <= now }.sortedBy { it.purgeAt }.take(limit)

        override suspend fun earliestPurgeAt(): Long? =
            tombstones.values.minOfOrNull { it.purgeAt }

        override suspend fun upsert(tombstone: TrashedThreadEntity) {
            tombstones[tombstone.threadId] = tombstone
            publish()
        }

        override suspend fun delete(threadId: Long) {
            tombstones.remove(threadId)
            publish()
        }

        override suspend fun deleteAll(threadIds: List<Long>) {
            threadIds.forEach { tombstones.remove(it) }
            publish()
        }

        override suspend fun deleteSnapshot(tombstone: TrashedThreadEntity) {
            if (failSnapshotDelete) error("mirror cleanup failed")
            snapshotDeletes += tombstone.threadId
        }
    }

    /** Records every provider purge; can refuse per thread id. */
    private class FakePurger(private val refuse: MutableSet<Long> = mutableSetOf()) :
        TrashRepository.ProviderPurger {
        val calls = mutableListOf<Long>()

        fun refuse(threadId: Long) {
            refuse += threadId
        }

        fun allow(threadId: Long) {
            refuse -= threadId
        }

        override suspend fun purge(tombstone: TrashedThreadEntity): Boolean {
            calls += tombstone.threadId
            return tombstone.threadId !in refuse
        }
    }

    private fun message(
        providerId: Long,
        date: Long,
        source: String = MessageEntity.SOURCE_SMS
    ) = MessageEntity(
        source = source,
        providerId = providerId,
        threadId = 7L,
        normalizedAddress = "09120000000",
        rawAddress = "+989120000000",
        body = "body-$providerId",
        date = date,
        type = 1,
        status = -1,
        dateSent = 0L,
        read = true
    )

    private val day = 24L * 60 * 60 * 1000

    // ── Delete → Trash ─────────────────────────────────────────────────────

    @Test
    fun `moving a conversation to trash writes a tombstone and performs NO provider delete`() =
        runBlocking {
            val store = FakeTrashStore()
            val purger = FakePurger()
            val trash = TrashRepository(store, purger)

            val tombstone = trash.moveToTrash(7L, newestActive = message(101L, 2_000L), now = 1_000L)

            assertEquals("the cutoff is the canonical newest row at trash time", 2_000L, tombstone.cutoffDate)
            assertEquals(MessageEntity.SOURCE_SMS, tombstone.cutoffSource)
            assertEquals(101L, tombstone.cutoffProviderId)
            assertEquals(
                "the deadline is the retention window, not an in-memory timer",
                1_000L + TrashRepository.RETENTION_MILLIS,
                tombstone.purgeAt
            )
            assertEquals("the tombstone is durable Room state", tombstone, store.get(7L))
            assertTrue(
                "trashing must NOT delete provider rows — that would make Undo impossible",
                purger.calls.isEmpty()
            )
        }

    @Test
    fun `a trashed conversation hides its snapshot and keeps a NEWER message visible`() =
        runBlocking {
            val store = FakeTrashStore()
            val trash = TrashRepository(store, FakePurger())
            val tombstone = trash.moveToTrash(7L, newestActive = message(101L, 2_000L), now = 1_000L)

            assertTrue(
                "the deleted snapshot is hidden",
                !trash.isVisibleUnderTombstone(tombstone, 2_000L, MessageEntity.SOURCE_SMS, 101L)
            )
            assertTrue(
                "older history is hidden",
                !trash.isVisibleUnderTombstone(tombstone, 1_500L, MessageEntity.SOURCE_SMS, 100L)
            )
            assertTrue(
                "a genuinely NEW message after the cutoff is VISIBLE (a thread is never hidden forever)",
                trash.isVisibleUnderTombstone(tombstone, 2_001L, MessageEntity.SOURCE_SMS, 102L)
            )
        }

    @Test
    fun `an empty thread still gets a tombstone and its later first message is visible`() =
        runBlocking {
            val store = FakeTrashStore()
            val trash = TrashRepository(store, FakePurger())

            val tombstone = trash.moveToTrash(7L, newestActive = null, now = 1_000L)

            assertEquals(0L, tombstone.cutoffDate)
            assertEquals(0L, tombstone.cutoffProviderId)
            assertTrue(
                "the first message of an empty thread must be visible",
                trash.isVisibleUnderTombstone(tombstone, 5_000L, MessageEntity.SOURCE_SMS, 1L)
            )
        }

    @Test
    fun `undo restores immediately with no provider write`() = runBlocking {
        val store = FakeTrashStore()
        val purger = FakePurger()
        val trash = TrashRepository(store, purger)
        trash.moveToTrash(7L, newestActive = message(101L, 2_000L), now = 1_000L)

        trash.restore(7L)

        assertNull("the tombstone is gone, so the conversation is active again", store.get(7L))
        assertTrue("the provider rows were never touched, so nothing is re-inserted", purger.calls.isEmpty())
        assertTrue(
            "with no tombstone every row is visible again",
            trash.isVisibleUnderTombstone(null, 2_000L, MessageEntity.SOURCE_SMS, 101L)
        )
    }

    /**
     * PROCESS DEATH: the trash state must be DURABLE. A brand-new repository over
     * the same Room store is exactly what the next process gets; an in-memory map
     * would come back empty here and the user's "deleted" conversation would
     * reappear.
     */
    @Test
    fun `trash state survives a restart because it lives in the store, not in memory`() = runBlocking {
        val store = FakeTrashStore()
        val firstProcess = TrashRepository(store, FakePurger())
        firstProcess.moveToTrash(7L, newestActive = message(101L, 2_000L), now = 1_000L)

        // ── process death ──────────────────────────────────────────────────
        val secondProcess = TrashRepository(store, FakePurger())

        val survived = secondProcess.get(7L)
        assertNotNull("the tombstone must survive a restart", survived)
        assertEquals(2_000L, survived!!.cutoffDate)
        assertEquals(listOf(7L), secondProcess.trashedThreadIds())
        assertFalse(secondProcess.isEmpty())

        // ...and Restore still works in the new process.
        secondProcess.restore(7L)
        assertTrue(secondProcess.isEmpty())
    }

    // ── Purge ──────────────────────────────────────────────────────────────

    @Test
    fun `a due purse deletes the provider range first and only then the room state`() = runBlocking {
        val store = FakeTrashStore()
        val purger = FakePurger()
        val trash = TrashRepository(store, purger)
        val now = 1_000L
        trash.moveToTrash(7L, newestActive = message(101L, 2_000L), now = now - day * 31)

        val (purged, failed) = trash.purgeDue(now)

        assertEquals(1, purged)
        assertEquals(0, failed)
        assertEquals("the provider purge ran exactly once", listOf(7L), purger.calls)
        assertEquals("Room mirror rows were removed only after the provider confirmed", listOf(7L), store.snapshotDeletes)
        assertNull("the tombstone is gone after a confirmed purge", store.get(7L))
    }

    /**
     * A provider failure must keep EVERYTHING: the tombstone, the Room rows and
     * the user's Trash entry — and it must never be reported as a successful
     * delete.
     */
    @Test
    fun `purge failure keeps the state and retries later`() = runBlocking {
        val store = FakeTrashStore()
        val purger = FakePurger(mutableSetOf(7L))
        val trash = TrashRepository(store, purger)
        val now = 1_000L
        trash.moveToTrash(7L, newestActive = message(101L, 2_000L), now = now - day * 31)

        val (purged, failed) = trash.purgeDue(now)

        assertEquals(0, purged)
        assertEquals(1, failed)
        assertNotNull("the tombstone is retained for retry", store.get(7L))
        assertTrue("no Room row may be removed without a confirmed provider delete", store.snapshotDeletes.isEmpty())

        // The retry (a later run) succeeds and clears everything.
        purger.allow(7L)
        val (retriedPurged, retriedFailed) = trash.purgeDue(now + 60_000L)

        assertEquals(1, retriedPurged)
        assertEquals(0, retriedFailed)
        assertNull(store.get(7L))
        assertEquals(listOf(7L), store.snapshotDeletes)
    }

    @Test
    fun `a throwing provider purge is a failure, never a success`() = runBlocking {
        val store = FakeTrashStore()
        val trash = TrashRepository(
            store,
            TrashRepository.ProviderPurger { error("provider exploded") }
        )
        val now = 1_000L
        trash.moveToTrash(7L, newestActive = message(101L, 2_000L), now = now - day * 31)

        val (purged, failed) = trash.purgeDue(now)

        assertEquals(0, purged)
        assertEquals(1, failed)
        assertNotNull(store.get(7L))
        assertTrue(store.snapshotDeletes.isEmpty())
    }

    @Test
    fun `a local cleanup failure keeps the tombstone so the next run finishes the job`() = runBlocking {
        val store = FakeTrashStore()
        store.failSnapshotDelete = true
        val purger = FakePurger()
        val trash = TrashRepository(store, purger)
        val now = 1_000L
        trash.moveToTrash(7L, newestActive = message(101L, 2_000L), now = now - day * 31)

        val (purged, failed) = trash.purgeDue(now)
        assertEquals(0, purged)
        assertEquals(1, failed)
        assertNotNull("the tombstone survives so the cleanup can be retried", store.get(7L))

        store.failSnapshotDelete = false
        val (secondPurged, _) = trash.purgeDue(now + 1)
        assertEquals(1, secondPurged)
        assertNull(store.get(7L))
    }

    @Test
    fun `a purge only touches tombstones whose retention elapsed`() = runBlocking {
        val store = FakeTrashStore()
        val purger = FakePurger()
        val trash = TrashRepository(store, purger)
        val now = 10L * day
        trash.moveToTrash(7L, newestActive = message(101L, 2_000L), now = now)
        trash.moveToTrash(8L, newestActive = message(201L, 2_000L), now = now - day * 31)

        val (purged, failed) = trash.purgeDue(now)

        assertEquals("only the due tombstone is purged", 1, purged)
        assertEquals(0, failed)
        assertEquals(listOf(8L), purger.calls)
        assertNotNull("the still-retained conversation stays in Trash", store.get(7L))
    }

    @Test
    fun `purgeNow deletes one conversation immediately and reports the provider outcome`() = runBlocking {
        val store = FakeTrashStore()
        val purger = FakePurger()
        val trash = TrashRepository(store, purger)
        val now = 1_000L
        trash.moveToTrash(7L, newestActive = message(101L, 2_000L), now = now)

        // Still 30 days from its deadline: a purge NOW is the user's explicit
        // "Delete permanently" and must not wait for retention.
        assertTrue(trash.purgeNow(7L))
        assertNull(store.get(7L))
        assertEquals(listOf(7L), purger.calls)

        // A provider that refuses leaves the row in Trash and reports failure.
        trash.moveToTrash(8L, newestActive = message(201L, 2_000L), now = now)
        purger.refuse(8L)
        assertFalse(trash.purgeNow(8L))
        assertNotNull("a refused permanent delete keeps the conversation in Trash", store.get(8L))

        // Nothing to purge is success, not failure (idempotent).
        assertTrue(trash.purgeNow(999L))
    }

    @Test
    fun `empty trash purges everything in bounded batches and reports the remainder`() = runBlocking {
        val store = FakeTrashStore()
        val purger = FakePurger()
        val trash = TrashRepository(store, purger)
        val now = 10L * day
        (1L..5L).forEach { threadId ->
            trash.moveToTrash(threadId, newestActive = message(100L + threadId, 2_000L), now = now)
        }

        val first = trash.emptyTrash(now, limit = 3)
        assertEquals(3, first.purged)
        assertEquals(0, first.failed)
        assertEquals("the batch limit is reported, never silently dropped", 2, first.remaining)
        assertFalse(first.complete)

        val second = trash.emptyTrash(now, limit = 3)
        assertEquals(2, second.purged)
        assertEquals(0, second.remaining)
        assertTrue(second.complete)
        assertTrue("Trash is now genuinely empty", trash.isEmpty())
    }

    @Test
    fun `empty trash keeps only the conversations whose provider delete failed`() = runBlocking {
        val store = FakeTrashStore()
        val purger = FakePurger(mutableSetOf(2L))
        val trash = TrashRepository(store, purger)
        val now = 10L * day
        (1L..3L).forEach { threadId ->
            trash.moveToTrash(threadId, newestActive = message(100L + threadId, 2_000L), now = now)
        }

        val outcome = trash.emptyTrash(now)

        assertEquals(2, outcome.purged)
        assertEquals(1, outcome.failed)
        assertTrue(outcome.complete)
        assertEquals(listOf(2L), trash.trashedThreadIds())
    }

    // ── Retention arithmetic ───────────────────────────────────────────────

    @Test
    fun `days remaining rounds UP and is zero once the deadline passed`() {
        val store = FakeTrashStore()
        val trash = TrashRepository(store, FakePurger())
        val now = 1_000_000L
        val purgeAt = now + 30 * day

        fun tombstone(at: Long) = TrashedThreadEntity(
            threadId = 7L,
            deletedAt = now,
            purgeAt = at,
            cutoffDate = 0L,
            cutoffSource = "sms",
            cutoffProviderId = 0L
        )

        assertEquals("a full retention window reads as 30 days", 30L, trash.daysRemaining(tombstone(purgeAt), now))
        assertEquals(
            "one millisecond short of 30 days still shows 30 (never '29' and a lie)",
            30L,
            trash.daysRemaining(tombstone(purgeAt - 1), now)
        )
        assertEquals("exactly 29 days reads as 29", 29L, trash.daysRemaining(tombstone(now + 29 * day), now))
        assertEquals(
            "a partial day counts as a remaining day",
            29L,
            trash.daysRemaining(tombstone(now + 28 * day + 1), now)
        )
        assertEquals("the deadline instant is zero days left", 0L, trash.daysRemaining(tombstone(now), now))
        assertEquals("an overdue deadline never reads negative", 0L, trash.daysRemaining(tombstone(now - day), now))
    }

    @Test
    fun `trash is empty when nothing was deleted`() = runBlocking {
        val trash = TrashRepository(FakeTrashStore(), FakePurger())
        assertTrue(trash.isEmpty())
        assertTrue(trash.allTrashed().isEmpty())
        assertNull(trash.earliestPurgeAt())
        val (purged, failed) = trash.purgeDue(1_000L)
        assertEquals(0, purged)
        assertEquals(0, failed)
    }
}
