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
import com.autonomousone.messages.messaging.SimDiscovery
import com.autonomousone.messages.messaging.SimDiscoveryResult
import com.autonomousone.messages.messaging.SimManager
import com.autonomousone.messages.sms.SmsSendPreflight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicReference

/**
 * Best-effort operational telemetry — now event-driven, not only periodic.
 *
 * **What was wrong.** This class was a loop that reported once and then slept 60 seconds, and its
 * result was discarded. So:
 *
 *  - GMweb's SIM list could be a minute stale, and a SIM inserted, ejected or re-defaulted in that
 *    minute was reported as "not reported" or as the previous state;
 *  - a user granting Phone permission waited up to a minute for the SIMs to appear at all;
 *  - a reconnect did not re-announce the phone, so GMweb's "last seen" lagged behind reality;
 *  - a failing report was invisible: the returned boolean went nowhere.
 *
 * The timer remains — a heartbeat is the only thing that proves a phone which changed nothing is
 * still alive — but it is no longer the only trigger. Every trigger is a moment GMweb's picture
 * becomes wrong: process start, network back, gateway connected, subscriptions changed, permission
 * granted, default SMS line changed, app updated, or a manual diagnostic refresh.
 *
 * Two properties make that safe to do often:
 *
 *  1. [TelemetryWakeState] guarantees at most ONE report in flight and at most ONE queued behind it,
 *     so a burst of SIM changes is one extra POST, never five overlapping ones;
 *  2. a short debounce lets a burst (an eSIM toggle fires several subscription callbacks) collapse
 *     into a single report that already reflects the settled state.
 *
 * It still never gates sync, trust or messaging: every failure is recorded in [TelemetryHealth] and
 * swallowed.
 */
