package com.autonomousone.messages.security

import java.util.UUID

/**
 * LDV-004 PR-E (slice 1) — the pure producer core for per-conversation CKE grants.
 *
 * This file builds the `KEY_GRANT` payload defined by
 * `docs/status/ldv-004-cke-grant-design.md` §4.2/§4.3 and parsed by god-ai's
 * `adapters/gmweb_device/grant.go`:
 *
 *  - [ConversationGrantPayload] / [ConversationGrantPayload.toJson] use the **exact** JSON
 *    field names of `grant.GrantEvent` (`v`, `kind`, `grantId`, `deviceId`, `scopeMode`,
 *    `conversationId`, `keyDomain`, `epochId`, `wrappedKey`, `wrappedKeyAlgorithm`,
 *    `scopeSequence`, `issuedAt`, `expiresAt`, `revokesGrantIds`, `rootSignature`, `reason`)
 *    and standard (not URL-safe) base64.
 *  - the HPKE `info` is
 *    `binding("GMweb-conversation-key-grant-v1", grantId, deviceId, scopeMode,
 *    conversationId, epochId, keyDomain)` — six fields, in that order (`GrantEvent.Unwrap`).
 *  - the grant `rootSignature` is
 *    `binding("GMweb-conversation-key-grant-signature-v1", grantId, deviceId, scopeMode,
 *    conversationId, epochId, keyDomain, scopeSequence, issuedAt, expiresAt, wrappedKey)`.
 *  - the tombstone (`gmweb-conversation-key-revoke`) carries **no** key material and signs
 *    `binding("GMweb-conversation-key-revoke-signature-v1", grantId, deviceId,
 *    conversationId, scopeSequence, issuedAt, reason)` — the PR-A extension god-ai actually
 *    verifies; the design only says "rootSignature over §4.3", so god-ai's `grant.go` is
 *    authoritative here.
 *  - `grantId` is deterministic (`UUID.nameUUIDFromBytes` over
 *    `"LDV-004|deviceId|scopeMode|conversationId|epochId|sequence"`), exactly like
 *    `ConversationKeyRepository`'s existing event ids, so re-publication is idempotent and a
 *    relay cannot replay an edited grant under the same id.
 *
 * ## What this slice is not
 *
 * No Room entity, no outbox insert, no network, no UI, and no use of
 * [PrimaryTrustRoot]/[MessageCrypto] crypto primitives. Wrapping and signing are injected as
 * [GrantKeyWrapper] / [GrantSigner]; the production wiring (a Tink `HybridEncrypt` wrapper
 * and a `PrimaryTrustRoot` signer that base64-decodes `signBytes`) belongs to the next slice.
 * The only production helpers used here are [MessageCrypto.binding] and [MessageCrypto.b64],
 * which are pure encoders and are what the existing producers already use — so the AAD bytes
 * cannot drift from `ConversationKeyRepository`.
 *
 * ## Key material
 *
 * The plaintext CKE ([buildGrant]'s `key`) is handed straight to the injected wrapper and is
 * never copied, retained, logged or serialised by this file; the payload only ever contains
 * the HPKE ciphertext (`wrappedKey`). The wrapper owns the plaintext lifetime and must zero
 * any copy it makes. [ConversationGrantPayload.toString] redacts the wrapped key and the
 * signature so an accidental log line cannot carry them either.
 */
fun interface GrantKeyWrapper {
    /**
     * Wraps [key] for [recipientPublicKey] with [info] as the HPKE / AEAD `info`/AAD.
     *
     * @param recipientPublicKey the device's raw 65-byte uncompressed P-256 point
     *   (`TrustedDeviceEntity.encryptionPublicKey` base64-decoded).
     * @param info the six-field scope binding this grant is bound to. It is public metadata,
     *   not key material.
     * @return the wrapped key bytes (HPKE ciphertext). Must not be empty.
     */
    fun wrap(recipientPublicKey: ByteArray, key: ByteArray, info: ByteArray): ByteArray
}

/** Signs a binding with the phone's non-exportable root key (ECDSA-P256/SHA-256). */
fun interface GrantSigner {
    /**
     * @param binding the exact bytes defined by the grant/tombstone signature domain.
     * @return the DER-encoded ECDSA signature. A production implementation wraps
     *   `PrimaryTrustRoot.signBytes(binding)` (which returns base64) and decodes it once.
     */
    fun sign(binding: ByteArray): ByteArray
}

