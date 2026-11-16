package com.autonomousone.messages.security

import android.content.Context
import androidx.room.withTransaction
import com.autonomousone.messages.data.ConversationGrantOutbox
import com.autonomousone.messages.data.ConversationKeyEpochEntity
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.data.toEntities

/**
 * LDV-004 PR-E (slice 2b) — the production adapters behind
 * [ConversationGrantCoordinator]'s ports.
 *
 * The coordinator is deliberately Android-free; every effect it performs is one of the
 * interfaces in `ConversationGrantCoordinator.kt`. This file is the only place that knows how
 * those ports are realised on a real phone:
 *
 * | Port | Realisation |
 * |---|---|
 * | [ConversationScopeStore] | slice 2a's `LinkedDeviceScopeDao` (Room, v30 tables) |
 * | [GrantOutbox] | `gateway_event_outbox` row of type `KEY_GRANT`, the queue `EventUploader` drains |
 * | [ConversationKeySource] | the existing CKE keyring (`conversation_key_epochs`), unwrapped through [ConversationKeyVault] |
 * | [GrantEpochSource] | the same account `READ_MESSAGES` epoch, so the wrapped key and the `epochId` always agree |
 * | [GrantSigner] | [PrimaryTrustRoot] (non-exportable Android-Keystore ECDSA-P256/SHA-256) |
 * | [GrantKeyWrapper] | [MessageCrypto.wrapForDevice] (Tink RFC 9180 HPKE, the same primitive the browser grants use) |
 *
 * There is no gap in the crypto wiring: both the signer and the wrapper are complete, because
 * the two primitives already existed for `KEYRING_ENTRY`/`HISTORY_KEY_GRANT`. What PR-E cannot
 * provide yet is a DISTINCT conversation key — see [AccountReadMessagesKeySource].
 *
 * Nothing in this file logs a key, a payload or a signature.
 */

/**
 * The durable scope, read and written through slice 2a's transactional DAO.
 *
 * [save] writes the whole snapshot (state row plus every conversation row) in ONE Room
 * transaction, which is what makes "persist only after every enqueue succeeded" atomic on the
 * storage side: a crash cannot leave a state row that claims a scope its conversation rows do
 * not describe.
 *
 * The raw `putState`/`putConversation` inserts are used deliberately instead of the DAO's
 * guarded `upsertState`/`setConversation` helpers: a decision batch legitimately contains rows
 * whose `scopeSequence` is older than the state row's (a tombstone recorded at the sequence
 * that revoked it, carried forward by later decisions), and the per-conversation guard exists
 * for a *single* incremental write, not for replacing a whole snapshot. The monotonic rule is
 * enforced here instead: a write whose sequence is below the persisted one is stale and is
 * refused.
 */
class RoomConversationScopeStore(private val db: MessagesDatabase) : ConversationScopeStore {

    override suspend fun load(deviceId: String): ScopeState? =
        db.linkedDeviceScopeDao().snapshot(deviceId).toScopeState()

    override suspend fun save(deviceId: String, state: ScopeState): Boolean {
        require(deviceId.isNotBlank()) { "deviceId must not be blank" }
        val entities = state.toEntities(deviceId)
        return db.withTransaction {
            val persisted = db.linkedDeviceScopeDao().maxSequence(deviceId)
            if (persisted != null && state.scopeSequence < persisted) {
                return@withTransaction false
            }
            db.linkedDeviceScopeDao().putState(entities.state)
            for (row in entities.conversations) {
                db.linkedDeviceScopeDao().putConversation(row)
            }
            true
        }
    }
}

/**
 * Enqueues one `KEY_GRANT` into the durable gateway outbox — the SAME queue
 * [com.autonomousone.messages.gateway.EventUploader] claims, uploads and ACKs, no new queue.
 *
 * `INSERT OR IGNORE` on the unique `eventUuid`: the coordinator's event id is the payload's
 * deterministic `grantId`, so re-running a decision is a no-op instead of a duplicate.
 */
