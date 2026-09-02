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
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var supervisor: ConnectionSupervisor
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val CHANNEL_ID = "gateway_service_channel"
        private const val NOTIFICATION_ID = 2001
        private const val WATCHDOG_DELAY_MS = 15_000L

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

        fun startGateway(context: Context) {
            if (!GatewayAccessPolicy.canStart(GatewayPreferences(context).hasGatewayConsent)) return
            val intent = Intent(context, GatewayService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
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
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = GatewayPreferences(this)
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
                startEventUploader = { eventUploader.start() },
                stopEventUploader = { eventUploader.stop() },
                startCommandPoller = { commandPoller.start() },
                stopCommandPoller = { commandPoller.stop() },
                startSync = {
                    com.autonomousone.messages.data.TelephonySyncCoordinator
                        .get(this).ensureLoopRunning()
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
        if (!GatewayAccessPolicy.canStart(prefs.hasGatewayConsent)) return
        if (!prefs.isEnabled) return // user turned the gateway OFF — stay dead
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
                // v2.6.11: dataSync is the honest type for the pull bridge
                // (a long-running network sync to GMweb) and gives the OS a
                // correct policy signal, while specialUse stays for the LAN
                // server aspect. On API 34+ both are declared; dataSync is
                // what keeps Doze from freezing our sockets mid-long-poll.
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                }
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
        shutdownComponents()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