/**
 * One AI device a grant is addressed to.
 *
 * [encryptionPublicKey] is the raw uncompressed P-256 point (65 bytes, first byte `0x04`),
 * i.e. `MessageCrypto.unb64(TrustedDeviceEntity.encryptionPublicKey)`. Treat the array as
 * immutable; [equals]/[hashCode] use its contents, [toString] only its length.
 */
data class GrantTarget(
    val deviceId: String,
    val encryptionPublicKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is GrantTarget &&
                deviceId == other.deviceId &&
                encryptionPublicKey.contentEquals(other.encryptionPublicKey))

    override fun hashCode(): Int = 31 * deviceId.hashCode() + encryptionPublicKey.contentHashCode()

    override fun toString(): String =
        "GrantTarget(deviceId=$deviceId, encryptionPublicKey=<${encryptionPublicKey.size} bytes>)"
}

/** Wire constants and bounds, mirrored from god-ai `grant.go` and design §4.2. */
object ConversationGrantSchema {
    /** `KEY_GRANT` schema version. */
    const val VERSION = 1

    const val GRANT_KIND = "gmweb-conversation-key-grant"
    const val REVOKE_KIND = "gmweb-conversation-key-revoke"

    const val SCOPE_MODE_CONVERSATION = "conversation"
    const val SCOPE_MODE_ACCOUNT = "account"

    const val KEY_DOMAIN = "READ_MESSAGES"

    const val WRAPPED_KEY_ALGORITHM = "HPKE-P256-HKDF-SHA256-AES-256-GCM"

    /** HPKE `info` domain (design §4.3, six bound fields). */
    const val ENCRYPTION_INFO_DOMAIN = "GMweb-conversation-key-grant-v1"

    /** Grant signature domain (design §4.3, ten bound fields). */
    const val GRANT_SIGNATURE_DOMAIN = "GMweb-conversation-key-grant-signature-v1"

    /**
     * Tombstone signature domain. Not spelled out in the design; god-ai PR-A defines it and
     * documents it as the wire contract for this producer.
     */
    const val REVOKE_SIGNATURE_DOMAIN = "GMweb-conversation-key-revoke-signature-v1"

    /** Prefix of the deterministic `grantId` input (`LDV-004|deviceId|...`). */
    const val GRANT_ID_DOMAIN = "LDV-004"

    /** Tombstone reasons (design §4.2 / god-ai `GrantRevokeReason*`). */
    const val REASON_REVOKED = "revoked"
    const val REASON_SUPERSEDED = "superseded"
    const val REASON_UNLINKED = "unlinked"
    const val REASON_EXPIRED = "expired"

    // Length caps copied from god-ai's parser so a payload we build can never be rejected
    // for shape, and a hostile caller can never make us build an oversized one.
    const val MAX_GRANT_ID_LENGTH = 128
    const val MAX_DEVICE_ID_LENGTH = 128
    const val MAX_CONVERSATION_ID_LENGTH = 256
    const val MAX_KEY_DOMAIN_LENGTH = 64
    const val MAX_EPOCH_ID_LENGTH = 128
    const val MAX_WRAPPED_KEY_BYTES = 1024
    const val MAX_SIGNATURE_BYTES = 256
    const val MAX_REVOKES = 256

    /** The `READ_MESSAGES` CKE is an AES-256 key: exactly 32 bytes. */
    const val CKE_BYTES = 32

    /** Raw uncompressed P-256 public key: `0x04 || X(32) || Y(32)`. */
    const val P256_PUBLIC_KEY_BYTES = 65
    const val P256_UNCOMPRESSED_PREFIX: Byte = 0x04
}

/** Grant vs. key-less tombstone (`kind` on the wire). */
enum class GrantKind(val wire: String) {
    /** `gmweb-conversation-key-grant` — carries the HPKE-wrapped CKE. */
    GRANT(ConversationGrantSchema.GRANT_KIND),

    /** `gmweb-conversation-key-revoke` — no key material (design §4.2). */
    REVOKE(ConversationGrantSchema.REVOKE_KIND),
}

/**
 * `scopeMode` on the wire. A conversation grant authorises one opaque conversation; an
 * account grant authorises the whole account (god-ai maps it to "everything granted").
 */
enum class GrantScopeMode(val wire: String) {
    CONVERSATION(ConversationGrantSchema.SCOPE_MODE_CONVERSATION),
    ACCOUNT(ConversationGrantSchema.SCOPE_MODE_ACCOUNT),
}