class GatewayGrantOutbox(
    private val db: MessagesDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) : GrantOutbox {
    override suspend fun enqueue(eventId: String, deviceId: String, payloadJson: ByteArray) {
        db.gatewayEventOutboxDao().insertOrIgnore(
            ConversationGrantOutbox.create(
                eventId = eventId,
                deviceId = deviceId,
                payloadJson = payloadJson,
                createdAt = now(),
            )
        )
    }
}

/**
 * The account `READ_MESSAGES` CKE, from the real keyring.
 *
 * ## The known PR-E limitation, stated plainly
 *
 * Messages produces **one `READ_MESSAGES` key per account epoch**, not one per conversation
 * (design §2.1, §5.1): the v2/v3 envelope wraps every message's DEK with that account key, so
 * a per-conversation key does not exist to hand out. This adapter therefore returns the
 * account key for EVERY conversation id, exactly as the design's "honest limitation" says a
 * grant for now can only do. The conversation binding in the HPKE `info` + `rootSignature` is
 * what makes the grant *authorisation* per-conversation (god-ai refuses to decrypt a
 * conversation it was not granted), but it is **not cryptographic isolation**: a device that
 * receives one conversation's grant receives the account key and could mathematically open
 * another conversation's ciphertext if it obtained it. Closing that needs envelope v4
 * (per-conversation ciphertext keys), which is explicitly out of scope for PR-E.
 *
 * TODO(LDV-004 / PR-E slice 2c+): when per-conversation CKEs exist, look the row up by
 * `(conversationId, generation, historyFloor, category)` — the table already has that shape
 * (`ConversationKeyEpochEntity`) — and return it instead of the account row. Until then the
 * account row is the only key, and returning `null` (rather than the account key) would break
 * every grant the design has approved.
 *
 * [epochId] and [ckeFor] resolve the SAME row through [currentEpoch], so a grant's
 * `wrappedKey` and its `epochId` can never disagree.
 */
class AccountReadMessagesKeySource(
    private val db: MessagesDatabase,
) : ConversationKeySource, GrantEpochSource {

    /** Generation-keyed cache of the account epoch row, so one decision sees one epoch. */
    @Volatile
    private var cachedGeneration: Int = Int.MIN_VALUE

    @Volatile
    private var cachedEpoch: ConversationKeyEpochEntity? = null

    override suspend fun epochId(): String =
        currentEpoch()?.epochId
            ?: throw IllegalStateException("READ_MESSAGES account epoch is not available yet")

    override suspend fun ckeFor(conversationId: String): ByteArray? {
        // Deliberately ignores `conversationId` until per-conversation CKEs exist: see the
        // class KDoc. Returning null would fail every grant closed for no security gain.
        val epoch = currentEpoch() ?: return null
        return try {
            ConversationKeyVault.unwrap(epoch.epochId, epoch.wrappedKey)
        } catch (e: Exception) {
            // Locked/unavailable Keystore key: the coordinator reports a KEY failure and the
            // persisted scope stays unchanged. Never fall back to an unencrypted copy.
            null
        }
    }

    /**
     * The account epoch for the current trust generation — the same row
     * `ConversationKeyRepository` publishes as this generation's `KEYRING_ENTRY`.
     *
     * A missing row is not created here: creating a CKE is the keyring producer's job
     * (`ConversationKeyRepository.drainHistoryGrants` runs from `EventUploader`), and a scope
     * screen must not silently mint key material. `null` becomes a typed `EPOCH` failure.
     */
    private suspend fun currentEpoch(): ConversationKeyEpochEntity? {
        val generation = db.trustStatementOutboxDao().maxTrustSequence()
        val cached = cachedEpoch
        if (cached != null && generation == cachedGeneration) return cached
        val epoch = db.conversationKeyDao().current(
            ConversationKeyRepository.ACCOUNT_KEYRING_AGGREGATE,
            generation,
            0L,
            ConversationKeyRepository.MESSAGE_DOMAIN,
        )
        cachedEpoch = epoch
        cachedGeneration = generation
        return epoch
    }
}

