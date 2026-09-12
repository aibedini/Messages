package com.autonomousone.messages.security

import androidx.room.withTransaction
import com.autonomousone.messages.data.ConversationKeyEpochEntity
import com.autonomousone.messages.data.GatewayEventFactory
import com.autonomousone.messages.data.GatewayEventOutboxEntity
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.data.TrustedDeviceEntity
import org.json.JSONArray
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

        private fun capabilities(device: TrustedDeviceEntity): Set<String> {
            val values = JSONArray(device.capabilitiesJson)
            return (0 until values.length()).mapTo(mutableSetOf()) { values.getString(it) }
        }

        private fun trusted(device: TrustedDeviceEntity, now: Long): Boolean =
            device.status in setOf(
                TrustedDeviceEntity.STATUS_ACTIVE,
                TrustedDeviceEntity.STATUS_PENDING_PUBLICATION
            ) && device.certificateJson.isNotBlank() && device.expiresAt > now && device.revokedAt == null

        fun domainFor(category: String): String = category.ifBlank { MESSAGE_DOMAIN }

        private fun eligibleForAccountKey(
            device: TrustedDeviceEntity,
            epoch: ConversationKeyEpochEntity,
            now: Long
        ): Boolean {
            if (!trusted(device, now)) return false
            val caps = capabilities(device)
            if (MESSAGE_DOMAIN !in caps || epoch.category !in caps) return false
            if (epoch.category == MESSAGE_DOMAIN && device.historyGrant == "FULL_HISTORY") return false
            return device.historyGrant == "FULL_HISTORY" || epoch.createdAt >= device.approvedAt
        }

        private fun eligibleForHistoryKey(device: TrustedDeviceEntity, now: Long): Boolean =
            trusted(device, now) && device.historyGrant == "FULL_HISTORY" &&
                MESSAGE_DOMAIN in capabilities(device)
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
                    return row.copy(
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
                        )
                    )
                } finally {
                    historyKey.fill(0)
                }
            }
            return row.copy(
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
                )
            )
        } finally {
            accountKey.fill(0)
        }
    }

    suspend fun hasAuthorizedHistoryReader(domain: String): Boolean {
        if (domain !in KEYRING_DOMAINS || domain == MESSAGE_DOMAIN) return false
        val now = System.currentTimeMillis()
        return db.trustedDeviceDao().all().any { device ->
            trusted(device, now) && device.historyGrant == "FULL_HISTORY" &&
                MESSAGE_DOMAIN in capabilities(device) && domain in capabilities(device)
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
                    it in KEYRING_DOMAINS && !(it == MESSAGE_DOMAIN && device.historyGrant == "FULL_HISTORY")
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