/** Tombstone `reason` on the wire (design §4.2). */
enum class GrantRevokeReason(val wire: String) {
    REVOKED(ConversationGrantSchema.REASON_REVOKED),
    SUPERSEDED(ConversationGrantSchema.REASON_SUPERSEDED),
    UNLINKED(ConversationGrantSchema.REASON_UNLINKED),
    EXPIRED(ConversationGrantSchema.REASON_EXPIRED),
}

/**
 * One `KEY_GRANT` payload, matching god-ai's `GrantEvent` field for field.
 *
 * The payload is metadata plus ciphertext: [wrappedKey] is HPKE ciphertext and
 * [rootSignature] is a public signature. It never contains the CKE, a message body, a phone
 * number, a contact name or a conversation title (design §4.2 "Forbidden content").
 *
 * Construction is validated so a malformed payload cannot be serialised:
 *  - a grant always has `scopeMode`, `keyDomain == READ_MESSAGES`, a non-blank `epochId`,
 *    a canonical-base64 non-empty `wrappedKey`, the HPKE algorithm string and
 *    `expiresAt > issuedAt`;
 *  - a tombstone carries none of those (god-ai rejects a tombstone with key material or
 *    scope fields) and always has a [reason].
 */
data class ConversationGrantPayload(
    val kind: GrantKind,
    val grantId: String,
    val deviceId: String,
    val scopeMode: GrantScopeMode? = null,
    val conversationId: String? = null,
    val keyDomain: String? = null,
    val epochId: String? = null,
    val wrappedKey: String? = null,
    val wrappedKeyAlgorithm: String? = null,
    val scopeSequence: Long,
    val issuedAt: Long,
    val expiresAt: Long? = null,
    val revokesGrantIds: List<String> = emptyList(),
    val rootSignature: String,
    val reason: GrantRevokeReason? = null,
    val version: Int = ConversationGrantSchema.VERSION,
) {
    init {
        require(version == ConversationGrantSchema.VERSION) { "v must be 1" }
        require(grantId.isNotBlank() && grantId.length <= ConversationGrantSchema.MAX_GRANT_ID_LENGTH) {
            "grantId must be non-blank and at most ${ConversationGrantSchema.MAX_GRANT_ID_LENGTH} chars"
        }
        require(deviceId.isNotBlank() && deviceId.length <= ConversationGrantSchema.MAX_DEVICE_ID_LENGTH) {
            "deviceId must be non-blank and at most ${ConversationGrantSchema.MAX_DEVICE_ID_LENGTH} chars"
        }
        require(scopeSequence > 0) { "scopeSequence must be positive" }
        require(issuedAt > 0) { "issuedAt must be positive" }
        require(rootSignature.isNotBlank()) { "rootSignature must not be blank" }
        requireCanonicalBase64("rootSignature", rootSignature, ConversationGrantSchema.MAX_SIGNATURE_BYTES)
        require(revokesGrantIds.size <= ConversationGrantSchema.MAX_REVOKES) {
            "revokesGrantIds has too many entries"
        }
        revokesGrantIds.forEach { id ->
            require(id.isNotBlank() && id.length <= ConversationGrantSchema.MAX_GRANT_ID_LENGTH) {
                "revokesGrantIds contains an invalid grant id"
            }
        }
        when (kind) {
            GrantKind.GRANT -> requireGrantFields()
            GrantKind.REVOKE -> requireTombstoneFields()
        }
    }

    private fun requireGrantFields() {
        val mode = requireNotNull(scopeMode) { "scopeMode is required on a grant" }
        require(keyDomain == ConversationGrantSchema.KEY_DOMAIN) { "keyDomain must be READ_MESSAGES" }
        val epoch = requireNotNull(epochId) { "epochId is required on a grant" }
        require(epoch.isNotBlank()) { "epochId must not be blank" }
        require(epoch.length <= ConversationGrantSchema.MAX_EPOCH_ID_LENGTH) {
            "epochId must be at most ${ConversationGrantSchema.MAX_EPOCH_ID_LENGTH} chars"
        }
        val wrapped = requireNotNull(wrappedKey) { "wrappedKey is required on a grant" }
        requireCanonicalBase64(
            "wrappedKey",
            wrapped,
            ConversationGrantSchema.MAX_WRAPPED_KEY_BYTES,
        )
        require(wrappedKeyAlgorithm == ConversationGrantSchema.WRAPPED_KEY_ALGORITHM) {
            "wrappedKeyAlgorithm must be ${ConversationGrantSchema.WRAPPED_KEY_ALGORITHM}"
        }
        val expires = requireNotNull(expiresAt) { "expiresAt is required on a grant" }
        require(expires > issuedAt) { "expiresAt must be after issuedAt" }
        require(reason == null) { "reason must be absent on a grant" }
        when (mode) {
            GrantScopeMode.CONVERSATION -> {
                val conversation = requireNotNull(conversationId) {
                    "conversationId is required for conversation scope"
                }
                require(conversation.isNotBlank()) { "conversationId must not be blank" }
                require(conversation.length <= ConversationGrantSchema.MAX_CONVERSATION_ID_LENGTH) {
                    "conversationId must be at most " +
                        "${ConversationGrantSchema.MAX_CONVERSATION_ID_LENGTH} chars"
                }
            }

            GrantScopeMode.ACCOUNT -> require(conversationId == null) {
                "conversationId must be absent for account scope"
            }
        }
    }

    private fun requireTombstoneFields() {
        require(
            scopeMode == null && keyDomain == null && epochId == null && wrappedKey == null &&
                wrappedKeyAlgorithm == null && expiresAt == null && revokesGrantIds.isEmpty()
        ) { "a tombstone must not carry key material or scope fields" }
        require(reason != null) { "reason is required on a tombstone" }
        require(
            conversationId == null ||
                (!conversationId.isBlank() &&
                    conversationId.length <= ConversationGrantSchema.MAX_CONVERSATION_ID_LENGTH)
        ) { "conversationId has an invalid length" }
    }

    /** True for the key-less revoke tombstone. */
    val isTombstone: Boolean get() = kind == GrantKind.REVOKE

    /**
     * The payload as an ordered map with god-ai's exact field names and JSON types.
     *
     * Optional fields are **omitted** when absent, mirroring Go's `omitempty`, so a tombstone
     * never serialises `scopeMode`/`keyDomain`/`epochId`/`wrappedKey`/`expiresAt`/`reason`
     * slots it must not carry. Field order is god-ai's struct order, which makes the JSON
     * deterministic for a given payload.
     */
    fun toJsonMap(): LinkedHashMap<String, Any> {
        val map = LinkedHashMap<String, Any>()
        map["v"] = version
        map["kind"] = kind.wire
        map["grantId"] = grantId
        map["deviceId"] = deviceId
        scopeMode?.let { map["scopeMode"] = it.wire }
        conversationId?.let { map["conversationId"] = it }
        keyDomain?.let { map["keyDomain"] = it }
        epochId?.let { map["epochId"] = it }
        wrappedKey?.let { map["wrappedKey"] = it }
        wrappedKeyAlgorithm?.let { map["wrappedKeyAlgorithm"] = it }
        map["scopeSequence"] = scopeSequence
        map["issuedAt"] = issuedAt
        expiresAt?.let { map["expiresAt"] = it }
        if (revokesGrantIds.isNotEmpty()) map["revokesGrantIds"] = revokesGrantIds.toList()
        map["rootSignature"] = rootSignature
        reason?.let { map["reason"] = it.wire }
        return map
    }

    /** Compact, deterministic UTF-8 JSON exactly as described by [toJsonMap]. */
    fun toJson(): String = buildString {
        append('{')
        var first = true
        for ((key, value) in toJsonMap()) {
            if (!first) append(',')
            first = false
            append(jsonString(key))
            append(':')
            append(jsonValue(value))
        }
        append('}')
    }

    /**
     * Redacted rendering: the payload's ciphertext and signature never appear in logs. The
     * plaintext key is never part of this object at all.
     */
    override fun toString(): String = buildString {
        append("ConversationGrantPayload(")
        append("kind=").append(kind.wire)
        append(", grantId=").append(grantId)
        append(", deviceId=").append(deviceId)
        append(", scopeMode=").append(scopeMode?.wire)
        append(", conversationId=").append(conversationId)
        append(", keyDomain=").append(keyDomain)
        append(", epochId=").append(epochId)
        append(", scopeSequence=").append(scopeSequence)
        append(", issuedAt=").append(issuedAt)
        append(", expiresAt=").append(expiresAt)
        append(", wrappedKey=<redacted ").append(wrappedKey?.length ?: 0).append(" chars>")
        append(", rootSignature=<redacted ").append(rootSignature.length).append(" chars>")
        append(", reason=").append(reason?.wire)
        append(')')
    }
}

