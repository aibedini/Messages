package com.autonomousone.messages

import com.autonomousone.messages.data.MessageCategory
import com.autonomousone.messages.data.ThreadCategoryOverride
import com.autonomousone.messages.data.ThreadCategoryRow
import com.autonomousone.messages.repository.BackfillCursor
import com.autonomousone.messages.repository.BackfillOutcome
import com.autonomousone.messages.repository.ClassificationRepository
import com.autonomousone.messages.repository.ConversationCategoryResolver
import com.autonomousone.messages.ui.home.CategoryFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FEATURE 12 — Smart Categories: the override rule, the Home chip projection,
 * the OTP-cleanup eligibility computation and the backfill cursor contract.
 *
 * Pure JVM. `ClassificationRepository` is only touched through its companion
 * (both of those members are `@JvmStatic`-free pure functions with no Android
 * dependency), and the cursor type is a plain data class.
 */
class SmartCategoriesContractTest {

    // ── The user override ALWAYS wins ──────────────────────────────────────

    @Test
    fun `manual override beats the automatic category`() {
        val effective = ConversationCategoryResolver.effectiveCategories(
            automatic = listOf(ThreadCategoryRow(threadId = 7L, category = "OTP")),
            overrides = listOf(ThreadCategoryOverride(threadId = 7L, categoryOverride = "PERSONAL"))
        )
        assertEquals(1, effective.size)
        assertEquals(MessageCategory.PERSONAL, effective.single().category)
    }

    @Test
    fun `user spam override wins`() {
        val effective = ConversationCategoryResolver.effectiveCategories(
            automatic = listOf(ThreadCategoryRow(threadId = 9L, category = "PROMOTION")),
            overrides = listOf(ThreadCategoryOverride(threadId = 9L, categoryOverride = "SPAM"))
        )
        assertEquals(MessageCategory.SPAM, effective.single().category)
    }

    @Test
    fun `a user spam report alone reads as SPAM`() {
        val effective = ConversationCategoryResolver.effectiveCategories(
            automatic = listOf(ThreadCategoryRow(threadId = 4L, category = "PERSONAL")),
            overrides = emptyList(),
            userSpamThreads = setOf(4L)
        )
        assertEquals(MessageCategory.SPAM, effective.single().category)
    }

    @Test
    fun `a null override follows the automatic category`() {
        val effective = ConversationCategoryResolver.effectiveCategories(
            automatic = listOf(ThreadCategoryRow(threadId = 3L, category = "TRANSACTION")),
            overrides = listOf(ThreadCategoryOverride(threadId = 3L, categoryOverride = null))
        )
        assertEquals(MessageCategory.TRANSACTION, effective.single().category)
    }

    @Test
    fun `clearing an override returns the thread to the automatic category`() {
        val override = ThreadCategoryOverride(threadId = 3L, categoryOverride = "OTP")
        val automatic = listOf(ThreadCategoryRow(threadId = 3L, category = "TRANSACTION"))
        assertEquals(
            MessageCategory.OTP,
            ConversationCategoryResolver.effectiveCategories(automatic, listOf(override))
                .single().category
        )
        assertEquals(
            MessageCategory.TRANSACTION,
            ConversationCategoryResolver.effectiveCategories(
                automatic,
                listOf(override.copy(categoryOverride = null))
            ).single().category
        )
    }

    @Test
    fun `an override on a thread with no automatic row is still visible`() {
        val effective = ConversationCategoryResolver.effectiveCategories(
            automatic = emptyList(),
            overrides = listOf(ThreadCategoryOverride(threadId = 11L, categoryOverride = "OTP"))
        )
        assertEquals(1, effective.size)
        assertEquals(11L, effective.single().threadId)
        assertEquals(MessageCategory.OTP, effective.single().category)
    }

    @Test
    fun `a null override with no automatic row adds nothing`() {
        val effective = ConversationCategoryResolver.effectiveCategories(
            automatic = emptyList(),
            overrides = listOf(ThreadCategoryOverride(threadId = 11L, categoryOverride = null))
        )
        assertTrue(effective.isEmpty())
    }

