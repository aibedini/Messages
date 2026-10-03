package com.autonomousone.messages.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Foreground-start policy regressions for modern Android.
 *
 * Android 15 both forbids boot-driven dataSync starts and time-limits long-running dataSync FGS
 * usage. A persistent SMS gateway therefore uses specialUse only on API 35+, regardless of who
 * triggered the start; older Android keeps the existing dataSync behavior.
 */
class GatewayForegroundStartPolicyTest {

    private val mainRoots = listOf("src/main", "app/src/main")

    private fun source(relative: String): String =
        mainRoots.map { File(it, relative) }.firstOrNull { it.isFile }?.readText()
            ?: error("cannot locate $relative")

    private fun codeOnly(relative: String): String = source(relative).lineSequence()
        .map { line ->
            val withoutLineComment = line.substringBefore("//")
            if (withoutLineComment.trimStart().startsWith("*")) "" else withoutLineComment
        }
        .joinToString("\n")

    @Test
    fun `all Android 15 or newer starts must avoid dataSync budget`() {
        GatewayForegroundStartPolicy.StartReason.entries.forEach { reason ->
            listOf(35, 36, 40).forEach { api ->
                assertEquals(
                    "$reason on API $api must use specialUse only",
                    GatewayForegroundStartPolicy.Decision.START_SPECIAL_USE_ONLY,
                    GatewayForegroundStartPolicy.decide(api, reason)
                )
            }
        }
    }

    @Test
    fun `below Android 15 starts may still use dataSync`() {
        GatewayForegroundStartPolicy.StartReason.entries.forEach { reason ->
            listOf(26, 30, 33, 34).forEach { api ->
                assertEquals(
                    "$reason on API $api keeps dataSync",
                    GatewayForegroundStartPolicy.Decision.START_WITH_DATA_SYNC,
                    GatewayForegroundStartPolicy.decide(api, reason)
                )
            }
        }
    }

    @Test
    fun `the decision and the type bitmask cannot disagree`() {
        GatewayForegroundStartPolicy.StartReason.entries.forEach { reason ->
            listOf(26, 34, 35, 36).forEach { api ->
                val decision = GatewayForegroundStartPolicy.decide(api, reason)
                val expected = decision == GatewayForegroundStartPolicy.Decision.START_WITH_DATA_SYNC
                assertEquals(
                    "$reason/API $api: includesDataSync must match the decision",
                    expected,
                    GatewayForegroundStartPolicy.includesDataSync(decision)
                )
            }
        }
    }

    @Test
    fun `an unknown or absent reason extra is treated as a user start`() {
        assertEquals(
            GatewayForegroundStartPolicy.StartReason.USER_OR_APP,
            GatewayForegroundStartPolicy.StartReason.fromExtra(null)
        )
        assertEquals(
            GatewayForegroundStartPolicy.StartReason.USER_OR_APP,
            GatewayForegroundStartPolicy.StartReason.fromExtra("NOT_A_REASON")
        )
        assertEquals(
            GatewayForegroundStartPolicy.StartReason.BOOT,
            GatewayForegroundStartPolicy.StartReason.fromExtra("BOOT")
        )
    }

    @Test
    fun `a background restart is not attempted through an alarm where the platform refuses it`() {
        listOf(31, 33, 34, 35, 36, 40).forEach { api ->
            assertEquals(
                "API $api must not rely on the alarm",
                GatewayForegroundStartPolicy.RestartMechanism.WORKMANAGER_ONLY,
                GatewayForegroundStartPolicy.restartMechanism(api)
            )
            assertFalse(
                "API $api must not arm the alarm",
                GatewayForegroundStartPolicy.includesAlarm(
                    GatewayForegroundStartPolicy.restartMechanism(api)
                )
            )
        }
    }

