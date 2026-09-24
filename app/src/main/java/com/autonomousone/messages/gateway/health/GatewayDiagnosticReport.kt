package com.autonomousone.messages.gateway.health

import com.autonomousone.messages.data.DeadLetterBreakdownRow
import com.autonomousone.messages.data.DeadLetterSummary
import com.autonomousone.messages.gateway.AddressScope
import com.autonomousone.messages.gateway.NetworkAddressFacts
import com.autonomousone.messages.sync.diagnostics.SyncDiagnostics
import com.autonomousone.messages.sync.diagnostics.SyncDiagnosticsText
import com.autonomousone.messages.utils.PhoneToken
import java.time.Instant

/**
 * The one-tap diagnostic report.
 *
 * Built ENTIRELY from already-safe values: the health snapshot holds counts, enums, HTTP
 * statuses and redacted captions, the probe holds stage outcomes, and nothing here ever
 * reads the API key, the device private key, the registration secret, an auth signature or a
 * message. On top of that, the rendered text passes through one final redaction sweep, so a
 * long digit run that reached a `detail` by some future path is tokenized rather than
 * printed.
 *
 * The output is deliberately plain ASCII text in English. It is a TECHNICAL ARTIFACT the
 * user copies into a bug report or shares with whoever runs GMweb, and a report whose field
 * names change with the phone's locale is a report nobody can compare across two devices.
 * The user-facing status CARD is localized; this is not the card.
 */
object GatewayDiagnosticReport {

    /** Longest value rendered for one line before it is elided. */
    private const val MAX_VALUE = 200

