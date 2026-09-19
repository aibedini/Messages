package com.autonomousone.messages

import com.autonomousone.messages.sms.DelayPlan
import com.autonomousone.messages.sms.DelayedSendGate
import com.autonomousone.messages.sms.DelayedSendState
import com.autonomousone.messages.sms.DelayedSendStateMachine
import com.autonomousone.messages.sms.DelayedSendTransitions
import com.autonomousone.messages.sms.PendingDelayedSend
import com.autonomousone.messages.sms.SendSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.4.0 FEATURE 11 — the Send-delay POLICY, with no Android, no Room and no
 * WorkManager in sight.
 *
 * The product promise is "hold this message for N seconds, and if I change my
 * mind before the deadline, it never leaves the phone". Every rule that promise
 * depends on is a pure function, so it is asserted here directly rather than
 * inferred from a UI test:
 *
 *  * delay 0 (the default) must be the ABSENCE of the feature;
 *  * only the composer may be delayed;
 *  * the state machine has no edge out of SENT or CANCELLED;
 *  * undo is legal exactly while PENDING.
 */
class DelayedSendStateMachineTest {

    private val now = 1_700_000_000_000L

    // ── The gate: which sends are delayed at all ─────────────────────────────

    @Test
    fun `delay zero is immediate - the feature is absent, not a zero-length hold`() {
        val plan = DelayedSendGate.plan(SendSource.COMPOSER, delayMillis = 0L, now = now)
        assertEquals(
            "delay OFF must take the unchanged direct SmsSender path",
            DelayPlan.Immediate,
            plan
        )
    }

    @Test
    fun `a negative delay is treated as OFF and never schedules in the past`() {
        assertEquals(
            DelayPlan.Immediate,
            DelayedSendGate.plan(SendSource.COMPOSER, delayMillis = -5_000L, now = now)
        )
    }

    @Test
    fun `a positive composer delay produces a deadline of now plus the delay`() {
        val plan = DelayedSendGate.plan(SendSource.COMPOSER, delayMillis = 10_000L, now = now)
        assertEquals(DelayPlan.Delayed(delayMillis = 10_000L, dueAt = now + 10_000L), plan)
    }

    @Test
    fun `only the composer is delayed`() {
        // Exhaustive over the enum on purpose: a new SendSource must be
        // classified here deliberately, not inherit the delay by accident.
        val delayed = SendSource.entries.filter { DelayedSendGate.appliesTo(it) }
        assertEquals(listOf(SendSource.COMPOSER), delayed)
    }

    @Test
    fun `notification quick reply bypasses the delay - there is no in-notification undo`() {
        assertFalse(DelayedSendGate.appliesTo(SendSource.NOTIFICATION_REPLY))
        assertEquals(
            "quick reply must never be held",
            DelayPlan.Immediate,
            DelayedSendGate.plan(SendSource.NOTIFICATION_REPLY, delayMillis = 30_000L, now = now)
        )
    }

    @Test
    fun `gateway automated scheduled and external sends bypass the delay`() {
        listOf(
            SendSource.GATEWAY,
            SendSource.AUTOMATED,
            SendSource.SCHEDULED,
            SendSource.EXTERNAL
        ).forEach { source ->
            assertEquals(
                "$source must not be delayed",
                DelayPlan.Immediate,
                DelayedSendGate.plan(source, delayMillis = 30_000L, now = now)
            )
        }
    }

    @Test
    fun `delay presets offered by settings are exactly off 3 5 10 30`() {
        assertEquals(
            listOf(0L, 3_000L, 5_000L, 10_000L, 30_000L),
            com.autonomousone.messages.messaging.SendDelayPreferences
                .PRESET_MILLIS.map { it.toLong() }
        )
    }

    // ── The state machine ────────────────────────────────────────────────────

