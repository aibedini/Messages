package com.autonomousone.messages.data

import com.autonomousone.messages.security.ScopeEntry
import com.autonomousone.messages.security.ScopeSelection
import com.autonomousone.messages.security.ScopeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LDV-004 PR-E slice 2a — the pure mapping between Room rows and slice 1's
 * [ScopeState] / [ScopeSelection] / [ScopeEntry].
 *
 * Plain JVM test: the entities are data classes and the mappers are pure, so no Android,
 * Room runtime or SQLite is needed. These cover the cases the DAO's transactional writes
 * rely on: account-wide vs an empty enabled set, per-conversation grants and tombstones,
 * revoke-all, unknown devices and the monotonic-sequence rule.
 */
class LinkedDeviceScopeMapperTest {

    private val deviceId = "dev-1"

    private fun conversationState(): ScopeState =
        ScopeState.initial(ScopeSelection.Conversations(setOf("c-b", "c-a")), updatedAt = 100)

    // ── Account-wide ────────────────────────────────────────────────────────

    @Test
    fun `account wide state round trips with no conversation rows`() {
        val state = ScopeState.initial(ScopeSelection.AccountWide, updatedAt = 100)
        val entities = state.toEntities(deviceId)

        assertEquals(LinkedDeviceScopeStateEntity.MODE_ACCOUNT, entities.state.mode)
        assertEquals(100L, entities.state.updatedAt)
        assertTrue("account scope has no enumerable ids", entities.conversations.isEmpty())

        val snapshot = entities.toSnapshot()
        assertEquals(ScopeSelection.AccountWide, snapshot.toSelection())
        assertEquals(state, snapshot.toScopeState())
    }

    @Test
    fun `switching to account wide tombstones the previous conversations`() {
        val accountWide = conversationState().withSelection(ScopeSelection.AccountWide, 200)
        val entities = accountWide.toEntities(deviceId)

        assertEquals(LinkedDeviceScopeStateEntity.MODE_ACCOUNT, entities.state.mode)
        assertEquals(2L, entities.state.scopeSequence)
        assertEquals(listOf("c-a", "c-b"), entities.conversations.map { it.conversationId })
        assertTrue(
            "account mode grants everything directly, so the old ids must be tombstones",
            entities.conversations.none { it.enabled }
        )

        val restored = entities.toSnapshot().toScopeState()!!
        assertEquals(accountWide, restored)
        assertEquals(ScopeSelection.AccountWide, restored.selection)
        assertTrue("an account-wide state grants a conversation never seen locally", restored.grants("c-new"))
    }

    // ── Conversation scope ──────────────────────────────────────────────────

    @Test
    fun `conversation state round trips with sorted enabled rows`() {
        val state = conversationState()
        val entities = state.toEntities(deviceId)

        assertEquals(LinkedDeviceScopeStateEntity.MODE_CONVERSATION, entities.state.mode)
        assertEquals(listOf("c-a", "c-b"), entities.conversations.map { it.conversationId })
        assertTrue(entities.conversations.all { it.enabled })
        assertTrue(entities.conversations.all { it.scopeSequence == 1L })
        assertTrue(entities.conversations.all { it.updatedAt == 100L })

        val snapshot = entities.toSnapshot()
        val selection = snapshot.toSelection() as ScopeSelection.Conversations
        assertEquals(setOf("c-a", "c-b"), selection.ids)
        assertEquals(state, snapshot.toScopeState())
    }

    @Test
    fun `removing a conversation persists a disabled tombstone`() {
        val narrowed = conversationState()
            .withSelection(ScopeSelection.Conversations(setOf("c-a")), updatedAt = 200)
        val entities = narrowed.toEntities(deviceId)

        val rows = entities.conversations.associateBy { it.conversationId }
        assertEquals(true, rows.getValue("c-a").enabled)
        assertEquals(false, rows.getValue("c-b").enabled)
        assertEquals(2L, rows.getValue("c-a").scopeSequence)
        assertEquals(
            "the tombstone records the sequence that revoked it",
            2L,
            rows.getValue("c-b").scopeSequence
        )

        assertEquals(narrowed, entities.toSnapshot().toScopeState())
    }

    // ── Revoke-all ──────────────────────────────────────────────────────────

    @Test
    fun `revoke all persists a revoked state with every known conversation disabled`() {
        val revoked = conversationState()
            .withSelection(ScopeSelection.Conversations(setOf("c-a")), updatedAt = 200)
            .revokeAll(updatedAt = 300)
        val entities = revoked.toEntities(deviceId)

        assertEquals(LinkedDeviceScopeStateEntity.MODE_CONVERSATION, entities.state.mode)
        assertEquals(3L, entities.state.scopeSequence)
        assertEquals(listOf("c-a", "c-b"), entities.conversations.map { it.conversationId })
        assertTrue(
            "a paused device must have no enabled conversation",
            entities.conversations.none { it.enabled }
        )
        val rows = entities.conversations.associateBy { it.conversationId }
        assertEquals(
            "the conversation granted at revoke time gets the new tombstone sequence",
            3L,
            rows.getValue("c-a").scopeSequence
        )
        assertEquals(
            "a conversation revoked earlier keeps the sequence that revoked it",
            2L,
            rows.getValue("c-b").scopeSequence
        )

        val snapshot = entities.toSnapshot()
        assertNull("revoked state has no selection", snapshot.toSelection())
        val restored = snapshot.toScopeState()!!
        assertEquals(revoked, restored)
        assertTrue(restored.revoked)
        assertEquals(setOf("c-a", "c-b"), restored.revokedConversationIds)
        assertFalse(restored.grants("c-a"))
    }

