package com.autonomousone.messages.gateway.health

/**
 * One stage of the connectivity chain, in the order it must succeed.
 *
 * The order is the point: `NETWORK → DNS → TCP → TLS → HTTPS → AUTH → PULL`. When the chain
 * breaks, the FIRST failed stage names the cause, and every stage after it is reported as
 * SKIPPED rather than failed — reporting "TLS failed" for a host that never resolved is how
 * a diagnosis turns into a wild goose chase.
 */
enum class GatewayProbeStage {
    NETWORK,
    DNS,
    TCP,
    TLS,
    HTTPS,
    AUTH,
    PULL
}

enum class GatewayProbeStatus {
    /** Ran and succeeded. */
    PASSED,

    /** Ran and failed: this is the stage that broke. */
    FAILED,

    /** Deliberately not run — an earlier stage failed. */
    SKIPPED,

    /** Genuinely not applicable, e.g. DNS for a literal address. */
    NOT_REQUIRED
}

/** One stage's outcome. [detail] is already redacted. */
data class GatewayProbeStep(
    val stage: GatewayProbeStage,
    val status: GatewayProbeStatus,
    val durationMs: Long? = null,
    val detail: String? = null,
    val failure: GatewayFailureKind? = null,
    val httpStatus: Int? = null
) {
    val failed: Boolean get() = status == GatewayProbeStatus.FAILED
}

/**
 * The result of one full diagnostic run.
 *
 * [firstFailure] is what the user is actually told. Every later stage carries SKIPPED, so
 * the report reads as a chain rather than as a list of unrelated complaints.
 */
data class GatewayConnectivityResult(
    val startedAt: Long,
    val steps: List<GatewayProbeStep>,
    val resolvedAddresses: List<String> = emptyList(),
    val tls: TlsHealth = TlsHealth()
) {
    fun step(stage: GatewayProbeStage): GatewayProbeStep? = steps.firstOrNull { it.stage == stage }

    val firstFailure: GatewayProbeStep?
        get() = steps.firstOrNull { it.failed }

    val passed: Boolean get() = firstFailure == null
}

data class AuthenticatedPingResult(
    val ok: Boolean,
    val httpStatus: Int? = null,
    val clockSkewMs: Long? = null,
    val detail: String? = null
)

/**
 * The low-level operations the probe performs, extracted so every stage's DECISION LOGIC is
 * testable on the JVM with no network at all.
 *
 * The production implementation ([com.autonomousone.messages.gateway.AndroidGatewayProbeIo])
 * is bound by three rules that are not negotiable:
 *  - certificate validation is NEVER bypassed: no trust-all `TrustManager`, no
 *    `HostnameVerifier { true }`, no permissive `SSLContext`;
 *  - hostname verification is ALWAYS on, so a certificate issued for
 *    `gmweb.example.com` is rejected for `203.0.113.10` and the user is told exactly that;
 *  - ICMP is never the health authority. Mobile networks and servers block it while HTTPS
 *    works perfectly, so reachability is a TCP connect.
 */
interface GatewayProbeIo {

    fun isOnline(): Boolean

    fun transportLabel(): String

    suspend fun resolve(host: String): List<String>

    /** TCP connect latency in ms, or null when the connection failed. */
    suspend fun tcpConnect(host: String, port: Int, timeoutMs: Int): Long?

    /** Opens TLS and returns negotiated facts. Throws when the certificate is rejected. */
    suspend fun tlsHandshake(host: String, port: Int, timeoutMs: Int): TlsHealth

    /** One unauthenticated HTTPS request; returns the HTTP status. */
    suspend fun httpsGet(path: String, timeoutMs: Int): Int

    /** One AUTHENTICATED liveness check that changes no server state and enqueues nothing. */
    suspend fun authenticatedPing(timeoutMs: Int): AuthenticatedPingResult

    /** The LIVE pull bridge's health. The probe must never open a second long-poll. */
    fun pullBridgeHealth(): PullBridgeHealth

    fun now(): Long
}

/**
 * A single-pass connectivity diagnosis (v3.4.x P0).
 *
 * RUN ON EXPLICIT USER ACTION. This is the expensive half of the health system: it resolves
 * DNS, opens a socket, performs a TLS handshake and makes two HTTPS requests. None of that
 * may happen on a timer. The cheap, continuous half is [GatewayHealthRecorder], which the
 * live components feed for free — keeping the two apart is what stops a device with 360 000
 * messages from re-running provider counts, or DNS, every few seconds.
 *
 * The probe WRITES its findings into the recorder, so running diagnostics also refreshes the
 * status card's endpoint, TLS and auth rows.
 */
