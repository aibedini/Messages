package com.autonomousone.messages.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.data.TrustedDeviceEntity
import com.autonomousone.messages.security.PairingClient
import com.autonomousone.messages.security.TrustedDeviceRegistry
import com.autonomousone.messages.security.PairingEndpointResolver
import com.autonomousone.messages.utils.showBiometricPrompt
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * ADR-007 §3 — Linked devices screen: list + [Link new device] + QR scanner
 * + explicit confirmation + BiometricPrompt. NO silent approval.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkedDevicesScreen(navController: androidx.navigation.NavController) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity

    var setupMode by remember { mutableStateOf(false) }
    var setupClaim by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var step by remember { mutableStateOf("LIST") }
    // LINKED DEVICE CONTROL: the local Trust Registry is the source of truth
    // for the list — durable, survives restart, includes revocation state.
    var devices by remember { mutableStateOf<List<TrustedDeviceEntity>>(emptyList()) }
    var revokeTarget by remember { mutableStateOf<TrustedDeviceEntity?>(null) }
    var manageTarget by remember { mutableStateOf<TrustedDeviceEntity?>(null) }
    var editedCapabilities by remember { mutableStateOf(setOf<String>()) }
    val uiScope = rememberCoroutineScope()
    var scanned by remember { mutableStateOf<PairingClient.SessionInfo?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    suspend fun refreshDevices() {
        devices = MessagesDatabase.get(context).trustedDeviceDao().all()
    }
    LaunchedEffect(step, result) { refreshDevices() }
    LaunchedEffect(Unit) {
        while (true) {
            refreshDevices()
            delay(2_000)
        }
    }
    var historyFull by remember { mutableStateOf(true) }
    // ADR-006 Amendment: per-device sensitive grants (privacy-first OFF).
    var grantOtp by remember { mutableStateOf(false) }
    var grantBank by remember { mutableStateOf(false) }
    var grantReset by remember { mutableStateOf(false) }
    var grantAuth by remember { mutableStateOf(false) }
    var grantFinancial by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) step = "SCANNING"
        else result = "Camera permission is required to scan the QR"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Linked devices") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (step) {
                "LIST" -> {
                    Text(
                        "Sign in to your linked web devices from the browser. " +
                            "New devices are paired by scanning their QR code — trust " +
                            "always comes from THIS phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(onClick = {
                        setupMode = true
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    }) { Text("Enroll this phone as Primary") }
                    Button(
                        onClick = {
                            setupMode = false
                            val granted = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.CAMERA
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) step = "SCANNING"
                            else permissionLauncher.launch(Manifest.permission.CAMERA)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("+ Link new device") }
                    result?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    }
                    // ── Linked device registry (durable, from Room) ──────────
                    revokeTarget?.let { target ->
                        AlertDialog(
                            onDismissRequest = { revokeTarget = null },
                            title = { Text("Unlink device?") },
                            text = {
                                Text(
                                    "Access will end when this phone publishes the revocation. " +
                                        "Publication retries while offline. Messages already downloaded " +
                                        "or decrypted on that device cannot be erased remotely."
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    val t = target
                                    revokeTarget = null
                                    showBiometricPrompt(
                                        context as androidx.appcompat.app.AppCompatActivity,
                                        title = "Unlink device",
                                        subtitle = "Authenticate to revoke ${t.displayName}",
                                        onSuccess = {
                                            uiScope.launch {
                                              try {
                                                TrustedDeviceRegistry.recordRevocation(context, t.deviceId)
                                                refreshDevices()
                                                result = "🛡 ${t.displayName} revoked — publishing DEVICE_REVOKED"
                                              } catch (e: Exception) {
                                                result = "Could not save revocation: ${e.message}"
                                              }
                                            }
                                        },
                                        onError = { result = "Revocation cancelled" }
                                    )
                                }) { Text("Unlink") }
                            },
                            dismissButton = {
                                TextButton(onClick = { revokeTarget = null }) { Text("Cancel") }
                            }
                        )
                    }
                    manageTarget?.let { target ->
                        AlertDialog(
                            onDismissRequest = { manageTarget = null },
                            title = { Text(target.displayName) },
                            text = {
                                Column(Modifier.verticalScroll(rememberScrollState())) {
                                    Text(target.origin)
                                    Text("Updated: " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(target.updatedAt)))
                                    Text("Message access", fontWeight = FontWeight.SemiBold)
                                    deviceCapabilityLabels.forEach { (capability, label) ->
                                        SensitiveGrantRow(label, capability in editedCapabilities) { enabled ->
                                            editedCapabilities = if (enabled) editedCapabilities + capability else editedCapabilities - capability
                                        }
                                    }
                                    Text("Sensitive access also requires permission in this phone's privacy settings. Previously downloaded messages cannot be erased remotely.")
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    val caps = editedCapabilities.toList()
                                    showBiometricPrompt(
                                        context as androidx.appcompat.app.AppCompatActivity,
                                        title = "Change device access", subtitle = target.displayName,
                                        onSuccess = {
                                            uiScope.launch {
                                                try {
                                                    TrustedDeviceRegistry.recordCapabilityChange(context, target.deviceId, caps)
                                                    result = "Access change saved; awaiting server publication"
                                                    manageTarget = null
                                                    refreshDevices()
                                                } catch (e: Exception) { result = "Access change failed: ${e.message}" }
                                            }
                                        }, onError = { result = "Access change cancelled" }
                                    )
                                }) { Text("Save access") }
                            },
                            dismissButton = { TextButton(onClick = { manageTarget = null }) { Text("Cancel") } }
                        )
                    }
                    if (devices.isNotEmpty()) {
                        Text(
                            "Linked devices",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 16.dp)
                        )
                        devices.forEach { d ->
                            val statusLabel = when (d.status) {
                                TrustedDeviceEntity.STATUS_ACTIVE -> "Trusted"
                                TrustedDeviceEntity.STATUS_PENDING_PUBLICATION -> "Trusted · syncing"
                                TrustedDeviceEntity.STATUS_REVOKE_PENDING -> "Revoking…"
                                TrustedDeviceEntity.STATUS_REVOKED -> "Revoked"
                                else -> "Expired"
                            }
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(d.displayName, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "$statusLabel · ${d.origin.removePrefix("https://").take(28)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        deviceCapabilities(d.capabilitiesJson).joinToString(" · ") { deviceCapabilityLabels[it] ?: it.replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase) },
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        "History requested: " + if (d.historyGrant == "FULL_HISTORY") "Full history" else "From now on",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    if (d.status != TrustedDeviceEntity.STATUS_REVOKED &&
                                        d.status != TrustedDeviceEntity.STATUS_REVOKE_PENDING
                                    ) {
                                        if (d.status == TrustedDeviceEntity.STATUS_ACTIVE) {
                                            OutlinedButton(onClick = {
                                                editedCapabilities = deviceCapabilities(d.capabilitiesJson)
                                                manageTarget = d
                                            }) { Text("Manage") }
                                        }
                                        OutlinedButton(
                                            onClick = { revokeTarget = d },
                                            modifier = Modifier.padding(top = 8.dp)
                                        ) { Text("Unlink device") }
                                    }
                                }
                            }
                        }
                    }
                }

                "SETUP_CONFIRM" -> {
                    Text("Enroll this phone as the primary device for ${setupClaim?.optString("apiOrigin")}? Existing browser links will be signed out.")
                    Button(onClick = { step = "ENROLLING_PRIMARY" }) { Text("Enroll this phone as Primary") }
                    OutlinedButton(onClick = { setupClaim = null; step = "LIST" }) { Text("Cancel") }
                }
                "ENROLLING_PRIMARY" -> {
                    Text("Enrolling this phone...")
                    LaunchedEffect(Unit) {
                        val ok = runCatching {
                            val prefs = com.autonomousone.messages.gateway.GatewayPreferences(context)
                            com.autonomousone.messages.gateway.RegistrationManager(context, prefs,
                                com.autonomousone.messages.gateway.BackendClient(prefs)).enrollPrimary(checkNotNull(setupClaim))
                        }.getOrDefault(false)
                        setupClaim = null
                        result = if (ok) "Primary enrollment complete. You can now scan a browser pairing QR." else "Enrollment failed. Create a new phone setup QR in the dashboard."
                        step = "LIST"
                    }
                }

                "SCANNING" -> {
                    Text(if (setupMode) "Scan the phone setup QR from the dashboard" else "Scan the browser pairing QR")
                    Box(modifier = Modifier.fillMaxWidth().height(360.dp)) {
                        // REVIEW FIX: onQr returns Boolean — true = accepted
                        // (scanner stops via clearAnalyzer), false = keep
                        // scanning. No latch needed at the call site.
                        QrCameraView(
                            onQr = { raw ->
                                // Never log the QR: authenticated sessions contain a
                                // short-lived identity bootstrap capability.
                                android.util.Log.i("QR_SCAN", "pairing payload received")
                                if (setupMode) {
                                    val claim = runCatching { org.json.JSONObject(raw) }.getOrNull()
                                    if (claim == null || claim.optString("kind") != "PRIMARY_SETUP" ||
                                        claim.optString("protocol") != com.autonomousone.messages.security.PairingProtocol.PROTOCOL ||
                                        claim.optLong("expiresAt") <= System.currentTimeMillis() ||
                                        !PairingEndpointResolver.originMatches(context, claim.optString("apiOrigin"))) {
                                        cameraError = "Scan a current phone setup QR from your GMweb dashboard"
                                        return@QrCameraView false
                                    }
                                    setupClaim = claim
                                    step = "SETUP_CONFIRM"
                                    return@QrCameraView true
                                }
                                val info = PairingClient.parseQrPayload(raw)
                                if (info == null) {
                                    android.util.Log.w("QR_SCAN", "payload did not parse — scanner stays live")
                                    cameraError = "Not a Messages pairing QR"
                                    return@QrCameraView false
                                }
                                // PairingEndpointResolver: user setting wins,
                                // else the build's production URL. Pairing
                                // never requires manual Gateway setup first.
                                val trusted = PairingEndpointResolver.trustedServerUrl(context)
                                if (!PairingEndpointResolver.originMatches(context, info.apiOrigin)) {
                                    android.util.Log.w("QR_SCAN", "origin mismatch: ${info.apiOrigin} vs $trusted")
                                    cameraError = "This QR uses API ${info.apiOrigin}, but this app trusts $trusted"
                                    return@QrCameraView false
                                }
                                cameraError = null
                                android.util.Log.i("QR_SCAN", "origin verified → FETCHING_METADATA (session=${info.pairingSessionId.take(8)})")
                                scanned = info
                                step = "FETCHING_METADATA"
                                true
                            },
                            onError = {
                                android.util.Log.e("QR_SCAN", "camera error: $it")
                                cameraError = it
                            }
                        )
                    }
                    // Errors overlay ABOVE the camera area — clearly visible.
                    cameraError?.let {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Text(
                                it,
                                modifier = Modifier.padding(12.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { step = "LIST"; cameraError = null }) { Text("Back") }
                    }
                }

                "FETCHING_METADATA" -> {
                    Text("Fetching pairing metadata…")
                    val info = scanned
                    LaunchedEffect(info?.pairingSessionId) {
                        if (info == null) {
                            step = "LIST"
                            return@LaunchedEffect
                        }
                        val (meta, err) = PairingClient.fetchSessionMetadata(context, info)
                        if (err != null || meta == null) {
                            result = "Metadata fetch failed: ${err ?: "invalid response"}"
                            step = "LIST"
                        } else {
                            scanned = meta
                            step = "CONFIRM"
                        }
                    }
                }

                "CONFIRM" -> {
                    val info = scanned
                    if (info == null) {
                        step = "LIST"
                        return@Column
                    }
                    cameraError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Link new device?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    val display = info.origin.removePrefix("https://").removePrefix("http://")
                    Text(
                        "$display",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Requested access", style = MaterialTheme.typography.labelLarge)
                    Text("✓ Read messages\n✓ Send messages\n✓ Receive notifications", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Requested history access", style = MaterialTheme.typography.labelLarge)
                    Text("This choice controls encrypted history keys. Previously uploaded legacy plaintext messages cannot be protected retroactively.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = historyFull, onClick = { historyFull = true })
                        Text("Full history")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = !historyFull, onClick = { historyFull = false })
                        Text("From now on")
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    // ADR-006 Amendment: sensitive access is USER-SELECTABLE
                    // per linked device (privacy-first defaults = OFF). Each
                    // grant becomes a signed capability in the certificate.
                    Text("Sensitive messages", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "Choose what this device may see. Everything stays on " +
                            "this phone unless you enable it here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SensitiveGrantRow("OTP & login codes", grantOtp) { grantOtp = it }
                    SensitiveGrantRow("Bank security codes", grantBank) { grantBank = it }
                    SensitiveGrantRow("Password reset codes", grantReset) { grantReset = it }
                    SensitiveGrantRow("Authentication / 2FA codes", grantAuth) { grantAuth = it }
                    SensitiveGrantRow("Bank transaction notifications", grantFinancial) { grantFinancial = it }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { step = "LIST" }, modifier = Modifier.weight(1f)) {
                            Text("Cancel")
                        }
                        Button(
                            onClick = {
                                val act = context as? androidx.appcompat.app.AppCompatActivity
                                if (act == null) {
                                    cameraError = "Activity unavailable"
                                    return@Button
                                }
                                // §3: user presence is on Android — biometric
                                // BEFORE the certificate is signed.
                                showBiometricPrompt(
                                    act,
                                    title = "Link this device",
                                    subtitle = "Confirm to link $display",
                                    onSuccess = {
                                        val info0 = scanned
                                        if (info0 != null) {
                                            val caps = buildList {
                                                add("READ_MESSAGES")
                                                add("SEND_MESSAGES")
                                                add("MARK_READ")
                                                add("RECEIVE_NOTIFICATIONS")
                                                // ADR-006 Amendment: signed sensitive
                                                // grants — only what the user enabled.
                                                if (grantOtp) add("READ_OTP")
                                                if (grantBank) add("READ_BANK_SECURITY")
                                                if (grantReset) add("READ_PASSWORD_RESET")
                                                if (grantAuth) add("READ_AUTH_CODES")
                                                if (grantFinancial) add("READ_FINANCIAL_NOTIFICATIONS")
                                            }
                                            val grant = if (historyFull) "FULL_HISTORY" else "FROM_NOW_ON"
                                            // FIX 1: network must leave the UI thread —
                                            // the callback only launches a coroutine.
                                            CoroutineScope(Dispatchers.Main).launch {
                                                try {
                                                    // P0-5 ORDER: durable local trust FIRST
                                                    // (registry allocates the authoritative
                                                    // trustSequence) → network approve carries
                                                    // that SAME sequence → attach certificate →
                                                    // statement outbox. Network failure leaves
                                                    // PENDING_PUBLICATION — never an orphan.
                                                    val meta0 = info0.rawMetadata ?: org.json.JSONObject()
                                                    val approved = com.autonomousone.messages.security
                                                        .TrustedDeviceRegistry.ApprovedDevice(
                                                            deviceId = info0.webDeviceId,
                                                            displayName = "Web · ${info0.origin.removePrefix("https://").take(32)}",
                                                            origin = info0.origin,
                                                            signingPublicKey = meta0.optString("webSigningPublicKey", ""),
                                                            encryptionPublicKey = meta0.optString("webEncryptionPublicKey", ""),
                                                            capabilities = caps,
                                                            historyGrant = grant,
                                                            certificateJson = "",
                                                            certificateSignature = "",
                                                            expiresAt = System.currentTimeMillis() + 180L * 24 * 60 * 60 * 1000
                                                        )
                                                    step = "APPROVING_ON_SERVER"
                                                    val seq = com.autonomousone.messages.security.TrustedDeviceRegistry
                                                        .nextTrustSequence(context)
                                                    val res = PairingClient.approve(context, info0, caps, grant, seq) { certJson ->
                                                        com.autonomousone.messages.security.TrustedDeviceRegistry.recordApproval(
                                                            context,
                                                            approved.copy(
                                                                certificateJson = certJson.toString(),
                                                                certificateSignature = certJson.optString("rootSignature", "")
                                                            ),
                                                            trustSequence = seq
                                                        )
                                                        com.autonomousone.messages.security
                                                            .SensitiveGrantStore.savePairingGrants(context, info0.webDeviceId, caps)
                                                    }
                                                    if (res.isSuccess) {
                                                        com.autonomousone.messages.data.TelephonySyncCoordinator.get(context).requestSync()
                                                        result = "✅ Device linked — continue in the browser"
                                                        step = "LINKED"
                                                    } else {
                                                        result = "❌ Link failed: ${res.exceptionOrNull()?.message}"
                                                        step = "LIST"
                                                    }
                                                } catch (t: Throwable) {
                                                    // P0-7: the UI must never die on post-approval
                                                    // errors; root causes are fixed upstream, this is
                                                    // the safety net. No keys/secrets logged.
                                                    android.util.Log.e("PAIRING", "post approval failed", t)
                                                    result = "Link failed: local trust registration failed"
                                                    step = "LIST"
                                                }
                                            }
                                        }
                                    },
                                    onError = { cameraError = "Authentication required to link a device" }
                                )
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Link device") }
                    }
                }

                "APPROVING_ON_SERVER" -> Text("Approving on server…")

                "LINKED" -> {
                    Text("Device linked — continue in the browser")
                    Button(onClick = { step = "LIST" }) { Text("Done") }
                }

                "DONE" -> Unit
            }
        }
    }
}

