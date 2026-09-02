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
import com.autonomousone.messages.security.PairingClient
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
    var scanned by remember { mutableStateOf<PairingClient.SessionInfo?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var historyFull by remember { mutableStateOf(true) }

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
                    Text("Sensitive messages", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "✓ OTP stays on this phone\n✓ Bank security codes stay on this phone",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                                            val caps = listOf("READ_MESSAGES", "SEND_MESSAGES", "MARK_READ", "RECEIVE_NOTIFICATIONS")
                                            val grant = if (historyFull) "FULL_HISTORY" else "FROM_NOW_ON"
                                            // FIX 1: network must leave the UI thread —
                                            // the callback only launches a coroutine.
                                            CoroutineScope(Dispatchers.Main).launch {
                                                val res = PairingClient.approve(context, info0, caps, grant)
                                                result = if (res.isSuccess) "✅ Device linked — continue in the browser"
                                                else "❌ Link failed: ${res.exceptionOrNull()?.message}"
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
