package com.autonomousone.messages.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Command routing, pinned.
 *
 * Two of these assertions are P0 regressions:
 *
 *  - an UNSUPPORTED type must never be silently dropped: the old `execute()` returned early and left
 *    the claimed row non-terminal for ever, so GMweb's optimistic entry spun with nothing on the
 *    device working on it;
 *  - `REFRESH_DEVICE_TELEMETRY` must never reach the SMS pipeline — that pipeline exists to send
 *    messages, and a refresh routed into it would be treated as one.
 */
class CommandRoutingTest {

    @Test
    fun `send sms is the only type that enters the sms pipeline`() {
        val pipelineRoutes = listOf(
            CommandRouting.SEND_SMS,
            CommandRouting.MARK_THREAD_READ,
            CommandRouting.REFRESH_DEVICE_TELEMETRY,
            "SOMETHING_NEW"
        ).filter { CommandRouting.routeOf(it) == CommandRoute.SMS_PIPELINE }

        assertEquals(listOf(CommandRouting.SEND_SMS), pipelineRoutes)
    }

    @Test
    fun `a telemetry refresh routes to telemetry, not to the sms pipeline`() {
        assertEquals(
            CommandRoute.TELEMETRY_REFRESH,
            CommandRouting.routeOf(CommandRouting.REFRESH_DEVICE_TELEMETRY)
        )
        assertFalse(
            CommandRouting.routeOf(CommandRouting.REFRESH_DEVICE_TELEMETRY) == CommandRoute.SMS_PIPELINE
        )
    }

    @Test
    fun `mark thread read routes to the read path`() {
        assertEquals(CommandRoute.READ_THREAD, CommandRouting.routeOf(CommandRouting.MARK_THREAD_READ))
    }

    @Test
    fun `an unknown type is explicitly UNSUPPORTED rather than a default that looks executable`() {
        for (type in listOf("", "UNKNOWN", "REFRESH_TELEMETRY", "send_sms", "MARK_THREAD_READ ")) {
            assertEquals(
                "type [$type] must not be routed to an executor",
                CommandRoute.UNSUPPORTED,
                CommandRouting.routeOf(type)
            )
        }
    }

    @Test
    fun `every supported type is also considered executable by the drain policy`() {
        // A type the poller executes but the drain does not know is a row that can strand.
        val supported = listOf(
            CommandRouting.SEND_SMS,
            CommandRouting.MARK_THREAD_READ,
            CommandRouting.REFRESH_DEVICE_TELEMETRY
        )
        for (type in supported) {
            assertTrue(
                "$type must be executable by the drain",
                type in CommandDrainPolicy.EXECUTABLE_TYPES
            )
        }
        assertTrue(
            "an unknown type must never be in the executable set",
            CommandRouting.routeOf("SOMETHING_NEW") == CommandRoute.UNSUPPORTED &&
                "SOMETHING_NEW" !in CommandDrainPolicy.EXECUTABLE_TYPES
        )
    }

    @Test
    fun `a telemetry refresh is not re-drivable`() {
        // Re-driving it is harmless but pointless: the fail-safe default (never re-run an unlisted
        // type) is kept minimal on purpose, and only the genuinely idempotent read is listed.
        assertFalse(CommandRouting.REFRESH_DEVICE_TELEMETRY in CommandDrainPolicy.RE_DRIVABLE_TYPES)
        assertTrue(CommandRouting.MARK_THREAD_READ in CommandDrainPolicy.RE_DRIVABLE_TYPES)
        assertFalse("SEND_SMS must never be re-drivable", CommandRouting.SEND_SMS in CommandDrainPolicy.RE_DRIVABLE_TYPES)
    }
}
