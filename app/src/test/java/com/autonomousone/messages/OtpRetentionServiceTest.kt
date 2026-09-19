package com.autonomousone.messages

import com.autonomousone.messages.data.ExistingOtpCleanupCandidate
import com.autonomousone.messages.data.MessageClassificationEntity
import com.autonomousone.messages.data.MessageKey
import com.autonomousone.messages.data.MessageUserStateEntity
import com.autonomousone.messages.messaging.CustomRetentionRange
import com.autonomousone.messages.messaging.MessageDirectionLookup
import com.autonomousone.messages.messaging.OtpDetector
import com.autonomousone.messages.messaging.OtpRetentionPolicy
import com.autonomousone.messages.messaging.OtpRetentionSettings
import com.autonomousone.messages.repository.OtpRetentionService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FEATURE 14 — the OTP-retention ENGINE (bounded due run, triage, user actions,
 * the explicit opt-in sweep).
 *
 * These are the invariants a user would notice if they broke:
 *  - with the switch OFF nothing is ever moved;
 *  - the due run MOVES TO TRASH and never removes a message;
 *  - a starred / kept / trashed / outgoing / low-confidence / unknown-direction
 *    message is never moved, even when it already carries a durable deadline;
 *  - history is only ever enrolled by the explicit action;
 *  - every deadline change tells the one scheduler.
 *
 * The engine is pure Kotlin over injected ports, so none of this needs Android
 * or a device.
 */
class OtpRetentionServiceTest {

    private val hour = CustomRetentionRange.HOUR_MS
    private val day = CustomRetentionRange.DAY_MS
    private val now = 1_800_000_000_000L

    private val classification = FakeClassificationDao()
    private val userState = FakeUserStateDao()
    private val trash = TrashRecorder()
    private val reschedules = mutableListOf<Long>()

    /** Messages present in the `messages` mirror, keyed by (source, providerId). */
    private val mirror = mutableMapOf<Pair<String, Long>, MirrorRow>()

    /** Rows the opt-in sweep may see, exactly as the bounded DAO would return them. */
    private val sweepRows = mutableListOf<ExistingOtpCleanupCandidate>()

    private class TestSettings(
        override var enabled: Boolean = false,
        override var retentionMillis: Long = 24L * 60L * 60L * 1000L
    ) : OtpRetentionSettings

    private val settings = TestSettings()

    /** A message mirror row: provider direction plus the body the sweep re-detects. */
    private data class MirrorRow(val type: Int, val date: Long, val body: String)

    private val strongOtpBody = "Your verification code is 482193"
    private val weakOtpBody = "your order code 4821"
    private val ordinaryBody = "lunch tomorrow at 1"

    private fun service() = OtpRetentionService(
        classification = classification,
        userState = userState,
        preferences = settings,
        sweepCandidates = { afterDate, afterProviderId, limit ->
            sweepRows
                .filter { it.date < afterDate || (it.date == afterDate && it.providerId < afterProviderId) }
                .sortedWith(
                    compareByDescending<ExistingOtpCleanupCandidate> { it.date }
                        .thenByDescending { it.providerId }
                )
                .take(limit)
        },
        trashAll = { rows, _ -> rows.forEach { trash.trashed += it.source to it.providerId } },
        directions = MessageDirectionLookup { source, providerId ->
            mirror[source to providerId]?.type
        },
        reschedule = { reschedules += now },
        clock = { now }
    )

    private fun message(
        providerId: Long,
        type: Int = OtpRetentionPolicy.TYPE_INCOMING,
        date: Long = now,
        body: String = strongOtpBody
    ) {
        mirror["sms" to providerId] = MirrorRow(type, date, body)
        sweepRows += ExistingOtpCleanupCandidate(
            source = "sms",
            providerId = providerId,
            threadId = THREAD,
            body = body,
            date = date,
            rawAddress = "1200",
            messageType = type,
            confidence = classification.get("sms", providerId)?.confidence,
            eligibleAt = classification.get("sms", providerId)?.otpDeleteEligibleAt
        )
    }

