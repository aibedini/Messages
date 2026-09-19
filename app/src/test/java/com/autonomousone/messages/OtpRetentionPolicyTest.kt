package com.autonomousone.messages

import com.autonomousone.messages.data.MessageClassificationEntity
import com.autonomousone.messages.data.MessageUserStateEntity
import com.autonomousone.messages.messaging.CustomRetentionRange
import com.autonomousone.messages.messaging.OtpCleanupScheduler
import com.autonomousone.messages.messaging.OtpDetector
import com.autonomousone.messages.messaging.OtpRetentionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FEATURE 14 — the pure OTP-retention policy.
 *
 * These tests pin the contract that decides WHAT is ever moved to Trash and
 * WHEN. They are deliberately exhaustive about the negative cases: the failure
 * mode that matters is not "an old OTP survived an extra day", it is "the app
 * removed a message the user had starred, kept, or never received".
 */
class OtpRetentionPolicyTest {

    private val hour = CustomRetentionRange.HOUR_MS
    private val day = CustomRetentionRange.DAY_MS
    private val now = 1_800_000_000_000L

    /** A high-confidence incoming OTP, protected by nothing. */
    private fun state(
        isOtp: Boolean = true,
        confidence: Float = OtpDetector.HIGH_CONFIDENCE,
        isIncoming: Boolean = true,
        starred: Boolean = false,
        keep: Boolean = false,
        trashed: Boolean = false
    ) = OtpRetentionPolicy.MessageState(isOtp, confidence, isIncoming, starred, keep, trashed)

    private fun plan(
        state: OtpRetentionPolicy.MessageState = state(),
        enabled: Boolean = true,
        retentionMillis: Long = 24 * hour,
        anchorMillis: Long = now,
        nowMillis: Long = now
    ) = OtpRetentionPolicy.plan(state, enabled, retentionMillis, anchorMillis, nowMillis)

    // ── The OFF default ─────────────────────────────────────────────────────

    @Test
    fun `disabled retention never enrolls anything`() {
        val decision = plan(enabled = false)

        assertFalse(decision.eligible)
        assertNull(decision.eligibleAt)
        assertEquals(OtpRetentionPolicy.EligibilityReason.RETENTION_DISABLED, decision.reason)
        assertEquals(0L, OtpRetentionPolicy.deadlineFor(decision))
    }

    @Test
    fun `a non-positive retention is treated as disabled`() {
        assertEquals(
            OtpRetentionPolicy.EligibilityReason.RETENTION_DISABLED,
            plan(retentionMillis = 0L).reason
        )
    }

    // ── Preset deadlines ────────────────────────────────────────────────────

    @Test
    fun `each preset computes the exact deadline from the anchor`() {
        val anchor = now - 5 * hour
        val cases = mapOf(
            1 * hour to 1 * hour,
            6 * hour to 6 * hour,
            24 * hour to 24 * hour,
            3 * day to 3 * day,
            7 * day to 7 * day
        )
        for ((retention, expectedOffset) in cases) {
            val decision = plan(retentionMillis = retention, anchorMillis = anchor)
            assertTrue("retention=$retention must be eligible", decision.eligible)
            assertEquals(anchor + expectedOffset, decision.eligibleAt)
            assertEquals(OtpRetentionPolicy.EligibilityReason.ELIGIBLE, decision.reason)
        }
    }

    @Test
    fun `an anchor of zero falls back to now so a deadline is never epoch zero`() {
        val decision = plan(anchorMillis = 0L)

        assertEquals(now + 24 * hour, decision.eligibleAt)
    }

    // ── Eligibility rules ───────────────────────────────────────────────────

    @Test
    fun `starred messages are exempt`() {
        val decision = plan(state(starred = true))

        assertFalse(decision.eligible)
        assertEquals(OtpRetentionPolicy.EligibilityReason.STARRED, decision.reason)
    }

    @Test
    fun `keep-flagged messages are exempt`() {
        val decision = plan(state(keep = true))

        assertFalse(decision.eligible)
        assertEquals(OtpRetentionPolicy.EligibilityReason.KEPT_BY_USER, decision.reason)
    }

    @Test
    fun `already-trashed messages are skipped`() {
        val decision = plan(state(trashed = true))

        assertFalse(decision.eligible)
        assertEquals(OtpRetentionPolicy.EligibilityReason.ALREADY_TRASHED, decision.reason)
    }

    @Test
    fun `outgoing messages are skipped`() {
        val decision = plan(state(isIncoming = false))

        assertFalse(decision.eligible)
        assertEquals(OtpRetentionPolicy.EligibilityReason.NOT_INCOMING, decision.reason)
    }

