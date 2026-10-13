package com.autonomousone.messages.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 3GPP TP-Status decoder, against the specification.
 *
 * ## The production bug this pins down
 *
 * `classify3gppTpStatus` was four bit-ranges with `0x00..0x1F -> DELIVERED`. Per 3GPP TS 23.040
 * §9.2.3.15 only `0x00` means "received by the SME"; the same range holds
 *
 * ```text
 * 0x01  forwarded by the SC, but the SC is UNABLE TO CONFIRM DELIVERY
 * 0x02  short message REPLACED by the SC
 * ```
 *
 * so the app reported **confirmed delivery** for two codes that explicitly deny it. Claiming a message
 * arrived when the network said it could not confirm that is the worst direction to be wrong in.
 *
 * ## What is asserted here
 *
 * Codes are grouped by the standard's own category boundaries, and every individually-named code is
 * checked, plus the reserved and carrier-specific ranges — because "adjacent to 0x00" is not evidence
 * that anything arrived.
 *
 * Reference: 3GPP TS 23.040 §9.2.3.15.
 */
class SmsTpStatusTest {

    private fun evidence(code: Int) = SmsTpStatus.decode(code).evidence

    // ── the completed range: only 0x00 is a delivery ──────────────────────────

    @Test
    fun `0x00 is the only confirmed delivery`() {
        val d = SmsTpStatus.decode(0x00)
        assertEquals(SmsTpStatus.Evidence.DELIVERED, d.evidence)
        assertEquals(SmsTpStatus.Category.COMPLETED, d.category)
        assertTrue("the network gave a final answer", d.isTerminal)
        assertFalse(d.carrierStillRetrying)
        assertEquals("DELIVERED", d.symbolicName)
    }

    @Test
    fun `0x01 is forwarded-but-unconfirmed, never a delivery`() {
        val d = SmsTpStatus.decode(0x01)
        assertEquals(SmsTpStatus.Evidence.NOT_CONFIRMED, d.evidence)
        assertNotEquals("this code explicitly denies confirmation", SmsTpStatus.Evidence.DELIVERED, d.evidence)
        assertNotEquals(
            "the network never said it failed, so it must not be a failure either",
            SmsTpStatus.Evidence.FAILED, d.evidence
        )
        assertEquals("FORWARDED_NOT_CONFIRMED", d.symbolicName)
        assertTrue(d.isTerminal)
        assertTrue("the reason must say the network could not confirm", d.reason.contains("confirm"))
    }

    @Test
    fun `0x02 replaced-by-SC is never a delivery`() {
        val d = SmsTpStatus.decode(0x02)
        assertEquals(SmsTpStatus.Evidence.NOT_CONFIRMED, d.evidence)
        assertNotEquals(SmsTpStatus.Evidence.DELIVERED, d.evidence)
        assertEquals("REPLACED_BY_SC", d.symbolicName)
        assertTrue(d.reason.contains("replaced"))
    }

    @Test
    fun `reserved completed codes 0x03 to 0x0F are not deliveries`() {
        for (code in 0x03..0x0F) {
            assertEquals(
                "reserved code $code must not be read as success",
                SmsTpStatus.Evidence.NOT_CONFIRMED,
                evidence(code)
            )
        }
    }

    @Test
    fun `carrier-specific completed codes 0x10 to 0x1F are not deliveries`() {
        for (code in 0x10..0x1F) {
            val d = SmsTpStatus.decode(code)
            assertEquals("SC-specific $code must not be read as success", SmsTpStatus.Evidence.NOT_CONFIRMED, d.evidence)
            assertTrue("and must be flagged as carrier-specific", d.carrierSpecific)
        }
    }

    // ── temporary error, still retrying ──────────────────────────────────────

    @Test
    fun `the named temporary codes report that the network may still deliver`() {
        val expected = mapOf(
            0x20 to "CONGESTION",
            0x21 to "SME_BUSY",
            0x22 to "NO_RESPONSE",
            0x23 to "SERVICE_REJECTED",
            0x24 to "QOS_UNAVAILABLE",
            0x25 to "ERROR_IN_SME"
        )
        for ((code, name) in expected) {
            val d = SmsTpStatus.decode(code)
            assertEquals(SmsTpStatus.Evidence.TEMPORARY, d.evidence)
            assertEquals(name, d.symbolicName)
            assertTrue("$code: the carrier is still trying", d.carrierStillRetrying)
            assertFalse("$code must not be terminal", d.isTerminal)
        }
    }

