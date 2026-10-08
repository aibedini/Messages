package com.autonomousone.messages.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `SET_CONVERSATION_PREFERRED_SIM` contract and its failure taxonomy.
 *
 * ## The rule these tests exist for
 *
 * JSON cannot distinguish "the field is absent" from "the field is null", and here the difference is
 * the entire instruction:
 *
 * ```text
 * simRef absent  => INVALID  (the sender did not say what it wants)
 * simRef null    => CLEAR    (return to the phone default)
 * simRef "..."   => SET      (must resolve against the live inventory)
 * ```
 *
 * Treating absence as "clear" would destroy a user's explicit choice because a client forgot a key;
 * treating it as "keep the current value" would ACK a change that never happened. Both are silent, so
 * neither is acceptable, and both are pinned here.
 */
class ConversationSimCommandTest {

    private val refA = "sim:v1:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

    private fun payload(vararg pairs: Pair<String, Any?>): String {
        val json = JSONObject()
        for ((key, value) in pairs) json.put(key, value ?: JSONObject.NULL)
        return json.toString()
    }

    private fun ok(parsed: ConversationSimCommand.Parsed): ConversationSimCommand.Parsed.Ok {
        assertTrue("expected a valid command, got $parsed", parsed is ConversationSimCommand.Parsed.Ok)
        return parsed as ConversationSimCommand.Parsed.Ok
    }

    private fun code(parsed: ConversationSimCommand.Parsed): SyncErrorCode {
        assertTrue("expected an invalid command, got $parsed", parsed is ConversationSimCommand.Parsed.Invalid)
        return (parsed as ConversationSimCommand.Parsed.Invalid).code
    }

    // ── the three states ─────────────────────────────────────────────────────

    @Test
    fun `a well formed set is parsed with its reference`() {
        val parsed = ok(
            ConversationSimCommand.parse(payload("androidThreadId" to 123L, "simRef" to refA))
        )

        assertEquals(123L, parsed.androidThreadId)
        assertEquals(refA, parsed.simRef)
    }

    @Test
    fun `an explicit null is a CLEAR, not an error`() {
        val parsed = ok(
            ConversationSimCommand.parse(payload("androidThreadId" to 123L, "simRef" to null))
        )

        assertEquals(123L, parsed.androidThreadId)
        assertNull("null means: go back to the phone default", parsed.simRef)
    }

    @Test
    fun `an ABSENT simRef is invalid, never a silent clear`() {
        val parsed = ConversationSimCommand.parse(
            JSONObject().put("androidThreadId", 123L).toString()
        )

        assertEquals(SyncErrorCode.INVALID_SIM_REFERENCE, code(parsed))
    }

    @Test
    fun `absent and null are distinguishable from the same payload shape`() {
        // The two payloads differ by one key and mean opposite things; this is the assertion that the
        // parser does not collapse them.
        val absent = ConversationSimCommand.parse(
            JSONObject().put("androidThreadId", 1L).toString()
        )
        val explicitNull = ConversationSimCommand.parse(
            JSONObject().put("androidThreadId", 1L).put("simRef", JSONObject.NULL).toString()
        )

        assertTrue(absent is ConversationSimCommand.Parsed.Invalid)
        assertTrue(explicitNull is ConversationSimCommand.Parsed.Ok)
    }

    // ── reference validation ─────────────────────────────────────────────────

    @Test
    fun `a malformed reference is rejected before any lookup`() {
        for (bad in listOf("", "   ", "sim", "sim:v2:abc", "5", "sim:v1:NOTHEX", "sha256:x")) {
            val parsed = ConversationSimCommand.parse(
                payload("androidThreadId" to 1L, "simRef" to bad)
            )
            assertEquals("accepted a malformed reference: '$bad'",
                SyncErrorCode.INVALID_SIM_REFERENCE, code(parsed))
        }
    }

    @Test
    fun `a reference with the wrong token length is rejected`() {
        val shortRef = "sim:v1:aaaa"

        assertEquals(
            SyncErrorCode.INVALID_SIM_REFERENCE,
            code(ConversationSimCommand.parse(payload("androidThreadId" to 1L, "simRef" to shortRef)))
        )
    }

    @Test
    fun `a reference that is not a string is rejected`() {
        // A number or an object where a string belongs is malformed, not null: the sender is confused
        // about the contract, and guessing which part it meant is how a wrong line gets selected.
        for (bad in listOf(Any::class.java.let { 5 }, true)) {
            val parsed = ConversationSimCommand.parse(
                JSONObject().put("androidThreadId", 1L).put("simRef", bad).toString()
            )
            assertTrue("accepted a non-string reference: $bad",
                parsed is ConversationSimCommand.Parsed.Invalid)
        }
    }

    // ── thread validation ────────────────────────────────────────────────────

    @Test
    fun `a missing thread id is rejected`() {
        val parsed = ConversationSimCommand.parse(
            JSONObject().put("simRef", refA).toString()
        )

        assertEquals(SyncErrorCode.THREAD_NOT_FOUND, code(parsed))
    }

