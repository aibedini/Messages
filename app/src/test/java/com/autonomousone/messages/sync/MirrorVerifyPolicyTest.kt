package com.autonomousone.messages.sync

import com.autonomousone.messages.data.MirrorReconcileRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mission §35: the full-mirror verification walk.
 *
 * The window check examines 48 hours. A message whose event was lost three weeks ago is inside no
 * window it looks at, and once the history scan has finished it never revisits those rows either — so
 * the app's answer to "is the whole mirror replicated?" rested on two passes that both declined to ask
 * the question. This is the pass that asks it, and what has to be true of it is that it *finishes*:
 * every row examined once, nothing skipped, and the cursor strictly advancing.
 */
class MirrorVerifyPolicyTest {

    private val now = 1_700_000_000_000L

    private fun row(date: Long, providerId: Long, source: String = "sms", type: Int = 1) =
        MirrorReconcileRow(
            source = source,
            providerId = providerId,
            threadId = 7,
            normalizedAddress = "09120000000",
            body = "hi",
            date = date,
            type = type,
            status = -1,
            read = false
        )

    // ── The walk advances, and cannot loop or skip ────────────────────────────

    @Test
    fun `theCursorIsTheOldestRowOfThePageNotTheNewest`() {
        // Ordering is `date DESC, providerId DESC`, so the last row is the oldest examined. Taking the
        // FIRST row would never advance — the next page would start exactly where this one did.
        val page = listOf(row(300, 3), row(200, 2), row(100, 1))

        val cursor = MirrorVerifyPolicy.nextCursor(page)!!

        assertEquals(MirrorVerifyCursor(date = 100, providerId = 1), cursor)
    }

    @Test
    fun `rowsSharingADateAreNotSkipped`() {
        // The classic keyset failure. A page can end in the middle of a date group — a burst of
        // messages in the same second, or multi-part SMS — and a cursor that advanced by date alone
        // would skip every remaining row of that group on the next page. The cursor carries the
        // provider id for exactly this reason.
        val page = listOf(row(500, 9), row(500, 8), row(500, 7))

        val cursor = MirrorVerifyPolicy.nextCursor(page)!!

        assertEquals(500L, cursor.date)
        assertEquals("the provider id must be part of the cursor", 7L, cursor.providerId)
    }

    @Test
    fun `anEmptyPageHasNoNextCursor`() {
        assertNull(MirrorVerifyPolicy.nextCursor(emptyList()))
    }

    @Test
    fun `aShortPageEndsTheSweepAndAFullOneDoesNot`() {
        assertTrue(MirrorVerifyPolicy.isComplete(pageSize = 499, limit = 500))
        assertFalse(MirrorVerifyPolicy.isComplete(pageSize = 500, limit = 500))
    }

    @Test
    fun `aFinishedSweepCarriesNoCursorForward`() {
        // Otherwise a resumed sweep would re-walk the oldest page forever: the cursor would still
        // point at the last row of the mirror.
        val page = MirrorVerifyPolicy.page(
            examined = 3, eligible = 3, missing = 0, skippedNoDirection = 0,
            next = MirrorVerifyCursor(date = 100, providerId = 1), limit = 500
        )

        assertTrue(page.complete)
        assertEquals(MirrorVerifyCursor.START, page.next)
    }

    @Test
    fun `anUnfinishedSweepKeepsItsCursor`() {
        val page = MirrorVerifyPolicy.page(
            examined = 500, eligible = 500, missing = 0, skippedNoDirection = 0,
            next = MirrorVerifyCursor(date = 100, providerId = 1), limit = 500
        )

        assertFalse(page.complete)
        assertEquals(MirrorVerifyCursor(date = 100, providerId = 1), page.next)
    }

    // ── Every examined row is explained ──────────────────────────────────────

    @Test
    fun `aHealthyPageBalancesWithMostRowsAlreadyReplicated`() {
        // The arithmetic has to survive the case it will actually meet on every page of a healthy
        // device, where almost every row already has an event. An arithmetic that only balanced when
        // something was recovered would show a residual on every good page.
        val page = MirrorVerifyPolicy.page(
            examined = 500, eligible = 500, missing = 0, skippedNoDirection = 0,
            next = MirrorVerifyCursor(1, 1), limit = 500
        )

        assertEquals(500, page.alreadyReplicated)
        assertEquals(0, page.recovered)
        assertTrue(page.balances)
    }

    @Test
    fun `recoveredAndSkippedSplitTheMissingRowsExactly`() {
        val page = MirrorVerifyPolicy.page(
            examined = 10, eligible = 10, missing = 4, skippedNoDirection = 3,
            next = MirrorVerifyCursor(1, 1), limit = 500
        )

        assertEquals(6, page.alreadyReplicated)
        assertEquals(1, page.recovered)
        assertEquals(3, page.skippedNoDirection)
        assertEquals(0, page.skippedNoProviderId)
        assertTrue(page.balances)
    }

