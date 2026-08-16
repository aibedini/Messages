package com.autonomousone.messages.viewmodel

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import com.autonomousone.messages.gateway.GatewayPreferences
import com.autonomousone.messages.gateway.GatewayServer
import com.autonomousone.messages.gateway.GatewayService
import com.autonomousone.messages.gateway.HeartbeatManager
import com.autonomousone.messages.gateway.RegistrationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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
    var port by mutableIntStateOf(prefs.port)
    var apiKey by mutableStateOf(prefs.apiKey)
    var webhookUrl by mutableStateOf(prefs.webhookUrl)
    var localIpAddress by mutableStateOf(GatewayServer.getLocalIpAddress())
        private set

    // ── Cloud backend state (new) ──────────────────────────────────────────
    var backendUrl by mutableStateOf(prefs.backendUrl)
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
        refreshStatus()
    }

    fun refreshStatus() {
        isServerRunning = GatewayService.isServiceRunning
        localIpAddress = GatewayServer.getLocalIpAddress()
        gatewayId = prefs.gatewayId
        lastHeartbeatAt = prefs.lastHeartbeatAt
        isRegistered = prefs.isRegistered
        backendUrl = prefs.backendUrl
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
            prefs.port = port
            prefs.apiKey = apiKey
            GatewayService.startGateway(context)
        } else {
            GatewayService.stopGateway(context)
        }
        isServerRunning = enable
        refreshStatus()
    }

    fun reconnectNow() {
        viewModelScope.launch(Dispatchers.IO) {
            addLog("🔄 Manual reconnect triggered...")
            cloudConnectionState = HeartbeatManager.ConnectionState.CONNECTING
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

    fun generateNewApiKey() {
        val newKey = prefs.generateNewApiKey()
        apiKey = newKey
        addLog("🔑 Generated new API Key: $newKey")
    }

    fun saveWebhookUrl(newUrl: String) {
        webhookUrl = newUrl.trim()
        prefs.webhookUrl = webhookUrl
        addLog("🔗 Saved Webhook URL: $webhookUrl")
        Toast.makeText(getApplication(), "Webhook URL saved", Toast.LENGTH_SHORT).show()
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

    fun clearLogs() {
        logs.clear()
    }

    private fun addLog(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logs.add(0, "[$time] $msg")
    }
}