/**
 * Pure builder for the `KEY_GRANT` grant and tombstone payloads (design §4.2/§4.3).
 *
 * Every method validates its inputs before calling the injected crypto, so a bad device,
 * key, epoch, sequence or timestamp fails locally instead of publishing a payload the device
 * will reject (or, worse, one it accepts with the wrong scope).
 */
object ConversationGrantPublisher {

    /**
     * Deterministic `grantId` for a grant:
     * `UUID.nameUUIDFromBytes("LDV-004|deviceId|scopeMode|conversationId|epochId|sequence")`.
     *
     * Note: Kotlin's `nameUUIDFromBytes` is an MD5 name-based UUID (UUIDv3), not the UUIDv5
     * the design prose mentions; this is the same call the existing `ConversationKeyRepository`
     * ids use, and the design's §5.2 requirement is determinism, which this satisfies.
     *
     * @param conversationId `null` for account scope (encoded as the empty string, exactly as
     *   the HPKE `info` encodes it).
     */
    fun grantIdFor(
        deviceId: String,
        scopeMode: GrantScopeMode,
        conversationId: String?,
        epochId: String,
        sequence: Long,
    ): String {
        require(deviceId.isNotBlank()) { "deviceId must not be blank" }
        require(epochId.isNotBlank()) { "epochId must not be blank" }
        require(sequence > 0) { "sequence must be positive" }
        require(
            scopeMode != GrantScopeMode.ACCOUNT || conversationId.isNullOrEmpty()
        ) { "account scope must not name a conversation" }
        require(
            scopeMode != GrantScopeMode.CONVERSATION || !conversationId.isNullOrBlank()
        ) { "conversation scope requires a conversationId" }
        return deterministicGrantId(deviceId, scopeMode, conversationId, epochId, sequence)
    }

