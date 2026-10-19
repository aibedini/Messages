package com.autonomousone.messages.gateway.wake

import org.junit.After
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Lifecycle helpers shared by the STAGE-4 socket tests.
 *
 * Every source and server created here is closed in [tearDown], so a failing assertion cannot leave
 * a daemon reconnect loop competing with the next test.
 */
abstract class WakeSocketTestBase {

    private val cleanups = mutableListOf<() -> Unit>()

    protected data class Runtime(
        val source: SocketWakeSource,
        val wakes: AtomicInteger,
        val fallbacks: AtomicInteger,
        val lastHealth: AtomicReference<WakeHealth>,
    )

    @After
    fun tearDown() {
        cleanups.asReversed().forEach { runCatching { it() } }
        cleanups.clear()
    }

    protected fun newServer(
        status: Int = 101,
        extraHeaders: Map<String, String> = emptyMap(),
    ): FakeWakeServer {
        val server = FakeWakeServer(
            certificates = WakeTestHarness.serverCertificates,
            status = status,
            extraHeaders = extraHeaders,
        )
        cleanups += { server.close() }
        return server
    }

    protected fun newRuntime(
        server: FakeWakeServer,
        pins: WakeCertificatePins = WakeCertificatePins(),
        pingIntervalMs: Long = 240_000L,
        activityPollMs: Long = 40L,
        signals: () -> WakeActivitySignals = { WakeActivitySignals(appForeground = true) },
        clock: () -> Long = System::currentTimeMillis,
        backoffMs: (Int) -> Long = { 60_000L },
        configOverride: (SocketWakeConfig) -> SocketWakeConfig = { it },
    ): Runtime {
        val wakes = AtomicInteger(0)
        val fallbacks = AtomicInteger(0)
        val health = AtomicReference(WakeHealth(id = WakeSourceId.SOCKET, active = false))
        val source = SocketWakeSource(
            config = configOverride(
                SocketWakeConfig(
                    endpoint = WakeTestHarness.endpoint(server.port),
                    deviceId = "device-test",
                    pins = pins,
                    pingIntervalMs = pingIntervalMs,
                    activityPollMs = activityPollMs,
                )
            ),
            signer = WakeHandshakeSigner { _, _, _, _ ->
                WakeHandshakeHeaders("device-test:c2ln", 123456789L)
            },
            startPollingFallback = { fallbacks.incrementAndGet() },
            onHealth = { health.set(it) },
            activitySignals = { signals() },
            clock = clock,
            backoffMs = backoffMs,
            baseClient = WakeTestHarness.client(),
        )
        cleanups += { source.stop() }
        source.start { wakes.incrementAndGet() }
        return Runtime(source, wakes, fallbacks, health)
    }
}