    private fun classified(
        providerId: Long,
        eligibleAt: Long,
        confidence: Float = 0.9f,
        isOtp: Boolean = true,
        category: String = "OTP"
    ) {
        classification.put(
            MessageClassificationEntity(
                source = "sms",
                providerId = providerId,
                threadId = THREAD,
                category = category,
                confidence = confidence,
                isOtp = isOtp,
                otpDeleteEligibleAt = eligibleAt,
                classifiedAt = now - day
            )
        )
    }

    private fun userStateFor(
        providerId: Long,
        starred: Boolean = false,
        keep: Boolean = false,
        trashed: Boolean = false
    ) {
        userState.rows["sms" to providerId] = MessageUserStateEntity(
            source = "sms",
            providerId = providerId,
            threadId = THREAD,
            starred = starred,
            keepFromOtpCleanup = keep,
            trashedAt = if (trashed) now - hour else 0L,
            purgeAt = if (trashed) now + 30 * day else 0L
        )
    }

    /** The sweep candidate list is a snapshot; refresh it after classification. */
    private fun refreshSweepEligibility() {
        for (index in sweepRows.indices) {
            val row = sweepRows[index]
            val stored = classification.get(row.source, row.providerId)
            sweepRows[index] = row.copy(eligibleAt = stored?.otpDeleteEligibleAt)
        }
    }

    // ── Default OFF: nothing is ever cleaned ────────────────────────────────

    @Test
    fun `disabled means a due OTP is not touched`() = runBlocking {
        settings.enabled = false
        message(OTP_ID)
        classified(OTP_ID, eligibleAt = now - day)

        val outcome = service().runDueCleanup(limit = 50)

        assertTrue(trash.trashed.isEmpty())
        assertEquals(0, outcome.trashed)
        assertEquals(0, outcome.scanned)
        // The durable deadline survives: switching off must not rewrite history.
        assertEquals(now - day, classification.get("sms", OTP_ID)!!.otpDeleteEligibleAt)
    }

    @Test
    fun `disabled means the triage pass changes nothing`() = runBlocking {
        settings.enabled = false
        message(OTP_ID)
        classified(OTP_ID, eligibleAt = now - day)
        userStateFor(OTP_ID, starred = true)

        val changed = service().reconcileEnrolments()

        assertEquals(0, changed)
        assertEquals(now - day, classification.get("sms", OTP_ID)!!.otpDeleteEligibleAt)
    }

    // ── The due run moves to Trash ──────────────────────────────────────────

    @Test
    fun `a due eligible OTP is moved to trash and not removed`() = runBlocking {
        settings.enabled = true
        message(OTP_ID)
        classified(OTP_ID, eligibleAt = now - hour)

        val outcome = service().runDueCleanup(limit = 50)

        assertEquals(listOf("sms:$OTP_ID"), trash.keys())
        assertEquals(1, outcome.trashed)
        assertEquals(1, outcome.scanned)
        // "Moved to Trash", never "deleted": the classification row still exists
        // and its deadline is cleared so nothing can run for it again.
        val row = classification.get("sms", OTP_ID)
        assertNotNull(row)
        assertEquals(0L, row!!.otpDeleteEligibleAt)
    }

    @Test
    fun `only the due rows are considered`() = runBlocking {
        settings.enabled = true
        message(OTP_ID)
        message(FUTURE_ID)
        classified(OTP_ID, eligibleAt = now - hour)
        classified(FUTURE_ID, eligibleAt = now + day)

        val outcome = service().runDueCleanup(limit = 50)

        assertEquals(listOf("sms:$OTP_ID"), trash.keys())
        assertEquals(1, outcome.scanned)
        assertEquals(now + day, outcome.nextEligibleAt)
    }

    @Test
    fun `the run is bounded by the limit`() = runBlocking {
        settings.enabled = true
        repeat(5) { index ->
            val id = 500L + index
            message(id)
            classified(id, eligibleAt = now - hour)
        }

        val outcome = service().runDueCleanup(limit = 2)

        assertEquals(2, outcome.scanned)
        assertEquals(2, outcome.trashed)
        assertEquals(3, classification.countEnrolledOtpCleanup())
    }

    // ── Protection rules, enforced against a STALE deadline ─────────────────

    @Test
    fun `a starred message with a stale deadline is exempt and gets unscheduled`() = runBlocking {
        settings.enabled = true
        message(OTP_ID)
        classified(OTP_ID, eligibleAt = now - hour)
        userStateFor(OTP_ID, starred = true)

        val outcome = service().runDueCleanup(limit = 50)

        assertTrue(trash.trashed.isEmpty())
        assertEquals(1, outcome.deferred)
        assertEquals(0L, classification.get("sms", OTP_ID)!!.otpDeleteEligibleAt)
    }