    fun render(
        snapshot: GatewayHealthSnapshot,
        probe: GatewayConnectivityResult?,
        appVersion: String,
        deliveryMode: String,
        supervisorState: String,
        gatewayDesired: Boolean,
        deadLetters: List<DeadLetterBreakdownRow> = emptyList(),
        deadLetterSummary: DeadLetterSummary = DeadLetterSummary(),
        /**
         * Diagnostics V2, when the caller has collected it.
         *
         * ONE source of truth for the sync sections: the blocker verdict comes from
         * `diagnostics.prerequisites`, so the verdict and the detail beside it cannot disagree.
         * Optional so existing callers keep working unchanged.
         *
         * Rendered near the top because "why is nothing syncing?" is the question this report
         * exists to answer (mission §57). Before this existed the report could only say
         * `Verdict: DEGRADED` and leave the cause to be guessed at.
         */
        diagnostics: SyncDiagnostics? = null,
        now: Long = System.currentTimeMillis()
    ): String = buildString {
        appendLine("GMweb Gateway Diagnostic")
        appendLine("------------------------")
        appendLine()
        appendLine("Generated: ${Instant.ofEpochMilli(now)}")
        appendLine("App version: $appVersion")
        appendLine("Gateway desired: ${yesNo(gatewayDesired)}")
        appendLine("Supervisor: $supervisorState")
        appendLine("Delivery mode: $deliveryMode")
        diagnostics?.let { appendBlockerSection(it) }
        appendLine()
        diagnostics?.let { appendReplicationSection(it) }
        diagnostics?.let { appendHistorySection(it) }
        diagnostics?.let { appendCommandSection(it) }
        appendLine()

        appendLine("Internet: ${if (snapshot.network.validatedInternet) "validated" else "not validated"}" +
            " · ${snapshot.network.transport}")
        appendLine("Host: ${snapshot.endpoint.host ?: "not configured"}" +
            snapshot.endpoint.port?.let { ":$it" }.orEmpty())
        // SYSTEM DNS and the ACTUAL PEER are reported separately, because they can differ: a
        // VPN or proxy can answer DNS with a synthetic address from a reserved range while the
        // real connection goes elsewhere. Printing one of them unlabelled as "the server's IP"
        // states something that was never measured.
        appendLine("Resolved by DNS: ${snapshot.endpoint.dnsDescription() ?: "not checked"}")
        appendLine("Actual TLS peer: ${snapshot.tls.peerAddress ?: "not measured"}")
        appendLine("TCP connect: ${snapshot.endpoint.lastTcpConnectMs?.let { "$it ms" } ?: "n/a"}")
        if (snapshot.endpoint.dnsAddresses.any {
                NetworkAddressFacts.classify(it).scope == AddressScope.SYNTHETIC_RANGE
            }) {
            appendLine("Network note: DNS is intercepted by a VPN/proxy; TCP timing may reflect")
            appendLine("  the local proxy path rather than the GMweb origin.")
        }
        appendLine("TLS: ${tlsLine(snapshot.tls, now)}")
        appendLine("Certificate host match: ${triState(snapshot.tls.hostMatched)}")
        appendLine("HTTPS /health: ${probeStepLine(probe, GatewayProbeStage.HTTPS)}")
        appendLine("Agent auth: ${authLine(snapshot.authentication)}" +
            snapshot.authentication.clockSkewMs?.let { " · clock skew ${it / 1000}s" }.orEmpty())
        appendLine()

        appendLine("Event upload (Android → GMweb):")
        appendLine("  Running: ${yesNo(snapshot.eventUpload.running)}")
        appendLine("  Pending: ${snapshot.eventUpload.pending}")
        appendLine("  Sending: ${snapshot.eventUpload.sending}")
        appendLine("  Dead letter: ${snapshot.eventUpload.deadLetter}")
        appendLine("  Last attempt: ${ago(snapshot.eventUpload.lastAttemptAt, now)}")
        appendLine("  Last success: ${ago(snapshot.eventUpload.lastSuccessAt, now)}")
        appendLine("  Last HTTP: ${snapshot.eventUpload.lastHttpStatus ?: "n/a"}")
        appendLine("  Last error: ${failureLine(snapshot.eventUpload.lastFailure, snapshot.eventUpload.lastFailureSafeDetail)}")
        appendLine()

        appendLine("Pull bridge (GMweb → Android):")
        appendLine("  Running: ${yesNo(snapshot.pullBridge.running)}")
        appendLine("  State: ${snapshot.pullBridge.state}")
        appendLine("  Last poll started: ${ago(snapshot.pullBridge.lastPollStartedAt, now)}")
        appendLine("  Last successful poll: ${ago(snapshot.pullBridge.lastSuccessfulPollAt, now)}")
        appendLine("  Last empty poll: ${ago(snapshot.pullBridge.lastEmptyPollAt, now)}")
        appendLine("  Last task received: ${ago(snapshot.pullBridge.lastTaskReceivedAt, now)}")
        appendLine("  Last result ACKed: ${ago(snapshot.pullBridge.lastAckAt, now)}")
        appendLine("  Last HTTP: ${snapshot.pullBridge.lastHttpStatus ?: "n/a"}")
        appendLine("  Last error: ${failureLine(snapshot.pullBridge.lastFailure, snapshot.pullBridge.lastFailureSafeDetail)}")
        appendLine("  Consecutive failures: ${snapshot.pullBridge.consecutiveFailures}")
        appendLine("  ACK failures this session: ${snapshot.pullBridge.ackFailures}")
        appendLine("  ACK consecutive failures: ${snapshot.pullBridge.ackConsecutiveFailures}")
        appendLine("  ACK last failure: ${ago(snapshot.pullBridge.lastAckFailureAt, now)}")
        // The counters above are in-memory and reset with the process. This one is read from the
        // DURABLE queue, so it is the only figure here that can survive a restart — and it is the one
        // that says whether GMweb is missing an outcome at all.
        val awaitingReports = diagnostics?.gatewayReportsAwaitingAck
        when {
            awaitingReports == null -> appendLine(
                "  Outcomes GMweb has not accepted: not measured — the durable queue has not been read"
            )
            awaitingReports == 0 -> appendLine("  Outcomes GMweb has not accepted: none")
            else -> appendLine(
                "  Outcomes GMweb has not accepted: $awaitingReports — re-reported on every poll until " +
                    "the server accepts them (a report, never a second send)"
            )
        }
        appendLine()

        appendLine("EVE queue:")
        appendLine("  Queued: ${snapshot.eveQueue.queued} · Active: ${snapshot.eveQueue.active}" +
            " · Deferred: ${snapshot.eveQueue.deferred}")
        appendLine("  Sent: ${snapshot.eveQueue.sentRecent} · Failed: ${snapshot.eveQueue.failedRecent}" +
            " · Cancelled: ${snapshot.eveQueue.cancelledRecent}")
        appendLine("  Last pulled task: ${snapshot.eveQueue.lastPulledRequestToken ?: "none"}")
        appendLine("  Last SIM submit: ${ago(snapshot.eveQueue.lastNativeSubmitAt, now)}")
        appendLine("  Last gateway ACK: ${ago(snapshot.eveQueue.lastGatewayAckAt, now)}")
        appendLine()

        appendLine("Connectivity probe:")
        if (probe == null) {
            appendLine("  not run — tap Run diagnostics")
        } else {
            probe.steps.forEach { step ->
                appendLine(
                    "  ${step.stage.name.padEnd(8)} ${step.status.name.padEnd(12)}" +
                        (step.durationMs?.let { " ${it}ms" } ?: "") +
                        (step.detail?.let { "  $it" } ?: "")
                )
            }
        }
        appendLine()

        appendLine("Verdict: ${snapshot.overall.name}")
        // WRAPPED, not one long line. The redaction sweep bounds each line to keep the report
        // readable, and a conclusion long enough to be useful was therefore being CUT OFF before
        // its decisive clause ("this is NOT a rejected key"). A report must never truncate its
        // own verdict.
        appendWrapped("Conclusion: ", conclusionText(snapshot.conclusion))
        appendLine()

        // ── Dead letters, AGGREGATE ONLY ────────────────────────────────────
        // Nothing is deleted and no row is read. The SHAPE is what distinguishes a historical
        // cohort from an active defect: a cluster of one event type at one crypto version with
        // a bounded attempt count, all created in a narrow window, is a rollout artefact; a
        // spread across types with recent timestamps is a live problem.
        if (snapshot.eventUpload.deadLetter > 0) {
            appendLine("Dead-lettered outbox events (aggregate only; nothing deleted):")
            appendLine("  Historical items: ${deadLetterSummary.count.takeIf { it > 0 } ?: snapshot.eventUpload.deadLetter}")
            appendLine("  Last new dead-letter: ${ago(deadLetterSummary.lastDeadLetteredAt, now)}")
            appendLine("  New in last 1h / 24h: ${deadLetterSummary.newLastHour} / ${deadLetterSummary.newLast24Hours}")
            if (deadLetters.isEmpty()) {
                appendLine("  ${snapshot.eventUpload.deadLetter} row(s); breakdown unavailable")
            } else {
                deadLetters.forEach { row ->
                    val metadata = if (row.metadataCount == 0) {
                        " | failure metadata unavailable"
                    } else {
                        " | metadata ${row.metadataCount}/${row.count}"
                    }
                    appendLine(
                        "  ${row.eventType} | crypto v${row.cryptoVersion} | ${row.priority}" +
                            " | count=${row.count}" +
                            " | attempts ${row.minAttempts}..${row.maxAttempts}" +
                            " | created ${Instant.ofEpochMilli(row.firstCreatedAt)}" +
                            " .. ${Instant.ofEpochMilli(row.lastCreatedAt)}" + metadata
                    )
                }
            }
            if (deadLetterSummary.metadataCount < snapshot.eventUpload.deadLetter) {
                appendLine("  NOTE: older rows predate safe failure metadata; missing values are")
                appendLine("  reported as unavailable and are never inferred.")
            }
            appendLine()
        }

        appendLine("Secrets (API key, device key, registration secret, signatures), message")
        appendLine("bodies and full phone numbers are never included in this report.")
    }.let(::redact)

