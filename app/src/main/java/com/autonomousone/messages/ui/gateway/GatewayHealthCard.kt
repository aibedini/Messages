package com.autonomousone.messages.ui.gateway

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autonomousone.messages.gateway.health.AuthVerification
import com.autonomousone.messages.gateway.health.GatewayConnectivityResult
import com.autonomousone.messages.gateway.health.GatewayHealthPresentation
import com.autonomousone.messages.gateway.health.GatewayHealthSnapshot
import com.autonomousone.messages.gateway.health.GatewayLogEntry
import com.autonomousone.messages.gateway.health.GatewayLogFilter
import com.autonomousone.messages.gateway.health.GatewayProbeStatus
import com.autonomousone.messages.gateway.health.HealthTone

/**
 * v3.4.x P0 — the gateway status card, as independent DIMENSIONS.
 *
 * ── WHY THIS REPLACES ONE GREEN LIGHT ────────────────────────────────────────
 * The old card showed a single colour driven by `ConnectionSupervisor.State`, which reports
 * CONNECTED as soon as the components have been STARTED. It was green in production while
 * `/gateway/pull` had never once succeeded, because the outbound event sync (a different
 * route, in the other direction) was working perfectly.
 *
 * So the headline is a real verdict derived from what the components OBSERVED, and below it
 * each dimension reports itself: Internet, Server, TLS, Authentication, the outbound sync and
 * the inbound delivery bridge — the last two deliberately separate, because conflating them
 * is the entire bug.
 *
 * The colours come from [GatewayHealthPresentation], which is unit-tested: OFFLINE is grey
 * rather than red, because the usual reason for it is that the user turned the gateway off
 * and shouting at someone about their own decision teaches them to ignore the colour.
 */
@Composable
fun GatewayHealthCard(
    health: GatewayHealthSnapshot,
    now: Long,
    reconnecting: Boolean,
    diagnosticRunning: Boolean,
    diagnosticResult: GatewayConnectivityResult?,
    onRunDiagnostics: () -> Unit,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tone = GatewayHealthPresentation.tone(health.overall)
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ToneDot(tone, size = 14)
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Gateway",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = GatewayHealthPresentation.headline(health.overall),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (reconnecting || diagnosticRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = GatewayHealthPresentation.summary(health.conclusion),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics {
                    contentDescription =
                        "${GatewayHealthPresentation.headline(health.overall)}. " +
                            GatewayHealthPresentation.summary(health.conclusion)
                }
            )

            Spacer(modifier = Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onRunDiagnostics,
                    enabled = !diagnosticRunning
                ) {
                    Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (diagnosticRunning) "Running…" else "Run diagnostics")
                }
                Button(
                    onClick = onReconnect,
                    enabled = !reconnecting
                ) {
                    Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (reconnecting) "Reconnecting…" else "Reconnect")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── The dimensions ──────────────────────────────────────────────
            val bridgeTone = GatewayHealthPresentation.bridgeTone(health.pullBridge, now)
            val uploadTone = GatewayHealthPresentation.uploadTone(health.eventUpload, now)

            DimensionRow(
                label = "Internet",
                detail = if (health.network.validatedInternet) health.network.transport else "offline",
                tone = GatewayHealthPresentation.tone(health.network.validatedInternet)
            )
            DimensionRow(
                label = "Server",
                detail = health.endpoint.lastTcpConnectMs?.let { "${it}ms" }
                    ?: health.endpoint.host ?: "not configured",
                tone = when {
                    health.endpoint.lastTcpConnectMs != null -> HealthTone.GOOD
                    health.endpoint.configured -> HealthTone.NEUTRAL
                    else -> HealthTone.IDLE
                }
            )
            DimensionRow(
                label = "TLS",
                detail = health.tls.protocol ?: "not checked",
                tone = GatewayHealthPresentation.tone(health.tls.valid?.let { it && health.tls.hostMatched != false })
            )
            DimensionRow(
                label = "Authentication",
                // Three outcomes, not two. "Could not verify" and "rejected" are different
                // answers, and collapsing them is what made a healthy device report its own key
                // as rejected.
                detail = when (health.authentication.status) {
                    AuthVerification.VERIFIED -> "device enrolled"
                    AuthVerification.REJECTED -> "key rejected (401/403)"
                    AuthVerification.UNVERIFIABLE -> "could not verify — not a rejection"
                    AuthVerification.UNKNOWN -> "not checked"
                },
                tone = when (health.authentication.status) {
                    AuthVerification.VERIFIED -> HealthTone.GOOD
                    AuthVerification.REJECTED -> HealthTone.BAD
                    AuthVerification.UNVERIFIABLE -> HealthTone.WARN
                    AuthVerification.UNKNOWN -> HealthTone.NEUTRAL
                }
            )
            DimensionRow(
                label = "Android → GMweb sync",
                detail = GatewayHealthPresentation.uploadSummary(health.eventUpload, now),
                tone = uploadTone
            )
            DimensionRow(
                label = "GMweb → Android pull",
                detail = GatewayHealthPresentation.bridgeSummary(health.pullBridge, now),
                tone = bridgeTone
            )
            // "Current poll waiting 18s" — the long-poll holds a request open for ~25s by
            // design, so the user needs to see that a poll is IN FLIGHT rather than infer it
            // from a number that has not moved.
            GatewayHealthPresentation.inFlightWait(health.pullBridge, now)
                ?.takeIf { health.pullBridge.lastFailure == null }
                ?.let { wait ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 19.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "current poll waiting $wait",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            DimensionRow(
                label = "EVE queue",
                detail = GatewayHealthPresentation.queueSummary(health.eveQueue),
                tone = if (health.eveQueue.idle) HealthTone.IDLE else HealthTone.NEUTRAL
            )

            // ── The last probe run, when there is one ───────────────────────
            diagnosticResult?.let { result ->
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "Last diagnostics",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                result.steps.forEach { step ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ToneDot(GatewayHealthPresentation.tone(step.status), size = 8)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = step.stage.name,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(72.dp)
                        )
                        Text(
                            text = buildString {
                                append(step.status.name.lowercase())
                                step.durationMs?.let { append(" · ${it}ms") }
                            },
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        step.detail?.let {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = it,
                                fontSize = 11.sp,
                                maxLines = 1,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                            )
                        }
                    }
                }
                if (result.steps.any { it.status == GatewayProbeStatus.FAILED }) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Stopped at " +
                            (result.firstFailure?.stage?.name ?: "") +
                            " — everything after it was not attempted.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}

