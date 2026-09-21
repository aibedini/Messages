package com.autonomousone.messages.gateway

import android.content.Context
import android.util.Log
import com.autonomousone.messages.BuildConfig
import com.autonomousone.messages.gateway.health.AuthHealth
import com.autonomousone.messages.gateway.health.AuthVerification
import com.autonomousone.messages.gateway.health.GatewayFailureKind
import com.autonomousone.messages.gateway.health.GatewayHealthRecorder
import com.autonomousone.messages.gateway.health.GatewayHealthText
import com.autonomousone.messages.utils.DiagnosticLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Manages the periodic heartbeat to the cloud backend.
 *
 * - Sends heartbeat every [HEARTBEAT_INTERVAL_MS] when healthy.
 * - Uses exponential backoff (1s → 2s → 4s … max 5 min) on failure.
 * - Detects 401/403 → triggers re-registration automatically.
 * - State exposed via [stateFlow] for UI binding.
 */
class HeartbeatManager(
    private val context: Context,
    private val prefs: GatewayPreferences,
    private val client: BackendClient,
    private val registrationManager: RegistrationManager,
    private val scope: CoroutineScope,
    private val onLog: (String) -> Unit = {},
) {

    companion object {
        private const val TAG = "HEARTBEAT_MGR"
        private const val HEARTBEAT_INTERVAL_MS = 60_000L     // 60 seconds
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 5 * 60_000L        // 5 minutes
    }

    enum class ConnectionState { IDLE, CONNECTING, CONNECTED, DISCONNECTED, ERROR }

    private val _stateFlow = MutableStateFlow(ConnectionState.IDLE)
    val stateFlow: StateFlow<ConnectionState> = _stateFlow.asStateFlow()

    private var heartbeatJob: Job? = null
    private var backoffMs = INITIAL_BACKOFF_MS

    /** One place for the battery/network facts a liveness request carries. */
    private val deviceFacts = AgentDeviceFacts(context)

    /** Woken by retryNow() to cut short a pending backoff sleep. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /**
     * Cancel any in-progress backoff and tick NOW — called by
     * ConnectionSupervisor the moment the network is validated online again.
     * Without this a 5-second WiFi re-association still waited out the
     * exponential ladder (up to 5 minutes) before the next heartbeat attempt.
     */
    fun retryNow() {
        backoffMs = INITIAL_BACKOFF_MS
        wake.trySend(Unit)
    }

    fun start() {
        if (heartbeatJob?.isActive == true) return

        heartbeatJob = scope.launch {
            _stateFlow.value = ConnectionState.CONNECTING
            Log.d(TAG, "Heartbeat loop started")

            while (isActive) {
                if (!GatewayAccessPolicy.canTransmit(prefs.hasGatewayConsent, prefs.isEnabled)) {
                    _stateFlow.value = ConnectionState.IDLE
                    Log.d(TAG, "Heartbeat stopped because gateway consent is absent or gateway is disabled")
                    break
                }
                val success = sendHeartbeat()

                if (success) {
                    backoffMs = INITIAL_BACKOFF_MS   // Reset backoff on success
                    _stateFlow.value = ConnectionState.CONNECTED
                    // Interruptible: retryNow() must even shorten the normal
                    // 60 s interval after a manual reconnect press.
                    withTimeoutOrNull(HEARTBEAT_INTERVAL_MS) { wake.receive() }
                } else {
                    _stateFlow.value = ConnectionState.DISCONNECTED
                    Log.d(TAG, "Heartbeat failed, retrying in ${backoffMs}ms")
                    // The backoff sleep is cancellable by retryNow(): when the
                    // supervisor reports the network valid again, tick NOW.
                    withTimeoutOrNull(backoffMs) { wake.receive() }
                    // Exponential backoff: double each failure, cap at MAX_BACKOFF_MS
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                }
            }
        }
    }

    fun stop() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        _stateFlow.value = ConnectionState.IDLE
        Log.d(TAG, "Heartbeat loop stopped")
    }

    private suspend fun sendHeartbeat(): Boolean {
        if (!GatewayAccessPolicy.canTransmit(prefs.hasGatewayConsent, prefs.isEnabled)) return false
        if (!prefs.isRegistered || (prefs.gatewayToken.isBlank() && !prefs.identityRegistered)) {
            Log.w(TAG, "Not registered — attempting registration before heartbeat")
            val registered = registrationManager.ensureRegistered()
            if (!registered) return false
        }

        val deviceId = prefs.agentDeviceId(context)
        // v3.4.7: the body comes from the SHARED contract. The diagnostic probe used to build
        // its own — with an extra field and two missing ones — and collected a 400 that it then
        // reported as a rejected device key. One builder, one contract, no drift.
        val payload = AgentLivenessRequest.body(
            appVersion = BuildConfig.APP_VERSION,
            batteryLevel = deviceFacts.batteryLevel(),
            networkType = deviceFacts.networkType(),
            timestamp = System.currentTimeMillis(),
            sourceDeviceId = deviceId
        )
        // GMweb requires X-Agent-Auth once the deviceId has enrolled; sign the
        // exact body about to be sent (fail closed when the Keystore is down).
        val sign: (java.net.HttpURLConnection, ByteArray) -> Boolean = { conn, bodyBytes ->
            AgentAuth.sign(conn, deviceId, AgentLivenessRequest.EVENTS_PATH, "POST", bodyBytes)
        }
        val result = client.post(
            AgentLivenessRequest.EVENTS_PATH,
            payload,
            authenticated = false,
            extraHeaders = mapOf(
                "X-API-Key" to prefs.apiKey,
                "X-Agent-Id" to deviceId,
            ),
            signer = sign,
        )

        return when (result) {
            is BackendClient.Result.Success -> {
                prefs.lastHeartbeatAt = System.currentTimeMillis()
                onLog("💓 Heartbeat OK")
                // ── v3.4.x P0: this IS the AUTH dimension's producer ─────────────
                // The heartbeat is a PURE liveness ping: an empty events batch, which the
                // server ingests as `{accepted:[],duplicates:0}` without touching
                // sequences. It is authenticated exactly like every other agent call
                // (X-API-Key + X-Agent-Id + a per-device X-Agent-Auth signature), so a
                // success here is real proof that the secret and the enrollment are still
                // accepted — no new server endpoint required, and nothing is enqueued.
                GatewayHealthRecorder.onAuthProbe(
                    AuthHealth(
                        status = AuthVerification.VERIFIED,
                        lastVerifiedAt = System.currentTimeMillis()
                    )
                )
                true
            }
            is BackendClient.Result.Failure -> {
                if (result.isAuthError) {
                    // Token rejected → re-enroll identity (fail-visible, the
                    // next successful register() restores the markers).
                    Log.w(TAG, "Heartbeat auth error — clearing credentials, will re-register")
                    onLog("🔄 Auth error — re-registering...")
                    // ONLY a real 401/403 may claim the credential was rejected.
                    GatewayHealthRecorder.onAuthProbe(
                        AuthHealth(
                            status = AuthVerification.REJECTED,
                            lastVerifiedAt = System.currentTimeMillis()
                        )
                    )
                    DiagnosticLog.event(
                        "GATEWAY_AUTH",
                        "rejected status=${result.httpStatus ?: "n/a"} — re-enrolling identity"
                    )
                    prefs.clearCloudCredentials()
                    registrationManager.register()
                } else {
                    // A non-auth failure (400, 5xx, timeout) proves NOTHING about the key. It
                    // is recorded as UNVERIFIABLE — not as a rejection, and not as a silent
                    // no-op. A 400 in particular means the request itself was refused, which is
                    // the app's contract problem, and reporting it as a rejected credential is
                    // the false alarm this tri-state exists to prevent.
                    val kind = GatewayFailureKind.classify(httpStatus = result.httpStatus)
                    GatewayHealthRecorder.onAuthProbe(
                        AuthHealth(
                            status = AuthVerification.UNVERIFIABLE,
                            lastVerifiedAt = System.currentTimeMillis(),
                            unverifiableReason = GatewayHealthText.safeDetail(
                                result.httpStatus?.let { "HTTP $it (${kind.name})" } ?: kind.name
                            )
                        )
                    )
                    DiagnosticLog.event(
                        "GATEWAY_AUTH",
                        "unverified status=${result.httpStatus ?: "n/a"} kind=${kind.name} " +
                            "detail=${GatewayHealthText.safeDetail(result.error) ?: "none"}"
                    )
                }
                false
            }
        }
    }

    // NOTE: the payload builder and the battery/network lookups used to live here. They now
    // live in [AgentLivenessRequest] and [AgentDeviceFacts] so the diagnostic probe sends the
    // SAME request this heartbeat sends. Keeping a private copy here is what allowed the two to
    // diverge and produced an HTTP 400 that was misreported as a rejected device key.
}
