package com.autonomousone.messages.gateway.health

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The recorder is the only place the live components write, so these tests are the
 * contract between them and the card.
 *
 * The two properties that matter most are asserted from both ends:
 *
 *  - a successful OR empty poll makes the bridge fresh, and a failure makes it stale;
 *  - recording an EVENT UPLOAD success moves nothing in the pull bridge, because the two
 *    routes are independent and conflating them is the exact bug that produced a green
 *    screen over a dead delivery path.
 */
class GatewayHealthRecorderTest {

    private val now = 2_000_000_000L

    @Before
    fun setUp() {
        GatewayHealthRecorder.resetForTest()
        GatewayHealthRecorder.setDesired(true)
        GatewayHealthRecorder.onNetwork(validated = true, transport = "Wi-Fi", at = now)
        GatewayHealthRecorder.onAuthProbe(AuthHealth(status = AuthVerification.VERIFIED, lastVerifiedAt = now))
        // The supervisor starts the uploader and the poller together, so every fixture
        // below describes a gateway whose components are up. A stop is asserted explicitly
        // where it matters.
        GatewayHealthRecorder.setUploaderRunning(true)
    }

    @After
    fun tearDown() = GatewayHealthRecorder.resetForTest()

    // ═══════════════════════════════════════════════════════════════════════════
    // The pull bridge
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `theBridgeIsStartingUntilAPollProducesSomething`() {
        GatewayHealthRecorder.setPollerRunning(true)
        GatewayHealthRecorder.setPollerState("POLLING")

        val snapshot = GatewayHealthRecorder.snapshot(now)

        assertTrue(snapshot.pullBridge.running)
        assertEquals("POLLING", snapshot.pullBridge.state)
        assertNull(snapshot.pullBridge.lastSuccessfulPollAt)
        assertEquals(GatewayOverallHealth.STARTING, snapshot.overall)
    }

    @Test
    fun `aStartedPollIsVisibleWhileTheLongPollIsStillWaiting`() {
        GatewayHealthRecorder.setPollerRunning(true)
        GatewayHealthRecorder.onPullStart(now - 18_000L)

        val bridge = GatewayHealthRecorder.snapshot(now).pullBridge

        assertEquals(now - 18_000L, bridge.lastPollStartedAt)
        assertEquals("the card can show how long it has been waiting", now - 18_000L, bridge.currentRequestStartedAt)
    }

    /** An EMPTY long-poll is a success: it proves the whole round trip, minus a task. */
    @Test
    fun `anEmptyPollIsASuccessfulPoll`() {
        GatewayHealthRecorder.onPullStart(now - 25_000L)
        GatewayHealthRecorder.onPullEmpty(now, httpStatus = 200)

        val snapshot = GatewayHealthRecorder.snapshot(now)

        assertEquals(now, snapshot.pullBridge.lastSuccessfulPollAt)
        assertEquals(now, snapshot.pullBridge.lastEmptyPollAt)
        assertNull("the in-flight marker is cleared", snapshot.pullBridge.currentRequestStartedAt)
        assertNull(snapshot.pullBridge.lastFailure)
        assertEquals(0, snapshot.pullBridge.consecutiveFailures)
        assertEquals(GatewayOverallHealth.HEALTHY, snapshot.overall)
        assertEquals(GatewayConclusion.NONE, snapshot.conclusion)
    }

    @Test
    fun `aTaskPollIsASuccessfulPollAndIsRecordedSeparately`() {
        GatewayHealthRecorder.onPullStart(now - 25_000L)
        GatewayHealthRecorder.onPullTask(now, httpStatus = 200)

        val snapshot = GatewayHealthRecorder.snapshot(now)

        assertEquals(now, snapshot.pullBridge.lastTaskReceivedAt)
        assertNull("no task means the empty stamp stays clean", snapshot.pullBridge.lastEmptyPollAt)
        assertEquals(GatewayOverallHealth.HEALTHY, snapshot.overall)
    }