/** One dimension: a dot, its name, and its current state in the user's terms. */
@Composable
private fun DimensionRow(
    label: String,
    detail: String,
    tone: HealthTone
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .semantics { contentDescription = "$label: $detail" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        ToneDot(tone, size = 9)
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * The one place a tone becomes a colour.
 *
 * A dot rather than a filled row: colour is a hint here, and the TEXT carries the meaning —
 * which is what makes the card readable for someone who cannot distinguish red from green.
 */
@Composable
fun ToneDot(tone: HealthTone, size: Int) {
    val colour = when (tone) {
        HealthTone.GOOD -> Color(0xFF10B981)
        HealthTone.WARN -> Color(0xFFF59E0B)
        HealthTone.BAD -> Color(0xFFEF4444)
        HealthTone.NEUTRAL -> MaterialTheme.colorScheme.outline
        HealthTone.IDLE -> MaterialTheme.colorScheme.outlineVariant
    }
    Row(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(colour)
    ) {}
}

/**
 * The live feed card: the structured log with its filter chips and actions.
 *
 * The filter is what makes the feed usable — the reported bug is exactly the case where every
 * sync line is green and every bridge line is red, and that is invisible in one
 * undifferentiated list. The technical rows (`accepted=2/2`, `duplicates=0`) are kept and
 * hidden behind a toggle, which is where the brief puts them.
 */
@Composable
fun GatewayLogFeedCard(
    entries: List<GatewayLogEntry>,
    filter: GatewayLogFilter,
    onFilterChange: (GatewayLogFilter) -> Unit,
    includeAdvanced: Boolean,
    onToggleAdvanced: () -> Unit,
    paused: Boolean,
    onTogglePause: () -> Unit,
    onClear: () -> Unit,
    onCopy: () -> Unit,
    onShareReport: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Live gateway log",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButtonCompat(text = if (paused) "Resume" else "Pause", onClick = onTogglePause)
                    TextButtonCompat(text = "Clear", onClick = onClear)
                    TextButtonCompat(text = "Copy", onClick = onCopy)
                    TextButtonCompat(text = "Report", onClick = onShareReport)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                GatewayLogFilter.entries.forEach { candidate ->
                    FilterChip(
                        selected = candidate == filter,
                        onClick = { onFilterChange(candidate) },
                        label = { Text(candidate.label(), fontSize = 12.sp) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = includeAdvanced,
                    onClick = onToggleAdvanced,
                    label = { Text("Advanced details", fontSize = 11.sp) }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (paused) "paused" else "${entries.size} line(s)",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (entries.isEmpty()) {
                Text(
                    text = when (filter) {
                        GatewayLogFilter.ALL -> "No gateway activity yet."
                        else -> "Nothing in this filter yet."
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1E1E2E))
                        .padding(10.dp)
                ) {
                    LazyColumn {
                        items(entries) { entry ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = entry.clockTime(),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = Color(0xFF6C7086),
                                    modifier = Modifier.width(58.dp)
                                )
                                ToneDot(GatewayHealthPresentation.tone(entry.severity), size = 7)
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text(
                                        text = entry.title,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = Color(0xFFCDD6F4),
                                        lineHeight = 15.sp
                                    )
                                    entry.detail?.let { detail ->
                                        Text(
                                            text = detail,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            color = Color(0xFFA6ADC8),
                                            lineHeight = 13.sp
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

/** A compact text button; a full `TextButton` is too tall for this action row. */
@Composable
private fun TextButtonCompat(text: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick) {
        Text(text, fontSize = 12.sp)
    }
}

private fun GatewayLogFilter.label(): String = when (this) {
    GatewayLogFilter.ALL -> "All"
    GatewayLogFilter.ERRORS -> "Errors"
    GatewayLogFilter.CONNECTION -> "Connection"
    GatewayLogFilter.BRIDGE -> "Bridge"
    GatewayLogFilter.SYNC -> "Sync"
    GatewayLogFilter.EVE -> "EVE"
}

/** `HH:mm:ss` in the device's zone — the same shape the rest of the screen uses. */
private fun GatewayLogEntry.clockTime(): String =
    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        .format(java.util.Date(at))
