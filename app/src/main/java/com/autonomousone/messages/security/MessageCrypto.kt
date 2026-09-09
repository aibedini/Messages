package com.autonomousone.messages.security

import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.Registry
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.proto.*
import com.google.crypto.tink.shaded.protobuf.ByteString
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** RFC 9180 HPKE for CKE grants; JCA AES-256-GCM for message and DEK AEAD. */
object MessageCrypto {
    private val random = SecureRandom()
    fun randomKey(): ByteArray = ByteArray(32).also(random::nextBytes)
    fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun unb64(value: String): ByteArray = Base64.getDecoder().decode(value)
    fun binding(domain: String, vararg fields: String): ByteArray =
        (listOf(domain) + fields.map { b64(it.toByteArray(Charsets.UTF_8)) })
            .joinToString("\n").toByteArray(Charsets.UTF_8)

    data class Sealed(val iv: ByteArray, val ciphertext: ByteArray)
    fun seal(key: ByteArray, plaintext: ByteArray, aad: ByteArray): Sealed {
        require(key.size == 32)
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return Sealed(iv, cipher.doFinal(plaintext))
    }
    fun open(key: ByteArray, value: Sealed, aad: ByteArray): ByteArray {
        require(key.size == 32 && value.iv.size == 12 && value.ciphertext.size >= 16)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, value.iv))
            updateAAD(aad)
            doFinal(value.ciphertext)
        }
    }
    fun encryptMessage(cke: ByteArray, epoch: String, eventId: String, type: String, conversation: String, payload: ByteArray): ByteArray {
        val dek = randomKey()
        try {
            val body = seal(dek, payload, binding("GMweb-message-v1", epoch, eventId, type, conversation))
            val wrapped = seal(cke, dek, binding("GMweb-DEK-v1", epoch, eventId, type, conversation))
            return JSONObject().put("v", 1).put("kind", "message").put("epochId", epoch)
                .put("eventId", eventId).put("type", type).put("conversationId", conversation)
                .put("iv", b64(body.iv)).put("ciphertext", b64(body.ciphertext))
                .put("wrapIv", b64(wrapped.iv)).put("wrappedDek", b64(wrapped.ciphertext))
                .toString().toByteArray(Charsets.UTF_8)
        } finally { dek.fill(0) }
    }

    /**
     * v2 uses a small account keyring instead of one key epoch per conversation.
     * The domain is a capability boundary (READ_MESSAGES, CONTACTS_READ, ...),
     * while keyId selects the version created for a trust revision.
     */
    fun encryptMessageV2(
        accountKey: ByteArray,
        keyId: String,
        domain: String,
        eventId: String,
        type: String,
        conversation: String,
        payload: ByteArray
    ): ByteArray {
        val fields = arrayOf(keyId, domain, eventId, type, conversation)
        val dek = randomKey()
        try {
            val body = seal(dek, payload, binding("GMweb-message-v2", *fields))
            val wrapped = seal(accountKey, dek, binding("GMweb-DEK-v2", *fields))
            return JSONObject().put("v", 2).put("kind", "message")
                .put("keyId", keyId).put("domain", domain)
                .put("eventId", eventId).put("type", type).put("conversationId", conversation)
                .put("iv", b64(body.iv)).put("ciphertext", b64(body.ciphertext))
                .put("wrapIv", b64(wrapped.iv)).put("wrappedDek", b64(wrapped.ciphertext))
                .toString().toByteArray(Charsets.UTF_8)
        } finally { dek.fill(0) }
    }

    /** Normal-message v3: one history wrap plus one rotating FROM_NOW_ON wrap. */
    fun encryptMessageV3(
        historyKey: ByteArray,
        historyKeyId: String,
        liveKey: ByteArray,
        liveKeyId: String,
        eventId: String,
        type: String,
        conversation: String,
        payload: ByteArray
    ): ByteArray {
        val fields = arrayOf(
            historyKeyId, liveKeyId, "READ_MESSAGES", eventId, type, conversation
        )
        val dek = randomKey()
        try {
            val body = seal(dek, payload, binding("GMweb-message-v3", *fields))
            val historyWrap = seal(historyKey, dek, binding("GMweb-history-DEK-v3", *fields))
            val liveWrap = seal(liveKey, dek, binding("GMweb-live-DEK-v3", *fields))
            return JSONObject().put("v", 3).put("kind", "message")
                .put("historyKeyId", historyKeyId).put("liveKeyId", liveKeyId)
                .put("domain", "READ_MESSAGES")
                .put("eventId", eventId).put("type", type).put("conversationId", conversation)
                .put("iv", b64(body.iv)).put("ciphertext", b64(body.ciphertext))
                .put("historyWrapIv", b64(historyWrap.iv))
                .put("historyWrappedDek", b64(historyWrap.ciphertext))
                .put("liveWrapIv", b64(liveWrap.iv))
                .put("liveWrappedDek", b64(liveWrap.ciphertext))
                .toString().toByteArray(Charsets.UTF_8)
        } finally { dek.fill(0) }
    }

    fun wrapForDevice(rawPublicKey: ByteArray, cke: ByteArray, info: ByteArray): ByteArray {
        require(rawPublicKey.size == 65 && rawPublicKey[0] == 4.toByte())
        HybridConfig.register()
        val params = HpkeParams.newBuilder().setKem(HpkeKem.DHKEM_P256_HKDF_SHA256)
            .setKdf(HpkeKdf.HKDF_SHA256).setAead(HpkeAead.AES_256_GCM).build()
        val key = HpkePublicKey.newBuilder().setVersion(0).setParams(params)
            .setPublicKey(ByteString.copyFrom(rawPublicKey)).build()
        return Registry.getPrimitive("type.googleapis.com/google.crypto.tink.HpkePublicKey",
            key.toByteString(), HybridEncrypt::class.java).encrypt(cke, info)
    }
}