    // ── helpers ─────────────────────────────────────────────────────────────

    /** Longest line before wrapping, chosen to stay under the redaction sweep's own bound. */
    private const val WRAP_AT = 92

    /**
     * Appends [text] under [prefix], wrapped so the redaction sweep cannot truncate it.
     *
     * Continuation lines are indented to the prefix width, so the block still reads as one
     * statement.
     */
    private fun StringBuilder.appendWrapped(prefix: String, text: String) {
        val indent = " ".repeat(prefix.length)
        var remaining = text
        var first = true
        while (remaining.isNotEmpty()) {
            val linePrefix = if (first) prefix else indent
            val room = WRAP_AT - linePrefix.length
            val slice = if (remaining.length <= room) {
                remaining.also { remaining = "" }
            } else {
                // Prefer a word boundary; fall back to a hard cut for a pathological token.
                val breakAt = remaining.lastIndexOf(' ', startIndex = room)
                    .takeIf { it > room / 2 } ?: room
                remaining.substring(0, breakAt).also { remaining = remaining.substring(breakAt).trimStart() }
            }
            appendLine(linePrefix + slice)
            first = false
        }
    }

    /**
     * The auth row, with the three outcomes kept distinct.
     *
     * "Not verified" and "rejected" are different answers, and printing the first while the
     * conclusion says the second was the inconsistency that made a working device look broken.
     */
    private fun authLine(auth: AuthHealth): String = when (auth.status) {
        AuthVerification.VERIFIED -> "verified"
        AuthVerification.REJECTED -> "REJECTED by the server (an authentication response)"
        AuthVerification.UNVERIFIABLE ->
            "could not be verified — NOT a rejection" +
                auth.unverifiableReason?.let { " ($it)" }.orEmpty()
        AuthVerification.UNKNOWN -> "not checked"
    }

