package com.autonomousone.messages

import com.autonomousone.messages.data.ConversationPreferenceEntity
import com.autonomousone.messages.data.MessageKey
import com.autonomousone.messages.repository.BulkActionRepository
import com.autonomousone.messages.repository.BulkConcurrency
import com.autonomousone.messages.repository.BulkMessageStateWriter
import com.autonomousone.messages.repository.BulkThreadReader
import com.autonomousone.messages.repository.BulkThreadStateWriter
import com.autonomousone.messages.repository.BulkThreadTrashWriter
import com.autonomousone.messages.repository.MessageIdentity
import com.autonomousone.messages.repository.TrashRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * FEATURE 10 — the bulk engine, exercised with fakes so every rule is pinned
 * without Room, a ContentResolver or the Compose main thread:
 *  - batching (one call per set, never one call per item),
 *  - partial-failure reporting that never claims full success,
 *  - composite message identity,
 *  - quiet no-op safety for an empty selection.
 */
class BulkActionRepositoryTest {

    private companion object {
        const val NOW = 1_700_000_000_000L
    }

    private class FakeThreadState : BulkThreadStateWriter {
        val manualUnread: MutableList<Pair<Set<Long>, Boolean>> =
            Collections.synchronizedList(mutableListOf())
        val muted: MutableList<Pair<Set<Long>, Long>> = Collections.synchronizedList(mutableListOf())
        val archived: MutableList<Pair<Set<Long>, Boolean>> =
            Collections.synchronizedList(mutableListOf())
        val pinned: MutableList<Pair<Set<Long>, Boolean>> =
            Collections.synchronizedList(mutableListOf())
        var failManualUnread = false
        var failArchive = false

        override suspend fun setManualUnread(threadIds: Set<Long>, unread: Boolean, now: Long) {
            if (failManualUnread) throw IllegalStateException("room transaction failed")
            manualUnread += threadIds to unread
        }

        override suspend fun setMutedUntil(threadIds: Set<Long>, until: Long, now: Long) {
            muted += threadIds to until
        }

        override suspend fun setArchived(threadIds: Set<Long>, archived: Boolean) {
            if (failArchive) throw IllegalStateException("archive commit failed")
            this.archived += threadIds to archived
        }

        override suspend fun setPinned(threadIds: Set<Long>, pinned: Boolean) {
            this.pinned += threadIds to pinned
        }
    }

    private class FakeThreadTrash : BulkThreadTrashWriter {
        val moved = Collections.synchronizedList(mutableListOf<Set<Long>>())
        val restored = Collections.synchronizedList(mutableListOf<Set<Long>>())
        var failing: Set<Long> = emptySet()
        var throwOnBatch = false

        override suspend fun moveToTrash(threadIds: Set<Long>, now: Long): Set<Long> {
            if (throwOnBatch) throw IllegalStateException("tombstone batch failed")
            moved += threadIds
            return threadIds.filterTo(LinkedHashSet()) { it in failing }
        }

        override suspend fun restore(threadIds: Set<Long>) {
            restored += threadIds
        }
    }

    private class FakeReader(private val failing: Set<Long> = emptySet()) : BulkThreadReader {
        val calls = Collections.synchronizedList(mutableListOf<Long>())

        override suspend fun markThreadRead(threadId: Long): Boolean {
            calls += threadId
            return threadId !in failing
        }
    }

    private class FakeMessageState(
        private val threadsByKey: Map<MessageKey, Long>
    ) : BulkMessageStateWriter {
        val starred =
            Collections.synchronizedList(mutableListOf<Triple<List<MessageKey>, Long, Boolean>>())
        val trashed =
            Collections.synchronizedList(mutableListOf<Triple<List<MessageKey>, Long, Long>>())
        var failingStarThreads: Set<Long> = emptySet()
        var resolveFails = false
        var resolveCalls = 0

        override suspend fun threadIdsFor(keys: Collection<MessageKey>): Map<MessageKey, Long> {
            resolveCalls++
            if (resolveFails) throw IllegalStateException("resolve failed")
            return threadsByKey.filterKeys { it in keys }
        }

        override suspend fun setStarredForThread(
            keys: Collection<MessageKey>,
            threadId: Long,
            starred: Boolean,
            now: Long
        ): Boolean {
            if (threadId in failingStarThreads) return false
            this.starred += Triple(keys.toList(), threadId, starred)
            return true
        }

        override suspend fun markTrashedForThread(
            keys: Collection<MessageKey>,
            threadId: Long,
            trashedAt: Long,
            purgeAt: Long,
            now: Long
        ): Boolean {
            trashed += Triple(keys.toList(), trashedAt, purgeAt)
            return true
        }
    }