    /**
     * Deterministic id for a tombstone built without the superseded grant at hand. It uses
     * the same formula with the (absent) epoch encoded as the empty string; when the
     * superseded grant is known, prefer [buildTombstone]'s grant overload so the tombstone
     * carries the grant's own id (god-ai's `device_grants` upsert is keyed by
     * `(device_id, grant_id)` and a tombstone supersedes the row it names).
     */
    fun tombstoneGrantIdFor(
        deviceId: String,
        scopeMode: GrantScopeMode,
        conversationId: String?,
        sequence: Long,
    ): String {
        require(deviceId.isNotBlank()) { "deviceId must not be blank" }
        require(sequence > 0) { "sequence must be positive" }
        require(
            scopeMode != GrantScopeMode.ACCOUNT || conversationId.isNullOrEmpty()
        ) { "account scope must not name a conversation" }
        require(
            scopeMode != GrantScopeMode.CONVERSATION || !conversationId.isNullOrBlank()
        ) { "conversation scope requires a conversationId" }
        return deterministicGrantId(deviceId, scopeMode, conversationId, epochId = "", sequence)
    }

    private fun deterministicGrantId(
        deviceId: String,
        scopeMode: GrantScopeMode,
        conversationId: String?,
        epochId: String,
        sequence: Long,
    ): String = UUID.nameUUIDFromBytes(
        listOf(
            ConversationGrantSchema.GRANT_ID_DOMAIN,
            deviceId,
            scopeMode.wire,
            conversationId.orEmpty(),
            epochId,
            sequence.toString(),
        ).joinToString("|").toByteArray(Charsets.UTF_8)
    ).toString()

    /**
     * Build a `scopeMode="conversation"` grant for one conversation.
     *
     * @param key the account `READ_MESSAGES` CKE (32 bytes). Handed to [wrapper] and never
     *   retained. The caller owns the buffer and should zero it when done; this builder
     *   makes no copy.
     * @param issuedAt / @param expiresAt epoch millis from the phone clock; the caller must
     *   clamp `expiresAt` to the device certificate expiry (design §4.2).
     * @param sequence the device's new `scopeSequence` (strictly greater than the last
     *   published value; see [ScopeState.nextSequence]).
     * @throws IllegalArgumentException on any input outside the wire schema.
     */
    fun buildGrant(
        target: GrantTarget,
        conversationId: String,
        epochId: String,
        key: ByteArray,
        issuedAt: Long,
        expiresAt: Long,
        sequence: Long,
        wrapper: GrantKeyWrapper,
        signer: GrantSigner,
    ): ConversationGrantPayload {
        validateTarget(target)
        require(conversationId.isNotBlank()) { "conversationId must not be blank" }
        require(conversationId.length <= ConversationGrantSchema.MAX_CONVERSATION_ID_LENGTH) {
            "conversationId must be at most ${ConversationGrantSchema.MAX_CONVERSATION_ID_LENGTH} chars"
        }
        return grantFor(
            target = target,
            scopeMode = GrantScopeMode.CONVERSATION,
            conversationId = conversationId,
            epochId = epochId,
            key = key,
            issuedAt = issuedAt,
            expiresAt = expiresAt,
            sequence = sequence,
            wrapper = wrapper,
            signer = signer,
        )
    }

