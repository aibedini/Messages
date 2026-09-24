package com.autonomousone.messages.security

import androidx.room.withTransaction
import com.autonomousone.messages.data.ConversationKeyEpochEntity
import com.autonomousone.messages.sync.EventKeyRef
import com.autonomousone.messages.sync.TrustedDevicePolicy
import com.autonomousone.messages.data.GatewayEventFactory
import com.autonomousone.messages.data.GatewayEventOutboxEntity
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.data.TrustedDeviceEntity
import org.json.JSONObject
import java.util.UUID

/**
 * Encrypts cloud payloads and publishes browser-readable key material.
 *
 * v3 gives FULL_HISTORY browsers one origin-bound History Master Key. Normal
 * messages also keep one rotating live wrap for FROM_NOW_ON browsers.
 * Sensitive capability domains remain isolated in the small v2 keyring.
 */
class ConversationKeyRepository(private val db: MessagesDatabase) {
    companion object {
        const val ACCOUNT_KEYRING_AGGREGATE = "__account_keyring__"
        const val HISTORY_MASTER_AGGREGATE = "__history_master__"
        const val KEYRING_ENTRY = "KEYRING_ENTRY"
        const val HISTORY_KEY_GRANT = "HISTORY_KEY_GRANT"
        const val MESSAGE_DOMAIN = "READ_MESSAGES"

        val KEYRING_DOMAINS = setOf(
            MESSAGE_DOMAIN,
            "CONTACTS_READ",
            "READ_OTP",
            "READ_BANK_SECURITY",
            "READ_PASSWORD_RESET",
            "READ_AUTH_CODES",
            "READ_FINANCIAL_NOTIFICATIONS"
        )

        /**
         * The device's declared capabilities, failing closed.
         *
         * Delegates to [TrustedDevicePolicy] rather than parsing the JSON here. This used to be a local
         * copy with no guard around `JSONArray(...)`, so a single malformed `capabilitiesJson` threw
         * `JSONException` **out of `encrypt`** — aborting the encryption of a message that then could not
         * be replicated at all, because one linked device's row was unparseable. The shared version
         * returns the empty set, which fails closed and lets every other device be served.
         */
        private fun capabilities(device: TrustedDeviceEntity): Set<String> =
            TrustedDevicePolicy.capabilities(device)

        /**
         * Delegates to the one trust predicate (mission §73).
         *
         * This was the second copy of the same expression; see [TrustedDevicePolicy] for why the pair
         * existed and what made unifying them safe.
         */
        private fun trusted(device: TrustedDeviceEntity, now: Long): Boolean =
            TrustedDevicePolicy.isTrusted(device, now)

        fun domainFor(category: String): String = category.ifBlank { MESSAGE_DOMAIN }

        private fun eligibleForAccountKey(
            device: TrustedDeviceEntity,
            epoch: ConversationKeyEpochEntity,
            now: Long
        ): Boolean {
            if (!trusted(device, now)) return false
            val caps = capabilities(device)
            if (MESSAGE_DOMAIN !in caps || epoch.category !in caps) return false
            if (epoch.category == MESSAGE_DOMAIN &&
                device.historyGrant == TrustedDevicePolicy.GRANT_FULL_HISTORY
            ) {
                return false
            }
            return device.historyGrant == TrustedDevicePolicy.GRANT_FULL_HISTORY ||
                epoch.createdAt >= device.approvedAt
        }

        /** Delegates, so the history-eligibility rule has exactly one definition. */
        private fun eligibleForHistoryKey(device: TrustedDeviceEntity, now: Long): Boolean =
            TrustedDevicePolicy.isEligibleForHistory(device, now)
    }

    suspend fun encrypt(
        row: GatewayEventOutboxEntity,
        category: String
    ): GatewayEventOutboxEntity {
        val now = System.currentTimeMillis()
        val devices = db.trustedDeviceDao().all()
        val generation = db.trustStatementOutboxDao().maxTrustSequence()
        val domain = domainFor(category)
        val epoch = accountEpoch(generation, domain, now)
        val accountKey = ConversationKeyVault.unwrap(epoch.epochId, epoch.wrappedKey)
        try {
            for (device in devices) {
                if (eligibleForAccountKey(device, epoch, now)) publishKeyringEntry(epoch, device, accountKey)
            }
            val payload = GatewayEventFactory.decodePayloadEnvelope(row.ciphertext)
                .toByteArray(Charsets.UTF_8)
            if (domain == MESSAGE_DOMAIN) {
                val history = historyMaster(now)
                val historyKey = ConversationKeyVault.unwrap(history.epochId, history.wrappedKey)
                try {
                    for (device in devices) {
                        if (eligibleForHistoryKey(device, now)) publishHistoryGrant(history, device, historyKey)
                    }
                    return encrypted(
                        row,
                        encoding = "envelope.v3",
                        cryptoVersion = 3,
                        ciphertext = MessageCrypto.encryptMessageV3(
                            historyKey,
                            history.epochId,
                            accountKey,
                            epoch.epochId,
                            row.eventUuid,
                            row.eventType,
                            row.aggregateId,
                            payload
                        ),
                        liveKeyId = epoch.epochId,
                        historyKeyId = history.epochId
                    )
                } finally {
                    historyKey.fill(0)
                }
            }
            return encrypted(
                row,
                encoding = "envelope.v2",
                cryptoVersion = 2,
                ciphertext = MessageCrypto.encryptMessageV2(
                    accountKey,
                    epoch.epochId,
                    domain,
                    row.eventUuid,
                    row.eventType,
                    row.aggregateId,
                    payload
                ),
                liveKeyId = epoch.epochId,
                historyKeyId = null
            )
        } finally {
            accountKey.fill(0)
        }
    }