    @Test
    fun `the transition table is exactly the six documented edges`() {
        assertEquals(
            setOf(
                DelayedSendState.PENDING to DelayedSendState.SENDING,
                DelayedSendState.PENDING to DelayedSendState.CANCELLED,
                DelayedSendState.PENDING to DelayedSendState.FAILED,
                DelayedSendState.SENDING to DelayedSendState.SENT,
                DelayedSendState.SENDING to DelayedSendState.FAILED,
                DelayedSendState.SENDING to DelayedSendState.CANCELLED
            ),
            DelayedSendTransitions.LEGAL
        )
    }

    @Test
    fun `CANCELLED can never become SENT`() {
        assertFalse(
            DelayedSendTransitions.isLegal(
                DelayedSendState.CANCELLED,
                DelayedSendState.SENT
            )
        )
        assertNotNull(
            "the refusal must be explainable",
            DelayedSendTransitions.refusal(DelayedSendState.CANCELLED, DelayedSendState.SENT)
        )
    }

    @Test
    fun `no terminal state can re-enter execution`() {
        listOf(
            DelayedSendState.SENT,
            DelayedSendState.CANCELLED,
            DelayedSendState.FAILED
        ).forEach { terminal ->
            assertTrue("$terminal must be terminal", terminal.isTerminal)
            DelayedSendState.entries.forEach { target ->
                assertFalse(
                    "$terminal -> $target must be illegal",
                    DelayedSendTransitions.isLegal(terminal, target)
                )
            }
        }
    }

    @Test
    fun `PENDING and SENDING are the only non-terminal states`() {
        assertFalse(DelayedSendState.PENDING.isTerminal)
        assertFalse(DelayedSendState.SENDING.isTerminal)
    }

    @Test
    fun `sendable states are derived from the transition table`() {
        // Only PENDING can be claimed; SENDING is the RESULT of a claim and must
        // never be claimable again, or one message would be submitted twice.
        assertEquals(setOf(DelayedSendState.PENDING), DelayedSendTransitions.SENDABLE)
    }

    // ── The claim predicate ──────────────────────────────────────────────────

    @Test
    fun `a pending message becomes sendable exactly at its deadline`() {
        val due = now + 10_000L
        assertFalse(
            "before the deadline the message must stay undoable",
            DelayedSendStateMachine.mayClaim(DelayedSendState.PENDING, due, now)
        )
        assertFalse(
            DelayedSendStateMachine.mayClaim(DelayedSendState.PENDING, due, due - 1)
        )
        assertTrue(
            DelayedSendStateMachine.mayClaim(DelayedSendState.PENDING, due, due)
        )
    }

    @Test
    fun `a message that is already sending or terminal can never be claimed again`() {
        val due = now - 1_000L
        listOf(
            DelayedSendState.SENDING,
            DelayedSendState.SENT,
            DelayedSendState.FAILED,
            DelayedSendState.CANCELLED
        ).forEach { state ->
            assertFalse(
                "$state must not be claimable",
                DelayedSendStateMachine.mayClaim(state, due, now)
            )
        }
    }

    @Test
    fun `late delivery still sends - the deadline is a lower bound, not an equality`() {
        // WorkManager may deliver hours late (Doze, reboot). A late delivery must
        // still send, or a delayed message would be silently dropped.
        val row = row(state = DelayedSendState.PENDING, dueAt = now - 3_600_000L)
        assertTrue(row.isSendable(now))
        assertEquals(0L, row.remainingMillis(now))
    }

    @Test
    fun `undo is allowed exactly while pending`() {
        assertTrue(DelayedSendStateMachine.mayUndo(DelayedSendState.PENDING))
        listOf(
            DelayedSendState.SENDING,
            DelayedSendState.SENT,
            DelayedSendState.FAILED,
            DelayedSendState.CANCELLED
        ).forEach { state ->
            assertFalse("$state must not be undoable", DelayedSendStateMachine.mayUndo(state))
        }
    }

    @Test
    fun `canUndo mirrors mayUndo on the row`() {
        assertTrue(row(state = DelayedSendState.PENDING).canUndo)
        assertFalse(row(state = DelayedSendState.SENDING).canUndo)
        assertFalse(row(state = DelayedSendState.SENT).canUndo)
        assertFalse(row(state = DelayedSendState.CANCELLED).canUndo)
    }