    @Test
    fun `a non-positive thread id is rejected`() {
        // 0 and negatives cannot address a real Telephony thread; accepting one would look up the wrong
        // conversation, or none, and ACK a change nobody asked for.
        for (bad in listOf(0L, -1L, -999L)) {
            assertEquals(
                "accepted thread id $bad",
                SyncErrorCode.THREAD_NOT_FOUND,
                code(ConversationSimCommand.parse(payload("androidThreadId" to bad, "simRef" to refA)))
            )
        }
    }

    @Test
    fun `thread validation runs before the reference so absence is not masked`() {
        // Order matters for the error a caller sees: a payload missing BOTH should report the
        // structural problem, not a reference complaint about a field that was never usable.
        val parsed = ConversationSimCommand.parse(JSONObject().put("simRef", refA).toString())

        assertEquals(SyncErrorCode.THREAD_NOT_FOUND, code(parsed))
    }

    // ── garbage input ────────────────────────────────────────────────────────

    @Test
    fun `a payload that is not JSON is invalid rather than a crash`() {
        for (bad in listOf("", "not json", "{", "[1,2,3]", "null")) {
            val parsed = ConversationSimCommand.parse(bad)
            assertTrue("accepted non-JSON payload '$bad'",
                parsed is ConversationSimCommand.Parsed.Invalid)
        }
    }

    // ── failure taxonomy ─────────────────────────────────────────────────────

    @Test
    fun `every preference failure maps to its own stable code`() {
        assertEquals(
            SyncErrorCode.THREAD_NOT_FOUND,
            ConversationSimError.classify(ConversationSimFailure.ThreadNotFound(1L))
        )
        assertEquals(
            SyncErrorCode.SIM_NOT_FOUND,
            ConversationSimError.classify(ConversationSimFailure.SimNotFound(refA))
        )
        assertEquals(
            SyncErrorCode.SIM_INACTIVE,
            ConversationSimError.classify(ConversationSimFailure.SimInactive(refA))
        )
        assertEquals(
            SyncErrorCode.PREFERENCE_UPDATE_FAILED,
            ConversationSimError.classify(ConversationSimFailure.PersistFailed())
        )
        assertEquals(
            SyncErrorCode.READ_EVENT_PUBLISH_FAILED,
            ConversationSimError.classify(ConversationSimFailure.PublishFailed())
        )
        assertEquals(
            SyncErrorCode.INVALID_SIM_REFERENCE,
            ConversationSimError.classify(
                ConversationSimFailure.InvalidPayload(SyncErrorCode.INVALID_SIM_REFERENCE)
            )
        )
    }

    @Test
    fun `an unclassified preference failure is a persistence failure, never a send failure`() {
        // The defect this prevents: reporting a preference problem as SMS_SEND_FAILED tells GMweb a
        // message failed when no message was involved.
        assertEquals(
            SyncErrorCode.PREFERENCE_UPDATE_FAILED,
            ConversationSimError.classify(IllegalStateException("boom"))
        )
    }

    @Test
    fun `a preference failure is never classified as an SMS send failure`() {
        val failures = listOf(
            ConversationSimFailure.ThreadNotFound(1L),
            ConversationSimFailure.SimNotFound(refA),
            ConversationSimFailure.SimInactive(refA),
            ConversationSimFailure.PersistFailed(),
            ConversationSimFailure.PublishFailed(),
            ConversationSimFailure.InventoryUnavailable(),
            IllegalStateException("boom")
        )

        for (failure in failures) {
            assertFalse(
                "$failure must not be reported as a send failure",
                ConversationSimError.classify(failure) == SyncErrorCode.SMS_SEND_FAILED
            )
        }
    }

    @Test
    fun `command routing recognises the preference command and declares it executable`() {
        assertEquals(
            CommandRoute.CONVERSATION_SIM,
            CommandRouting.routeOf(ConversationSimCommand.TYPE)
        )
        assertTrue(
            "the drain must treat the command as executable",
            CommandDrainPolicy.EXECUTABLE_TYPES.contains(ConversationSimCommand.TYPE)
        )
    }

    @Test
    fun `the preference command is never re-driven`() {
        // Re-running sets the same value, so it looks idempotent — but an abandoned attempt re-applied
        // against a CHANGED inventory is how a stale instruction becomes a preference nobody chose.
        assertFalse(
            "a preference command must not be re-driven after an abandoned attempt",
            CommandDrainPolicy.RE_DRIVABLE_TYPES.contains(ConversationSimCommand.TYPE)
        )
    }

    @Test
    fun `classifyFor routes the preference command to the preference taxonomy`() {
        // The poller classifies by command type; a preference command reaching the SEND fallback would
        // report SMS_SEND_FAILED for a non-send.
        assertEquals(
            SyncErrorCode.SIM_NOT_FOUND,
            ReadCommandError.classifyFor(
                ConversationSimCommand.TYPE,
                ConversationSimFailure.SimNotFound(refA)
            )
        )
        assertEquals(
            SyncErrorCode.SMS_SEND_FAILED,
            ReadCommandError.classifyFor("SEND_SMS", IllegalStateException("boom"))
        )
    }
}
