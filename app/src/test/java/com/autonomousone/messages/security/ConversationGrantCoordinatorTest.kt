package com.autonomousone.messages.security

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LDV-004 PR-E slice 2b — [ConversationGrantCoordinator]: persisted scope in, `KEY_GRANT`
 * events out.
 *
 * Pure JVM test. Room, the Keystore, Tink HPKE and ECDSA are all behind the coordinator's
 * ports, so every case here runs against in-memory fakes: no device, no network, no Android,
 * no key material that matters. The payload shape itself is covered by
 * `ConversationGrantPublisherTest`; this file covers the DIFF, the SEQUENCE, the ORDER, the
 * all-or-nothing persistence rule and the failure reporting.
 */
class ConversationGrantCoordinatorTest {

    private val now = 1_700_000_000_000L
    private val devicePublicKey = ByteArray(ConversationGrantSchema.P256_PUBLIC_KEY_BYTES) { index ->
        if (index == 0) ConversationGrantSchema.P256_UNCOMPRESSED_PREFIX else index.toByte()
    }
    private val target = GrantTarget(deviceId = "ai-device-1", encryptionPublicKey = devicePublicKey)

    private val store = InMemoryScopeStore()
    private val keys = FakeKeySource()
    private val outbox = RecordingOutbox()
    private val epoch = FakeEpoch("epoch-7")

    private fun coordinator(
        store: ConversationScopeStore = this.store,
        keys: ConversationKeySource = this.keys,
        outbox: GrantOutbox = this.outbox,
        epoch: GrantEpochSource = this.epoch,
    ): ConversationGrantCoordinator = ConversationGrantCoordinator(
        store = store,
        keys = keys,
        outbox = outbox,
        signer = FakeSigner(),
        wrapper = FakeWrapper(),
        epoch = epoch,
        now = { now },
        grantValidityMillis = ONE_DAY,
    )

    private fun selection(vararg ids: String): ScopeSelection =
        ScopeSelection.Conversations(ids.toSet())

    private fun apply(
        newSelection: ScopeSelection,
        expiresAtLimit: Long? = null,
        conversationCategory: (String) -> String? = { null },
    ) = runBlocking {
        coordinator().applySelection(
            device = target,
            newSelection = newSelection,
            conversationCategory = conversationCategory,
            expiresAtLimit = expiresAtLimit,
        )
    }

    private fun applyTwice(
        first: ScopeSelection,
        second: ScopeSelection,
        conversationCategory: (String) -> String? = { null },
    ): ScopeApplyResult = runBlocking {
        val coordinator = coordinator()
        coordinator.applySelection(target, first, conversationCategory)
        coordinator.applySelection(target, second, conversationCategory)
    }

    // ── Add / grant ─────────────────────────────────────────────────────────

    @Test
    fun `first selection grants every conversation at sequence 1`() {
        val result = apply(selection("c-a", "c-b"))

        val applied = result as ScopeApplyResult.Applied
        assertEquals(2, applied.published.size)
        assertEquals(
            listOf("c-a", "c-b"),
            applied.published.map { it.conversationId },
        )
        assertTrue(applied.published.all { it.kind == GrantKind.GRANT })
        assertTrue("one decision has one sequence", applied.published.all { it.scopeSequence == 1L })
        assertEquals(1L, applied.state.scopeSequence)
        assertEquals(selection("c-a", "c-b"), applied.state.selection)
        assertEquals("the new scope is persisted once", 1, store.saves)
        assertEquals(applied.state, store.state)
    }

