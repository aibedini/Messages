package com.autonomousone.messages.gateway.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * The gateway health contract.
 *
 * Every test here is one of the ways the old single `CONNECTED` flag lied. The two that
 * matter most are the first two: an outbound event upload can never make the inbound
 * delivery bridge healthy, and "the components were started" is not a health claim.
 */
class GatewayHealthModelTest {

    private val now = 1_000_000_000L

    /** A bridge that has polled successfully and has nothing to do. */
    private fun healthyBridge(now: Long = this.now) = PullBridgeHealth(
        running = true,
        state = "POLLING",
        lastPollStartedAt = now - 3_000L,
        lastSuccessfulPollAt = now - 2_000L,
        lastEmptyPollAt = now - 2_000L,
        consecutiveFailures = 0
    )

    private fun snapshot(
        desired: Boolean = true,
        network: NetworkHealth = NetworkHealth(validatedInternet = true, transport = "Wi-Fi"),
        auth: AuthHealth = AuthHealth(status = AuthVerification.VERIFIED, lastVerifiedAt = now - 1_000L),
        upload: EventUploadHealth = EventUploadHealth(
            running = true,
            lastAttemptAt = now - 1_000L,
            lastSuccessAt = now - 1_000L,
            lastHttpStatus = 200
        ),
        bridge: PullBridgeHealth = healthyBridge(),
        tls: TlsHealth = TlsHealth(),
        endpoint: EndpointHealth = EndpointHealth(),
        queue: EveQueueHealth = EveQueueHealth()
    ) = GatewayHealthSnapshot(
        generatedAt = now,
        desired = desired,
        network = network,
        endpoint = endpoint,
        authentication = auth,
        eventUpload = upload,
        pullBridge = bridge,
        tls = tls,
        eveQueue = queue
    )

    private fun evaluate(snapshot: GatewayHealthSnapshot) =
        GatewayHealthRules.evaluate(snapshot, now)

    // ═══════════════════════════════════════════════════════════════════════════
    // The two lies the old flag told
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * `event ACK does NOT imply pull health`.
     *
     * This is the reported production state: `/api/v1/agent/events/batch` returns
     * "2/2 event(s) ACKed by GMweb" while `/gateway/pull` has never succeeded.
     */
    @Test
    fun `anEventUploadAckNeverMakesThePullBridgeHealthy`() {
        val result = evaluate(
            snapshot(
                upload = EventUploadHealth(
                    running = true,
                    lastAttemptAt = now - 2_000L,
                    lastSuccessAt = now - 2_000L,
                    lastHttpStatus = 200,
                    pending = 0,
                    deadLetter = 0
                ),
                // The poller is running locally but has NEVER had a successful poll.
                bridge = PullBridgeHealth(
                    running = true,
                    state = "POLLING",
                    lastPollStartedAt = now - 30_000L,
                    lastSuccessfulPollAt = null,
                    consecutiveFailures = 0,
                    currentRequestStartedAt = now - 30_000L
                )
            )
        )

        assertTrue("the uploader itself is fine", result.eventUpload.healthy)
        assertEquals(GatewayOverallHealth.DEGRADED, result.overall)
        assertEquals(GatewayConclusion.BRIDGE_NOT_POLLING, result.conclusion)
        assertFalse(result.overall == GatewayOverallHealth.HEALTHY)
    }

