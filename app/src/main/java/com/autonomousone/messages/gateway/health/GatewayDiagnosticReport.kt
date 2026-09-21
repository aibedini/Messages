package com.autonomousone.messages.gateway.health

import com.autonomousone.messages.data.DeadLetterBreakdownRow
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
        appendLine("TCP: ${snapshot.endpoint.lastTcpConnectMs?.let { "$it ms" } ?: "n/a"}")
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
        appendLine("  ACK failures: ${snapshot.pullBridge.ackFailures}")
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
            appendLine("Dead-lettered outbox events (aggregate only — nothing deleted):")
            if (deadLetters.isEmpty()) {
                appendLine("  ${snapshot.eventUpload.deadLetter} row(s); breakdown unavailable")
            } else {
                deadLetters.forEach { row ->
                    appendLine(
                        "  ${row.eventType} · crypto v${row.cryptoVersion} · ${row.priority}" +
                            " · count=${row.count}" +
                            " · attempts ${row.minAttempts}..${row.maxAttempts}" +
                            " · created ${Instant.ofEpochMilli(row.firstCreatedAt)}" +
                            " .. ${Instant.ofEpochMilli(row.lastCreatedAt)}"
                    )
                }
            }
            appendLine("  NOTE: the outbox row persists no failure reason, no HTTP status and no")
            appendLine("  update time, so those cannot be reported per event. Correlate by time")
            appendLine("  with the GATEWAY_UPLOAD lines in the on-device diagnostics log.")
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