    @Test
    fun `adding a conversation grants only the new one and increments the sequence`() {
        val result = applyTwice(selection("c-a"), selection("c-a", "c-b"))

        val applied = result as ScopeApplyResult.Applied
        assertEquals(listOf("c-b"), applied.published.map { it.conversationId })
        assertEquals(2L, applied.state.scopeSequence)
        assertEquals(2L, applied.published.single().scopeSequence)
        // `ScopeState.withSelection` re-records EVERY selected conversation at the decision's
        // sequence; only the newly added one is published, because the others are still
        // authorised by their earlier grant.
        assertEquals(2L, store.state!!.entries.single { it.conversationId == "c-a" }.scopeSequence)
        assertEquals(2L, store.state!!.entries.single { it.conversationId == "c-b" }.scopeSequence)
        assertEquals(
            "the first save's grant for c-a is not re-published",
            1,
            outbox.events.indices.count { outbox.json(it).getString("conversationId") == "c-a" },
        )
    }

    @Test
    fun `every event of one decision carries the same scopeSequence`() {
        val applied = apply(selection("c-a", "c-b", "c-c")) as ScopeApplyResult.Applied

        assertEquals(3, applied.published.size)
        assertEquals(setOf(1L), applied.published.map { it.scopeSequence }.toSet())
        assertEquals(setOf(1L), outbox.jsonValues("scopeSequence").toSet())
    }

    // ── Remove / tombstone ──────────────────────────────────────────────────

    @Test
    fun `removing a conversation publishes a key-less tombstone`() {
        val result = applyTwice(selection("c-a", "c-b"), selection("c-a"))

        val applied = result as ScopeApplyResult.Applied
        val tombstone = applied.published.single()
        assertEquals(GrantKind.REVOKE, tombstone.kind)
        assertEquals("c-b", tombstone.conversationId)
        assertNull("a tombstone carries no scopeMode", tombstone.scopeMode)
        assertEquals(2L, applied.state.scopeSequence)
        assertTrue(store.state!!.entries.single { it.conversationId == "c-b" }.revoked)

        val json = outbox.json(2)
        assertEquals("gmweb-conversation-key-revoke", json.getString("kind"))
        assertEquals("c-b", json.getString("conversationId"))
        assertEquals("revoked", json.getString("reason"))
        assertFalse("a tombstone must not carry a wrapped key", json.has("wrappedKey"))
        assertFalse(json.has("scopeMode"))
        assertFalse(json.has("epochId"))
        assertFalse(json.has("expiresAt"))
    }

    @Test
    fun `re-adding a revoked conversation grants it again at a new sequence`() {
        runBlocking {
            val coordinator = coordinator()
            coordinator.applySelection(target, selection("c-a", "c-b"), { null })
            coordinator.applySelection(target, selection("c-a"), { null })
            val result = coordinator.applySelection(target, selection("c-a", "c-b"), { null })

            val applied = result as ScopeApplyResult.Applied
            assertEquals(listOf("c-b"), applied.published.map { it.conversationId })
            assertEquals(GrantKind.GRANT, applied.published.single().kind)
            assertEquals(3L, applied.published.single().scopeSequence)
            assertEquals(3L, store.state!!.entries.single { it.conversationId == "c-b" }.scopeSequence)
            assertFalse(store.state!!.entries.single { it.conversationId == "c-b" }.revoked)
        }
    }

    // ── Account-wide ────────────────────────────────────────────────────────

    @Test
    fun `account wide is a single account-scope grant`() {
        val applied = apply(ScopeSelection.AccountWide) as ScopeApplyResult.Applied

        assertEquals(1, applied.published.size)
        assertEquals(GrantKind.GRANT, applied.published.single().kind)
        assertEquals(GrantScopeMode.ACCOUNT, applied.published.single().scopeMode)
        assertNull(applied.published.single().conversationId)
        assertEquals(ScopeSelection.AccountWide, applied.state.selection)
        assertEquals(1L, applied.state.scopeSequence)

        val json = outbox.json(0)
        assertEquals("account", json.getString("scopeMode"))
        assertFalse("an account grant names no conversation", json.has("conversationId"))
    }