    private fun tlsLine(tls: TlsHealth, now: Long): String {
        if (tls.valid == null && tls.protocol == null) return "n/a"
        val parts = mutableListOf<String>()
        parts += tls.protocol ?: "unknown protocol"
        tls.issuer?.let { parts += it }
        tls.daysUntilExpiry(now)?.let { parts += "expires in $it days" }
        return parts.joinToString(" · ")
    }

    private fun probeStepLine(
        probe: GatewayConnectivityResult?,
        stage: GatewayProbeStage
    ): String {
        val step = probe?.step(stage) ?: return "n/a"
        return buildString {
            append(step.httpStatus?.toString() ?: step.status.name.lowercase())
            step.durationMs?.let { append(" · ${it}ms") }
            step.detail?.let { append(" · $it") }
        }
    }

    private fun failureLine(kind: GatewayFailureKind?, detail: String?): String = when {
        kind == null && detail == null -> "none"
        kind == null -> detail!!
        detail == null -> kind.name
        else -> "${kind.name} · $detail"
    }

    private fun ago(at: Long?, now: Long): String {
        if (at == null) return "never"
        val delta = (now - at).coerceAtLeast(0L)
        return when {
            delta < 1_000L -> "just now"
            delta < 60_000L -> "${delta / 1000}s ago"
            delta < 3_600_000L -> "${delta / 60_000}m ago"
            else -> "${delta / 3_600_000}h ago"
        }
    }

    private fun yesNo(value: Boolean): String = if (value) "yes" else "no"

