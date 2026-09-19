package com.autonomousone.messages

import com.autonomousone.messages.data.ExistingOtpCleanupCandidate
import com.autonomousone.messages.data.MessageClassificationEntity
import com.autonomousone.messages.data.MessageKey
import com.autonomousone.messages.data.MessageUserStateEntity
import com.autonomousone.messages.messaging.CustomRetentionRange
import com.autonomousone.messages.messaging.OtpDetector
import com.autonomousone.messages.messaging.OtpRetentionPolicy
import com.autonomousone.messages.repository.OtpExistingSweepSource
import com.autonomousone.messages.repository.OtpReschedule
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
 * The engine is pure Kotlin over narrow ports, so none of this needs Android.
 */
class OtpRetentionServiceTest {

    private val hour = CustomRetentionRange.HOUR_MS
    private val day = CustomRetentionRange.DAY_MS
    private val now = 1_800_000_000_000L

    private val store = FakeRetentionStore()
    private val settings = FakeRetentionSettings()
    private val reschedules = mutableListOf<Long>()

    /** Message bodies for the opt-in sweep, keyed by providerId. */
    private val bodies = mutableMapOf<Long, String>()

    /** Mirrors the `messages.date` anchor the sweep orders by. */
    private val dates = mutableMapOf<Long, Long>()

    private val strongOtpBody = "Your verification code is 482193"
    private val weakOtpBody = "your order code 4821"
    private val ordinaryBody = "lunch tomorrow at 1"

    private fun service() = OtpRetentionService(
        store = store,
        preferences = settings,
        sweep = OtpExistingSweepSource { afterDate, afterProviderId, limit ->
            candidates()
                .filter {
                    it.date < afterDate || (it.date == afterDate && it.providerId < afterProviderId)
                }
                .sortedWith(
                    compareByDescending<ExistingOtpCleanupCandidate> { it.date }
                        .thenByDescending { it.providerId }
                )
                .take(limit)
        },
        reschedule = OtpReschedule { reschedules += now },
        clock = { now }
    )

    /**
     * Rebuilds the sweep page from current state — the production query is a JOIN
     * over `messages` + `message_user_state`, so a test that changed either must
     * re-read it rather than reuse a stale snapshot.
     */
    private fun candidates(): List<ExistingOtpCleanupCandidate> =
        store.classifications.values.mapNotNull { row ->
            val type = store.directions[row.source to row.providerId] ?: return@mapNotNull null
            val state = store.userStates[row.source to row.providerId]
            if (state?.starred == true || state?.keepFromOtpCleanup == true || state?.isTrashed == true) {
                return@mapNotNull null
            }
            ExistingOtpCleanupCandidate(
                source = row.source,
                providerId = row.providerId,
                threadId = row.threadId,
                body = bodies[row.providerId] ?: ordinaryBody,
                date = dates[row.providerId] ?: now,
                rawAddress = "1200",
                messageType = type,
                confidence = row.confidence,
                eligibleAt = row.otpDeleteEligibleAt
            )
        }

    private fun message(
        providerId: Long,
        type: Int = OtpRetentionPolicy.TYPE_INCOMING,
        date: Long = now,
        body: String = strongOtpBody
    ) {
        store.directions["sms" to providerId] = type
        bodies[providerId] = body
        dates[providerId] = date
    }