    /**
     * Build a `scopeMode="account"` grant (the design's whole-scope mode). The HPKE `info`
     * encodes the empty conversation id, so the device's account grant is bound to the same
     * shape god-ai validates (`conversationId` absent for account scope).
     */
    fun buildAccountGrant(
        target: GrantTarget,
        epochId: String,
        key: ByteArray,
        issuedAt: Long,
        expiresAt: Long,
        sequence: Long,
        wrapper: GrantKeyWrapper,
        signer: GrantSigner,
    ): ConversationGrantPayload = grantFor(
        target = target,
        scopeMode = GrantScopeMode.ACCOUNT,
        conversationId = null,
        epochId = epochId,
        key = key,
        issuedAt = issuedAt,
        expiresAt = expiresAt,
        sequence = sequence,
        wrapper = wrapper,
        signer = signer,
    )

    private fun grantFor(
        target: GrantTarget,
        scopeMode: GrantScopeMode,
        conversationId: String?,
        epochId: String,
        key: ByteArray,
        issuedAt: Long,
        expiresAt: Long,
        sequence: Long,
        wrapper: GrantKeyWrapper,
        signer: GrantSigner,
    ): ConversationGrantPayload {
        validateTarget(target)
        require(key.size == ConversationGrantSchema.CKE_BYTES) {
            "key must be a ${ConversationGrantSchema.CKE_BYTES}-byte READ_MESSAGES CKE"
        }
        require(epochId.isNotBlank()) { "epochId must not be blank" }
        require(epochId.length <= ConversationGrantSchema.MAX_EPOCH_ID_LENGTH) {
            "epochId must be at most ${ConversationGrantSchema.MAX_EPOCH_ID_LENGTH} chars"
        }
        require(sequence > 0) { "sequence must be positive" }
        require(issuedAt > 0) { "issuedAt must be positive" }
        require(expiresAt > issuedAt) { "expiresAt must be after issuedAt" }

        val grantId = deterministicGrantId(target.deviceId, scopeMode, conversationId, epochId, sequence)
        val info = encryptionInfo(grantId, target.deviceId, scopeMode, conversationId, epochId)
        val wrapped = wrapper.wrap(target.encryptionPublicKey, key, info)
        require(wrapped.isNotEmpty()) { "wrapper produced an empty wrapped key" }
        require(wrapped.size <= ConversationGrantSchema.MAX_WRAPPED_KEY_BYTES) {
            "wrapped key must be at most ${ConversationGrantSchema.MAX_WRAPPED_KEY_BYTES} bytes"
        }
        val wrappedKey = MessageCrypto.b64(wrapped)
        val signatureBinding = grantSignatureBinding(
            grantId = grantId,
            deviceId = target.deviceId,
            scopeMode = scopeMode,
            conversationId = conversationId,
            epochId = epochId,
            scopeSequence = sequence,
            issuedAt = issuedAt,
            expiresAt = expiresAt,
            wrappedKey = wrappedKey,
        )
        val signature = signer.sign(signatureBinding)
        require(signature.isNotEmpty()) { "signer produced an empty signature" }
        require(signature.size <= ConversationGrantSchema.MAX_SIGNATURE_BYTES) {
            "signature must be at most ${ConversationGrantSchema.MAX_SIGNATURE_BYTES} bytes"
        }
        return ConversationGrantPayload(
            kind = GrantKind.GRANT,
            grantId = grantId,
            deviceId = target.deviceId,
            scopeMode = scopeMode,
            conversationId = conversationId,
            keyDomain = ConversationGrantSchema.KEY_DOMAIN,
            epochId = epochId,
            wrappedKey = wrappedKey,
            wrappedKeyAlgorithm = ConversationGrantSchema.WRAPPED_KEY_ALGORITHM,
            scopeSequence = sequence,
            issuedAt = issuedAt,
            expiresAt = expiresAt,
            rootSignature = MessageCrypto.b64(signature),
        )
    }

