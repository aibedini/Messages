package com.autonomousone.messages.security

import androidx.room.withTransaction
import com.autonomousone.messages.data.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Runs within the same Room transaction as the canonical message/outbox row. */
class ConversationKeyRepository(private val db: MessagesDatabase) {
    companion object {
        fun eligible(device: TrustedDeviceEntity, floor: Long, category: String, now: Long): Boolean {
            if (device.status !in setOf(TrustedDeviceEntity.STATUS_ACTIVE, TrustedDeviceEntity.STATUS_PENDING_PUBLICATION) ||
                device.certificateJson.isBlank() || device.expiresAt <= now || device.revokedAt != null) return false
            val arr = JSONArray(device.capabilitiesJson)
            val caps = (0 until arr.length()).map { arr.getString(it) }
            return "READ_MESSAGES" in caps && (category.isBlank() || category in caps) &&
                (device.historyGrant == "FULL_HISTORY" || (device.historyGrant == "FROM_NOW_ON" && floor >= device.approvedAt))
        }
    }
    suspend fun encrypt(row: GatewayEventOutboxEntity, messageAt: Long, category: String): GatewayEventOutboxEntity {
        val now = System.currentTimeMillis()
        val devices = db.trustedDeviceDao().all()
        val generation = db.trustStatementOutboxDao().maxTrustSequence()
        // Time partitions prevent a historical backfill from reusing an epoch
        // already granted to a device which selected FROM_NOW_ON.
        val floor = devices.map { it.approvedAt }.filter { it <= messageAt }.maxOrNull() ?: 0L
        var epoch = db.conversationKeyDao().current(row.aggregateId, generation, floor, category)
        if (epoch == null) {
            val id = UUID.randomUUID().toString()
            val raw = MessageCrypto.randomKey()
            try {
                epoch = ConversationKeyEpochEntity(epochId = id, conversationId = row.aggregateId,
                    generation = generation, historyFloor = floor, category = category,
                    wrappedKey = ConversationKeyVault.wrap(id, raw), createdAt = now)
                db.conversationKeyDao().insert(epoch)
            } finally { raw.fill(0) }
        }
        val cke = ConversationKeyVault.unwrap(epoch.epochId, epoch.wrappedKey)
        try {
            for (device in devices) if (eligible(device, floor, category, now)) grant(epoch, device, cke)
            val payload = GatewayEventFactory.decodePayloadEnvelope(row.ciphertext).toByteArray(Charsets.UTF_8)
            return row.copy(cryptoVersion = 1, ciphertext = MessageCrypto.encryptMessage(cke, epoch.epochId,
                row.eventUuid, row.eventType, row.aggregateId, payload))
        } finally { cke.fill(0) }
    }

    private suspend fun grant(epoch: ConversationKeyEpochEntity, device: TrustedDeviceEntity, raw: ByteArray) {
        val eventId = UUID.nameUUIDFromBytes("grant:${epoch.epochId}:${device.deviceId}:${device.encryptionPublicKey}"
            .toByteArray(Charsets.UTF_8)).toString()
        if (db.gatewayEventOutboxDao().idOf(eventId) != null) return
        val fields = arrayOf(epoch.epochId, epoch.conversationId, device.deviceId, epoch.category, epoch.historyFloor.toString())
        val wrapped = MessageCrypto.b64(MessageCrypto.wrapForDevice(MessageCrypto.unb64(device.encryptionPublicKey), raw,
            MessageCrypto.binding("GMweb-CKE-v1", *fields)))
        val payload = JSONObject().put("v", 1).put("kind", "key-grant").put("epochId", epoch.epochId)
            .put("conversationId", epoch.conversationId).put("deviceId", device.deviceId)
            .put("category", epoch.category).put("historyFloor", epoch.historyFloor)
            .put("wrappedCke", wrapped).put("rootSignature", PrimaryTrustRoot.signBytes(
                MessageCrypto.binding("GMweb-CKE-signature-v1", *fields, wrapped)))
        db.gatewayEventOutboxDao().insertOrIgnore(GatewayEventOutboxEntity(eventUuid = eventId,
            eventType = "KEY_GRANT", aggregateId = epoch.conversationId, ciphertext = payload.toString().toByteArray(Charsets.UTF_8),
            encoding = "envelope.v1", schemaVersion = 1, cryptoVersion = 1, createdAt = System.currentTimeMillis()))
    }

    /** Implicit durable job per signed trust revision; pages and emitted grants commit together. */
    suspend fun drainHistoryGrants() {
        for (candidate in db.trustedDeviceDao().all()) {
            db.withTransaction {
                val device = db.trustedDeviceDao().byId(candidate.deviceId) ?: return@withTransaction
                if (device.status !in setOf(TrustedDeviceEntity.STATUS_ACTIVE, TrustedDeviceEntity.STATUS_PENDING_PUBLICATION)) return@withTransaction
                val direction = "cke-grants:${device.deviceId}:${device.trustSequence}"
                val cursor = db.syncCursorDao().get(direction)?.lastSequence ?: 0L
                val page = db.conversationKeyDao().page(cursor, 25)
                for (epoch in page) if (eligible(device, epoch.historyFloor, epoch.category, System.currentTimeMillis())) {
                    val raw = ConversationKeyVault.unwrap(epoch.epochId, epoch.wrappedKey)
                    try { grant(epoch, device, raw) } finally { raw.fill(0) }
                }
                if (page.isNotEmpty()) db.syncCursorDao().upsert(SyncCursorEntity(direction,
                    lastSequence = page.last().id, updatedAt = System.currentTimeMillis()))
            }
        }
    }
}
