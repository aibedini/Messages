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
import com.autonomousone.messages.BuildConfig
import com.autonomousone.messages.data.DeadLetterBreakdownRow
import com.autonomousone.messages.data.DeadLetterSummary
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.data.TelephonySyncCoordinator
import com.autonomousone.messages.gateway.AndroidGatewayProbeIo
import com.autonomousone.messages.gateway.BackendClient
import com.autonomousone.messages.gateway.ConnectionSupervisor
import com.autonomousone.messages.gateway.GatewayPreferences
import com.autonomousone.messages.gateway.GatewayServer
import com.autonomousone.messages.gateway.GatewayService
import com.autonomousone.messages.gateway.GmwebInputError
import com.autonomousone.messages.gateway.GmwebServerNormalization
import com.autonomousone.messages.gateway.HeartbeatManager
import com.autonomousone.messages.gateway.RegistrationManager
import com.autonomousone.messages.repository.GatewaySyncRepository
import com.autonomousone.messages.sync.diagnostics.SyncDiagnostics
import com.autonomousone.messages.sync.diagnostics.SyncDiagnosticsCollector
import com.autonomousone.messages.gateway.health.GatewayConnectivityProbe
import com.autonomousone.messages.gateway.health.GatewayConnectivityResult
import com.autonomousone.messages.gateway.health.GatewayDiagnosticReport
import com.autonomousone.messages.gateway.health.GatewayEndpoint
import com.autonomousone.messages.gateway.health.GatewayHealthRecorder
import com.autonomousone.messages.gateway.health.GatewayHealthSnapshot
import com.autonomousone.messages.gateway.health.GatewayLog
import com.autonomousone.messages.gateway.health.GatewayLogEntry
import com.autonomousone.messages.gateway.health.GatewayLogFilter
import com.autonomousone.messages.gateway.health.GatewayLogSeverity
import com.autonomousone.messages.gateway.health.GatewayLogSubsystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    // ═══════════════════════════════════════════════════════════════════════════
    // v3.4.x P0 — the multi-dimension health surface
    //
    // The screen reads a SNAPSHOT of independent dimensions plus a live log, instead of the
    // single green/amber light that used to mean "the components were started". The
    // derivation lives in GatewayHealthRules (pure, tested); this only republishes it.
    // ═══════════════════════════════════════════════════════════════════════════

    /** True from the moment Reconnect is tapped until the request has been dispatched. */
    var reconnecting by mutableStateOf(false)
        private set

    /**
     * A 1-second tick, so the card's RELATIVE times ("last poll 4m ago") and its FRESHNESS
     * transitions stay true while the screen is open.
     *
     * Without it the card would only refresh when a component recorded something, so a
     * bridge that went silent would keep showing "3s ago" indefinitely — the exact class of
     * stale-green the whole P0 is about. It costs a counter increment, matching the 1-second
     * supervisor tick this ViewModel already runs.
     */
    var healthTick by mutableIntStateOf(0)
        private set

    /** The registry's change counter, so a recorded transition recomposes immediately. */
    val healthRevision: kotlinx.coroutines.flow.StateFlow<Long> = GatewayHealthRecorder.revision

    /** The latest explicit diagnostic run, or null when it has not been run. */
    var diagnosticResult by mutableStateOf<GatewayConnectivityResult?>(null)
        private set

    var diagnosticRunning by mutableStateOf(false)
        private set

    /**
     * The dead-letter aggregate, refreshed alongside diagnostics.
     *
     * AGGREGATE ONLY, and read-only by construction: it answers "are these historical leftovers
     * or an active defect?" without reading a payload and without deleting anything.
     */
    var deadLetterBreakdown by mutableStateOf<List<DeadLetterBreakdownRow>>(emptyList())
        private set

    var deadLetterSummary by mutableStateOf(DeadLetterSummary())
        private set

    /**
     * Diagnostics V2, or null before it has been collected.
     *
     * Nullable rather than defaulting to a placeholder: "we have not looked yet" is not the same
     * as "blocked", and a placeholder would print a cause that was never observed.
     */
    var syncDiagnostics by mutableStateOf<SyncDiagnostics?>(null)
        private set

    /**
     * Collects the sync diagnostics.
     *
     * Runs on the caller's IO dispatcher: the collector touches Room and the Keystore.
     */
    private suspend fun refreshSyncDiagnostics() {
        val collected = runCatching {
            SyncDiagnosticsCollector(getApplication()).collect()
        }.getOrNull()
        withContext(Dispatchers.Main) { syncDiagnostics = collected }
    }

    /** Refreshes the aggregate-only dead-letter view. Never mutates the outbox. */
    private suspend fun refreshDeadLetterBreakdown() {
        val repository = GatewaySyncRepository(MessagesDatabase.get(getApplication()))
        val rows = runCatching { repository.deadLetterBreakdown() }.getOrDefault(emptyList())
        val summary = runCatching { repository.deadLetterSummary() }.getOrDefault(DeadLetterSummary())
        withContext(Dispatchers.Main) {
            deadLetterBreakdown = rows
            deadLetterSummary = summary
        }
    }

    /** The current dimensions with the verdict derived from them. */
    fun gatewayHealth(): GatewayHealthSnapshot = GatewayHealthRecorder.snapshot()

    /**
     * Reconcile the mirror against the outbox (mission §34, "manual Re-check").
     *
     * Bounded (48 h window, 500 rows) and idempotent. Reports the outcome in both directions,
     * because "nothing was missing" is the reassuring answer and "recovered N" means the safety net
     * caught something real.
     */
    private suspend fun recheckMissedMessages() {
        val result = runCatching {
            TelephonySyncCoordinator.get(getApplication()).reconcileMissingEvents()
        }.getOrNull()
        when {
            result == null -> addLog("⚠️ Re-check could not complete")
            result.foundGap -> addLog(
                "♻️ Re-check recovered ${result.recovered} message(s) that had not reached the outbox"
            )
            else -> addLog("✅ Re-check: no missed messages in ${result.examined} recent message(s)")
        }
    }

    /** The live feed for one filter chip. Advanced rows are opt-in. */
    fun gatewayLogFeed(
        filter: GatewayLogFilter,
        includeAdvanced: Boolean = false
    ): List<GatewayLogEntry> = GatewayLog.buffer.visible(filter, includeAdvanced)

    /**
     * Runs the full staged connectivity check.
     *
     * EXPLICIT USER ACTION ONLY. This resolves DNS, opens a socket, performs a TLS handshake
     * and makes two HTTPS requests, so it must never be put on a timer — the continuous half
     * of the health system is [gatewayHealth], which the live components feed for free.
     *
     * A reconnect is deliberately NOT implied: the probe reports, the user decides.
     */
    fun runDiagnostics() {
        if (diagnosticRunning) return
        val endpoint = GatewayEndpoint.parse(prefs.gmwebUrl)
        if (endpoint == null) {
            diagnosticResult = null
            addLog("⚠️ No usable GMweb URL configured — nothing to diagnose")
            return
        }
        diagnosticRunning = true
        viewModelScope.launch(Dispatchers.IO) {
            addLog("🔎 Running gateway diagnostics against ${endpoint.displayHost}…")
            refreshDeadLetterBreakdown()
            refreshSyncDiagnostics()
            // Mission §34: "manual Re-check". Running diagnostics is already the user saying
            // "something looks wrong" — reconciling the mirror against the outbox here is what can
            // actually recover a message whose notification was missed, rather than only describing
            // the problem. Bounded, idempotent, and reported either way.
            recheckMissedMessages()
            val result = runCatching {
                GatewayConnectivityProbe(
                    io = AndroidGatewayProbeIo(getApplication(), prefs),
                    endpoint = endpoint
                ).run()
            }
            diagnosticRunning = false
            result.fold(
                onSuccess = { outcome ->
                    diagnosticResult = outcome
                    GatewayLog.record(
                        severity = if (outcome.passed) {
                            GatewayLogSeverity.SUCCESS
                        } else {
                            GatewayLogSeverity.ERROR
                        },
                        subsystem = GatewayLogSubsystem.SUPERVISOR,
                        code = "DIAGNOSTICS_DONE",
                        title = if (outcome.passed) {
                            "Diagnostics passed"
                        } else {
                            "Diagnostics stopped at ${outcome.firstFailure?.stage?.name}"
                        },
                        detail = outcome.firstFailure?.detail
                    )
                    addLog(
                        if (outcome.passed) "✅ Diagnostics passed"
                        else "❌ Diagnostics: ${outcome.firstFailure?.stage} — ${outcome.firstFailure?.detail ?: ""}"
                    )
                },
                onFailure = { error ->
                    GatewayLog.resumeFailed("Diagnostics failed to run", error.message)
                    addLog("❌ Diagnostics failed to run: ${error.message ?: "unknown error"}")
                }
            )
        }
    }

    /**
     * The shareable report: the live dimensions, the last probe run (if any), and the version
     * and mode context, redacted by [GatewayDiagnosticReport].
     */
    fun buildDiagnosticReport(): String = GatewayDiagnosticReport.render(
        snapshot = GatewayHealthRecorder.snapshot(),
        probe = diagnosticResult,
        appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        deliveryMode = "LEGACY_PULL",
        supervisorState = GatewayService.supervisorState.name,
        gatewayDesired = prefs.gatewayDesiredEnabled && prefs.hasGatewayConsent,
        // Aggregate only — nothing deleted, no payload read.
        deadLetters = deadLetterBreakdown,
        deadLetterSummary = deadLetterSummary,
        // The one actionable blocker, when it has been read (mission §57).
        diagnostics = syncDiagnostics
    )

    /** Copies the redacted report to the clipboard. */
    fun copyDiagnosticReport() {
        val clipboard = getApplication<Application>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("GMweb gateway diagnostic", buildDiagnosticReport()))
        addLog("📋 Diagnostic report copied")
    }

    /** Shares the redacted report through the system sheet. */
    fun shareDiagnosticReport() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "GMweb Gateway Diagnostic")
            putExtra(Intent.EXTRA_TEXT, buildDiagnosticReport())
        }
        getApplication<Application>().startActivity(
            Intent.createChooser(intent, "Share diagnostic report")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /**
     * The sanitized JSON export (mission §58).
     *
     * Sanitization is structural, not a redaction pass: [SyncDiagnostics] has no field that can
     * hold a message body, an address, a token or key material, so this cannot leak by
     * construction. The "not collected yet" case says so rather than emitting a plausible-looking
     * empty document.
     */
    fun buildSyncDiagnosticsJson(): String =
        syncDiagnostics?.toSanitizedJson()
            ?: """{"error":"sync diagnostics not collected yet - run diagnostics first"}"""

    /** Copies the sanitized JSON export. */
    fun copySyncDiagnosticsJson() {
        val clipboard = getApplication<Application>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText("GMweb sync diagnostics", buildSyncDiagnosticsJson())
        )
        addLog("📋 Sync diagnostics JSON copied")
    }

    init {
        observeLogs()
        observeHeartbeatState()
        observeSupervisorState()
        observeHealthTick()
        refreshStatus()
    }

    /** Drives the card's relative times and freshness while this screen is alive. */
    private fun observeHealthTick() {
        viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                healthTick++
            }
        }
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

    /**
     * Transport-agnostic manual reconnect (v2.6.7 goal #10): the supervisor
     * wake-up heals EVERY mode — GMweb pull bridge, LAN server, cloud.
     * The cloud register() call below is a fast-path only for phones that
     * actually use a cloud backend (registered or one configured); a pure
     * android-pull gateway must never see a "Reconnect failed" from it.
     */
    fun reconnectNow() {
        if (!prefs.hasGatewayConsent) {
            showConsentDialog = true
            return
        }
        // v3.4.x P0: the card must say RECONNECTING immediately. A reconnect is a request
        // to try again — it is not evidence that anything worked, so this never sets a
        // healthy state; only a real poll result may do that.
        reconnecting = true
        viewModelScope.launch(Dispatchers.IO) {
            addLog("🔄 Manual reconnect triggered...")
            GatewayLog.record(
                severity = GatewayLogSeverity.INFO,
                subsystem = GatewayLogSubsystem.SUPERVISOR,
                code = "RECONNECT_REQUESTED",
                title = "Reconnect requested"
            )
            GatewayService.reconnectNow(getApplication())
            if (prefs.gmwebServerOrigin.isNotBlank()) {
                cloudConnectionState = HeartbeatManager.ConnectionState.CONNECTING
                val success = registrationManager.register()
                if (success) {
                    refreshStatus()
                    cloudConnectionState = HeartbeatManager.ConnectionState.CONNECTED
                    // NOT "Reconnected": a successful identity enrollment proves the device key
                    // and the control plane, and says NOTHING about whether /gateway/pull can
                    // deliver a task. Claiming end-to-end reconnection from this one step is
                    // exactly how the old screen stayed green over a dead delivery bridge —
                    // the overall verdict belongs to the health model.
                    addLog("✅ Device identity authenticated (control plane). Delivery status is in Gateway health.")
                } else {
                    addLog("❌ Identity enrollment failed — check the server address and the device key")
                    cloudConnectionState = HeartbeatManager.ConnectionState.ERROR
                }
            } else {
                addLog("✅ Reconnect requested — supervisor reconciling")
            }
            reconnecting = false
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
        // NO part of the key is logged (mission §43). A prefix and suffix is not "safe": it is
        // enough to confirm a guessed key or to correlate one across logs. The value is in the
        // field on screen, which is where a user needs it.
        addLog("🔑 Generated a new API key")
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
        // No part of the key is logged (mission §43).
        addLog("🔑 API key updated")
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

    /**
     * THE one GMweb origin (v3.4.6). Everything the screen shows about the server — the
     * origin, the derived API base and the panel URL — comes from this one value.
     */
    var gmwebServerOrigin by mutableStateOf(prefs.gmwebServerOrigin)
        private set

    /** `https://host/app`, or null when no server is configured. */
    var gmwebPanelUrl by mutableStateOf(prefs.gmwebServerProfile()?.dashboardUrl)
        private set

    /** @deprecated alias kept for the existing UI cards; always equals [gmwebServerOrigin]. */
    var gmwebUrl by mutableStateOf(prefs.gmwebServerOrigin)
        private set

    /**
     * Saves the GMweb server and makes the change take effect EVERYWHERE, with no restart.
     *
     * The user may paste the panel URL they actually have (`https://host/app`); the origin is
     * derived and stored, and the panel URL is kept only for the "Open panel" button.
     *
     * Reconciling every client is the point of this method. Saving used to update the pull URL
     * alone, which left the control plane, the event uploader and enrollment pointed at the
     * previous server — so the phone could pull from one GMweb and upload to another.
     */
    fun saveGmwebServer(input: String) {
        when (val result = prefs.saveGmwebServer(input)) {
            is GmwebServerNormalization.Valid -> {
                val profile = result.profile
                gmwebServerOrigin = profile.origin
                gmwebUrl = profile.origin
                gmwebPanelUrl = profile.dashboardUrl
                // The health card's target must move with the server, or the next diagnostic
                // run would test the address the user just replaced.
                GatewayHealthRecorder.setEndpointUrl(profile.origin)
                addLog("🔌 GMweb server saved: ${profile.origin}")
                // ONE nudge reconciles every GMweb client: heartbeat, trust publisher, event
                // uploader, and the delivery poller (restarting it if its loop had exited).
                GatewayService.reconnectNow(getApplication())
                Toast.makeText(
                    getApplication(),
                    "GMweb server saved — reconnecting all clients",
                    Toast.LENGTH_SHORT
                ).show()
            }
            is GmwebServerNormalization.Invalid -> {
                if (input.isBlank()) {
                    gmwebServerOrigin = ""
                    gmwebUrl = ""
                    gmwebPanelUrl = null
                    GatewayHealthRecorder.setEndpointUrl("")
                    addLog("🔌 GMweb server cleared — gateway delivery disabled")
                    Toast.makeText(getApplication(), "GMweb server cleared", Toast.LENGTH_SHORT).show()
                } else {
                    val reason = gmwebInputMessage(result.error)
                    addLog("⚠️ GMweb server not saved: $reason")
                    Toast.makeText(getApplication(), reason, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Opens the GMweb panel in a browser — the address the user recognizes. */
    fun openGmwebPanel() {
        val url = gmwebPanelUrl ?: return
        runCatching {
            getApplication<Application>().startActivity(
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { addLog("⚠️ No browser available to open $url") }
    }

    /**
     * One sentence per rejection, so the user is told what to fix instead of "invalid URL".
     */
    private fun gmwebInputMessage(error: GmwebInputError): String = when (error) {
        GmwebInputError.BLANK -> "Enter your GMweb server address"
        GmwebInputError.MALFORMED -> "That does not look like a URL"
        GmwebInputError.MISSING_SCHEME -> "Include https:// at the start"
        GmwebInputError.INSECURE_SCHEME -> "Only https:// is accepted"
        GmwebInputError.MISSING_HOST -> "That URL has no server name"
        GmwebInputError.EMBEDDED_CREDENTIALS -> "Remove the username and password from the URL"
        GmwebInputError.HAS_QUERY -> "Remove the ?query part of the URL"
        GmwebInputError.HAS_FRAGMENT -> "Remove the #fragment part of the URL"
        GmwebInputError.UNSUPPORTED_PATH -> "Use the panel address, e.g. https://your-server/app"
    }

    /**
     * @deprecated Kept so the existing screen keeps compiling. Routes into [saveGmwebServer], so
     * the pull URL and the control-plane URL can never diverge again.
     */
    @Deprecated("Use saveGmwebServer", ReplaceWith("saveGmwebServer(newUrl)"))
    fun saveGmwebUrl(newUrl: String) = saveGmwebServer(newUrl)

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