    @Test
    fun `a keep-flagged message with a stale deadline is exempt`() = runBlocking {
        settings.enabled = true
        message(OTP_ID)
        classified(OTP_ID, eligibleAt = now - hour)
        userStateFor(OTP_ID, keep = true)

        val outcome = service().runDueCleanup(limit = 50)

        assertTrue(trash.trashed.isEmpty())
        assertEquals(1, outcome.deferred)
        assertEquals(0L, classification.get("sms", OTP_ID)!!.otpDeleteEligibleAt)
    }

    @Test
    fun `an already-trashed message with a deadline is skipped`() = runBlocking {
        settings.enabled = true
        message(OTP_ID)
        classified(OTP_ID, eligibleAt = now - hour)
        userStateFor(OTP_ID, trashed = true)

        val outcome = service().runDueCleanup(limit = 50)

        assertTrue(trash.trashed.isEmpty())
        assertEquals(1, outcome.deferred)
    }

    @Test
    fun `an outgoing message with a deadline is skipped`() = runBlocking {
        settings.enabled = true
        message(OTP_ID, type = 2)
        classified(OTP_ID, eligibleAt = now - hour)

        val outcome = service().runDueCleanup(limit = 50)

        assertTrue(trash.trashed.isEmpty())
        assertEquals(1, outcome.deferred)
        assertEquals(0L, classification.get("sms", OTP_ID)!!.otpDeleteEligibleAt)
    }

    @Test
    fun `a low-confidence OTP with a deadline is skipped`() = runBlocking {
        settings.enabled = true
        message(OTP_ID)
        classified(OTP_ID, eligibleAt = now - hour, confidence = 0.5f)

        val outcome = service().runDueCleanup(limit = 50)

        assertTrue(trash.trashed.isEmpty())
        assertEquals(1, outcome.deferred)
    }

    @Test
    fun `a missing mirror row fails safe and is not trashed`() = runBlocking {
        settings.enabled = true
        // No mirror entry ⇒ direction unknown.
        classified(OTP_ID, eligibleAt = now - hour)

        val outcome = service().runDueCleanup(limit = 50)

        assertTrue(trash.trashed.isEmpty())
        assertEquals(1, outcome.deferred)
    }

    // ── Triage ──────────────────────────────────────────────────────────────

    @Test
    fun `triage unschedules a message that became protected`() = runBlocking {
        settings.enabled = true
        message(OTP_ID)
        classified(OTP_ID, eligibleAt = now + hour)
        userStateFor(OTP_ID, starred = true)

        val changed = service().reconcileEnrolments()

        assertEquals(1, changed)
        assertEquals(0L, classification.get("sms", OTP_ID)!!.otpDeleteEligibleAt)
        assertNull(service().nextEligibleAt())
    }

    @Test
    fun `triage keeps an eligible enrollment and re-anchors a wrong-clock one`() = runBlocking {
        settings.enabled = true
        message(OTP_ID)
        message(OLD_ID, date = now - 400 * day)
        classified(OTP_ID, eligibleAt = now + hour)
        // Enrolled 400 days ago with a 24h retention: a wrong-clock enrollment.
        classified(OLD_ID, eligibleAt = now - 399 * day)

        val changed = service().reconcileEnrolments()

        assertEquals(1, changed)
        assertEquals(now + hour, classification.get("sms", OTP_ID)!!.otpDeleteEligibleAt)
        assertEquals(now + 24 * hour, classification.get("sms", OLD_ID)!!.otpDeleteEligibleAt)
    }

    // ── Explicit user actions ───────────────────────────────────────────────

    @Test
    fun `keeping a message unschedules it and tells the scheduler`() = runBlocking {
        settings.enabled = true
        message(OTP_ID)
        classified(OTP_ID, eligibleAt = now + hour)
        reschedules.clear()

        val plan = service().setKeepFromOtpCleanup(MessageKey("sms", OTP_ID), THREAD, keep = true)

        assertFalse(plan.eligible)
        assertEquals(OtpRetentionPolicy.EligibilityReason.KEPT_BY_USER, plan.reason)
        assertEquals(0L, classification.get("sms", OTP_ID)!!.otpDeleteEligibleAt)
        assertTrue(userState.get("sms", OTP_ID)!!.keepFromOtpCleanup)
        assertTrue("the keep action must reschedule the unique work", reschedules.isNotEmpty())
    }

