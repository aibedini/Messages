package com.autonomousone.messages.gateway.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one-tap report.
 *
 * The report is the artifact the user COPIES and shares, so the redaction contract is the
 * part that has to be proven rather than intended. It is also the artifact that has to
 * answer "why is EVE not reaching this phone?" — so the two halves of the gateway must be
 * reported separately and the conclusion must name the one that is broken.
 */
class GatewayDiagnosticReportTest {

    private val now = 1_700_000_000_000L

    private fun snapshot(
        upload: EventUploadHealth = EventUploadHealth(
            running = true,
            lastAttemptAt = now - 8_000L,
            lastSuccessAt = now - 8_000L,
            lastHttpStatus = 200
        ),
        bridge: PullBridgeHealth = PullBridgeHealth(
            running = true,
            state = "POLLING",
            lastPollStartedAt = now - 20_000L,
            lastSuccessfulPollAt = now - 20_000L,
            lastEmptyPollAt = now - 20_000L,
            lastHttpStatus = 200
        ),
        auth: AuthHealth = AuthHealth(enrolled = true, lastVerifiedAt = now - 1_000L),
        tls: TlsHealth = TlsHealth(
            valid = true,
            protocol = "TLSv1.3",
            issuer = "Let's Encrypt",
            notAfter = now + 71L * 86_400_000L,
            hostMatched = true,
            lastCheckedAt = now
        ),
        queue: EveQueueHealth = EveQueueHealth(),
        network: NetworkHealth = NetworkHealth(validatedInternet = true, transport = "Wi-Fi"),
        endpoint: EndpointHealth = EndpointHealth(
            configured = true,
            host = "gmweb.46.31.76.103.nip.io",
            port = 443,
            lastTcpConnectMs = 42L,
            lastProbeAt = now
        )
    ): GatewayHealthSnapshot = GatewayHealthRules.evaluate(
        GatewayHealthSnapshot(
            generatedAt = now,
            desired = true,
            network = network,
            endpoint = endpoint,
            tls = tls,
            authentication = auth,
            eventUpload = upload,
            pullBridge = bridge,
            eveQueue = queue
        ),
        now
    )

    private fun render(
        snapshot: GatewayHealthSnapshot,
        probe: GatewayConnectivityResult? = null
    ) = GatewayDiagnosticReport.render(
        snapshot = snapshot,
        probe = probe,
        appVersion = "3.4.4 (111)",
        deliveryMode = "LEGACY_PULL",
        supervisorState = "CONNECTED",
        gatewayDesired = true,
        now = now
    )

    private fun probeResult(vararg steps: GatewayProbeStep) = GatewayConnectivityResult(
        startedAt = now - 5_000L,
        steps = steps.toList(),
        resolvedAddresses = listOf("46.31.76.103"),
        tls = TlsHealth(valid = true, protocol = "TLSv1.3", hostMatched = true)
    )

    // ═══════════════════════════════════════════════════════════════════════════
    // The shape the user reads
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `theReportNamesBothDirectionsSeparately`() {
        val text = render(snapshot())

        assertTrue(text.contains("Event upload (Android → GMweb)"))
        assertTrue(text.contains("Pull bridge (GMweb → Android)"))
        assertTrue(text.contains("App version: 3.4.4 (111)"))
        assertTrue(text.contains("Delivery mode: LEGACY_PULL"))
        assertTrue(text.contains("Supervisor: CONNECTED"))
    }

    @Test
    fun `theReportStatesTheVerdictAndAConclusion`() {
        val healthy = render(snapshot())
        assertTrue(healthy.contains("Verdict: HEALTHY"))
        assertTrue(healthy.contains("Everything the gateway needs is fresh."))
    }

    @Test
    fun `theProductionStateIsReportedAsBridgeNotPolling`() {
        val text = render(
            snapshot(
                // The outbound sync works …
                upload = EventUploadHealth(
                    running = true,
                    lastAttemptAt = now - 8_000L,
                    lastSuccessAt = now - 8_000L,
                    lastHttpStatus = 200
                ),
                // … while the inbound bridge has never succeeded.
                bridge = PullBridgeHealth(
                    running = true,
                    state = "POLLING",
                    lastPollStartedAt = now - 200_000L,
                    lastSuccessfulPollAt = null
                )
            )
        )

        assertTrue(text.contains("Verdict: DEGRADED"))
        assertTrue(text.contains("Android → GMweb"))
        assertTrue(text.contains("send requests are not arriving"))
        assertTrue(text.contains("Last successful poll: never"))
    }

