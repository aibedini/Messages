package com.autonomousone.messages

import com.autonomousone.messages.data.ConversationPreferenceEntity
import com.autonomousone.messages.data.MessageCategory
import com.autonomousone.messages.repository.SpamActionPlan
import com.autonomousone.messages.repository.SpamBlockPort
import com.autonomousone.messages.repository.SpamReportOutcome
import com.autonomousone.messages.repository.SpamReportPolicy
import com.autonomousone.messages.repository.SpamRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.4.0 FEATURE 15 — local spam report / block provenance.
 *
 * The rules under test are the ones that are easy to get subtly wrong and
 * expensive to get wrong in production: a report must never delete messages, a
 * report must never claim a block the user made themselves, and Not-Spam must
 * never unblock a number the user had blocked manually.
 */
class SpamRepositoryTest {

    // ── Pure policy: effective category precedence ──────────────────────────

    @Test
    fun `spam report outranks a category override`() {
        val prefs = ConversationPreferenceEntity(
            threadId = 7L,
            categoryOverride = MessageCategory.PROMOTION.name,
            spam = true
        )
        assertEquals(
            MessageCategory.SPAM,
            SpamReportPolicy.effectiveCategory(prefs, automatic = MessageCategory.PROMOTION)
        )
    }

    @Test
    fun `category override outranks the automatic classifier`() {
        val prefs = ConversationPreferenceEntity(
            threadId = 7L,
            categoryOverride = MessageCategory.TRANSACTION.name
        )
        assertEquals(
            MessageCategory.TRANSACTION,
            SpamReportPolicy.effectiveCategory(prefs, automatic = MessageCategory.PROMOTION)
        )
    }

    @Test
    fun `automatic category is used when there is no override and no report`() {
        assertEquals(
            MessageCategory.OTP,
            SpamReportPolicy.effectiveCategory(null, automatic = MessageCategory.OTP)
        )
        assertEquals(
            MessageCategory.UNKNOWN,
            SpamReportPolicy.effectiveCategory(null, automatic = null)
        )
    }

    // ── Pure plan: provenance ──────────────────────────────────────────────

    @Test
    fun `report on an unblocked number creates the block and claims provenance`() {
        val plan = SpamActionPlan.report("09123456789", wasBlockedBefore = false)

        assertTrue(plan.blockAddress)
        assertFalse(plan.unblockAddress)
        assertTrue(plan.spam)
        assertTrue(plan.blockedByReport)
        assertTrue(plan.clearBlockProvenanceOnUndo)
    }

    @Test
    fun `report on an already-blocked number keeps the block as the user's own`() {
        val plan = SpamActionPlan.report("09123456789", wasBlockedBefore = true)

        // The block already exists, so the report must not re-issue the system
        // contract write and must NOT claim the block as its own.
        assertFalse(plan.blockAddress)
        assertTrue(plan.spam)
        assertFalse(plan.blockedByReport)
        assertFalse(plan.clearBlockProvenanceOnUndo)
    }

    @Test
    fun `not spam unblocks only when the report created the block`() {
        val createdByReport = SpamActionPlan.notSpam("09123456789", blockedByReport = true)
        assertTrue(createdByReport.unblockAddress)
        assertFalse(createdByReport.spam)
        assertFalse(createdByReport.blockedByReport)

        val preExisting = SpamActionPlan.notSpam("09123456789", blockedByReport = false)
        assertFalse(
            "a manual block must survive Not-Spam",
            preExisting.unblockAddress
        )
        assertFalse(preExisting.spam)
    }

    @Test
    fun `manual block is never recorded as spam provenance`() {
        val block = SpamActionPlan.block("09123456789", blocked = true)
        assertTrue(block.blockAddress)
        assertFalse("a manual block is not a spam report", block.spam)
        assertFalse(block.blockedByReport)
        assertFalse(block.clearBlockProvenanceOnUndo)

        val unblock = SpamActionPlan.block("09123456789", blocked = false)
        assertTrue(unblock.unblockAddress)
        assertFalse(unblock.spam)
        assertFalse(unblock.blockedByReport)
    }

    // ── Repository behaviour against a recording fake port ─────────────────

    private class FakePort(
        private var blocked: MutableSet<String> = mutableSetOf()
    ) : SpamBlockPort {
        val blockedAdds = mutableListOf<String>()
        val blockedRemovals = mutableListOf<String>()
        val spamWrites = mutableListOf<Pair<Long, Boolean>>()
        val spamClears = mutableListOf<Pair<Long, Boolean>>()

        override fun isBlocked(normalizedAddress: String): Boolean = normalizedAddress in blocked
        override fun block(address: String) {
            blockedAdds += address
            blocked += address
        }
        override fun unblock(address: String) {
            blockedRemovals += address
            blocked -= address
        }
        override suspend fun markSpam(threadId: Long, blockedByReport: Boolean, now: Long) {
            spamWrites += threadId to blockedByReport
        }
        override suspend fun clearSpam(
            threadId: Long,
            clearBlockProvenance: Boolean,
            now: Long
        ) {
            spamClears += threadId to clearBlockProvenance
        }
    }

