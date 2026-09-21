package com.autonomousone.messages.gateway

import android.content.Context
import android.util.Log
import com.autonomousone.messages.BuildConfig
import com.autonomousone.messages.gateway.health.AuthenticatedPingResult
import com.autonomousone.messages.gateway.health.GatewayHealthRecorder
import com.autonomousone.messages.gateway.health.GatewayProbeIo
import com.autonomousone.messages.gateway.health.PullBridgeHealth
import com.autonomousone.messages.gateway.health.TlsHealth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocket

/**
 * The production [GatewayProbeIo]: the only part of the diagnostics that touches the network.
 *
 * ── THE THREE RULES, AND WHY THEY ARE IN THIS FILE'S HEADER ──────────────────
 *
 * 1. **Certificate validation is never bypassed.** No trust-all `TrustManager`, no
 *    `HostnameVerifier { true }`, no permissive `SSLContext`, and no "insecure" flag
 *    anywhere in this file or reachable from it. The whole point of the diagnostics is to
 *    tell the user their certificate is wrong; an app that ignores the certificate cannot
 *    do that, and would ship the bug instead of reporting it.
 *
 * 2. **Hostname verification is always on.** A certificate issued for
 *    `gmweb.example.com` is rejected for `https://46.31.76.103`, and the report says
 *    exactly that. This is a real situation for this deployment — Let's Encrypt now issues
 *    IP certificates, but such a certificate must be issued FOR the IP, not for a name that
 *    happens to resolve to it.
 *
 * 3. **ICMP is not the health authority.** Mobile networks and many servers drop ICMP while
 *    HTTPS works perfectly, so reachability is measured with a TCP connect. If ICMP were
 *    ever added as an extra diagnostic it could never downgrade a passing TCP/TLS result.
 *
 * Everything here is also bounded: every call has a timeout, because a diagnostic that can
 * hang is worse than no diagnostic.
 */
