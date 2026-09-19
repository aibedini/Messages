package com.autonomousone.messages

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SINGLE-FLOW GUARANTEE for conversation deletion (v3.4.0 FEATURE 8).
 *
 * The feature exists because the old flow was WRONG in a way no behavioural test
 * of the new code can catch: it hid the row optimistically and then permanently
 * deleted the thread from the provider after 4 seconds unless the user managed to
 * press Undo. Re-introducing that path would not fail any of the new tests, so it
 * is pinned here at the SOURCE level — where a second delete flow would have to
 * appear.
 *
 * These are deliberately textual assertions about THIS repository's own files
 * (the same technique `UxSqlLiteralDriftTest` uses to pin SQL literals that no
 * runtime engine can observe).
 */
class TrashSingleFlowTest {

    private val main = "src/main/java/com/autonomousone/messages"

    private fun source(path: String): String {
        val file = File(main, path)
        assertTrue("missing source file ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    private val homeViewModel by lazy { source("viewmodel/HomeViewModel.kt") }

    @Test
    fun `the old in-memory 4-second permanent delete flow no longer exists`() {
        assertFalse(
            "a pending-delete job map is the old flow's signature",
            homeViewModel.contains("pendingDeletes")
        )
        assertFalse(
            "the delay parameter only existed to postpone the permanent delete",
            homeViewModel.contains("delayMs")
        )
        assertFalse(
            "Home must never permanently delete a conversation at all",
            homeViewModel.contains("repository.deleteThread(")
        )
        assertFalse(
            "the old flow deleted the Room thread instead of writing a tombstone",
            homeViewModel.contains("deleteThreadFromShadow")
        )
    }

    @Test
    fun `delete writes the durable tombstone and undo clears it, with no provider access`() {
        assertTrue(
            "the delete must go through the ONE durable tombstone writer",
            homeViewModel.contains("trashRepository.moveToTrash(")
        )
        assertTrue(
            "the cutoff must be the canonical newest row at trash time",
            homeViewModel.contains(".newestForThread(threadId)")
        )
        assertTrue(
            "Undo is a local tombstone delete (no provider re-insert)",
            homeViewModel.contains("trashRepository.restore(threadId)")
        )
        assertTrue(
            "deleteConversation reports the durable outcome to the UI",
            homeViewModel.contains("suspend fun deleteConversation(sms: Sms): Boolean")
        )
        assertTrue(
            "undoDelete reports whether the conversation really came back",
            homeViewModel.contains("suspend fun undoDelete(sms: Sms): Boolean")
        )
        assertTrue(
            "the projection is rebuilt from the mirror on both directions",
            homeViewModel.contains("rebuildThreadProjectionFromMirror")
        )
    }

    @Test
    fun `the Home snackbar reports the trash outcome instead of claiming a delete`() {
        val homeScreen = source("ui/screens/HomeScreen.kt")
        assertTrue(homeScreen.contains("R.string.trash_snackbar_moved"))
        assertTrue(homeScreen.contains("R.string.trash_snackbar_move_failed"))
        assertTrue(homeScreen.contains("R.string.trash_snackbar_restored"))
        assertTrue(
            "the destructive action is still confirmed by a dialog",
            homeScreen.contains("HomeConfirmDialog(")
        )
        assertFalse(
            "no screen may call the removed delay-based API",
            homeScreen.contains("deleteConversation(target, ")
        )
    }

    @Test
    fun `the ACTIVE-UI projection is built from ACTIVE rows, never from the raw mirror`() {
        val coordinator = source("data/TelephonySyncCoordinator.kt")
        assertTrue(coordinator.contains("newestActiveForThread"))
        assertTrue(coordinator.contains("countActiveUnread"))
        assertTrue(coordinator.contains("newestActivePerThread"))
        assertTrue(coordinator.contains("unreadActiveCountsByThread"))
        assertFalse(
            "a projection builder reading the raw newest row would resurrect a trashed conversation",
            coordinator.contains("dao.newestForThread(")
        )
        assertFalse(coordinator.contains("dao.countUnread("))

        val daos = source("data/Daos.kt")
        assertTrue("Home reads the ACTIVE conversation list", daos.contains("observeAllActive"))
        assertTrue("the cold-start paint uses the same rule", daos.contains("allActive"))
        assertTrue("the conversation tail is ACTIVE-UI filtered", daos.contains("observeActiveThread"))
    }

    @Test
    fun `the purge can only ever use the strict range delete`() {
        val purger = source("repository/TrashProviderPurger.kt")
        assertTrue(purger.contains("deleteThreadSnapshotStrict"))
        assertFalse(
            "a purge must never rescan (or clear) a whole thread",
            purger.contains("querySmsThreadStrict") || purger.contains("deleteThread(")
        )

        val repository = source("repository/SmsRepository.kt")
        assertTrue(
            "the range is built by the tested, Android-free planner",
            repository.contains("TrashProviderRange.selectionFor(")
        )
        assertTrue(
            "the delete is verified before it is reported as successful",
            repository.contains("sourceSnapshotExistsStrict")
        )
        assertTrue(
            "the strict path returns a typed failure instead of swallowing it",
            repository.contains("SourceWriteResult.Failure(e.javaClass.simpleName, e)")
        )

        val trashRepository = source("repository/TrashRepository.kt")
        assertTrue(
            "Room state is removed only after the provider confirmed",
            trashRepository.contains("if (!providerDeleted)")
        )
    }

    @Test
    fun `one purge worker is scheduled through the existing work infrastructure`() {
        val scheduler = source("trash/TrashPurgeScheduler.kt")
        assertTrue(scheduler.contains("enqueueUniqueWork"))
        assertTrue(scheduler.contains("WORK_NAME = \"trash_purge\""))
        assertTrue(
            "the worker reschedules the next due time from persisted state",
            scheduler.contains("rescheduleAfterRun")
        )
        assertFalse(
            "a periodic worker would be a full-table scan by another name",
            scheduler.contains("PeriodicWorkRequest")
        )
    }
}