    private fun repository(port: FakePort, now: Long = 1_000L) =
        SpamRepository(port, now = { now })

    @Test
    fun `report blocks the sender, marks the conversation spam and partitions events`() = runBlocking {
        val port = FakePort()
        val outcome = repository(port).reportSpam(threadId = 42L, address = "09123456789")

        assertTrue(outcome is SpamReportOutcome.Reported)
        assertEquals(listOf("09123456789"), port.blockedAdds)
        assertEquals(listOf(42L to true), port.spamWrites)
        assertEquals("notification id is the sender hash", "09123456789".hashCode(), repository(port).notificationIdentity("09123456789"))
    }

    @Test
    fun `report of a pre-existing manual block does not claim provenance`() = runBlocking {
        val port = FakePort(blocked = mutableSetOf("09123456789"))
        repository(port).reportSpam(threadId = 42L, address = "09123456789")

        assertTrue("no second block write", port.blockedAdds.isEmpty())
        assertEquals(listOf(42L to false), port.spamWrites)
    }

    @Test
    fun `not spam returns the conversation to the inbox`() = runBlocking {
        val port = FakePort()
        repository(port).reportSpam(threadId = 42L, address = "09123456789")
        repository(port).notSpam(threadId = 42L, address = "09123456789", blockedByReport = true)

        assertEquals(listOf("09123456789"), port.blockedRemovals)
        assertEquals(listOf(42L to true), port.spamClears)
    }

    @Test
    fun `not spam keeps a manually blocked number blocked`() = runBlocking {
        val port = FakePort(blocked = mutableSetOf("09123456789"))
        repository(port).notSpam(threadId = 42L, address = "09123456789", blockedByReport = false)

        assertTrue("the user's own block must survive", port.blockedRemovals.isEmpty())
        assertEquals(listOf(42L to false), port.spamClears)
    }

    @Test
    fun `blank address writes nothing at all`() = runBlocking {
        val port = FakePort()
        assertEquals(
            SpamReportOutcome.InvalidAddress,
            repository(port).reportSpam(threadId = 42L, address = "")
        )
        assertEquals(
            SpamReportOutcome.InvalidAddress,
            repository(port).notSpam(42L, "", blockedByReport = true)
        )
        assertEquals(
            SpamReportOutcome.InvalidAddress,
            repository(port).setBlocked("   ", blocked = true)
        )
        assertTrue(port.blockedAdds.isEmpty())
        assertTrue(port.blockedRemovals.isEmpty())
        assertTrue(port.spamWrites.isEmpty())
        assertTrue(port.spamClears.isEmpty())
    }

    @Test
    fun `address normalization folds the iranian country code before the probe`() = runBlocking {
        // BlocklistRepository.normalize("+989123456789") -> "09123456789"
        val port = FakePort(blocked = mutableSetOf("09123456789"))
        repository(port).reportSpam(threadId = 42L, address = "+989123456789")

        assertTrue(
            "the +98 form must be recognized as the already-blocked number",
            port.blockedAdds.isEmpty()
        )
        assertEquals(listOf(42L to false), port.spamWrites)
    }

    @Test
    fun `a report never deletes a message`() {
        // Structural guard, expressed as a test on the public surface: the whole
        // spam workflow exposes no delete of any kind. If a future change adds
        // one, this test's intent (messages are retained) must be revisited
        // deliberately rather than silently.
        val methods = SpamRepository::class.java.methods.map { it.name }.toSet()
        assertFalse(methods.any { it.contains("delete", ignoreCase = true) })
        assertFalse(methods.any { it.contains("purge", ignoreCase = true) })
    }

    /**
     * The provenance probe is the reason [com.autonomousone.messages.repository.BlocklistRepository.isBlockedNorm]
     * exists next to the fuzzy `isBlocked`: claiming a DIFFERENT stored number
     * as "already blocked" would let a report take ownership of the user's own
     * block and then remove it on Undo.
     */
    @Test
    fun `normalize is exact enough for provenance and still folds the country code`() {
        assertEquals("09123456789", com.autonomousone.messages.repository.BlocklistRepository.normalize("+989123456789"))
        assertEquals("09123456789", com.autonomousone.messages.repository.BlocklistRepository.normalize("0912 345 6789"))
        assertEquals("09123456789", com.autonomousone.messages.repository.BlocklistRepository.normalize("989123456789"))
        assertEquals("", com.autonomousone.messages.repository.BlocklistRepository.normalize("not a number"))
    }
}
