package com.autonomousone.messages.ui.gateway

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.autonomousone.messages.MainActivity
import com.autonomousone.messages.gateway.health.AuthHealth
import com.autonomousone.messages.gateway.health.AuthVerification
import com.autonomousone.messages.gateway.health.EndpointHealth
import com.autonomousone.messages.gateway.health.EventUploadHealth
import com.autonomousone.messages.gateway.health.GatewayHealthRules
import com.autonomousone.messages.gateway.health.GatewayHealthSnapshot
import com.autonomousone.messages.gateway.health.GatewayLogEntry
import com.autonomousone.messages.gateway.health.GatewayLogFilter
import com.autonomousone.messages.gateway.health.GatewayLogSeverity
import com.autonomousone.messages.gateway.health.GatewayLogSubsystem
import com.autonomousone.messages.gateway.health.NetworkHealth
import com.autonomousone.messages.gateway.health.PullBridgeHealth
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GatewayResponsiveLayoutTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val now = 1_700_000_000_000L

    @Test fun width320Font1() = verifyLayout(320, 1f)
    @Test fun width320Font13() = verifyLayout(320, 1.3f)
    @Test fun width360Font1() = verifyLayout(360, 1f)
    @Test fun width360Font13() = verifyLayout(360, 1.3f)
    @Test fun width393Font115() = verifyLayout(393, 1.15f)
    @Test fun width393Font1() = verifyLayout(393, 1f)
    @Test fun width412Font13() = verifyLayout(412, 1.3f)
    @Test fun width412Font15() = verifyLayout(412, 1.5f)

    private fun verifyLayout(widthDp: Int, fontScale: Float) {
        val health = GatewayHealthRules.evaluate(
            GatewayHealthSnapshot(
                generatedAt = now,
                desired = true,
                network = NetworkHealth(true, "Wi-Fi", now),
                endpoint = EndpointHealth(configured = true, host = "gmweb.example", lastTcpConnectMs = 3),
                authentication = AuthHealth(
                    AuthVerification.UNVERIFIABLE,
                    unverifiableReason = "HTTP 400"
                ),
                eventUpload = EventUploadHealth(
                    running = true,
                    deadLetter = 309,
                    lastAttemptAt = now - 60_000,
                    lastSuccessAt = now - 60_000,
                    lastHttpStatus = 200
                ),
                pullBridge = PullBridgeHealth(
                    running = true,
                    state = "POLLING",
                    lastPollStartedAt = now - 20_000,
                    lastSuccessfulPollAt = now - 18_000,
                    lastEmptyPollAt = now - 18_000,
                    lastHttpStatus = 200
                )
            ),
            now
        )
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                MaterialTheme {
                    Column(
                        Modifier
                            .width(widthDp.dp)
                            .padding(horizontal = 16.dp)
                    ) {
                        GatewayHealthCard(
                            health = health,
                            now = now,
                            reconnecting = false,
                            diagnosticRunning = false,
                            diagnosticResult = null,
                            onRunDiagnostics = {},
                            onReconnect = {}
                        )
                        Spacer(Modifier.height(12.dp))
                        GatewayLogFeedCard(
                            entries = listOf(
                                GatewayLogEntry(
                                    at = now,
                                    severity = GatewayLogSeverity.SUCCESS,
                                    subsystem = GatewayLogSubsystem.PULL_BRIDGE,
                                    code = "PULL_COMPLETE",
                                    title = "Pull completed",
                                    detail = "No task · 46.9s"
                                )
                            ),
                            filter = GatewayLogFilter.ALL,
                            onFilterChange = {},
                            includeAdvanced = false,
                            onToggleAdvanced = {},
                            paused = false,
                            onTogglePause = {},
                            onClear = {},
                            onCopy = {},
                            onShareReport = {}
                        )
                    }
                }
            }
        }

        listOf("Reconnect", "Authentication", "Report").forEach { label ->
            composeRule.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
            val bounds = composeRule.onNodeWithText(label, useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            assertTrue("$label wrapped vertically at ${widthDp}dp / $fontScale", bounds.width > bounds.height)
        }
        composeRule.onNodeWithText("Run diagnostics", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Runtime traffic is working", ignoreCase = true).assertIsDisplayed()
        composeRule.onNodeWithText("All").assertIsDisplayed()
        composeRule.onNodeWithText("EVE").performScrollTo().assertIsDisplayed()

        val actionBounds = listOf("Pause", "Clear", "Copy", "Report").map {
            composeRule.onNodeWithText(it, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        }
        actionBounds.forEachIndexed { index, first ->
            actionBounds.drop(index + 1).forEach { second ->
                assertTrue("log actions overlap at ${widthDp}dp / $fontScale", !first.overlaps(second))
            }
        }

        val healthBounds = composeRule.onNodeWithTag("GatewayHealthCard").fetchSemanticsNode().boundsInRoot
        val logBounds = composeRule.onNodeWithTag("GatewayLogFeedCard").fetchSemanticsNode().boundsInRoot
        assertTrue(healthBounds.left >= 0f && healthBounds.right <= widthDp.toFloat())
        assertTrue(logBounds.left >= 0f && logBounds.right <= widthDp.toFloat())
    }
}