/**
 * [GrantSigner] over the phone's non-exportable root key (design §4.3).
 *
 * [PrimaryTrustRoot.signBytes] returns standard base64 of the DER signature; the coordinator
 * needs the raw DER bytes, so it is decoded once with [MessageCrypto.unb64] (the same standard
 * base64 alphabet Android's encoder uses). The key never leaves the Keystore — only the
 * signature does.
 */
object PrimaryTrustRootGrantSigner : GrantSigner {
    override fun sign(binding: ByteArray): ByteArray =
        MessageCrypto.unb64(PrimaryTrustRoot.signBytes(binding))
}

/**
 * [GrantKeyWrapper] over the same RFC 9180 HPKE primitive that wraps the browser grants
 * (`MessageCrypto.wrapForDevice`, DHKEM P-256 / HKDF-SHA256 / AES-256-GCM).
 *
 * The recipient key is the device's raw 65-byte uncompressed P-256 point, already validated
 * by the coordinator's [ConversationGrantPublisher] call path.
 */
object TinkHpkeGrantKeyWrapper : GrantKeyWrapper {
    override fun wrap(recipientPublicKey: ByteArray, key: ByteArray, info: ByteArray): ByteArray =
        MessageCrypto.wrapForDevice(recipientPublicKey, key, info)
}

/**
 * [AiAgentDeviceSource] over the authoritative local trust registry.
 *
 * Only `AI_AGENT` rows are returned: a `WEB_PWA` device can never hold a conversation grant
 * (the capability contract forbids it), so tombstoning one would be an unrequested deny.
 *
 * The `encryptionPublicKey` is decoded best-effort and may be empty. That is deliberate: a
 * revoke is **key-less** (design §4.2), so a device whose key row is missing, rotated or
 * malformed must still be reachable by its `deviceId` — otherwise exactly the revocation that
 * matters most would be skipped. A target built from this source can only ever be used for
 * grants through the publisher's validated overload, which refuses an unusable key.
 */
class TrustedAiAgentDeviceSource(
    private val db: MessagesDatabase,
) : AiAgentDeviceSource {
    override suspend fun aiAgentDevices(): List<GrantTarget> =
        db.trustedDeviceDao().byType(PairingCapabilityContract.DEVICE_TYPE_AI_AGENT)
            .map { row ->
                GrantTarget(
                    deviceId = row.deviceId,
                    encryptionPublicKey = decodeP256(row.encryptionPublicKey),
                )
            }

    private fun decodeP256(encoded: String): ByteArray {
        val raw = runCatching { MessageCrypto.unb64(encoded) }.getOrNull() ?: return ByteArray(0)
        val isP256 = raw.size == ConversationGrantSchema.P256_PUBLIC_KEY_BYTES &&
            raw[0] == ConversationGrantSchema.P256_UNCOMPRESSED_PREFIX
        return if (isP256) raw else ByteArray(0)
    }
}

/**
 * Production wiring: one coordinator over the app database and the Android Keystore.
 *
 * The key source is constructed ONCE and passed as both the key and the epoch port, so the
 * wrapped key and the `epochId` of one decision are resolved from the same cached row.
 */
object ConversationGrantWiring {
    fun create(context: Context): ConversationGrantCoordinator {
        val db = MessagesDatabase.get(context.applicationContext)
        val keySource = AccountReadMessagesKeySource(db)
        return ConversationGrantCoordinator(
            store = RoomConversationScopeStore(db),
            keys = keySource,
            outbox = GatewayGrantOutbox(db),
            signer = PrimaryTrustRootGrantSigner,
            wrapper = TinkHpkeGrantKeyWrapper,
            epoch = keySource,
            aiDevices = TrustedAiAgentDeviceSource(db),
        )
    }
}
