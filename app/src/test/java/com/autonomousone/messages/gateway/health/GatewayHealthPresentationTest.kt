package com.autonomousone.messages.gateway.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How health LOOKS.
 *
 * The mapping is a user-facing contract, so it is asserted rather than left as an inline
 * `when` in a composable: a red row for a gateway the user switched off, or a green row for
 * something never observed, is a bug report waiting to happen.
 */
class GatewayHealthPresentationTest {

    private val now = 1_700_000_000_000L

    // ── The verdict ─────────────────────────────────────────────────────────

    @Test
    fun `everyVerdictHasAToneAndAHeadline`() {
        GatewayOverallHealth.entries.forEach { overall ->
            GatewayHealthPresentation.headline(overall)
            GatewayHealthPresentation.tone(overall)
        }
        assertEquals(HealthTone.GOOD, GatewayHealthPresentation.tone(GatewayOverallHealth.HEALTHY))
        assertEquals(HealthTone.WARN, GatewayHealthPresentation.tone(GatewayOverallHealth.DEGRADED))
        assertEquals(HealthTone.BAD, GatewayHealthPresentation.tone(GatewayOverallHealth.ERROR))
        assertEquals(HealthTone.NEUTRAL, GatewayHealthPresentation.tone(GatewayOverallHealth.STARTING))
    }

    /**
     * OFFLINE is IDLE, never BAD. The most common reason is that the user turned the gateway
     * off, and alarming someone about their own decision trains them to ignore the colour
     * that matters.
     */
    @Test
    fun `aDeliberatelyOffGatewayIsNeverAlarming`() {
        assertEquals(HealthTone.IDLE, GatewayHealthPresentation.tone(GatewayOverallHealth.OFFLINE))
        assertEquals("Off", GatewayHealthPresentation.headline(GatewayOverallHealth.OFFLINE))
    }

    @Test
    fun `theHeadlinesNameTheStateInPlainWords`() {
        assertEquals("Healthy", GatewayHealthPresentation.headline(GatewayOverallHealth.HEALTHY))
        assertEquals("Degraded", GatewayHealthPresentation.headline(GatewayOverallHealth.DEGRADED))
        assertEquals("Not working", GatewayHealthPresentation.headline(GatewayOverallHealth.ERROR))
        assertEquals("Starting", GatewayHealthPresentation.headline(GatewayOverallHealth.STARTING))
    }

    @Test
    fun `everyConclusionHasASentence`() {
        GatewayConclusion.entries.forEach { conclusion ->
            assertTrue(
                "$conclusion needs a user-facing sentence",
                GatewayHealthPresentation.summary(conclusion).isNotBlank()
            )
        }
        // The reported production state, in words the user can act on.
        assertTrue(
            GatewayHealthPresentation.summary(GatewayConclusion.BRIDGE_NOT_POLLING)
                .contains("not reaching this phone")
        )
    }

    // ── Dimensions ──────────────────────────────────────────────────────────

    @Test
    fun `anUnknownDimensionIsNeutralAndNeverGreen`() {
        assertEquals(HealthTone.GOOD, GatewayHealthPresentation.tone(true))
        assertEquals(HealthTone.BAD, GatewayHealthPresentation.tone(false))
        assertEquals(HealthTone.NEUTRAL, GatewayHealthPresentation.tone(null))
    }

    @Test
    fun `aFreshBridgeIsGoodAndARejectedKeyIsBad`() {
        val fresh = PullBridgeHealth(
            running = true,
            lastSuccessfulPollAt = now - 5_000L,
            lastEmptyPollAt = now - 5_000L
        )
        assertEquals(HealthTone.GOOD, GatewayHealthPresentation.bridgeTone(fresh, now))

        val rejected = PullBridgeHealth(
            running = true,
            lastFailure = GatewayFailureKind.HTTP_AUTH,
            consecutiveFailures = 1
        )
        assertEquals(HealthTone.BAD, GatewayHealthPresentation.bridgeTone(rejected, now))

        // A transient failure is a warning, not an alarm: it is being retried.
        val transient = PullBridgeHealth(
            running = true,
            lastFailure = GatewayFailureKind.READ_TIMEOUT,
            consecutiveFailures = 1
        )
        assertEquals(HealthTone.WARN, GatewayHealthPresentation.bridgeTone(transient, now))
    }

    @Test
    fun `aNeverObservedBridgeIsNeutralRatherThanBroken`() {
        val bridge = PullBridgeHealth(running = true)

        assertEquals(HealthTone.NEUTRAL, GatewayHealthPresentation.bridgeTone(bridge, now))
        assertTrue(GatewayHealthPresentation.bridgeSummary(bridge, now).contains("not polling yet"))
        assertNull(GatewayHealthPresentation.inFlightWait(bridge, now))
    }

    /**
     * A long-poll holds a request open for ~25 seconds BY DESIGN, so "waiting 18s" is normal
     * and the user has to be able to tell it apart from a bridge that never polls.
     */
    @Test
    fun `anInFlightPollReportsHowLongItHasBeenWaiting`() {
        val inFlight = PullBridgeHealth(
            running = true,
            state = "POLLING",
            lastPollStartedAt = now - 18_000L,
            currentRequestStartedAt = now - 18_000L
        )

        assertEquals("18s ago", GatewayHealthPresentation.inFlightWait(inFlight, now))
        assertTrue(GatewayHealthPresentation.bridgeSummary(inFlight, now).contains("waiting 18s ago"))
    }

