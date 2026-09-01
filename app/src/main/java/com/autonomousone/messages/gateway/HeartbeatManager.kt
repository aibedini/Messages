package com.autonomousone.messages.gateway

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.telephony.TelephonyManager
import android.util.Log
import com.autonomousone.messages.BuildConfig
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

        /** PR-11: empty events/batch POST = liveness ping on the control plane. */
        private const val HEARTBEAT_PATH = "/api/v1/agent/events/batch"
    }

    enum class ConnectionState { IDLE, CONNECTING, CONNECTED, DISCONNECTED, ERROR }

    private val _stateFlow = MutableStateFlow(ConnectionState.IDLE)
    val stateFlow: StateFlow<ConnectionState> = _stateFlow.asStateFlow()

    private var heartbeatJob: Job? = null
    private var backoffMs = INITIAL_BACKOFF_MS

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
        // v2.6.21 (PR-11): heartbeat targets the ADR-004 control plane liveness
        // probe instead of the retired /api/gateways/heartbeat. POSTing an
        // EMPTY batch is a pure liveness ping — eventStore.ingestBatch returns
        // {accepted:[],duplicates:0} without touching sequences, and the
        // request is authenticated exactly like the other agent calls
        // (X-API-Key bootstrap, then X-Agent-Auth per-device signatures).
        val payload = buildHeartbeatPayload().put(
            "events",
            org.json.JSONArray(),
        ).put("sourceDeviceId", deviceId)
        // GMweb requires X-Agent-Auth once the deviceId has enrolled; sign the
        // exact body about to be sent (fail closed when the Keystore is down).
        val sign: (java.net.HttpURLConnection, ByteArray) -> Boolean = { conn, bodyBytes ->
            AgentAuth.sign(conn, deviceId, HEARTBEAT_PATH, "POST", bodyBytes)
        }
        val result = client.post(
            HEARTBEAT_PATH,
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
                true
            }
            is BackendClient.Result.Failure -> {
                if (result.isAuthError) {
                    // Token rejected → re-enroll identity (fail-visible, the
                    // next successful register() restores the markers).
                    Log.w(TAG, "Heartbeat auth error — clearing credentials, will re-register")
                    onLog("🔄 Auth error — re-registering...")
                    prefs.clearCloudCredentials()
                    registrationManager.register()
                }
                false
            }
        }
    }

    private fun buildHeartbeatPayload(): JSONObject {
        return JSONObject().apply {
            put("appVersion", BuildConfig.APP_VERSION)
            put("batteryLevel", getBatteryLevel())
            put("networkType", getNetworkType())
            put("timestamp", System.currentTimeMillis())
        }
    }

    private fun getBatteryLevel(): Int {
        return try {
            val intent = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            )
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        } catch (e: Exception) {
            -1
        }
    }

    private fun getNetworkType(): String {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            when {
                tm == null -> "unknown"
                tm.dataState == TelephonyManager.DATA_CONNECTED -> "mobile"
                else -> "unknown"  // wifi detection via ConnectivityManager would need extra permission
            }
        } catch (e: Exception) {
            "unknown"
        }
    }
}