    /**
     * Attach the encrypted bytes AND the key reference that describes them (mission §42).
     *
     * One helper for every encrypted row, so the key reference is recorded by the code that KNOWS the
     * key ids rather than by a caller that would have to re-parse the envelope — and so a future
     * encryption path cannot produce a row whose `keyRef` silently stays null.
     *
     * `keyRef` is derived here from the same `liveKeyId`/`historyKeyId` arguments that were handed to
     * `MessageCrypto`, which means the column and the envelope agree by construction. A test asserts
     * the stronger version of that: the stored value equals what parsing the ciphertext back yields.
     */
    private fun encrypted(
        row: GatewayEventOutboxEntity,
        encoding: String,
        cryptoVersion: Int,
        ciphertext: ByteArray,
        liveKeyId: String,
        historyKeyId: String?
    ): GatewayEventOutboxEntity = row.copy(
        encoding = encoding,
        cryptoVersion = cryptoVersion,
        ciphertext = ciphertext,
        keyRef = EventKeyRef(liveKeyId = liveKeyId, historyKeyId = historyKeyId).encode()
    )

    suspend fun hasAuthorizedHistoryReader(domain: String): Boolean {
        if (domain !in KEYRING_DOMAINS || domain == MESSAGE_DOMAIN) return false
        val now = System.currentTimeMillis()
        return db.trustedDeviceDao().all().any { device ->
            // The shared history rule, plus this domain's own capability. Written out, this was a THIRD
            // copy of the trust expression — with its own `"FULL_HISTORY"` literal — and a guard found it
            // only because it looks for the re-typed grant name rather than for one spelling of the code.
            TrustedDevicePolicy.isEligibleForHistory(device, now) && domain in capabilities(device)
        }
    }

    private suspend fun historyMaster(now: Long): ConversationKeyEpochEntity {
        db.conversationKeyDao().current(
            HISTORY_MASTER_AGGREGATE, 0, 0L, MESSAGE_DOMAIN
        )?.let { return it }
        val keyId = UUID.randomUUID().toString()
        val raw = MessageCrypto.randomKey()
        try {
            db.conversationKeyDao().insert(
                ConversationKeyEpochEntity(
                    epochId = keyId,
                    conversationId = HISTORY_MASTER_AGGREGATE,
                    generation = 0,
                    historyFloor = 0L,
                    category = MESSAGE_DOMAIN,
                    wrappedKey = ConversationKeyVault.wrap(keyId, raw),
                    createdAt = now
                )
            )
            return db.conversationKeyDao().current(
                HISTORY_MASTER_AGGREGATE, 0, 0L, MESSAGE_DOMAIN
            )!!
        } finally {
            raw.fill(0)
        }
    }

    private suspend fun accountEpoch(
        generation: Int,
        domain: String,
        now: Long
    ): ConversationKeyEpochEntity {
        require(domain in KEYRING_DOMAINS) { "Unsupported keyring domain" }
        db.conversationKeyDao().current(ACCOUNT_KEYRING_AGGREGATE, generation, 0L, domain)?.let {
            return it
        }
        val keyId = UUID.randomUUID().toString()
        val raw = MessageCrypto.randomKey()
        try {
            val epoch = ConversationKeyEpochEntity(
                epochId = keyId,
                conversationId = ACCOUNT_KEYRING_AGGREGATE,
                generation = generation,
                historyFloor = 0L,
                category = domain,
                wrappedKey = ConversationKeyVault.wrap(keyId, raw),
                createdAt = now
            )
            db.conversationKeyDao().insert(epoch)
            return db.conversationKeyDao()
                .current(ACCOUNT_KEYRING_AGGREGATE, generation, 0L, domain)!!
        } finally {
            raw.fill(0)
        }
    }