    private fun classified(
        providerId: Long,
        eligibleAt: Long,
        confidence: Float = 0.9f,
        isOtp: Boolean = true,
        category: String = "OTP"
    ) {
        store.putClassification(
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
        store.putUserState(
            MessageUserStateEntity(
                source = "sms",
                providerId = providerId,
                threadId = THREAD,
                starred = starred,
                keepFromOtpCleanup = keep,
                trashedAt = if (trashed) now - hour else 0L,
                purgeAt = if (trashed) now + 30 * day else 0L
            )
        )
    }

    private fun deadlineOf(providerId: Long): Long? =
        store.classifications["sms" to providerId]?.otpDeleteEligibleAt

    // ── Default OFF: nothing is ever cleaned ────────────────────────────────

    @Test
    fun `disabled means a due OTP is not touched`() = runBlocking {
        settings.enabled = false
        message(OTP_ID)
        classified(OTP_ID, eligibleAt = now - day)

        val outcome = service().runDueCleanup(limit = 50)

        assertTrue(store.trashed.isEmpty())
        assertEquals(0, outcome.trashed)
        assertEquals(0, outcome.scanned)
        // The durable deadline survives: switching off must not rewrite history.
        assertEquals(now - day, deadlineOf(OTP_ID))
    }

    @Test
    fun `disabled means the triage pass changes nothing`() = runBlocking {
        settings.enabled = false
        message(OTP_ID)
        classified(OTP_ID, eligibleAt = now - day)
        userStateFor(OTP_ID, starred = true)

        val changed = service().reconcileEnrolments()

        assertEquals(0, changed)
        assertEquals(now - day, deadlineOf(OTP_ID))
    }

    // ── The due run moves to Trash ──────────────────────────────────────────

    @Test
    fun `a due eligible OTP is moved to trash and not removed`() = runBlocking {
        settings.enabled = true
        message(OTP_ID)
        classified(OTP_ID, eligibleAt = now - hour)

        val outcome = service().runDueCleanup(limit = 50)

        assertEquals(listOf("sms" to OTP_ID), store.trashed)
        assertEquals(1, outcome.trashed)
        assertEquals(1, outcome.scanned)
        // "Moved to Trash", never "deleted": the classification row still exists,
        // the message state is a TRASH state (not a removal), and the deadline is
        // cleared so nothing can run for it again.
        assertNotNull(store.classifications["sms" to OTP_ID])
        assertEquals(0L, deadlineOf(OTP_ID))
        val state = store.userStates["sms" to OTP_ID]
        assertNotNull(state)
        assertEquals(now, state!!.trashedAt)
        assertEquals(now + 30 * day, state.purgeAt)
    }

    @Test
    fun `only the due rows are considered`() = runBlocking {
        settings.enabled = true
        message(OTP_ID)
        message(FUTURE_ID)
        classified(OTP_ID, eligibleAt = now - hour)
        classified(FUTURE_ID, eligibleAt = now + day)

        val outcome = service().runDueCleanup(limit = 50)

        assertEquals(listOf("sms" to OTP_ID), store.trashed)
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
        assertEquals(3, store.enrolledCount())
        assertNotNull(service().nextEligibleAt())
    }

    // ── Protection rules, enforced against a STALE deadline ─────────────────

    @Test
    fun `a starred message with a stale deadline is exempt and gets unscheduled`() = runBlocking {
        settings.enabled = true
        message(OTP_ID)
        classified(OTP_ID, eligibleAt = now - hour)
        userStateFor(OTP_ID, starred = true)

        val outcome = service().runDueCleanup(limit = 50)

        assertTrue(store.trashed.isEmpty())
        assertEquals(1, outcome.deferred)
        assertEquals(0L, deadlineOf(OTP_ID))
    }

    @Test
    fun `a keep-flagged message with a stale deadline is exempt`() = runBlocking {
        settings.enabled = true
        message(OTP_ID)
        classified(OTP_ID, eligibleAt = now - hour)
        userStateFor(OTP_ID, keep = true)

        val outcome = service().runDueCleanup(limit = 50)

        assertTrue(store.trashed.isEmpty())
        assertEquals(1, outcome.deferred)
        assertEquals(0L, deadlineOf(OTP_ID))
    }

    @Test
    fun `an already-trashed message with a deadline is skipped`() = runBlocking {
        settings.enabled = true
        message(OTP_ID)
        classified(OTP_ID, eligibleAt = now - hour)
        userStateFor(OTP_ID, trashed = true)

        val outcome = service().runDueCleanup(limit = 50)

        assertTrue(store.trashed.isEmpty())
        assertEquals(1, outcome.deferred)
    }

    @Test
    fun `an outgoing message with a deadline is skipped`() = runBlocking {
        settings.enabled = true
        message(OTP_ID, type = 2)
        classified(OTP_ID, eligibleAt = now - hour)

        val outcome = service().runDueCleanup(limit = 50)

        assertTrue(store.trashed.isEmpty())
        assertEquals(1, outcome.deferred)
        assertEquals(0L, deadlineOf(OTP_ID))
    }

    @Test
    fun `a low-confidence OTP with a deadline is skipped`() = runBlocking {
        settings.enabled = true
        message(OTP_ID)
        classified(OTP_ID, eligibleAt = now - hour, confidence = 0.5f)

        val outcome = service().runDueCleanup(limit = 50)

        assertTrue(store.trashed.isEmpty())
        assertEquals(1, outcome.deferred)
    }

    @Test
    fun `a missing mirror row fails safe and is not trashed`() = runBlocking {
        settings.enabled = true
        // No mirror entry ⇒ direction unknown.
        classified(OTP_ID, eligibleAt = now - hour)

        val outcome = service().runDueCleanup(limit = 50)

        assertTrue(store.trashed.isEmpty())
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
        assertEquals(0L, deadlineOf(OTP_ID))
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
        assertEquals(now + hour, deadlineOf(OTP_ID))
        assertEquals(now + 24 * hour, deadlineOf(OLD_ID))
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
        assertEquals(0L, deadlineOf(OTP_ID))
        assertTrue(store.userStates["sms" to OTP_ID]!!.keepFromOtpCleanup)
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
        assertEquals(now + 24 * hour, deadlineOf(OTP_ID))
        assertTrue(reschedules.isNotEmpty())
    }

    @Test
    fun `starring an OTP unschedules it`() = runBlocking {
        settings.enabled = true
        message(OTP_ID)
        classified(OTP_ID, eligibleAt = now + hour)

        service().onStarChanged(MessageKey("sms", OTP_ID), THREAD, starred = true)

        assertEquals(0L, deadlineOf(OTP_ID))
        assertTrue(store.userStates["sms" to OTP_ID]!!.starred)
    }

    @Test
    fun `unstarring an OTP re-enrolls it from now`() = runBlocking {
        settings.enabled = true
        message(OTP_ID)
        classified(OTP_ID, eligibleAt = 0L)
        userStateFor(OTP_ID, starred = true)

        val plan = service().onStarChanged(MessageKey("sms", OTP_ID), THREAD, starred = false)

        assertTrue(plan.eligible)
        assertEquals(now + 24 * hour, deadlineOf(OTP_ID))
    }

    @Test
    fun `nothing is enrolled by the user actions while the feature is off`() = runBlocking {
        settings.enabled = false
        message(OTP_ID)
        classified(OTP_ID, eligibleAt = 0L)

        service().onStarChanged(MessageKey("sms", OTP_ID), THREAD, starred = false)

        assertEquals(0L, deadlineOf(OTP_ID))
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
        assertTrue(store.trashed.isEmpty())
    }

    @Test
    fun `the explicit action enrolls eligible history`() = runBlocking {
        settings.enabled = true
        message(OTP_ID)
        classified(OTP_ID, eligibleAt = 0L)

        val outcome = service().applyToExistingOtpMessages(maxBatches = 2)

        assertTrue(outcome.enabled)
        assertEquals(1, outcome.enrolled)
        // Anchored to NOW: the user just asked for this, so it must not expire at once.
        assertEquals(now + 24 * hour, deadlineOf(OTP_ID))
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
            assertTrue(deadlineOf(OTP_ID)!! > 0L)
            assertEquals(0L, deadlineOf(STARRED_ID))
            assertEquals(0L, deadlineOf(KEPT_ID))
            assertEquals(0L, deadlineOf(TRASHED_ID))
            assertEquals(0L, deadlineOf(OUTGOING_ID))
            // Weak "code" wording never reaches the HIGH-confidence bar.
            assertEquals(0L, deadlineOf(WEAK_OTP_ID))
        }

    @Test
    fun `the explicit action does nothing while the feature is off`() = runBlocking {
        settings.enabled = false
        message(OTP_ID)
        classified(OTP_ID, eligibleAt = 0L)

        val outcome = service().applyToExistingOtpMessages()

        assertFalse(outcome.enabled)
        assertEquals(0, outcome.enrolled)
        assertEquals(0L, deadlineOf(OTP_ID))
    }

    @Test
    fun `an ordinary message in history is never enrolled by the sweep`() = runBlocking {
        settings.enabled = true
        message(ORDINARY_ID, body = ordinaryBody)
        classified(
            ORDINARY_ID,
            eligibleAt = 0L,
            confidence = 0f,
            isOtp = false,
            category = "PERSONAL"
        )

        val outcome = service().applyToExistingOtpMessages(maxBatches = 2)

        assertEquals(0, outcome.enrolled)
        assertTrue(outcome.skipped >= 1)
        assertEquals(0L, deadlineOf(ORDINARY_ID))
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