    @Test
    fun `reserved and carrier-specific temporary codes stay temporary`() {
        // 0x26..0x2F reserved, 0x30..0x3F SC-specific: still the "may yet deliver" family.
        for (code in listOf(0x26, 0x2F, 0x30, 0x3F)) {
            val d = SmsTpStatus.decode(code)
            assertEquals("$code is still a retrying temporary error", SmsTpStatus.Evidence.TEMPORARY, d.evidence)
            assertTrue(d.carrierStillRetrying)
        }
    }

    // ── permanent failure ────────────────────────────────────────────────────

    @Test
    fun `the named permanent codes are terminal failures`() {
        val expected = mapOf(
            0x40 to "REMOTE_PROCEDURE_ERROR",
            0x41 to "INCOMPATIBLE_DESTINATION",
            0x42 to "CONNECTION_REJECTED",
            0x43 to "NOT_OBTAINABLE",
            0x44 to "QOS_UNAVAILABLE_PERMANENT",
            0x45 to "NO_INTERWORKING",
            0x46 to "VALIDITY_PERIOD_EXPIRED",
            0x47 to "DELETED_BY_ORIGINATOR",
            0x48 to "DELETED_BY_ADMIN",
            0x49 to "SM_DOES_NOT_EXIST"
        )
        for ((code, name) in expected) {
            val d = SmsTpStatus.decode(code)
            assertEquals(SmsTpStatus.Evidence.FAILED, d.evidence)
            assertEquals(name, d.symbolicName)
            assertTrue("$code must be terminal", d.isTerminal)
            assertFalse("$code: no more attempts", d.carrierStillRetrying)
        }
    }

    /**
     * `0x50..0x5F` belongs to the PERMANENT family, not the stopped-retry one.
     *
     * The standard's `0x50..0x55` are the same six causes as `0x20..0x25` but inside the permanent
     * range. Earlier in this change I mis-filed them under 0x60–0x7F; this test is what caught it.
     */
    @Test
    fun `0x50 to 0x5F are permanent failures`() {
        for (code in 0x4A..0x5F) {
            val d = SmsTpStatus.decode(code)
            assertEquals("$code is in the permanent family", SmsTpStatus.Evidence.FAILED, d.evidence)
            assertEquals(SmsTpStatus.Category.PERMANENT_FAILURE, d.category)
            assertTrue("$code must be terminal", d.isTerminal)
            assertFalse("$code: no more attempts", d.carrierStillRetrying)
        }
    }

    @Test
    fun `0x43 is the standard not-obtainable code`() {
        // The brief attributed "Not obtainable" to 0x63. The standard defines it at 0x43; 0x63 is
        // carrier-specific. This pins the correct code so the mislabelling cannot creep back in.
        val d = SmsTpStatus.decode(0x43)
        assertEquals("NOT_OBTAINABLE", d.symbolicName)
        assertEquals(SmsTpStatus.Evidence.FAILED, d.evidence)
        assertEquals(SmsTpStatus.Category.PERMANENT_FAILURE, d.category)
    }

    @Test
    fun `reserved and carrier-specific permanent codes are terminal failures`() {
        for (code in listOf(0x4A, 0x4F)) {
            val d = SmsTpStatus.decode(code)
            assertEquals("$code is in the permanent family", SmsTpStatus.Evidence.FAILED, d.evidence)
            assertTrue("$code must be terminal", d.isTerminal)
        }
    }

    // ── temporary error, no longer retrying ──────────────────────────────────

    @Test
    fun `the stopped-retry family 0x60 to 0x7F is failed and terminal`() {
        // The standard leaves every code in this range to the individual SC, so none can be named. They
        // are still terminal failures: the family is "temporary error, SC is not making any more
        // transfer attempts".
        for (code in 0x60..0x7F) {
            val d = SmsTpStatus.decode(code)
            assertEquals(SmsTpStatus.Evidence.FAILED, d.evidence)
            assertEquals(SmsTpStatus.Category.TEMPORARY_STOPPED, d.category)
            assertTrue("$code is terminal", d.isTerminal)
            assertFalse("$code: the SC stopped trying", d.carrierStillRetrying)
            assertTrue("$code is carrier-specific", d.carrierSpecific)
        }
    }

    @Test
    fun `0x56 to 0x5F are still the permanent family, not the stopped one`() {
        // The boundary that this change initially got wrong, asserted explicitly so it cannot drift.
        for (code in 0x56..0x5F) {
            assertEquals(
                "0x%02X sits in 0x40..0x5F".format(code),
                SmsTpStatus.Category.PERMANENT_FAILURE,
                SmsTpStatus.decode(code).category
            )
        }
        assertEquals(
            "0x60 starts the stopped-retry family",
            SmsTpStatus.Category.TEMPORARY_STOPPED,
            SmsTpStatus.decode(0x60).category
        )
    }