    // ═══════════════════════════════════════════════════════════════════════════
    // Diagnostics V2 sections (mission §56-§57)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * The verdict, first, because it is the one thing a reader must not have to hunt for.
     *
     * Never a bare state: the blocker is always named, and named with an action.
     */
    private fun StringBuilder.appendBlockerSection(diagnostics: SyncDiagnostics) {
        val prerequisites = diagnostics.prerequisites
        appendLine("Replication: " + if (prerequisites.canUploadRealtime) "ready" else "blocked")
        SyncDiagnosticsText.blockerLine(prerequisites).lineSequence().forEach { appendLine(it) }
        if (prerequisites.blockers.isNotEmpty()) {
            appendLine(
                "Blockers: " + prerequisites.blockers
                    .sortedBy { it.priority }
                    .joinToString(", ") { SyncDiagnosticsText.codeName(it) }
            )
        }
        appendLine(
            "History upload: " + (if (prerequisites.canUploadHistory) "allowed" else "blocked") +
                " · Commands: " + (if (prerequisites.canReceiveCommands) "allowed" else "blocked")
        )
        appendLine("Replication state: ${diagnostics.replication.state.name}")
        appendLine("History state: ${diagnostics.history.state.name}")
        appendLine("Commands state: ${diagnostics.commands.state.name}")
        // Answers "does this phone even detect messages with the screen off?" — the one question
        // the old report could not answer, and the reason a silent replication gap went unnoticed.
        appendLine(
            "Telephony relay (UI-independent detection): " +
                triStateRunning(diagnostics.telephonyRelayRunning)
        )
        // Mission §70: the one measured answer to "did any message go missing?". A repair that
        // reached only a log line left that question unanswerable.
        val reconciliation = diagnostics.reconciliation
        if (reconciliation == null) {
            appendLine("Missed-message check: not run yet")
        } else {
            appendLine(
                "Missed-message check: examined ${reconciliation.examined} message(s) in the last " +
                    "${reconciliation.windowHours}h, recovered ${reconciliation.recovered}" +
                    " (${ago(reconciliation.ranAt, diagnostics.generatedAt)})"
            )
            if (reconciliation.recovered > 0) {
                appendLine(
                    "  NOTE: ${reconciliation.recovered} message(s) the phone already knew about " +
                        "had NO durable event and were re-enqueued. That is a replication gap that " +
                        "was detected and repaired, not merely a statistic."
                )
            }
        }
    }

    private fun triStateRunning(value: Boolean?): String = when (value) {
        null -> "not measured"
        true -> "running"
        false -> "stopped"
    }

    /**
     * The queue, and crucially how OLD the oldest waiting event is.
     *
     * A count alone looks identical whether the queue is draining or stuck; the age is what
     * separates those two.
     */
    private fun StringBuilder.appendReplicationSection(diagnostics: SyncDiagnostics) {
        val replication = diagnostics.replication
        appendLine()
        appendLine("Replication queue (aggregate only):")
        appendLine("  Ready: ${count(replication.readyCount)} · In flight: ${count(replication.inFlightCount)}" +
            " · Retry wait: ${count(replication.retryWaitCount)}")
        appendLine("  Acknowledged: ${count(replication.ackedCount)} · Dead letter: ${count(replication.deadLetterCount)}")
        appendLine("  Oldest waiting event: ${replication.oldestPendingAgeMs?.let { "${it / 1000}s" } ?: "none"}")
        appendLine("  Last upload attempt: ${ago(replication.lastUploadAttemptAt, diagnostics.generatedAt)}")
        appendLine("  Last successful upload: ${ago(replication.lastSuccessfulUploadAt, diagnostics.generatedAt)}")
        appendLine("  Last upload HTTP: ${replication.lastHttpStatus ?: "n/a"}")
        // Batch counters are deliberately absent rather than zero: they are computed per upload
        // and logged, never stored, so a number here would be invented.
        appendLine("  Last batch size/accepted/duplicate/rejected: not recorded")
        appendKeyRefSection(this, replication.keyRefs)
        appendMirrorVerifySection(this, diagnostics.mirrorVerify)
    }

