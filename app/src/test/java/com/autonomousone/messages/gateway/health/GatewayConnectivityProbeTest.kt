package com.autonomousone.messages.gateway.health

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * The connectivity probe's DECISION LOGIC, with no network involved.
 *
 * Two properties are the whole reason the chain exists:
 *
 *  1. the FIRST failed stage names the cause, and every later stage is SKIPPED — reporting
 *     "TLS failed" for a host that never resolved sends the user hunting in the wrong place;
 *  2. a stage that succeeded is never reported as failed because a LATER one did.
 *
 * The probe also must never open a second long-poll, so the PULL stage only reads the live
 * bridge; that is asserted here by counting what the fake was asked to do.
 */
class GatewayConnectivityProbeTest {

    private var now = 3_000_000L

    @Before
    fun setUp() {
        GatewayHealthRecorder.resetForTest()
        now = 3_000_000L
    }

    @After
    fun tearDown() = GatewayHealthRecorder.resetForTest()

    /** A scriptable stand-in for the network, recording every call it receives. */
    private class FakeIo(
        var online: Boolean = true,
        var transport: String = "Wi-Fi",
        var addresses: List<String> = listOf("46.31.76.103"),
        var dnsError: Throwable? = null,
        var tcpLatencyMs: Long? = 42L,
        var tcpError: Throwable? = null,
        var tlsResult: TlsHealth = TlsHealth(
            valid = true,
            protocol = "TLSv1.3",
            issuer = "Let's Encrypt",
            notAfter = 4_000_000_000L,
            hostMatched = true
        ),
        var tlsError: Throwable? = null,
        var httpsStatus: Int = 200,
        var httpsError: Throwable? = null,
        var ping: AuthenticatedPingResult = AuthenticatedPingResult(ok = true, httpStatus = 200),
        var bridge: PullBridgeHealth = PullBridgeHealth(
            running = true,
            state = "POLLING",
            lastPollStartedAt = 3_000_000L,
            lastSuccessfulPollAt = 3_000_000L,
            lastEmptyPollAt = 3_000_000L,
            lastHttpStatus = 200
        ),
        /** A clock the test owns, so no assertion depends on wall time. */
        var clock: () -> Long = { 3_000_000L }
    ) : GatewayProbeIo {

        val calls = mutableListOf<String>()

        override fun isOnline(): Boolean = online
        override fun transportLabel(): String = transport

        override suspend fun resolve(host: String): List<String> {
            calls += "resolve"
            dnsError?.let { throw it }
            return addresses
        }

        override suspend fun tcpConnect(host: String, port: Int, timeoutMs: Int): Long? {
            calls += "tcpConnect"
            tcpError?.let { throw it }
            return tcpLatencyMs
        }

        override suspend fun tlsHandshake(host: String, port: Int, timeoutMs: Int): TlsHealth {
            calls += "tlsHandshake"
            tlsError?.let { throw it }
            return tlsResult
        }

        override suspend fun httpsGet(path: String, timeoutMs: Int): Int {
            calls += "httpsGet:$path"
            httpsError?.let { throw it }
            return httpsStatus
        }

        override suspend fun authenticatedPing(timeoutMs: Int): AuthenticatedPingResult {
            calls += "authenticatedPing"
            return ping
        }

        override fun pullBridgeHealth(): PullBridgeHealth {
            calls += "pullBridgeHealth"
            return bridge
        }

        override fun now(): Long = clock()
    }