class DeviceTelemetry(
    context: Context,
    private val prefs: GatewayPreferences,
    private val client: ControlPlaneClient,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "DEVICE_TELEMETRY"
        private const val PATH = "/api/v1/agent/device-telemetry"

        /** The heartbeat interval. The floor on how stale GMweb's picture may get when nothing happens. */
        internal const val INTERVAL_MS = 60_000L

        /**
         * How long a burst of triggers is allowed to coalesce before the report is sent.
         *
         * Long enough for an eSIM toggle's several callbacks to settle, short enough that a user who
         * just granted Phone permission sees the SIMs almost immediately.
         */
        internal const val DEBOUNCE_MS = 750L

        /** The live instance, if the gateway service exists. Written only on its own thread. */
        private val instance = AtomicReference<DeviceTelemetry?>(null)

        /**
         * Ask the running telemetry loop to report now, for [reason].
         *
         * Safe from any thread, and a NO-OP when the gateway service is not running: a trigger must
         * never start a reporter of its own, because that is how a second loop gets created. When the
         * service is down, nothing is being reported anyway — and the next start reports immediately
         * as [TelemetryTrigger.STARTUP].
         */
        fun requestImmediate(reason: TelemetryTrigger) {
            val live = instance.get() ?: run {
                // Recorded even when nothing is running, so diagnostics can say "asked, but the
                // gateway was down" instead of showing a request that seems to have vanished.
                TelemetryHealth.onSkipped(reason)
                return
            }
            live.requestImmediate(reason)
        }

        internal fun batteryPercent(level: Int, scale: Int): Int =
            if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else -1

        /** Subscription labels can be user supplied; never relay a phone-like value. */
        internal fun safeSimLabel(value: String): String = value
            .take(64)
            .replace(Regex("[\\r\\n\\t]"), " ")
            .replace(Regex("(?U)\\+?\\d[\\d\\s()\\-]{6,}"), "SIM")
            .trim()
    }

    private val appContext = context.applicationContext
    private val startedAt = SystemClock.elapsedRealtime()
    private var job: Job? = null

    /** Conflated: five triggers while busy are one pending wake-up, which is the intent. */
    private val wake = Channel<TelemetryTrigger>(Channel.CONFLATED)
    private val wakeState = TelemetryWakeState()

    /** The trigger that caused the newest request; reported in the payload. */
    @Volatile
    private var pendingTrigger: TelemetryTrigger = TelemetryTrigger.PERIODIC

    /** The last eligibility logged, so the line appears on CHANGE rather than every iteration. */
    @Volatile
    private var lastLoggedEligibility: TelemetryEligibility? = null

    fun start(initialTrigger: TelemetryTrigger = TelemetryTrigger.STARTUP) {
        if (job?.isActive == true) return
        instance.set(this)
        TelemetryHealth.setRunning(true)
        // BOUNDARY 1+2 (P0 instrumentation): the instance exists and the loop is starting. Without
        // this line, "telemetry never ran" and "telemetry ran and was blocked" look identical in a
        // logcat that no longer exists after the process died — so it goes to the DURABLE diagnostic
        // log as well.
        Log.i(TAG, "TELEMETRY_INSTANCE_CREATED agent=${shortId(prefs.agentDeviceId(appContext))}")
        trace("instance_created trigger=${initialTrigger.wireValue}")
        job = scope.launch {
            trace("start trigger=${initialTrigger.wireValue} pid=${android.os.Process.myPid()}")
            logEligibility(force = true)
            // The FIRST report is immediate: a phone that has just brought its gateway up (or just
            // been updated) must not wait a minute to become visible.
            pendingTrigger = initialTrigger
            var nextPeriodicAt = 0L
            var attempt = 0L
            while (isActive) {
                val eligibility = eligible()
                val due = pendingTrigger != TelemetryTrigger.PERIODIC ||
                    System.currentTimeMillis() >= nextPeriodicAt
                if (eligibility.eligible && due && wakeState.beginRun()) {
                    val trigger = pendingTrigger
                    pendingTrigger = TelemetryTrigger.PERIODIC
                    attempt++
                    TelemetryHealth.onAttempt(trigger)
                    // BOUNDARY 3: why this report exists, and for which device. IDs are shortened;
                    // no secret, no path, no body.
                    Log.i(TAG, "TELEMETRY_REPORT_BEGIN trigger=${trigger.wireValue} attempt=$attempt " +
                        "agent=${shortId(prefs.agentDeviceId(appContext))}")
                    trace("report_begin trigger=${trigger.wireValue} attempt=$attempt")
                    runCatching { report(trigger) }
                        .onFailure {
                            // A report that could not even be attempted is still a failed attempt;
                            // the registry must never show a silent gap.
                            TelemetryHealth.onFailure(errorCode = TelemetryHealth.ErrorCode.TRANSPORT)
                            Log.w(TAG, "TELEMETRY_REPORT_END result=threw", it)
                            trace("report_end result=threw error=${it.javaClass.simpleName}")
                        }
                    nextPeriodicAt = System.currentTimeMillis() + INTERVAL_MS
                    if (wakeState.endRun()) {
                        // A trigger arrived while this report ran: ONE immediate extra pass, so the
                        // newest state is what GMweb ends up holding. Deliberately not a loop —
                        // endRun() collapses any number of triggers into this single pass.
                        delay(DEBOUNCE_MS)
                    }
                    continue
                }
                if (!eligibility.eligible && due) {
                    // BOUNDARY 4 (the one that was missing): not eligible, and WHY. A device that is
                    // switched off is not misconfigured, and a device with no server is not
                    // de-registered — the reason is the whole point of this branch.
                    logEligibility(force = false, eligibility = eligibility)
                    pendingTrigger = TelemetryTrigger.PERIODIC
                    nextPeriodicAt = System.currentTimeMillis() + INTERVAL_MS
                }
                // Idle: wait for a trigger, or until the periodic heartbeat is due.
                val waitMs = (nextPeriodicAt - System.currentTimeMillis()).coerceAtLeast(0L)
                val signalled = kotlinx.coroutines.withTimeoutOrNull(waitMs) { wake.receive() } != null
                if (signalled) {
                    // Let a burst settle (an eSIM toggle fires several callbacks) so the report
                    // carries the SETTLED state; the conflated channel keeps only the newest wake-up,
                    // and pendingTrigger already holds the newest reason.
                    delay(DEBOUNCE_MS)
                    while (wake.tryReceive().isSuccess) { /* conflated: nothing further to drain */ }
                }
            }
        }
        if (job == null) instance.compareAndSet(this, null)
    }

    /**
     * Record and log the eligibility, loudly on a CHANGE.
     *
     * `eligible()` used to be a private Boolean expression whose answer nobody could see; a device
     * that spent a week ineligible produced zero log lines and zero requests. Logging on change (not
     * every iteration) keeps the log readable while making the transition — "the gateway came up, so
     * telemetry became eligible" — impossible to miss.
     */
    private fun logEligibility(force: Boolean, eligibility: TelemetryEligibility = eligible()) {
        TelemetryEligibilityState.record(eligibility)
        val previous = lastLoggedEligibility
        if (!force && previous == eligibility) return
        lastLoggedEligibility = eligibility
        val line = "TELEMETRY_ELIGIBILITY eligible=${eligibility.eligible} " +
            "reason=${eligibility.reason ?: "none"} " +
            "isEnabled=${prefs.isEnabled} identity=${prefs.identityRegistered} " +
            "origin=${if (prefs.gmwebServerOrigin.isBlank()) "absent" else "present"} " +
            "host=${safeHost()}"
        Log.i(TAG, line)
        trace("eligibility eligible=${eligibility.eligible} reason=${eligibility.reason ?: "none"} " +
            "isEnabled=${prefs.isEnabled} identity=${prefs.identityRegistered} " +
            "origin=${if (prefs.gmwebServerOrigin.isBlank()) "absent" else "present"} host=${safeHost()}")
    }

    private fun trace(message: String) {
        runCatching {
            com.autonomousone.messages.utils.DiagnosticLog.event("GM_TELEMETRY", message)
        }
    }

    /** The origin's host only — never a path, query or credential. */
    private fun safeHost(): String = runCatching {
        java.net.URI(prefs.gmwebServerOrigin).host ?: "unknown"
    }.getOrDefault("unparsable")

    private fun shortId(value: String): String = value.take(8)

    fun stop() {
        job?.cancel()
        job = null
        instance.compareAndSet(this, null)
        TelemetryHealth.setRunning(false)
        trace("stop")
    }

    /**
     * Report now, for [reason].
     *
     * [TelemetryWakeState] decides whether this starts a run or queues exactly one behind the run in
     * progress — the caller is never told to wait, and never spawns a loop of its own.
     */
    fun requestImmediate(reason: TelemetryTrigger) {
        // The newest reason is the one reported; the signal itself only has to wake the loop.
        pendingTrigger = reason
        wakeState.markTriggered()
        wake.trySend(reason)
    }

    private fun eligible(): TelemetryEligibility = TelemetryEligibility.evaluate(
        isEnabled = prefs.isEnabled,
        identityRegistered = prefs.identityRegistered,
        serverOrigin = prefs.gmwebServerOrigin
    )


    internal suspend fun report(trigger: TelemetryTrigger = TelemetryTrigger.PERIODIC): Boolean {
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
        val historyAckLag = db.cloudHistoryCheckpointDao().all().sumOf {
            (it.nextOrdinal - 1 - it.ackedContiguousOrdinal).coerceAtLeast(0)
        }
        val trustHealth = TrustStatementPublisher.health.value
        val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        @Suppress("DEPRECATION")
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }
        val deviceId = prefs.agentDeviceId(appContext)

        // ── SIMs: three different answers, never collapsed into "no SIM" ──────
        val simManager = SimManager(appContext)
        val discovery = simManager.discover()
        val permissionGranted = simManager.hasReadPhoneState()
        val sims = (discovery as? SimDiscoveryResult.Available)?.sims.orEmpty()
        val discoveryReason = SimDiscovery.reasonOf(discovery)
        TelemetryHealth.onSubscriptionDiscovery(
            count = (discovery as? SimDiscoveryResult.Available)?.sims?.size,
            reason = discoveryReason
        )
        val activeSims = JSONArray()
        sims.forEach { sim ->
            activeSims.put(JSONObject()
                .put("subscriptionId", sim.subscriptionId)
                .put("slotIndex", sim.slotIndex)
                .put("displayName", safeSimLabel(sim.displayName))
                .put("carrierName", safeSimLabel(sim.carrierName))
                .put("isDefaultSms", sim.isSystemDefault)
                .put("isActive", true))
        }
        val defaultSmsSubscriptionId = simManager.defaultSmsSubscriptionId()
        val telemetryHealth = TelemetryHealth.snapshot()

        val payload = JSONObject()
            .put("deviceId", deviceId)
            .put("timestamp", System.currentTimeMillis())
            // Why this report exists. Additive: a consumer that ignores it still sees a heartbeat.
            .put("trigger", trigger.wireValue)
            .put("smsSubscriptions", JSONObject()
                .put("available", discovery is SimDiscoveryResult.Available)
                // Additive detail, so "permission missing" and "no SIM" stop looking identical.
                .putOpt("reason", discoveryReason)
                .put("permissionGranted", permissionGranted)
                .put("defaultSubscriptionId", defaultSmsSubscriptionId)
                .putOpt("lastChangedAt", telemetryHealth.lastSubscriptionChangeAt)
                .put("items", activeSims))
            // The permissions that decide what this phone can be asked to do, reported separately:
            // being able to enumerate SIMs says nothing about being able to send.
            .put("permissions", JSONObject()
                .put("readPhoneState", permissionGranted)
                .put("sendSms", SmsSendPreflight.hasSendSmsPermission(appContext))
                .put("defaultSmsRole", DefaultSmsRole.isHeld(appContext)))
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
                .put("realtimeQueueDepth", eventDao.pendingRealtimeDepth())
                .put("backfillQueueDepth", eventDao.pendingBackfillDepth())
                .put("deadLetterCount", eventDao.deadLetterDepth())
                .put("historyAckLag", historyAckLag)
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
        val result = client.post(
            path = PATH,
            body = payload,
            signer = { conn, bytes ->
                // BOUNDARY 5+6: the exact bytes signed == the exact bytes written (the signer receives
                // the same array the client then writes). Logged by the client around this call.
                AgentAuth.sign(conn, deviceId, PATH, "POST", bytes)
            },
            traceTag = "GM_TELEMETRY"
        )
        val now = System.currentTimeMillis()
        val ok = result is ControlPlaneClient.Result.Success
        if (ok) {
            TelemetryHealth.onSuccess(
                at = now,
                httpStatus = (result as ControlPlaneClient.Result.Success).httpStatus
            )
        } else {
            val failure = result as? ControlPlaneClient.Result.Failure
            TelemetryHealth.onFailure(at = now, httpStatus = failure?.httpStatus)
        }
        // BOUNDARY 7: the end of the attempt, with the outcome. Together with the lines above this
        // makes "the report was never attempted" distinguishable from "it was attempted and failed"
        // from "it succeeded" — the distinction the last seven days could not make.
        Log.i(
            TAG,
            "TELEMETRY_REPORT_END trigger=${trigger.wireValue} " +
                "result=${if (ok) "success" else "failure"} " +
                "http=${(result as? ControlPlaneClient.Result.Success)?.httpStatus ?: (result as? ControlPlaneClient.Result.Failure)?.httpStatus ?: "none"} " +
                "sims=${sims.size} reason=${discoveryReason ?: "none"} outbox=${eventDao.pendingDepth()}"
        )
        trace(
            "report_end trigger=${trigger.wireValue} result=${if (ok) "success" else "failure"} " +
                "http=${(result as? ControlPlaneClient.Result.Success)?.httpStatus ?: (result as? ControlPlaneClient.Result.Failure)?.httpStatus ?: "none"} " +
                "sims=${sims.size} simReason=${discoveryReason ?: "none"} " +
                "outbox=${eventDao.pendingDepth()} realtime=${eventDao.pendingRealtimeDepth()} " +
                "backfill=${eventDao.pendingBackfillDepth()} dead=${eventDao.deadLetterDepth()}"
        )
        return ok
    }
}