    /**
     * The full-mirror verification's progress (mission §35).
     *
     * Three states, and they must never be merged: never run (unmeasured), running (measured but
     * incomplete, so "nothing was lost" is not yet a verified claim), and finished. A finished sweep
     * that recovered nothing is the good outcome and gets no line beyond the summary; one that
     * recovered something is the finding this pass exists to produce, and it is called a gap.
     */
    private fun appendMirrorVerifySection(
        sb: StringBuilder,
        sections: List<SyncDiagnostics.MirrorVerifySection>
    ) {
        sb.appendLine()
        sb.appendLine("Full-mirror verification (mission §35):")
        if (sections.isEmpty()) {
            sb.appendLine(
                "  not started — no sweep has walked the mirror, so a gap older than the " +
                    "missed-message check's window is UNMEASURED rather than absent"
            )
            return
        }
        sections.forEach { verify ->
            sb.appendLine(
                "  ${verify.source}: examined=${verify.examined}" +
                    " alreadyReplicated=${verify.alreadyReplicated}" +
                    " recovered=${verify.recovered}" +
                    " noProviderId=${verify.skippedNoProviderId}" +
                    " · ${if (verify.complete) "finished" else "STILL RUNNING"}" +
                    " · balanced=${triState(verify.balanced)}"
            )
            if (verify.recovered > 0) {
                sb.appendLine(
                    "    NOTE: ${verify.recovered} message(s) had NO durable event and were " +
                        "re-enqueued. The history scan had already finished, so nothing else was " +
                        "going to find them."
                )
            }
            if (!verify.balanced) {
                sb.appendLine(
                    "    NOTE: this sweep's own tally does not balance (residual ${verify.residual}), " +
                        "so its recovery count cannot be trusted."
                )
            }
        }
        val unfinished = sections.filter { !it.complete }
        if (unfinished.isNotEmpty()) {
            sb.appendLine(
                "  NOTE: the verification has not finished for " +
                    unfinished.joinToString(", ") { it.source } +
                    ". 'No messages lost' is not yet a verified claim for those sources."
            )
        }
    }

    /**
     * Which encryption keys the not-yet-uploaded events are under (mission §42).
     *
     * Printed only when the answer is not uniform. A single key covering everything is the healthy
     * state and needs no line; the case worth a reader's attention is MORE than one key, because that
     * is what a rotation in progress looks like from this device — and it is invisible in every other
     * number in this section, since the counts are identical whichever key is in use.
     */
    private fun appendKeyRefSection(
        sb: StringBuilder,
        keyRefs: List<SyncDiagnostics.KeyRefSection>
    ) {
        if (keyRefs.size < 2) return
        val described = keyRefs.joinToString(", ") { entry ->
            val label = entry.keyRef?.take(8) ?: "no key reference"
            "$label=${entry.count}"
        }
        sb.appendLine("  Keys among waiting events: $described")
        val namedKeys = keyRefs.mapNotNull { it.keyRef }.distinct()
        if (namedKeys.size > 1) {
            sb.appendLine(
                "  NOTE: waiting events are encrypted under ${namedKeys.size} different keys. That is " +
                    "what a key rotation in progress looks like; a rotation that is expected to be " +
                    "finished has left rows behind."
            )
        }
    }

