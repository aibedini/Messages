package com.autonomousone.messages.sync

import com.autonomousone.messages.data.GatewayEventOutboxEntity
import com.autonomousone.messages.repository.GatewaySyncRepository.Policy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fair batching and the priority model (mission §13).
 *
 * The defect this guards is starvation: with one strict REALTIME-before-everything ordering and a
 * 200-row candidate window, a device with sustained realtime traffic never uploaded a single row
 * of history — a 360k-message backfill could stall forever (`docs/gateway-replication-audit.md`,
 * Blocker 11).
 */
class OutboxBatchingPolicyTest {

    private fun event(
        priority: String,
        id: Long,
        bytes: Int = 10
    ) = GatewayEventOutboxEntity(
        id = id,
        eventUuid = "evt-$priority-$id",
        eventType = "MESSAGE_CREATED",
        aggregateId = "agg-$id",
        priority = priority,
        ciphertext = ByteArray(bytes),
        encoding = "json",
        schemaVersion = 1,
        createdAt = id
    )

    // ── The priority model ───────────────────────────────────────────────────

    @Test
    fun `theFiveMissionLevelsExistInWeightOrder`() {
        assertEquals(5, GatewayEventOutboxEntity.DELIVERY_PRIORITIES.size)
        assertEquals(
            listOf("REALTIME", "COMMAND_RESULT", "STATUS_UPDATE", "BACKFILL", "RECONCILIATION"),
            GatewayEventOutboxEntity.DELIVERY_PRIORITIES
        )
        // Highest first, and strictly decreasing.
        val weights = GatewayEventOutboxEntity.DELIVERY_PRIORITIES.map {
            GatewayEventOutboxEntity.weightOf(it)
        }
        assertEquals(listOf(100, 90, 80, 20, 10), weights)
        assertEquals(weights.sortedDescending(), weights)
    }

    @Test
    fun `backfillIsTheHistoryLevelAndIsBackground`() {
        // The stored value is kept; the mission's HISTORY level is BACKFILL.
        assertEquals(20, GatewayEventOutboxEntity.weightOf(GatewayEventOutboxEntity.PRIORITY_BACKFILL))
        assertTrue(GatewayEventOutboxEntity.isBackground(GatewayEventOutboxEntity.PRIORITY_BACKFILL))
        assertTrue(GatewayEventOutboxEntity.isBackground(GatewayEventOutboxEntity.PRIORITY_RECONCILIATION))
        assertFalse(GatewayEventOutboxEntity.isBackground(GatewayEventOutboxEntity.PRIORITY_REALTIME))
        assertFalse(GatewayEventOutboxEntity.isBackground(GatewayEventOutboxEntity.PRIORITY_COMMAND_RESULT))
        assertFalse(GatewayEventOutboxEntity.isBackground(GatewayEventOutboxEntity.PRIORITY_STATUS_UPDATE))
    }

    @Test
    fun `anUnknownPriorityIsNeitherStarvedNorAllowedToOutrankAUserMessage`() {
        val unknown = GatewayEventOutboxEntity.weightOf("SOMETHING_NEW")
        assertTrue(unknown > GatewayEventOutboxEntity.weightOf(GatewayEventOutboxEntity.PRIORITY_BACKFILL))
        assertTrue(unknown < GatewayEventOutboxEntity.weightOf(GatewayEventOutboxEntity.PRIORITY_REALTIME))
        assertFalse(GatewayEventOutboxEntity.isBackground("SOMETHING_NEW"))
    }