    @Test
    fun `an older platform keeps the fast alarm path with WorkManager as the backstop`() {
        listOf(26, 28, 30).forEach { api ->
            val mechanism = GatewayForegroundStartPolicy.restartMechanism(api)
            assertEquals(
                "API $api may still use the alarm",
                GatewayForegroundStartPolicy.RestartMechanism.ALARM_AND_WORKMANAGER,
                mechanism
            )
            assertTrue(GatewayForegroundStartPolicy.includesAlarm(mechanism))
        }
    }

    @Test
    fun `the restart watchdog always defers to WorkManager before considering the alarm`() {
        val service = codeOnly("java/com/autonomousone/messages/gateway/GatewayService.kt")
        val watchdog = service.substringAfter("private fun scheduleRestartWatchdog()")
            .substringBefore("private fun ")
        assertTrue(
            "the watchdog must hand the retry to WorkManager",
            watchdog.contains("GatewayStartDeferral.defer(this)")
        )
        assertTrue(
            "…and must not arm the alarm where the platform refuses a background start",
            watchdog.contains("includesAlarm(mechanism)")
        )
        assertTrue(
            "the deferral must come before the alarm is armed: $watchdog",
            watchdog.indexOf("GatewayStartDeferral.defer(this)") <
                watchdog.indexOf("PendingIntent.getForegroundService")
        )
    }

    @Test
    fun `the service handles the Android 15 foreground timeout instead of ignoring it`() {
        val service = codeOnly("java/com/autonomousone/messages/gateway/GatewayService.kt")
        assertTrue(
            "the service must override onTimeout(startId, fgsType)",
            service.contains("override fun onTimeout(startId: Int, fgsType: Int)")
        )
        val handler = service.substringAfter("override fun onTimeout(startId: Int, fgsType: Int)")
            .substringBefore("private fun ")
        assertTrue(
            "a timeout must defer the restart rather than pretend the bridge survived",
            handler.contains("GatewayStartDeferral.defer(this)")
        )
        assertTrue(
            "the service must stop promptly after a timeout",
            handler.contains("stopSelf(startId)")
        )
    }

    @Test
    fun `the boot receiver never starts the service itself`() {
        val receiver = codeOnly("java/com/autonomousone/messages/receiver/BootGatewayReceiver.kt")
        assertFalse(
            "the boot receiver must not start a foreground service directly: $receiver",
            receiver.contains("startForegroundService")
        )
        assertTrue(
            "the boot receiver must identify itself as a boot start",
            receiver.contains("GatewayForegroundStartPolicy.StartReason.BOOT")
        )
    }

    @Test
    fun `the service chooses the type through the policy and never hardcodes the old pair`() {
        val service = codeOnly("java/com/autonomousone/messages/gateway/GatewayService.kt")
        assertTrue(
            "the foreground type must come from the policy",
            service.contains("GatewayForegroundStartPolicy.decide(")
        )
        assertTrue(
            "the start reason must be recorded from the intent",
            service.contains("recordStartReason(intent)")
        )
        assertFalse(
            "the dataSync type must not be requested unconditionally again",
            service.contains(
                "val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {"
            )
        )
    }

    @Test
    fun `a refused start is caught and deferred rather than thrown`() {
        val service = codeOnly("java/com/autonomousone/messages/gateway/GatewayService.kt")
        val deferral = codeOnly("java/com/autonomousone/messages/gateway/GatewayStartDeferral.kt")
        assertTrue(
            "startGateway must not let a platform refusal escape into a receiver",
            service.contains("GatewayStartDeferral.defer(context)")
        )
        assertTrue(
            "the deferral must be a real unique WorkManager retry, not a silent catch",
            deferral.contains("enqueueUniqueWork")
        )
        assertTrue(
            "the deferred worker must be able to ask for another attempt",
            deferral.contains("Result.retry()")
        )
        assertTrue(
            "the worker's own retry is the deferral, so it must pass deferOnFailure = false",
            deferral.contains("deferOnFailure = false")
        )
    }
}