    /** Per-source history progress. `?` means the app does not measure that number. */
    private fun StringBuilder.appendHistorySection(diagnostics: SyncDiagnostics) {
        val history = diagnostics.history
        appendLine()
        appendLine("History:")
        appendLine("  Grant: ${history.grant ?: "none"} · Key version: ${history.keyVersion ?: "n/a"}")
        appendLine("  Session: ${history.sessionId ?: "none"}")
        if (history.sources.isEmpty()) {
            appendLine("  No history session recorded")
        }
        history.sources.forEach { source ->
            appendLine(
                "  ${source.source}: enqueued=${count(source.enqueued)}" +
                    " acked=${count(source.acked)} pending=${count(source.pending)}" +
                    " scanned=? skipped=? failed=?"
            )
            // Rendered as an ISO instant, NOT as epoch millis: the final redaction sweep
            // tokenizes any 7-15 digit run (it cannot tell an epoch from a phone number), so a
            // raw millisecond value would be printed as `id#…` and be useless to a reader.
            // The dead-letter section already reports timestamps this way.
            appendLine(
                "    checkpoint=" + (
                    source.checkpointDate?.let { Instant.ofEpochMilli(it).toString() }
                        ?: "not advanced"
                    ) +
                    "/${source.checkpointProviderId ?: "?"}" +
                    " · scan complete=${triState(source.scanComplete)}" +
                    " · delivered=${triState(source.delivered)}"
            )
            appendSessionLine(this, source.session)
        }
        // Mission §26: the two facts are separate, and saying so only when they DIFFER is what
        // makes the difference visible instead of academic.
        val scannedNotDelivered = history.sources.filter {
            it.scanComplete == true && it.delivered == false
        }
        if (scannedNotDelivered.isNotEmpty()) {
            appendLine(
                "  NOTE: scan complete but NOT delivered for " +
                    scannedNotDelivered.joinToString(", ") { it.source } +
                    ". The Provider has been read to the end; GMweb does not yet have all of it."
            )
        }
    }

    /**
     * The §70 arithmetic for one source's last history session, or an explicit "not measured".
     *
     * Two things this must never do. It must not print a reassuring line when nothing has been
     * measured — `not recorded yet` is not `balanced`. And it must not present the four numbers
     * without their identity, because the identity IS the claim: any reader can then check that
     * `eligible` equals the sum, which is the only reason to trust the `failed` count.
     */
    private fun appendSessionLine(
        sb: StringBuilder,
        session: SyncDiagnostics.HistorySessionSection?
    ) {
        if (session == null) {
            sb.appendLine(
                "    scan accounting: not recorded yet — no history session has been accounted for, " +
                    "so whether this source dropped anything is UNMEASURED"
            )
            return
        }
        sb.appendLine(
            "    scan accounting (session ${session.sessionId.take(8)}): " +
                "eligible=${session.eligible} = enqueued=${session.enqueued} + " +
                "skipped=${session.skipped} + failed=${session.failed}" +
                " · balanced=${triState(session.balanced)}" +
                " · scan exhausted=${triState(session.scanExhausted)}" +
                " · ${if (session.open) "still scanning" else "finished"}"
        )
        // Only worth a line when something was deliberately kept back; otherwise it is noise that
        // makes the section longer without telling anyone anything.
        if (session.skipped > 0) {
            sb.appendLine(
                "      kept back by policy: localOnly=${session.skippedLocalOnly}" +
                    " awaitingAnswer=${session.skippedAskPending}" +
                    " noDirection=${session.skippedNoDirection}" +
                    " syncOff=${session.skippedSyncOff}"
            )
        }
        if (session.failed > 0) {
            sb.appendLine(
                "      NOTE: ${session.failed} message(s) this scan read were eligible and have NO " +
                    "durable event. That is unexplained loss, not a policy decision."
            )
        }
        if (!session.balanced) {
            sb.appendLine(
                "      NOTE: the scan's own arithmetic does not balance (residual " +
                    "${session.residual}). Its other numbers cannot be trusted, and a report that " +
                    "hid this would be worse than one with no numbers at all."
            )
        }
    }

    private fun StringBuilder.appendCommandSection(diagnostics: SyncDiagnostics) {        val commands = diagnostics.commands
        appendLine()
        appendLine("Commands:")
        appendLine(
            "  received=${count(commands.received)} claimed=${count(commands.claimed)}" +
                " executing=${count(commands.executing)} completed=${count(commands.completed)}" +
                " failed=${count(commands.failed)}"
        )
        appendLine(
            "  Last command: ${ago(commands.lastCommandAt, diagnostics.generatedAt)}" +
                " · type=${commands.lastCommandType ?: "none"}"
        )
    }