    @Test
    fun `releasing the keep flag re-enrolls from now`() = runBlocking {
        settings.enabled = true
        message(OTP_ID)
        classified(OTP_ID, eligibleAt = now + hour)
        service().setKeepFromOtpCleanup(MessageKey("sms", OTP_ID), THREAD, keep = true)
        reschedules.clear()

        val plan = service().setKeepFromOtpCleanup(MessageKey("sms", OTP_ID), THREAD, keep = false)

        assertTrue(plan.eligible)
        // Re-anchored to NOW, never to the original arrival date.
        assertEquals(now + 24 * hour, classification.get("sms", OTP_ID)!!.otpDeleteEligibleAt)
        assertTrue(reschedules.isNotEmpty())
    }

    @Test
    fun `starring an OTP unschedules it`() = runBlocking {
        settings.enabled = true
        message(OTP_ID)
        classified(OTP_ID, eligibleAt = now + hour)

        service().onStarChanged(MessageKey("sms", OTP_ID), THREAD, starred = true)

        assertEquals(0L, classification.get("sms", OTP_ID)!!.otpDeleteEligibleAt)
        assertTrue(userState.get("sms", OTP_ID)!!.starred)
    }

    @Test
    fun `unstarring an OTP re-enrolls it from now`() = runBlocking {
        settings.enabled = true
        message(OTP_ID)
        classified(OTP_ID, eligibleAt = 0L)
        userStateFor(OTP_ID, starred = true)

        val plan = service().onStarChanged(MessageKey("sms", OTP_ID), THREAD, starred = false)

        assertTrue(plan.eligible)
        assertEquals(now + 24 * hour, classification.get("sms", OTP_ID)!!.otpDeleteEligibleAt)
    }

    @Test
    fun `nothing is enrolled by the user actions while the feature is off`() = runBlocking {
        settings.enabled = false
        message(OTP_ID)
        classified(OTP_ID, eligibleAt = 0L)

        service().onStarChanged(MessageKey("sms", OTP_ID), THREAD, starred = false)

        assertEquals(0L, classification.get("sms", OTP_ID)!!.otpDeleteEligibleAt)
        assertNull(service().nextEligibleAt())
    }

    // ── The explicit opt-in sweep ───────────────────────────────────────────

    @Test
    fun `existing messages are not enrolled without the explicit action`() = runBlocking {
        settings.enabled = true
        message(OTP_ID)
        message(SECOND_OTP_ID)
        // Both were classified before the feature was switched on, so neither has
        // a deadline — and nothing else in the app enrolls them.
        classified(OTP_ID, eligibleAt = 0L)
        classified(SECOND_OTP_ID, eligibleAt = 0L)

        assertNull(service().nextEligibleAt())
        assertEquals(0, service().enrolledCount())
        assertTrue(trash.trashed.isEmpty())
    }

    @Test
    fun `the explicit action enrolls eligible history`() = runBlocking {
        settings.enabled = true
        message(OTP_ID)
        classified(OTP_ID, eligibleAt = 0L)
        refreshSweepEligibility()

        val outcome = service().applyToExistingOtpMessages(maxBatches = 2)

        assertTrue(outcome.enabled)
        assertEquals(1, outcome.enrolled)
        // Anchored to NOW: the user just asked for this, so it must not expire at once.
        assertEquals(now + 24 * hour, classification.get("sms", OTP_ID)!!.otpDeleteEligibleAt)
    }

