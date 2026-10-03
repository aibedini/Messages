package com.autonomousone.messages.ui.gateway

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Phone telemetry diagnostics — the on-device answer to "is this phone reporting, and if not, where
 * does it stop?".
 *
 * Two sections that must never be read as one another:
 *
 *  - **Live agent runtime**: which APK is running and what it advertises over the command channel.
 *    This comes from PackageManager and the command runtime health, and it does NOT depend on
 *    telemetry: a phone whose telemetry has never succeeded can still prove it is alive and current.
 *  - **Device telemetry**: the reporter's own state and the outcome of the last real attempt.
 *
 * "Test telemetry now" runs ONE attempt through the production reporter — the same gated, serialized
 * path as the 60-second heartbeat. Success is shown ONLY for a real 2xx; every other outcome is shown
 * as its own stable failure code, because "something went wrong" is what kept this bug undiagnosed.
 */
@Composable
fun TelemetryDiagnosticsCard(
    installedAppVersion: String,
    runtime: com.autonomousone.messages.gateway.CommandRuntimeHealth.Snapshot,
    commandPollerRunning: Boolean,
    telemetry: com.autonomousone.messages.gateway.TelemetryHealth.Snapshot,
    eligibility: com.autonomousone.messages.gateway.TelemetryEligibility?,
    destinationHost: String?,
    simDiscoveryReason: String?,
    testState: String,
    testDetail: String?,
    onTestTelemetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                "Phone telemetry",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))

            // ── Live agent runtime: independent of telemetry ────────────────
            Text("Live agent runtime", style = MaterialTheme.typography.labelLarge)
            Line("Installed Android app", installedAppVersion)
            Line("Command poller", if (commandPollerRunning) "running" else "stopped")
            Line(
                "Runtime advertised",
                when {
                    runtime.runtimeEverAccepted ->
                        "yes · ${runtime.lastAdvertisedVersionName ?: "?"}" +
                            " (${runtime.lastAdvertisedVersionCode ?: 0})"
                    runtime.lastClaimAttemptAt != null ->
                        "attempted · ${runtime.lastClaimHttpStatus?.toString() ?: "no response"}"
                    else -> "no claim yet"
                }
            )
            Line("Command types", "${runtime.advertisedCommandTypeCount}")
            Line("Last claim HTTP", runtime.lastClaimHttpStatus?.toString() ?: "n/a")

            Spacer(Modifier.height(10.dp))

            // ── Device telemetry: allowed to fail ──────────────────────────
            Text("Device telemetry", style = MaterialTheme.typography.labelLarge)
            Line("Reporter", if (telemetry.running) "running" else "stopped")
            Line(
                "Eligible",
                when {
                    eligibility == null -> "unknown"
                    eligibility.eligible -> "yes"
                    else -> "blocked · ${eligibility.reason ?: "unknown"}"
                }
            )
            Line("Eligibility reason", eligibility?.reason ?: "none")
            Line("Destination host", destinationHost ?: "not configured")
            Line("Last trigger", telemetry.lastTrigger ?: "never")
            Line("Last attempt", ago(telemetry.lastAttemptAt))
            Line("Last success", ago(telemetry.lastSuccessAt))
            Line("Last HTTP", telemetry.lastHttpStatus?.toString() ?: "n/a")
            Line("Last failure code", telemetry.lastErrorCode ?: "none")
            Line(
                "Attempts / successes / failures",
                "${telemetry.attempts} / ${telemetry.successes} / ${telemetry.failures}"
            )
            Line("Skipped (no reporter)", telemetry.skipped.toString())
            // SIM state is reported SEPARATELY from the transport: a missing permission is a
            // successful report that says "cannot list SIMs", not a failed phone.
            Line(
                "SIM discovery",
                when {
                    telemetry.lastSubscriptionReason == null && telemetry.lastSubscriptionCount != null ->
                        "available"
                    simDiscoveryReason != null -> simDiscoveryReason
                    else -> "unknown"
                }
            )
            Line("Active SIMs", telemetry.lastSubscriptionCount?.toString() ?: "unknown")
            Line("SIM discovery reason", simDiscoveryReason ?: "none")
            Line(
                "Remote refresh",
                if (com.autonomousone.messages.gateway.DeviceTelemetry.REMOTE_REFRESH_SUPPORTED) {
                    "supported · last ${telemetry.lastRemoteRefreshResult ?: "none"}" +
                        " · count ${telemetry.remoteRefreshCount}"
                } else {
                    "not supported"
                }
            )

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onTestTelemetry,
                    enabled = testState != "RUNNING"
                ) {
                    Text(if (testState == "RUNNING") "Testing telemetry…" else "Test telemetry now")
                }
                if (testState == "RUNNING") {
                    Spacer(Modifier.width(12.dp))
                    CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp))
                }
            }
            testDetail?.let { detail ->
                Spacer(Modifier.height(8.dp))
                val (label, color) = when (testState) {
                    "SUCCESS" -> "Telemetry sent successfully" to Color(0xFF10B981)
                    "FAILED" -> "Telemetry failed" to MaterialTheme.colorScheme.error
                    else -> "Telemetry" to MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(
                    "$label · $detail",
                    style = MaterialTheme.typography.bodySmall,
                    color = color
                )
            }
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Compact, dependency-free age label: "just now", "42s ago", "7m ago", "3h ago" or "never". */
private fun ago(at: Long?): String {
    if (at == null || at <= 0L) return "never"
    val delta = (System.currentTimeMillis() - at).coerceAtLeast(0L)
    return when {
        delta < 1_000L -> "just now"
        delta < 60_000L -> "${delta / 1000}s ago"
        delta < 3_600_000L -> "${delta / 60_000}m ago"
        else -> "${delta / 3_600_000}h ago"
    }
}
