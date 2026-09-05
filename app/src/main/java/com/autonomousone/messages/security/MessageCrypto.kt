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