    @Test
    fun `aRecordedFailureOutranksTheInFlightLine`() {
        val failing = PullBridgeHealth(
            running = true,
            lastPollStartedAt = now,
            currentRequestStartedAt = now - 1_000L,
            lastFailure = GatewayFailureKind.HTTP_AUTH,
            consecutiveFailures = 1
        )

        assertEquals("HTTP_AUTH", GatewayHealthPresentation.bridgeSummary(failing, now))
        // The UI suppresses the in-flight row while an error is on screen, so the user is
        // never told a poll is "waiting" for one that has already come back rejected.
        assertEquals(1, failing.consecutiveFailures)
    }

    @Test
    fun `aStaleBridgeIsAWarning`() {
        val stale = PullBridgeHealth(
            running = true,
            lastSuccessfulPollAt = now - GatewayHealthRules.PULL_FRESH_MS - 1L
        )

        assertEquals(HealthTone.WARN, GatewayHealthPresentation.bridgeTone(stale, now))
        assertTrue(GatewayHealthPresentation.bridgeSummary(stale, now).contains("stalled"))
    }

    /**
     * A phone with nothing to send is HEALTHY. Colouring an empty queue amber would make
     * every idle device look broken.
     */
    @Test
    fun `aQuietUploaderIsHealthy`() {
        val idle = EventUploadHealth(running = true, pending = 0, sending = 0, deadLetter = 0)

        assertEquals(HealthTone.GOOD, GatewayHealthPresentation.uploadTone(idle, now))
        assertTrue(GatewayHealthPresentation.uploadSummary(idle, now).contains("nothing to send"))
    }

    @Test
    fun `aDeadLetterOrAFailureColoursTheUploader`() {
        assertEquals(
            HealthTone.WARN,
            GatewayHealthPresentation.uploadTone(
                EventUploadHealth(running = true, deadLetter = 2), now
            )
        )
        assertEquals(
            HealthTone.BAD,
            GatewayHealthPresentation.uploadTone(
                EventUploadHealth(running = true, lastFailure = GatewayFailureKind.HTTP_SERVER), now
            )
        )
        assertEquals(
            HealthTone.NEUTRAL,
            GatewayHealthPresentation.uploadTone(EventUploadHealth(running = false), now)
        )
    }

    // ── Log and probe tones ─────────────────────────────────────────────────

    @Test
    fun `logSeveritiesMapToTones`() {
        assertEquals(HealthTone.GOOD, GatewayHealthPresentation.tone(GatewayLogSeverity.SUCCESS))
        assertEquals(HealthTone.WARN, GatewayHealthPresentation.tone(GatewayLogSeverity.WARNING))
        assertEquals(HealthTone.BAD, GatewayHealthPresentation.tone(GatewayLogSeverity.ERROR))
        assertEquals(HealthTone.NEUTRAL, GatewayHealthPresentation.tone(GatewayLogSeverity.INFO))
    }

    @Test
    fun `probeStatusesMapToTones`() {
        assertEquals(HealthTone.GOOD, GatewayHealthPresentation.tone(GatewayProbeStatus.PASSED))
        assertEquals(HealthTone.BAD, GatewayHealthPresentation.tone(GatewayProbeStatus.FAILED))
        assertEquals(HealthTone.NEUTRAL, GatewayHealthPresentation.tone(GatewayProbeStatus.SKIPPED))
        // A stage that does not apply must not look like a stage that passed.
        assertEquals(HealthTone.IDLE, GatewayHealthPresentation.tone(GatewayProbeStatus.NOT_REQUIRED))
    }

    @Test
    fun `everyToneAndStatusHasAColourAssignment`() {
        HealthTone.entries.forEach { tone ->
            assertFalse(tone.name.isBlank())
        }
        GatewayProbeStatus.entries.forEach { GatewayHealthPresentation.tone(it) }
        GatewayLogSeverity.entries.forEach { GatewayHealthPresentation.tone(it) }
    }

    // ── Relative times and summaries ────────────────────────────────────────

    @Test
    fun `agoIsShortEnoughForAStatusRow`() {
        assertEquals("never", GatewayHealthPresentation.ago(null, now))
        assertEquals("just now", GatewayHealthPresentation.ago(now - 500L, now))
        assertEquals("42s ago", GatewayHealthPresentation.ago(now - 42_000L, now))
        assertEquals("4m ago", GatewayHealthPresentation.ago(now - 4 * 60_000L, now))
        assertEquals("2h ago", GatewayHealthPresentation.ago(now - 2 * 3_600_000L, now))
        // A phone whose clock moved backwards must not render a negative age.
        assertEquals("just now", GatewayHealthPresentation.ago(now + 60_000L, now))
    }

    @Test
    fun `theQueueSummaryNamesTheStageThatIsBusy`() {
        assertEquals("idle", GatewayHealthPresentation.queueSummary(EveQueueHealth()))
        assertEquals("2 waiting", GatewayHealthPresentation.queueSummary(EveQueueHealth(queued = 2)))
        assertEquals("sending 1", GatewayHealthPresentation.queueSummary(EveQueueHealth(active = 1)))
        assertEquals("1 deferred", GatewayHealthPresentation.queueSummary(EveQueueHealth(deferred = 1)))
        assertEquals("3 failed", GatewayHealthPresentation.queueSummary(EveQueueHealth(failedRecent = 3)))
        // "Sending" outranks "waiting": it is the stage that is actually moving.
        assertEquals(
            "sending 1",
            GatewayHealthPresentation.queueSummary(EveQueueHealth(queued = 5, active = 1))
        )
    }
}