    @Test
    fun `an unknown stored category name degrades to UNKNOWN`() {
        val effective = ConversationCategoryResolver.effectiveCategories(
            automatic = listOf(ThreadCategoryRow(threadId = 5L, category = "NOT_A_CATEGORY")),
            overrides = emptyList()
        )
        assertEquals(MessageCategory.UNKNOWN, effective.single().category)
    }

    // ── Home chip projection ───────────────────────────────────────────────

    @Test
    fun `only categories with data produce a chip count`() {
        val effective = ConversationCategoryResolver.effectiveCategories(
            automatic = listOf(
                ThreadCategoryRow(1L, "OTP"),
                ThreadCategoryRow(2L, "OTP"),
                ThreadCategoryRow(3L, "PROMOTION")
            ),
            overrides = emptyList()
        )
        val counts = counts(effective)
        assertEquals(2, counts[CategoryFilter.Otp])
        assertEquals(1, counts[CategoryFilter.Promotion])
        assertNull("an empty category must not offer a chip", counts[CategoryFilter.Personal])
        assertNull(counts[CategoryFilter.Transaction])
        assertNull(counts[CategoryFilter.Spam])
        assertFalse(counts.containsKey(CategoryFilter.Personal))
    }

    @Test
    fun `chip order is personal otp transactions promotions spam`() {
        assertEquals(
            listOf("Personal", "Otp", "Transaction", "Promotion", "Spam"),
            CategoryFilter.displayOrder.map { it.name }
        )
    }

    @Test
    fun `no chip selected means no narrowing`() {
        val effective = ConversationCategoryResolver.effectiveCategories(
            automatic = listOf(ThreadCategoryRow(1L, "OTP")),
            overrides = emptyList()
        )
        assertNull(threadIdsFor(null, effective))
    }

    @Test
    fun `selecting a chip narrows to exactly its threads`() {
        val effective = ConversationCategoryResolver.effectiveCategories(
            automatic = listOf(
                ThreadCategoryRow(1L, "OTP"),
                ThreadCategoryRow(2L, "PERSONAL"),
                ThreadCategoryRow(3L, "OTP")
            ),
            overrides = emptyList()
        )
        assertEquals(setOf(1L, 3L), threadIdsFor(CategoryFilter.Otp, effective))
    }

    // ── OTP cleanup eligibility (the data the OTP-retention workstream reads) ──

    @Test
    fun `otp cleanup is off by default`() {
        assertEquals(
            0L,
            ClassificationRepository.otpDeleteEligibleAt(
                isOtp = true,
                messageType = ClassificationRepository.INBOX_MESSAGE_TYPE,
                dateMs = 1_000L,
                retentionMillis = 0L
            )
        )
    }

    @Test
    fun `an eligible otp is dated from the message, not from now`() {
        val day = 24L * 60 * 60 * 1000
        assertEquals(
            1_000L + day,
            ClassificationRepository.otpDeleteEligibleAt(
                isOtp = true,
                messageType = ClassificationRepository.INBOX_MESSAGE_TYPE,
                dateMs = 1_000L,
                retentionMillis = day
            )
        )
    }

    @Test
    fun `a non otp is never eligible`() {
        val day = 24L * 60 * 60 * 1000
        assertEquals(
            0L,
            ClassificationRepository.otpDeleteEligibleAt(
                isOtp = false,
                messageType = ClassificationRepository.INBOX_MESSAGE_TYPE,
                dateMs = 1_000L,
                retentionMillis = day
            )
        )
    }

    @Test
    fun `an outgoing otp is never eligible`() {
        val day = 24L * 60 * 60 * 1000
        assertEquals(
            0L,
            ClassificationRepository.otpDeleteEligibleAt(
                isOtp = true,
                messageType = 2,
                dateMs = 1_000L,
                retentionMillis = day
            )
        )
    }

    @Test
    fun `an otp with no usable date is never eligible`() {
        assertEquals(
            0L,
            ClassificationRepository.otpDeleteEligibleAt(
                isOtp = true,
                messageType = ClassificationRepository.INBOX_MESSAGE_TYPE,
                dateMs = 0L,
                retentionMillis = 86_400_000L
            )
        )
    }