    @Test
    fun `narrowing away from account wide withdraws the account grant then grants the list`() {
        val result = applyTwice(ScopeSelection.AccountWide, selection("c-a"))

        val applied = result as ScopeApplyResult.Applied
        assertEquals(2, applied.published.size)
        val withdrawal = applied.published[0]
        assertEquals(GrantKind.REVOKE, withdrawal.kind)
        assertNull("the account tombstone names no conversation", withdrawal.conversationId)
        assertEquals(GrantKind.GRANT, applied.published[1].kind)
        assertEquals("c-a", applied.published[1].conversationId)
        assertEquals(2L, applied.state.scopeSequence)

        val tombstone = outbox.json(1)
        assertEquals("gmweb-conversation-key-revoke", tombstone.getString("kind"))
        assertEquals("superseded", tombstone.getString("reason"))
        assertFalse(tombstone.has("conversationId"))
    }

    @Test
    fun `switching to account wide publishes no per-conversation denies`() {
        val result = applyTwice(selection("c-a", "c-b"), ScopeSelection.AccountWide)

        val applied = result as ScopeApplyResult.Applied
        assertEquals(
            "an account grant supersedes the list; a tombstone would be a sticky deny in god-ai",
            1,
            applied.published.size,
        )
        assertEquals(GrantScopeMode.ACCOUNT, applied.published.single().scopeMode)
        assertEquals(
            "three events total: the two grants, then only the account grant",
            3,
            outbox.events.size,
        )
        // The per-conversation decisions are still retired in the DATABASE.
        assertEquals(LinkedMode.ACCOUNT, store.state!!.selectionMode)
        assertTrue(store.state!!.entries.all { it.revoked })
    }

    // ── Policy ──────────────────────────────────────────────────────────────

    @Test
    fun `a sensitive conversation is rejected and nothing is published or persisted`() {
        val result = apply(selection("c-a"), conversationCategory = { "OTP" })

        val rejected = result as ScopeApplyResult.Rejected
        assertEquals(ScopeViolation.SENSITIVE_CONVERSATION, rejected.violation)
        assertEquals("c-a", rejected.conversationId)
        assertTrue(outbox.events.isEmpty())
        assertEquals(0, store.saves)
        assertNull(store.state)
    }

    @Test
    fun `LOCAL_ONLY and an unknown category fail closed`() {
        for (category in listOf(ConversationScope.LOCAL_ONLY, "SOMETHING_NEW", "READ_OTP")) {
            val result = apply(selection("c-a"), conversationCategory = { category })
            assertEquals(
                "category=$category must be refused",
                ScopeViolation.SENSITIVE_CONVERSATION,
                (result as ScopeApplyResult.Rejected).violation,
            )
            assertEquals("nothing published for $category", 0, outbox.events.size)
        }
    }

    @Test
    fun `an empty conversation set is rejected, never read as nothing selected`() {
        val result = apply(selection())

        val rejected = result as ScopeApplyResult.Rejected
        assertEquals(ScopeViolation.EMPTY_CONVERSATION_SET, rejected.violation)
        assertTrue(outbox.events.isEmpty())
        assertNull(store.state)
    }

    @Test
    fun `re-saving the same selection publishes nothing and keeps the sequence`() {
        val result = applyTwice(selection("c-a"), selection("c-a"))

        assertEquals(ScopeApplyResult.Unchanged, result)
        assertEquals(1, outbox.events.size)
        assertEquals(1, store.saves)
        assertEquals(1L, store.state!!.scopeSequence)
    }

    @Test
    fun `re-saving account wide is also a no-op`() {
        val result = applyTwice(ScopeSelection.AccountWide, ScopeSelection.AccountWide)

        assertEquals(ScopeApplyResult.Unchanged, result)
        assertEquals(1, outbox.events.size)
    }

    // ── Failure / rollback ──────────────────────────────────────────────────

