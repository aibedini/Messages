package com.autonomousone.messages.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The capability advertisement must equal what the build can execute.
 *
 * The drift this prevents is real and quiet: `runtime.commandTypes` is how GMweb decides what it may
 * send, so a type that is advertised but unroutable leaves a command spinning non-terminal for ever,
 * and a type that is routable but unadvertised is never used at all. Both lists used to be maintained
 * by hand; now one is derived from the other through [CommandRouting.routeOf], and this test pins it.
 */
class CommandRoutingDriftTest {

    @Test
    fun `every advertised type is routable`() {
        for (type in CommandRouting.ADVERTISED_COMMAND_TYPES) {
            assertFalse(
                "advertised but unroutable: $type",
                CommandRouting.routeOf(type) == CommandRoute.UNSUPPORTED
            )
        }
    }

    @Test
    fun `the advertised set equals the executable set`() {
        assertEquals(
            CommandRouting.ADVERTISED_COMMAND_TYPES.toSet(),
            CommandRouting.EXECUTABLE_COMMAND_TYPES
        )
    }

    @Test
    fun `the four required capabilities are advertised`() {
        assertEquals(
            listOf("SEND_SMS", "MARK_THREAD_READ", "REFRESH_DEVICE_TELEMETRY", "FETCH_THREAD_HISTORY"),
            CommandRouting.ADVERTISED_COMMAND_TYPES
        )
    }

    @Test
    fun `no type is advertised twice`() {
        val types = CommandRouting.ADVERTISED_COMMAND_TYPES

        assertEquals(types.size, types.toSet().size)
    }

    @Test
    fun `only SEND_SMS may enter the SMS pipeline`() {
        val pipelineTypes = CommandRouting.ADVERTISED_COMMAND_TYPES
            .filter { CommandRouting.routeOf(it) == CommandRoute.SMS_PIPELINE }

        assertEquals(listOf(CommandRouting.SEND_SMS), pipelineTypes)
    }

    @Test
    fun `each capability routes to its own executor`() {
        assertEquals(CommandRoute.SMS_PIPELINE, CommandRouting.routeOf(CommandRouting.SEND_SMS))
        assertEquals(CommandRoute.READ_THREAD, CommandRouting.routeOf(CommandRouting.MARK_THREAD_READ))
        assertEquals(
            CommandRoute.TELEMETRY_REFRESH,
            CommandRouting.routeOf(CommandRouting.REFRESH_DEVICE_TELEMETRY)
        )
        assertEquals(
            CommandRoute.THREAD_HISTORY,
            CommandRouting.routeOf(CommandRouting.FETCH_THREAD_HISTORY)
        )
    }

    @Test
    fun `an unknown type is explicitly unsupported, never silently ignored`() {
        assertEquals(CommandRoute.UNSUPPORTED, CommandRouting.routeOf("SOMETHING_NEW"))
        assertEquals(CommandRoute.UNSUPPORTED, CommandRouting.routeOf(""))
        assertFalse(CommandRouting.EXECUTABLE_COMMAND_TYPES.contains("SOMETHING_NEW"))
    }

    @Test
    fun `the advertised constant and the history command type cannot drift apart`() {
        assertEquals(ThreadHistoryCommand.TYPE, CommandRouting.FETCH_THREAD_HISTORY)
    }

    @Test
    fun `history selection is keyset, not offset based`() {
        val (selection, args) = com.autonomousone.messages.data.threadHistorySelection(
            source = "sms",
            threadId = 12345L,
            beforeDateMs = 1791000000000L,
            beforeProviderId = 987654L
        )

        assertTrue("the cursor is a strict (date, id) key", selection.contains("date < ?"))
        assertTrue("equal timestamps are broken by id", selection.contains("_id < ?"))
        assertFalse("an OFFSET would walk the whole thread", selection.uppercase().contains("OFFSET"))
        assertEquals(4, args.size)
        assertEquals("12345", args[0])
    }

    @Test
    fun `the first history page has no cursor`() {
        val (selection, args) = com.autonomousone.messages.data.threadHistorySelection(
            source = "sms",
            threadId = 12345L,
            beforeDateMs = null,
            beforeProviderId = null
        )

        assertEquals("thread_id = ?", selection)
        assertEquals(listOf("12345"), args.toList())
    }

    @Test
    fun `mms history uses the mms columns`() {
        val (selection, _) = com.autonomousone.messages.data.threadHistorySelection(
            source = "mms",
            threadId = 7L,
            beforeDateMs = 100L,
            beforeProviderId = 5L
        )

        assertTrue(selection.contains("thread_id = ?"))
        assertTrue(selection.contains("date < ?"))
    }
}