    @Test
    fun `theSqlMirrorsEveryKotlinPriority`() {
        // The DAO cannot build its SQL from a Kotlin list at runtime, so the two are kept in step
        // by this assertion rather than by hope.
        GatewayEventOutboxEntity.FOREGROUND_PRIORITIES.forEach { priority ->
            assertTrue(
                "$priority missing from FOREGROUND_PRIORITIES_SQL",
                GatewayEventOutboxEntity.FOREGROUND_PRIORITIES_SQL.contains("'$priority'")
            )
        }
        GatewayEventOutboxEntity.BACKGROUND_PRIORITIES.forEach { priority ->
            assertTrue(
                "$priority missing from BACKGROUND_PRIORITIES_SQL",
                GatewayEventOutboxEntity.BACKGROUND_PRIORITIES_SQL.contains("'$priority'")
            )
        }
        // Every level must appear in exactly one group.
        val grouped = GatewayEventOutboxEntity.FOREGROUND_PRIORITIES +
            GatewayEventOutboxEntity.BACKGROUND_PRIORITIES
        assertEquals(GatewayEventOutboxEntity.DELIVERY_PRIORITIES.toSet(), grouped.toSet())
        assertEquals(grouped.size, grouped.distinct().size)

        // The ORDER BY cases must name the same values as their group.
        assertEquals(3, Regex("WHEN").findAll(GatewayEventOutboxEntity.FOREGROUND_ORDER_SQL).count())
        assertEquals(2, Regex("WHEN").findAll(GatewayEventOutboxEntity.BACKGROUND_ORDER_SQL).count())
        GatewayEventOutboxEntity.BACKGROUND_PRIORITIES.forEach { priority ->
            assertTrue(
                "$priority missing from BACKGROUND_ORDER_SQL",
                GatewayEventOutboxEntity.BACKGROUND_ORDER_SQL.contains("'$priority'")
            )
        }
    }

    // ── Fairness ─────────────────────────────────────────────────────────────

    @Test
    fun `backgroundWorkGetsAShareWhenBothGroupsAreBusy`() {
        // The starvation case: 100 due realtime rows would have consumed every slot.
        val foreground = List(100) { event(GatewayEventOutboxEntity.PRIORITY_REALTIME, it.toLong()) }
        val background = List(100) { event(GatewayEventOutboxEntity.PRIORITY_BACKFILL, 1_000L + it) }

        val batch = Policy.selectFair(foreground, background)

        assertEquals(Policy.MAX_BATCH_EVENTS, batch.events.size)
        val backgroundCount = batch.events.count {
            GatewayEventOutboxEntity.isBackground(it.priority)
        }
        // 30 of 100 reserved for the background group.
        assertEquals(30, backgroundCount)
        assertEquals(70, batch.events.size - backgroundCount)
        assertTrue("history must actually make it into the batch", backgroundCount > 0)
    }

    @Test
    fun `foregroundIsPlacedFirstWithinTheBatch`() {
        val foreground = List(10) { event(GatewayEventOutboxEntity.PRIORITY_REALTIME, it.toLong()) }
        val background = List(10) { event(GatewayEventOutboxEntity.PRIORITY_BACKFILL, 100L + it) }

        val batch = Policy.selectFair(foreground, background)

        val firstBackgroundIndex = batch.events.indexOfFirst {
            GatewayEventOutboxEntity.isBackground(it.priority)
        }
        val lastForegroundIndex = batch.events.indexOfLast {
            !GatewayEventOutboxEntity.isBackground(it.priority)
        }
        assertTrue(
            "realtime must be submitted ahead of backfill in the same batch; " +
                "actual=${batch.events.map { it.priority }} fg=$lastForegroundIndex bg=$firstBackgroundIndex",
            lastForegroundIndex < firstBackgroundIndex
        )
    }

    @Test
    fun `unusedQuotaIsReturnedToTheOtherGroup`() {
        // Only 5 background rows exist, so foreground should use the other 95 slots, not stop at 70.
        val foreground = List(100) { event(GatewayEventOutboxEntity.PRIORITY_REALTIME, it.toLong()) }
        val background = List(5) { event(GatewayEventOutboxEntity.PRIORITY_BACKFILL, 1_000L + it) }

        val batch = Policy.selectFair(foreground, background)

        assertEquals(Policy.MAX_BATCH_EVENTS, batch.events.size)
        assertEquals(5, batch.events.count { GatewayEventOutboxEntity.isBackground(it.priority) })
        assertEquals(95, batch.events.count { !GatewayEventOutboxEntity.isBackground(it.priority) })
    }

    @Test
    fun `aQuietForegroundStillSendsABackgroundHeavyBatch`() {
        // The other direction matters too: history must not be capped at 30 when nothing else
        // is waiting, or a 360k backfill would take days of idle time.
        val background = List(150) { event(GatewayEventOutboxEntity.PRIORITY_BACKFILL, it.toLong()) }

        val batch = Policy.selectFair(emptyList(), background)

        assertEquals(Policy.MAX_BATCH_EVENTS, batch.events.size)
    }

