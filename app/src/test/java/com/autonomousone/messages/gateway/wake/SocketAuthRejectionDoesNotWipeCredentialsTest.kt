package com.autonomousone.messages.gateway.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * The trap that must never ship (STAGE-4 §4.4/§6).
 *
 * `AgentAuth`'s KDoc warns that a 401 makes the device wipe its credentials via
 * `ControlPlaneAuthPolicy`. A WebSocket upgrade refusal is not proof that the identity is invalid:
 * a server that simply has not deployed `/gateway/wake` may answer 401 or 404. The socket must treat
 * it as a transport failure, leave the credentials alone and fall back to polling.
 */
class SocketAuthRejectionDoesNotWipeCredentialsTest : WakeSocketTestBase() {

    private class FakeCredentialVault {
        @Volatile
        var deviceId: String = "device-test"
        val wipeCount = AtomicInteger(0)

        fun wipe() {
            wipeCount.incrementAndGet()
            deviceId = ""
        }
    }

    @Test
    fun `a 401 upgrade leaves the stored credentials untouched`() {
        val vault = FakeCredentialVault()
        val server = newServer(status = 401)
        val wakes = AtomicInteger(0)
        val fallbacks = AtomicInteger(0)
        val source = SocketWakeSource(
            config = SocketWakeConfig(
                endpoint = WakeTestHarness.endpoint(server.port),
                deviceId = vault.deviceId,
                activityPollMs = 40L,
            ),
            signer = WakeHandshakeSigner { deviceId, path, method, body ->
                // Read the CURRENT identity, exactly as the production signer does.
                assertEquals(vault.deviceId, deviceId)
                WakeHandshakeHeaders("$deviceId:c2ln", 1L)
            },
            startPollingFallback = { fallbacks.incrementAndGet() },
            activitySignals = { WakeActivitySignals(appForeground = true) },
            backoffMs = { 60_000L },
            baseClient = WakeTestHarness.client(),
        )
        try {
            source.start { wakes.incrementAndGet() }
            WakeTestHarness.awaitUntil(description = "fallback after 401") {
                fallbacks.get() == 1
            }

            assertEquals("a rejected upgrade must not wipe credentials", 0, vault.wipeCount.get())
            assertEquals("device-test", vault.deviceId)
            assertEquals(WakeUnavailable.TRANSPORT_FAILED, source.health.value.unavailable)
        } finally {
            source.stop()
            server.close()
        }
    }

    @Test
    fun `the socket source has no credential or enrollment call at all`() {
        val candidates = listOf(
            "src/main/java/com/autonomousone/messages/gateway/wake/SocketWakeSource.kt",
            "app/src/main/java/com/autonomousone/messages/gateway/wake/SocketWakeSource.kt",
        )
        val file = candidates.map { File(it) }.firstOrNull { it.isFile }
            ?: error("cannot locate SocketWakeSource.kt; looked in $candidates")
        val text = file.readText()

        listOf(
            "ControlPlaneAuthPolicy",
            "clearCloudCredentials",
            "RegistrationManager",
            "ensureEnrolled",
            "wipeCredentials",
        ).forEach { forbidden ->
            assertFalse(
                "the socket must not reach the credential-wipe path: found $forbidden",
                text.contains(forbidden)
            )
        }
        assertTrue(text.contains("TRANSPORT_FAILED"))
    }
}
