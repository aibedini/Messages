package com.autonomousone.messages.gateway.health

import com.autonomousone.messages.sync.CommandSyncState
import com.autonomousone.messages.sync.HistorySyncState
import com.autonomousone.messages.sync.ReplicationInputs
import com.autonomousone.messages.sync.ReplicationPrerequisiteEvaluator
import com.autonomousone.messages.sync.ReplicationPrerequisites
import com.autonomousone.messages.sync.ReplicationState
import com.autonomousone.messages.sync.diagnostics.SyncDiagnostics
import org.json.JSONObject
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
        auth: AuthHealth = AuthHealth(status = AuthVerification.VERIFIED, lastVerifiedAt = now - 1_000L),
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
            host = "gmweb.example.com",
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
        resolvedAddresses = listOf("203.0.113.10"),
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
        // A 401 IS an authentication response, so claiming a rejection is correct here — and
        // the wording matches the field's own label ("Device key").
        assertTrue(text.contains("Check the device key"))
    }

    /**
     * The flip side, and the defect the device run caught: a 400 must NOT be reported as a
     * rejected key.
     */
    @Test
    fun `aBadRequestBlamesTheRequestAndNotTheKey`() {
        val text = render(
            snapshot(
                bridge = PullBridgeHealth(
                    running = true,
                    lastPollStartedAt = now - 500L,
                    lastHttpStatus = 400,
                    lastFailure = GatewayFailureKind.HTTP_BAD_REQUEST,
                    lastFailureSafeDetail = "HTTP 400 · server said: unprocessable body",
                    consecutiveFailures = 1
                )
            )
        )

        assertTrue(text.contains("HTTP_BAD_REQUEST"))
        assertTrue(text.contains("NOT a rejected key"))
        assertFalse(
            "a 400 must never produce an auth-rejection verdict",
            text.contains("Verdict: ERROR\nConclusion: GMweb rejected")
        )
        assertFalse(text.contains("Check the device key"))
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
                GatewayProbeStep(GatewayProbeStage.DNS, GatewayProbeStatus.PASSED, detail = "203.0.113.10"),
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
        // The address must come from the PROBE's resolved addresses. An earlier version of this
        // test passed by accident: the fixture hostname happened to CONTAIN the IP as a
        // substring, so it asserted nothing about redaction at all.
        val text = render(
            snapshot(),
            probeResult(
                GatewayProbeStep(GatewayProbeStage.NETWORK, GatewayProbeStatus.PASSED, detail = "Wi-Fi"),
                GatewayProbeStep(GatewayProbeStage.DNS, GatewayProbeStatus.PASSED, detail = "203.0.113.10")
            )
        )

        assertTrue("the resolved address must be reported", text.contains("203.0.113.10"))
        assertTrue("and the configured host with it", text.contains("gmweb.example.com"))
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

    // ═══════════════════════════════════════════════════════════════════════════
    // The prerequisite verdict (mission §57)
    // ═══════════════════════════════════════════════════════════════════════════

    private fun prerequisites(
        gatewayEnabled: Boolean = true,
        consentGranted: Boolean = true,
        serverOriginConfigured: Boolean = true,
        identityRegistered: Boolean = true,
        authRejected: Boolean = false,
        deviceRevoked: Boolean = false,
        cryptoKeyAvailable: Boolean = true,
        telephonyPermissionGranted: Boolean = true,
        historyGrantPresent: Boolean = true,
        networkValidated: Boolean = true
    ) = ReplicationPrerequisiteEvaluator.evaluate(
        ReplicationInputs(
            gatewayEnabled, consentGranted, serverOriginConfigured, identityRegistered,
            authRejected, deviceRevoked, cryptoKeyAvailable, telephonyPermissionGranted,
            historyGrantPresent, networkValidated
        )
    )

    private fun renderWithPrerequisites(
        prerequisites: ReplicationPrerequisites
    ) = GatewayDiagnosticReport.render(
        snapshot = snapshot(),
        probe = null,
        appVersion = "3.4.9 (116)",
        deliveryMode = "LEGACY_PULL",
        supervisorState = "CONNECTED",
        gatewayDesired = true,
        diagnostics = diagnosticsFor(prerequisites),
        now = now
    )

    /**
     * A [SyncDiagnostics] carrying only what these assertions care about.
     *
     * Deliberately sparse: the unmeasured fields stay null so the rendered `?` markers are
     * exercised rather than accidentally replaced by zeroes.
     */
    private fun diagnosticsFor(
        prerequisites: ReplicationPrerequisites,
        telephonyRelayRunning: Boolean? = null,
        scanComplete: Boolean? = false,
        delivered: Boolean? = null,
        reconciliation: SyncDiagnostics.ReconcileSection? = null,
        session: SyncDiagnostics.HistorySessionSection? = null
    ) = SyncDiagnostics(
        generatedAt = now,
        application = SyncDiagnostics.ApplicationSection(null, null, null),
        identity = SyncDiagnostics.IdentitySection(null, null, null, null),
        gateway = SyncDiagnostics.GatewaySection(null, null, null, null),
        replication = SyncDiagnostics.ReplicationSection(
            state = ReplicationState.READY,
            readyCount = 3, inFlightCount = 0, retryWaitCount = 1, deadLetterCount = 0,
            ackedCount = 309, oldestPendingAgeMs = 90_000L,
            lastUploadAttemptAt = now - 5_000L, lastSuccessfulUploadAt = now - 5_000L,
            lastHttpStatus = 200,
            lastBatchSize = null, lastAccepted = null, lastDuplicates = null, lastRejected = null
        ),
        history = SyncDiagnostics.HistorySection(
            state = HistorySyncState.SCANNING,
            grant = "FULL_HISTORY",
            keyVersion = null,
            sessionId = null,
            sources = listOf(
                SyncDiagnostics.HistorySourceSection(
                    source = "sms", scanned = null, enqueued = 900, acked = 850, pending = 50,
                    skipped = null, failed = null,
                    checkpointDate = 1_600_000_000_000L, checkpointProviderId = 4242L,
                    scanComplete = scanComplete,
                    delivered = delivered,
                    session = session
                )
            )
        ),
        commands = SyncDiagnostics.CommandSection(
            state = CommandSyncState.READY,
            received = 4, claimed = 3, executing = 0, completed = 3, failed = 0,
            lastCommandAt = now - 60_000L, lastCommandType = "SEND_SMS"
        ),
        prerequisites = prerequisites,
        telephonyRelayRunning = telephonyRelayRunning,
        reconciliation = reconciliation
    )

    // ── The missed-message check (mission §70) ───────────────────────────────

    private fun renderReconcile(section: SyncDiagnostics.ReconcileSection?) =
        GatewayDiagnosticReport.render(
            snapshot = snapshot(), probe = null, appVersion = "3.4.9 (116)",
            deliveryMode = "LEGACY_PULL", supervisorState = "CONNECTED", gatewayDesired = true,
            diagnostics = diagnosticsFor(prerequisites(), reconciliation = section),
            now = now
        )

    @Test
    fun `aCleanReconciliationSaysWhatItExamined`() {
        val text = renderReconcile(
            SyncDiagnostics.ReconcileSection(
                examined = 500, recovered = 0, windowHours = 48, ranAt = now - 60_000L
            )
        )

        assertTrue(text, text.contains("Missed-message check: examined 500 message(s)"))
        assertTrue(text, text.contains("recovered 0"))
        assertFalse("a clean run must not warn", text.contains("NOTE:"))
    }

    @Test
    fun `aRecoveredGapIsCalledAGapNotAStatistic`() {
        // The number that matters: these messages were known to the phone and had no durable event,
        // so they would have been lost silently.
        val text = renderReconcile(
            SyncDiagnostics.ReconcileSection(
                examined = 500, recovered = 12, windowHours = 48, ranAt = now
            )
        )

        assertTrue(text, text.contains("recovered 12"))
        assertTrue(text, text.contains("had NO durable event"))
        assertTrue(text, text.contains("detected and repaired"))
    }

    @Test
    fun `aCheckThatNeverRanIsNotReportedAsClean`() {
        // "Not measured" and "nothing wrong" are different facts, and conflating them is how a
        // diagnostic turns an unmeasured state into a reassuring one.
        val text = renderReconcile(null)

        assertTrue(text, text.contains("Missed-message check: not run yet"))
        assertFalse(text, text.contains("recovered 0"))
    }

    @Test
    fun `theReconciliationSectionReachesTheSanitizedExport`() {
        val json = JSONObject(
            diagnosticsFor(
                prerequisites(),
                reconciliation = SyncDiagnostics.ReconcileSection(
                    examined = 10, recovered = 2, windowHours = 48, ranAt = now
                )
            ).toSanitizedJson()
        )

        val section = json.getJSONObject("reconciliation")
        assertEquals(10, section.getInt("examined"))
        assertEquals(2, section.getInt("recovered"))
        assertEquals(48, section.getInt("windowHours"))
    }

    // ── The scan's own arithmetic (mission §25/§70) ─────────────────────────

    private fun renderSession(session: SyncDiagnostics.HistorySessionSection?) =
        GatewayDiagnosticReport.render(
            snapshot = snapshot(), probe = null, appVersion = "3.4.9 (116)",
            deliveryMode = "LEGACY_PULL", supervisorState = "CONNECTED", gatewayDesired = true,
            diagnostics = diagnosticsFor(prerequisites(), session = session),
            now = now
        )

    private fun session(
        eligible: Long = 12,
        enqueued: Long = 10,
        skippedLocalOnly: Long = 2,
        failed: Long = 0,
        scanExhausted: Boolean = true,
        open: Boolean = false,
    ) = SyncDiagnostics.HistorySessionSection(
        sessionId = "4f2a9c1e-0000-0000-0000-000000000000",
        eligible = eligible,
        enqueued = enqueued,
        skipped = skippedLocalOnly,
        failed = failed,
        skippedLocalOnly = skippedLocalOnly,
        skippedAskPending = 0,
        skippedNoDirection = 0,
        skippedSyncOff = 0,
        scanExhausted = scanExhausted,
        open = open,
        startedAt = now - 600_000L,
        finishedAt = if (open) 0L else now - 60_000L
    )

    @Test
    fun `theScanReportsItsArithmeticAndNotJustThatItRan`() {
        // The identity is the claim. A report that printed only `failed=0` would leave a reader
        // unable to tell a scan that lost nothing from one that never looked.
        val text = renderSession(session())

        assertTrue(text, text.contains("scan accounting (session 4f2a9c1e)"))
        assertTrue(text, text.contains("eligible=12 = enqueued=10 + skipped=2 + failed=0"))
        assertTrue(text, text.contains("balanced=yes"))
        assertTrue(text, text.contains("scan exhausted=yes"))
        assertTrue(text, text.contains("finished"))
    }

    @Test
    fun `policySkipsAreSeparatedFromLossAndTheirReasonsAreShown`() {
        val text = renderSession(session())

        assertTrue(text, text.contains("kept back by policy: localOnly=2"))
        assertTrue(text, text.contains("awaitingAnswer=0"))
        assertFalse("kept-back messages are not loss", text.contains("unexplained loss"))
    }

    @Test
    fun `aScanThatLostRowsSaysSoInTheReport`() {
        val text = renderSession(session(eligible = 12, enqueued = 9, skippedLocalOnly = 1, failed = 2))

        assertTrue(text, text.contains("failed=2"))
        assertTrue(text, text.contains("2 message(s) this scan read were eligible and have NO durable event"))
        assertTrue(text, text.contains("unexplained loss, not a policy decision"))
    }

    @Test
    fun `aScanThatNeverRanIsNotReportedAsClean`() {
        // Same rule as the missed-message check: an unmeasured state must not read as a good one.
        val text = renderSession(null)

        assertTrue(text, text.contains("scan accounting: not recorded yet"))
        assertTrue(text, text.contains("UNMEASURED"))
        assertFalse(text, text.contains("balanced=yes"))
    }

    @Test
    fun `anUnbalancedScanAdmitsItsOwnNumbersCannotBeTrusted`() {
        // Deliberately NOT reported as "messages were lost": a residual is a bug in the accounting,
        // and concluding loss from books that do not add up would be a fabricated finding.
        val text = renderSession(session(eligible = 12, enqueued = 8, skippedLocalOnly = 1))

        assertTrue(text, text.contains("balanced=NO"))
        assertTrue(text, text.contains("does not balance"))
        assertFalse(text, text.contains("unexplained loss"))
    }

    @Test
    fun `aStillRunningScanSaysSoRatherThanLookingFinished`() {
        val text = renderSession(session(scanExhausted = false, open = true))

        assertTrue(text, text.contains("still scanning"))
        assertTrue(text, text.contains("scan exhausted=NO"))
    }

    @Test
    fun `theSessionSectionReachesTheSanitizedExport`() {
        val json = JSONObject(
            diagnosticsFor(prerequisites(), session = session()).toSanitizedJson()
        )

        val section = json.getJSONObject("history").getJSONArray("sources")
            .getJSONObject(0).getJSONObject("session")
        assertEquals(12, section.getInt("eligible"))
        assertEquals(10, section.getInt("enqueued"))
        assertEquals(2, section.getInt("skipped"))
        assertEquals(0, section.getInt("failed"))
        assertTrue(section.getBoolean("balanced"))
        assertTrue(section.getBoolean("scanExhausted"))
    }

    // ── Outcomes GMweb has not accepted (mission §58) ────────────────────────

    private fun renderAwaitingAck(count: Int?) =
        GatewayDiagnosticReport.render(
            snapshot = snapshot(), probe = null, appVersion = "3.4.9 (116)",
            deliveryMode = "LEGACY_PULL", supervisorState = "CONNECTED", gatewayDesired = true,
            diagnostics = diagnosticsFor(prerequisites()).let {
                it.copy(gatewayReportsAwaitingAck = count)
            },
            now = now
        )

    @Test
    fun `aCleanReportSaysNoOutcomeIsAwaitingTheServer`() {
        val text = renderAwaitingAck(0)

        assertTrue(text, text.contains("Outcomes GMweb has not accepted: none"))
        // Scoped to THIS line: the report legitimately says "not measured" about several other facts,
        // so a bare substring check on the whole document would fail for the wrong reason.
        assertFalse(
            text,
            text.contains("Outcomes GMweb has not accepted: not measured")
        )
    }

    @Test
    fun `anUnreadQueueIsReportedAsUnmeasuredRatherThanClean`() {
        // The trap this three-state avoids: before the durable queue is read, the in-memory map is empty
        // because nothing has been loaded — not because everything is reported. Rendering that as
        // "none" turns "we have not looked" into a clean bill of health.
        val text = renderAwaitingAck(null)

        assertTrue(text, text.contains("Outcomes GMweb has not accepted: not measured"))
        assertFalse(text, text.contains("Outcomes GMweb has not accepted: none"))
    }

    @Test
    fun `aBacklogNamesTheCountAndSaysItIsNeverASecondSend`() {
        val text = renderAwaitingAck(3)

        assertTrue(text, text.contains("has not accepted: 3"))
        assertTrue(text, text.contains("a report, never a second send"))
    }

    @Test
    fun `theAwaitingAckCountReachesTheSanitizedExport`() {
        val json = JSONObject(
            diagnosticsFor(prerequisites()).let { it.copy(gatewayReportsAwaitingAck = 4) }
                .toSanitizedJson()
        )

        assertEquals(4, json.getInt("gatewayReportsAwaitingAck"))
    }

    // ── Full-mirror verification (mission §35) ───────────────────────────────

    private fun verify(
        source: String = "sms",
        complete: Boolean = true,
        examined: Long = 360_000,
        alreadyReplicated: Long = 360_000,
        recovered: Long = 0,
        skippedNoProviderId: Long = 0,
        skippedNoDirection: Long = 0,
        balanced: Boolean = true,
        residual: Long = 0,
    ) = SyncDiagnostics.MirrorVerifySection(
        source = source,
        complete = complete,
        examined = examined,
        alreadyReplicated = alreadyReplicated,
        recovered = recovered,
        skippedNoProviderId = skippedNoProviderId,
        skippedNoDirection = skippedNoDirection,
        balanced = balanced,
        residual = residual,
        startedAt = now - 600_000L,
        updatedAt = now - 1_000L,
        completedAt = if (complete) now - 1_000L else 0L
    )

    private fun renderVerify(sections: List<SyncDiagnostics.MirrorVerifySection>) =
        GatewayDiagnosticReport.render(
            snapshot = snapshot(), probe = null, appVersion = "3.4.9 (116)",
            deliveryMode = "LEGACY_PULL", supervisorState = "CONNECTED", gatewayDesired = true,
            diagnostics = diagnosticsFor(prerequisites()).let { it.copy(mirrorVerify = sections) },
            now = now
        )

    @Test
    fun `aFinishedSweepThatFoundNothingReportsItsCoverageAndDoesNotWarn`() {
        val text = renderVerify(listOf(verify()))

        assertTrue(text, text.contains("Full-mirror verification (mission §35):"))
        assertTrue(text, text.contains("examined=360000"))
        assertTrue(text, text.contains("finished"))
        assertTrue(text, text.contains("balanced=yes"))
        assertFalse(text, text.contains("NOTE:"))
    }

    @Test
    fun `anUnstartedSweepIsCalledUnmeasuredRatherThanClean`() {
        // The whole reason this section exists: the missed-message check cannot see a gap older than
        // its window, and "no sweep has run" must not read as "nothing was lost".
        val text = renderVerify(emptyList())

        assertTrue(text, text.contains("not started"))
        assertTrue(text, text.contains("UNMEASURED"))
    }

    @Test
    fun `anUnfinishedSweepRefusesToCallTheMirrorVerified`() {
        val text = renderVerify(
            listOf(verify(complete = false, examined = 5000, alreadyReplicated = 5000))
        )

        assertTrue(text, text.contains("STILL RUNNING"))
        assertTrue(text, text.contains("is not yet a verified claim"))
    }

    @Test
    fun `aRecoveredGapIsCalledAGap`() {
        val text = renderVerify(
            listOf(verify(examined = 1000, alreadyReplicated = 998, recovered = 2))
        )

        assertTrue(text, text.contains("recovered=2"))
        assertTrue(text, text.contains("had NO durable event"))
        assertTrue(text, text.contains("nothing else was going to find them"))
    }

    @Test
    fun `anUnbalancedSweepAdmitsItsNumbersCannotBeTrusted`() {
        val text = renderVerify(
            listOf(verify(examined = 10, alreadyReplicated = 8, balanced = false, residual = 2))
        )

        assertTrue(text, text.contains("balanced=NO"))
        assertTrue(text, text.contains("cannot be trusted"))
    }

    @Test
    fun `rowsNeverLookedUpAreNamedRatherThanCountedAsHealthy`() {
        val text = renderVerify(
            listOf(verify(examined = 100, alreadyReplicated = 95, skippedNoProviderId = 5))
        )

        assertTrue(text, text.contains("noProviderId=5"))
    }

    @Test
    fun `theVerificationSectionReachesTheSanitizedExport`() {
        val json = JSONObject(
            diagnosticsFor(prerequisites()).let {
                it.copy(
                    mirrorVerify = listOf(
                        verify(examined = 100, alreadyReplicated = 99, recovered = 1, complete = false)
                    )
                )
            }.toSanitizedJson()
        )

        val entry = json.getJSONArray("mirrorVerify").getJSONObject(0)
        assertEquals("sms", entry.getString("source"))
        assertEquals(100, entry.getInt("examined"))
        assertEquals(1, entry.getInt("recovered"))
        assertFalse(entry.getBoolean("complete"))
        assertTrue(entry.getBoolean("balanced"))
    }

    // ── Which keys the waiting events are under (mission §42) ────────────────

    private fun renderKeyRefs(keyRefs: List<SyncDiagnostics.KeyRefSection>) =
        GatewayDiagnosticReport.render(
            snapshot = snapshot(), probe = null, appVersion = "3.4.9 (116)",
            deliveryMode = "LEGACY_PULL", supervisorState = "CONNECTED", gatewayDesired = true,
            diagnostics = diagnosticsFor(prerequisites()).let {
                it.copy(replication = it.replication.copy(keyRefs = keyRefs))
            },
            now = now
        )

    @Test
    fun `aUniformKeyNeedsNoLine`() {
        // One key covering everything is the healthy state. A line that always appears is a line
        // nobody reads.
        val text = renderKeyRefs(
            listOf(SyncDiagnostics.KeyRefSection(keyRef = "4f2a9c1e-1111", count = 900))
        )

        assertFalse(text, text.contains("Keys among waiting events"))
    }

    @Test
    fun `moreThanOneKeyIsReportedAsARotationInProgress`() {
        // Invisible in every other number in the section: the counts are identical whichever key is in
        // use, so without this line a half-finished rotation looks exactly like a healthy queue.
        val text = renderKeyRefs(
            listOf(
                SyncDiagnostics.KeyRefSection(keyRef = "4f2a9c1e-1111", count = 900),
                SyncDiagnostics.KeyRefSection(keyRef = "9b7e0d42-2222", count = 40)
            )
        )

        assertTrue(text, text.contains("Keys among waiting events: 4f2a9c1e=900, 9b7e0d42=40"))
        assertTrue(text, text.contains("2 different keys"))
        assertTrue(text, text.contains("left rows behind"))
    }

    @Test
    fun `aNullKeyGroupIsShownAsAbsentRatherThanAsAKey`() {
        val text = renderKeyRefs(
            listOf(
                SyncDiagnostics.KeyRefSection(keyRef = null, count = 3),
                SyncDiagnostics.KeyRefSection(keyRef = "4f2a9c1e-1111", count = 900)
            )
        )

        assertTrue(text, text.contains("no key reference=3"))
        assertFalse("a NULL group is not a second key", text.contains("different keys"))
    }

    @Test
    fun `theKeyRefSectionReachesTheSanitizedExport`() {
        // The full id is exported even though the report truncates it: a rotation needs the whole key
        // id, and a key ID is a UUID rather than key material.
        val json = JSONObject(
            diagnosticsFor(prerequisites()).let {
                it.copy(
                    replication = it.replication.copy(
                        keyRefs = listOf(
                            SyncDiagnostics.KeyRefSection(keyRef = "4f2a9c1e-1111-2222", count = 7)
                        )
                    )
                )
            }.toSanitizedJson()
        )

        val entry = json.getJSONObject("replication").getJSONArray("keyRefs").getJSONObject(0)
        assertEquals("4f2a9c1e-1111-2222", entry.getString("keyRef"))
        assertEquals(7, entry.getInt("count"))
    }

    // ── UI-independent detection (audit Blocker 1) ───────────────────────────

    @Test
    fun `theReportSaysWhetherMessageDetectionRunsWithoutTheUi`() {
        // This defect was invisible: replication ran only while a screen was open, and the report
        // had no way to say so.
        mapOf(
            true to "Telephony relay (UI-independent detection): running",
            false to "Telephony relay (UI-independent detection): stopped"
        ).forEach { (value, expected) ->
            val text = GatewayDiagnosticReport.render(
                snapshot = snapshot(), probe = null, appVersion = "3.4.9 (116)",
                deliveryMode = "LEGACY_PULL", supervisorState = "CONNECTED", gatewayDesired = true,
                diagnostics = diagnosticsFor(prerequisites(), telephonyRelayRunning = value),
                now = now
            )
            assertTrue(text, text.contains(expected))
        }
    }

    @Test
    fun `anUnmeasuredRelayReadsAsNotMeasuredRatherThanStopped`() {
        val text = renderWithPrerequisites(prerequisites())

        assertTrue(
            text,
            text.contains("Telephony relay (UI-independent detection): not measured")
        )
    }

    @Test
    fun `aBlockedReportNamesTheCauseInsteadOfOnlyAVerdict`() {
        val text = renderWithPrerequisites(prerequisites(identityRegistered = false))

        assertTrue(text, text.contains("Replication: blocked"))
        assertTrue(text, text.contains("Replication blocked: IDENTITY_NOT_REGISTERED"))
        // The failure this guards: a diagnostic that reports a state with no cause.
        assertFalse(text, text.contains("Replication blocked: DEGRADED"))
    }

    // ── SCAN_COMPLETE vs CAUGHT_UP (mission §26) ─────────────────────────────

    @Test
    fun `theReportSeparatesScanCompleteFromDelivered`() {
        val text = GatewayDiagnosticReport.render(
            snapshot = snapshot(), probe = null, appVersion = "3.4.9 (116)",
            deliveryMode = "LEGACY_PULL", supervisorState = "CONNECTED", gatewayDesired = true,
            diagnostics = diagnosticsFor(
                prerequisites(), scanComplete = true, delivered = false
            ),
            now = now
        )

        // Both facts, side by side, so neither can be mistaken for the other. The report's
        // established tri-state rendering is yes/NO/unknown.
        assertTrue(text, text.contains("scan complete=yes · delivered=NO"))
        assertTrue(
            text,
            text.contains("scan complete but NOT delivered for sms")
        )
        assertTrue(text, text.contains("GMweb does not yet have all of it"))
    }

    @Test
    fun `aFullyDeliveredSourceCarriesNoWarning`() {
        val text = GatewayDiagnosticReport.render(
            snapshot = snapshot(), probe = null, appVersion = "3.4.9 (116)",
            deliveryMode = "LEGACY_PULL", supervisorState = "CONNECTED", gatewayDesired = true,
            diagnostics = diagnosticsFor(
                prerequisites(), scanComplete = true, delivered = true
            ),
            now = now
        )

        assertTrue(text, text.contains("scan complete=yes · delivered=yes"))
        assertFalse(text, text.contains("NOT delivered"))
    }

    @Test
    fun `anUnmeasuredDeliveryStateIsNotReportedAsDelivered`() {
        // Absent data must not read as success: that is the failure mode the whole distinction
        // exists to prevent.
        val text = renderWithPrerequisites(prerequisites())

        assertTrue(text, text.contains("delivered=unknown"))
        assertFalse(text, text.contains("delivered=yes"))
    }

    @Test
    fun `aBlockedReportCarriesAnActionAndTheFullBlockerList`() {
        val text = renderWithPrerequisites(
            prerequisites(identityRegistered = false, historyGrantPresent = false)
        )

        assertTrue(text, text.contains("Blockers: IDENTITY_NOT_REGISTERED, MISSING_HISTORY_GRANT"))
        assertTrue(text, text.contains("Enroll this device's identity"))
    }

    @Test
    fun `theReportSeparatesTheThreeCapabilities`() {
        val text = renderWithPrerequisites(prerequisites(historyGrantPresent = false))

        // Realtime is fine; only history is blocked. A single boolean could not say this.
        assertTrue(text, text.contains("Replication: ready"))
        assertTrue(text, text.contains("History upload: blocked · Commands: allowed"))
    }

    @Test
    fun `aFullyClearReportSaysSo`() {
        val text = renderWithPrerequisites(prerequisites())

        assertTrue(text, text.contains("Replication: ready"))
        assertTrue(text, text.contains("Replication clear: all prerequisites satisfied."))
        assertFalse(text, text.contains("Blockers:"))
    }

    @Test
    fun `omittingDiagnosticsLeavesTheReportUnchanged`() {
        // Existing callers and their golden-ish assertions must not move.
        val withoutDiagnostics = render(snapshot())

        listOf(
            "Replication:", "Blockers:", "Replication state:",
            "Replication queue", "History:", "Commands:"
        ).forEach { marker ->
            assertFalse(withoutDiagnostics, withoutDiagnostics.contains(marker))
        }
    }

    // ── Diagnostics V2 sections (mission §56) ───────────────────────────────

    @Test
    fun `theReportCarriesTheReplicationQueueWithTheAgeOfTheOldestWaitingEvent`() {
        val text = renderWithPrerequisites(prerequisites())

        assertTrue(text, text.contains("Replication queue (aggregate only):"))
        assertTrue(text, text.contains("Ready: 3 · In flight: 0 · Retry wait: 1"))
        assertTrue(text, text.contains("Acknowledged: 309 · Dead letter: 0"))
        // A count alone cannot say whether the queue is draining; the age can.
        assertTrue(text, text.contains("Oldest waiting event: 90s"))
        assertTrue(text, text.contains("Replication state: READY"))
    }

    @Test
    fun `theReportCarriesHistoryProgressAndItsCheckpoint`() {
        val text = renderWithPrerequisites(prerequisites())

        assertTrue(text, text.contains("History:"))
        assertTrue(text, text.contains("Grant: FULL_HISTORY"))
        assertTrue(text, text.contains("State: SCANNING") || text.contains("History state: SCANNING"))
        assertTrue(text, text.contains("sms: enqueued=900 acked=850 pending=50"))
        // The checkpoint is rendered as ISO, not epoch millis: the redaction sweep tokenizes long
        // digit runs and would have printed `id#…` instead. Asserted so that regression is caught.
        assertTrue(text, text.contains("checkpoint=2020-09-13T12:26:40Z/4242"))
        assertFalse(text, text.contains("checkpoint=1600000000000"))
        // Never measured, so it must read as unknown rather than as zero.
        assertTrue(text, text.contains("scanned=? skipped=? failed=?"))
    }

    @Test
    fun `theReportCarriesCommandProgress`() {
        val text = renderWithPrerequisites(prerequisites())

        assertTrue(text, text.contains("Commands:"))
        assertTrue(text, text.contains("received=4 claimed=3 executing=0 completed=3 failed=0"))
        assertTrue(text, text.contains("type=SEND_SMS"))
    }

    @Test
    fun `unmeasuredCountsRenderAsUnknownNotAsZero`() {
        // The whole point: a diagnostic must not invent a zero for something it never measured.
        val sparse = SyncDiagnostics(
            generatedAt = now,
            application = SyncDiagnostics.ApplicationSection(null, null, null),
            identity = SyncDiagnostics.IdentitySection(null, null, null, null),
            gateway = SyncDiagnostics.GatewaySection(null, null, null, null),
            replication = SyncDiagnostics.ReplicationSection(
                state = ReplicationState.READY,
                readyCount = null, inFlightCount = null, retryWaitCount = null,
                deadLetterCount = null, ackedCount = null, oldestPendingAgeMs = null,
                lastUploadAttemptAt = null, lastSuccessfulUploadAt = null, lastHttpStatus = null,
                lastBatchSize = null, lastAccepted = null, lastDuplicates = null, lastRejected = null
            ),
            history = SyncDiagnostics.HistorySection(
                state = HistorySyncState.NOT_STARTED, grant = null, keyVersion = null,
                sessionId = null, sources = emptyList()
            ),
            commands = SyncDiagnostics.CommandSection(
                state = CommandSyncState.READY, received = null, claimed = null,
                executing = null, completed = null, failed = null,
                lastCommandAt = null, lastCommandType = null
            ),
            prerequisites = prerequisites()
        )

        val text = GatewayDiagnosticReport.render(
            snapshot = snapshot(), probe = null, appVersion = "3.4.9 (116)",
            deliveryMode = "LEGACY_PULL", supervisorState = "CONNECTED", gatewayDesired = true,
            diagnostics = sparse, now = now
        )

        assertTrue(text, text.contains("Ready: ? · In flight: ? · Retry wait: ?"))
        assertTrue(text, text.contains("Oldest waiting event: none"))
        assertTrue(text, text.contains("received=? claimed=?"))
        assertTrue(text, text.contains("Grant: none"))
        assertTrue(text, text.contains("No history session recorded"))
        // Batch counters are never stored, so they must not appear as numbers.
        assertTrue(text, text.contains("not recorded"))
    }

    @Test
    fun `aBlockedReportStillRespectsThePerLineBound`() {
        val text = renderWithPrerequisites(
            prerequisites(
                serverOriginConfigured = false,
                identityRegistered = false,
                telephonyPermissionGranted = false,
                historyGrantPresent = false,
                networkValidated = false
            )
        )

        assertTrue(text.lines().all { it.length <= 200 })
    }
}