    @Test
    fun `an outbox failure leaves the persisted scope untouched`() {
        val failing = RecordingOutbox(failOn = 2)
        val result = runBlocking {
            val coordinator = coordinator(outbox = failing)
            coordinator.applySelection(target, selection("c-a", "c-b"), { null })
        }

        val failed = result as ScopeApplyResult.Failed
        assertEquals(ScopeApplyStage.OUTBOX, failed.stage)
        assertEquals("one payload was already enqueued", 1, failed.publishedBeforeFailure)
        assertEquals(1, failing.events.size)
        assertEquals("the DB must not record a scope that was not published", 0, store.saves)
        assertNull(store.state)
    }

    @Test
    fun `a persistence failure is reported after the enqueues, db unchanged`() {
        store.failSave = true
        val result = apply(selection("c-a"))

        val failed = result as ScopeApplyResult.Failed
        assertEquals(ScopeApplyStage.PERSIST, failed.stage)
        assertEquals(1, failed.publishedBeforeFailure)
        assertEquals("the enqueue happened", 1, outbox.events.size)
        assertNull("but the old (unknown) scope is still what is persisted", store.state)
    }

    @Test
    fun `a missing cke fails closed before anything is enqueued`() {
        val noKeys = FakeKeySource(available = emptySet())
        val result = runBlocking {
            coordinator(keys = noKeys).applySelection(target, selection("c-a"), { null })
        }

        val failed = result as ScopeApplyResult.Failed
        assertEquals(ScopeApplyStage.KEY, failed.stage)
        assertEquals(0, failed.publishedBeforeFailure)
        assertTrue("build-all-then-enqueue means nothing was published", outbox.events.isEmpty())
        assertNull(store.state)
    }

    @Test
    fun `a missing epoch fails closed as an epoch failure`() {
        val result = runBlocking {
            coordinator(epoch = FakeEpoch(null)).applySelection(target, selection("c-a"), { null })
        }

        val failed = result as ScopeApplyResult.Failed
        assertEquals(ScopeApplyStage.EPOCH, failed.stage)
        assertTrue(outbox.events.isEmpty())
        assertNull(store.state)
    }

    @Test
    fun `an expired device certificate is refused before any payload is built`() {
        val result = apply(selection("c-a"), expiresAtLimit = now - 1L)

        val failed = result as ScopeApplyResult.Failed
        assertEquals(ScopeApplyStage.EXPIRY, failed.stage)
        assertTrue(outbox.events.isEmpty())
        assertNull(store.state)
    }

    @Test
    fun `a load failure is reported as a load failure`() {
        store.failLoad = true
        val result = apply(selection("c-a"))

        assertEquals(ScopeApplyStage.LOAD, (result as ScopeApplyResult.Failed).stage)
        assertTrue(outbox.events.isEmpty())
    }

    // ── Key handling ────────────────────────────────────────────────────────

    @Test
    fun `the coordinator zeroes every plaintext key it was given`() {
        apply(selection("c-a", "c-b"))

        assertEquals(2, keys.returned.size)
        for (key in keys.returned) {
            assertArrayEquals(
                "the plaintext CKE must be zeroed after wrapping",
                ByteArray(key.size),
                key,
            )
        }
    }

    @Test
    fun `the account grant asks the key source for the account key`() {
        apply(ScopeSelection.AccountWide)

        assertEquals(
            listOf(ConversationGrantCoordinator.ACCOUNT_CONVERSATION_ID),
            keys.requested,
        )
    }

    @Test
    fun `no payload contains the plaintext cke`() {
        apply(selection("c-a"))

        val rawKey = ByteArray(ConversationGrantSchema.CKE_BYTES) { (it + 1).toByte() }
        val payload = String(outbox.events.single().third, Charsets.UTF_8)
        assertFalse("the plaintext key must never be serialised", payload.contains(MessageCrypto.b64(rawKey)))
        assertTrue("the wrapped key is present instead", payload.contains("wrappedKey"))
    }

    // ── Payload contract as enqueued ────────────────────────────────────────