    @Test
    fun `aRowThatWasNeverLookedUpIsNotClaimedAsReplicated`() {
        // `candidates` excludes rows with no provider id — `eventUuidFor` is meaningless without a
        // real provider row — so those rows are never checked. Counting them as "already replicated"
        // would be a claim about rows nobody looked at, which is the exact kind of number this whole
        // effort exists to stop printing.
        val page = MirrorVerifyPolicy.page(
            examined = 10, eligible = 8, missing = 1, skippedNoDirection = 0,
            next = MirrorVerifyCursor(1, 1), limit = 500
        )

        assertEquals(2, page.skippedNoProviderId)
        assertEquals(7, page.alreadyReplicated)
        assertEquals(1, page.recovered)
        assertTrue(page.balances)
    }

    @Test
    fun `theTallyBalancesForEveryCombinationOfCauses`() {
        // Stated over the space rather than by example: whatever the page contains, the parts must sum
        // to what was examined, or the sweep's numbers cannot be trusted at all.
        for (eligible in 0..8) {
            for (missing in 0..eligible) {
                for (skipped in 0..missing) {
                    val page = MirrorVerifyPolicy.page(
                        examined = 8, eligible = eligible, missing = missing,
                        skippedNoDirection = skipped, next = MirrorVerifyCursor(1, 1), limit = 500
                    )
                    assertTrue(
                        "eligible=$eligible missing=$missing skipped=$skipped must balance",
                        page.balances
                    )
                }
            }
        }
    }

    // ── Accumulating across passes ───────────────────────────────────────────

    @Test
    fun `pagesAccumulateIntoTheProgressRatherThanReplacingIt`() {
        // A sweep of a real mirror is many passes; an assignment would report only the last page and
        // the sweep would look like it examined 500 rows however long it ran.
        var progress = MirrorVerifyPolicy.start("sms", now)
        val first = MirrorVerifyPolicy.page(
            examined = 500, eligible = 500, missing = 2, skippedNoDirection = 0,
            next = MirrorVerifyCursor(900, 5), limit = 500
        )
        progress = MirrorVerifyPolicy.apply(progress, first, now)
        val second = MirrorVerifyPolicy.page(
            examined = 500, eligible = 500, missing = 0, skippedNoDirection = 0,
            next = MirrorVerifyCursor(400, 1), limit = 500
        )
        progress = MirrorVerifyPolicy.apply(progress, second, now + 1000)

        assertEquals(1000, progress.examined)
        assertEquals(998, progress.alreadyReplicated)
        assertEquals(2, progress.recovered)
        assertTrue(progress.balances)
        assertEquals(0L, progress.completedAt)
        assertFalse(progress.complete)
    }

    @Test
    fun `aCompletedPageClosesTheSweepAtItsTimestamp`() {
        var progress = MirrorVerifyPolicy.start("sms", now)
        progress = MirrorVerifyPolicy.apply(
            progress,
            MirrorVerifyPolicy.page(
                examined = 3, eligible = 3, missing = 0, skippedNoDirection = 0,
                next = null, limit = 500
            ),
            now + 500
        )

        assertTrue(progress.complete)
        assertEquals(now + 500, progress.completedAt)
        assertEquals(3, progress.examined)
        assertTrue(progress.balances)
    }

    @Test
    fun `anUnbalancedProgressReportsTheResidualRatherThanHidingIt`() {
        val progress = MirrorVerifyPolicy.start("sms", now).copy(examined = 10, recovered = 1)

        assertEquals(9, progress.residual)
        assertFalse(progress.balances)
    }

    // ── What a human is told ─────────────────────────────────────────────────

    @Test
    fun `aFinishedSweepThatFoundNothingSaysNothing`() {
        // The good outcome is not worth a line, and a diagnostic that always speaks is one nobody
        // reads.
        val progress = MirrorVerifyPolicy.start("sms", now)
            .copy(examined = 360_000, alreadyReplicated = 360_000, completedAt = now)

        assertNull(MirrorVerifyPolicy.alarm(progress))
    }

    @Test
    fun `aRecoveredGapIsNamedAsSomethingNothingElseWouldHaveFound`() {
        val progress = MirrorVerifyPolicy.start("sms", now)
            .copy(examined = 1000, alreadyReplicated = 998, recovered = 2, completedAt = now)

        val message = MirrorVerifyPolicy.alarm(progress)!!
        assertTrue(message, message.contains("2 message(s)"))
        assertTrue(message, message.contains("already finished"))
    }

    @Test
    fun `anIncompleteSweepRefusesToCallTheMirrorVerified`() {
        // The honest limit, stated rather than implied: until the sweep finishes, "no messages lost"
        // is a belief and not a verified claim — which is exactly what §70 asks a diagnostic to stop
        // doing. The tally has to balance for this to be the message that is shown.
        val progress = MirrorVerifyPolicy.start("sms", now)
            .copy(examined = 5000, alreadyReplicated = 5000)

        val message = MirrorVerifyPolicy.alarm(progress)!!
        assertTrue(message, message.contains("has not finished"))
        assertTrue(message, message.contains("is not yet a verified claim"))
    }

    @Test
    fun `anUnbalancedSweepSaysItsOwnTallyCannotBeTrusted`() {
        // Reported BEFORE any recovery count, because `recovered` comes from the same broken tally.
        val progress = MirrorVerifyPolicy.start("sms", now).copy(examined = 10, recovered = 1)

        val message = MirrorVerifyPolicy.alarm(progress)!!
        assertTrue(message, message.contains("residual 9"))
        assertTrue(message, message.contains("cannot be trusted"))
        assertFalse("no loss claim from broken books", message.contains("NO durable event"))
    }
}