    /** `supervisor CONNECTED alone does NOT imply overall HEALTHY`. */
    @Test
    fun `startingTheComponentsIsNotAHealthClaim`() {
        val result = evaluate(
            snapshot(
                upload = EventUploadHealth(running = true),
                auth = AuthHealth(status = AuthVerification.VERIFIED),
                bridge = PullBridgeHealth(running = true, state = "IDLE")
            )
        )

        assertEquals(GatewayOverallHealth.STARTING, result.overall)
        assertEquals(GatewayConclusion.BRIDGE_NOT_POLLING, result.conclusion)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // The freshness rule
    // ═══════════════════════════════════════════════════════════════════════════

    /** `recent empty pull = bridge healthy`: HTTP 200 with no task is a SUCCESS. */
    @Test
    fun `aRecentEmptyLongPollIsAFullyHealthyBridge`() {
        val result = evaluate(
            snapshot(
                bridge = PullBridgeHealth(
                    running = true,
                    state = "POLLING",
                    lastPollStartedAt = now - 26_000L,
                    lastSuccessfulPollAt = now - 1_000L,
                    lastEmptyPollAt = now - 1_000L,
                    currentRequestStartedAt = now - 1_000L
                )
            )
        )

        assertEquals(GatewayOverallHealth.HEALTHY, result.overall)
        assertEquals(GatewayConclusion.NONE, result.conclusion)
    }

    /** `stale last successful pull = DEGRADED`. */
    @Test
    fun `aStaleSuccessfulPollDegradesTheBridge`() {
        val result = evaluate(
            snapshot(
                bridge = PullBridgeHealth(
                    running = true,
                    state = "POLLING",
                    lastPollStartedAt = now - 30_000L,
                    lastSuccessfulPollAt = now - (GatewayHealthRules.PULL_FRESH_MS + 1_000L)
                )
            )
        )

        assertEquals(GatewayOverallHealth.DEGRADED, result.overall)
        assertEquals(GatewayConclusion.BRIDGE_NOT_POLLING, result.conclusion)
    }

    @Test
    fun `aPollExactlyAtTheFreshnessBoundaryIsStillFresh`() {
        val result = evaluate(
            snapshot(
                bridge = PullBridgeHealth(
                    running = true,
                    lastPollStartedAt = now - 1_000L,
                    lastSuccessfulPollAt = now - GatewayHealthRules.PULL_FRESH_MS
                )
            )
        )

        assertEquals(GatewayOverallHealth.HEALTHY, result.overall)
    }

    @Test
    fun `stalenessIsReportedSoTheCardCanSayHowLong`() {
        val bridge = PullBridgeHealth(lastSuccessfulPollAt = now - 222_000L)

        assertEquals(222_000L, bridge.stalenessMs(now))
        assertNull(PullBridgeHealth().stalenessMs(now))
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Network
    // ═══════════════════════════════════════════════════════════════════════════

    /** `network offline → OFFLINE/WAITING, not ERROR`. */
    @Test
    fun `noNetworkIsOfflineRatherThanAnError`() {
        val beforeAnything = evaluate(
            snapshot(
                network = NetworkHealth(validatedInternet = false, transport = "None"),
                bridge = PullBridgeHealth(running = true, state = "IDLE")
            )
        )
        assertEquals(GatewayOverallHealth.OFFLINE, beforeAnything.overall)
        assertEquals(GatewayConclusion.NO_NETWORK, beforeAnything.conclusion)

        // Even a phone that was healthy a moment ago: the bridge went quiet because the
        // network did, and there is nothing in the app to fix.
        val wasHealthy = evaluate(
            snapshot(
                network = NetworkHealth(validatedInternet = false, transport = "None"),
                bridge = PullBridgeHealth(
                    running = true,
                    lastPollStartedAt = now - 200_000L,
                    lastSuccessfulPollAt = now - 200_000L
                )
            )
        )
        assertEquals(GatewayOverallHealth.DEGRADED, wasHealthy.overall)
        assertEquals(GatewayConclusion.NO_NETWORK, wasHealthy.conclusion)
    }

    @Test
    fun `aDisabledGatewayIsOfflineRegardlessOfAnythingElse`() {
        val result = evaluate(snapshot(desired = false))

        assertEquals(GatewayOverallHealth.OFFLINE, result.overall)
        assertEquals(GatewayConclusion.DISABLED, result.conclusion)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Failures
    // ═══════════════════════════════════════════════════════════════════════════

    /** `HTTP 401 → AUTH error`, immediately, because retrying a rejected key cannot help. */
    @Test
    fun `http401IsAnAuthErrorOnTheFirstOccurrence`() {
        val result = evaluate(
            snapshot(
                bridge = PullBridgeHealth(
                    running = true,
                    lastPollStartedAt = now - 1_000L,
                    lastHttpStatus = 401,
                    lastFailure = GatewayFailureKind.HTTP_AUTH,
                    consecutiveFailures = 1
                )
            )
        )

        assertEquals(GatewayOverallHealth.ERROR, result.overall)
        assertEquals(GatewayConclusion.AUTH_REJECTED, result.conclusion)
        assertTrue(result.pullBridge.lastFailure!!.needsConfigurationChange)
    }

    @Test
    fun `aTransientPollFailureIsDegradedUntilItPersists`() {
        val once = evaluate(
            snapshot(
                bridge = PullBridgeHealth(
                    running = true,
                    lastPollStartedAt = now - 1_000L,
                    lastFailure = GatewayFailureKind.READ_TIMEOUT,
                    consecutiveFailures = 1
                )
            )
        )
        assertEquals(GatewayOverallHealth.DEGRADED, once.overall)
        assertEquals(GatewayConclusion.BRIDGE_FAILING, once.conclusion)

        val persistent = evaluate(
            snapshot(
                bridge = PullBridgeHealth(
                    running = true,
                    lastPollStartedAt = now - 1_000L,
                    lastFailure = GatewayFailureKind.READ_TIMEOUT,
                    consecutiveFailures = GatewayHealthRules.PERSISTENT_FAILURE_THRESHOLD
                )
            )
        )
        assertEquals(GatewayOverallHealth.ERROR, persistent.overall)
    }

    @Test
    fun `serverUnreachableAndDnsFailuresHaveTheirOwnConclusions`() {
        val tcp = evaluate(
            snapshot(
                bridge = PullBridgeHealth(
                    lastPollStartedAt = now,
                    lastFailure = GatewayFailureKind.TCP_CONNECT,
                    consecutiveFailures = 1
                )
            )
        )
        assertEquals(GatewayConclusion.SERVER_UNREACHABLE, tcp.conclusion)

        val dns = evaluate(
            snapshot(
                bridge = PullBridgeHealth(
                    lastPollStartedAt = now,
                    lastFailure = GatewayFailureKind.DNS,
                    consecutiveFailures = 1
                )
            )
        )
        assertEquals(GatewayConclusion.DNS_PROBLEM, dns.conclusion)

        val wrongUrl = evaluate(
            snapshot(
                bridge = PullBridgeHealth(
                    lastPollStartedAt = now,
                    lastFailure = GatewayFailureKind.HTTP_NOT_FOUND,
                    consecutiveFailures = 1
                )
            )
        )
        assertEquals(GatewayConclusion.WRONG_URL, wrongUrl.conclusion)
    }

    /** `TLS mismatch → TLS error`, from a probe that reported it even before any poll. */
    @Test
    fun `aTlsProbeFailureIsAnErrorEvenWithAFreshPoll`() {
        val result = evaluate(
            snapshot(tls = TlsHealth(valid = true, hostMatched = false, lastCheckedAt = now))
        )

        assertEquals(GatewayOverallHealth.ERROR, result.overall)
        assertEquals(GatewayConclusion.TLS_PROBLEM, result.conclusion)
    }

    /**
     * ONLY an explicit rejection is a fault.
     *
     * This test used to pass `enrolled = false` as an auth failure and expect ERROR /
     * AUTH_REJECTED — precisely the conflation that produced the field false alarm: "we could
     * not check" rendered as "your key was rejected".
     */
    @Test
    fun `theAuthenticationDimensionIsRequired`() {
        val rejected = evaluate(snapshot(auth = AuthHealth(status = AuthVerification.REJECTED)))

        assertEquals(GatewayOverallHealth.ERROR, rejected.overall)
        assertEquals(GatewayConclusion.AUTH_REJECTED, rejected.conclusion)
    }

    /** `not verified` must never be reported as `rejected`. */
    @Test
    fun `anUnverifiableCredentialIsNotARejection`() {
        listOf(AuthVerification.UNKNOWN, AuthVerification.UNVERIFIABLE).forEach { status ->
            val result = evaluate(
                snapshot(
                    auth = AuthHealth(
                        status = status,
                        unverifiableReason = "HTTP 400 (HTTP_BAD_REQUEST)"
                    )
                )
            )

            assertFalse(
                "$status must not be rendered as a rejected credential",
                result.conclusion == GatewayConclusion.AUTH_REJECTED
            )
            assertFalse(result.overall == GatewayOverallHealth.ERROR)
        }

        // UNVERIFIABLE is surfaced as its own honest conclusion when nothing more urgent is
        // wrong — not silently swallowed, and not escalated into a rejection.
        val unverifiable = evaluate(
            snapshot(auth = AuthHealth(status = AuthVerification.UNVERIFIABLE))
        )
        assertEquals(GatewayConclusion.AUTH_UNVERIFIED, unverifiable.conclusion)
    }

    /**
     * A 400 must be reported as a REQUEST/contract problem, never as a bad credential — the
     * exact defect the device acceptance run caught.
     */
    @Test
    fun `aBadRequestIsAContractMismatchAndNeverARejectedKey`() {
        val result = evaluate(
            snapshot(
                bridge = PullBridgeHealth(
                    running = true,
                    lastPollStartedAt = now - 1_000L,
                    lastHttpStatus = 400,
                    lastFailure = GatewayFailureKind.HTTP_BAD_REQUEST,
                    lastFailureSafeDetail = "HTTP 400 · server said: unprocessable body",
                    consecutiveFailures = 1
                )
            )
        )

        assertEquals(GatewayConclusion.REQUEST_CONTRACT_MISMATCH, result.conclusion)
        assertFalse(
            "a 400 must never be reported as a rejected key",
            result.conclusion == GatewayConclusion.AUTH_REJECTED
        )
        assertTrue(GatewayFailureKind.HTTP_BAD_REQUEST.isRequestContractMismatch)
        assertFalse(GatewayFailureKind.HTTP_BAD_REQUEST.isAuthenticationRejection)
        assertFalse(
            "a contract mismatch is not something the user fixes by changing settings",
            GatewayFailureKind.HTTP_BAD_REQUEST.needsConfigurationChange
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // The uploader's own rules
    // ═══════════════════════════════════════════════════════════════════════════

    /** `no events pending → event uploader can still be healthy`. */
    @Test
    fun `anEmptyUploadQueueIsHealthy`() {
        val upload = EventUploadHealth(
            running = true,
            pending = 0,
            sending = 0,
            deadLetter = 0,
            lastAttemptAt = null,
            lastSuccessAt = null
        )

        assertTrue("a quiet device is not a broken one", upload.healthy)
        // …and it is not reported as a stalled sync either.
        val result = evaluate(snapshot(upload = upload))
        assertEquals(GatewayOverallHealth.HEALTHY, result.overall)
        assertEquals(GatewayConclusion.NONE, result.conclusion)
    }

    @Test
    fun `historicalDeadLettersDoNotMakeCurrentUploadFail`() {
        val upload = EventUploadHealth(
            running = true,
            deadLetter = 309,
            pending = 0,
            sending = 0,
            lastAttemptAt = now - 60_000L,
            lastSuccessAt = now - 60_000L,
            lastHttpStatus = 200
        )
        val result = evaluate(snapshot(upload = upload))

        assertTrue(upload.healthy)
        assertEquals(GatewayOverallHealth.HEALTHY, result.overall)
        assertEquals(GatewayConclusion.HISTORICAL_FAILURES, result.conclusion)
        // The bridge is proven fresh by its own polling, and the uploader's rows say so.
        assertTrue(result.pullBridge.freshWithin(GatewayHealthRules.PULL_FRESH_MS, now))
    }

    /** Excluding dead letters must not blind the rule to work that is genuinely stuck. */
    @Test
    fun `aStaleQueuedUploadDegradesEvenWithNoRecordedFailureKind`() {
        val upload = EventUploadHealth(
            running = true,
            pending = 4,
            sending = 1,
            lastAttemptAt = now - GatewayHealthRules.UPLOAD_FRESH_MS - 1,
            lastSuccessAt = now - 60_000L,
            lastHttpStatus = 200
        )
        val result = evaluate(snapshot(upload = upload))

        assertTrue(upload.hasActiveFailure(now))
        assertEquals(GatewayOverallHealth.DEGRADED, result.overall)
        assertEquals(GatewayConclusion.UPLOAD_STALLED, result.conclusion)
    }

    @Test
    fun `aQueuedUploadExactlyAtTheFreshnessBoundaryIsNotStale`() {
        val upload = EventUploadHealth(
            running = true,
            pending = 4,
            lastAttemptAt = now - GatewayHealthRules.UPLOAD_FRESH_MS
        )

        assertFalse(upload.hasActiveFailure(now))
        assertEquals(GatewayOverallHealth.HEALTHY, evaluate(snapshot(upload = upload)).overall)
    }

    /** A queue that has never been attempted is stuck, not quiet. */
    @Test
    fun `queuedWorkThatWasNeverAttemptedIsAFailureRatherThanAHealthClaim`() {
        val upload = EventUploadHealth(running = true, pending = 4, lastAttemptAt = null)

        assertTrue(upload.hasActiveFailure(now))
        assertEquals(GatewayConclusion.UPLOAD_STALLED, evaluate(snapshot(upload = upload)).conclusion)
    }

    /**
     * The uploader's own consecutive-failure count degrades on its own, exactly as the ACK
     * leg does, and must not require a second signal to be believed.
     */
    @Test
    fun `consecutiveUploadFailuresDegradeWithAnEmptyQueue`() {
        val upload = EventUploadHealth(
            running = true,
            pending = 0,
            sending = 0,
            lastAttemptAt = now - 1_000L,
            consecutiveFailures = 2
        )

        assertTrue(upload.hasActiveFailure(now))
        assertEquals(GatewayOverallHealth.DEGRADED, evaluate(snapshot(upload = upload)).overall)
    }

    @Test
    fun `anUploadFailureIsReportedSeparatelyFromTheBridge`() {
        val upload = EventUploadHealth(
            running = true,
            lastAttemptAt = now - 1_000L,
            lastHttpStatus = 503,
            lastFailure = GatewayFailureKind.HTTP_SERVER,
            lastFailureSafeDetail = "HTTP 503"
        )
        val result = evaluate(snapshot(upload = upload))

        assertFalse(upload.healthy)
        assertEquals(GatewayOverallHealth.DEGRADED, result.overall)
        assertEquals(GatewayConclusion.UPLOAD_STALLED, result.conclusion)
    }

    @Test
    fun `anUploadIsOnlyRecentlyUploadedWhenItActuallySucceededRecently`() {
        assertFalse(EventUploadHealth(running = true).uploadedWithin(GatewayHealthRules.UPLOAD_FRESH_MS, now))
        assertTrue(
            EventUploadHealth(running = true, lastSuccessAt = now - 5_000L)
                .uploadedWithin(GatewayHealthRules.UPLOAD_FRESH_MS, now)
        )
        assertFalse(
            EventUploadHealth(running = true, lastSuccessAt = now - GatewayHealthRules.UPLOAD_FRESH_MS - 1L)
                .uploadedWithin(GatewayHealthRules.UPLOAD_FRESH_MS, now)
        )
    }

    @Test
    fun `theQueueIsIdleOnlyWhenNothingIsWaiting`() {
        assertTrue(EveQueueHealth().idle)
        assertFalse(EveQueueHealth(queued = 1).idle)
        assertFalse(EveQueueHealth(active = 1).idle)
        assertFalse(EveQueueHealth(deferred = 1).idle)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Failure classification
    // ═══════════════════════════════════════════════════════════════════════════

    /** `UnknownHost → DNS error`. */
    @Test
    fun `aDnsFailureIsClassifiedAsDns`() {
        assertEquals(
            GatewayFailureKind.DNS,
            GatewayFailureKind.classify(error = UnknownHostException("gmweb.invalid"))
        )
    }

    @Test
    fun `aRefusedConnectionIsClassifiedAsTcpConnect`() {
        assertEquals(
            GatewayFailureKind.TCP_CONNECT,
            GatewayFailureKind.classify(error = ConnectException("Connection refused"))
        )
        assertEquals(
            GatewayFailureKind.TCP_CONNECT,
            GatewayFailureKind.classify(error = NoRouteToHostException("No route"))
        )
    }

    /** A connect-phase timeout is "could not reach the server", not "the server went quiet". */
    @Test
    fun `timeoutsAreClassifiedByThePhaseTheyHappenedIn`() {
        val timeout = SocketTimeoutException("timed out")
        assertEquals(
            GatewayFailureKind.TCP_CONNECT,
            GatewayFailureKind.classify(error = timeout, phase = GatewayRequestPhase.CONNECT)
        )
        assertEquals(
            GatewayFailureKind.READ_TIMEOUT,
            GatewayFailureKind.classify(error = timeout, phase = GatewayRequestPhase.READ)
        )
        assertEquals(
            GatewayFailureKind.WRITE_TIMEOUT,
            GatewayFailureKind.classify(error = timeout, phase = GatewayRequestPhase.WRITE)
        )
        // Unknown phase still lands on the read timeout rather than on UNKNOWN.
        assertEquals(
            GatewayFailureKind.READ_TIMEOUT,
            GatewayFailureKind.classify(error = timeout)
        )
    }

    /** `TLS mismatch → TLS error`, and the mismatch must not be mislabelled as a handshake. */
    @Test
    fun `tlsFailuresAndHostnameMismatchesAreClassifiedAsTls`() {
        assertEquals(
            GatewayFailureKind.TLS,
            GatewayFailureKind.classify(error = SSLHandshakeException("certificate verify failed"))
        )
        assertEquals(
            GatewayFailureKind.TLS,
            GatewayFailureKind.classify(
                error = SSLPeerUnverifiedException("Hostname 203.0.113.10 not verified")
            )
        )
    }

    @Test
    fun `aTlsFailureIsNeverTransient`() {
        assertFalse(GatewayFailureKind.TLS.isTransient)
        assertFalse(GatewayFailureKind.HTTP_AUTH.isTransient)
        assertFalse(GatewayFailureKind.HTTP_NOT_FOUND.isTransient)
        assertTrue(GatewayFailureKind.HTTP_SERVER.isTransient)
        assertTrue(GatewayFailureKind.NETWORK_OFFLINE.isTransient)
    }

    @Test
    fun `httpStatusesMapToDistinctFailureKinds`() {
        assertEquals(null, GatewayFailureKind.fromHttpStatus(200))
        assertEquals(null, GatewayFailureKind.fromHttpStatus(204))
        assertEquals(GatewayFailureKind.HTTP_AUTH, GatewayFailureKind.fromHttpStatus(401))
        assertEquals(GatewayFailureKind.HTTP_FORBIDDEN, GatewayFailureKind.fromHttpStatus(403))
        assertEquals(GatewayFailureKind.HTTP_NOT_FOUND, GatewayFailureKind.fromHttpStatus(404))
        assertEquals(GatewayFailureKind.HTTP_CONFLICT, GatewayFailureKind.fromHttpStatus(409))
        assertEquals(GatewayFailureKind.HTTP_RATE_LIMITED, GatewayFailureKind.fromHttpStatus(429))
        assertEquals(GatewayFailureKind.HTTP_SERVER, GatewayFailureKind.fromHttpStatus(500))
        assertEquals(GatewayFailureKind.HTTP_SERVER, GatewayFailureKind.fromHttpStatus(503))
        // Other 4xx are the server refusing the request SHAPE: not transient, not a 5xx.
        assertEquals(GatewayFailureKind.VALIDATION_FAILED, GatewayFailureKind.fromHttpStatus(422))
    }

    /**
     * The status is the more specific fact: a 401 whose error body timed out is still an
     * authentication problem, and reporting it as a timeout would send the user to look at
     * their network instead of their key.
     */
    @Test
    fun `anHttpStatusWinsOverACoincidentalException`() {
        assertEquals(
            GatewayFailureKind.HTTP_AUTH,
            GatewayFailureKind.classify(
                error = SocketTimeoutException("timed out"),
                httpStatus = 401,
                phase = GatewayRequestPhase.READ
            )
        )
    }

    @Test
    fun `anOfflineNetworkIsReportedAsSuchWhenNothingElseExplainsTheFailure`() {
        assertEquals(
            GatewayFailureKind.NETWORK_OFFLINE,
            GatewayFailureKind.classify(networkValidated = false)
        )
        // …but a real status still wins, because it proves the network worked.
        assertEquals(
            GatewayFailureKind.HTTP_SERVER,
            GatewayFailureKind.classify(httpStatus = 502, networkValidated = false)
        )
    }

    @Test
    fun `anUnclassifiableFailureStaysUnknownAndNeverBecomesASpecificOne`() {
        assertEquals(GatewayFailureKind.NONE, GatewayFailureKind.classify())
        assertEquals(
            GatewayFailureKind.UNKNOWN,
            GatewayFailureKind.classify(error = IllegalStateException("something else"))
        )
    }

    @Test
    fun `aWrappedCauseIsStillClassified`() {
        val wrapped = RuntimeException("request failed", UnknownHostException("nope"))

        assertEquals(GatewayFailureKind.DNS, GatewayFailureKind.classify(error = wrapped))
    }

    @Test
    fun `needsConfigurationChangeIsReservedForThingsRetryingCannotFix`() {
        assertTrue(GatewayFailureKind.HTTP_AUTH.needsConfigurationChange)
        assertTrue(GatewayFailureKind.HTTP_NOT_FOUND.needsConfigurationChange)
        assertTrue(GatewayFailureKind.TLS.needsConfigurationChange)
        assertFalse(GatewayFailureKind.READ_TIMEOUT.needsConfigurationChange)
        assertFalse(GatewayFailureKind.NETWORK_OFFLINE.needsConfigurationChange)
        assertFalse(GatewayFailureKind.NONE.needsConfigurationChange)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Certificate facts
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `certificateExpiryIsReportedInDays`() {
        val tls = TlsHealth(valid = true, notAfter = now + 71L * 86_400_000L)

        assertEquals(71L, tls.daysUntilExpiry(now))
        assertEquals(-1L, TlsHealth(notAfter = now - 86_400_000L).daysUntilExpiry(now))
        assertNull(TlsHealth().daysUntilExpiry(now))
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // The acceptance cases, end to end
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `acceptance1 gmwebReachableAgentUploadsWorkButPullIsBroken`() {
        val result = evaluate(
            snapshot(
                upload = EventUploadHealth(
                    running = true,
                    lastAttemptAt = now - 8_000L,
                    lastSuccessAt = now - 8_000L,
                    lastHttpStatus = 200
                ),
                bridge = PullBridgeHealth(
                    running = true,
                    state = "ERROR",
                    lastPollStartedAt = now - 222_000L,
                    lastSuccessfulPollAt = null,
                    consecutiveFailures = 4,
                    lastHttpStatus = 404,
                    lastFailure = GatewayFailureKind.HTTP_NOT_FOUND,
                    lastFailureSafeDetail = "HTTP 404"
                )
            )
        )

        assertEquals(GatewayOverallHealth.ERROR, result.overall)
        assertEquals(GatewayConclusion.WRONG_URL, result.conclusion)
        assertTrue(result.eventUpload.healthy)
        assertFalse(result.pullBridge.freshWithin(GatewayHealthRules.PULL_FRESH_MS, now))
    }

    @Test
    fun `acceptance2 anEmptyPullIsHealthy`() {
        val result = evaluate(
            snapshot(
                bridge = PullBridgeHealth(
                    running = true,
                    state = "POLLING",
                    lastPollStartedAt = now - 20_000L,
                    lastSuccessfulPollAt = now - 20_000L,
                    lastEmptyPollAt = now - 20_000L,
                    lastHttpStatus = 200
                )
            )
        )

        assertEquals(GatewayOverallHealth.HEALTHY, result.overall)
    }

    @Test
    fun `acceptance3 aWrongApiKeyIsAnAuthErrorWithinOnePoll`() {
        val result = evaluate(
            snapshot(
                bridge = PullBridgeHealth(
                    running = true,
                    lastPollStartedAt = now - 500L,
                    lastHttpStatus = 401,
                    lastFailure = GatewayFailureKind.HTTP_AUTH,
                    lastFailureSafeDetail = "HTTP 401",
                    consecutiveFailures = 1
                )
            )
        )

        assertEquals(GatewayOverallHealth.ERROR, result.overall)
        assertEquals(GatewayConclusion.AUTH_REJECTED, result.conclusion)
    }

    @Test
    fun `acceptance4 aCertificateProblemIsATlsError`() {
        val result = evaluate(
            snapshot(
                tls = TlsHealth(
                    valid = false,
                    hostMatched = false,
                    issuer = "Let's Encrypt",
                    notAfter = now + 30L * 86_400_000L,
                    lastCheckedAt = now
                )
            )
        )

        assertEquals(GatewayOverallHealth.ERROR, result.overall)
        assertEquals(GatewayConclusion.TLS_PROBLEM, result.conclusion)
    }

    @Test
    fun `acceptance5 aDeadServerIsUnreachableAndNotGenericDisconnected`() {
        val result = evaluate(
            snapshot(
                endpoint = EndpointHealth(
                    configured = true,
                    host = "203.0.113.10",
                    port = 443,
                    lastTcpConnectMs = null,
                    lastProbeAt = now
                ),
                bridge = PullBridgeHealth(
                    running = true,
                    lastPollStartedAt = now - 2_000L,
                    lastFailure = GatewayFailureKind.TCP_CONNECT,
                    consecutiveFailures = 2
                )
            )
        )

        assertEquals(GatewayOverallHealth.DEGRADED, result.overall)
        assertEquals(GatewayConclusion.SERVER_UNREACHABLE, result.conclusion)
    }

    /** The card can only be trusted if both derivations agree in one call. */
    @Test
    fun `evaluateFillsInBothOverallAndConclusion`() {
        val raw = snapshot(
            bridge = PullBridgeHealth(
                running = true,
                lastPollStartedAt = now,
                lastFailure = GatewayFailureKind.HTTP_AUTH,
                consecutiveFailures = 1
            )
        )

        assertEquals(GatewayOverallHealth.OFFLINE, raw.overall) // not yet derived
        val derived = GatewayHealthRules.evaluate(raw, now)
        assertEquals(GatewayOverallHealth.ERROR, derived.overall)
        assertEquals(GatewayConclusion.AUTH_REJECTED, derived.conclusion)
        assertEquals(raw.generatedAt, derived.generatedAt)
        assertEquals(raw.network, derived.network)
    }
}
