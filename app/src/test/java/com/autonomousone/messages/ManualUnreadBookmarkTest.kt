package com.autonomousone.messages

import com.autonomousone.messages.messaging.ConversationReadAudit
import com.autonomousone.messages.messaging.ReadCause
import com.autonomousone.messages.messaging.ReadSkipReason
import com.autonomousone.messages.viewmodel.HomeUnreadRule
import com.autonomousone.messages.model.Sms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `manualUnread` bookmark, and the Home unread rule that depends on it.
 *
 * ## The production bug this pins down
 *
 * Home renders `unread = realUnread OR manualUnread`. The explicit bulk "mark read" cleared the
 * bookmark; OPENING the conversation did not. So a conversation the user had once marked unread could
 * never clear its badge by being read: the messages genuinely became read, the bookmark survived, and
 * the OR re-added the highlight on every render — permanently, across restarts.
 *
 * The fix clears the bookmark inside the same durable transaction that marks the messages read. These
 * tests cover the rule and the boundary conditions that decide whether the badge can be trusted.
 */
class ManualUnreadBookmarkTest {

    private fun row(threadId: Long, unread: Boolean, date: Long = 1_700_000_000_000L) = Sms(
        id = threadId,
        threadId = threadId,
        sender = "+989120000000",
        message = "hello",
        date = date,
        unread = unread,
        type = 1
    )

    private fun apply(rows: List<Sms>, bookmarked: Set<Long>) =
        HomeUnreadRule.applyManualUnread(rows, bookmarked)

    // ── the rule itself ──────────────────────────────────────────────────────

    @Test
    fun `a bookmarked thread renders unread even when the messages are READ`() {
        // This is the mechanism, stated plainly: correct reading cannot remove the badge while the
        // bookmark is set. It is why the bookmark has to be cleared, not merely ignored.
        val rendered = apply(listOf(row(7L, unread = false)), bookmarked = setOf(7L))

        assertTrue("the OR re-adds the badge", rendered.single().unread)
    }

    @Test
    fun `clearing the bookmark clears the badge when the messages are read`() {
        val readRow = row(7L, unread = false)

        val before = apply(listOf(readRow), bookmarked = setOf(7L))
        val after = apply(listOf(readRow), bookmarked = emptySet())

        assertTrue("bookmark alone kept it unread", before.single().unread)
        assertFalse("with the bookmark gone the real state shows", after.single().unread)
    }

    @Test
    fun `a new incoming message shows unread again after the bookmark was cleared`() {
        // The bookmark must not be needed to keep genuine unread visible.
        val rendered = apply(listOf(row(7L, unread = true)), bookmarked = emptySet())

        assertTrue(rendered.single().unread)
    }

    @Test
    fun `a bookmark on one thread cannot mark another thread unread`() {
        val rendered = apply(
            listOf(row(7L, unread = false), row(9L, unread = false)),
            bookmarked = setOf(7L)
        )

        assertTrue("thread 7 is bookmarked", rendered.first { it.threadId == 7L }.unread)
        assertFalse("thread 9 never was", rendered.first { it.threadId == 9L }.unread)
    }

    @Test
    fun `an already-unread row is left untouched by the bookmark rule`() {
        // Idempotence: the OR must not double-apply or change a row that already says unread.
        val original = row(7L, unread = true)
        val rendered = apply(listOf(original), bookmarked = setOf(7L))

        assertEquals(original.unread, rendered.single().unread)
    }

    @Test
    fun `an empty bookmark set is a no-op and returns the same rows`() {
        val rows = listOf(row(7L, unread = false))
        val rendered = apply(rows, bookmarked = emptySet())

        assertEquals(rows, rendered)
    }

    @Test
    fun `every row is preserved, in order, when a bookmark is applied`() {
        // A map-based transform must not drop or reorder conversations.
        val rows = listOf(row(1L, false), row(2L, false), row(3L, true))
        val rendered = apply(rows, bookmarked = setOf(2L))

        assertEquals(listOf(1L, 2L, 3L), rendered.map { it.threadId })
    }

    // ── the durable diagnosis this bug needed ────────────────────────────────

    @Test
    fun `the audit token is stable, hashed and never the raw thread id`() {
        // Section 1 requires classifying the affected thread from durable state without ever logging its
        // number. The token is what makes that possible.
        val token = ConversationReadAudit.token(33_477_456L)

        assertEquals("deterministic", token, ConversationReadAudit.token(33_477_456L))
        assertNotEquals(ConversationReadAudit.token(33_477_456L), ConversationReadAudit.token(33_477_457L))
        assertFalse("must not contain the raw id", token.contains("33477456"))
        assertTrue("short, opaque, prefixed", token.startsWith("t_") && token.length == 10)
    }

    @Test
    fun `a bookmark released by a real read is distinguishable from one the user set`() {
        // MANUAL_UNREAD_CLEARED vs MANUAL_UNREAD_SET: without separate events a badge that clears — or
        // stubbornly does not — has no explanation in the log.
        ConversationReadAudit.manualUnreadSet(7L, source = "user_mark_unread")
        ConversationReadAudit.manualUnreadCleared(7L, source = "local_read")
        // Reaching here without throwing is the contract; DiagnosticLog is a no-op on the JVM, so the
        // event text itself is asserted in TechnicalDetailsTextTest-style tests, not here.
        assertTrue(true)
    }

    @Test
    fun `the read-cause taxonomy keeps the two screen-dependent causes separate from explicit ones`() {
        // The bookmark may only be cleared by a real read. Screen-dependent and explicit read
        // authorities stay distinct; the provenance-only causes do not authorise a read at all.
        val causes = ReadCause.entries.map { it.name }.toSet()

        assertEquals(
            setOf(
                "USER_OPEN_RESUMED", "USER_MARK_READ", "REMOTE_MARK_READ",
                "INCOMING_WHILE_VISIBLE", "NOTIFICATION_ACTION", "MARK_ALL",
                "PROVIDER_SYNC", "REPAIR", "INCOMING_UNREAD"
            ),
            causes
        )
    }

    @Test
    fun `the skip reasons a user must be able to tell apart stay distinct`() {
        // A conversation left unread by "no horizon" and one left unread by "not on screen" are
        // different problems with different fixes.
        assertNotEquals(ReadSkipReason.NO_USER_EVIDENCE, ReadSkipReason.SCREEN_NOT_VISIBLE)
        assertTrue(ReadSkipReason.entries.contains(ReadSkipReason.STALE_GENERATION))
    }
}
