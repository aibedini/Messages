package com.autonomousone.messages.gateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.autonomousone.messages.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class GatewayService : Service() {

    private var gatewayServer: GatewayServer? = null
    private lateinit var prefs: GatewayPreferences
    private lateinit var backendClient: BackendClient
    private lateinit var registrationManager: RegistrationManager
    private lateinit var heartbeatManager: HeartbeatManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val CHANNEL_ID = "gateway_service_channel"
        private const val NOTIFICATION_ID = 2001

        const val ACTION_START = "com.autonomousone.messages.ACTION_START_GATEWAY"
        const val ACTION_STOP = "com.autonomousone.messages.ACTION_STOP_GATEWAY"

        @Volatile
        var isServiceRunning = false
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
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServer()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                if (!GatewayAccessPolicy.canStart(prefs.hasGatewayConsent)) {
                    _logFlow.tryEmit("Gateway start blocked: privacy consent is required")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForegroundNotification()
                startServerAsync()
            }
        }
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val port = prefs.port
        val ip = GatewayServer.getLocalIpAddress()
        val notification = buildNotification(
            "SMS Gateway Active",
            "LAN: http://$ip:$port \u2022 Cloud: ${prefs.backendUrl}"
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    } else {
                        0
                    }
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // Fallback for devices without type restriction
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startServerAsync() {
        serviceScope.launch {
            if (!GatewayAccessPolicy.canStart(prefs.hasGatewayConsent)) return@launch
            if (isServiceRunning && gatewayServer?.isRunning() == true) return@launch

            val port = prefs.port
            val apiKey = prefs.apiKey

            gatewayServer = GatewayServer(
                this@GatewayService,
                port,
                apiKey,
                bindAllInterfaces = prefs.bindAllInterfaces
            ) { logMsg ->
                _logFlow.tryEmit(logMsg)
            }

            gatewayServer?.start()
            isServiceRunning = true
            prefs.isEnabled = true

            val ip = GatewayServer.getLocalIpAddress()
            _logFlow.tryEmit("🚀 Gateway Service running at http://$ip:$port")

            // Start cloud backend registration + heartbeat
            val registered = registrationManager.ensureRegistered()
            if (registered) {
                heartbeatManager.start()
                _logFlow.tryEmit("☁️ Cloud backend connected (${prefs.backendUrl})")
            } else {
                _logFlow.tryEmit("⚠️ Cloud registration failed — will retry automatically")
                // HeartbeatManager will self-register on its first loop iteration
                heartbeatManager.start()
            }
        }
    }

    private fun stopServer() {
        serviceScope.launch {
            heartbeatManager.stop()
            gatewayServer?.stop()
            gatewayServer = null
            isServiceRunning = false
            prefs.isEnabled = false
            _logFlow.tryEmit("🛑 Gateway Service stopped")
        }
    }

    override fun onDestroy() {
        stopServer()
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
