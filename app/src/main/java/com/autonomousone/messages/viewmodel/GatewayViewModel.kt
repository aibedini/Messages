package com.autonomousone.messages.viewmodel

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autonomousone.messages.gateway.BackendClient
import com.autonomousone.messages.gateway.ConnectionSupervisor
import com.autonomousone.messages.gateway.GatewayPreferences
import com.autonomousone.messages.gateway.GatewayServer
import com.autonomousone.messages.gateway.GatewayService
import com.autonomousone.messages.gateway.HeartbeatManager
import com.autonomousone.messages.gateway.RegistrationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GatewayViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val prefs = GatewayPreferences(application)
    private val backendClient = BackendClient(prefs)
    private val registrationManager = RegistrationManager(
        context = application,
        prefs = prefs,
        client = backendClient,
        onLog = { msg -> addLog(msg) }
    )

    // ── LAN server state (existing) ────────────────────────────────────────
    var isServerRunning by mutableStateOf(GatewayService.isServiceRunning)
        private set
    /** Live supervisor state for the UI chips/banner (derived, not a flag). */
    var gatewayState by mutableStateOf(GatewayService.supervisorState)
        private set
    /** The user's intent — drives the switch while runtime state recovers. */
    var gatewayDesired by mutableStateOf(prefs.gatewayDesiredEnabled)
        private set
    var hasGatewayConsent by mutableStateOf(prefs.hasGatewayConsent)
        private set
    var showConsentDialog by mutableStateOf(false)
        private set
    var port by mutableIntStateOf(prefs.port)
    var apiKey by mutableStateOf(prefs.apiKey)
    var webhookUrl by mutableStateOf(prefs.webhookUrl)
    var webhookSecret by mutableStateOf(prefs.webhookSecret)
    var bindAllInterfaces by mutableStateOf(prefs.bindAllInterfaces)
        private set
    var localIpAddress by mutableStateOf(GatewayServer.getLocalIpAddress())
        private set

    // ── Cloud backend state (new) ──────────────────────────────────────────
    var backendUrl by mutableStateOf(prefs.backendUrl)
        private set
    var registrationSecret by mutableStateOf(prefs.registrationSecret)
        private set
    var gatewayId by mutableStateOf(prefs.gatewayId)
        private set
    var cloudConnectionState by mutableStateOf(HeartbeatManager.ConnectionState.IDLE)
        private set
    var lastHeartbeatAt by mutableLongStateOf(prefs.lastHeartbeatAt)
        private set
    var cloudError by mutableStateOf<String?>(null)
        private set
    var isRegistered by mutableStateOf(prefs.isRegistered)
        private set

    val logs = mutableStateListOf<String>()

    init {
        observeLogs()
        observeHeartbeatState()
        observeSupervisorState()
        refreshStatus()
    }

    /** Poll the live supervisor state (the service may start/stop while this
     *  screen is open; the singleton swaps underneath us). Cheap: 1 s tick. */
    private fun observeSupervisorState() {
        viewModelScope.launch {
            while (isActive) {
                gatewayState = GatewayService.supervisorState
                isServerRunning = GatewayService.isServiceRunning
                gatewayDesired = prefs.gatewayDesiredEnabled
                delay(1_000)
            }
        }
    }

    fun refreshStatus() {
        isServerRunning = GatewayService.isServiceRunning
        localIpAddress = GatewayServer.getLocalIpAddress()
        gatewayId = prefs.gatewayId
        lastHeartbeatAt = prefs.lastHeartbeatAt
        isRegistered = prefs.isRegistered
        backendUrl = prefs.backendUrl
        hasGatewayConsent = prefs.hasGatewayConsent
    }

    private fun observeLogs() {
        viewModelScope.launch {
            GatewayService.logFlow.collect { logMsg ->
                isServerRunning = GatewayService.isServiceRunning
                val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                logs.add(0, "[$time] $logMsg")
                if (logs.size > 100) logs.removeAt(logs.lastIndex)
            }
        }
    }

    private fun observeHeartbeatState() {
        // Observe state from the running GatewayService via log flow — lightweight approach
        // since HeartbeatManager lives inside the Service process
        viewModelScope.launch {
            GatewayService.logFlow.onEach {
                lastHeartbeatAt = prefs.lastHeartbeatAt
                isRegistered = prefs.isRegistered
                gatewayId = prefs.gatewayId
                cloudConnectionState = if (prefs.lastHeartbeatAt > System.currentTimeMillis() - 90_000) {
                    HeartbeatManager.ConnectionState.CONNECTED
                } else if (prefs.isRegistered) {
                    HeartbeatManager.ConnectionState.DISCONNECTED
                } else {
                    HeartbeatManager.ConnectionState.IDLE
                }
            }.launchIn(this)
        }
    }

    // ── Actions ────────────────────────────────────────────────────────────

    fun toggleServer(enable: Boolean) {
        val context = getApplication<Application>()
        if (enable) {
            if (!prefs.hasGatewayConsent) {
                showConsentDialog = true
                isServerRunning = false
                return
            }
            prefs.port = port
            prefs.apiKey = apiKey
            GatewayService.startGateway(context)
        } else {
            GatewayService.stopGateway(context)
        }
        gatewayDesired = enable // optimistic switch state; service reconciles truth
        refreshStatus()
    }

    fun reconnectNow() {
        if (!prefs.hasGatewayConsent) {
            showConsentDialog = true
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            addLog("🔄 Manual reconnect triggered...")
            cloudConnectionState = HeartbeatManager.ConnectionState.CONNECTING
            // Supervisor first: cancels heartbeat backoff, rebinds a stale LAN
            // server, wakes the poller — the full self-heal path. The manual
            // registration below is a fast-path for the cloud chip only.
            GatewayService.retryNow(getApplication())
            val success = registrationManager.register()
            if (success) {
                refreshStatus()
                addLog("✅ Reconnected to cloud backend")
                cloudConnectionState = HeartbeatManager.ConnectionState.CONNECTED
            } else {
                addLog("❌ Reconnect failed — check network and backend URL")
                cloudConnectionState = HeartbeatManager.ConnectionState.ERROR
            }
        }
    }

    fun acceptGatewayConsentAndStart() {
        prefs.acceptGatewayConsent()
        hasGatewayConsent = true
        showConsentDialog = false
        toggleServer(true)
    }

    fun dismissGatewayConsent() {
        showConsentDialog = false
    }

    fun revokeGatewayConsent() {
        GatewayService.stopGateway(getApplication())
        prefs.revokeGatewayConsent()
        hasGatewayConsent = false
        isServerRunning = false
        gatewayDesired = false
        showConsentDialog = false
        addLog("Gateway consent revoked; networking and SMS forwarding stopped")
    }

    fun generateNewApiKey() {
        val newKey = prefs.generateNewApiKey()
        apiKey = newKey
        // Never log the full key value.
        addLog("🔑 Generated new API Key (${newKey.take(7)}…${newKey.takeLast(4)})")
    }

    /**
     * Replaces the gateway API key with one supplied by the user — e.g. the
     * GMWEB_ANDROID_DEVICE_KEY generated on the GMweb server, so both sides
     * share the same secret without copy-pasting in both directions.
     */
    fun updateApiKey(newKey: String) {
        val v = newKey.trim()
        if (v.isBlank()) {
            Toast.makeText(getApplication(), "API key cannot be empty", Toast.LENGTH_LONG).show()
            return
        }
        prefs.apiKey = v
        apiKey = v
        addLog("🔑 API key set manually (${v.take(7)}…${v.takeLast(4)})")
        Toast.makeText(getApplication(), "API key updated", Toast.LENGTH_SHORT).show()
    }

    fun saveWebhookUrl(newUrl: String) {
        webhookUrl = newUrl.trim()
        prefs.webhookUrl = webhookUrl
        addLog("🔗 Saved Webhook URL: $webhookUrl")
        Toast.makeText(getApplication(), "Webhook URL saved", Toast.LENGTH_SHORT).show()
    }

    fun saveWebhookSecret(newSecret: String) {
        webhookSecret = newSecret.trim()
        prefs.webhookSecret = webhookSecret
        addLog(
            if (webhookSecret.isBlank()) "🔓 Webhook signing disabled"
            else "🔒 Webhook HMAC signing enabled (X-Signature header)"
        )
        Toast.makeText(getApplication(), "Webhook secret saved", Toast.LENGTH_SHORT).show()
    }

    fun saveRegistrationSecret(newSecret: String) {
        registrationSecret = newSecret.trim()
        prefs.registrationSecret = registrationSecret
        addLog(
            if (registrationSecret.isBlank()) "⚠️ Registration secret cleared — backend must allow open registration"
            else "🔐 Registration secret saved (sent as X-Registration-Secret)"
        )
        Toast.makeText(getApplication(), "Registration secret saved", Toast.LENGTH_SHORT).show()
    }

    var gmwebUrl by mutableStateOf(prefs.gmwebUrl)
        private set

    /**
     * Saves the GMweb-API base URL for the pull bridge and restarts the poller
     * so the change takes effect without toggling the whole gateway.
     */
    fun saveGmwebUrl(newUrl: String) {
        val v = newUrl.trim().trimEnd('/')
        try {
            prefs.gmwebUrl = v
            gmwebUrl = prefs.gmwebUrl
            addLog(
                if (gmwebUrl.isBlank()) "🔌 GMweb pull bridge disabled"
                else "🔌 GMweb pull bridge URL saved: $gmwebUrl (takes effect on gateway start/restart)"
            )
            Toast.makeText(getApplication(), "GMweb URL saved", Toast.LENGTH_SHORT).show()
        } catch (e: IllegalArgumentException) {
            Toast.makeText(getApplication(), e.message, Toast.LENGTH_LONG).show()
        }
    }

    fun saveBindAllInterfaces(bindAll: Boolean) {
        bindAllInterfaces = bindAll
        prefs.bindAllInterfaces = bindAll
        addLog(
            if (bindAll) "🌐 Server will bind to all interfaces (0.0.0.0) on next start"
            else "🏠 Server will bind to the LAN address only (recommended) on next start"
        )
    }

    fun savePort(newPort: Int) {
        if (newPort in 1024..65535) {
            port = newPort
            prefs.port = newPort
            addLog("⚙️ Set Gateway Port: $newPort")
            Toast.makeText(getApplication(), "Port updated (restart server to apply)", Toast.LENGTH_SHORT).show()
        }
    }

    fun copyToClipboard(label: String, text: String) {
        val cm = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(getApplication(), "Copied $label to clipboard", Toast.LENGTH_SHORT).show()
    }

    /** Shares the whole live-log buffer through the Android share sheet. */
    fun shareLogs() {
        val text = logs.joinToString("\n")
        if (text.isBlank()) {
            Toast.makeText(getApplication(), "No logs to share yet", Toast.LENGTH_SHORT).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Messages — Gateway live logs")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        getApplication<Application>().startActivity(
            Intent.createChooser(send, "Share gateway logs").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    fun clearLogs() {
        logs.clear()
    }

    private fun addLog(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logs.add(0, "[$time] $msg")
    }
}