class AndroidGatewayProbeIo(
    context: Context,
    private val prefs: GatewayPreferences
) : GatewayProbeIo {

    companion object {
        private const val TAG = "GATEWAY_PROBE"
    }

    private val appContext = context.applicationContext
    private val networkMonitor = NetworkMonitor.get(appContext)

    override fun isOnline(): Boolean = networkMonitor.isOnline()

    override fun transportLabel(): String = networkMonitor.transportLabel()

    override suspend fun resolve(host: String): List<String> = withContext(Dispatchers.IO) {
        InetAddress.getAllByName(host).mapNotNull { it.hostAddress }
    }

    override suspend fun tcpConnect(host: String, port: Int, timeoutMs: Int): Long? =
        withContext(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), timeoutMs)
                    System.currentTimeMillis() - started
                }
            } catch (error: Exception) {
                Log.d(TAG, "TCP $host:$port failed: ${error.javaClass.simpleName}")
                null
            }
        }

    /**
     * A real TLS handshake on a real socket, using the platform's default `SSLSocketFactory`
     * so the full validation chain runs: trust anchors, expiry and the certificate chain.
     *
     * HOSTNAME VERIFICATION IS SET EXPLICITLY. The platform defaults
     * `endpointIdentificationAlgorithm` to `"HTTPS"` for `HttpsURLConnection`, but NOT for a
     * raw `SSLSocket` — so leaving it unset here would silently accept a certificate issued
     * for a different name, which is precisely the mistake this whole feature exists to
     * catch. Setting it makes the JSSE raise `SSLPeerUnverifiedException` on a mismatch.
     *
     * That exception is caught rather than propagated because the TLS layer itself
     * SUCCEEDED — only the name did not match — and the user deserves the actual facts
     * ("issued for gmweb.example.com, you are connecting to 46.31.76.103") rather than a
     * generic failure. [TlsHealth.hostMatched] carries that verdict, and the probe turns a
     * false into a hard failure of the TLS stage, so no unverified connection is ever used
     * for real traffic.
     */
    override suspend fun tlsHandshake(host: String, port: Int, timeoutMs: Int): TlsHealth =
        withContext(Dispatchers.IO) {
            val socket = javax.net.ssl.SSLSocketFactory.getDefault().createSocket() as SSLSocket
            try {
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                socket.soTimeout = timeoutMs
                socket.sslParameters = socket.sslParameters.apply {
                    endpointIdentificationAlgorithm = "HTTPS"
                }
                var hostMatched = true
                try {
                    socket.startHandshake()
                } catch (_: javax.net.ssl.SSLPeerUnverifiedException) {
                    hostMatched = false
                }
                val session = socket.session
                val peer = session?.peerCertificates?.firstOrNull() as? X509Certificate
                TlsHealth(
                    valid = true,
                    protocol = session?.protocol,
                    issuer = peer?.issuerX500Principal?.name,
                    notAfter = peer?.notAfter?.time,
                    hostMatched = hostMatched,
                    lastCheckedAt = System.currentTimeMillis()
                )
            } finally {
                runCatching { socket.close() }
            }
        }

    /**
     * An unauthenticated reachability check.
     *
     * `/health` is an OPTIONAL server-side addition in the brief, so a 404 is a perfectly
     * good answer here: it proves the reverse proxy answered, which is what this stage is
     * for. Only a transport failure or a 5xx is a connectivity problem.
     */
    override suspend fun httpsGet(path: String, timeoutMs: Int): Int = withContext(Dispatchers.IO) {
        val base = prefs.gmwebUrl.trim().trimEnd('/')
        require(base.startsWith("https://")) { "control-plane URL must be HTTPS" }
        val connection = URL(base + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "AndroidGateway/${BuildConfig.APP_VERSION}")
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = false
            connection.responseCode
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    /**
     * The authenticated check, reusing the path [HeartbeatManager] already proved safe.
     *
     * An EMPTY events batch is a pure liveness ping: the server ingests it as
     * `{accepted:[],duplicates:0}` and touches no sequence, so nothing is enqueued and no
     * server state changes. It is authenticated exactly like every other agent call
     * (`X-API-Key` bootstrap plus a per-device `X-Agent-Auth` signature over the exact body
     * bytes), which is why a success is real proof that the key and the enrollment are still
     * accepted — and it needs no new endpoint on GMweb.
     *
     * If GMweb ever publishes `POST /api/v1/agent/ping`, this is the one place that has to
     * change.
     */
    override suspend fun authenticatedPing(timeoutMs: Int): AuthenticatedPingResult =
        withContext(Dispatchers.IO) {
            val base = prefs.gmwebUrl.trim().trimEnd('/')
            if (!base.startsWith("https://")) {
                return@withContext AuthenticatedPingResult(
                    ok = false,
                    detail = "control-plane URL is not HTTPS"
                )
            }
            val deviceId = prefs.agentDeviceId(appContext)
            val body = JSONObject()
                .put("appVersion", BuildConfig.APP_VERSION)
                .put("timestamp", System.currentTimeMillis())
                .put("diagnostic", true)
                .put("events", JSONArray())
                .put("sourceDeviceId", deviceId)
            val bodyBytes = body.toString().toByteArray(Charsets.UTF_8)
            val connection = URL(base + "/api/v1/agent/events/batch")
                .openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("User-Agent", "AndroidGateway/${BuildConfig.APP_VERSION}")
                if (prefs.apiKey.isNotBlank()) {
                    connection.setRequestProperty("X-API-Key", prefs.apiKey)
                }
                connection.setRequestProperty("X-Agent-Id", deviceId)
                connection.connectTimeout = timeoutMs
                connection.readTimeout = timeoutMs
                connection.doOutput = true
                // Fail closed: an unsigned request must never be sent, because a 401 caused
                // by our own missing signature would be reported as a rejected key.
                if (!AgentAuth.sign(connection, deviceId, "/api/v1/agent/events/batch", "POST", bodyBytes)) {
                    return@withContext AuthenticatedPingResult(
                        ok = false,
                        detail = "device signing unavailable (keystore)"
                    )
                }
                connection.outputStream.use { it.write(bodyBytes) }
                val status = connection.responseCode
                val text = if (status >= 400) {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                } else {
                    connection.inputStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                }
                val serverTime = runCatching { JSONObject(text).optLong("serverTime", 0L) }
                    .getOrDefault(0L)
                AuthenticatedPingResult(
                    ok = status in 200..299,
                    httpStatus = status,
                    clockSkewMs = serverTime
                        .takeIf { it > 0L }
                        ?.let { it - System.currentTimeMillis() },
                    detail = if (status in 200..299) "device enrolled" else "HTTP $status"
                )
            } finally {
                runCatching { connection.disconnect() }
            }
        }

    override fun pullBridgeHealth(): PullBridgeHealth =
        GatewayHealthRecorder.snapshot().pullBridge

    override fun now(): Long = System.currentTimeMillis()
}