    private suspend fun publishKeyringEntry(
        epoch: ConversationKeyEpochEntity,
        device: TrustedDeviceEntity,
        raw: ByteArray
    ) {
        val eventId = UUID.nameUUIDFromBytes(
            "keyring:${epoch.epochId}:${device.deviceId}:${device.encryptionPublicKey}"
                .toByteArray(Charsets.UTF_8)
        ).toString()
        if (db.gatewayEventOutboxDao().idOf(eventId) != null) return
        val fields = arrayOf(
            epoch.epochId,
            epoch.category,
            device.deviceId,
            epoch.generation.toString()
        )
        val wrapped = MessageCrypto.b64(
            MessageCrypto.wrapForDevice(
                MessageCrypto.unb64(device.encryptionPublicKey),
                raw,
                MessageCrypto.binding("GMweb-account-key-v2", *fields)
            )
        )
        val payload = JSONObject()
            .put("v", 2)
            .put("kind", "keyring-entry")
            .put("keyId", epoch.epochId)
            .put("domain", epoch.category)
            .put("deviceId", device.deviceId)
            .put("generation", epoch.generation)
            .put("wrappedKey", wrapped)
            .put(
                "rootSignature",
                PrimaryTrustRoot.signBytes(
                    MessageCrypto.binding("GMweb-account-key-signature-v2", *fields, wrapped)
                )
            )
        db.gatewayEventOutboxDao().insertOrIgnore(
            GatewayEventOutboxEntity(
                eventUuid = eventId,
                eventType = KEYRING_ENTRY,
                aggregateId = ACCOUNT_KEYRING_AGGREGATE,
                ciphertext = payload.toString().toByteArray(Charsets.UTF_8),
                encoding = "envelope.v2",
                schemaVersion = 1,
                cryptoVersion = 2,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    private suspend fun publishHistoryGrant(
        epoch: ConversationKeyEpochEntity,
        device: TrustedDeviceEntity,
        raw: ByteArray
    ) {
        val certificate = JSONObject(device.certificateJson)
        val origin = certificate.getString("webOrigin")
        val transcriptHash = certificate.getString("pairingTranscriptHash")
        val fields = arrayOf(
            epoch.epochId,
            device.deviceId,
            origin,
            device.trustSequence.toString(),
            transcriptHash,
            device.encryptionPublicKey
        )
        val eventId = UUID.nameUUIDFromBytes(
            (listOf("history-key-v3") + fields).joinToString(":").toByteArray(Charsets.UTF_8)
        ).toString()
        if (db.gatewayEventOutboxDao().idOf(eventId) != null) return
        val wrapped = MessageCrypto.b64(
            MessageCrypto.wrapForDevice(
                MessageCrypto.unb64(device.encryptionPublicKey),
                raw,
                MessageCrypto.binding("GMweb-history-key-v3", *fields)
            )
        )
        val payload = JSONObject()
            .put("v", 3)
            .put("kind", "history-key-grant")
            .put("keyId", epoch.epochId)
            .put("deviceId", device.deviceId)
            .put("origin", origin)
            .put("trustSequence", device.trustSequence)
            .put("pairingTranscriptHash", transcriptHash)
            .put("encryptionPublicKey", device.encryptionPublicKey)
            .put("wrappedKey", wrapped)
            .put(
                "rootSignature",
                PrimaryTrustRoot.signBytes(
                    MessageCrypto.binding("GMweb-history-key-signature-v3", *fields, wrapped)
                )
            )
        db.gatewayEventOutboxDao().insertOrIgnore(
            GatewayEventOutboxEntity(
                eventUuid = eventId,
                eventType = HISTORY_KEY_GRANT,
                aggregateId = HISTORY_MASTER_AGGREGATE,
                ciphertext = payload.toString().toByteArray(Charsets.UTF_8),
                encoding = "envelope.v3",
                schemaVersion = 1,
                cryptoVersion = 3,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    /**
     * Publishes the one v3 history grant plus any capability-domain keys.
     */
    suspend fun drainHistoryGrants() {
        val now = System.currentTimeMillis()
        for (candidate in db.trustedDeviceDao().all()) {
            db.withTransaction {
                val device = db.trustedDeviceDao().byId(candidate.deviceId) ?: return@withTransaction
                if (!trusted(device, now)) return@withTransaction

                val generation = db.trustStatementOutboxDao().maxTrustSequence()
                capabilities(device).filterTo(mutableSetOf()) {
                    it in KEYRING_DOMAINS &&
                        !(it == MESSAGE_DOMAIN &&
                            device.historyGrant == TrustedDevicePolicy.GRANT_FULL_HISTORY)
                }
                    .forEach { accountEpoch(generation, it, now) }
                if (eligibleForHistoryKey(device, now)) {
                    val history = historyMaster(now)
                    val raw = ConversationKeyVault.unwrap(history.epochId, history.wrappedKey)
                    try {
                        publishHistoryGrant(history, device, raw)
                    } finally {
                        raw.fill(0)
                    }
                }
                for (epoch in db.conversationKeyDao().accountKeyring()) {
                    if (!eligibleForAccountKey(device, epoch, now)) continue
                    val raw = ConversationKeyVault.unwrap(epoch.epochId, epoch.wrappedKey)
                    try {
                        publishKeyringEntry(epoch, device, raw)
                    } finally {
                        raw.fill(0)
                    }
                }

            }
        }
    }
}