    private class Fixture(
        threadsByKey: Map<MessageKey, Long> = emptyMap(),
        reader: FakeReader = FakeReader()
    ) {
        val threadState = FakeThreadState()
        val trash = FakeThreadTrash()
        val theReader = reader
        val messageState = FakeMessageState(threadsByKey)
        val repository = BulkActionRepository(
            threadState = threadState,
            threadTrash = trash,
            threadReader = theReader,
            messageState = messageState,
            clock = { NOW },
            maxParallel = BulkConcurrency.MAX_PARALLEL
        )
    }

    // ── Read / unread ───────────────────────────────────────────────────────

    @Test
    fun `markUnread writes the bookmark in ONE batch and never touches the provider read path`() =
        runBlocking {
            val f = Fixture()

            val result = f.repository.markUnread(setOf(1L, 2L, 3L))

            assertTrue(result.isFullSuccess)
            assertEquals(3, result.succeeded)
            assertEquals(listOf(setOf(1L, 2L, 3L) to true), f.threadState.manualUnread)
            // "Mark as unread" is a UI bookmark: the provider READ column is
            // NEVER rewritten back to 0, so no read-path call may happen.
            assertTrue(f.theReader.calls.isEmpty())
        }

    @Test
    fun `markUnread reports a whole-batch failure as nothing succeeded`() = runBlocking {
        val f = Fixture()
        f.threadState.failManualUnread = true

        val result = f.repository.markUnread(setOf(1L, 2L))

        assertFalse(result.isFullSuccess)
        assertEquals(0, result.succeeded)
        assertEquals(2, result.failedCount)
        assertEquals(setOf(1L, 2L), result.failedThreadIds())
    }

    @Test
    fun `markRead clears the bookmark and runs the existing read path per thread`() = runBlocking {
        val f = Fixture()

        val result = f.repository.markRead(setOf(4L, 5L))

        assertTrue(result.isFullSuccess)
        assertEquals(listOf(setOf(4L, 5L) to false), f.threadState.manualUnread)
        assertEquals(setOf(4L, 5L), f.theReader.calls.toSet())
        assertEquals(2, f.theReader.calls.size)
    }

    @Test
    fun `markRead reports a partial failure with the exact counts`() = runBlocking {
        val f = Fixture(reader = FakeReader(failing = setOf(5L)))

        val result = f.repository.markRead(setOf(4L, 5L, 6L))

        assertFalse(result.isFullSuccess)
        assertTrue(result.isPartial)
        assertEquals(2, result.succeeded)
        assertEquals(1, result.failedCount)
        assertEquals(setOf(5L), result.failedThreadIds())
    }

    @Test
    fun `markRead never reports success when the bookmark write failed`() = runBlocking {
        val f = Fixture()
        f.threadState.failManualUnread = true

        val result = f.repository.markRead(setOf(4L))

        assertFalse(result.isFullSuccess)
        assertEquals(0, result.succeeded)
        assertEquals(1, result.failedCount)
    }

    // ── Archive / unarchive ─────────────────────────────────────────────────

    @Test
    fun `archive and unarchive are one batch call each`() = runBlocking {
        val f = Fixture()

        val archived = f.repository.archive(setOf(1L, 2L))
        val unarchived = f.repository.unarchive(setOf(1L, 2L))

        assertTrue(archived.isFullSuccess)
        assertTrue(unarchived.isFullSuccess)
        assertEquals(
            listOf(setOf(1L, 2L) to true, setOf(1L, 2L) to false),
            f.threadState.archived
        )
    }

    @Test
    fun `archive failure is reported, never swallowed`() = runBlocking {
        val f = Fixture()
        f.threadState.failArchive = true

        val result = f.repository.archive(setOf(1L, 2L))

        assertFalse(result.isFullSuccess)
        assertEquals(0, result.succeeded)
        assertEquals(2, result.failedCount)
    }

    // ── Mute / unmute ───────────────────────────────────────────────────────