    /** `null` is "not measured", which is a different fact from zero. */
    private fun count(value: Int?): String = value?.toString() ?: "?"


    private fun triState(value: Boolean?): String = when (value) {
        true -> "yes"
        false -> "NO"
        null -> "unknown"
    }

    private fun conclusionText(conclusion: GatewayConclusion): String = when (conclusion) {
        GatewayConclusion.NONE -> "Everything the gateway needs is fresh."
        GatewayConclusion.DISABLED -> "The gateway is not enabled, so nothing was checked."
        GatewayConclusion.NO_NETWORK -> "This phone has no validated internet connection."
        GatewayConclusion.SERVER_UNREACHABLE ->
            "The GMweb address resolved but nothing accepted the connection — check the port, " +
                "the reverse proxy and that the server is up."
        GatewayConclusion.DNS_PROBLEM ->
            "The GMweb host name did not resolve — check the configured URL and this network's DNS."
        GatewayConclusion.TLS_PROBLEM ->
            "The TLS certificate was rejected. It must be issued FOR the exact host in the " +
                "configured URL; a certificate for a different name is not accepted for an IP."
        GatewayConclusion.AUTH_REJECTED ->
            "GMweb rejected this device's key (an authentication response), so it is not " +
                "enrolled. Check the device key."
        GatewayConclusion.AUTH_UNVERIFIED ->
            "The device key could NOT be confirmed, and that is NOT a rejection: the check " +
                "itself did not produce a clear authentication answer. Look at the AUTH row " +
                "above for what actually happened. If the pull bridge above is polling " +
                "successfully, the credential is demonstrably accepted and only the check is at " +
                "fault."
        GatewayConclusion.REQUEST_CONTRACT_MISMATCH ->
            "The SERVER refused the request itself (a 400/404/405/422-style answer): this app " +
                "and your GMweb version disagree about the API contract — path, method or body " +
                "shape. This is NOT a rejected key, and changing the key will not fix it."
        GatewayConclusion.WRONG_URL ->
            "GMweb answered but the gateway route is not there — the configured URL is probably wrong."
        GatewayConclusion.RATE_LIMITED -> "GMweb is rate limiting this device; retrying later will help."
        GatewayConclusion.SERVER_ERROR -> "GMweb itself returned a server error."
        GatewayConclusion.BRIDGE_NOT_POLLING ->
            "GMweb is reachable and the outbound sync is fine, but this phone's delivery " +
                "polling is not healthy — send requests are not arriving."
        GatewayConclusion.BRIDGE_FAILING -> "The delivery poll is failing; see the last error above."
        GatewayConclusion.UPLOAD_STALLED ->
            "Inbound delivery is fine, but the OUTBOUND event sync is failing."
        GatewayConclusion.ACK_FAILING ->
            "The pull bridge is working, but the latest result ACK failed and has not recovered."
        GatewayConclusion.HISTORICAL_FAILURES ->
            "Live traffic is working. Historical dead-letter events remain for review."
        GatewayConclusion.UNKNOWN_FAILURE -> "A failure was recorded that does not match a known cause."
    }

    /**
     * The final sweep.
     *
     * `#` is excluded from the leading boundary so a value that a `detail` already tokenized
     * (`id#…`) is not tokenized a second time — that would be harmless but it would make the
     * report harder to read, and a report nobody reads is not a diagnostic.
     */
    private val longDigitRun = Regex("(?<![\\dA-Za-z#])\\+?\\d{7,15}(?!\\d)")

    private fun redact(text: String): String = text
        .lineSequence()
        .joinToString("\n") { line ->
            val clipped = if (line.length <= MAX_VALUE) line else line.take(MAX_VALUE - 1) + "…"
            longDigitRun.replace(clipped) { match -> "id#" + PhoneToken.of(match.value) }
        }
}
