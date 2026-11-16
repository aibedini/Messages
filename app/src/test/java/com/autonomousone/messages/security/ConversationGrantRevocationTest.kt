package com.autonomousone.messages.security

import com.autonomousone.messages.data.LinkedDeviceScopeStateEntity
import com.autonomousone.messages.data.toEntities
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LDV-004 PR-E slice 2c — the revocation lifecycle of [ConversationGrantCoordinator]:
 * `pauseAll`, `revokeConversation` and `revokeForSensitiveReclassification`.
 *
 * Plain JVM test. Room, the Keystore, HPKE and ECDSA stay behind the coordinator's ports, so
 * every case runs against in-memory fakes. What is pinned here:
 *
 *  - pause-all publishes tombstone denies explicitly at BOTH levels (account + every enabled
 *    conversation) — no "sticky deny" regression where only the local row changes;
 *  - the one-sequence-per-decision rule, and the DB ending in conversation mode with no
 *    enabled row (slice 2a's "revoked" encoding);
 *  - idempotency (`AlreadyRevoked` / `NotGranted`, no event, no save);
 *  - zero key material: a revoke never asks for a CKE/epoch and never emits one;
 *  - sensitive reclassification only for sensitive categories and only for AI devices that
 *    actually hold a grant, one device's failure never hiding another's;
 *  - outbox/persist failures leave the persisted scope unchanged.
 */
class ConversationGrantRevocationTest {

    private val now = 1_700_000_000_000L
    private val devicePublicKey = ByteArray(ConversationGrantSchema.P256_PUBLIC_KEY_BYTES) { index ->
        if (index == 0) ConversationGrantSchema.P256_UNCOMPRESSED_PREFIX else index.toByte()
    }
    private val target = GrantTarget(deviceId = "ai-device-1", encryptionPublicKey = devicePublicKey)
    private val secondTarget = GrantTarget(deviceId = "ai-device-2", encryptionPublicKey = devicePublicKey)

    private val store = InMemoryScopeStore()
    private val keys = FakeKeySource()
    private val outbox = RecordingOutbox()
    private val aiDevices = FakeAiDevices()

    private fun coordinator(): ConversationGrantCoordinator = ConversationGrantCoordinator(
        store = store,
        keys = keys,
        outbox = outbox,
        signer = FakeSigner(),
        wrapper = FakeWrapper(),
        epoch = FakeEpoch(),
        aiDevices = aiDevices,
        now = { now },
        grantValidityMillis = ONE_DAY,
    )

    private fun seed(selection: ScopeSelection) {
        store.state = ScopeState.initial(selection, updatedAt = 100)
    }

    private fun seedConversations(vararg ids: String) =
        seed(ScopeSelection.Conversations(ids.toSet()))

    private fun pause(reason: GrantRevokeReason = GrantRevokeReason.REVOKED) =
        runBlocking { coordinator().pauseAll(target, reason) }

    private fun revoke(conversationId: String, device: GrantTarget = target) =
        runBlocking {
            coordinator().revokeConversation(device, conversationId, GrantRevokeReason.REVOKED)
        }

    // ── pauseAll: account scope ─────────────────────────────────────────────

    @Test
    fun `pauseAll on an account scope publishes one account tombstone and clears the scope`() {
        seed(ScopeSelection.AccountWide)

        val result = pause() as ScopeRevokeResult.Revoked

        assertEquals(1, result.published.size)
        val tombstone = result.published.single()
        assertEquals(GrantKind.REVOKE, tombstone.kind)
        assertNull("an account tombstone names no conversation", tombstone.conversationId)
        assertEquals(2L, tombstone.scopeSequence)
        assertEquals(2L, result.state.scopeSequence)
        assertTrue(result.state.revoked)
        assertNull(result.state.selection)

        val json = outbox.json(0)
        assertEquals("gmweb-conversation-key-revoke", json.getString("kind"))
        assertEquals("revoked", json.getString("reason"))
        assertFalse("a revoke carries no key material", json.has("wrappedKey"))
        assertFalse(json.has("epochId"))
        assertFalse(json.has("scopeMode"))
        assertFalse(json.has("expiresAt"))

        val entities = result.state.toEntities(target.deviceId)
        assertEquals(LinkedDeviceScopeStateEntity.MODE_CONVERSATION, entities.state.mode)
        assertTrue("the paused state must have no enabled row", entities.conversations.none { it.enabled })

        assertEquals(1, store.saves)
        assertEquals(result.state, store.state)
        assertTrue("a revoke must never request key material", keys.requested.isEmpty())
    }

    // ── pauseAll: conversation scope ────────────────────────────────────────

    @Test
    fun `pauseAll tombstones every enabled conversation at one sequence`() {
        seedConversations("c-b", "c-a")

        val result = pause() as ScopeRevokeResult.Revoked

        assertEquals("deterministic sorted order", listOf("c-a", "c-b"), result.published.map { it.conversationId })
        assertTrue(result.published.all { it.kind == GrantKind.REVOKE })
        assertEquals("one decision, one sequence", setOf(2L), result.published.map { it.scopeSequence }.toSet())
        assertEquals(setOf(2L), outbox.jsonValues("scopeSequence").toSet())
        assertTrue(result.state.revoked)
        assertEquals(setOf("c-a", "c-b"), result.state.entries.map { it.conversationId }.toSet())
        assertTrue("every entry must be recorded as revoked", result.state.entries.all { it.revoked })

        val entities = result.state.toEntities(target.deviceId)
        assertEquals(LinkedDeviceScopeStateEntity.MODE_CONVERSATION, entities.state.mode)
        assertEquals(2, entities.conversations.size)
        assertTrue(entities.conversations.none { it.enabled })
        assertTrue(keys.requested.isEmpty())
    }

    @Test
    fun `pauseAll is idempotent - a second call publishes nothing and advances nothing`() {
        seedConversations("c-a", "c-b")
        pause()

        val savesAfterFirst = store.saves
        val eventsAfterFirst = outbox.events.size
        val sequenceAfterFirst = store.state!!.scopeSequence

        val second = pause()

        assertEquals(ScopeRevokeResult.AlreadyRevoked, second)
        assertEquals(eventsAfterFirst, outbox.events.size)
        assertEquals(savesAfterFirst, store.saves)
        assertEquals(sequenceAfterFirst, store.state!!.scopeSequence)
    }

    @Test
    fun `pauseAll of an unknown device is notGranted and publishes no deny`() {
        val result = pause()

        assertEquals(ScopeRevokeResult.NotGranted, result)
        assertTrue(outbox.events.isEmpty())
        assertEquals(0, store.saves)
        assertNull(store.state)
    }

    @Test
    fun `pauseAll honours the unlinked wire reason`() {
        seedConversations("c-a")

        pause(GrantRevokeReason.UNLINKED)

        assertEquals("unlinked", outbox.json(0).getString("reason"))
    }

    @Test
    fun `an outbox failure during pauseAll leaves the scope untouched`() {
        seedConversations("c-a", "c-b", "c-c")
        val failing = RecordingOutbox(failOn = 2)

        val result = runBlocking {
            ConversationGrantCoordinator(
                store = store,
                keys = keys,
                outbox = failing,
                signer = FakeSigner(),
                wrapper = FakeWrapper(),
                epoch = FakeEpoch(),
                aiDevices = aiDevices,
                now = { now },
            ).pauseAll(target)
        }

        val failed = result as ScopeRevokeResult.Failed
        assertEquals(ScopeApplyStage.OUTBOX, failed.stage)
        assertEquals("one tombstone was already enqueued", 1, failed.publishedBeforeFailure)
        assertEquals(1, failing.events.size)
        assertEquals("the DB must not claim a scope that was not published", 0, store.saves)
        assertEquals(1L, store.state!!.scopeSequence)
        assertEquals(ScopeSelection.Conversations(setOf("c-a", "c-b", "c-c")), store.state!!.selection)
    }

    @Test
    fun `a persistence failure during pauseAll is reported after the enqueues`() {
        seedConversations("c-a")
        store.failSave = true

        val failed = pause() as ScopeRevokeResult.Failed

        assertEquals(ScopeApplyStage.PERSIST, failed.stage)
        assertEquals(1, failed.publishedBeforeFailure)
        assertEquals(1, outbox.events.size)
        assertEquals("the persisted scope is still the granted one", 1L, store.state!!.scopeSequence)
        assertFalse(store.state!!.revoked)
    }

    @Test
    fun `a load failure during pauseAll is reported as a load failure`() {
        seedConversations("c-a")
        store.failLoad = true

        val failed = pause() as ScopeRevokeResult.Failed

        assertEquals(ScopeApplyStage.LOAD, failed.stage)
        assertTrue(outbox.events.isEmpty())
        assertEquals(0, store.saves)
    }

    // ── revokeConversation ──────────────────────────────────────────────────

    @Test
    fun `revokeConversation tombstones one conversation and persists the narrowed scope`() {
        seedConversations("c-a", "c-b")

        val result = revoke("c-a") as ScopeRevokeResult.Revoked

        val tombstone = result.published.single()
        assertEquals(GrantKind.REVOKE, tombstone.kind)
        assertEquals("c-a", tombstone.conversationId)
        assertEquals(2L, tombstone.scopeSequence)
        assertEquals("revoked", outbox.json(0).getString("reason"))
        assertFalse(outbox.json(0).has("wrappedKey"))
        assertEquals(target.deviceId, outbox.events.single().second)

        assertEquals(ScopeSelection.Conversations(setOf("c-b")), result.state.selection)
        assertEquals(2L, result.state.scopeSequence)
        assertTrue(result.state.entries.single { it.conversationId == "c-a" }.revoked)

        val entities = result.state.toEntities(target.deviceId)
        assertFalse(
            "the revoked conversation must be an enabled=0 row",
            entities.conversations.single { it.conversationId == "c-a" }.enabled,
        )
        assertTrue(entities.conversations.single { it.conversationId == "c-b" }.enabled)
        assertTrue(keys.requested.isEmpty())
    }

    @Test
    fun `revoking the last granted conversation leaves the device paused`() {
        seedConversations("c-a")

        val result = revoke("c-a") as ScopeRevokeResult.Revoked

        assertTrue(result.state.revoked)
        assertNull(result.state.selection)
        val entities = result.state.toEntities(target.deviceId)
        assertEquals(LinkedDeviceScopeStateEntity.MODE_CONVERSATION, entities.state.mode)
        assertTrue(entities.conversations.none { it.enabled })
    }

    @Test
    fun `revokeConversation is idempotent - a second call publishes nothing`() {
        seedConversations("c-a", "c-b")
        revoke("c-a")
        val savesAfterFirst = store.saves
        val eventsAfterFirst = outbox.events.size

        val second = revoke("c-a")

        assertEquals(ScopeRevokeResult.AlreadyRevoked, second)
        assertEquals(eventsAfterFirst, outbox.events.size)
        assertEquals(savesAfterFirst, store.saves)
    }

    @Test
    fun `revokeConversation of a conversation that was never granted is notGranted`() {
        seedConversations("c-b")

        val result = revoke("c-a")

        assertEquals(ScopeRevokeResult.NotGranted, result)
        assertTrue("no deny for a conversation nobody granted", outbox.events.isEmpty())
        assertEquals(0, store.saves)
    }

    @Test
    fun `revokeConversation of an unknown device is notGranted`() {
        val result = revoke("c-a")

        assertEquals(ScopeRevokeResult.NotGranted, result)
        assertTrue(outbox.events.isEmpty())
    }

    @Test
    fun `revoking one conversation of an account-wide scope pauses the account`() {
        seed(ScopeSelection.AccountWide)

        val result = runBlocking {
            coordinator().revokeConversation(target, "c-a", GrantRevokeReason.SUPERSEDED)
        } as ScopeRevokeResult.Revoked

        val tombstone = result.published.single()
        assertNull("the account tombstone is the only consistent fail-closed revoke", tombstone.conversationId)
        assertEquals("superseded", outbox.json(0).getString("reason"))
        assertTrue(result.state.revoked)
    }

    // ── Sensitive reclassification ──────────────────────────────────────────

    @Test
    fun `a non-sensitive category does nothing and never reads the device list`() {
        seedConversations("c-a")
        aiDevices.targets = listOf(target)

        for (category in listOf("", "NORMAL", "PERSONAL", "UNKNOWN", "SOMETHING_NEW")) {
            val result = runBlocking {
                coordinator().revokeForSensitiveReclassification("c-a", category)
            }
            assertEquals(
                "category=$category must not trigger a revoke",
                SensitiveRevocationResult.NotSensitive,
                result,
            )
        }
        assertEquals("no device may even be enumerated", 0, aiDevices.calls)
        assertTrue(outbox.events.isEmpty())
    }

    @Test
    fun `a sensitive category revokes only the AI devices that hold a grant`() {
        seedConversations("c-a")
        val coordinator = ConversationGrantCoordinator(
            store = ScopedStore(
                mapOf(
                    target.deviceId to store.state!!,
                    secondTarget.deviceId to ScopeState.initial(
                        ScopeSelection.Conversations(setOf("c-b")),
                        updatedAt = 100,
                    ),
                )
            ),
            keys = keys,
            outbox = outbox,
            signer = FakeSigner(),
            wrapper = FakeWrapper(),
            epoch = FakeEpoch(),
            aiDevices = aiDevices,
            now = { now },
        )
        aiDevices.targets = listOf(target, secondTarget)

        val result = runBlocking {
            coordinator.revokeForSensitiveReclassification("c-a", "OTP")
        } as SensitiveRevocationResult.Evaluated

        assertEquals(2, result.outcomes.size)
        val first = result.outcomes.single { it.deviceId == target.deviceId }
        assertTrue(first.result is ScopeRevokeResult.Revoked)
        val second = result.outcomes.single { it.deviceId == secondTarget.deviceId }
        assertEquals("a device without a grant for c-a is untouched", ScopeRevokeResult.NotGranted, second.result)

        assertEquals("only one tombstone, for the device that held the grant", 1, outbox.events.size)
        assertEquals(target.deviceId, outbox.events.single().second)
        assertEquals("superseded", outbox.json(0).getString("reason"))
        assertEquals("c-a", outbox.json(0).getString("conversationId"))
        assertFalse(outbox.json(0).has("wrappedKey"))
    }

    @Test
    fun `a sensitive reclassification uses the account tombstone for an account-wide device`() {
        aiDevices.targets = listOf(target)
        val coordinator = ConversationGrantCoordinator(
            store = ScopedStore(
                mapOf(target.deviceId to ScopeState.initial(ScopeSelection.AccountWide, updatedAt = 100))
            ),
            keys = keys,
            outbox = outbox,
            signer = FakeSigner(),
            wrapper = FakeWrapper(),
            epoch = FakeEpoch(),
            aiDevices = aiDevices,
            now = { now },
        )

        val result = runBlocking {
            coordinator.revokeForSensitiveReclassification("c-a", "BANK_SECURITY_CODE")
        } as SensitiveRevocationResult.Evaluated

        assertTrue(result.outcomes.single().result is ScopeRevokeResult.Revoked)
        assertEquals(1, outbox.events.size)
        assertEquals(
            "the whole account scope is withdrawn, fail closed",
            "gmweb-conversation-key-revoke",
            outbox.json(0).getString("kind"),
        )
        assertFalse("an account tombstone names no conversation", outbox.json(0).has("conversationId"))
    }

    @Test
    fun `one device's outbox failure does not hide another device's revoke`() {
        val coordinator = ConversationGrantCoordinator(
            store = ScopedStore(
                mapOf(
                    target.deviceId to ScopeState.initial(
                        ScopeSelection.Conversations(setOf("c-a")),
                        updatedAt = 100,
                    ),
                    secondTarget.deviceId to ScopeState.initial(
                        ScopeSelection.Conversations(setOf("c-a")),
                        updatedAt = 100,
                    ),
                )
            ),
            keys = keys,
            outbox = RecordingOutbox(failForDevice = target.deviceId),
            signer = FakeSigner(),
            wrapper = FakeWrapper(),
            epoch = FakeEpoch(),
            aiDevices = FakeAiDevices(listOf(target, secondTarget)),
            now = { now },
        )

        val result = runBlocking {
            coordinator.revokeForSensitiveReclassification("c-a", "READ_OTP")
        } as SensitiveRevocationResult.Evaluated

        val failed = result.outcomes.single { it.deviceId == target.deviceId }
        assertEquals(ScopeApplyStage.OUTBOX, (failed.result as ScopeRevokeResult.Failed).stage)
        val revoked = result.outcomes.single { it.deviceId == secondTarget.deviceId }
        assertTrue(revoked.result is ScopeRevokeResult.Revoked)
    }

    @Test
    fun `a device enumeration failure is reported as failed, not as a successful revoke`() {
        seedConversations("c-a")
        aiDevices.fail = true

        val result = runBlocking {
            coordinator().revokeForSensitiveReclassification("c-a", "OTP")
        }

        assertTrue(result is SensitiveRevocationResult.Failed)
        assertTrue(outbox.events.isEmpty())
    }

    @Test
    fun `reclassification keeps the conversation id out of the wire reason and key fields`() {
        seedConversations("c-a")
        aiDevices.targets = listOf(target)

        runBlocking { coordinator().revokeForSensitiveReclassification("c-a", "TRANSACTION") }

        val json = outbox.json(0)
        assertEquals("superseded", json.getString("reason"))
        assertFalse(json.has("wrappedKey"))
        assertFalse(json.has("epochId"))
        assertFalse(json.has("expiresAt"))
    }

    // ── Determinism ─────────────────────────────────────────────────────────

    @Test
    fun `pauseAll event ids are deterministic for the same decision`() {
        seedConversations("c-a", "c-b")
        pause()
        val first = outbox.events.map { it.first }

        val secondRun = RecordingOutbox()
        val secondStore = InMemoryScopeStore().apply { state = ScopeState.initial(ScopeSelection.Conversations(setOf("c-a", "c-b")), 100) }
        runBlocking {
            ConversationGrantCoordinator(
                store = secondStore,
                keys = keys,
                outbox = secondRun,
                signer = FakeSigner(),
                wrapper = FakeWrapper(),
                epoch = FakeEpoch(),
                aiDevices = aiDevices,
                now = { now },
            ).pauseAll(target)
        }

        assertEquals(first, secondRun.events.map { it.first })
    }

    @Test
    fun `the tombstone event id is the payload grant id and names the right device`() {
        seedConversations("c-a")

        pause()

        val (eventId, deviceId, bytes) = outbox.events.single()
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        assertEquals(json.getString("grantId"), eventId)
        assertEquals(target.deviceId, deviceId)
        assertEquals(target.deviceId, json.getString("deviceId"))
    }

    // ── Fakes ───────────────────────────────────────────────────────────────

    /** A store whose content depends on the device id. */
    private class ScopedStore(private val states: Map<String, ScopeState>) : ConversationScopeStore {
        override suspend fun load(deviceId: String): ScopeState? = states[deviceId]

        override suspend fun save(deviceId: String, state: ScopeState): Boolean = true
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

    private class RecordingOutbox(
        private val failOn: Int? = null,
        private val failForDevice: String? = null,
    ) : GrantOutbox {
        val events = mutableListOf<Triple<String, String, ByteArray>>()

        override suspend fun enqueue(eventId: String, deviceId: String, payloadJson: ByteArray) {
            if (failForDevice != null && deviceId == failForDevice) {
                throw IllegalStateException("outbox write failed")
            }
            if (failOn != null && events.size + 1 == failOn) {
                throw IllegalStateException("outbox write failed")
            }
            events += Triple(eventId, deviceId, payloadJson)
        }

        fun json(index: Int): JSONObject = JSONObject(String(events[index].third, Charsets.UTF_8))

        fun jsonValues(field: String): List<Long> =
            events.indices.map { json(it).getLong(field) }
    }

    private class FakeKeySource : ConversationKeySource {
        val requested = mutableListOf<String>()

        override suspend fun ckeFor(conversationId: String): ByteArray? {
            requested += conversationId
            return ByteArray(ConversationGrantSchema.CKE_BYTES) { (it + 1).toByte() }
        }
    }

    private class FakeEpoch : GrantEpochSource {
        override suspend fun epochId(): String = "epoch-1"
    }

    private class FakeSigner : GrantSigner {
        override fun sign(binding: ByteArray): ByteArray = ByteArray(64) { (it + 1).toByte() }
    }

    private class FakeWrapper : GrantKeyWrapper {
        override fun wrap(recipientPublicKey: ByteArray, key: ByteArray, info: ByteArray): ByteArray =
            ByteArray(96) { (it + 1).toByte() }
    }

    private class FakeAiDevices(
        var targets: List<GrantTarget> = emptyList(),
        var fail: Boolean = false,
    ) : AiAgentDeviceSource {
        var calls = 0

        override suspend fun aiAgentDevices(): List<GrantTarget> {
            calls++
            if (fail) throw IllegalStateException("trust registry unreadable")
            return targets
        }
    }

    private companion object {
        const val ONE_DAY = 24L * 60L * 60L * 1000L
    }
}