    @Test
    fun `a low-confidence OTP is skipped`() {
        val decision = plan(state(confidence = OtpDetector.HIGH_CONFIDENCE - 0.01f))

        assertFalse(decision.eligible)
        assertEquals(OtpRetentionPolicy.EligibilityReason.LOW_CONFIDENCE, decision.reason)
    }

    @Test
    fun `the high-confidence threshold is exactly the detector floor`() {
        assertEquals(OtpDetector.HIGH_CONFIDENCE, OtpRetentionPolicy.HIGH_CONFIDENCE)
        assertTrue(plan(state(confidence = OtpDetector.HIGH_CONFIDENCE)).eligible)
        // The detector's weak-keyword band must never auto-clean.
        assertFalse(plan(state(confidence = 0.55f)).eligible)
    }

    @Test
    fun `a non-OTP is skipped`() {
        val decision = plan(state(isOtp = false, confidence = 1f))

        assertFalse(decision.eligible)
        assertEquals(OtpRetentionPolicy.EligibilityReason.NOT_OTP, decision.reason)
    }

    // ── Ordering of the reasons (diagnostics) ───────────────────────────────

    @Test
    fun `disabling wins over every per-message exemption`() {
        val decision = plan(state(starred = true, trashed = true), enabled = false)

        assertEquals(OtpRetentionPolicy.EligibilityReason.RETENTION_DISABLED, decision.reason)
    }

    @Test
    fun `a starred OTP reports STARRED not a generic refusal`() {
        assertEquals(
            OtpRetentionPolicy.EligibilityReason.STARRED,
            plan(state(isIncoming = false, starred = true)).reason
        )
    }

    // ── Wrong-clock protection ──────────────────────────────────────────────

    @Test
    fun `a badly overdue deadline is refused as clock skew`() {
        val decision = plan(
            retentionMillis = 1 * hour,
            anchorMillis = now - 90 * day,
            nowMillis = now
        )

        assertFalse(decision.eligible)
        assertEquals(OtpRetentionPolicy.EligibilityReason.CLOCK_SKEW, decision.reason)
    }

    @Test
    fun `an overdue deadline inside the tolerance window is still eligible`() {
        // The deadline is 11 hours old — past due, but INSIDE
        // OtpRetentionPolicy.MAX_OVERDUE_MILLIS (24h). The test used to anchor two
        // DAYS back with a one-hour retention, which is 47 hours overdue and therefore
        // outside the window by construction, so it asserted the opposite of its own
        // name. The pair with the test above now brackets the tolerance boundary.
        val decision = plan(
            retentionMillis = 1 * hour,
            anchorMillis = now - 12 * hour,
            nowMillis = now
        )

        assertTrue(decision.eligible)
        assertNotNull(decision.eligibleAt)
    }

    // ── deadlineFor / needsReschedule ───────────────────────────────────────

    @Test
    fun `an ineligible plan persists an explicit zero so it is unenrolled`() {
        val decision = plan(state(keep = true))

        assertEquals(0L, OtpRetentionPolicy.deadlineFor(decision))
        assertTrue(OtpRetentionPolicy.needsReschedule(previousDeadline = now, plan = decision))
    }

    @Test
    fun `an unchanged deadline needs no rewrite`() {
        val decision = plan(anchorMillis = now, retentionMillis = 24 * hour)

        assertFalse(OtpRetentionPolicy.needsReschedule(now + 24 * hour, decision))
        assertTrue(OtpRetentionPolicy.needsReschedule(now + 1 * hour, decision))
    }

    // ── MessageState.of (persisted rows + missing rows) ─────────────────────

    @Test
    fun `state defaults are unstarred, unkept and untrashed when no user row exists`() {
        val classification = MessageClassificationEntity(
            source = "sms",
            providerId = 100,
            threadId = 7,
            category = "OTP",
            confidence = 0.9f,
            isOtp = true,
            otpDeleteEligibleAt = now,
            classifiedAt = now
        )

        val built = OtpRetentionPolicy.MessageState.of(
            classification = classification,
            userState = null,
            messageType = OtpRetentionPolicy.TYPE_INCOMING
        )

        assertTrue(built.isOtp)
        assertTrue(built.isIncoming)
        assertFalse(built.starred)
        assertFalse(built.keepFromOtpCleanup)
        assertFalse(built.trashed)
    }

    @Test
    fun `a null classification is never an OTP`() {
        val built = OtpRetentionPolicy.MessageState.of(null, null, OtpRetentionPolicy.TYPE_INCOMING)

        assertFalse(built.isOtp)
        assertFalse(plan(built).eligible)
    }

