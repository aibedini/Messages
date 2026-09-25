package com.autonomousone.messages.gateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.autonomousone.messages.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class GatewayService : Service() {

    private lateinit var prefs: GatewayPreferences
    private lateinit var backendClient: BackendClient
    private lateinit var registrationManager: RegistrationManager
    private lateinit var heartbeatManager: HeartbeatManager
    private lateinit var outboxPoller: OutboxPoller
    private lateinit var eventUploader: EventUploader
    private lateinit var commandPoller: SecureCommandPoller
    private lateinit var trustPublisher: TrustStatementPublisher
    private lateinit var deviceTelemetry: DeviceTelemetry
    private lateinit var contactsSyncPublisher: ContactsSyncPublisher
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var supervisor: ConnectionSupervisor

    /**
     * Makes provider→cloud replication independent of the UI (audit Blocker 1).
     *
     * Owned by the service, so its lifetime is exactly "the gateway is running" — which is what
     * replication being on means. Registered even while offline, on purpose: an event must become
     * durable locally whether or not it can be uploaded yet, so an outage loses nothing.
     */
    private lateinit var changeRelay: com.autonomousone.messages.observer.GatewayChangeRelay

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val CHANNEL_ID = "gateway_service_channel"
        private const val NOTIFICATION_ID = 2001
        private const val WATCHDOG_DELAY_MS = 15_000L

        /** Log tag for the start path, including refused foreground starts. */
        private const val TAG = "GatewayService"

        const val ACTION_START = "com.autonomousone.messages.ACTION_START_GATEWAY"
        const val ACTION_STOP = "com.autonomousone.messages.ACTION_STOP_GATEWAY"
        /** Manual "Reconnect now" from the UI: cancel backoff, retry immediately. */
        const val ACTION_RETRY_NOW = "com.autonomousone.messages.ACTION_RETRY_GATEWAY"

        /**
         * Runtime truth, DERIVED by ConnectionSupervisor — no longer a flag
         * components poke on their way out. Consumers (GatewayViewModel,
         * screens) read this instead of a hand-set boolean.
         */
        val isServiceRunning: Boolean
            get() = supervisorState.let {
                it == ConnectionSupervisor.State.CONNECTED ||
                it == ConnectionSupervisor.State.CONNECTING ||
                it == ConnectionSupervisor.State.RECONNECTING
            }

        /** Supervisor state, live once the service exists. */
        @Volatile
        var supervisorStateFlow: StateFlow<ConnectionSupervisor.State>? = null
            private set

        /** Latest supervisor state (safe default before the service starts). */
        @Volatile
        var supervisorState: ConnectionSupervisor.State = ConnectionSupervisor.State.DISABLED
            private set

        /** Latest OutboxPoller state, updated while the service is alive. */
        @Volatile
        var bridgeStateFlow: StateFlow<OutboxPoller.State>? = null
            private set

        private val _logFlow = MutableSharedFlow<String>(extraBufferCapacity = 100)
        val logFlow: SharedFlow<String> = _logFlow.asSharedFlow()

        /**
         * @param reason who is starting this. [GatewayForegroundStartPolicy.StartReason.BOOT] is not
         *   decoration: on Android 15+ a boot receiver may not launch a `dataSync` foreground
         *   service, so the reason decides which foregroundServiceType is legal. It travels as an
         *   intent extra because the type is chosen later, in `startForegroundNotification`.
         * @param deferOnFailure when the platform refuses, enqueue a WorkManager retry. The worker
         *   itself passes false, because its own `Result.retry()` IS the deferral — without this the
         *   two would enqueue work for each other.
         * @return true when the platform accepted the start request.
         */
        fun startGateway(
            context: Context,
            reason: GatewayForegroundStartPolicy.StartReason =
                GatewayForegroundStartPolicy.StartReason.USER_OR_APP,
            deferOnFailure: Boolean = true
        ): Boolean {
            if (!GatewayAccessPolicy.canStart(GatewayPreferences(context).hasGatewayConsent)) return false
            val intent = Intent(context, GatewayService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_START_REASON, reason.name)
            }
            // A refused foreground start must never crash the caller: a boot receiver that throws
            // takes the whole process down at boot, and the watchdog alarm would crash too. The
            // policy avoids the documented refusals; this catches the undocumented ones.
            return runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { error ->
                Log.w(TAG, "gateway foreground start refused (${error.javaClass.simpleName})")
                if (deferOnFailure) GatewayStartDeferral.defer(context)
            }.isSuccess
        }

        fun stopGateway(context: Context) {
            val intent = Intent(context, GatewayService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        /**
         * Transport-agnostic self-heal: demand an immediate reconcile +
         * heartbeat retry. Touches no cloud-only registration — a pure
         * android-pull (GMweb) gateway heals through exactly the same door
         * as a cloud one. If the reconcile loop died with a stale service,
         * retryNow()'s idempotent ensureLoop() revives it; if the service
         * itself is gone, the ACTION_RETRY_NOW start path rebuilds and
         * retries from onCreate/onStartCommand.
         */
        fun reconnectNow(context: Context) {
            ConnectionSupervisor.peek()?.retryNow() ?: retryNow(context)
        }

        fun retryNow(context: Context) {
            val intent = Intent(context, GatewayService::class.java).apply {
                action = ACTION_RETRY_NOW
                putExtra(EXTRA_START_REASON, GatewayForegroundStartPolicy.StartReason.RETRY.name)
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { error ->
                Log.w(TAG, "gateway retry start refused (${error.javaClass.simpleName}) — deferring")
                GatewayStartDeferral.defer(context)
            }
        }

        /** Intent extra carrying [GatewayForegroundStartPolicy.StartReason]. */
        const val EXTRA_START_REASON = "com.autonomousone.messages.EXTRA_START_REASON"

        /**
         * The reason recorded for the current start, read by `startForegroundNotification`.
         *
         * Volatile and process-wide rather than per-intent because `startForeground` is called from
         * the service lifecycle, after the intent that caused it has been consumed.
         */
        @Volatile
        var startReason: GatewayForegroundStartPolicy.StartReason =
            GatewayForegroundStartPolicy.StartReason.USER_OR_APP
            private set

        private fun recordStartReason(intent: Intent?) {
            intent?.getStringExtra(EXTRA_START_REASON)?.let {
                startReason = GatewayForegroundStartPolicy.StartReason.fromExtra(it)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = GatewayPreferences(this)
        // Registered for the service's whole life: replication must not depend on a screen being
        // open, and a change that arrives while offline still has to become durable locally.
        changeRelay = com.autonomousone.messages.observer.GatewayChangeRelay(this)
        changeRelay.start()
        backendClient = BackendClient(prefs)
        registrationManager = RegistrationManager(this, prefs, backendClient) { msg ->
            _logFlow.tryEmit(msg)
        }
        heartbeatManager = HeartbeatManager(
            context = this,
            prefs = prefs,
            client = backendClient,
            registrationManager = registrationManager,
            scope = serviceScope,
            onLog = { msg -> _logFlow.tryEmit(msg) }
        )
        outboxPoller = OutboxPoller(
            context = this,
            prefs = prefs,
            scope = serviceScope,
            onLog = { msg -> _logFlow.tryEmit(msg) },
            networkMonitor = NetworkMonitor.get(this)
        )
        // PR-02 + P0 control-plane SSOT: events → GMweb (gmwebUrl), NOT the
        // legacy cloud backendUrl (BackendClient stays for legacy heartbeat).
        eventUploader = EventUploader(
            context = this,
            prefs = prefs,
            client = ControlPlaneClient(prefs),
            scope = serviceScope,
            onLog = { msg -> _logFlow.tryEmit(msg) }
        )
        // PR-10: strategic command transport (/api/v1 agent bridge). The
        // legacy OutboxPoller above stays as the compatibility transport.
        commandPoller = SecureCommandPoller(
            context = this,
            prefs = prefs,
            scope = serviceScope,
            onLog = { msg -> _logFlow.tryEmit(msg) }
        )
        // LINKED DEVICE CONTROL pt2: publish signed trust statements
        // (DEVICE_APPROVED/CAPABILITIES_CHANGED/REVOKED) until ACKed.
        trustPublisher = TrustStatementPublisher(
            context = this,
            prefs = prefs,
            scope = serviceScope,
            onLog = { msg -> _logFlow.tryEmit(msg) }
        )
        deviceTelemetry = DeviceTelemetry(this, prefs, ControlPlaneClient(prefs), serviceScope)
        deviceTelemetry.start()
        contactsSyncPublisher = ContactsSyncPublisher(this, prefs, serviceScope)
        contactsSyncPublisher.start()
        // Expose poller state app-wide so the Gateway screen can show it live.
        bridgeStateFlow = outboxPoller.stateFlow

        networkMonitor = NetworkMonitor.get(this)
        supervisor = ConnectionSupervisor.get(
            context = this,
            prefs = prefs,
            networkMonitor = networkMonitor,
            scope = serviceScope,
            newServer = {
                GatewayServer(
                    this,
                    prefs.port,
                    prefs.apiKey,
                    bindAllInterfaces = prefs.bindAllInterfaces
                ) { logMsg -> _logFlow.tryEmit(logMsg) }
            },
            components = ConnectionSupervisor.ManagedComponents(
                startHeartbeat = { heartbeatManager.start() },
                stopHeartbeat = { heartbeatManager.stop() },
                retryHeartbeat = { heartbeatManager.retryNow() },
                startPoller = { outboxPoller.start() },
                stopPoller = { outboxPoller.stop() },
                isPollerRunning = { outboxPoller.isRunning },
                wakePoller = { outboxPoller.retryNow() },
                retryUploader = { eventUploader.retryNow() },
                startEventUploader = { eventUploader.start() },
                stopEventUploader = { eventUploader.stop() },
                startTrustPublisher = { trustPublisher.start() },
                stopTrustPublisher = { trustPublisher.stop() },
                retryTrustPublisher = { trustPublisher.retryNow() },
                startCommandPoller = { commandPoller.start() },
                stopCommandPoller = { commandPoller.stop() },
                startSync = {
                    com.autonomousone.messages.data.TelephonySyncCoordinator
                        .get(this).startGatewaySync()
                },
                // Mission §34: after a reconnect, reconcile the mirror against the outbox. Runs off
                // the supervisor's tick, so it must never block or throw into it.
                reconcileMissedEvents = {
                    serviceScope.launch {
                        runCatching {
                            com.autonomousone.messages.data.TelephonySyncCoordinator
                                .get(this@GatewayService)
                                .reconcileMissingEvents()
                        }.onSuccess { result ->
                            if (result.foundGap) {
                                _logFlow.tryEmit(
                                    "♻️ Recovered ${result.recovered} message(s) that had not " +
                                        "reached the outbox"
                                )
                            }
                        }.onFailure {
                            android.util.Log.w("SYNC_RECONCILE", "post-reconnect reconcile failed", it)
                        }
                    }
                },
                // Mission §35: one bounded page of the full-mirror verification per tick. The window
                // check above cannot see a gap older than its own window, and once the history scan
                // has finished nothing else revisits those rows — so without this the app's answer to
                // "is the whole mirror replicated?" would rest on two passes that both decline to ask.
                verifyMirror = {
                    serviceScope.launch {
                        runCatching {
                            com.autonomousone.messages.data.TelephonySyncCoordinator
                                .get(this@GatewayService)
                                .verifyMirrorPage()
                        }.onFailure {
                            android.util.Log.w("SYNC_VERIFY", "mirror verification page failed", it)
                        }
                    }
                }
            ),
            onLog = { msg -> _logFlow.tryEmit(msg) }
        )
        supervisorStateFlow = supervisor.stateFlow
        serviceScope.launch {
            supervisor.stateFlow.collect { state ->
                supervisorState = state
                updateNotification(state)
            }
        }
        // v2.6.11: mirror poller state into the persistent notification so
        // "GMweb bridge dark" (the 503 android_gateway_unreachable cause) is
        // visible on the lock screen, not only inside the gateway screen.
        serviceScope.launch {
            outboxPoller.stateFlow.collect { pollState ->
                val bridgeLine = when (pollState) {
                    OutboxPoller.State.POLLING -> " • GMweb bridge: live"
                    OutboxPoller.State.DELIVERING -> " • GMweb bridge: delivering"
                    OutboxPoller.State.ERROR -> " • GMweb bridge: retrying…"
                    OutboxPoller.State.IDLE -> if (prefs.gmwebUrl.isNotBlank()) {
                        " • GMweb bridge: idle"
                    } else {
                        ""
                    }
                }
                val state = supervisorState
                if (state == ConnectionSupervisor.State.CONNECTED) {
                    updateNotification(state, bridgeLine)
                }
            }
        }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Before anything starts a foreground service: record WHY it was started, because the
        // legal foregroundServiceType depends on it (a boot start may not use dataSync on
        // Android 15+) and the type is chosen in startForegroundNotification below.
        recordStartReason(intent)
        when (intent?.action) {
            ACTION_STOP -> {
                supervisor.stop() // flips desired OFF persistently
                shutdownComponents()
                stopForegroundLike()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RETRY_NOW -> {
                startForegroundNotification()
                supervisor.retryNow()
            }
            ACTION_START, null -> {
                if (!GatewayAccessPolicy.canStart(prefs.hasGatewayConsent)) {
                    _logFlow.tryEmit("Gateway start blocked: privacy consent is required")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForegroundNotification()
                // Non-blocking: the supervisor loop binds the server, starts
                // heartbeat/poller/sync and self-heals from here on. A null
                // intent (START_STICKY revival after process death) lands
                // here too — desiredEnabled replays from prefs automatically.
                supervisor.start()
            }
        }
        return START_STICKY
    }

    /**
     * v2.6.11 watchdog: START_STICKY revival is delayed and unreliable under
     * Doze — and while the service is dead, the GMweb pull bridge is dark, so
     * every Eve send fails with 503 android_gateway_unreachable. If the
     * gateway is still desired-enabled, schedule an exact alarm that revives
     * the service even from Doze (setExactAndAllowWhileIdle). This runs in
     * onDestroy, including force-stop-adjacent kills where onDestroy fires.
     */
    private fun scheduleRestartWatchdog() {
        if (!GatewayAccessPolicy.shouldAutoReconnect(
                prefs.hasGatewayConsent,
                prefs.gatewayDesiredEnabled
            )
        ) return // no consent or user explicitly turned the gateway off

        // WorkManager FIRST, on every platform. It is the only revival that survives a refusal: its
        // worker retries with backoff and a refusal is caught and logged. The alarm below cannot do
        // that on API 31+, where the system performs the background start and simply refuses it with
        // no callback here — which is how this watchdog used to report a revival that never happened.
        GatewayStartDeferral.defer(this)

        val mechanism = GatewayForegroundStartPolicy.restartMechanism(Build.VERSION.SDK_INT)
        if (!GatewayForegroundStartPolicy.includesAlarm(mechanism)) {
            _logFlow.tryEmit(
                "⏱️ Gateway restart deferred to WorkManager " +
                    "(background start restricted on API ${Build.VERSION.SDK_INT})"
            )
            return
        }

        try {
            val alarmManager = getSystemService(AlarmManager::class.java) ?: return
            val restart = Intent(this, GatewayService::class.java).apply {
                action = ACTION_START
            }
            val pi = PendingIntent.getForegroundService(
                this, 2002, restart,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val triggerAt = SystemClock.elapsedRealtime() + WATCHDOG_DELAY_MS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                // Inexact fallback still revives us within Doze-compatible windows.
                alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            } else {
                alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
            _logFlow.tryEmit("⏱️ Watchdog scheduled: gateway restart in ${WATCHDOG_DELAY_MS / 1000}s if it stays down")
        } catch (e: Exception) {
            Log.e("GatewayService", "watchdog schedule failed", e)
        }
    }

    private fun stopForegroundLike() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    /** Full teardown of the supervisor + its components (ACTION_STOP, onDestroy). */
    private fun shutdownComponents() {
        supervisor.shutdown()
        supervisorStateFlow = null
        bridgeStateFlow = null
    }

    private fun startForegroundNotification() {
        val port = prefs.port
        val notification = buildNotification(
            "SMS Gateway Active",
            "LAN: http://${GatewayServer.getLocalIpAddress()}:$port • Cloud: ${prefs.backendUrl}"
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // The type is DECIDED, not hardcoded: a boot start on Android 15+ may not use
                // dataSync (GatewayForegroundStartPolicy), and asking for a type the start reason
                // forbids is what made the reboot re-arm throw.
                val decision = GatewayForegroundStartPolicy.decide(
                    apiLevel = Build.VERSION.SDK_INT,
                    startReason = startReason
                )
                val wantsDataSync = GatewayForegroundStartPolicy.includesDataSync(decision)
                val type = when {
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    wantsDataSync ->
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    else ->
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                }
                Log.i(TAG, "foreground start reason=$startReason decision=$decision type=$type")
                startForeground(NOTIFICATION_ID, notification, type)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // Fallback for devices without type restriction
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** Notification text mirrors the supervisor state — the user sees
     *  "waiting for network", not a silent dead gateway. [extraSuffix]
     *  appends live bridge telemetry (v2.6.11) without changing the title. */
    private fun updateNotification(
        state: ConnectionSupervisor.State,
        extraSuffix: String = ""
    ) {
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        val port = prefs.port
        val (title, text) = when (state) {
            ConnectionSupervisor.State.DISABLED ->
                "SMS Gateway" to "Gateway is stopped"
            ConnectionSupervisor.State.WAITING_FOR_NETWORK ->
                "SMS Gateway" to "📴 Waiting for a network connection…"
            ConnectionSupervisor.State.CONNECTING ->
                "SMS Gateway" to "Starting gateway…"
            ConnectionSupervisor.State.CONNECTED ->
                "SMS Gateway Active" to "LAN: http://${GatewayServer.getLocalIpAddress()}:$port • Cloud: ${prefs.backendUrl}$extraSuffix"
            ConnectionSupervisor.State.RECONNECTING ->
                "SMS Gateway" to "🔁 Reconnecting…"
            ConnectionSupervisor.State.ERROR ->
                "SMS Gateway" to "⚠️ Retrying — check the gateway screen for details"
        }
        try {
            mgr.notify(NOTIFICATION_ID, buildNotification(title, text))
        } catch (_: Exception) {
            // Notification may have been cancelled with the service — ignore.
        }
    }

    override fun onDestroy() {
        // v2.6.11: if this death was NOT user-initiated (ACTION_STOP already
        // cleared the desired state), arm the alarm watchdog so the pull
        // bridge comes back even under Doze — this is the 503-killer.
        scheduleRestartWatchdog()
        changeRelay.stop()
        deviceTelemetry.stop()
        contactsSyncPublisher.stop()
        shutdownComponents()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Android 15+ stops a `dataSync` foreground service that has run too long in the background.
     *
     * This is the platform answer to "how long may the bridge run?", and the honest response is to
     * accept it rather than fight it: the service CANNOT start itself back into the background, so
     * pretending it is alive would be false. Record it, hand the retry to WorkManager — which will
     * run when the OS next permits it — and stop promptly, because lingering past a timeout is what
     * earns an ANR.
     *
     * Overridden against compileSdk 36; it is only ever invoked on API 35+, and the default
     * implementation (which simply stops the service) still applies below that.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        android.util.Log.w(
            TAG,
            "foreground service timed out (startId=$startId fgsType=$fgsType) — deferring restart"
        )
        _logFlow.tryEmit("⏱️ Foreground service timed out; restart deferred to WorkManager")
        GatewayStartDeferral.defer(this)
        stopSelf(startId)
    }

    private fun buildNotification(title: String, text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, GatewayService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Gateway", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SMS Gateway Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows persistent status of the SMS Gateway server"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
