package com.autonomousone.messages.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The reboot re-arm may not use a `dataSync` foreground service (Workstream C/F).
 *
 * The defect this pins: `BootGatewayReceiver` called `GatewayService.startGateway(context)`, which
 * called `context.startForegroundService` with the `dataSync or specialUse` type unconditionally.
 * On Android 15+ an app targeting 35+ may not launch a `dataSync` foreground service from a
 * `BOOT_COMPLETED` receiver, so the reboot re-arm threw. The gateway's only scheduler is a one-time
 * send worker, so nothing else revived it: after a reboot on a modern device the gateway stayed
 * down until the user opened the app, taking the realtime bridge and any web-requested sends with
 * it.
 *
 * The decision is a pure function so the answer is inspectable for platforms this build has never
 * run on, and so the regression cannot hide inside a Service callback.
 */
class GatewayForegroundStartPolicyTest {

    private val mainRoots = listOf("src/main", "app/src/main")

    private fun source(relative: String): String =
        mainRoots.map { File(it, relative) }.firstOrNull { it.isFile }?.readText()
            ?: error("cannot locate $relative")

    /**
     * Executable text only: line comments and KDoc continuation lines removed.
     *
     * This is not tidiness. The first version of `a refused start is caught and deferred rather than
     * thrown` asserted `deferral.contains("deferOnFailure = false")` against the RAW file, and it
     * passed while the code no longer contained that argument — because the phrase survived in a
     * comment. A guard that reads prose passes on a change to the prose, which is precisely the
     * false pass these guards exist to prevent. Every structural assertion below now reads code.
     */
    private fun codeOnly(relative: String): String = source(relative).lineSequence()
        .map { line ->
            val withoutLineComment = line.substringBefore("//")
            if (withoutLineComment.trimStart().startsWith("*")) "" else withoutLineComment
        }
        .joinToString("\n")

    // ── The decision ─────────────────────────────────────────────────────────

    @Test
    fun `a boot start on Android 15 or newer must not ask for dataSync`() {
        listOf(35, 36, 40).forEach { api ->
            assertEquals(
                "API $api boot start must drop dataSync",
                GatewayForegroundStartPolicy.Decision.START_SPECIAL_USE_ONLY,
                GatewayForegroundStartPolicy.decide(api, GatewayForegroundStartPolicy.StartReason.BOOT)
            )
        }
    }

    @Test
    fun `a boot start below Android 15 may still use the honest dataSync type`() {
        // The restriction is Android 15+; older platforms have no reason to be degraded, and the
        // richer type is what keeps Doze from freezing the bridge's sockets.
        listOf(26, 30, 33, 34).forEach { api ->
            assertEquals(
                "API $api boot start keeps dataSync",
                GatewayForegroundStartPolicy.Decision.START_WITH_DATA_SYNC,
                GatewayForegroundStartPolicy.decide(api, GatewayForegroundStartPolicy.StartReason.BOOT)
            )
        }
    }

    @Test
    fun `only a boot start is restricted`() {
        // A user-driven or retry start is not a boot receiver, so it must keep the full type even on
        // the newest platform — otherwise this fix would quietly weaken the bridge on every start.
        listOf(
            GatewayForegroundStartPolicy.StartReason.USER_OR_APP,
            GatewayForegroundStartPolicy.StartReason.RETRY
        ).forEach { reason ->
            listOf(35, 36, 40).forEach { api ->
                assertEquals(
                    "$reason on API $api must keep dataSync",
                    GatewayForegroundStartPolicy.Decision.START_WITH_DATA_SYNC,
                    GatewayForegroundStartPolicy.decide(api, reason)
                )
            }
        }
    }

    @Test
    fun `the decision and the type bitmask cannot disagree`() {
        // The service derives the bitmask through includesDataSync rather than re-writing the
        // condition, so this asserts the two questions stay consistent for every combination.
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
        // Fail-safe direction matters: if the extra is lost, claiming BOOT would silently downgrade
        // the type for foreground starts, while claiming USER_OR_APP only risks a refusal — which
        // the service catches and defers.
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

    // ── Revival after the system stops the service ──────────────────────────

    @Test
    fun `a background restart is not attempted through an alarm where the platform refuses it`() {
        // From API 31 the system performs a background foreground-service start and refuses it with
        // no callback into this process, so an alarm-based revival is not merely ineffective — it is
        // invisible. The service must rely on WorkManager there, which retries and can report.
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
        // Below API 31 the direct background start works, so the quick 15-second revival is worth
        // keeping — but WorkManager is still armed, because the alarm is best-effort under Doze.
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
        // The ordering is the fix: the deferral happens FIRST and unconditionally (past the user-intent
        // check), so a platform that refuses the alarm still has a durable, retrying path. Reversing
        // the two would restore the old silent failure on exactly the platforms it affects.
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
        // The `dataSync` time limit stops the bridge in the background. Ignoring `onTimeout` means the
        // service lingers past a timeout (the documented route to an ANR) and nothing records that the
        // bridge went dark.
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

    // ── Structural guards on the shipped path ────────────────────────────────

    @Test
    fun `the boot receiver never starts the service itself`() {
        // A future edit that goes back to calling startForegroundService directly from the receiver
        // would reintroduce the crash at boot without touching the policy, and every test above
        // would still pass.
        val receiver = codeOnly("java/com/autonomousone/messages/receiver/BootGatewayReceiver.kt")
        assertFalse(
            "the boot receiver must not start a foreground service directly — the type is illegal " +
                "from BOOT_COMPLETED on Android 15+: $receiver",
            receiver.contains("startForegroundService")
        )
        assertTrue(
            "the boot receiver must identify itself as a boot start",
            receiver.contains("GatewayForegroundStartPolicy.StartReason.BOOT")
        )
    }

    @Test
    fun `the service chooses the type through the policy and never inside a catch`() {
        val service = codeOnly("java/com/autonomousone/messages/gateway/GatewayService.kt")
        assertTrue(
            "the foreground type must come from the policy",
            service.contains("GatewayForegroundStartPolicy.decide(")
        )
        assertTrue(
            "the start reason must be recorded from the intent",
            service.contains("recordStartReason(intent)")
        )
        // The old unconditional pair is what made the boot start illegal; asserting its absence
        // pins the specific expression rather than the general intent.
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
        // The worker must NOT also defer: each would then enqueue work for the other.
        assertTrue(
            "the worker's own retry is the deferral, so it must pass deferOnFailure = false",
            deferral.contains("deferOnFailure = false")
        )
    }
}
