package com.autonomousone.messages.sync

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A read command's failure must name the condition.
 *
 * This exists because the read path used to report every failure as `SMS_SEND_FAILED` — a SEND code
 * on a read command — in the execution ledger, and `UNKNOWN` plus prose to GMweb. A conversation the
 * phone has no mapping for therefore looked exactly like a transient provider refusal, although they
 * need opposite responses.
 */
class ReadCommandErrorTest {

    @Test
    fun `a missing conversation mapping is its own code`() {
        val code = ReadCommandError.classify(ReadCommandFailure.MappingMissing("conv-1"))
        assertEquals(SyncErrorCode.READ_MAPPING_MISSING, code)
    }

    @Test
    fun `a refused provider write is distinct from a missing mapping`() {
        assertEquals(
            SyncErrorCode.READ_PROVIDER_WRITE_FAILED,
            ReadCommandError.classify(ReadCommandFailure.ProviderWriteFailed("SecurityException"))
        )
    }

    @Test
    fun `a refused publish is distinct from both a provider failure and a success`() {
        assertEquals(
            SyncErrorCode.READ_EVENT_PUBLISH_FAILED,
            ReadCommandError.classify(ReadCommandFailure.EventPublishFailed("SKIPPED_LOCAL_ONLY"))
        )
    }

    @Test
    fun `decrypt and payload failures are separated`() {
        assertEquals(
            SyncErrorCode.READ_COMMAND_DECRYPT_FAILED,
            ReadCommandError.classify(ReadCommandFailure.Decrypt())
        )
        assertEquals(
            SyncErrorCode.READ_INVALID_PAYLOAD,
            ReadCommandError.classify(ReadCommandFailure.InvalidPayload())
        )
        // A JSON parse failure out of the payload is the same condition, seen one frame lower.
        assertEquals(
            SyncErrorCode.READ_INVALID_PAYLOAD,
            ReadCommandError.classify(JSONException("no conversationId"))
        )
    }

    @Test
    fun `a permission refusal is named`() {
        assertEquals(
            SyncErrorCode.READ_PERMISSION_DENIED,
            ReadCommandError.classify(SecurityException("no READ_PHONE_STATE"))
        )
    }

    @Test
    fun `an unmapped-but-present thread is distinguished from an absent mapping`() {
        assertEquals(
            SyncErrorCode.READ_UNKNOWN_CONVERSATION,
            ReadCommandError.classify(ReadCommandFailure.UnknownConversation(42L))
        )
    }

    @Test
    fun `a read failure is never classified as a send failure`() {
        val causes = listOf(
            ReadCommandFailure.Decrypt(),
            ReadCommandFailure.InvalidPayload(),
            ReadCommandFailure.MappingMissing("c"),
            ReadCommandFailure.UnknownConversation(1L),
            ReadCommandFailure.ProviderWriteFailed("x"),
            ReadCommandFailure.EventPublishFailed("y"),
            SecurityException(),
            JSONException("z"),
            IllegalStateException("unclassified")
        )
        for (cause in causes) {
            assertEquals(
                "a read failure must never be recorded as a SEND failure ($cause)",
                false,
                ReadCommandError.classifyFor(ReadCommandError.READ_COMMAND_TYPE, cause) ==
                    SyncErrorCode.SMS_SEND_FAILED
            )
        }
    }

    @Test
    fun `a send command keeps its own code`() {
        assertEquals(
            SyncErrorCode.SMS_SEND_FAILED,
            ReadCommandError.classifyFor("SEND_SMS", ReadCommandFailure.MappingMissing("c"))
        )
    }

    @Test
    fun `an unclassifiable cause is honestly unknown`() {
        assertEquals(
            SyncErrorCode.UNKNOWN,
            ReadCommandError.classify(IllegalStateException("something new"))
        )
    }

    @Test
    fun `no read failure is treated as retryable-by-waiting`() {
        // Every one of these needs a human or a policy decision, not a retry ladder: a missing
        // mapping never appears by waiting, and a LOCAL_ONLY conversation stays LOCAL_ONLY.
        val codes = listOf(
            SyncErrorCode.READ_COMMAND_DECRYPT_FAILED,
            SyncErrorCode.READ_INVALID_PAYLOAD,
            SyncErrorCode.READ_MAPPING_MISSING,
            SyncErrorCode.READ_UNKNOWN_CONVERSATION,
            SyncErrorCode.READ_PROVIDER_WRITE_FAILED,
            SyncErrorCode.READ_EVENT_PUBLISH_FAILED,
            SyncErrorCode.READ_PERMISSION_DENIED
        )
        for (code in codes) {
            assertEquals("$code must not be transient", false, code.isTransient)
        }
    }
}