    @Test
    fun `revoke all from account wide keeps the sequence without inventing ids`() {
        val revoked = ScopeState.initial(ScopeSelection.AccountWide, updatedAt = 100)
            .revokeAll(updatedAt = 200)
        val entities = revoked.toEntities(deviceId)

        // An empty enabled set is a revoked scope, never AccountWide and never an empty
        // Conversations set (which slice 1 rejects as invalid).
        assertEquals(LinkedDeviceScopeStateEntity.MODE_CONVERSATION, entities.state.mode)
        assertTrue(entities.conversations.isEmpty())
        assertNull(entities.toSnapshot().toSelection())
        assertEquals(revoked, entities.toSnapshot().toScopeState())
    }

    // ── Unknown / defensive cases ───────────────────────────────────────────

    @Test
    fun `unknown device maps to no state instead of a fabricated scope`() {
        val snapshot = LinkedDeviceScopeSnapshot()
        assertNull(snapshot.toSelection())
        assertNull(snapshot.toScopeState())
    }

    @Test
    fun `unknown persisted mode grants nothing`() {
        val snapshot = LinkedDeviceScopeSnapshot(
            state = LinkedDeviceScopeStateEntity(
                deviceId = deviceId,
                mode = "future-mode",
                scopeSequence = 3,
                updatedAt = 100,
            ),
            conversations = listOf(
                LinkedDeviceConversationScopeEntity(deviceId, "c-a", enabled = true, scopeSequence = 3, updatedAt = 100)
            ),
        )
        assertNull("an unrecognised mode must fail closed", snapshot.toSelection())
        assertTrue(snapshot.toScopeState()!!.revoked)
    }

    @Test
    fun `rows belonging to another device are ignored`() {
        val state = conversationState()
        val entities = state.toEntities(deviceId)
        val snapshot = LinkedDeviceScopeSnapshot(
            state = entities.state,
            conversations = entities.conversations + LinkedDeviceConversationScopeEntity(
                deviceId = "dev-other",
                conversationId = "c-other",
                enabled = true,
                scopeSequence = 99,
                updatedAt = 999,
            ),
        )
        assertEquals(state, snapshot.toScopeState())
        assertFalse(snapshot.toScopeState()!!.grants("c-other"))
    }

    @Test
    fun `a stale granted entry cannot widen the persisted scope`() {
        // c-b carries an enabled entry from an older sequence but is not in the authoritative
        // selection, so it must be persisted as a tombstone.
        val state = ScopeState(
            scopeSequence = 4,
            revoked = false,
            updatedAt = 400,
            selection = ScopeSelection.Conversations(setOf("c-a")),
            entries = listOf(
                ScopeEntry("c-a", scopeSequence = 4, revoked = false, updatedAt = 400),
                ScopeEntry("c-b", scopeSequence = 3, revoked = false, updatedAt = 300),
            ),
        )
        val rows = state.toEntities(deviceId).conversations.associateBy { it.conversationId }
        assertEquals(true, rows.getValue("c-a").enabled)
        assertEquals(false, rows.getValue("c-b").enabled)
        assertEquals(3L, rows.getValue("c-b").scopeSequence)
    }

    @Test
    fun `a selected id missing from entries still gets an enabled row`() {
        val state = ScopeState(
            scopeSequence = 5,
            revoked = false,
            updatedAt = 500,
            selection = ScopeSelection.Conversations(setOf("c-a", "c-c")),
            entries = listOf(
                ScopeEntry("c-a", scopeSequence = 5, revoked = false, updatedAt = 500),
            ),
        )
        val rows = state.toEntities(deviceId).conversations.associateBy { it.conversationId }
        assertEquals(setOf("c-a", "c-c"), rows.keys)
        assertEquals(true, rows.getValue("c-c").enabled)
        assertEquals(5L, rows.getValue("c-c").scopeSequence)
        assertEquals(500L, rows.getValue("c-c").updatedAt)
    }

    // ── Sequence monotonicity ───────────────────────────────────────────────

    @Test
    fun `stale and equal sequences are refused while newer ones win`() {
        assertTrue("the first write always wins", acceptsScopeSequence(null, 1))
        assertFalse("a lower sequence is stale", acceptsScopeSequence(5, 4))
        assertFalse("an equal sequence is stale", acceptsScopeSequence(5, 5))
        assertTrue("a strictly greater sequence wins", acceptsScopeSequence(5, 6))
        assertThrows(IllegalArgumentException::class.java) {
            acceptsScopeSequence(null, 0)
        }
    }

    @Test
    fun `conversation writes accept their own batch but never a stale one`() {
        assertTrue("first row of a device", acceptsConversationWrite(null, null, 1))
        assertTrue("completing the current state's batch", acceptsConversationWrite(5, null, 5))
        assertTrue("rewriting a row in the same batch", acceptsConversationWrite(5, 5, 5))
        assertTrue("a newer decision", acceptsConversationWrite(5, 4, 6))
        assertFalse(
            "a row below the device state must not be inserted",
            acceptsConversationWrite(5, null, 4)
        )
        assertFalse(
            "a row below its own recorded sequence is stale",
            acceptsConversationWrite(5, 4, 4)
        )
        assertThrows(IllegalArgumentException::class.java) {
            acceptsConversationWrite(null, null, 0)
        }
    }

    @Test
    fun `reissuing the current selection advances only the state sequence`() {
        val next = conversationState().nextSequence(updatedAt = 400)
        val entities = next.toEntities(deviceId)

        assertEquals(2L, entities.state.scopeSequence)
        assertEquals(400L, entities.state.updatedAt)
        assertEquals(
            "re-issuing a grant does not change a per-conversation decision",
            listOf(1L, 1L),
            entities.conversations.map { it.scopeSequence }
        )
        assertEquals(next, entities.toSnapshot().toScopeState())
    }
}
