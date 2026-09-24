package com.autonomousone.messages.mms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mission §52: a payload that cannot be sent must be a named refusal, not a silent drop.
 *
 * Every part used to be written as `cr.openOutputStream(partUri)?.use { out -> out.write(bytes) }` and
 * the function then returned a valid MMS id regardless. The `?.` is the defect: a null stream means the
 * write never happened, yet the caller reported success — so an MMS with an EMPTY part was created,
 * handed to the platform and shown to the user as sent. `compressImage` had the same shape, returning
 * `ByteArray(0)` when the bitmap would not decode.
 *
 * The row is not merely a local artefact either: it is a real message in the provider, so the mirror
 * replicates it and GMweb shows a message that never existed.
 */
class MmsPayloadPolicyTest {

    private fun bytes(size: Int) = ByteArray(size) { 1 }

    @Test
    fun `anEmptyPayloadIsRefusedAndNamed`() {
        // The decode-failure case: `compressImage` returns ByteArray(0), which the old code wrote into
        // the part as if it were a picture.
        assertEquals(MmsPayloadVerdict.Empty, MmsPayloadPolicy.classify(bytes(0)))
        assertEquals("mms_empty_payload", MmsPayloadPolicy.code(MmsPayloadVerdict.Empty))
    }

    @Test
    fun `aNullPayloadIsUnavailableRatherThanEmpty`() {
        // Distinct because the causes differ: a stream that would not open is a provider/permission
        // problem, an empty decode is a bad image. Same refusal, different diagnosis.
        assertEquals(MmsPayloadVerdict.Unavailable, MmsPayloadPolicy.classify(null))
        assertEquals("mms_payload_unavailable", MmsPayloadPolicy.code(MmsPayloadVerdict.Unavailable))
    }

    @Test
    fun `theCapBoundaryIsExact`() {
        // Off-by-one here means either refusing a payload that would have worked, or writing one byte
        // more than a carrier accepts.
        assertTrue(MmsPayloadPolicy.classify(bytes(MmsPayloadPolicy.MAX_PART_BYTES)).isUsable)
        assertTrue(MmsPayloadPolicy.classify(bytes(MmsPayloadPolicy.MAX_PART_BYTES - 1)).isUsable)
        assertEquals(
            MmsPayloadVerdict.TooLarge(MmsPayloadPolicy.MAX_PART_BYTES + 1),
            MmsPayloadPolicy.classify(bytes(MmsPayloadPolicy.MAX_PART_BYTES + 1))
        )
    }

    @Test
    fun `aOneBytePayloadIsUsable`() {
        // The floor of "not empty" is one byte, and the boundary must not be `<= 1`.
        assertEquals(MmsPayloadVerdict.Usable(1), MmsPayloadPolicy.classify(bytes(1)))
    }

    @Test
    fun `classifySizeAgreesWithClassify`() {
        // A caller that counted bytes while copying must get the same answer as one that buffered
        // them, or the two paths would disagree about what is sendable.
        listOf(0, 1, 500, MmsPayloadPolicy.MAX_PART_BYTES, MmsPayloadPolicy.MAX_PART_BYTES + 1)
            .forEach { size ->
                assertEquals(
                    "size=$size",
                    MmsPayloadPolicy.classify(bytes(size)),
                    MmsPayloadPolicy.classifySize(size)
                )
            }
    }

    @Test
    fun `aUsablePayloadHasNoFailureCode`() {
        assertNull(MmsPayloadPolicy.code(MmsPayloadPolicy.classify(bytes(10))))
    }

    @Test
    fun `everyRefusalHasAStableCodeAndAReadableReason`() {
        val refusals = listOf(
            MmsPayloadVerdict.Empty,
            MmsPayloadVerdict.TooLarge(MmsPayloadPolicy.MAX_PART_BYTES + 5),
            MmsPayloadVerdict.Unavailable
        )

        val codes = refusals.map { MmsPayloadPolicy.code(it) }
        assertEquals("each refusal needs its own code", codes.size, codes.toSet().size)
        codes.forEach { assertTrue("a code must be machine-readable, not prose: $it", it!!.startsWith("mms_")) }
        refusals.forEach { assertFalse(MmsPayloadPolicy.reason(it).isBlank()) }
    }

    @Test
    fun `theReasonsNeverNameARecipientOrAnyContent`() {
        // These strings travel into log lines and REST responses, so they must be safe by
        // construction. A byte count is digits too, so the check is for phone-SHAPED runs (and a
        // leading `+`) rather than for any digit at all — a rule that flagged `1200000 bytes` would be
        // noise, and a rule that allowed `+98912…` would be useless.
        listOf(
            MmsPayloadVerdict.Empty,
            MmsPayloadVerdict.TooLarge(1_200_000),
            MmsPayloadVerdict.Unavailable
        ).forEach { verdict ->
            val reason = MmsPayloadPolicy.reason(verdict)
            assertFalse("a phone number cannot appear in: $reason", reason.contains("+"))
            assertFalse(
                "no phone-shaped digit run in: $reason",
                Regex("""\d{8,}""").containsMatchIn(reason)
            )
        }
    }

    @Test
    fun `theReasonsThatHaveNoSizeCarryNoDigitsAtAll`() {
        // The exact version, for the two verdicts where there is no legitimate number to quote.
        listOf(MmsPayloadVerdict.Empty, MmsPayloadVerdict.Unavailable).forEach { verdict ->
            assertFalse(MmsPayloadPolicy.reason(verdict), Regex("""\d""").containsMatchIn(MmsPayloadPolicy.reason(verdict)))
        }
    }

    @Test
    fun `theReadLimitIsOneByteOverTheCap`() {
        // Enough to tell "too large" from "fine" without buffering a tens-of-megabytes recording on
        // the send path.
        assertEquals(MmsPayloadPolicy.MAX_PART_BYTES + 1, MmsPayloadPolicy.READ_LIMIT_BYTES)
    }
}