    /**
     * Build a key-less `gmweb-conversation-key-revoke` tombstone that supersedes whatever
     * grant is active for the conversation / device.
     *
     * @param conversationId the conversation to revoke, or `null`/blank for an account-wide
     *   revoke (the design's "whole scope" tombstone).
     * @param sequence the tombstone's own `scopeSequence`; it must be strictly greater than
     *   the sequence of the grant it supersedes or god-ai's replay window rejects it.
     * @param reason one of `revoked`, `superseded`, `unlinked`, `expired`.
     *
     * A tombstone has no `wrappedKey`, `keyDomain`, `epochId`, `expiresAt` or
     * `revokesGrantIds`: god-ai rejects a tombstone carrying any of them, and the design
     * forbids key material in a revoke.
     */
    fun buildTombstone(
        target: GrantTarget,
        conversationId: String?,
        issuedAt: Long,
        sequence: Long,
        reason: GrantRevokeReason,
        signer: GrantSigner,
    ): ConversationGrantPayload {
        validateTarget(target)
        require(sequence > 0) { "sequence must be positive" }
        require(issuedAt > 0) { "issuedAt must be positive" }
        val scopeMode = if (conversationId.isNullOrBlank()) {
            GrantScopeMode.ACCOUNT
        } else {
            GrantScopeMode.CONVERSATION
        }
        val conversation = conversationId?.takeIf { it.isNotBlank() }
        require(
            conversation == null ||
                conversation.length <= ConversationGrantSchema.MAX_CONVERSATION_ID_LENGTH
        ) { "conversationId must be at most ${ConversationGrantSchema.MAX_CONVERSATION_ID_LENGTH} chars" }
        val grantId = tombstoneGrantIdFor(target.deviceId, scopeMode, conversation, sequence)
        return tombstoneFor(
            target = target,
            conversationId = conversation,
            grantId = grantId,
            issuedAt = issuedAt,
            sequence = sequence,
            reason = reason,
            signer = signer,
        )
    }

    /**
     * Build a tombstone that reuses the superseded grant's id (the preferred form: god-ai's
     * `device_grants` row is keyed by `(device_id, grant_id)`, so the tombstone updates the
     * exact row it revokes). The tombstone's own [sequence] must still be strictly greater
     * than the grant's — AAD/signature bind it, and the device's replay window requires it.
     */
    fun buildTombstone(
        target: GrantTarget,
        grantedGrant: ConversationGrantPayload,
        issuedAt: Long,
        sequence: Long,
        reason: GrantRevokeReason,
        signer: GrantSigner,
    ): ConversationGrantPayload {
        validateTarget(target)
        require(!grantedGrant.isTombstone) { "a tombstone cannot supersede a tombstone" }
        require(grantedGrant.deviceId == target.deviceId) {
            "the grant belongs to a different device"
        }
        require(sequence > grantedGrant.scopeSequence) {
            "tombstone sequence must be greater than the superseded grant's sequence"
        }
        require(issuedAt > 0) { "issuedAt must be positive" }
        return tombstoneFor(
            target = target,
            conversationId = grantedGrant.conversationId,
            grantId = grantedGrant.grantId,
            issuedAt = issuedAt,
            sequence = sequence,
            reason = reason,
            signer = signer,
        )
    }

    private fun tombstoneFor(
        target: GrantTarget,
        conversationId: String?,
        grantId: String,
        issuedAt: Long,
        sequence: Long,
        reason: GrantRevokeReason,
        signer: GrantSigner,
    ): ConversationGrantPayload {
        val signatureBinding = revokeSignatureBinding(
            grantId = grantId,
            deviceId = target.deviceId,
            conversationId = conversationId,
            scopeSequence = sequence,
            issuedAt = issuedAt,
            reason = reason,
        )
        val signature = signer.sign(signatureBinding)
        require(signature.isNotEmpty()) { "signer produced an empty signature" }
        require(signature.size <= ConversationGrantSchema.MAX_SIGNATURE_BYTES) {
            "signature must be at most ${ConversationGrantSchema.MAX_SIGNATURE_BYTES} bytes"
        }
        return ConversationGrantPayload(
            kind = GrantKind.REVOKE,
            grantId = grantId,
            deviceId = target.deviceId,
            conversationId = conversationId,
            scopeSequence = sequence,
            issuedAt = issuedAt,
            rootSignature = MessageCrypto.b64(signature),
            reason = reason,
        )
    }

