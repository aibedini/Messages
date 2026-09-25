package com.autonomousone.messages

import com.autonomousone.messages.data.RemoteCommandEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A terminal command transition must record WHY it ended and WHEN (mission §K, round 49–50).
 *
 * `markCommandState` sets only `state`. Using it to finish a command leaves `completedAt = 0` and an
 * empty `lastErrorCode`, so a web-requested send the radio refused reached GMweb as FAILED with no
 * reason — the exact thing the structured codes exist to prevent — and its duration was unknowable.
 *
 * WHY THE RULE LIVES IN THE CALLEE. A source guard over the call sites was written first and it
 * FALSE-PASSED: the most important site (`GatewayOutgoingPipeline`, the normal completion of a remote
 * SEND_SMS) passed a local `terminal` variable, so the scan — which looked for literal state names —
 * did not see it. A heuristic that depends on how a call site is written will keep missing shapes; a
 * rule the callee enforces cannot be dodged by any shape.
 */
class CommandTerminalTransitionTest {

    @Test
    fun `a state-only transition refuses every terminal state`() {
        RemoteCommandEntity.TERMINAL_STATES.forEach { state ->
            val refusal = RemoteCommandEntity.refusalForStateOnlyTransition(state)
            assertNotNull("$state must not be reachable through a state-only transition", refusal)
            assertTrue(
                "the refusal must say what to use instead: $refusal",
                refusal!!.contains("finishCommandFrom")
            )
        }
    }

    @Test
    fun `a state-only transition still allows every non-terminal state`() {
        // The rule must not be so broad that it blocks the legitimate uses — ACCEPTED and EXECUTING
        // are exactly what `markCommandState` is for, and refusing those would break the send
        // lifecycle while looking like a stricter guarantee.
        RemoteCommandEntity.NON_TERMINAL_STATES.forEach { state ->
            assertNull(
                "$state is a legitimate state-only transition",
                RemoteCommandEntity.refusalForStateOnlyTransition(state)
            )
        }
    }

    @Test
    fun `the terminal and non-terminal sets do not overlap and cover the lifecycle`() {
        val terminal = RemoteCommandEntity.TERMINAL_STATES.toSet()
        val nonTerminal = RemoteCommandEntity.NON_TERMINAL_STATES.toSet()
        assertEquals("the two sets must be disjoint", emptySet<String>(), terminal intersect nonTerminal)
        assertEquals(
            "every lifecycle state must be classified, or the rule has a blind spot",
            setOf("RECEIVED", "ACCEPTED", "EXECUTING", "COMPLETED", "FAILED", "EXPIRED"),
            terminal + nonTerminal
        )
    }
}