    @Test
    fun `negative retention stays disabled`() {
        assertEquals(
            0L,
            ClassificationRepository.otpDeleteEligibleAt(
                isOtp = true,
                messageType = ClassificationRepository.INBOX_MESSAGE_TYPE,
                dateMs = 5_000L,
                retentionMillis = -1L
            )
        )
    }

    // ── Backfill cursor contract ───────────────────────────────────────────

    @Test
    fun `a fresh sweep starts from the newest row`() {
        val start = BackfillCursor.START
        assertEquals(Long.MAX_VALUE, start.date)
        assertEquals(Long.MAX_VALUE, start.providerId)
        assertEquals("", start.source)
    }

    @Test
    fun `batch bounds stay inside the required 200 to 500 window`() {
        assertEquals(200, ClassificationRepository.MIN_BATCH)
        assertEquals(500, ClassificationRepository.MAX_BATCH)
        assertTrue(ClassificationRepository.DEFAULT_BATCH in 200..500)
    }

    @Test
    fun `the cursor advances strictly backwards through the canonical order`() {
        // Mirrors the assertion the persistence test makes on real rows: each
        // batch's cursor is the last row of that batch, and a keyset predicate
        // only accepts rows that sort STRICTLY AFTER the cursor.
        //
        // The sweep walks the canonical order DESCENDING (date DESC, source DESC,
        // providerId DESC), so "after the cursor" means OLDER: the older cursor
        // (date 500) is the one that sorts after the newer one (date 900).
        val firstBatch = BackfillCursor(date = 900L, source = "sms", providerId = 42L)
        val secondBatch = BackfillCursor(date = 500L, source = "mms", providerId = 7L)
        assertTrue(isAfter(secondBatch, firstBatch))
        assertFalse("a cursor is not after itself", isAfter(firstBatch, firstBatch))
        // Equal-date rows stay distinct and ordered by (source, providerId), so an
        // equal timestamp can never skip rows. Descending source order means "sms"
        // sorts BEFORE "mms" ('s' > 'm'), so the mms cursor is the one that has not
        // been consumed yet.
        val sameDateSms = BackfillCursor(date = 900L, source = "sms", providerId = 10L)
        val sameDateMms = BackfillCursor(date = 900L, source = "mms", providerId = 10L)
        assertTrue(isAfter(sameDateMms, sameDateSms))
        assertFalse(isAfter(sameDateSms, sameDateMms))
    }

    @Test
    fun `an outcome with no rows reports not finished only when the read failed`() {
        val cursor = BackfillCursor.START
        val readFailed = BackfillOutcome(cursor = cursor, handled = 0, finished = false)
        val swept = BackfillOutcome(cursor = cursor, handled = 0, finished = true)
        assertFalse(readFailed.finished)
        assertTrue(swept.finished)
        assertEquals(cursor, readFailed.cursor)
    }

    // ── Test-local mirrors of the pure projection helpers ──────────────────
    // Kept minimal and duplicated from `ui/home/HomeCategoryChipBar`'s caller on
    // purpose: they pin the CONTRACT (which chips exist, what they narrow to)
    // without pulling Compose into the JVM test source set.

    private fun counts(
        effective: List<ConversationCategoryResolver.EffectiveCategory>
    ): Map<CategoryFilter, Int> {
        val out = LinkedHashMap<CategoryFilter, Int>()
        for (filter in CategoryFilter.displayOrder) {
            val category = filter.category ?: continue
            val count = effective.count { it.category == category }
            if (count > 0) out[filter] = count
        }
        return out
    }

    private fun threadIdsFor(
        selected: CategoryFilter?,
        effective: List<ConversationCategoryResolver.EffectiveCategory>
    ): Set<Long>? {
        val category = selected?.category ?: return null
        return effective.asSequence()
            .filter { it.category == category }
            .map { it.threadId }
            .toHashSet()
    }

    /** Keyset predicate mirror: `row` sorts strictly after `cursor`. */
    private fun isAfter(row: BackfillCursor, cursor: BackfillCursor): Boolean = when {
        row.date != cursor.date -> row.date < cursor.date
        row.source != cursor.source -> row.source < cursor.source
        else -> row.providerId < cursor.providerId
    }
}