    @Test
    fun `consecutiveFailuresAccumulateAndASuccessResetsThem`() {
        GatewayHealthRecorder.onPullStart(now - 3_000L)
        GatewayHealthRecorder.onPullFailure(
            kind = GatewayFailureKind.READ_TIMEOUT,
            safeDetail = "timed out",
            retryInMs = 5_000L,
            at = now
        )
        assertEquals(1, GatewayHealthRecorder.snapshot(now).pullBridge.consecutiveFailures)
        assertEquals(now + 5_000L, GatewayHealthRecorder.snapshot(now).pullBridge.nextRetryAt)

        GatewayHealthRecorder.onPullStart(now + 1_000L)
        GatewayHealthRecorder.onPullFailure(
            kind = GatewayFailureKind.READ_TIMEOUT,
            at = now + 1_000L
        )
        assertEquals(2, GatewayHealthRecorder.snapshot(now + 1_000L).pullBridge.consecutiveFailures)

        GatewayHealthRecorder.onPullEmpty(now + 2_000L)
        val recovered = GatewayHealthRecorder.snapshot(now + 2_000L)
        assertEquals(0, recovered.pullBridge.consecutiveFailures)
        assertNull(recovered.pullBridge.lastFailure)
        assertEquals(GatewayOverallHealth.HEALTHY, recovered.overall)
    }

    @Test
    fun `aRejectedKeyIsAnErrorAndKeepsTheHttpStatusAsAValue`() {
        GatewayHealthRecorder.onPullStart(now - 500L)
        GatewayHealthRecorder.onPullFailure(
            kind = GatewayFailureKind.HTTP_AUTH,
            httpStatus = 401,
            safeDetail = "HTTP 401",
            at = now
        )

        val snapshot = GatewayHealthRecorder.snapshot(now)

        assertEquals(401, snapshot.pullBridge.lastHttpStatus)
        assertEquals(GatewayFailureKind.HTTP_AUTH, snapshot.pullBridge.lastFailure)
        assertEquals("HTTP 401", snapshot.pullBridge.lastFailureSafeDetail)
        assertEquals(GatewayOverallHealth.ERROR, snapshot.overall)
        assertEquals(GatewayConclusion.AUTH_REJECTED, snapshot.conclusion)
    }

    @Test
    fun `aSuccessfulPollOutranksThePreviousFailure`() {
        GatewayHealthRecorder.onPullFailure(
            kind = GatewayFailureKind.HTTP_NOT_FOUND,
            httpStatus = 404,
            at = now - 60_000L
        )
        assertEquals(GatewayOverallHealth.ERROR, GatewayHealthRecorder.snapshot(now - 60_000L).overall)

        GatewayHealthRecorder.onPullEmpty(now - 1_000L)

        val snapshot = GatewayHealthRecorder.snapshot(now)
        assertEquals(GatewayOverallHealth.HEALTHY, snapshot.overall)
        assertNull(snapshot.pullBridge.lastFailure)
    }

    @Test
    fun `startingANewPollKeepsTheLastFailureVisibleUntilItSucceeds`() {
        GatewayHealthRecorder.onPullFailure(
            kind = GatewayFailureKind.HTTP_AUTH,
            httpStatus = 401,
            at = now - 5_000L
        )

        GatewayHealthRecorder.onPullStart(now)

        val snapshot = GatewayHealthRecorder.snapshot(now)
        assertEquals(
            "a retry is a hope, not evidence — the error stays on screen until a real success",
            GatewayFailureKind.HTTP_AUTH,
            snapshot.pullBridge.lastFailure
        )
        assertEquals(GatewayOverallHealth.ERROR, snapshot.overall)
    }

