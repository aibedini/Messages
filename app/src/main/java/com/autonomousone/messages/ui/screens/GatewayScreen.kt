package com.autonomousone.messages.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Webhook
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.autonomousone.messages.gateway.HeartbeatManager
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.autonomousone.messages.viewmodel.GatewayViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GatewayScreen(
    navController: NavController
) {
    val viewModel: GatewayViewModel = viewModel()

    LaunchedEffect(Unit) {
        viewModel.refreshStatus()
    }

    var tempWebhook by remember(viewModel.webhookUrl) { mutableStateOf(viewModel.webhookUrl) }
    var tempWebhookSecret by remember(viewModel.webhookSecret) { mutableStateOf(viewModel.webhookSecret) }

    // ── Advanced transport modes (Cloud backend / LAN API / Webhook) ─────────
    // GMweb pull bridge is the ONLY supported way to connect a server for now.
    // Flip to true to re-expose: Cloud Backend Gateway card, API Key auth card,
    // REST endpoints card and Incoming SMS Webhook card.
    val showAdvancedGatewayModes = false
    if (showAdvancedGatewayModes) {
        // referenced here so the state stays wired when re-enabled
        tempWebhook.hashCode(); tempWebhookSecret.hashCode()
    }

    if (viewModel.showConsentDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissGatewayConsent,
            title = { Text("Enable SMS Gateway?") },
            text = {
                Text(
                    "When enabled, the gateway can send the sender phone number, full message text, " +
                        "message time and device details to ${viewModel.backendUrl} and to any HTTPS webhook " +
                        "you configure. Authenticated gateway clients can also ask this phone to send SMS, " +
                        "which may incur carrier charges. Data is sent only while the gateway is enabled. Revoking consent " +
                        "stops the service, registration, heartbeat and message forwarding."
                )
            },
            confirmButton = {
                Button(onClick = viewModel::acceptGatewayConsentAndStart) { Text("Agree and enable") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissGatewayConsent) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "SMS Gateway",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp)
        ) {
            // ── 0. Setup steps header (what is this page? what do I do?) ────
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("How this works", fontWeight = FontWeight.Bold)
                        StepRow(step = 1, text = "Turn the gateway ON below (first time asks for privacy consent).")
                        StepRow(step = 2, text = "Connect a server: GMweb bridge (easiest) or Cloud backend or LAN.")
                        StepRow(step = 3, text = "Share the API key with that server so it can authenticate.")
                    }
                }
            }

            // ── 0. Privacy consent ──────────────────────────────────────────
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (viewModel.hasGatewayConsent)
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(22.dp),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.PrivacyTip,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Step 1 · Privacy", fontWeight = FontWeight.Bold)
                        }
                        Text(
                            if (viewModel.hasGatewayConsent)
                                "Consent active. SMS data only leaves the phone while the gateway is ON."
                            else
                                "The gateway is off and nothing leaves your phone. Turning it on requires one-time privacy consent.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (viewModel.hasGatewayConsent) {
                            OutlinedButton(onClick = viewModel::revokeGatewayConsent) {
                                Text("Revoke consent and stop Gateway")
                            }
                        }
                    }
                }
            }

            if (showAdvancedGatewayModes) {
            item {
                CloudConnectionCard(
                    backendUrl = viewModel.backendUrl,
                    gatewayId = viewModel.gatewayId,
                    isRegistered = viewModel.isRegistered,
                    connectionState = viewModel.cloudConnectionState,
                    lastHeartbeatAt = viewModel.lastHeartbeatAt,
                    registrationSecret = viewModel.registrationSecret,
                    onReconnect = { viewModel.reconnectNow() },
                    onCopyGatewayId = { viewModel.copyToClipboard("Gateway ID", viewModel.gatewayId) },
                    onSaveRegistrationSecret = { viewModel.saveRegistrationSecret(it) }
                )
            }

            }

            // ── 2. GMweb pull bridge (Step 2 · recommended way to connect) ───
            // (advanced cards hidden above; see showAdvancedGatewayModes)
            item {
                var editingUrl by rememberSaveable(viewModel.gmwebUrl) {
                    mutableStateOf(viewModel.gmwebUrl)
                }
                var editingKey by rememberSaveable(viewModel.apiKey) {
                    mutableStateOf("")
                }
                var showKey by rememberSaveable { mutableStateOf(false) }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (viewModel.gmwebUrl.isNotBlank())
                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.SwapVert,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Step 2 · Connect GMweb server", fontWeight = FontWeight.Bold)
                        }
                        Text(
                            "Recommended: your phone dials OUT to the GMweb server over HTTPS — no tunnel, no static IP, survives mobile IP changes. The server queues SMS jobs; this phone pulls and sends them.",
                            style = MaterialTheme.typography.bodySmall
                        )

                        OutlinedTextField(
                            value = editingUrl,
                            onValueChange = { editingUrl = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Server URL") },
                            placeholder = { Text("https://gmweb.example.com") },
                            isError = editingUrl.isNotBlank() && !editingUrl.startsWith("https://"),
                            supportingText = {
                                if (editingUrl.isNotBlank() && !editingUrl.startsWith("https://"))
                                    Text("Must start with https://")
                            }
                        )

                        // Shared secret: must MATCH the server's GMWEB_ANDROID_DEVICE_KEY.
                        OutlinedTextField(
                            value = if (showKey) editingKey else "",
                            onValueChange = { editingKey = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Shared API key") },
                            placeholder = { Text("Paste GMWEB_ANDROID_DEVICE_KEY from the server") },
                            visualTransformation = if (showKey) androidx.compose.ui.text.input.VisualTransformation.None
                                else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showKey = !showKey }) {
                                    Icon(
                                        if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (showKey) "Hide" else "Show",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            },
                            supportingText = {
                                Text(
                                    if (editingKey.isBlank())
                                        "Current key: ${viewModel.apiKey.take(7)}…${viewModel.apiKey.takeLast(4)} — the server must use the SAME value."
                                    else "Saving will replace the phone's key with yours.",
                                    fontSize = 11.sp
                                )
                            }
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    viewModel.saveGmwebUrl(editingUrl)
                                    // Only replace the key when the user typed one.
                                    if (editingKey.isNotBlank()) viewModel.updateApiKey(editingKey)
                                    editingKey = ""
                                },
                                enabled = editingUrl.isNotBlank()
                            ) {
                                Text(if (viewModel.gmwebUrl.isBlank()) "Connect" else "Update")
                            }
                            if (viewModel.gmwebUrl.isNotBlank()) {
                                OutlinedButton(onClick = { editingUrl = ""; viewModel.saveGmwebUrl("") }) {
                                    Text("Disconnect")
                                }
                            }
                        }

                        Text(
                            "On the server (.env): GMWEB_ANDROID_DEVICE_KEY=<the same key> · Android device appears online within ~25s of saving.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            // ── 1. Server Status Card ───────────────────────────────────────
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (viewModel.isServerRunning)
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                        else
                            MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val gwState = viewModel.gatewayState
                                Box(
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clip(CircleShape)
                                        .background(
                                            when (gwState) {
                                                com.autonomousone.messages.gateway.ConnectionSupervisor.State.CONNECTED -> Color(0xFF10B981)
                                                com.autonomousone.messages.gateway.ConnectionSupervisor.State.CONNECTING,
                                                com.autonomousone.messages.gateway.ConnectionSupervisor.State.RECONNECTING,
                                                com.autonomousone.messages.gateway.ConnectionSupervisor.State.WAITING_FOR_NETWORK -> Color(0xFFF59E0B)
                                                else -> Color(0xFFEF4444)
                                            }
                                        )
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = when (gwState) {
                                        com.autonomousone.messages.gateway.ConnectionSupervisor.State.CONNECTED -> "Gateway Active"
                                        com.autonomousone.messages.gateway.ConnectionSupervisor.State.WAITING_FOR_NETWORK -> "Waiting for network…"
                                        com.autonomousone.messages.gateway.ConnectionSupervisor.State.CONNECTING -> "Starting gateway…"
                                        com.autonomousone.messages.gateway.ConnectionSupervisor.State.RECONNECTING -> "Reconnecting…"
                                        com.autonomousone.messages.gateway.ConnectionSupervisor.State.ERROR -> "Gateway error — retrying"
                                        com.autonomousone.messages.gateway.ConnectionSupervisor.State.DISABLED -> "Gateway Stopped"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            Switch(
                                // Bound to the USER's intent, not runtime truth:
                                // an offline gateway must still show "on" while
                                // waiting for network (otherwise the switch
                                // fights the user during a WiFi blip).
                                checked = viewModel.gatewayDesired || viewModel.isServerRunning,
                                onCheckedChange = { viewModel.toggleServer(it) },
                                modifier = Modifier.semantics {
                                    contentDescription = "SMS Gateway"
                                    stateDescription = if (viewModel.isServerRunning) "Running" else "Stopped"
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = MaterialTheme.colorScheme.primary
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Lan,
                                contentDescription = "IP",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Base URL: http://${viewModel.localIpAddress}:${viewModel.port}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Bind to all interfaces",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = if (viewModel.bindAllInterfaces)
                                        "Reachable on every network interface (0.0.0.0)"
                                    else
                                        "LAN address only — recommended",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                            Switch(
                                checked = viewModel.bindAllInterfaces,
                                onCheckedChange = { viewModel.saveBindAllInterfaces(it) }
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // ── Auto Reconnect (v2.6.7 goal #8): visible in the
                        // MAIN card, not hidden in Advanced. The supervisor
                        // watches validated-network flaps, resets the retry
                        // ladder on recovery, and wakes the heartbeat + poller
                        // in every transport (GMweb pull, LAN, cloud).
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Sync,
                                contentDescription = null,
                                tint = if (viewModel.isServerRunning)
                                    Color(0xFF10B981)
                                else MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Auto Reconnect",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    if (viewModel.isServerRunning)
                                        "On — retries immediately when the network returns"
                                    else
                                        "Ready — starts with the gateway and self-heals while it is on",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = { viewModel.reconnectNow() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                Icons.Default.Sync,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Reconnect now")
                        }
                    }
                }
            }

            if (showAdvancedGatewayModes) {
            // ── 2. API Key Authentication Card ─────────────────────────────
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Key,
                                contentDescription = "API Key",
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "API Key Authentication",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = viewModel.apiKey,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Row {
                                IconButton(
                                    onClick = { viewModel.copyToClipboard("API Key", viewModel.apiKey) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "Copy API Key",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { viewModel.generateNewApiKey() },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Generate New Key",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Include header 'X-API-Key: ${viewModel.apiKey}' in REST API calls",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            }

            // ── 3. REST API Endpoints Card ─────────────────────────────────
            item {
                val isConnected = viewModel.isRegistered || viewModel.gatewayId.isNotBlank() || viewModel.isServerRunning || viewModel.cloudConnectionState == HeartbeatManager.ConnectionState.CONNECTED
                val cloudUrl = if (viewModel.backendUrl.isNotBlank()) viewModel.backendUrl else "https://gaitway.autonomousone.in"
                val effectiveBaseUrl = if (isConnected) cloudUrl else "http://${viewModel.localIpAddress}:${viewModel.port}"

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "REST API Endpoints",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (isConnected) Color(0xFF10B981).copy(alpha = 0.15f) else Color.Gray.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = if (isConnected) "Cloud Mode" else "LAN Mode",
                                    color = if (isConnected) Color(0xFF10B981) else Color.Gray,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Text(
                            text = "Base API URL: $effectiveBaseUrl",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 4.dp)
                        )

                        if (isConnected && viewModel.localIpAddress.isNotBlank()) {
                            Text(
                                text = "LAN Fallback: http://${viewModel.localIpAddress}:${viewModel.port}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        EndpointItem(
                            method = "POST",
                            path = "/api/v1/sms/send",
                            desc = "Send SMS text message",
                            baseUrl = effectiveBaseUrl,
                            onCopy = { url -> viewModel.copyToClipboard("SMS Endpoint", url) }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        EndpointItem(
                            method = "POST",
                            path = "/api/v1/mms/send",
                            desc = "Send MMS image message",
                            baseUrl = effectiveBaseUrl,
                            onCopy = { url -> viewModel.copyToClipboard("MMS Endpoint", url) }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        EndpointItem(
                            method = "GET",
                            path = "/api/v1/sms/inbox",
                            desc = "Get recent inbox messages",
                            baseUrl = effectiveBaseUrl,
                            onCopy = { url -> viewModel.copyToClipboard("Inbox Endpoint", url) }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        EndpointItem(
                            method = "GET",
                            path = "/api/v1/status",
                            desc = "Gateway battery & network status",
                            baseUrl = effectiveBaseUrl,
                            onCopy = { url -> viewModel.copyToClipboard("Status Endpoint", url) }
                        )
                    }
                }
            }

            if (showAdvancedGatewayModes) {
            // ── 4. Webhook Settings Card ───────────────────────────────────
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Webhook,
                                contentDescription = "Webhook",
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Incoming SMS Webhook",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = tempWebhook,
                            onValueChange = { tempWebhook = it },
                            placeholder = { Text("https://your-server.com/sms-webhook") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp)
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = tempWebhookSecret,
                            onValueChange = { tempWebhookSecret = it },
                            placeholder = { Text("Signing secret (optional — enables X-Signature HMAC)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp)
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "Payloads are signed with HMAC-SHA256 over \"timestamp.body\" — verify the X-Signature header on your server.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Button(
                            onClick = { viewModel.saveWebhookUrl(tempWebhook) },
                            modifier = Modifier.align(Alignment.End),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Save, contentDescription = "Save", modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Save Webhook")
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Button(
                            onClick = { viewModel.saveWebhookSecret(tempWebhookSecret) },
                            modifier = Modifier.align(Alignment.End),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Key, contentDescription = "Save Secret", modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Save Secret")
                        }
                    }
                }
            }

            }

            // ── 5. Live Logs Feed Card ───────────────────────────────────────
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Live Server Logs",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Row {
                                IconButton(onClick = { viewModel.shareLogs() }, modifier = Modifier.size(28.dp)) {
                                    Icon(
                                        imageVector = Icons.Default.Share,
                                        contentDescription = "Share logs",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                IconButton(onClick = { viewModel.clearLogs() }, modifier = Modifier.size(28.dp)) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Clear logs",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        if (viewModel.logs.isEmpty()) {
                            Text(
                                text = "No server activity yet.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(140.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF1E1E2E))
                                    .padding(10.dp)
                            ) {
                                LazyColumn {
                                    items(viewModel.logs) { logLine ->
                                        Text(
                                            text = logLine,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            color = Color(0xFFA6ADC8),
                                            lineHeight = 15.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EndpointItem(
    method: String,
    path: String,
    desc: String,
    baseUrl: String,
    onCopy: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (method == "POST") Color(0xFF10B981) else Color(0xFF3B82F6)
                ) {
                    Text(
                        text = method,
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = path,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = desc,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        IconButton(
            onClick = { onCopy("$baseUrl$path") },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = "Copy Endpoint",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun CloudConnectionCard(
    backendUrl: String,
    gatewayId: String,
    isRegistered: Boolean,
    connectionState: com.autonomousone.messages.gateway.HeartbeatManager.ConnectionState,
    lastHeartbeatAt: Long,
    registrationSecret: String,
    onReconnect: () -> Unit,
    onCopyGatewayId: () -> Unit,
    onSaveRegistrationSecret: (String) -> Unit
) {
    var tempRegistrationSecret by remember(registrationSecret) { mutableStateOf(registrationSecret) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (connectionState) {
                com.autonomousone.messages.gateway.HeartbeatManager.ConnectionState.CONNECTED ->
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                com.autonomousone.messages.gateway.HeartbeatManager.ConnectionState.CONNECTING ->
                    MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(
                                when (connectionState) {
                                    com.autonomousone.messages.gateway.HeartbeatManager.ConnectionState.CONNECTED -> Color(0xFF10B981)
                                    com.autonomousone.messages.gateway.HeartbeatManager.ConnectionState.CONNECTING -> Color(0xFFF59E0B)
                                    else -> Color(0xFFEF4444)
                                }
                            )
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Cloud Backend Gateway",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = when (connectionState) {
                                com.autonomousone.messages.gateway.HeartbeatManager.ConnectionState.CONNECTED -> "Online • Heartbeat active"
                                com.autonomousone.messages.gateway.HeartbeatManager.ConnectionState.CONNECTING -> "Connecting..."
                                com.autonomousone.messages.gateway.HeartbeatManager.ConnectionState.DISCONNECTED -> "Disconnected"
                                com.autonomousone.messages.gateway.HeartbeatManager.ConnectionState.ERROR -> "Connection Error"
                                else -> if (isRegistered) "Registered" else "Not Registered"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(onClick = onReconnect) {
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = "Sync / Reconnect",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Backend URL
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Cloud,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = backendUrl,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (isRegistered && gatewayId.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "ID: $gatewayId",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    IconButton(onClick = onCopyGatewayId, modifier = Modifier.size(24.dp)) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy ID",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = tempRegistrationSecret,
                onValueChange = { tempRegistrationSecret = it },
                placeholder = { Text("Registration secret (pairing code)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(14.dp)
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Sent as the X-Registration-Secret header. Set the same secret on your backend so only your device can register or re-register this gateway.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = { onSaveRegistrationSecret(tempRegistrationSecret) },
                modifier = Modifier.align(Alignment.End),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(imageVector = Icons.Default.Save, contentDescription = "Save", modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Save Secret")
            }
        }
    }
}

/** Numbered step indicator row used by the "How this works" card. */
@Composable
private fun StepRow(step: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = step.toString(),
                color = MaterialTheme.colorScheme.onPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

