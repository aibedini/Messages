package com.autonomousone.messages.sync

import com.autonomousone.messages.data.GatewayEventFactory
import com.autonomousone.messages.data.MirrorReconcileRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirror → outbox reconciliation (mission §34/§35).
 *
 * The scenario this exists for: a message the phone knows about but that has NO durable event —
 * because its notification was missed, or arrived while the process was dead. Before reconciliation
 * that message left no trace at all (`docs/gateway-replication-audit.md`, Blockers 1 and 12).
 */
class MirrorReconcilePolicyTest {

    private fun row(
        source: String = "sms",
        providerId: Long = 1,
        date: Long = 1_700_000_000_000L,
        body: String = "hi",
        type: Int = 1
    ) = MirrorReconcileRow(
        source = source,
        providerId = providerId,
        threadId = 7,
        normalizedAddress = "09120000000",
        body = body,
        date = date,
        type = type,
        status = -1,
        read = false
    )

    // ── The core decision ────────────────────────────────────────────────────

    @Test
    fun `aMessageWithNoDurableEventIsReportedMissing`() {
        val rows = listOf(row(providerId = 5))

        val missing = MirrorReconcilePolicy.missing(rows, existingIds = emptySet())

        assertEquals(1, missing.size)
        assertEquals(5L, missing.single().providerId)
    }

    @Test
    fun `aMessageThatAlreadyHasAnEventIsNotReEnqueued`() {
        val rows = listOf(row(providerId = 5))
        val existing = setOf(MirrorReconcilePolicy.canonicalId(rows.single()))

        assertTrue(MirrorReconcilePolicy.missing(rows, existing).isEmpty())
    }

    @Test
    fun `onlyTheRowsWithoutEventsAreReturned`() {
        val present = row(providerId = 1)
        val absent = row(providerId = 2)
        val existing = setOf(MirrorReconcilePolicy.canonicalId(present))

        val missing = MirrorReconcilePolicy.missing(listOf(present, absent), existing)

        assertEquals(listOf(2L), missing.map { it.providerId })
    }

    @Test
    fun `runningTwiceChangesNothingTheSecondTime`() {
        // Idempotence: the second pass sees the ids the first pass would have created.
        val rows = listOf(row(providerId = 1), row(providerId = 2))
        val afterFirstRun = rows.map(MirrorReconcilePolicy::canonicalId).toSet()

        assertTrue(MirrorReconcilePolicy.missing(rows, afterFirstRun).isEmpty())
    }

    // ── The identity it depends on ───────────────────────────────────────────

    @Test
    fun `theCanonicalIdIsTheOneEveryOtherPathComputes`() {
        // Reconciliation must ask "is this message already queued?" using the SAME identity the
        // realtime and history paths produce, or it would enqueue a duplicate of a message that is
        // already there (mission §33).
        val r = row(source = "sms", providerId = 4242, date = 1_700_000_000_000L)

        assertEquals(
            GatewayEventFactory.eventUuidFor(
                GatewayEventFactory.Types.MESSAGE_CREATED, "sms", 4242, 1_700_000_000_000L
            ),
            MirrorReconcilePolicy.canonicalId(r)
        )
        assertEquals(
            GatewayEventFactory.messageCreated(
                source = "sms", providerId = 4242, conversationId = "c",
                direction = "in", body = "b", dateMs = 1_700_000_000_000L, status = 0
            ).eventUuid,
            MirrorReconcilePolicy.canonicalId(r)
        )
    }

    @Test
    fun `aReconciledEventIsSourcedAsReconciliationAtTheLowestPriority`() {
        val built = GatewayEventFactory.messageCreated(
            source = "sms", providerId = 4242, conversationId = "c",
            direction = "in", body = "b", dateMs = 1, status = 0,
            priority = com.autonomousone.messages.data.GatewayEventOutboxEntity.PRIORITY_RECONCILIATION
        )

        assertEquals(
            com.autonomousone.messages.data.GatewayEventOutboxEntity.SOURCE_RECONCILIATION,
            built.source
        )
        assertEquals(
            10,
            com.autonomousone.messages.data.GatewayEventOutboxEntity
                .weightOf(built.priority)
        )
    }

    // ── Guards ───────────────────────────────────────────────────────────────

    @Test
    fun `anInvalidProviderIdIsNeverReconciled`() {
        // eventUuidFor is only meaningful for a real provider row; a synthetic one would produce an
        // event the server could never match to a message.
        val rows = listOf(row(providerId = 0), row(providerId = -1))

        assertTrue(MirrorReconcilePolicy.candidates(rows).isEmpty())
        assertTrue(MirrorReconcilePolicy.missing(rows, emptySet()).isEmpty())
    }

    @Test
    fun `anEmptyWindowIsNotAGap`() {
        assertTrue(MirrorReconcilePolicy.missing(emptyList(), emptySet()).isEmpty())
        assertFalse(ReconcileResult(examined = 0, recovered = 0).foundGap)
    }

    @Test
    fun `smsAndMmsAtTheSameProviderIdAreDifferentMessages`() {
        // providerId alone is not unique across the two provider tables, which is exactly why the
        // mirror keys on (source, providerId) and why the canonical id includes the source.
        val sms = row(source = "sms", providerId = 5)
        val mms = row(source = "mms", providerId = 5)

        assertTrue(MirrorReconcilePolicy.canonicalId(sms) != MirrorReconcilePolicy.canonicalId(mms))
        assertEquals(2, MirrorReconcilePolicy.missing(listOf(sms, mms), emptySet()).size)
    }

    @Test
    fun `theSameProviderRowAtADifferentDateIsADifferentEvent`() {
        val a = row(providerId = 5, date = 1)
        val b = row(providerId = 5, date = 2)

        assertTrue(MirrorReconcilePolicy.canonicalId(a) != MirrorReconcilePolicy.canonicalId(b))
    }

    @Test
    fun `aResultReportsWhetherItFoundAnything`() {
        // "found nothing" and "recovered 12" are very different facts about a device, and only one
        // of them means the safety net worked.
        assertFalse(ReconcileResult(examined = 500, recovered = 0).foundGap)
        assertTrue(ReconcileResult(examined = 500, recovered = 12).foundGap)
    }
}