    @Test
    fun `the enqueued event id is the payload grantId and the payload targets the device`() {
        apply(selection("c-a"))

        val (eventId, deviceId, bytes) = outbox.events.single()
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        assertEquals(json.getString("grantId"), eventId)
        assertEquals(target.deviceId, deviceId)
        assertEquals(target.deviceId, json.getString("deviceId"))
        assertEquals("gmweb-conversation-key-grant", json.getString("kind"))
        assertEquals("conversation", json.getString("scopeMode"))
        assertEquals("c-a", json.getString("conversationId"))
        assertEquals("READ_MESSAGES", json.getString("keyDomain"))
        assertEquals("epoch-7", json.getString("epochId"))
        assertEquals(1L, json.getLong("scopeSequence"))
        assertEquals(now, json.getLong("issuedAt"))
        assertEquals("the grant must not outlive the device certificate", now + ONE_DAY, json.getLong("expiresAt"))
    }

    @Test
    fun `the grant is clamped to the device certificate expiry`() {
        val expiry = now + 60_000L
        apply(selection("c-a"), expiresAtLimit = expiry)

        assertEquals(expiry, outbox.json(0).getLong("expiresAt"))
    }

    // ── Fakes ───────────────────────────────────────────────────────────────

    /** The persisted state as one enum, so assertions never compare `ScopeSelection` types. */
    private enum class LinkedMode { ACCOUNT, CONVERSATIONS, NONE }

    private val ScopeState.selectionMode: LinkedMode?
        get() = when (selection) {
            ScopeSelection.AccountWide -> LinkedMode.ACCOUNT
            is ScopeSelection.Conversations -> LinkedMode.CONVERSATIONS
            null -> if (revoked) LinkedMode.NONE else null
        }

    private class InMemoryScopeStore : ConversationScopeStore {
        var state: ScopeState? = null
        var saves = 0
        var failLoad = false
        var failSave = false

        override suspend fun load(deviceId: String): ScopeState? {
            if (failLoad) throw IllegalStateException("database gone")
            return state
        }

        override suspend fun save(deviceId: String, state: ScopeState): Boolean {
            saves++
            if (failSave) return false
            this.state = state
            return true
        }
    }

    private class RecordingOutbox(private val failOn: Int? = null) : GrantOutbox {
        val events = mutableListOf<Triple<String, String, ByteArray>>()

        override suspend fun enqueue(eventId: String, deviceId: String, payloadJson: ByteArray) {
            if (failOn != null && events.size + 1 == failOn) {
                throw IllegalStateException("outbox write failed")
            }
            events += Triple(eventId, deviceId, payloadJson)
        }

        fun json(index: Int): JSONObject = JSONObject(String(events[index].third, Charsets.UTF_8))

        fun jsonValues(field: String): List<Long> =
            events.indices.map { json(it).getLong(field) }
    }

    private class FakeKeySource(private val available: Set<String>? = null) : ConversationKeySource {
        val requested = mutableListOf<String>()
        val returned = mutableListOf<ByteArray>()

        override suspend fun ckeFor(conversationId: String): ByteArray? {
            requested += conversationId
            if (available != null && conversationId !in available) return null
            return ByteArray(ConversationGrantSchema.CKE_BYTES) { (it + 1).toByte() }
                .also { returned += it }
        }
    }

    private class FakeEpoch(private val id: String?) : GrantEpochSource {
        override suspend fun epochId(): String =
            id ?: throw IllegalStateException("no READ_MESSAGES epoch yet")
    }

    /** Deterministic DER-shaped signature: 64 distinct bytes, no key involved. */
    private class FakeSigner : GrantSigner {
        override fun sign(binding: ByteArray): ByteArray = ByteArray(64) { (it + 1).toByte() }
    }

    /** Deterministic HPKE-shaped wrap: 96 bytes, no crypto involved. */
    private class FakeWrapper : GrantKeyWrapper {
        override fun wrap(recipientPublicKey: ByteArray, key: ByteArray, info: ByteArray): ByteArray =
            ByteArray(96) { (it + 1).toByte() }
    }

    private companion object {
        const val ONE_DAY = 24L * 60L * 60L * 1000L
    }
}