class GatewayConnectivityProbe(
    private val io: GatewayProbeIo,
    private val endpoint: GatewayEndpoint
) {

    companion object {
        const val DEFAULT_TIMEOUT_MS = 8_000

        /**
         * Ceiling on the unauthenticated reachability check.
         *
         * Deliberately SHORT: `/health` is an optional server-side addition in the brief,
         * so its absence must cost a quick round trip, not a long timeout.
         */
        const val HEALTH_TIMEOUT_MS = 6_000
    }

    suspend fun run(): GatewayConnectivityResult {
        val startedAt = io.now()
        val steps = mutableListOf<GatewayProbeStep>()
        var resolved: List<String> = emptyList()
        var tls = TlsHealth()

        steps += networkStep()
        if (steps.last().failed) return finish(startedAt, steps, resolved, tls)

        val dns = dnsStep()
        steps += dns.step
        resolved = dns.resolved
        if (steps.last().failed) return finish(startedAt, steps, resolved, tls)

        steps += tcpStep()
        if (steps.last().failed) return finish(startedAt, steps, resolved, tls)

        val tlsStep = tlsStep()
        steps += tlsStep.step
        tls = tlsStep.health
        if (steps.last().failed) return finish(startedAt, steps, resolved, tls)

        steps += httpsStep()
        if (steps.last().failed) return finish(startedAt, steps, resolved, tls)

        steps += authStep()
        if (steps.last().failed) return finish(startedAt, steps, resolved, tls)

        steps += pullStep()

        return finish(startedAt, steps, resolved, tls)
    }

    // ── Stages ──────────────────────────────────────────────────────────────

    private fun networkStep(): GatewayProbeStep {
        if (!io.isOnline()) {
            return fail(
                GatewayProbeStage.NETWORK,
                GatewayFailureKind.NETWORK_OFFLINE,
                "no validated internet (${io.transportLabel()})"
            )
        }
        return pass(GatewayProbeStage.NETWORK, detail = io.transportLabel())
    }

    private class DnsOutcome(val step: GatewayProbeStep, val resolved: List<String>)

    private suspend fun dnsStep(): DnsOutcome {
        if (endpoint.isLiteralAddress) {
            // A literal address needs no resolution. Calling this a PASS would imply DNS was
            // exercised; NOT_REQUIRED is the honest answer, and it is what tells the user
            // that a DNS problem cannot be their fault here.
            return DnsOutcome(
                GatewayProbeStep(
                    stage = GatewayProbeStage.DNS,
                    status = GatewayProbeStatus.NOT_REQUIRED,
                    detail = "literal address"
                ),
                emptyList()
            )
        }
        val started = io.now()
        return try {
            val addresses = io.resolve(endpoint.host)
            if (addresses.isEmpty()) {
                DnsOutcome(
                    fail(
                        GatewayProbeStage.DNS,
                        GatewayFailureKind.DNS,
                        "no addresses for ${endpoint.host}"
                    ),
                    emptyList()
                )
            } else {
                DnsOutcome(
                    pass(
                        GatewayProbeStage.DNS,
                        durationMs = io.now() - started,
                        detail = addresses.joinToString(", ")
                    ),
                    addresses
                )
            }
        } catch (error: Throwable) {
            DnsOutcome(
                fail(
                    GatewayProbeStage.DNS,
                    GatewayFailureKind.DNS,
                    GatewayHealthText.safeDetail(error.message) ?: "resolution failed"
                ),
                emptyList()
            )
        }
    }

    private suspend fun tcpStep(): GatewayProbeStep {
        val started = io.now()
        return try {
            val latency = io.tcpConnect(endpoint.host, endpoint.port, DEFAULT_TIMEOUT_MS)
            if (latency == null) {
                fail(
                    GatewayProbeStage.TCP,
                    GatewayFailureKind.TCP_CONNECT,
                    "${endpoint.displayHost} did not accept a connection"
                )
            } else {
                // The UI label for this row is "Server latency" / "TCP 443" — never "ping",
                // because ICMP is not what was measured.
                pass(
                    GatewayProbeStage.TCP,
                    durationMs = latency.takeIf { it > 0 } ?: (io.now() - started),
                    detail = "TCP ${endpoint.port}"
                )
            }
        } catch (error: Throwable) {
            fail(
                GatewayProbeStage.TCP,
                GatewayFailureKind.classify(error = error, phase = GatewayRequestPhase.CONNECT),
                GatewayHealthText.safeDetail(error.message) ?: "connection failed"
            )
        }
    }

    private class TlsOutcome(val step: GatewayProbeStep, val health: TlsHealth)

    private suspend fun tlsStep(): TlsOutcome {
        if (!endpoint.secure) {
            return TlsOutcome(
                fail(GatewayProbeStage.TLS, GatewayFailureKind.TLS, "endpoint is not HTTPS"),
                TlsHealth(valid = false, hostMatched = false, lastCheckedAt = io.now())
            )
        }
        val started = io.now()
        return try {
            val health = io.tlsHandshake(endpoint.host, endpoint.port, DEFAULT_TIMEOUT_MS)
            when {
                health.hostMatched == false -> TlsOutcome(
                    fail(
                        GatewayProbeStage.TLS,
                        GatewayFailureKind.TLS,
                        "certificate does not cover ${endpoint.host}"
                    ),
                    health
                )
                health.valid == false -> TlsOutcome(
                    fail(GatewayProbeStage.TLS, GatewayFailureKind.TLS, "certificate is not valid"),
                    health
                )
                else -> TlsOutcome(
                    pass(
                        GatewayProbeStage.TLS,
                        durationMs = io.now() - started,
                        detail = health.protocol ?: "TLS"
                    ),
                    health
                )
            }
        } catch (error: Throwable) {
            // A rejected certificate is the single most misdiagnosed failure in this app, so
            // it gets its own conclusion instead of a generic "network error".
            val kind = GatewayFailureKind.classify(error = error, phase = GatewayRequestPhase.TLS)
            TlsOutcome(
                fail(
                    GatewayProbeStage.TLS,
                    if (kind == GatewayFailureKind.UNKNOWN) GatewayFailureKind.TLS else kind,
                    GatewayHealthText.safeDetail(error.message) ?: "TLS handshake failed"
                ),
                TlsHealth(valid = false, hostMatched = null, lastCheckedAt = io.now())
            )
        }
    }

    private suspend fun httpsStep(): GatewayProbeStep {
        val started = io.now()
        return try {
            val status = io.httpsGet("/health", HEALTH_TIMEOUT_MS)
            when {
                status in 200..299 -> pass(
                    GatewayProbeStage.HTTPS,
                    durationMs = io.now() - started,
                    detail = "HTTPS /health",
                    httpStatus = status
                )
                // The server ANSWERED, which is exactly what this stage measures. A missing
                // /health route is not a connectivity fault, and reporting it as one would
                // send the user hunting for a problem that does not exist.
                status == 404 -> pass(
                    GatewayProbeStage.HTTPS,
                    durationMs = io.now() - started,
                    detail = "server answered; /health not published",
                    httpStatus = status
                )
                else -> fail(
                    GatewayProbeStage.HTTPS,
                    GatewayFailureKind.fromHttpStatus(status) ?: GatewayFailureKind.UNKNOWN,
                    "HTTP $status",
                    httpStatus = status
                )
            }
        } catch (error: Throwable) {
            fail(
                GatewayProbeStage.HTTPS,
                GatewayFailureKind.classify(error = error, phase = GatewayRequestPhase.READ),
                GatewayHealthText.safeDetail(error.message) ?: "HTTPS request failed"
            )
        }
    }

    /**
     * The authenticated check.
     *
     * THE RULE THIS METHOD EXISTS TO ENFORCE: only a 401/403 may be reported as "the device key
     * was rejected". Every other outcome is [AuthVerification.UNVERIFIABLE] with its own
     * explanation, because a 400 means the SERVER could not accept the REQUEST — an app/server
     * contract mismatch — and dressing that up as a rejected credential produced a false alarm
     * on a device whose gateway was demonstrably working.
     */
    private suspend fun authStep(): GatewayProbeStep {
        val started = io.now()
        return try {
            val ping = io.authenticatedPing(DEFAULT_TIMEOUT_MS)
            if (ping.ok) {
                GatewayHealthRecorder.onAuthProbe(
                    AuthHealth(
                        status = AuthVerification.VERIFIED,
                        lastVerifiedAt = io.now(),
                        clockSkewMs = ping.clockSkewMs
                    )
                )
                pass(
                    GatewayProbeStage.AUTH,
                    durationMs = io.now() - started,
                    detail = ping.clockSkewMs
                        ?.let { "clock skew ${it / 1000}s" }
                        ?: "device enrolled",
                    httpStatus = ping.httpStatus
                )
            } else {
                val kind = GatewayFailureKind.fromHttpStatus(ping.httpStatus ?: 0)
                    ?: GatewayFailureKind.UNKNOWN
                val rejected = kind.isAuthenticationRejection
                GatewayHealthRecorder.onAuthProbe(
                    AuthHealth(
                        status = if (rejected) {
                            AuthVerification.REJECTED
                        } else {
                            AuthVerification.UNVERIFIABLE
                        },
                        lastVerifiedAt = io.now(),
                        unverifiableReason = if (rejected) {
                            null
                        } else {
                            ping.detail ?: "HTTP ${ping.httpStatus ?: "n/a"} (${kind.name})"
                        }
                    )
                )
                fail(
                    GatewayProbeStage.AUTH,
                    kind,
                    // The message is DERIVED from the classification, never hardcoded. This is
                    // the line that used to say "device key not accepted by GMweb" for a 400.
                    ping.detail ?: defaultAuthFailureDetail(kind, ping.httpStatus),
                    httpStatus = ping.httpStatus
                )
            }
        } catch (error: Throwable) {
            val kind = GatewayFailureKind.classify(error = error, phase = GatewayRequestPhase.READ)
            GatewayHealthRecorder.onAuthProbe(
                AuthHealth(
                    status = AuthVerification.UNVERIFIABLE,
                    lastVerifiedAt = io.now(),
                    unverifiableReason = GatewayHealthText.safeDetail(error.message) ?: kind.name
                )
            )
            fail(
                GatewayProbeStage.AUTH,
                kind,
                GatewayHealthText.safeDetail(error.message) ?: "the authenticated check did not complete"
            )
        }
    }

    /** What to say when the check produced no message of its own. Never claims a rejection. */
    private fun defaultAuthFailureDetail(
        kind: GatewayFailureKind,
        httpStatus: Int?
    ): String = when {
        kind.isAuthenticationRejection -> "GMweb rejected this device's key (HTTP $httpStatus)"
        kind == GatewayFailureKind.HTTP_BAD_REQUEST ->
            "GMweb could not accept the check request (HTTP 400) — the app's request does not " +
                "match the server's contract; this is not a rejected key"
        kind == GatewayFailureKind.HTTP_METHOD_NOT_ALLOWED ->
            "GMweb does not allow this method on the check route (HTTP 405)"
        kind == GatewayFailureKind.HTTP_NOT_FOUND ->
            "the check route is not present on this server (HTTP 404)"
        kind == GatewayFailureKind.HTTP_SERVER ->
            "GMweb returned a server error (HTTP $httpStatus)"
        else -> "the credential could not be verified (${kind.name})"
    }

    private fun pullStep(): GatewayProbeStep {
        // NEVER a second long-poll. A real OutboxPoller may be holding a 25-second request
        // open right now; opening another would compete with it for the same server-side
        // queue and could double-deliver a task. The probe only READS the live bridge.
        val bridge = io.pullBridgeHealth()
        val now = io.now()
        return when {
            bridge.lastFailure != null -> GatewayProbeStep(
                stage = GatewayProbeStage.PULL,
                status = GatewayProbeStatus.FAILED,
                detail = bridge.lastFailureSafeDetail ?: "pull failed",
                failure = bridge.lastFailure,
                httpStatus = bridge.lastHttpStatus
            )
            bridge.freshWithin(GatewayHealthRules.PULL_FRESH_MS, now) -> GatewayProbeStep(
                stage = GatewayProbeStage.PULL,
                status = GatewayProbeStatus.PASSED,
                durationMs = bridge.stalenessMs(now),
                detail = "last successful poll ${bridge.stalenessMs(now)?.div(1000)}s ago",
                httpStatus = bridge.lastHttpStatus
            )
            bridge.lastSuccessfulPollAt == null -> GatewayProbeStep(
                stage = GatewayProbeStage.PULL,
                status = GatewayProbeStatus.FAILED,
                // No classified transport error: nothing was observed, which is not the same
                // as a transport failure and must not be reported as one.
                failure = null,
                detail = "no successful poll since this process started"
            )
            else -> GatewayProbeStep(
                stage = GatewayProbeStage.PULL,
                status = GatewayProbeStatus.FAILED,
                failure = null,
                detail = "last successful poll ${bridge.stalenessMs(now)?.div(1000)}s ago"
            )
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun pass(
        stage: GatewayProbeStage,
        durationMs: Long? = null,
        detail: String? = null,
        httpStatus: Int? = null
    ) = GatewayProbeStep(
        stage = stage,
        status = GatewayProbeStatus.PASSED,
        durationMs = durationMs,
        detail = GatewayHealthText.safeDetail(detail),
        httpStatus = httpStatus
    )

    private fun fail(
        stage: GatewayProbeStage,
        kind: GatewayFailureKind,
        detail: String?,
        httpStatus: Int? = null
    ) = GatewayProbeStep(
        stage = stage,
        status = GatewayProbeStatus.FAILED,
        detail = GatewayHealthText.safeDetail(detail),
        failure = kind,
        httpStatus = httpStatus
    )

    /**
     * Fills in SKIPPED for every stage after the one that failed, and PUBLISHES the probe-owned
     * dimensions.
     *
     * THE PUBLISH BELONGS HERE, on every exit path, not after the last stage. It used to sit at
     * the end of `run()`, where the early returns for a failed stage skipped it — so a probe
     * that measured TLSv1.3 successfully and then failed at AUTH never recorded its TLS result,
     * and the card kept saying "TLS: not checked" while the same run's report said
     * "TLS PASSED · TLSv1.3". Two states, one run, disagreeing.
     */
    private fun finish(
        startedAt: Long,
        steps: List<GatewayProbeStep>,
        resolved: List<String>,
        tls: TlsHealth
    ): GatewayConnectivityResult {
        val failedAt = steps.indexOfFirst { it.failed }
        val completed = if (failedAt < 0) {
            steps
        } else {
            val done = steps.toMutableList()
            GatewayProbeStage.entries
                .filter { it.ordinal > done[failedAt].stage.ordinal }
                .forEach { later ->
                    if (done.none { it.stage == later }) {
                        done += GatewayProbeStep(later, GatewayProbeStatus.SKIPPED)
                    }
                }
            done.sortedBy { it.stage.ordinal }
        }
        publishDimensions(completed, resolved, tls)
        return GatewayConnectivityResult(
            startedAt = startedAt,
            steps = completed,
            resolvedAddresses = resolved,
            tls = tls
        )
    }

    /**
     * Records what the probe LEARNED, whether or not the chain completed.
     *
     * A stage that was not reached leaves its dimension untouched (the card's "not checked"),
     * and a stage that ran records its result — so the card can only ever say "not checked"
     * for something that genuinely was not checked.
     */
    private fun publishDimensions(
        steps: List<GatewayProbeStep>,
        resolved: List<String>,
        tls: TlsHealth
    ) {
        val at = io.now()
        steps.firstOrNull { it.stage == GatewayProbeStage.TCP }
            ?.takeIf { it.status == GatewayProbeStatus.PASSED }
            ?.let { GatewayHealthRecorder.onTcpProbe(it.durationMs, at) }

        // Publish TLS whenever the stage actually RAN — passed or failed. A failed handshake is
        // still a measured TLS fact, and dropping it is how a bad certificate stayed invisible.
        val tlsStep = steps.firstOrNull { it.stage == GatewayProbeStage.TLS }
        if (tlsStep != null && tlsStep.status != GatewayProbeStatus.SKIPPED) {
            GatewayHealthRecorder.onTlsProbe(
                tls.copy(
                    lastCheckedAt = tls.lastCheckedAt ?: at,
                    // A hostname mismatch surfaces as a failed stage; carry that verdict into
                    // the dimension so the card and the report cannot disagree about it.
                    hostMatched = if (tlsStep.status == GatewayProbeStatus.FAILED) {
                        tls.hostMatched ?: false
                    } else {
                        tls.hostMatched
                    }
                )
            )
        }

        if (resolved.isNotEmpty()) {
            GatewayHealthRecorder.onDnsProbe(resolved, at)
        }
    }
}