    @Test
    fun `mute and unmute carry the requested expiry`() = runBlocking {
        val f = Fixture()

        f.repository.mute(setOf(1L, 2L), until = ConversationPreferenceEntity.MUTE_FOREVER)
        f.repository.unmute(setOf(1L, 2L))

        assertEquals(
            listOf(
                setOf(1L, 2L) to ConversationPreferenceEntity.MUTE_FOREVER,
                setOf(1L, 2L) to 0L
            ),
            f.threadState.muted
        )
    }

    // ── Pin / unpin ─────────────────────────────────────────────────────────

    @Test
    fun `pin is a single batched commit for both directions`() = runBlocking {
        val f = Fixture()

        f.repository.pin(setOf(1L, 2L), pinned = true)
        f.repository.pin(setOf(2L), pinned = false)

        assertEquals(listOf(setOf(1L, 2L) to true, setOf(2L) to false), f.threadState.pinned)
    }

    // ── Trash ───────────────────────────────────────────────────────────────

    @Test
    fun `conversation trash writes ONE tombstone batch and reports partial failure`() = runBlocking {
        val f = Fixture()
        f.trash.failing = setOf(2L)

        val result = f.repository.moveToTrash(setOf(1L, 2L, 3L))

        assertFalse(result.isFullSuccess)
        assertEquals(2, result.succeeded)
        assertEquals(1, result.failedCount)
        assertEquals(setOf(2L), result.failedThreadIds())
        assertEquals(listOf(setOf(1L, 2L, 3L)), f.trash.moved)
    }

    @Test
    fun `a failed tombstone batch never claims success`() = runBlocking {
        val f = Fixture()
        f.trash.throwOnBatch = true

        val result = f.repository.moveToTrash(setOf(1L, 2L))

        assertFalse(result.isFullSuccess)
        assertTrue(result.isCompleteFailure)
        assertEquals(0, result.succeeded)
        assertEquals(2, result.failedCount)
    }

    @Test
    fun `trash undo clears the tombstones`() = runBlocking {
        val f = Fixture()

        val result = f.repository.restoreFromTrash(setOf(1L, 2L))

        assertTrue(result.isFullSuccess)
        assertEquals(listOf(setOf(1L, 2L)), f.trash.restored)
    }

    // ── Message star / trash ────────────────────────────────────────────────

    @Test
    fun `star keeps SMS 100 and MMS 100 apart and resolves each owning thread`() = runBlocking {
        val sms100 = MessageKey(MessageIdentity.SOURCE_SMS, 100L)
        val mms100 = MessageKey(MessageIdentity.SOURCE_MMS, 100L)
        val sms101 = MessageKey(MessageIdentity.SOURCE_SMS, 101L)
        val f = Fixture(
            threadsByKey = mapOf(sms100 to 7L, mms100 to 9L, sms101 to 7L)
        )

        val result = f.repository.star(setOf(sms100, mms100, sms101), starred = true)

        assertTrue(result.isFullSuccess)
        assertEquals(3, result.succeeded)
        // ONE resolve pass, then ONE write batch per owning thread.
        assertEquals(1, f.messageState.resolveCalls)
        val byThread = f.messageState.starred.groupBy { it.second }
        assertEquals(setOf(7L, 9L), byThread.keys)
        assertEquals(2, byThread.getValue(7L).first().first.size)
        assertEquals(listOf(mms100), byThread.getValue(9L).first().first)
        assertTrue(f.messageState.starred.all { it.third })
    }

    @Test
    fun `unstar is the same batch with starred false`() = runBlocking {
        val key = MessageKey(MessageIdentity.SOURCE_SMS, 100L)
        val f = Fixture(threadsByKey = mapOf(key to 7L))

        val result = f.repository.star(setOf(key), starred = false)

        assertTrue(result.isFullSuccess)
        assertFalse(f.messageState.starred.single().third)
    }

    @Test
    fun `a key with no Room row is reported failed, never silently dropped`() = runBlocking {
        val sms100 = MessageKey(MessageIdentity.SOURCE_SMS, 100L)
        val ghost = MessageKey(MessageIdentity.SOURCE_MMS, 555L)
        val f = Fixture(threadsByKey = mapOf(sms100 to 7L))

        val result = f.repository.star(setOf(sms100, ghost), starred = true)

        assertFalse(result.isFullSuccess)
        assertEquals(1, result.succeeded)
        assertEquals(1, result.failedCount)
        assertEquals(MessageIdentity.SOURCE_MMS, result.failed.single().source)
        assertEquals(555L, result.failed.single().providerId)
    }