/** CameraX preview + ML Kit QR detection (on-device).
 *  [onQr] returns TRUE when the payload is accepted — the analyzer is then
 *  cleared so no further frames fire. FALSE/invalid keeps scanning.
 *  Camera, scanner and executor are cleaned up on dispose. */
@Composable
private fun QrCameraView(onQr: (String) -> Boolean, onError: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scannerRef = remember { java.util.concurrent.atomic.AtomicReference<com.google.mlkit.vision.barcode.BarcodeScanner?>(null) }
    val providerRef = remember { java.util.concurrent.atomic.AtomicReference<ProcessCameraProvider?>(null) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            try { scannerRef.get()?.close() } catch (_: Exception) {}
            try { providerRef.get()?.unbindAll() } catch (_: Exception) {}
            executor.shutdown()
        }
    }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            val scanner = BarcodeScanning.getClient(
                BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                    .build()
            ).also { scannerRef.set(it) }
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                try {
                    val provider = providerFuture.get().also { providerRef.set(it) }
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val analysis = androidx.camera.core.ImageAnalysis.Builder()
                        .setBackpressureStrategy(androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(executor) { imageProxy ->
                        @androidx.camera.core.ExperimentalGetImage
                        val media = imageProxy.image
                        if (media == null) {
                            imageProxy.close()
                            return@setAnalyzer
                        }
                        val input = InputImage.fromMediaImage(
                            media,
                            imageProxy.imageInfo.rotationDegrees
                        )
                        scanner.process(input)
                            .addOnSuccessListener { codes ->
                                val raw = codes.firstOrNull()?.rawValue
                                if (raw != null && onQr(raw)) {
                                    // Accepted — stop the analyzer so this QR
                                    // is never delivered twice.
                                    analysis.clearAnalyzer()
                                }
                            }
                            .addOnCompleteListener { imageProxy.close() }
                    }
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                    )
                } catch (e: Exception) {
                    onError(e.message ?: "camera failed")
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}

private val deviceCapabilityLabels = linkedMapOf(
    "READ_MESSAGES" to "Read messages", "SEND_MESSAGES" to "Send messages",
    "MARK_READ" to "Mark messages read", "RECEIVE_NOTIFICATIONS" to "Notifications",
    "READ_OTP" to "OTP and login codes", "READ_BANK_SECURITY" to "Bank security codes",
    "READ_PASSWORD_RESET" to "Password reset codes", "READ_AUTH_CODES" to "Authentication codes",
    "READ_FINANCIAL_NOTIFICATIONS" to "Bank transaction notifications"
)

private fun deviceCapabilities(json: String): Set<String> = runCatching {
    val values = org.json.JSONArray(json)
    (0 until values.length()).map { values.getString(it) }.toSet()
}.getOrDefault(emptySet())

/** ADR-006 Amendment: one per-device sensitive grant switch. */
@Composable
private fun SensitiveGrantRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
