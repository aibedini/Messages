package com.autonomousone.messages.data

import org.json.JSONObject

/**
 * LDV-004 PR-E (slice 2b) — the durable outbox row for one `KEY_GRANT` payload.
 *
 * A `KEY_GRANT` is an ordinary control-key sync event: `EventCryptoPolicy.keyVersions` already
 * allows `KEY_GRANT` at `cryptoVersion 1`, GMweb already accepts, indexes and serves it, and
 * [com.autonomousone.messages.gateway.EventUploader] is the only transmitter. So this file does
 * **not** invent a second queue — it builds the exact [GatewayEventOutboxEntity] the existing
 * loader drains, and the existing producer path (`ConversationKeyRepository.publishKeyringEntry`)
 * is the template for every field below.
 *
 * ## Why `ciphertext` holds the raw JSON, not base64
 *
 * Two encodings meet here and they must not be confused:
 *
 *  - **At rest and at ingest**, GMweb parses the payload as JSON: its grant index and query are
 *    `json_extract(CAST(ciphertext AS TEXT), '$.deviceId')`
 *    (`gmweb-main/src/eventStore.js:160-165`, `:270-288`), and it stores what the batch request
 *    carried base64-decoded. The room column therefore holds the **raw UTF-8 JSON bytes**, which
 *    is exactly what every existing key producer writes (`payload.toString().toByteArray`).
 *  - **On the wire**, `EventUploader` base64-encodes `ciphertext` into the batch item's
 *    `payload` string (`EventUploader.uploadBatch`). The server sees base64, decodes it once and
 *    indexes the JSON.
 *
 * Writing base64 into the column would double-encode and make GMweb's `json_extract` return
 * NULL, i.e. the grant would never be routed to the device.
 *
 * ## Targeting
 *
 * The payload's `deviceId` is the routing key. This factory refuses a payload whose `deviceId`
 * differs from the device the caller claims to target, so a mis-wired adapter cannot deliver a
 * grant to the wrong device. `aggregateId` is only local/diagnostic metadata (GMweb routes by
 * the payload field); it repeats the conversation id for a conversation grant and uses the
 * account-keyring aggregate for an account grant, mirroring `KEYRING_ENTRY`.
 */
object ConversationGrantOutbox {

    /** Wire event type (design §4.1). Already allowed by `EventCryptoPolicy`. */
    const val EVENT_TYPE = "KEY_GRANT"

    /** `cryptoVersion 1` implies this encoding name; `EventCryptoPolicy` asserts the pair. */
    const val ENCODING = "envelope.v1"

    /** Design §4.1: `KEY_GRANT` reuses the v1 control-key envelope, no new crypto. */
    const val CRYPTO_VERSION = 1

    /** Every outbox row in this app carries schema version 1. */
    const val SCHEMA_VERSION = 1

    /**
     * `aggregateId` for an account-wide grant/tombstone (no conversation). Matches the
     * aggregate key `ConversationKeyRepository` uses for the account keyring, so diagnostics
     * that group by aggregate see the account key material together.
     */
    const val ACCOUNT_AGGREGATE_ID = "__account_keyring__"

    private const val FIELD_DEVICE_ID = "deviceId"
    private const val FIELD_CONVERSATION_ID = "conversationId"

    /**
     * The conversation id a payload names, or `null` for an account-scope payload / a payload
     * that does not parse. The payload is the producer's own JSON (design §4.2), not opaque
     * business data, and this value is already public routing metadata.
     */
    fun conversationIdOf(payloadJson: ByteArray): String? = runCatching {
        JSONObject(String(payloadJson, Charsets.UTF_8))
            .optString(FIELD_CONVERSATION_ID, "")
            .takeIf { it.isNotBlank() }
    }.getOrNull()

    /** The device id a payload is addressed to, or `null` when it does not parse. */
    fun deviceIdOf(payloadJson: ByteArray): String? = runCatching {
        JSONObject(String(payloadJson, Charsets.UTF_8))
            .optString(FIELD_DEVICE_ID, "")
            .takeIf { it.isNotBlank() }
    }.getOrNull()

    /** The local aggregate id for [payloadJson] (conversation id, else [ACCOUNT_AGGREGATE_ID]). */
    fun aggregateIdOf(payloadJson: ByteArray): String =
        conversationIdOf(payloadJson) ?: ACCOUNT_AGGREGATE_ID

    /**
     * Build the row [com.autonomousone.messages.gateway.EventUploader] will drain.
     *
     * @param eventId the deterministic id the producer assigned — the payload `grantId`, so a
     *   repeated decision is the same outbox row (`INSERT OR IGNORE`).
     * @param deviceId the device the payload must be addressed to; a mismatch throws rather
     *   than queueing an event that would be routed to another device.
     * @param payloadJson the exact `KEY_GRANT` JSON bytes (design §4.2).
     * @throws IllegalArgumentException when the payload is not addressed to [deviceId].
     */
    fun create(
        eventId: String,
        deviceId: String,
        payloadJson: ByteArray,
        createdAt: Long,
    ): GatewayEventOutboxEntity {
        require(eventId.isNotBlank()) { "eventId must not be blank" }
        require(deviceId.isNotBlank()) { "deviceId must not be blank" }
        require(payloadJson.isNotEmpty()) { "payloadJson must not be empty" }
        val addressedTo = deviceIdOf(payloadJson)
        require(addressedTo == deviceId) {
            "payload is addressed to a different device"
        }
        return GatewayEventOutboxEntity(
            eventUuid = eventId,
            eventType = EVENT_TYPE,
            aggregateId = aggregateIdOf(payloadJson),
            ciphertext = payloadJson,
            encoding = ENCODING,
            schemaVersion = SCHEMA_VERSION,
            cryptoVersion = CRYPTO_VERSION,
            createdAt = createdAt,
        )
    }
}