    @Test
    fun `the explicit action skips protected, trashed, outgoing and low-confidence history`() =
        runBlocking {
            settings.enabled = true
            message(OTP_ID)
            message(STARRED_ID)
            message(KEPT_ID)
            message(TRASHED_ID)
            message(OUTGOING_ID, type = 2)
            message(WEAK_OTP_ID, body = weakOtpBody)
            classified(OTP_ID, eligibleAt = 0L)
            classified(STARRED_ID, eligibleAt = 0L)
            classified(KEPT_ID, eligibleAt = 0L)
            classified(TRASHED_ID, eligibleAt = 0L)
            classified(OUTGOING_ID, eligibleAt = 0L)
            classified(WEAK_OTP_ID, eligibleAt = 0L, confidence = 0.5f)
            userStateFor(STARRED_ID, starred = true)
            userStateFor(KEPT_ID, keep = true)
            userStateFor(TRASHED_ID, trashed = true)

            val outcome = service().applyToExistingOtpMessages(maxBatches = 5)

            assertEquals(1, outcome.enrolled)
            assertTrue(classification.get("sms", OTP_ID)!!.otpDeleteEligibleAt > 0L)
            assertEquals(0L, classification.get("sms", STARRED_ID)!!.otpDeleteEligibleAt)
            assertEquals(0L, classification.get("sms", KEPT_ID)!!.otpDeleteEligibleAt)
            assertEquals(0L, classification.get("sms", TRASHED_ID)!!.otpDeleteEligibleAt)
            assertEquals(0L, classification.get("sms", OUTGOING_ID)!!.otpDeleteEligibleAt)
            assertEquals(0L, classification.get("sms", WEAK_OTP_ID)!!.otpDeleteEligibleAt)
        }

    @Test
    fun `the explicit action does nothing while the feature is off`() = runBlocking {
        settings.enabled = false
        message(OTP_ID)
        classified(OTP_ID, eligibleAt = 0L)

        val outcome = service().applyToExistingOtpMessages()

        assertFalse(outcome.enabled)
        assertEquals(0, outcome.enrolled)
        assertEquals(0L, classification.get("sms", OTP_ID)!!.otpDeleteEligibleAt)
    }

    @Test
    fun `an ordinary message in history is never enrolled by the sweep`() = runBlocking {
        settings.enabled = true
        message(ORDINARY_ID, body = ordinaryBody)
        classified(ORDINARY_ID, eligibleAt = 0L, confidence = 0f, isOtp = false, category = "PERSONAL")

        val outcome = service().applyToExistingOtpMessages(maxBatches = 2)

        assertEquals(0, outcome.enrolled)
        assertTrue(outcome.skipped >= 1)
        assertEquals(0L, classification.get("sms", ORDINARY_ID)!!.otpDeleteEligibleAt)
    }

    // ── Scheduling arithmetic ───────────────────────────────────────────────

    @Test
    fun `the next deadline is the earliest enrolled one`() = runBlocking {
        settings.enabled = true
        classified(FUTURE_ID, eligibleAt = now + 3 * day)
        classified(OTP_ID, eligibleAt = now + hour)

        assertEquals(now + hour, service().nextEligibleAt())
    }

    @Test
    fun `unscheduling the only message leaves nothing to schedule`() = runBlocking {
        settings.enabled = true
        message(OTP_ID)
        classified(OTP_ID, eligibleAt = now + hour)

        service().setKeepFromOtpCleanup(MessageKey("sms", OTP_ID), THREAD, keep = true)

        assertNull(service().nextEligibleAt())
        assertEquals(0, service().enrolledCount())
    }

    @Test
    fun `every preset produces a distinct future deadline for a fresh OTP`() {
        for (preset in CustomRetentionRange.PRESETS) {
            val plan = OtpRetentionPolicy.plan(
                state = OtpRetentionPolicy.MessageState(
                    isOtp = true,
                    confidence = OtpDetector.HIGH_CONFIDENCE,
                    isIncoming = true,
                    starred = false,
                    keepFromOtpCleanup = false,
                    trashed = false
                ),
                enabled = true,
                retentionMillis = preset,
                anchorMillis = now,
                nowMillis = now
            )
            assertTrue("preset=$preset", plan.eligible)
            assertEquals(now + preset, plan.eligibleAt)
        }
    }

    private companion object {
        const val THREAD = 7L
        const val OTP_ID = 100L
        const val SECOND_OTP_ID = 101L
        const val STARRED_ID = 102L
        const val KEPT_ID = 103L
        const val TRASHED_ID = 104L
        const val OUTGOING_ID = 105L
        const val WEAK_OTP_ID = 106L
        const val FUTURE_ID = 107L
        const val OLD_ID = 108L
        const val ORDINARY_ID = 109L
    }
}
