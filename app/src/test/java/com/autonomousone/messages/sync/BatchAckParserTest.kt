package com.autonomousone.messages.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Both batch-response shapes (mission §15/§16, §62).
 *
 * The behaviour this pins down is the one that could not converge before: a server that reports an
 * already-persisted event as DUPLICATE, or omits `serverSequence`, must still be understood as
 * "the server has this event". Anything else re-uploads it forever (audit Blocker 5).
 */
class BatchAckParserTest {

    // ── Legacy shape ─────────────────────────────────────────────────────────

    @Test
    fun `theLegacyAcceptedArrayIsUnderstood`() {
        val parsed = BatchAckParser.parse(
            """{"accepted":[{"eventId":"e1","serverSequence":58193}]}"""
        )

        assertEquals(mapOf("e1" to 58193L), parsed.acknowledged)
        assertTrue(parsed.rejected.isEmpty())
        assertFalse(parsed.mentioningNothing)
    }

    @Test
    fun `aLegacyAcceptedItemWithoutASequenceIsStillAnAcknowledgement`() {
        // The old parser required sequence > 0 and requeued everything else forever.
        val parsed = BatchAckParser.parse("""{"accepted":[{"eventId":"e1"}]}""")

        assertEquals(mapOf("e1" to 0L), parsed.acknowledged)
    }

    @Test
    fun `anEmptyAcceptedArrayAcknowledgesNothingAndIsNotARejection`() {
        val parsed = BatchAckParser.parse("""{"accepted":[]}""")

        assertTrue(parsed.acknowledged.isEmpty())
        assertTrue(parsed.rejected.isEmpty())
        assertFalse("an empty accepted list is a real answer", parsed.mentioningNothing)
    }

    // ── V2 shape ─────────────────────────────────────────────────────────────

    @Test
    fun `v2AcceptedAndDuplicateBothAcknowledge`() {
        val parsed = BatchAckParser.parse(
            """
            {"results":[
              {"eventId":"e1","status":"ACCEPTED","serverSequence":92110},
              {"eventId":"e2","status":"DUPLICATE","serverSequence":92000}
            ]}
            """.trimIndent()
        )

        assertEquals(
            mapOf("e1" to 92110L, "e2" to 92000L),
            parsed.acknowledged
        )
        assertTrue(parsed.rejected.isEmpty())
    }

    @Test
    fun `v2DuplicateWithoutASequenceIsStillAcknowledged`() {
        // Mission Test F: the response was lost, the retry reports DUPLICATE. This must converge.
        val parsed = BatchAckParser.parse("""{"results":[{"eventId":"e1","status":"DUPLICATE"}]}""")

        assertEquals(mapOf("e1" to 0L), parsed.acknowledged)
    }

    @Test
    fun `aPermanentlyRejectedItemIsReportedWithTheServersReason`() {
        val parsed = BatchAckParser.parse(
            """
            {"results":[{"eventId":"e3","status":"REJECTED","retryable":false,
                         "errorCode":"INVALID_SCHEMA"}]}
            """.trimIndent()
        )

        assertEquals(setOf("e3"), parsed.rejected.keys)
        assertEquals("INVALID_SCHEMA", parsed.rejected.getValue("e3").errorCode)
        assertTrue(parsed.acknowledged.isEmpty())
    }

    @Test
    fun `anItemRejectedButMarkedRetryableIsKept`() {
        val parsed = BatchAckParser.parse(
            """{"results":[{"eventId":"e4","status":"REJECTED","retryable":true}]}"""
        )

        assertEquals(setOf("e4"), parsed.retryable.keys)
        assertTrue("a retryable rejection must not kill the row", parsed.rejected.isEmpty())
    }

    @Test
    fun `anUnknownStatusIsTreatedAsRetryableRatherThanFatal`() {
        // A status we do not understand is not evidence that the event is unacceptable.
        val parsed = BatchAckParser.parse(
            """{"results":[{"eventId":"e5","status":"QUARANTINED"}]}"""
        )

        assertTrue(parsed.acknowledged.isEmpty())
        assertTrue(parsed.rejected.isEmpty())
        assertEquals(setOf("e5"), parsed.retryable.keys)
    }

    @Test
    fun `statusMatchingIsCaseInsensitive`() {
        val parsed = BatchAckParser.parse("""{"results":[{"eventId":"e1","status":"accepted"}]}""")

        assertEquals(mapOf("e1" to 0L), parsed.acknowledged)
    }

    @Test
    fun `resultsWithNoEventIdAreIgnored`() {
        val parsed = BatchAckParser.parse(
            """{"results":[{"status":"ACCEPTED"},{"eventId":"e1","status":"ACCEPTED"}]}"""
        )

        assertEquals(mapOf("e1" to 0L), parsed.acknowledged)
    }

    @Test
    fun `aMixedBatchSplitsIntoAllThreeOutcomes`() {
        // Mission §18: 100 events → 90 ACKED, 8 RETRY_WAIT, 2 DEAD_LETTER, not 100 retries.
        val parsed = BatchAckParser.parse(
            """
            {"results":[
              {"eventId":"a","status":"ACCEPTED","serverSequence":1},
              {"eventId":"b","status":"DUPLICATE","serverSequence":2},
              {"eventId":"c","status":"REJECTED","retryable":true,"errorCode":"BUSY"},
              {"eventId":"d","status":"REJECTED","retryable":false,"errorCode":"INVALID_SCHEMA"}
            ],"duplicates":1}
            """.trimIndent()
        )

        assertEquals(setOf("a", "b"), parsed.acknowledged.keys)
        assertEquals(setOf("c"), parsed.retryable.keys)
        assertEquals(setOf("d"), parsed.rejected.keys)
        assertEquals(1, parsed.duplicates)
    }

    // ── Rubbish input must not be read as a verdict ───────────────────────────

    @Test
    fun `anUnparseableBodySaysNothingRatherThanEverythingFailed`() {
        listOf(null, "", "not json", "<html>502</html>").forEach { body ->
            val parsed = BatchAckParser.parse(body)
            assertTrue("body=$body", parsed.mentioningNothing)
            assertTrue("body=$body", parsed.acknowledged.isEmpty())
            assertTrue("body=$body", parsed.rejected.isEmpty())
        }
    }

    @Test
    fun `aCountOnlyResponseIsRecognisedAsSayingNothingAboutItems`() {
        val parsed = BatchAckParser.parse("""{"duplicates":3}""")

        assertEquals(3, parsed.duplicates)
        assertTrue(parsed.acknowledged.isEmpty())
        // It reported a count but named no event, so no per-event conclusion may be drawn.
        assertFalse(parsed.mentioningNothing)
    }

    @Test
    fun `anUnknownFieldBodyFallsBackToTheLegacyReader`() {
        // A rollout-period server may send neither shape; it must not be treated as a rejection.
        val parsed = BatchAckParser.parse("""{"ok":true}""")

        assertTrue(parsed.acknowledged.isEmpty())
        assertTrue(parsed.rejected.isEmpty())
    }
}
