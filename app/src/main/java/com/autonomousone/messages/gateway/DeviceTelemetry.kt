package com.autonomousone.messages.gateway

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.autonomousone.messages.data.MessagesDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Best-effort operational telemetry. It never gates sync, trust, or messaging. */
class DeviceTelemetry(
    context: Context,
    private val prefs: GatewayPreferences,
    private val client: ControlPlaneClient,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "DEVICE_TELEMETRY"
        private const val PATH = "/api/v1/agent/device-telemetry"
        private const val INTERVAL_MS = 60_000L

        internal fun batteryPercent(level: Int, scale: Int): Int =
            if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else -1
    }

    private val appContext = context.applicationContext
    private val startedAt = SystemClock.elapsedRealtime()
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                if (prefs.isEnabled && prefs.identityRegistered && prefs.gmwebUrl.isNotBlank()) {
                    runCatching { report() }.onFailure { Log.w(TAG, "report_failed", it) }
                }
                delay(INTERVAL_MS)
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    internal suspend fun report(): Boolean {
        val db = MessagesDatabase.get(appContext)
        val battery = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
        val networkType = when {
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WIFI"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "CELLULAR"
            else -> "UNKNOWN"
        }
        val eventDao = db.gatewayEventOutboxDao()
        val trustHealth = TrustStatementPublisher.health.value
        val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        @Suppress("DEPRECATION")
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }
        val deviceId = prefs.agentDeviceId(appContext)
        val payload = JSONObject()
            .put("deviceId", deviceId)
            .put("timestamp", System.currentTimeMillis())
            .put("battery", JSONObject()
                .put("level", batteryPercent(
                    battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1,
                    battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1,
                ))
                .put("isCharging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
                .put("chargingSource", when (plugged) {
                    BatteryManager.BATTERY_PLUGGED_AC -> "AC"
                    BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "WIRELESS"
                    else -> "NONE"
                }))
            .put("sync", JSONObject()
                .put("outboxDepth", eventDao.pendingDepth())
                .put("deadLetterCount", eventDao.deadLetterDepth())
                .put("trustOutboxDepth", trustHealth.pendingCount)
                .putOpt("lastTrustAckAt", trustHealth.lastAckAt)
                .putOpt("lastTrustHttpStatus", trustHealth.lastHttpStatus))
            .put("trust", JSONObject()
                .put("isEnrolled", prefs.identityRegistered)
                .put("approvedDevicesCount", db.trustedDeviceDao().countTrusted())
                .put("trustSequence", db.trustStatementOutboxDao().maxTrustSequence()))
            .put("network", JSONObject()
                .put("isConnected", capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
                .put("networkType", networkType))
            .put("app", JSONObject()
                .put("versionName", packageInfo.versionName ?: "")
                .put("versionCode", versionCode)
                .put("uptimeMs", SystemClock.elapsedRealtime() - startedAt))
            .put("device", JSONObject()
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("androidVersion", Build.VERSION.RELEASE)
                .put("sdkInt", Build.VERSION.SDK_INT))
        val result = client.post(PATH, payload) { conn, bytes ->
            AgentAuth.sign(conn, deviceId, PATH, "POST", bytes)
        }
        val ok = result is ControlPlaneClient.Result.Success
        Log.i(TAG, "report status=${if (ok) "stored" else "failed"} outbox=${eventDao.pendingDepth()} trust=${trustHealth.pendingCount}")
        return ok
    }
}
