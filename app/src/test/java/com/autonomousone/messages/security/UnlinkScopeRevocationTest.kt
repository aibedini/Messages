package com.autonomousone.messages.security

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LDV-004 PR-E slice 2c — [UnlinkScopeRevocation], the primary-unlink hook.
 *
 * Plain JVM test. The AI-device list, the per-device pause and the warning sink are injected,
 * so "unlink pauses every AI device and survives a failure" is proven without Android, Room or
 * a device. The wiring case uses the REAL [ConversationGrantCoordinator] over a fake scope
 * store and a fake outbox, which is the same stack the production call site uses.
 */
class UnlinkScopeRevocationTest {

    private val aiDeviceId = "ai-1"
    private val secondAiDeviceId = "ai-2"
    private val publicKey = ByteArray(ConversationGrantSchema.P256_PUBLIC_KEY_BYTES) { index ->
        if (index == 0) ConversationGrantSchema.P256_UNCOMPRESSED_PREFIX else index.toByte()
    }

    private fun target(deviceId: String) = GrantTarget(deviceId, publicKey)

    // ── Wiring with the real coordinator ────────────────────────────────────

    @Test
    fun `unlink pauses every linked AI device through the real coordinator`() {
        val store = DeviceScopeStore(
            mapOf(
                aiDeviceId to granted("c-a", "c-b"),
                secondAiDeviceId to granted("c-c"),
            )
        )
        val outbox = DeviceOutbox()

        val outcome = runBlocking {
            UnlinkScopeRevocation(
                aiDevices = { listOf(target(aiDeviceId), target(secondAiDeviceId)) },
                pauseAll = { device, reason -> coordinator(store, outbox).pauseAll(device, reason) },
            ).run()
        }

        assertEquals(listOf(aiDeviceId, secondAiDeviceId), outcome.paused)
        assertTrue(outcome.allWithdrawn)
        assertEquals(3, outbox.events.size)
        assertEquals(
            "each tombstone is addressed to its own device",
            setOf(aiDeviceId, secondAiDeviceId),
            outbox.events.map { it.second }.toSet(),
        )
        assertTrue(outbox.events.all { JSONObject(String(it.third, Charsets.UTF_8)).getString("reason") == "unlinked" })
        assertTrue("all tombstones of one device share the decision's sequence",
            outbox.events.filter { it.second == aiDeviceId }.map {
                JSONObject(String(it.third, Charsets.UTF_8)).getLong("scopeSequence")
            }.toSet().size == 1)
        assertTrue(outbox.events.none { JSONObject(String(it.third, Charsets.UTF_8)).has("wrappedKey") })
    }

    @Test
    fun `unlink completes when one device's outbox write fails`() {
        val store = DeviceScopeStore(
            mapOf(
                aiDeviceId to granted("c-a"),
                secondAiDeviceId to granted("c-b"),
            )
        )
        val outbox = DeviceOutbox(failForDevice = aiDeviceId)
        val warnings = mutableListOf<String>()

        val outcome = runBlocking {
            UnlinkScopeRevocation(
                aiDevices = { listOf(target(aiDeviceId), target(secondAiDeviceId)) },
                pauseAll = { device, reason -> coordinator(store, outbox).pauseAll(device, reason) },
                onWarning = { message, _ -> warnings += message },
            ).run()
        }

        assertEquals(listOf(aiDeviceId), outcome.failed)
        assertEquals("the second device is still withdrawn", listOf(secondAiDeviceId), outcome.paused)
        assertFalse(outcome.allWithdrawn)
        assertEquals(1, warnings.size)
        assertTrue("the warning names the device and the stage", warnings.single().contains(aiDeviceId))
        assertTrue(warnings.single().contains(ScopeApplyStage.OUTBOX.name))
        assertEquals(
            "the failed device keeps its granted scope, so a retry can still withdraw it",
            ScopeSelection.Conversations(setOf("c-a")),
            store.state(aiDeviceId)!!.selection,
        )
    }

    // ── Failure isolation ───────────────────────────────────────────────────

