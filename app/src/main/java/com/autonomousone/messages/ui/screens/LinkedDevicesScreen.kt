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

    var step by remember { mutableStateOf("LIST") } // LIST | SCANNING | CONFIRM | DONE
    // LINKED DEVICE CONTROL: the local Trust Registry is the source of truth
    // for the list — durable, survives restart, includes revocation state.
    var devices by remember { mutableStateOf<List<TrustedDeviceEntity>>(emptyList()) }
    var revokeTarget by remember { mutableStateOf<TrustedDeviceEntity?>(null) }
    var scanned by remember { mutableStateOf<PairingClient.SessionInfo?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    suspend fun refreshDevices() {
        devices = MessagesDatabase.get(context).trustedDeviceDao().all()
    }
    LaunchedEffect(step, result) { refreshDevices() }
    var historyFull by remember { mutableStateOf(true) }
    // ADR-006 Amendment: per-device sensitive grants (privacy-first OFF).
    var grantOtp by remember { mutableStateOf(false) }
    var grantBank by remember { mutableStateOf(false) }
    var grantReset by remember { mutableStateOf(false) }
    var grantAuth by remember { mutableStateOf(false) }
    var grantFinancial by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) step = "SCANNING" else cameraError = "Camera permission is required to scan the QR" }

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
                    Button(
                        onClick = {
                            val granted = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.CAMERA
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) step = "SCANNING" else permissionLauncher.launch(Manifest.permission.CAMERA)
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
                                    "${target.displayName} will lose access immediately: " +
                                        "its session is killed, sync and commands are denied, " +
                                        "and no future key grants are issued. Audit history is kept."
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
                                            CoroutineScope(Dispatchers.Main).launch {
                                                TrustedDeviceRegistry.recordRevocation(context, t.deviceId)
                                                refreshDevices()
                                                result = "🛡 ${t.displayName} revoked — publishing DEVICE_REVOKED"
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
                                        "Capabilities: ${d.capabilitiesJson}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        "History: ${d.historyGrant}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    if (d.status != TrustedDeviceEntity.STATUS_REVOKED &&
                                        d.status != TrustedDeviceEntity.STATUS_REVOKE_PENDING
                                    ) {
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

                "SCANNING" -> {
                    Text("Point the camera at the QR code shown in the browser")
                    Box(modifier = Modifier.fillMaxWidth().height(360.dp)) {
                        // REVIEW FIX: onQr returns Boolean — true = accepted
                        // (scanner stops via clearAnalyzer), false = keep
                        // scanning. No latch needed at the call site.
                        QrCameraView(
                            onQr = { raw ->
                                android.util.Log.i("QR_SCAN", "payload received: ${raw.take(80)}")
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
                                if (!PairingEndpointResolver.originMatches(context, info.origin)) {
                                    android.util.Log.w("QR_SCAN", "origin mismatch: ${info.origin} vs $trusted")
                                    cameraError = "This QR belongs to ${info.origin}, but this app trusts $trusted"
                                    return@QrCameraView false
                                }
                                cameraError = null
                                android.util.Log.i("QR_SCAN", "origin verified → CONFIRM (session=${info.pairingSessionId.take(8)})")
                                scanned = info
                                step = "CONFIRM"
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

                "CONFIRM" -> {
                    val info = scanned
                    if (info == null) {
                        step = "LIST"
                        return@Column
                    }
                    // Metadata fetch runs once on entry. A failure is VISIBLE
                    // (was silently bouncing back to LIST before).
                    LaunchedEffect(info.pairingSessionId) {
                        val (meta, err) = PairingClient.fetchSessionMetadata(context, info)
                        if (err != null) {
                            cameraError = "Metadata fetch failed: $err"
                            step = "LIST"
                        } else if (meta != null) {
                            scanned = meta
                        }
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
                    Text("History", style = MaterialTheme.typography.labelLarge)
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
                                                val res = PairingClient.approve(context, info0, caps, grant)
                                                if (res.isSuccess) {
                                                    // LINKED DEVICE CONTROL: durable trust
                                                    // record + DEVICE_APPROVED statement in
                                                    // ONE Room transaction (Trust Root signed).
                                                    // Trust state is never RAM-only.
                                                    val certJson = res.getOrDefault(org.json.JSONObject())
                                                    val meta = info0.rawMetadata ?: org.json.JSONObject()
                                                    com.autonomousone.messages.security.TrustedDeviceRegistry
                                                        .recordApproval(
                                                            context,
                                                            com.autonomousone.messages.security
                                                                .TrustedDeviceRegistry.ApprovedDevice(
                                                                    deviceId = info0.webDeviceId,
                                                                    displayName = "Web · ${info0.origin.removePrefix("https://").take(32)}",
                                                                    origin = info0.origin,
                                                                    signingPublicKey = meta.optString("webSigningPublicKey", ""),
                                                                    encryptionPublicKey = meta.optString("webEncryptionPublicKey", ""),
                                                                    capabilities = caps,
                                                                    historyGrant = grant,
                                                                    certificateJson = certJson.toString(),
                                                                    certificateSignature = certJson.optString("rootSignature", ""),
                                                                    expiresAt = certJson.optLong("expiresAt")
                                                                )
                                                        )
                                                    // ADR-006 Amendment: persist the
                                                    // user's sensitive grants for this
                                                    // linked device (drives future
                                                    // DEVICE_CAPABILITIES_CHANGED).
                                                    com.autonomousone.messages.security
                                                        .SensitiveGrantStore.savePairingGrants(
                                                            context, info0.webDeviceId, caps
                                                        )
                                                    result = "✅ Device linked — continue in the browser"
                                                } else {
                                                    result = "❌ Link failed: ${res.exceptionOrNull()?.message}"
                                                }
                                                step = "LIST"
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