    @Test
    fun `a missing direction fails safe as not incoming`() {
        val built = OtpRetentionPolicy.MessageState.of(null, null, messageType = null)

        assertFalse(built.isIncoming)
    }

    @Test
    fun `a trashedAt above zero marks the row trashed`() {
        val userState = MessageUserStateEntity(
            source = "sms",
            providerId = 100,
            threadId = 7,
            trashedAt = now,
            purgeAt = now + 30 * day
        )

        assertTrue(userState.isTrashed)
        val decision = plan(
            OtpRetentionPolicy.MessageState(
                isOtp = true,
                confidence = 0.9f,
                isIncoming = true,
                starred = false,
                keepFromOtpCleanup = false,
                trashed = userState.isTrashed
            )
        )
        assertFalse(decision.eligible)
        assertEquals(OtpRetentionPolicy.EligibilityReason.ALREADY_TRASHED, decision.reason)
    }

    // ── Custom range validation ─────────────────────────────────────────────

    @Test
    fun `a custom value in hours is accepted inside the window`() {
        val result = CustomRetentionRange.validate("12", CustomRetentionRange.Unit.HOURS)

        assertTrue(result.isValid)
        assertEquals(12 * hour, result.millis)
    }

    @Test
    fun `a custom value in days is converted to hours`() {
        val result = CustomRetentionRange.validate("3", CustomRetentionRange.Unit.DAYS)

        assertTrue(result.isValid)
        assertEquals(3 * day, result.millis)
    }

    @Test
    fun `both window bounds are inclusive`() {
        assertEquals(1 * hour, CustomRetentionRange.validate("1", CustomRetentionRange.Unit.HOURS).millis)
        assertEquals(
            30 * day,
            CustomRetentionRange.validate("30", CustomRetentionRange.Unit.DAYS).millis
        )
    }

    @Test
    fun `a too-small custom value is rejected with visible feedback data`() {
        // 0 is below the floor whether typed in hours or days.
        assertEquals(
            CustomRetentionRange.Rejection.TOO_SMALL,
            CustomRetentionRange.validate("0", CustomRetentionRange.Unit.HOURS).rejection
        )
        assertEquals(
            CustomRetentionRange.Rejection.TOO_SMALL,
            CustomRetentionRange.validate("0", CustomRetentionRange.Unit.DAYS).rejection
        )
    }

    @Test
    fun `a too-large custom value is rejected`() {
        assertEquals(
            CustomRetentionRange.Rejection.TOO_LARGE,
            CustomRetentionRange.validate("31", CustomRetentionRange.Unit.DAYS).rejection
        )
        assertEquals(
            CustomRetentionRange.Rejection.TOO_LARGE,
            CustomRetentionRange.validate("721", CustomRetentionRange.Unit.HOURS).rejection
        )
    }

    @Test
    fun `the rejection carries the clamped value the UI can offer`() {
        assertEquals(
            CustomRetentionRange.MIN_HOURS,
            CustomRetentionRange.Rejection.TOO_SMALL.clampedHours
        )
        assertEquals(
            CustomRetentionRange.MAX_HOURS,
            CustomRetentionRange.Rejection.TOO_LARGE.clampedHours
        )
    }

    @Test
    fun `a non-numeric custom value is rejected rather than coerced`() {
        for (input in listOf("", "  ", "abc", "1.5", "-2", "12h")) {
            val result = CustomRetentionRange.validate(input, CustomRetentionRange.Unit.HOURS)
            assertFalse("input=$input must be rejected", result.isValid)
            assertEquals(
                "input=$input",
                CustomRetentionRange.Rejection.NOT_A_NUMBER,
                result.rejection
            )
            assertNull(result.millis)
        }
    }

    @Test
    fun `an enormous day count is rejected instead of overflowing`() {
        val result = CustomRetentionRange.validate("999999", CustomRetentionRange.Unit.DAYS)

        assertEquals(CustomRetentionRange.Rejection.TOO_LARGE, result.rejection)
        assertNull(result.millis)
    }

    // ── Scheduling constants ────────────────────────────────────────────────

    @Test
    fun `there is exactly one unique cleanup work name`() {
        assertEquals("otp_cleanup", OtpCleanupScheduler.WORK_NAME)
    }

    @Test
    fun `the preset list is the ordered 1h 6h 24h 3d 7d set`() {
        assertEquals(
            listOf(1 * hour, 6 * hour, 24 * hour, 3 * day, 7 * day),
            CustomRetentionRange.PRESETS
        )
    }
}