    @Test
    fun `a throwing pause never aborts the remaining devices`() {
        val calls = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val outcome = runBlocking {
            UnlinkScopeRevocation(
                aiDevices = { listOf(target("ai-1"), target("ai-2"), target("ai-3")) },
                pauseAll = { device, _ ->
                    calls += device.deviceId
                    if (device.deviceId == "ai-1") throw IllegalStateException("keystore gone")
                    ScopeRevokeResult.Revoked(
                        state = ScopeState(2, revoked = true, updatedAt = 0),
                        published = emptyList(),
                    )
                },
                onWarning = { message, _ -> warnings += message },
            ).run()
        }

        assertEquals(listOf("ai-1", "ai-2", "ai-3"), calls)
        assertEquals(listOf("ai-1"), outcome.failed)
        assertEquals(listOf("ai-2", "ai-3"), outcome.paused)
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("ai-1"))
    }

    @Test
    fun `a Failed result is recorded honestly and the loop continues`() {
        val warnings = mutableListOf<String>()
        val outcome = runBlocking {
            UnlinkScopeRevocation(
                aiDevices = { listOf(target("ai-1"), target("ai-2")) },
                pauseAll = { device, _ ->
                    if (device.deviceId == "ai-1") {
                        ScopeRevokeResult.Failed(ScopeApplyStage.OUTBOX)
                    } else {
                        ScopeRevokeResult.AlreadyRevoked
                    }
                },
                onWarning = { message, _ -> warnings += message },
            ).run()
        }

        assertEquals(listOf("ai-1"), outcome.failed)
        assertEquals(listOf("ai-2"), outcome.alreadyRevoked)
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains(ScopeApplyStage.OUTBOX.name))
    }

    @Test
    fun `already revoked and never granted devices are not reported as withdrawn`() {
        val outcome = runBlocking {
            UnlinkScopeRevocation(
                aiDevices = { listOf(target("ai-1"), target("ai-2")) },
                pauseAll = { device, _ ->
                    if (device.deviceId == "ai-1") {
                        ScopeRevokeResult.NotGranted
                    } else {
                        ScopeRevokeResult.AlreadyRevoked
                    }
                },
            ).run()
        }

        assertEquals(emptyList<String>(), outcome.paused)
        assertEquals(listOf("ai-1"), outcome.notGranted)
        assertEquals(listOf("ai-2"), outcome.alreadyRevoked)
        assertTrue("nothing failed, so the scope is known withdrawn", outcome.allWithdrawn)
        assertEquals(2, outcome.attempted)
    }

    @Test
    fun `an unreadable trust registry is reported and never throws`() {
        val warnings = mutableListOf<String>()
        val outcome = runBlocking {
            UnlinkScopeRevocation(
                aiDevices = { throw IllegalStateException("database gone") },
                pauseAll = { _, _ -> error("must not be called") },
                onWarning = { message, _ -> warnings += message },
            ).run()
        }

        assertTrue(outcome.enumerationFailed)
        assertEquals(0, outcome.attempted)
        assertFalse(outcome.allWithdrawn)
        assertEquals(1, warnings.size)
    }

    @Test
    fun `the default reason is unlinked`() {
        val reasons = mutableListOf<GrantRevokeReason>()
        runBlocking {
            UnlinkScopeRevocation(
                aiDevices = { listOf(target("ai-1")) },
                pauseAll = { _, reason ->
                    reasons += reason
                    ScopeRevokeResult.NotGranted
                },
            ).run()
        }

        assertEquals(listOf(GrantRevokeReason.UNLINKED), reasons)
    }

    @Test
    fun `duplicate device rows are paused once`() {
        val calls = mutableListOf<String>()
        runBlocking {
            UnlinkScopeRevocation(
                aiDevices = { listOf(target("ai-1"), target("ai-1")) },
                pauseAll = { device, _ ->
                    calls += device.deviceId
                    ScopeRevokeResult.NotGranted
                },
            ).run()
        }

        assertEquals(listOf("ai-1"), calls)
    }

    // ── Fakes ───────────────────────────────────────────────────────────────

    private fun granted(vararg conversations: String): ScopeState =
        ScopeState.initial(ScopeSelection.Conversations(conversations.toSet()), updatedAt = 100)

    private fun coordinator(
        store: ConversationScopeStore,
        outbox: GrantOutbox,
    ): ConversationGrantCoordinator = ConversationGrantCoordinator(
        store = store,
        keys = NoKeysAllowed,
        outbox = outbox,
        signer = NoSigner,
        wrapper = NoWrapper,
        epoch = NoEpoch,
        aiDevices = AiAgentDeviceSource { emptyList() },
        now = { 1_700_000_000_000L },
    )

    /** A revoke must never ask for a key; failing here makes that a test failure. */
    private object NoKeysAllowed : ConversationKeySource {
        override suspend fun ckeFor(conversationId: String): ByteArray? =
            error("a revoke must not request key material")
    }

    private object NoEpoch : GrantEpochSource {
        override suspend fun epochId(): String = error("a revoke must not request an epoch")
    }

    private object NoSigner : GrantSigner {
        override fun sign(binding: ByteArray): ByteArray = ByteArray(64) { (it + 1).toByte() }
    }

    private object NoWrapper : GrantKeyWrapper {
        override fun wrap(recipientPublicKey: ByteArray, key: ByteArray, info: ByteArray): ByteArray =
            error("a revoke must not wrap a key")
    }

    private class DeviceScopeStore(
        private val states: Map<String, ScopeState>,
    ) : ConversationScopeStore {
        private val mutable = states.toMutableMap()

        fun state(deviceId: String): ScopeState? = mutable[deviceId]

        override suspend fun load(deviceId: String): ScopeState? = mutable[deviceId]

        override suspend fun save(deviceId: String, state: ScopeState): Boolean {
            mutable[deviceId] = state
            return true
        }
    }

    private class DeviceOutbox(private val failForDevice: String? = null) : GrantOutbox {
        val events = mutableListOf<Triple<String, String, ByteArray>>()

        override suspend fun enqueue(eventId: String, deviceId: String, payloadJson: ByteArray) {
            if (deviceId == failForDevice) throw IllegalStateException("outbox write failed")
            events += Triple(eventId, deviceId, payloadJson)
        }
    }
}