    // ── the REAL INCIDENT ────────────────────────────────────────────────────

    @Test
    fun `the incident code 0x63 is a terminal carrier-specific failure`() {
        // Exactly what the phone reported: 99 / 0x63 after an ambiguous SENT callback.
        val d = SmsTpStatus.decode(0x63)
        assertEquals(SmsTpStatus.Evidence.FAILED, d.evidence)
        assertEquals(SmsTpStatus.Category.TEMPORARY_STOPPED, d.category)
        assertTrue("no further attempts will be made", d.isTerminal)
        assertFalse("the SC is not still retrying", d.carrierStillRetrying)
        assertTrue("only the carrier knows what 0x63 means", d.carrierSpecific)
        assertEquals("0x63", d.hexCode)
    }

    @Test
    fun `0x63 is NOT labelled Not obtainable`() {
        // The standard leaves 0x60..0x7F to each SC. Calling it "Not obtainable" (which is 0x43) would
        // attribute a standard meaning to a code the standard does not define, and would be a confident
        // wrong answer to the user's "why".
        val d = SmsTpStatus.decode(0x63)
        assertNotEquals("NOT_OBTAINABLE", d.symbolicName)
        assertFalse(
            "must not claim the standard's not-obtainable wording",
            d.reason.contains("could not be reached")
        )
        assertTrue("must say the code is carrier-specific", d.carrierSpecific)
        assertTrue("must still explain it will not retry", d.reason.contains("not try again"))
    }

    @Test
    fun `no decoded reason invents a cause the evidence cannot support`() {
        // Insufficient credit, a blocked sender, a switched-off phone, SIM registration and throttling
        // all need independent evidence. None of them may appear as a decoded reason.
        val forbidden = listOf(
            "balance", "credit", "charge", "blocked", "block", "switched off",
            "throttl", "SIM registration", "misconfigur", "operator"
        )
        for (code in 0x00..0xFF) {
            val reason = SmsTpStatus.decode(code).reason.lowercase()
            for (word in forbidden) {
                assertFalse(
                    "code 0x%02X must not claim '$word': ${SmsTpStatus.decode(code).reason}".format(code),
                    reason.contains(word)
                )
            }
        }
    }

    // ── reserved / undefined family ──────────────────────────────────────────

    @Test
    fun `0x80 to 0xFF are unknown and never a delivery`() {
        for (code in 0x80..0xFF) {
            val d = SmsTpStatus.decode(code)
            assertEquals("$code is undefined by the standard", SmsTpStatus.Evidence.UNKNOWN, d.evidence)
            assertNotEquals(SmsTpStatus.Evidence.DELIVERED, d.evidence)
        }
    }

    @Test
    fun `a value that cannot be a single TP-Status octet is rejected as out of range`() {
        for (code in listOf(-1, 0x100, 0x10000, 2 shl 16, Int.MAX_VALUE)) {
            val d = SmsTpStatus.decode(code)
            assertEquals("$code is not a TP-ST octet", SmsTpStatus.Evidence.UNKNOWN, d.evidence)
            assertEquals("OUT_OF_RANGE", d.symbolicName)
            assertFalse("and certainly not a delivery", d.evidence == SmsTpStatus.Evidence.DELIVERED)
        }
    }

    // ── invariants over the whole byte range ─────────────────────────────────

    @Test
    fun `exactly one code in the entire range is a confirmed delivery`() {
        // The strongest possible invariant: if this ever becomes 2, something started guessing again.
        val delivered = (0x00..0xFF).filter { SmsTpStatus.decode(it).evidence == SmsTpStatus.Evidence.DELIVERED }
        assertEquals("only 0x00 means received", listOf(0x00), delivered)
    }

    @Test
    fun `terminal and still-retrying are never both true`() {
        for (code in 0x00..0xFF) {
            val d = SmsTpStatus.decode(code)
            assertFalse(
                "code 0x%02X claims to be both terminal and retrying".format(code),
                d.isTerminal && d.carrierStillRetrying
            )
        }
    }

    @Test
    fun `every code keeps its raw value and a standard reference`() {
        for (code in 0x00..0xFF) {
            val d = SmsTpStatus.decode(code)
            assertEquals("the raw code must never be lost", code, d.rawCode)
            assertTrue("a reference makes the claim checkable", d.standardReference.contains("23.040"))
        }
    }

    @Test
    fun `hexCode is the exact two-digit form used in diagnostics`() {
        assertEquals("0x00", SmsTpStatus.decode(0x00).hexCode)
        assertEquals("0x63", SmsTpStatus.decode(99).hexCode)
        assertEquals("0xFF", SmsTpStatus.decode(0xFF).hexCode)
    }
}