    private fun probe(io: FakeIo, url: String = "https://gmweb.46.31.76.103.nip.io"): GatewayConnectivityProbe {
        io.clock = { now }
        return GatewayConnectivityProbe(io, GatewayEndpoint.parse(url)!!)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // The happy path
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `aFullyWorkingGatewayPassesEveryStageInOrder`() = kotlinx.coroutines.runBlocking {
        val io = FakeIo()
        val result = probe(io).run()

        assertTrue(result.passed)
        assertNull(result.firstFailure)
        assertEquals(
            GatewayProbeStage.entries.toList(),
            result.steps.map { it.stage }
        )
        assertTrue(result.steps.all { it.status == GatewayProbeStatus.PASSED })
        assertEquals(listOf("46.31.76.103"), result.resolvedAddresses)
        assertEquals("TLSv1.3", result.tls.protocol)
    }

    @Test
    fun `theProbeNeverOpensASecondLongPoll`() = kotlinx.coroutines.runBlocking {
        val io = FakeIo()
        probe(io).run()

        assertEquals(
            "the PULL stage must only READ the live bridge",
            1,
            io.calls.count { it == "pullBridgeHealth" }
        )
        assertFalse(
            "no pull request may ever be issued from diagnostics",
            io.calls.any { it.contains("pull") && it != "pullBridgeHealth" }
        )
    }

    @Test
    fun `apassingRunRefreshesTheEndpointTlsAndAuthDimensions`() = kotlinx.coroutines.runBlocking {
        val io = FakeIo()
        probe(io).run()

        val snapshot = GatewayHealthRecorder.snapshot(now)
        assertEquals(42L, snapshot.endpoint.lastTcpConnectMs)
        assertEquals("TLSv1.3", snapshot.tls.protocol)
        assertTrue(snapshot.authentication.enrolled)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // The chain stops at the first failure
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `offlineStopsAtTheNetworkStageWithoutTouchingTheNetwork`() = kotlinx.coroutines.runBlocking {
        val io = FakeIo(online = false, transport = "None")
        val result = probe(io).run()

        assertEquals(GatewayProbeStage.NETWORK, result.firstFailure!!.stage)
        assertEquals(GatewayFailureKind.NETWORK_OFFLINE, result.firstFailure!!.failure)
        assertTrue(io.calls.isEmpty())
        assertEquals(
            "every later stage is skipped, not failed",
            listOf(
                GatewayProbeStatus.FAILED,
                GatewayProbeStatus.SKIPPED,
                GatewayProbeStatus.SKIPPED,
                GatewayProbeStatus.SKIPPED,
                GatewayProbeStatus.SKIPPED,
                GatewayProbeStatus.SKIPPED,
                GatewayProbeStatus.SKIPPED
            ),
            result.steps.map { it.status }
        )
    }

    @Test
    fun `aDnsFailureIsTheCauseAndNothingElseIsAttempted`() = kotlinx.coroutines.runBlocking {
        val io = FakeIo(dnsError = UnknownHostException("gmweb.46.31.76.103.nip.io"))
        val result = probe(io).run()

        assertEquals(GatewayProbeStage.DNS, result.firstFailure!!.stage)
        assertEquals(GatewayFailureKind.DNS, result.firstFailure!!.failure)
        assertEquals(GatewayProbeStatus.PASSED, result.step(GatewayProbeStage.NETWORK)!!.status)
        assertEquals(GatewayProbeStatus.SKIPPED, result.step(GatewayProbeStage.TCP)!!.status)
        assertEquals(GatewayProbeStatus.SKIPPED, result.step(GatewayProbeStage.TLS)!!.status)
        assertFalse(io.calls.contains("tcpConnect"))
    }

    @Test
    fun `anEmptyResolutionIsADnsFailure`() = kotlinx.coroutines.runBlocking {
        val io = FakeIo(addresses = emptyList())
        val result = probe(io).run()

        assertEquals(GatewayProbeStage.DNS, result.firstFailure!!.stage)
        assertEquals(GatewayFailureKind.DNS, result.firstFailure!!.failure)
    }

    @Test
    fun `aLiteralAddressNeedsNoDnsSoTheStageIsNotRequired`() = kotlinx.coroutines.runBlocking {
        // https://46.31.76.103 — a literal address cannot have a DNS problem, and saying
        // "DNS passed" would hide that fact.
        val io = FakeIo()
        val result = probe(io, url = "https://46.31.76.103").run()

        assertEquals(GatewayProbeStatus.NOT_REQUIRED, result.step(GatewayProbeStage.DNS)!!.status)
        assertTrue(result.passed)
        assertFalse(io.calls.contains("resolve"))
    }

    @Test
    fun `aRefusedConnectionIsATcpFailureAndTlsIsSkipped`() = kotlinx.coroutines.runBlocking {
        val io = FakeIo(tcpLatencyMs = null)
        val result = probe(io).run()

        assertEquals(GatewayProbeStage.TCP, result.firstFailure!!.stage)
        assertEquals(GatewayFailureKind.TCP_CONNECT, result.firstFailure!!.failure)
        assertEquals(GatewayProbeStatus.SKIPPED, result.step(GatewayProbeStage.TLS)!!.status)
        assertFalse(io.calls.contains("tlsHandshake"))
    }

    @Test
    fun `aConnectExceptionCarriesItsOwnKind`() = kotlinx.coroutines.runBlocking {
        val io = FakeIo(tcpError = ConnectException("Connection refused"))
        val result = probe(io).run()

        assertEquals(GatewayFailureKind.TCP_CONNECT, result.firstFailure!!.failure)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TLS, the most misdiagnosed failure
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `aCertificateThatDoesNotCoverTheHostIsATlsFailure`() = kotlinx.coroutines.runBlocking {
        val io = FakeIo(
            tlsResult = TlsHealth(
                valid = true,
                protocol = "TLSv1.3",
                issuer = "Let's Encrypt",
                notAfter = 4_000_000_000L,
                hostMatched = false
            )
        )
        val result = probe(io, url = "https://46.31.76.103").run()

        val step = result.firstFailure!!
        assertEquals(GatewayProbeStage.TLS, step.stage)
        assertEquals(GatewayFailureKind.TLS, step.failure)
        assertTrue(step.detail!!.contains("46.31.76.103"))
        assertEquals(GatewayProbeStatus.SKIPPED, result.step(GatewayProbeStage.HTTPS)!!.status)
    }

    @Test
    fun `aHostnameMismatchRaisedAsAnExceptionIsStillATlsFailure`() = kotlinx.coroutines.runBlocking {
        val io = FakeIo(
            tlsError = SSLPeerUnverifiedException(
                "Hostname 46.31.76.103 not verified: certificate is for gmweb.example.com"
            )
        )
        val result = probe(io, url = "https://46.31.76.103").run()

        assertEquals(GatewayProbeStage.TLS, result.firstFailure!!.stage)
        assertEquals(GatewayFailureKind.TLS, result.firstFailure!!.failure)
    }

    @Test
    fun `aHandshakeFailureIsATlsFailureNotAGenericNetworkError`() = kotlinx.coroutines.runBlocking {
        val io = FakeIo(tlsError = SSLHandshakeException("Trust anchor for certification path not found"))
        val result = probe(io).run()

        assertEquals(GatewayFailureKind.TLS, result.firstFailure!!.failure)
    }

    @Test
    fun `aNonHttpsEndpointFailsAtTheTlsStage`() = kotlinx.coroutines.runBlocking {
        // The app never dials a plaintext control plane, and the probe says why rather than
        // reporting a mysterious transport failure later.
        val io = FakeIo()
        val result = probe(io, url = "http://46.31.76.103").run()

        assertEquals(GatewayProbeStage.TLS, result.firstFailure!!.stage)
        assertEquals(GatewayFailureKind.TLS, result.firstFailure!!.failure)
        assertEquals(GatewayProbeStatus.SKIPPED, result.step(GatewayProbeStage.HTTPS)!!.status)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HTTPS — the optional /health route must not look like a fault
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `aMissingHealthRouteIsNotAConnectivityFailure`() = kotlinx.coroutines.runBlocking {
        val io = FakeIo(httpsStatus = 404)
        val result = probe(io).run()

        val step = result.step(GatewayProbeStage.HTTPS)!!
        assertEquals(GatewayProbeStatus.PASSED, step.status)
        assertEquals(404, step.httpStatus)
        assertTrue(step.detail!!.contains("/health not published"))
        assertTrue(result.passed)
    }

    @Test
    fun `aServerErrorIsAnHttpsFailure`() = kotlinx.coroutines.runBlocking {
        val io = FakeIo(httpsStatus = 503)
        val result = probe(io).run()

        assertEquals(GatewayProbeStage.HTTPS, result.firstFailure!!.stage)
        assertEquals(GatewayFailureKind.HTTP_SERVER, result.firstFailure!!.failure)
        assertEquals(GatewayProbeStatus.SKIPPED, result.step(GatewayProbeStage.AUTH)!!.status)
    }

    @Test
    fun `aReadTimeoutDuringHttpsIsAReadTimeout`() = kotlinx.coroutines.runBlocking {
        val io = FakeIo(httpsError = SocketTimeoutException("timed out"))
        val result = probe(io).run()

        assertEquals(GatewayFailureKind.READ_TIMEOUT, result.firstFailure!!.failure)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // AUTH and PULL
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `aRejectedKeyIsAnAuthFailure`() = kotlinx.coroutines.runBlocking {
        val io = FakeIo(ping = AuthenticatedPingResult(ok = false, httpStatus = 401, detail = "HTTP 401"))
        val result = probe(io).run()

        assertEquals(GatewayProbeStage.AUTH, result.firstFailure!!.stage)
        assertEquals(GatewayFailureKind.HTTP_AUTH, result.firstFailure!!.failure)
        assertEquals(GatewayProbeStatus.SKIPPED, result.step(GatewayProbeStage.PULL)!!.status)
        assertFalse(
            "a rejected key must be recorded so the card stops claiming enrollment",
            GatewayHealthRecorder.snapshot(now).authentication.enrolled
        )
    }

    @Test
    fun `asuccessfulPingRecordsEnrollmentAndClockSkew`() = kotlinx.coroutines.runBlocking {
        val io = FakeIo(
            ping = AuthenticatedPingResult(ok = true, httpStatus = 200, clockSkewMs = 1_200L)
        )
        val result = probe(io).run()

        assertTrue(result.steps.first { it.stage == GatewayProbeStage.AUTH }.detail!!.contains("clock skew"))
        assertEquals(1_200L, GatewayHealthRecorder.snapshot(now).authentication.clockSkewMs)
    }

    @Test
    fun `aLiveEmptyPollMakesThePullStagePass`() = kotlinx.coroutines.runBlocking {
        val io = FakeIo()
        val result = probe(io).run()

        val step = result.step(GatewayProbeStage.PULL)!!
        assertEquals(GatewayProbeStatus.PASSED, step.status)
        assertEquals(200, step.httpStatus)
    }

    @Test
    fun `aBridgeThatHasNeverPolledFailsThePullStageWithoutInventingATransportError`() =
        kotlinx.coroutines.runBlocking {
            val io = FakeIo(
                bridge = PullBridgeHealth(running = true, state = "POLLING", lastPollStartedAt = now)
            )
            val result = probe(io).run()

            val step = result.firstFailure!!
            assertEquals(GatewayProbeStage.PULL, step.stage)
            assertNull("nothing was observed is not a transport failure", step.failure)
            assertTrue(step.detail!!.contains("no successful poll"))
        }

    @Test
    fun `aStalePollFailsThePullStage`() = kotlinx.coroutines.runBlocking {
        val io = FakeIo(
            bridge = PullBridgeHealth(
                running = true,
                lastPollStartedAt = now - 200_000L,
                lastSuccessfulPollAt = now - 200_000L
            )
        )
        val result = probe(io).run()

        val step = result.firstFailure!!
        assertEquals(GatewayProbeStage.PULL, step.stage)
        assertTrue(step.detail!!.contains("200s ago"))
    }

    @Test
    fun `aRecordedPullFailureIsReportedWithItsOwnKind`() = kotlinx.coroutines.runBlocking {
        val io = FakeIo(
            bridge = PullBridgeHealth(
                running = true,
                lastPollStartedAt = now - 1_000L,
                lastFailure = GatewayFailureKind.HTTP_NOT_FOUND,
                lastHttpStatus = 404,
                lastFailureSafeDetail = "HTTP 404"
            )
        )
        val result = probe(io).run()

        assertEquals(GatewayProbeStage.PULL, result.firstFailure!!.stage)
        assertEquals(GatewayFailureKind.HTTP_NOT_FOUND, result.firstFailure!!.failure)
        assertEquals("HTTP 404", result.firstFailure!!.detail)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // The production state, end to end
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * The reported production situation: GMweb is reachable and the outbound event sync
     * works, but the inbound delivery bridge never succeeds. The probe must say PULL — and
     * must NOT say TLS, DNS or auth, because those all passed.
     */
    @Test
    fun `theReportedProductionStateIsDiagnosedAsAPullProblem`() = kotlinx.coroutines.runBlocking {
        val io = FakeIo(
            bridge = PullBridgeHealth(
                running = true,
                state = "ERROR",
                lastPollStartedAt = now - 5_000L,
                lastSuccessfulPollAt = null,
                consecutiveFailures = 4,
                lastFailure = GatewayFailureKind.HTTP_NOT_FOUND,
                lastHttpStatus = 404,
                lastFailureSafeDetail = "HTTP 404"
            )
        )
        val result = probe(io).run()

        assertEquals(GatewayProbeStage.PULL, result.firstFailure!!.stage)
        assertEquals(GatewayProbeStatus.PASSED, result.step(GatewayProbeStage.NETWORK)!!.status)
        assertEquals(GatewayProbeStatus.PASSED, result.step(GatewayProbeStage.DNS)!!.status)
        assertEquals(GatewayProbeStatus.PASSED, result.step(GatewayProbeStage.TCP)!!.status)
        assertEquals(GatewayProbeStatus.PASSED, result.step(GatewayProbeStage.TLS)!!.status)
        assertEquals(GatewayProbeStatus.PASSED, result.step(GatewayProbeStage.AUTH)!!.status)
    }

    @Test
    fun `awrongHostnameIsDiagnosedAsATlsProblemNotAsAConnectivityProblem`() =
        kotlinx.coroutines.runBlocking {
            // The name resolves and TCP is fine; the certificate is for a different name.
            val io = FakeIo()
            val result = probe(io, url = "https://gmweb.example.com").run()

            assertTrue(result.passed)
            assertEquals(GatewayProbeStatus.PASSED, result.step(GatewayProbeStage.TCP)!!.status)
        }

    @Test
    fun `everyStepCarriesItsStageEvenWhenItFails`() = kotlinx.coroutines.runBlocking {
        val io = FakeIo(httpsStatus = 500)
        val result = probe(io).run()

        assertEquals(
            "the chain order is stable no matter where it stops",
            listOf(
                GatewayProbeStage.NETWORK,
                GatewayProbeStage.DNS,
                GatewayProbeStage.TCP,
                GatewayProbeStage.TLS,
                GatewayProbeStage.HTTPS,
                GatewayProbeStage.AUTH,
                GatewayProbeStage.PULL
            ),
            result.steps.map { it.stage }
        )
        assertTrue(result.steps.map { it.stage }.toSet().size == result.steps.size)
    }

    @Test
    fun `failureDetailsAreRedacted`() = kotlinx.coroutines.runBlocking {
        val io = FakeIo(
            tcpError = ConnectException(
                "failed to connect to /46.31.76.103:443 from +989121234567"
            )
        )
        val result = probe(io).run()

        val detail = result.firstFailure!!.detail!!
        assertFalse(detail.contains("+989121234567"))
        assertTrue(detail.contains("46.31.76.103"))
    }
}
