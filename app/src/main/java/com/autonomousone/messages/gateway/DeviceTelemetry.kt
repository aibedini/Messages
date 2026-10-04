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
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/**
 * Operational telemetry — the phone's presence and SIM state, reported to GMweb.
 *
 * **What this class is now.** One loop, one reporter, one POST at a time:
 *
 *  - a 60-second heartbeat PLUS nine event triggers (so a phone that changed nothing still proves it
 *    is alive, and a phone that just changed its SIM does not wait a minute to say so);
 *  - every attempt — heartbeat, lifecycle trigger or web-requested refresh — runs through
 *    [TelemetryReporter], which holds the single report mutex and returns a STRUCTURED result
 *    (`Success(2xx)` / `Failure(code)`) instead of a Boolean;
 *  - [requestImmediateAndAwait] is the awaitable door a remote command uses, so a browser-requested
 *    refresh can only be ACKed after a real 2xx.
 *
 * **What went wrong before.** A week of production produced ZERO
 * `POST /api/v1/agent/device-telemetry` requests while the event upload and pull bridge returned 200,
 * and the app could not say why: the gate was a Boolean over three prefs, every outcome was collapsed
 * to true/false, and a signing failure aborted before the socket with no record anywhere. The gate is
 * still enforced (consent, origin and identity are required); it is now NAMED, every stage is
 * recorded durably, and success means 2xx and nothing weaker.
 *
 * It still never gates sync, trust or messaging: a telemetry failure is recorded, never thrown into
 * another component.
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

        /** The heartbeat interval: the floor on how stale GMweb's picture may get when nothing happens. */
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

        /** True when this build can serve a web-requested telemetry refresh. */
        const val REMOTE_REFRESH_SUPPORTED = true

        /**
         * Ask the running telemetry loop to report now, for [reason].
         *
         * Safe from any thread, and a NO-OP when the gateway service is not running: a trigger must
         * never start a reporter of its own, because that is how a second loop gets created. Use
         * [requestImmediateAndAwait] when the caller needs the actual outcome.
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

        /**
         * Report now and WAIT for the outcome — the door the remote refresh command uses.
         *
         * Every failure is a structured code, never an exception and never a silent success. The
         * report itself runs in the service scope, so the timeout below abandons only the WAIT: the
         * attempt finishes, releases the report mutex and updates health, and no second POST can
         * start beside it.
         */
        suspend fun requestImmediateAndAwait(
            reason: TelemetryTrigger,
            timeoutMs: Long = 10_000L
        ): TelemetryReportResult {
            val live = instance.get() ?: return TelemetryReportResult.Failure(
                code = TelemetryFailureCode.NOT_RUNNING
            )
            return live.reportAndAwait(reason, timeoutMs)
        }

        internal fun batteryPercent(level: Int, scale: Int): Int =
            if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else -1

        /**
         * Subscription labels can be user supplied; never relay a phone-like value.
         *
         * Deliberately regex-free. Android's ICU regex engine on API 35 rejects Java's inline
         * `(?U)` flag, and the old sanitizer therefore threw PatternSyntaxException while building
         * every telemetry payload that contained a SIM. This scanner handles both ASCII and
         * non-ASCII decimal digits via Char.isDigit() and cannot fail during payload construction.
         */
        internal fun safeSimLabel(value: String): String {
            val flat = value.take(64).replace('\r', ' ').replace('\n', ' ').replace('\t', ' ')
            val out = StringBuilder(flat.length)
            var i = 0
            while (i < flat.length) {
                val first = flat[i]
                if (first == '+' || first.isDigit()) {
                    var j = i
                    var digits = 0
                    if (flat[j] == '+') j++
                    while (j < flat.length) {
                        val ch = flat[j]
                        when {
                            ch.isDigit() -> {
                                digits++
                                j++
                            }
                            ch == ' ' || ch == '(' || ch == ')' || ch == '-' -> j++
                            else -> break
                        }
                    }
                    if (digits >= 7) {
                        out.append("SIM")
                        i = j
                        continue
                    }
                }
                out.append(first)
                i++
            }
            return out.toString().trim()
        }
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

    /**
     * The single reporter. `perform` is the ONLY thing that opens a telemetry socket, and it is
     * serialized inside — the heartbeat, the lifecycle triggers and a remote refresh all contend for
     * the same mutex, so two POSTs can never overlap.
     */
    private val reporter = TelemetryReporter(
        eligibility = { eligible() },
        deviceId = { prefs.agentDeviceId(appContext) },
        payload = { trigger -> buildPayload(trigger) },
        transport = { body, deviceId -> postViaControlPlane(body, deviceId) }
    )

    fun start(initialTrigger: TelemetryTrigger = TelemetryTrigger.STARTUP) {
        if (job?.isActive == true) return
        instance.set(this)
        TelemetryHealth.setRunning(true)
        // BOUNDARY 1+2: the instance exists and the loop is starting, in the DURABLE log as well as
        // logcat — logcat is gone after a process death, and "telemetry never ran" must stay
        // distinguishable from "telemetry ran and was blocked".
        Log.i(TAG, "TELEMETRY_INSTANCE_CREATED agent=${shortId(prefs.agentDeviceId(appContext))}")
        trace("instance_created trigger=${initialTrigger.wireValue}")
        job = scope.launch {
            trace("start trigger=${initialTrigger.wireValue} pid=${android.os.Process.myPid()}")
            logEligibility(force = true)
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
                    // BOUNDARY 3: why this report exists, and for which device. IDs are shortened; no
                    // secret, no path, no body. NOTE: the attempt COUNTER is recorded inside
                    // performReport, once, for every path — recording it here as well would double
                    // count the heartbeat.
                    Log.i(
                        TAG,
                        "TELEMETRY_REPORT_BEGIN trigger=${trigger.wireValue} attempt=$attempt " +
                            "agent=${shortId(prefs.agentDeviceId(appContext))}"
                    )
                    trace("report_begin trigger=${trigger.wireValue} attempt=$attempt")
                    performReport(trigger)
                    nextPeriodicAt = System.currentTimeMillis() + INTERVAL_MS
                    if (wakeState.endRun()) {
                        // A trigger arrived while this report ran: ONE immediate extra pass, so the
                        // newest state is what GMweb ends up holding. endRun() collapses any number of
                        // triggers into this single pass.
                        delay(DEBOUNCE_MS)
                    }
                    continue
                }
                if (!eligibility.eligible && due) {
                    // BOUNDARY 4 (the one that was missing): not eligible, and WHY.
                    logEligibility(force = false, eligibility = eligibility)
                    TelemetryHealth.onFailure(
                        errorCode = TelemetryFailureCode
                            .forEligibility(eligibility.reason)?.name ?: TelemetryFailureCode.NOT_RUNNING.name
                    )
                    pendingTrigger = TelemetryTrigger.PERIODIC
                    nextPeriodicAt = System.currentTimeMillis() + INTERVAL_MS
                }
                // Idle: wait for a trigger, or until the periodic heartbeat is due.
                val waitMs = (nextPeriodicAt - System.currentTimeMillis()).coerceAtLeast(0L)
                val signalled = withTimeoutOrNull(waitMs) { wake.receive() } != null
                if (signalled) {
                    // Let a burst settle (an eSIM toggle fires several callbacks) so the report
                    // carries the SETTLED state.
                    delay(DEBOUNCE_MS)
                    while (wake.tryReceive().isSuccess) { /* conflated: nothing further to drain */ }
                }
            }
        }
    }

    /**
     * Record and log the eligibility, loudly on a CHANGE.
     *
     * `eligible()` used to be a private Boolean whose answer nobody could see; a device that spent a
     * week ineligible produced zero log lines and zero requests.
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
        trace(
            "eligibility eligible=${eligibility.eligible} reason=${eligibility.reason ?: "none"} " +
                "isEnabled=${prefs.isEnabled} identity=${prefs.identityRegistered} " +
                "origin=${if (prefs.gmwebServerOrigin.isBlank()) "absent" else "present"} host=${safeHost()}"
        )
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
     * Report now, for [reason] — fire and forget.
     *
     * [TelemetryWakeState] decides whether this starts a run or queues exactly one behind the run in
     * progress. Callers that need the outcome use [requestImmediateAndAwait].
     */
    fun requestImmediate(reason: TelemetryTrigger) {
        pendingTrigger = reason
        wakeState.markTriggered()
        wake.trySend(reason)
    }

    /**
     * One awaited attempt, run in the SERVICE scope.
     *
     * The timeout bounds only the wait. The attempt itself belongs to [scope], so it is not cancelled
     * mid-socket: it finishes, releases the report mutex and updates health. A refresh that reported
     * `TIMEOUT` therefore cannot overlap a later one.
     */
    internal suspend fun reportAndAwait(
        trigger: TelemetryTrigger,
        timeoutMs: Long
    ): TelemetryReportResult {
        val pending = scope.async { performReport(trigger) }
        return withTimeoutOrNull(timeoutMs) { pending.await() }
            ?: TelemetryReportResult.Failure(code = TelemetryFailureCode.TIMEOUT)
    }

    /**
     * Perform exactly one report: gate, build, POST, record. Never throws.
     *
     * This is the ONE path a telemetry socket is opened on, so the concurrency rule ("at most one
     * POST in flight") is a property of the design rather than a convention.
     */
    internal suspend fun performReport(trigger: TelemetryTrigger): TelemetryReportResult {
        // ATTEMPT RECORDED FIRST, exactly once, before gating/payload/signing/HTTP.
        //
        // This is what the real device exposed: failures reached 10 while attempts stayed 0 and
        // "last attempt" showed "never", because the periodic loop recorded the attempt and the
        // awaited (manual / remote) path never did. The counter is now owned by the ONE place every
        // report passes through, so `attempts == successes + failures` holds for completed reports
        // and "last attempt" can never be older than "last failure".
        TelemetryHealth.onAttempt(trigger)
        val result = reporter.perform(trigger)
        val now = System.currentTimeMillis()
        when (result) {
            is TelemetryReportResult.Success -> TelemetryHealth.onSuccess(
                at = result.completedAt,
                httpStatus = result.httpStatus
            )
            is TelemetryReportResult.Failure -> TelemetryHealth.onFailure(
                at = now,
                httpStatus = result.httpStatus,
                errorCode = result.code.name,
                stage = result.code.stage,
                detail = result.detail
            )
        }
        if (trigger == TelemetryTrigger.REMOTE_REFRESH) {
            TelemetryHealth.onRemoteRefresh(
                at = now,
                succeeded = result.succeeded,
                resultCode = when (result) {
                    is TelemetryReportResult.Success -> "success"
                    is TelemetryReportResult.Failure -> result.code.commandCode
                }
            )
        }
        val statusText = when (result) {
            is TelemetryReportResult.Success -> result.httpStatus.toString()
            is TelemetryReportResult.Failure -> result.httpStatus?.toString() ?: "none"
        }
        val codeText = (result as? TelemetryReportResult.Failure)?.code?.name ?: "none"
        // BOUNDARY 7: the end of the attempt, with the outcome — which together with the lines above
        // makes "never attempted" distinguishable from "attempted and failed" from "succeeded".
        Log.i(
            TAG,
            "TELEMETRY_REPORT_END trigger=${trigger.wireValue} " +
                "result=${if (result.succeeded) "success" else "failure"} " +
                "http=$statusText code=$codeText"
        )
        trace(
            "report_end trigger=${trigger.wireValue} " +
                "result=${if (result.succeeded) "success" else "failure"} " +
                "http=$statusText code=$codeText " +
                "sims=${TelemetryHealth.snapshot().lastSubscriptionCount ?: "unknown"} " +
                "simReason=${TelemetryHealth.snapshot().lastSubscriptionReason ?: "none"}"
        )
        return result
    }

    private fun eligible(): TelemetryEligibility = TelemetryEligibility.evaluate(
        isEnabled = prefs.isEnabled,
        identityRegistered = prefs.identityRegistered,
        serverOrigin = prefs.gmwebServerOrigin
    )

    /**
     * Build the payload for THIS report.
     *
     * SIM discovery happens here, per report, and is never a cached list: a web-requested refresh
     * exists precisely because a cached one can be stale. A missing permission is reported as an
     * unavailable SIM list INSIDE a successful report — it is not a transport failure and must not
     * make the phone look offline.
     */
    private suspend fun buildPayload(trigger: TelemetryTrigger): JSONObject {
        // BOUNDARY: the payload build. Traced around the call so a local failure names the exception
        // class instead of arriving as "the network is broken".
        trace("payload_build_begin trigger=${trigger.wireValue}")
        return try {
            buildPayloadSections(trigger).also { trace("payload_build_result ok=true") }
        } catch (e: Exception) {
            trace("payload_build_result ok=false errorClass=${e.javaClass.simpleName}")
            throw e
        }
    }

    /**
     * A NON-CRITICAL payload section.
     *
     * Telemetry must not disappear because one diagnostic counter could not be read: the phone's
     * presence, its version and its SIM state are worth far more than the outbox depths. A failing
     * section degrades to "not reported" and is traced with its exception class, while identity,
     * signing and the core fields stay mandatory — those failures must never be swallowed.
     */
    private suspend fun optionalSection(name: String, block: suspend () -> Any?): Any? = try {
        block()
    } catch (e: Exception) {
        trace("payload_section_failed name=$name errorClass=${e.javaClass.simpleName}")
        null
    }

    private suspend fun buildPayloadSections(trigger: TelemetryTrigger): JSONObject {
        val db = MessagesDatabase.get(appContext)
        val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        @Suppress("DEPRECATION")
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }
        val deviceId = prefs.agentDeviceId(appContext)

        // ── SIMs: a FRESH discovery, three distinguishable answers ──────────
        val simManager = SimManager(appContext)
        val discovery = simManager.discover()
        val permissionGranted = simManager.hasReadPhoneState()
        val discoveryReason = SimDiscovery.reasonOf(discovery)
        val simCount = (discovery as? SimDiscoveryResult.Available)?.sims?.size
        TelemetryHealth.onSubscriptionDiscovery(count = simCount, reason = discoveryReason)
        val defaultSmsSubscriptionId = simManager.defaultSmsSubscriptionId()
        val telemetryHealth = TelemetryHealth.snapshot()

        // ── Optional diagnostic sections ────────────────────────────────────
        // Every one of these is a NICE-TO-HAVE. A failing counter must cost its own section, not the
        // whole heartbeat: the phone's presence, version and SIM state are what GMweb cannot do
        // without, and a diagnostic read that throws is not a reason to disappear.
        val batterySection = optionalSection("battery") {
            val battery = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
            JSONObject()
                .put(
                    "level",
                    batteryPercent(
                        battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1,
                        battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1,
                    )
                )
                .put(
                    "isCharging",
                    status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
                )
                .put(
                    "chargingSource",
                    when (plugged) {
                        BatteryManager.BATTERY_PLUGGED_AC -> "AC"
                        BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "WIRELESS"
                        else -> "NONE"
                    }
                )
        }
        val networkSection = optionalSection("network") {
            val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
            val capabilities = connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
            val networkType = when {
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WIFI"
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "CELLULAR"
                else -> "UNKNOWN"
            }
            JSONObject()
                .put(
                    "isConnected",
                    capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                )
                .put("networkType", networkType)
        }
        val syncSection = optionalSection("sync") {
            val eventDao = db.gatewayEventOutboxDao()
            val historyAckLag = db.cloudHistoryCheckpointDao().all().sumOf {
                (it.nextOrdinal - 1 - it.ackedContiguousOrdinal).coerceAtLeast(0)
            }
            val trustHealth = TrustStatementPublisher.health.value
            JSONObject()
                .put("outboxDepth", eventDao.pendingDepth())
                .put("realtimeQueueDepth", eventDao.pendingRealtimeDepth())
                .put("backfillQueueDepth", eventDao.pendingBackfillDepth())
                .put("deadLetterCount", eventDao.deadLetterDepth())
                .put("historyAckLag", historyAckLag)
                .put("trustOutboxDepth", trustHealth.pendingCount)
                .putOpt("lastTrustAckAt", trustHealth.lastAckAt)
                .putOpt("lastTrustHttpStatus", trustHealth.lastHttpStatus)
        }
        val trustSection = optionalSection("trust") {
            JSONObject()
                .put("isEnrolled", prefs.identityRegistered)
                .put("approvedDevicesCount", db.trustedDeviceDao().countTrusted())
                .put("trustSequence", db.trustStatementOutboxDao().maxTrustSequence())
        }

        return JSONObject()
            .put("deviceId", deviceId)
            .put("timestamp", System.currentTimeMillis())
            .put("trigger", trigger.wireValue)
            .put(
                "smsSubscriptions",
                TelemetryPayloadSections.smsSubscriptions(
                    discovery = discovery,
                    permissionGranted = permissionGranted,
                    defaultSubscriptionId = defaultSmsSubscriptionId,
                    lastChangedAt = telemetryHealth.lastSubscriptionChangeAt,
                    safeLabel = Companion::safeSimLabel
                )
            )
            .put(
                "permissions",
                TelemetryPayloadSections.permissions(
                    readPhoneState = permissionGranted,
                    sendSms = SmsSendPreflight.hasSendSmsPermission(appContext),
                    defaultSmsRole = DefaultSmsRole.isHeld(appContext)
                )
            )
            // LOCAL BUILD EVIDENCE: what this APK can be asked to do over the encrypted command
            // channel. Never derived from server input; older GMweb ignores additive fields.
            .put("capabilities", TelemetryPayloadSections.capabilities())
            .putOpt("battery", batterySection)
            .putOpt("sync", syncSection)
            .putOpt("trust", trustSection)
            .putOpt("network", networkSection)
            .put(
                "app",
                JSONObject()
                    .put("versionName", packageInfo.versionName ?: "")
                    .put("versionCode", versionCode)
                    .put("uptimeMs", SystemClock.elapsedRealtime() - startedAt)
            )
            .put(
                "device",
                JSONObject()
                    .put("manufacturer", Build.MANUFACTURER)
                    .put("model", Build.MODEL)
                    .put("androidVersion", Build.VERSION.RELEASE)
                    .put("sdkInt", Build.VERSION.SDK_INT)
            )
    }

    /**
     * The transport, through the SAME client, path, method and signature family as every other agent
     * channel. The body's `deviceId` and the signed device id are the same value by construction:
     * both come from `prefs.agentDeviceId`.
     */
    private suspend fun postViaControlPlane(body: JSONObject, deviceId: String): TelemetryPostOutcome =
        when (
            val result = client.post(
                path = PATH,
                body = body,
                signer = { conn, bytes -> AgentAuth.sign(conn, deviceId, PATH, "POST", bytes) },
                traceTag = "GM_TELEMETRY"
            )
        ) {
            is ControlPlaneClient.Result.Success -> TelemetryPostOutcome.Accepted(result.httpStatus)
            is ControlPlaneClient.Result.Failure -> when (result.kind) {
                ControlPlaneClient.FailureKind.SIGNING -> TelemetryPostOutcome.SigningFailed
                ControlPlaneClient.FailureKind.HTTP -> TelemetryPostOutcome.Rejected(result.httpStatus ?: 0)
                ControlPlaneClient.FailureKind.INSECURE_URL -> TelemetryPostOutcome.InsecureUrl
                // A genuine network failure, and only that: its exception class is the detail the
                // on-device card needs to tell DNS from TLS from a read timeout.
                ControlPlaneClient.FailureKind.TRANSPORT ->
                    TelemetryPostOutcome.TransportError(result.error.take(TelemetryHealth.MAX_DETAIL_CHARS))
            }
        }
}