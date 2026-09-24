package com.autonomousone.messages.mms

import android.app.Activity
import android.telephony.SmsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MMS send result, which used to have nowhere to arrive.
 *
 * `MmsSender.triggerSend` passed a **null** `PendingIntent` to `sendMultimediaMessage`, so the
 * platform's outcome was discarded: a failed MMS sat in `MESSAGE_BOX_OUTBOX` for ever looking like a
 * message that was still being sent, and nothing in the app or the gateway could tell a picture that
 * left the device from one that never did. That is the mission's "silently lost" shape, on the one
 * message type whose payload is a photo or a voice note.
 */
class MmsSendResultPolicyTest {

    @Test
    fun `onlyResultOkCountsAsSent`() {
        assertEquals(MmsSendVerdict.SENT, MmsSendResultPolicy.classify(Activity.RESULT_OK).verdict)
    }

    @Test
    fun `anUnrecognisedResultCodeIsNeverASuccess`() {
        // The property that matters: an outcome the app cannot interpret must not be reported as
        // delivered. This is where a `resultCode == RESULT_OK` check plus a default of "probably fine"
        // would go wrong.
        listOf(0, 1, 2, 55, -1, Int.MIN_VALUE, Int.MAX_VALUE).forEach { code ->
            val outcome = MmsSendResultPolicy.classify(code)
            if (code != Activity.RESULT_OK) {
                assertTrue(
                    "result $code must not be reported as sent",
                    outcome.verdict != MmsSendVerdict.SENT
                )
            }
        }
    }

    @Test
    fun `theSdkErrorConstantsAreClassifiedExhaustively`() {
        // Every MMS_ERROR_* the SDK defines must land on a named verdict rather than on UNKNOWN,
        // because UNKNOWN is what tells a reader "the app could not interpret this".
        val retryable = listOf(
            SmsManager.MMS_ERROR_NO_DATA_NETWORK,
            SmsManager.MMS_ERROR_DATA_DISABLED,
            SmsManager.MMS_ERROR_IO_ERROR,
            SmsManager.MMS_ERROR_HTTP_FAILURE,
            SmsManager.MMS_ERROR_UNABLE_CONNECT_MMS,
            SmsManager.MMS_ERROR_RETRY
        )
        retryable.forEach { code ->
            assertEquals(
                "code $code must be retryable",
                MmsSendVerdict.RETRYABLE,
                MmsSendResultPolicy.classify(code).verdict
            )
        }
        val permanent = listOf(
            SmsManager.MMS_ERROR_INVALID_APN,
            SmsManager.MMS_ERROR_CONFIGURATION_ERROR,
            SmsManager.MMS_ERROR_INVALID_SUBSCRIPTION_ID,
            SmsManager.MMS_ERROR_INACTIVE_SUBSCRIPTION,
            SmsManager.MMS_ERROR_MMS_DISABLED_BY_CARRIER
        )
        permanent.forEach { code ->
            assertEquals(
                "code $code must be permanent",
                MmsSendVerdict.PERMANENT,
                MmsSendResultPolicy.classify(code).verdict
            )
        }
        assertEquals(
            MmsSendVerdict.UNKNOWN,
            MmsSendResultPolicy.classify(SmsManager.MMS_ERROR_UNSPECIFIED).verdict
        )
    }

    @Test
    fun `waitingIsAdviceOnlyForRetryableFailures`() {
        // The distinction is the user-facing one. "Try again when you have data" is an action;
        // "your MMSC address is wrong" is not fixed by waiting, and telling someone to retry it is
        // worse than telling them nothing.
        assertTrue(MmsSendResultPolicy.classify(SmsManager.MMS_ERROR_NO_DATA_NETWORK).isTransient)
        assertFalse(MmsSendResultPolicy.classify(SmsManager.MMS_ERROR_INVALID_APN).isTransient)
        assertFalse(MmsSendResultPolicy.classify(Activity.RESULT_OK).isTransient)
        assertFalse(MmsSendResultPolicy.classify(SmsManager.MMS_ERROR_UNSPECIFIED).isTransient)
    }

    @Test
    fun `failureCoversEverythingThatIsNotAKnownSuccess`() {
        assertFalse(MmsSendResultPolicy.classify(Activity.RESULT_OK).verdict.isFailure)
        listOf(
            SmsManager.MMS_ERROR_IO_ERROR,
            SmsManager.MMS_ERROR_INVALID_APN,
            SmsManager.MMS_ERROR_UNSPECIFIED,
            12345
        ).forEach { code ->
            assertTrue("code $code must read as a failure", MmsSendResultPolicy.classify(code).verdict.isFailure)
        }
    }

    @Test
    fun `everyCodeIsStableAndDistinguishable`() {
        // The code is what a diagnostic and a support conversation carry, so two different results
        // must not produce the same one — that would make "MMS_IO_ERROR" cover a wrong APN too.
        val codes = listOf(
            Activity.RESULT_OK,
            SmsManager.MMS_ERROR_NO_DATA_NETWORK,
            SmsManager.MMS_ERROR_DATA_DISABLED,
            SmsManager.MMS_ERROR_IO_ERROR,
            SmsManager.MMS_ERROR_HTTP_FAILURE,
            SmsManager.MMS_ERROR_UNABLE_CONNECT_MMS,
            SmsManager.MMS_ERROR_RETRY,
            SmsManager.MMS_ERROR_INVALID_APN,
            SmsManager.MMS_ERROR_CONFIGURATION_ERROR,
            SmsManager.MMS_ERROR_INVALID_SUBSCRIPTION_ID,
            SmsManager.MMS_ERROR_INACTIVE_SUBSCRIPTION,
            SmsManager.MMS_ERROR_MMS_DISABLED_BY_CARRIER,
            SmsManager.MMS_ERROR_UNSPECIFIED
        ).map { MmsSendResultPolicy.classify(it).code }

        assertEquals("each known result needs its own code", codes.size, codes.toSet().size)
        assertTrue(codes.none { it.contains("UNKNOWN") })
    }

    @Test
    fun `anUnknownResultKeepsTheRawCode`() {
        // Losing it would make an unclassifiable failure unactionable: the number is the only thing
        // that lets someone look it up.
        assertTrue(MmsSendResultPolicy.classify(4242).code.contains("4242"))
    }

    @Test
    fun `theDiagnosticLineCarriesNoRecipientAndNoContent`() {
        // The MMS row id is a local provider row id, not a phone number, and it is what makes a
        // support conversation about one stuck picture possible at all.
        val line = MmsSendResultPolicy.describe(77L, MmsSendResultPolicy.classify(SmsManager.MMS_ERROR_IO_ERROR))

        assertTrue(line, line.contains("mms=77"))
        assertTrue(line, line.contains("MMS_IO_ERROR"))
        assertTrue(line, line.contains("RETRYABLE"))
        assertFalse("nothing that could be a phone number", line.contains("+"))
    }
}