    @Test
    fun `a failed radio call is never retried - the claim consumes the send`() {
        // Retrying after a lost/held claim is exactly how one message becomes two
        // billable submits, so the policy is a hard NO.
        assertFalse(DelayedSendStateMachine.shouldRetryAfterFailure())
    }

    @Test
    fun `an unknown persisted state fails closed instead of becoming pending`() {
        assertEquals(DelayedSendState.FAILED, DelayedSendState.from("SOMETHING_ELSE"))
        assertEquals(DelayedSendState.FAILED, DelayedSendState.from(null))
        assertEquals(DelayedSendState.PENDING, DelayedSendState.from("PENDING"))
    }

    @Test
    fun `remaining time counts down and never goes negative`() {
        val row = row(state = DelayedSendState.PENDING, dueAt = now + 4_000L)
        assertEquals(4_000L, row.remainingMillis(now))
        assertEquals(1L, row.remainingMillis(now + 3_999L))
    }

    private fun row(
        state: DelayedSendState,
        dueAt: Long = now
    ): PendingDelayedSend = PendingDelayedSend(
        intentId = "dly_test",
        body = "hello",
        phoneToken = "abc1234567",
        threadId = 7L,
        state = state,
        dueAt = dueAt,
        createdAt = now
    )
}

/**
 * The recipient cross-check that guards a delayed send whose durable job and
 * ledger row could disagree about WHO the message goes to.
 */
class DelayedSendRecipientTokenTest {

    @Test
    fun `a matching recipient passes the cross-check`() {
        val phone = "+989121234567"
        assertEquals(
            com.autonomousone.messages.sms.RecipientTokenPolicy.Verdict.MATCH,
            com.autonomousone.messages.sms.RecipientTokenPolicy
                .verify(phone, com.autonomousone.messages.utils.PhoneToken.of(phone))
        )
    }

    @Test
    fun `a different recipient is refused`() {
        val verdict = com.autonomousone.messages.sms.RecipientTokenPolicy.verify(
            phone = "+989121234567",
            rowToken = com.autonomousone.messages.utils.PhoneToken.of("+989129999999")
        )
        assertEquals(
            com.autonomousone.messages.sms.RecipientTokenPolicy.Verdict.MISMATCH,
            verdict
        )
        assertFalse(
            "a mismatched recipient must never be sent to",
            com.autonomousone.messages.sms.RecipientTokenPolicy.allowsSend(verdict)
        )
    }

    @Test
    fun `a row with no token accepts the job's recipient`() {
        // Rows written by an older build have no token; refusing would strand a
        // legitimate message for a missing diagnostic aid.
        val verdict = com.autonomousone.messages.sms.RecipientTokenPolicy.verify(
            phone = "+989121234567",
            rowToken = ""
        )
        assertEquals(
            com.autonomousone.messages.sms.RecipientTokenPolicy.Verdict.UNKNOWN_ROW_TOKEN,
            verdict
        )
        assertTrue(com.autonomousone.messages.sms.RecipientTokenPolicy.allowsSend(verdict))
    }

    @Test
    fun `the token is stable, trimmed and never the number itself`() {
        val token = com.autonomousone.messages.utils.PhoneToken.of("  +989121234567  ")
        assertEquals(com.autonomousone.messages.utils.PhoneToken.of("+989121234567"), token)
        assertEquals(10, token.length)
        assertFalse("the token must not contain the number", token.contains("98912"))
        assertEquals("none", com.autonomousone.messages.utils.PhoneToken.of("   "))
    }

    @Test
    fun `diagnostics and the ledger agree on the token`() {
        val phone = "+989121234567"
        assertEquals(
            com.autonomousone.messages.utils.DiagnosticLog.phoneToken(phone),
            com.autonomousone.messages.utils.PhoneToken.of(phone)
        )
    }

    @Test
    fun `the mismatch failure code is stable`() {
        assertEquals(
            "RECIPIENT_MISMATCH",
            com.autonomousone.messages.sms.RecipientTokenPolicy.CODE_RECIPIENT_MISMATCH
        )
    }
}
