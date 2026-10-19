package com.autonomousone.messages.gateway.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The entire message vocabulary is one bit (STAGE-4 §1/§6).
 *
 * A wake delivers exactly one `onWake`. Extra fields are never inspected; an unknown frame is
 * counted and ignored, never parsed into a command.
 */
class WakeFrameParsingTest : WakeSocketTestBase() {

    @Test
    fun `one wake frame delivers exactly one onWake`() {
        val server = newServer()
        val runtime = newRuntime(server)
        WakeTestHarness.awaitUntil(description = "upgrade") { server.upgradeCount.get() == 1 }

        server.sendWake()
        WakeTestHarness.awaitUntil(description = "one wake") { runtime.wakes.get() == 1 }

        Thread.sleep(150L)
        assertEquals("a single frame must not be delivered twice", 1, runtime.wakes.get())
        assertEquals(1L, runtime.lastHealth.get().wakeCount)
    }

    @Test
    fun `optional seq is not required and extra fields are never inspected`() {
        val clock = MutableClock(100_000L)
        val server = newServer()
        val runtime = newRuntime(server, clock = clock)
        WakeTestHarness.awaitUntil(description = "upgrade") { server.upgradeCount.get() == 1 }

        clock.now += 1_001L
        server.sendText(
            "{\"t\":\"wake\",\"seq\":7,\"to\":\"+981234567890\",\"body\":\"secret\"," +
                "\"command\":\"SEND_SMS\",\"requestId\":\"req-1\"}"
        )

        WakeTestHarness.awaitUntil(description = "one wake") { runtime.wakes.get() == 1 }
        assertEquals("a wake carrying forbidden fields is still only a wake", 1, runtime.wakes.get())
        assertEquals(0L, runtime.lastHealth.get().unknownFrames)
    }

    @Test
    fun `a command frame is ignored and counted, never executed`() {
        val clock = MutableClock(200_000L)
        val server = newServer()
        val runtime = newRuntime(server, clock = clock)
        WakeTestHarness.awaitUntil(description = "upgrade") { server.upgradeCount.get() == 1 }

        clock.now += 1_001L
        server.sendText("{\"t\":\"command\",\"command\":\"SEND_SMS\",\"to\":\"+981234567890\"}")
        clock.now += 1_001L
        server.sendText("{\"t\":\"ping\"}")
        clock.now += 1_001L
        server.sendBinary(byteArrayOf(1, 2, 3, 4))

        WakeTestHarness.awaitUntil(description = "three unknown frames") {
            runtime.lastHealth.get().unknownFrames >= 3L
        }
        assertEquals(0, runtime.wakes.get())
    }
}