    @Test
    fun `a failing user-state batch fails only that thread's messages`() = runBlocking {
        val sms7 = MessageKey(MessageIdentity.SOURCE_SMS, 1L)
        val mms7 = MessageKey(MessageIdentity.SOURCE_MMS, 2L)
        val sms9 = MessageKey(MessageIdentity.SOURCE_SMS, 3L)
        val f = Fixture(threadsByKey = mapOf(sms7 to 7L, mms7 to 7L, sms9 to 9L))
        f.messageState.failingStarThreads = setOf(7L)

        val result = f.repository.star(setOf(sms7, mms7, sms9), starred = true)

        assertFalse(result.isFullSuccess)
        assertEquals(1, result.succeeded)
        assertEquals(2, result.failedCount)
        assertEquals(setOf(7L), result.failed.mapNotNull { it.threadId }.toSet())
    }

    @Test
    fun `a resolve failure fails every key instead of guessing a thread`() = runBlocking {
        val key = MessageKey(MessageIdentity.SOURCE_SMS, 100L)
        val f = Fixture(threadsByKey = mapOf(key to 7L))
        f.messageState.resolveFails = true

        val result = f.repository.star(setOf(key), starred = true)

        assertTrue(result.isCompleteFailure)
        assertEquals(0, result.succeeded)
        assertTrue(f.messageState.starred.isEmpty())
    }

    @Test
    fun `message trash writes the individual trash state with the retention window`() = runBlocking {
        val key = MessageKey(MessageIdentity.SOURCE_SMS, 100L)
        val f = Fixture(threadsByKey = mapOf(key to 7L))

        val result = f.repository.moveToTrash(listOf(key))

        assertTrue(result.isFullSuccess)
        val (keys, trashedAt, purgeAt) = f.messageState.trashed.single()
        assertEquals(listOf(key), keys)
        assertEquals(NOW, trashedAt)
        assertEquals(NOW + TrashRepository.RETENTION_MILLIS, purgeAt)
    }

    // ── Empty selection / invalid ids ───────────────────────────────────────

    @Test
    fun `an empty conversation selection is a quiet no-op`() = runBlocking {
        val f = Fixture()

        val unread = f.repository.markUnread(emptySet<Long>())
        val read = f.repository.markRead(emptySet<Long>())
        val archive = f.repository.archive(emptySet<Long>())
        val trash = f.repository.moveToTrash(emptySet<Long>())

        listOf(unread, read, archive, trash).forEach {
            assertTrue(it.isEmpty)
            assertEquals(0, it.requested)
            assertEquals(0, it.succeeded)
            assertTrue(it.failed.isEmpty())
            assertFalse(it.isFullSuccess)
        }
        assertTrue(f.threadState.manualUnread.isEmpty())
        assertTrue(f.threadState.archived.isEmpty())
        assertTrue(f.theReader.calls.isEmpty())
        assertTrue(f.trash.moved.isEmpty())
    }

    @Test
    fun `an empty message selection is a quiet no-op`() = runBlocking {
        val f = Fixture()

        val star = f.repository.star(emptySet<MessageKey>(), starred = true)
        val trash = f.repository.moveToTrash(emptyList<MessageKey>())

        assertTrue(star.isEmpty)
        assertTrue(trash.isEmpty)
        assertEquals(0, f.messageState.resolveCalls)
        assertTrue(f.messageState.starred.isEmpty())
        assertTrue(f.messageState.trashed.isEmpty())
    }

    @Test
    fun `non-positive thread ids are never requested`() = runBlocking {
        val f = Fixture()

        val result = f.repository.markUnread(setOf(0L, -3L, 8L))

        assertEquals(1, result.requested)
        assertEquals(listOf(setOf(8L) to true), f.threadState.manualUnread)
    }

    @Test
    fun `a message key without a provider id is never requested`() = runBlocking {
        val f = Fixture(threadsByKey = emptyMap())

        val result = f.repository.star(setOf(MessageKey(MessageIdentity.SOURCE_SMS, 0L)), true)

        assertTrue(result.isEmpty)
        assertEquals(0, f.messageState.resolveCalls)
    }
}