    @Test
    fun `aRejectedKeyNamesTheKeyAsTheCause`() {
        val text = render(
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

        assertTrue(text.contains("Verdict: ERROR"))
        assertTrue(text.contains("HTTP_AUTH · HTTP 401"))
        assertTrue(text.contains("Check the API key"))
    }

    @Test
    fun `aCertificateProblemTellsTheUserTheCertificateMustCoverTheHost`() {
        val text = render(
            snapshot(
                tls = TlsHealth(
                    valid = true,
                    protocol = "TLSv1.3",
                    issuer = "Let's Encrypt",
                    notAfter = now + 30L * 86_400_000L,
                    hostMatched = false
                )
            )
        )

        assertTrue(text.contains("Certificate host match: NO"))
        assertTrue(text.contains("Certificate host match: NO"))
        assertTrue(text.contains("issued FOR the exact host"))
    }

    @Test
    fun `timesAreRenderedRelativeSoTheReportReadsWithoutArithmetic`() {
        val text = render(snapshot())

        assertTrue(text.contains("Last success: 8s ago"))
        assertTrue(text.contains("Last successful poll: 20s ago"))
        assertTrue("a never-observed field says so", render(snapshot(queue = EveQueueHealth())).contains("Last SIM submit: never"))
    }

    @Test
    fun `theProbeChainIsRenderedInOrderWhenPresent`() {
        val text = render(
            snapshot(),
            probeResult(
                GatewayProbeStep(GatewayProbeStage.NETWORK, GatewayProbeStatus.PASSED, detail = "Wi-Fi"),
                GatewayProbeStep(GatewayProbeStage.DNS, GatewayProbeStatus.PASSED, detail = "46.31.76.103"),
                GatewayProbeStep(GatewayProbeStage.TCP, GatewayProbeStatus.PASSED, durationMs = 42L),
                GatewayProbeStep(GatewayProbeStage.TLS, GatewayProbeStatus.PASSED, durationMs = 61L),
                GatewayProbeStep(GatewayProbeStage.HTTPS, GatewayProbeStatus.PASSED, httpStatus = 200),
                GatewayProbeStep(GatewayProbeStage.AUTH, GatewayProbeStatus.PASSED),
                GatewayProbeStep(GatewayProbeStage.PULL, GatewayProbeStatus.FAILED, detail = "no successful poll")
            )
        )

        assertTrue(text.contains("Connectivity probe:"))
        // The stage name, status and latency are on one aligned row.
        assertTrue(Regex("NETWORK\\s+PASSED\\s+Wi-Fi").containsMatchIn(text))
        assertTrue(Regex("TCP\\s+PASSED\\s+42ms").containsMatchIn(text))
        assertTrue(Regex("HTTPS\\s+PASSED").containsMatchIn(text))
        assertTrue(Regex("PULL\\s+FAILED").containsMatchIn(text))
        assertFalse(text.contains("not run"))
    }

    @Test
    fun `theReportSaysWhenDiagnosticsHaveNotBeenRun`() {
        val text = render(snapshot(), probe = null)

        assertTrue(text.contains("not run — tap Run diagnostics"))
    }

    @Test
    fun `theEveQueueSectionReportsTheChainStages`() {
        val text = render(
            snapshot(
                queue = EveQueueHealth(
                    queued = 1,
                    active = 1,
                    deferred = 1,
                    sentRecent = 3,
                    failedRecent = 1,
                    cancelledRecent = 2,
                    lastPulledRequestToken = "a1b2c3d4",
                    lastNativeSubmitAt = now - 4_000L,
                    lastGatewayAckAt = now - 3_000L
                )
            )
        )

        assertTrue(text.contains("Queued: 1 · Active: 1 · Deferred: 1"))
        assertTrue(text.contains("Sent: 3 · Failed: 1 · Cancelled: 2"))
        assertTrue(text.contains("Last pulled task: a1b2c3d4"))
        assertTrue(text.contains("Last SIM submit: 4s ago"))
        assertTrue(text.contains("Last gateway ACK: 3s ago"))
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Redaction — the promise that makes the report shareable
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `aPhoneNumberThatReachedADetailInAnyWayIsRedacted`() {
        val text = render(
            snapshot(
                bridge = PullBridgeHealth(
                    running = true,
                    lastPollStartedAt = now,
                    lastFailure = GatewayFailureKind.TCP_CONNECT,
                    // A detail that was NOT produced by safeDetail: the final sweep must
                    // catch it anyway, because one future call site is all it takes.
                    lastFailureSafeDetail = "failed to reach +989121234567"
                )
            )
        )

        assertFalse(text.contains("+989121234567"))
        assertTrue(text.contains("id#"))
    }

    @Test
    fun `theReportContainsNoSecretsAndNoMessageContent`() {
        val text = render(snapshot())

        // No secret ever ENTERS the snapshot — it holds counts, enums, statuses and redacted
        // captions — so this asserts the concrete shapes a leak would take: an auth header,
        // a bearer token, or key material.
        listOf(
            "X-API-Key", "X-Agent-Auth", "X-Agent-Id", "Bearer ",
            "BEGIN PRIVATE KEY", "-----BEGIN", "client_secret", "apiKey="
        ).forEach { secret ->
            assertFalse("the report must not contain $secret", text.contains(secret, ignoreCase = true))
        }
        assertTrue(
            "and it says so, so the user knows it is shareable",
            text.contains("never included in this report")
        )
    }

    @Test
    fun `anIpAddressSurvivesRedactionBecauseItIsNotUserContent`() {
        val text = render(snapshot())

        assertTrue(text.contains("46.31.76.103"))
        assertTrue(text.contains("gmweb.46.31.76.103.nip.io"))
    }

    @Test
    fun `anAlreadyTokenizedValueIsNotTokenizedTwice`() {
        val text = render(
            snapshot(
                bridge = PullBridgeHealth(
                    running = true,
                    lastPollStartedAt = now,
                    lastFailure = GatewayFailureKind.TCP_CONNECT,
                    lastFailureSafeDetail = GatewayHealthText.safeDetail("dial +989121234567 failed")
                )
            )
        )

        // Exactly one token, not a token of a token: `id#` plus 10 hex characters.
        val tokens = Regex("id#[0-9a-f]+").findAll(text).toList().map { it.value }
        assertEquals(1, tokens.size)
        assertEquals(13, tokens.first().length)
    }

    @Test
    fun `aVeryLongDetailIsClippedToOneLine`() {
        val text = render(
            snapshot(
                bridge = PullBridgeHealth(
                    running = true,
                    lastPollStartedAt = now,
                    lastFailure = GatewayFailureKind.UNKNOWN,
                    lastFailureSafeDetail = "x".repeat(5_000)
                )
            )
        )

        assertTrue(text.lines().all { it.length <= 200 })
    }

    @Test
    fun `theReportIsDeterministicApartFromTheGenerationTime`() {
        val first = render(snapshot())
        val second = render(snapshot())

        assertEquals(first, second)
    }
}