    @Test
    fun `reconciliationStillRunsWhenHistoryFloodsTheBackgroundGroup`() {
        // RECONCILIATION is the lowest level, but a flood of HISTORY must not silence it forever:
        // the DAO orders the background group BACKFILL-first, so reconciliation is reached only
        // once history is drained — which is why the background group must never be starved shut.
        val background = List(120) { event(GatewayEventOutboxEntity.PRIORITY_BACKFILL, it.toLong()) } +
            listOf(event(GatewayEventOutboxEntity.PRIORITY_RECONCILIATION, 9_999L))

        val batch = Policy.selectFair(emptyList(), background)

        assertEquals(Policy.MAX_BATCH_EVENTS, batch.events.size)
        assertTrue(
            GatewayEventOutboxEntity.weightOf(GatewayEventOutboxEntity.PRIORITY_RECONCILIATION) > 0
        )
    }

    // ── The pre-existing rules still hold ────────────────────────────────────

    @Test
    fun `noEventIsEverSelectedTwice`() {
        // Regression: the top-up pass used to restart at index 0, so a batch built from 20
        // candidates contained all 20 twice — the same event uploaded twice in one request.
        val foreground = List(10) { event(GatewayEventOutboxEntity.PRIORITY_REALTIME, it.toLong()) }
        val background = List(10) { event(GatewayEventOutboxEntity.PRIORITY_BACKFILL, 100L + it) }

        val batch = Policy.selectFair(foreground, background)

        val ids = batch.events.map { it.id }
        assertEquals("a row must appear at most once in a batch", ids.size, ids.distinct().size)
        assertEquals(20, ids.size)
    }

    @Test
    fun `theQuotaIsFilledExactlyOnceWhenBothGroupsAreLarge`() {
        val foreground = List(150) { event(GatewayEventOutboxEntity.PRIORITY_REALTIME, it.toLong()) }
        val background = List(150) { event(GatewayEventOutboxEntity.PRIORITY_BACKFILL, 1_000L + it) }

        val batch = Policy.selectFair(foreground, background)

        assertEquals(Policy.MAX_BATCH_EVENTS, batch.events.size)
        assertEquals(
            "overlapping candidate pools must not produce duplicates",
            batch.events.size,
            batch.events.map { it.id }.distinct().size
        )
    }

    @Test
    fun `theFlatEntryPointSplitsMixedCandidatesFairly`() {
        val mixed = List(100) { event(GatewayEventOutboxEntity.PRIORITY_REALTIME, it.toLong()) } +
            List(100) { event(GatewayEventOutboxEntity.PRIORITY_BACKFILL, 1_000L + it) }

        val batch = Policy.selectBatch(mixed)

        assertEquals(Policy.MAX_BATCH_EVENTS, batch.events.size)
        assertEquals(30, batch.events.count { GatewayEventOutboxEntity.isBackground(it.priority) })
    }

    @Test
    fun `theByteCapStillBoundsA FairBatch`() {
        // 40 KiB each: the batch must stop at the byte cap regardless of the group quota.
        val foreground = List(100) { event(GatewayEventOutboxEntity.PRIORITY_REALTIME, it.toLong(), 40 * 1024) }
        val background = List(100) { event(GatewayEventOutboxEntity.PRIORITY_BACKFILL, 1_000L + it, 40 * 1024) }

        val batch = Policy.selectFair(foreground, background)

        assertTrue(batch.bytes <= Policy.MAX_BATCH_BYTES)
        assertEquals(12, batch.events.size)
    }

    @Test
    fun `aLoneOversizedBackgroundEventStillShipsAlone`() {
        val big = event(GatewayEventOutboxEntity.PRIORITY_BACKFILL, 0, 600 * 1024)
        val batch = Policy.selectFair(emptyList(), listOf(big, event(GatewayEventOutboxEntity.PRIORITY_BACKFILL, 1)))

        assertEquals(listOf(big), batch.events)
    }

    @Test
    fun `bothGroupsEmptyProducesNothing`() {
        assertTrue(Policy.selectFair(emptyList(), emptyList()).events.isEmpty())
        assertEquals(0L, Policy.selectFair(emptyList(), emptyList()).bytes)
    }

    @Test
    fun `theShareIsTheMissionsSeventyThirty`() {
        assertEquals(70, Policy.FOREGROUND_SHARE_PERCENT)
        assertEquals(70, Policy.MAX_BATCH_EVENTS * Policy.FOREGROUND_SHARE_PERCENT / 100)
    }
}