    @Test
    fun `aStaleSuccessfulPollDegradesEvenThoughThePollerIsRunning`() {
        GatewayHealthRecorder.setPollerRunning(true)
        GatewayHealthRecorder.onPullEmpty(now - GatewayHealthRules.PULL_FRESH_MS - 1L)

        val snapshot = GatewayHealthRecorder.snapshot(now)

        assertTrue(snapshot.pullBridge.running)
        assertEquals(GatewayOverallHealth.DEGRADED, snapshot.overall)
        assertEquals(GatewayConclusion.BRIDGE_NOT_POLLING, snapshot.conclusion)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // The ACK leg
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `aSuccessfulAckIsRecordedAndMirroredIntoTheQueue`() {
        GatewayHealthRecorder.onAckSuccess(now)

        val snapshot = GatewayHealthRecorder.snapshot(now)
        assertEquals(now, snapshot.pullBridge.lastAckAt)
        assertEquals(now, snapshot.eveQueue.lastGatewayAckAt)
        assertEquals(0, snapshot.pullBridge.ackFailures)
    }

    /**
     * THE FIELD BUG: the card showed "Last result ACKed: 25s ago" on the pull row and
     * "Last gateway ACK: never" on the EVE row — for the same event.
     *
     * `EveSmsQueue.healthSnapshot()` deliberately does not set `lastGatewayAckAt` (the ACK leg is
     * owned by whoever performed it), but the poller calls `setEveQueue` with a whole object
     * every ~25 seconds, and assigning it wholesale ERASED the stamp the ACK had just written.
     */
    @Test
    fun `aQueueSnapshotDoesNotEraseTheGatewayAckStamp`() {
        GatewayHealthRecorder.onAckSuccess(now)
        assertEquals(now, GatewayHealthRecorder.snapshot(now).eveQueue.lastGatewayAckAt)

        // The poller's periodic refresh, carrying the queue's own view (which has no ack stamp).
        GatewayHealthRecorder.setEveQueue(
            EveQueueHealth(queued = 2, active = 1, lastLocalTransitionAt = now + 1_000L)
        )

        val snapshot = GatewayHealthRecorder.snapshot(now + 1_000L)
        assertEquals(
            "the ACK stamp must survive the queue refresh",
            now,
            snapshot.eveQueue.lastGatewayAckAt
        )
        assertEquals("and the fresh queue counts must still land", 2, snapshot.eveQueue.queued)
        assertEquals(1, snapshot.eveQueue.active)
        // The two metrics describe ONE event, so they must agree.
        assertEquals(snapshot.pullBridge.lastAckAt, snapshot.eveQueue.lastGatewayAckAt)
    }

    @Test
    fun `anAckFailureIsCountedApartFromThePullFailure`() {
        GatewayHealthRecorder.onPullEmpty(now - 1_000L)
        GatewayHealthRecorder.onAckFailure(
            kind = GatewayFailureKind.HTTP_SERVER,
            httpStatus = 503,
            safeDetail = "HTTP 503",
            at = now
        )

        val snapshot = GatewayHealthRecorder.snapshot(now)

        assertEquals(1, snapshot.pullBridge.ackFailures)
        assertEquals(1, snapshot.pullBridge.ackConsecutiveFailures)
        assertEquals(GatewayFailureKind.HTTP_SERVER, snapshot.pullBridge.lastAckFailure)
        assertEquals("HTTP 503", snapshot.pullBridge.lastAckFailureSafeDetail)
        // A failed ACK is not a failed PULL: the bridge is still proven fresh.
        assertNull(snapshot.pullBridge.lastFailure)
        assertEquals(GatewayOverallHealth.DEGRADED, snapshot.overall)
        assertEquals(GatewayConclusion.ACK_FAILING, snapshot.conclusion)
    }

    @Test
    fun `aSuccessfulAckClearsCurrentFailuresButKeepsSessionHistory`() {
        GatewayHealthRecorder.onPullEmpty(now - 2_000L)
        repeat(2) {
            GatewayHealthRecorder.onAckFailure(GatewayFailureKind.HTTP_SERVER, 503, at = now - 1_000L)
        }
        GatewayHealthRecorder.onAckSuccess(now)

        val snapshot = GatewayHealthRecorder.snapshot(now)
        assertEquals(2, snapshot.pullBridge.ackFailures)
        assertEquals(0, snapshot.pullBridge.ackConsecutiveFailures)
        assertNull(snapshot.pullBridge.lastAckFailure)
        assertEquals(GatewayOverallHealth.HEALTHY, snapshot.overall)
    }

    /**
     * The uploader's consecutive-failure counter is a CURRENT-state signal, so a later
     * success must clear it — and clear the sticky failure kind with it. Otherwise one
     * transient 503 would keep the card degraded forever after the uploader recovered.
     */
    @Test
    fun `aSuccessfulUploadClearsTheCurrentUploadFailure`() {
        // The bridge must be fresh first, or `overall` degrades on BRIDGE_NOT_POLLING and the
        // upload rule is never reached — which would make this test pass for the wrong reason.
        GatewayHealthRecorder.onPullEmpty(now - 2_000L)
        repeat(2) {
            GatewayHealthRecorder.onUploadFailure(
                kind = GatewayFailureKind.HTTP_SERVER,
                httpStatus = 503,
                at = now - 1_000L
            )
        }
        assertEquals(2, GatewayHealthRecorder.snapshot(now).eventUpload.consecutiveFailures)
        assertEquals(GatewayOverallHealth.DEGRADED, GatewayHealthRecorder.snapshot(now).overall)

        GatewayHealthRecorder.onUploadSuccess(at = now, httpStatus = 200)

        val snapshot = GatewayHealthRecorder.snapshot(now)
        assertEquals(0, snapshot.eventUpload.consecutiveFailures)
        assertNull(snapshot.eventUpload.lastFailure)
        assertNull(snapshot.eventUpload.lastFailureAt)
        assertEquals(200, snapshot.eventUpload.lastHttpStatus)
        assertEquals(GatewayOverallHealth.HEALTHY, snapshot.overall)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Event upload must never be confused with the bridge
    // ═══════════════════════════════════════════════════════════════════════════

    /** The production bug, asserted from the recorder's side. */
    @Test
    fun `recordingAnUploadSuccessTouchesNothingInThePullBridge`() {
        GatewayHealthRecorder.setUploaderRunning(true)
        GatewayHealthRecorder.onUploadAttempt(now)
        GatewayHealthRecorder.onUploadSuccess(at = now, httpStatus = 200)
        GatewayHealthRecorder.setUploadQueue(pending = 0, sending = 0, deadLetter = 0)

        val snapshot = GatewayHealthRecorder.snapshot(now)

        assertTrue("the uploader really is healthy", snapshot.eventUpload.healthy)
        assertEquals(now, snapshot.eventUpload.lastSuccessAt)
        // …and the bridge knows nothing about it.
        assertNull(snapshot.pullBridge.lastSuccessfulPollAt)
        assertNull(snapshot.pullBridge.lastPollStartedAt)
        assertEquals(0, snapshot.pullBridge.consecutiveFailures)
        assertEquals(
            "an outbound ACK must never make the inbound bridge look healthy",
            GatewayOverallHealth.DEGRADED,
            snapshot.overall
        )
        assertEquals(GatewayConclusion.BRIDGE_NOT_POLLING, snapshot.conclusion)
    }

    @Test
    fun `anUploadFailureIsRecordedAsAnUploadFailure`() {
        GatewayHealthRecorder.setUploaderRunning(true)
        GatewayHealthRecorder.onUploadFailure(
            kind = GatewayFailureKind.HTTP_SERVER,
            httpStatus = 503,
            safeDetail = "HTTP 503",
            at = now
        )

        val snapshot = GatewayHealthRecorder.snapshot(now)

        assertFalse(snapshot.eventUpload.healthy)
        assertEquals(503, snapshot.eventUpload.lastHttpStatus)
        assertEquals(GatewayFailureKind.HTTP_SERVER, snapshot.eventUpload.lastFailure)
        assertNull(snapshot.pullBridge.lastFailure)
    }

    @Test
    fun `queueDepthIsReportedAsSeparatePendingSendingAndDeadLetterCounts`() {
        GatewayHealthRecorder.setUploadQueue(pending = 4, sending = 2, deadLetter = 1)

        val upload = GatewayHealthRecorder.snapshot(now).eventUpload

        assertEquals(4, upload.pending)
        assertEquals(2, upload.sending)
        assertEquals(1, upload.deadLetter)
        assertTrue("historical dead letters do not redefine live uploader health", upload.healthy)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Network, endpoint, queue
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `offlineIsReportedAsOfflineRatherThanError`() {
        GatewayHealthRecorder.onNetwork(validated = false, transport = "None", at = now)

        val snapshot = GatewayHealthRecorder.snapshot(now)

        assertFalse(snapshot.network.validatedInternet)
        assertEquals("None", snapshot.network.transport)
        assertEquals(GatewayOverallHealth.OFFLINE, snapshot.overall)
        assertEquals(GatewayConclusion.NO_NETWORK, snapshot.conclusion)
    }

    @Test
    fun `theConfiguredEndpointIsParsedSoTheTlsCheckHasAHost`() {
        GatewayHealthRecorder.setEndpointUrl("https://gmweb.example.com")

        val snapshot = GatewayHealthRecorder.snapshot(now)

        assertTrue(snapshot.endpoint.configured)
        assertEquals("gmweb.example.com", snapshot.endpoint.host)
        assertEquals(443, snapshot.endpoint.port)
    }

    @Test
    fun `anUnusableUrlReadsAsNotConfiguredRatherThanAsADnsFailure`() {
        GatewayHealthRecorder.setEndpointUrl("not a url")

        val snapshot = GatewayHealthRecorder.snapshot(now)

        assertFalse(snapshot.endpoint.configured)
        assertNull(snapshot.endpoint.host)
    }

    @Test
    fun `theTcpProbeLatencyAndTlsFactsAreExposed`() {
        GatewayHealthRecorder.onPullEmpty(now - 1_000L)
        GatewayHealthRecorder.onTcpProbe(latencyMs = 42L, at = now)
        GatewayHealthRecorder.onTlsProbe(
            TlsHealth(
                valid = true,
                protocol = "TLSv1.3",
                issuer = "Let's Encrypt",
                notAfter = now + 71L * 86_400_000L,
                hostMatched = true,
                lastCheckedAt = now
            )
        )

        val snapshot = GatewayHealthRecorder.snapshot(now)

        assertEquals(42L, snapshot.endpoint.lastTcpConnectMs)
        assertEquals(now, snapshot.endpoint.lastProbeAt)
        assertEquals("TLSv1.3", snapshot.tls.protocol)
        assertEquals(71L, snapshot.tls.daysUntilExpiry(now))
        assertEquals(GatewayOverallHealth.HEALTHY, snapshot.overall)
    }

    @Test
    fun `theEveQueueSnapshotIsExposed`() {
        GatewayHealthRecorder.setEveQueue(
            EveQueueHealth(queued = 2, active = 1, deferred = 1, failedRecent = 3)
        )
        GatewayHealthRecorder.onEveNativeSubmit(now)
        GatewayHealthRecorder.onEveLocalTransition(now - 500L)

        val queue = GatewayHealthRecorder.snapshot(now).eveQueue

        assertEquals(2, queue.queued)
        assertEquals(1, queue.active)
        assertEquals(1, queue.deferred)
        assertEquals(3, queue.failedRecent)
        assertEquals(now, queue.lastNativeSubmitAt)
        assertEquals(now - 500L, queue.lastLocalTransitionAt)
        assertFalse(queue.idle)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Reconnect
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * A reconnect is a REQUEST TO TRY AGAIN. It may clear the error so the card can say
     * "reconnecting", but it must not manufacture a success — going green on a button tap
     * is the same lie as going green on a component start.
     */
    @Test
    fun `aReconnectClearsTheErrorWithoutClaimingSuccess`() {
        GatewayHealthRecorder.onPullFailure(
            kind = GatewayFailureKind.HTTP_AUTH,
            httpStatus = 401,
            safeDetail = "HTTP 401",
            at = now - 10_000L
        )
        assertEquals(GatewayOverallHealth.ERROR, GatewayHealthRecorder.snapshot(now - 10_000L).overall)

        GatewayHealthRecorder.onReconnectRequested(now)

        val snapshot = GatewayHealthRecorder.snapshot(now)
        assertNull(snapshot.pullBridge.lastFailure)
        assertNull(snapshot.pullBridge.lastHttpStatus)
        assertNull("the last SUCCESS is not invented", snapshot.pullBridge.lastSuccessfulPollAt)
        assertEquals(
            "still not healthy — only a real poll may say that",
            GatewayOverallHealth.DEGRADED,
            snapshot.overall
        )
    }

    @Test
    fun `aReconnectKeepsAPreviouslyProvenPollFresh`() {
        GatewayHealthRecorder.onPullEmpty(now - 5_000L)

        GatewayHealthRecorder.onReconnectRequested(now)

        val snapshot = GatewayHealthRecorder.snapshot(now)
        assertEquals(now - 5_000L, snapshot.pullBridge.lastSuccessfulPollAt)
        assertEquals(GatewayOverallHealth.HEALTHY, snapshot.overall)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Reading and change notification
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `theRevisionAdvancesOnEveryRecordedChange`() {
        val start = GatewayHealthRecorder.revision.value

        GatewayHealthRecorder.setPollerRunning(true)
        GatewayHealthRecorder.onPullStart(now)
        GatewayHealthRecorder.onPullEmpty(now)

        assertEquals(start + 3, GatewayHealthRecorder.revision.value)
    }

    @Test
    fun `rawSnapshotCarriesTheDimensionsWithoutAVerdict`() {
        GatewayHealthRecorder.onPullEmpty(now)

        val raw = GatewayHealthRecorder.rawSnapshot(now)
        val derived = GatewayHealthRecorder.snapshot(now)

        assertEquals(GatewayOverallHealth.OFFLINE, raw.overall)
        assertEquals(GatewayConclusion.NONE, raw.conclusion)
        assertEquals(GatewayOverallHealth.HEALTHY, derived.overall)
        assertEquals(raw.pullBridge, derived.pullBridge)
    }

    @Test
    fun `aDisabledGatewayStaysOfflineNoMatterWhatTheComponentsObserved`() {
        GatewayHealthRecorder.onPullEmpty(now)
        GatewayHealthRecorder.setDesired(false)

        val snapshot = GatewayHealthRecorder.snapshot(now)

        assertEquals(GatewayOverallHealth.OFFLINE, snapshot.overall)
        assertEquals(GatewayConclusion.DISABLED, snapshot.conclusion)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Privacy
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `aFailureDetailIsBoundedToOneLineAndRedactsLongDigitRuns`() {
        val detail = GatewayHealthText.safeDetail(
            "connect to +989121234567 failed\n  second line\twith tab"
        )

        assertFalse("a detail is a caption, not a stack trace", detail!!.contains('\n'))
        assertFalse(detail.contains('\t'))
        assertFalse("a full number must never be stored", detail.contains("+989121234567"))
        assertTrue(detail.contains("id#"))
    }

    @Test
    fun `aFailureDetailIsCappedAndBlankBecomesNull`() {
        assertNull(GatewayHealthText.safeDetail(null))
        assertNull(GatewayHealthText.safeDetail("   "))

        val long = GatewayHealthText.safeDetail("x".repeat(500))!!
        assertTrue(long.length <= GatewayHealthText.MAX_LENGTH)
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun `theRecorderNeverStoresARawPhoneNumberFromAFailure`() {
        GatewayHealthRecorder.onPullFailure(
            kind = GatewayFailureKind.TCP_CONNECT,
            safeDetail = "failed to connect to /203.0.113.10 (port 443) from +989121234567",
            at = now
        )

        val stored = GatewayHealthRecorder.snapshot(now).pullBridge.lastFailureSafeDetail!!

        assertFalse(stored.contains("+989121234567"))
        assertTrue(
            "an IP is not user content and stays, so the report is still useful",
            stored.contains("203.0.113.10")
        )
    }
}