    /**
     * The HPKE `info` for a grant — exactly the six fields, in the exact order, that god-ai's
     * `GrantEvent.Unwrap` rebuilds:
     * `grantId, deviceId, scopeMode, conversationId, epochId, keyDomain`.
     * An account grant still encodes the empty conversation id.
     */
    fun encryptionInfo(
        grantId: String,
        deviceId: String,
        scopeMode: GrantScopeMode,
        conversationId: String?,
        epochId: String,
    ): ByteArray = MessageCrypto.binding(
        ConversationGrantSchema.ENCRYPTION_INFO_DOMAIN,
        grantId,
        deviceId,
        scopeMode.wire,
        conversationId.orEmpty(),
        epochId,
        ConversationGrantSchema.KEY_DOMAIN,
    )

    /**
     * The ten-field grant signature binding (god-ai `grantSignatureBinding`, grant branch):
     * `grantId, deviceId, scopeMode, conversationId, epochId, keyDomain, scopeSequence,
     * issuedAt, expiresAt, wrappedKey`.
     */
    fun grantSignatureBinding(
        grantId: String,
        deviceId: String,
        scopeMode: GrantScopeMode,
        conversationId: String?,
        epochId: String,
        scopeSequence: Long,
        issuedAt: Long,
        expiresAt: Long,
        wrappedKey: String,
    ): ByteArray = MessageCrypto.binding(
        ConversationGrantSchema.GRANT_SIGNATURE_DOMAIN,
        grantId,
        deviceId,
        scopeMode.wire,
        conversationId.orEmpty(),
        epochId,
        ConversationGrantSchema.KEY_DOMAIN,
        scopeSequence.toString(),
        issuedAt.toString(),
        expiresAt.toString(),
        wrappedKey,
    )

    /**
     * The six-field tombstone signature binding (god-ai `grantSignatureBinding`, revoke
     * branch): `grantId, deviceId, conversationId, scopeSequence, issuedAt, reason`.
     * There is deliberately no `scopeMode`/`keyDomain`/`epochId` field.
     */
    fun revokeSignatureBinding(
        grantId: String,
        deviceId: String,
        conversationId: String?,
        scopeSequence: Long,
        issuedAt: Long,
        reason: GrantRevokeReason,
    ): ByteArray = MessageCrypto.binding(
        ConversationGrantSchema.REVOKE_SIGNATURE_DOMAIN,
        grantId,
        deviceId,
        conversationId.orEmpty(),
        scopeSequence.toString(),
        issuedAt.toString(),
        reason.wire,
    )

    private fun validateTarget(target: GrantTarget) {
        require(target.deviceId.isNotBlank()) { "deviceId must not be blank" }
        require(target.deviceId.length <= ConversationGrantSchema.MAX_DEVICE_ID_LENGTH) {
            "deviceId must be at most ${ConversationGrantSchema.MAX_DEVICE_ID_LENGTH} chars"
        }
        require(target.encryptionPublicKey.size == ConversationGrantSchema.P256_PUBLIC_KEY_BYTES) {
            "encryptionPublicKey must be a raw uncompressed P-256 point " +
                "(${ConversationGrantSchema.P256_PUBLIC_KEY_BYTES} bytes)"
        }
        require(target.encryptionPublicKey[0] == ConversationGrantSchema.P256_UNCOMPRESSED_PREFIX) {
            "encryptionPublicKey must start with 0x04"
        }
    }
}

/** Validates a base64 field exactly as god-ai's parser does (canonical, bounded). */
private fun requireCanonicalBase64(name: String, value: String, maxBytes: Int) {
    val raw = runCatching { MessageCrypto.unb64(value) }.getOrElse {
        throw IllegalArgumentException("$name must be canonical base64")
    }
    require(raw.isNotEmpty()) { "$name must not be empty" }
    require(raw.size <= maxBytes) { "$name must be at most $maxBytes bytes" }
    require(MessageCrypto.b64(raw) == value) { "$name must be canonical base64" }
}

private fun jsonString(value: String): String = buildString {
    append('"')
    for (ch in value) {
        when (ch) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (ch < ' ') {
                append("\\u").append(Integer.toHexString(ch.code).padStart(4, '0'))
            } else {
                append(ch)
            }
        }
    }
    append('"')
}

private fun jsonValue(value: Any): String = when (value) {
    is String -> jsonString(value)
    is Int -> value.toString()
    is Long -> value.toString()
    is List<*> -> value.joinToString(prefix = "[", postfix = "]", separator = ",") {
        jsonString(it as String)
    }

    else -> throw IllegalStateException("unsupported JSON value type: ${value::class.java.name}")
}
