package com.autonomousone.messages.gateway

import org.json.JSONObject
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Browser-to-Android command envelope; plaintext exists only in process memory. */
object CommandCrypto {
    private fun binding(type: String, idempotencyKey: String): ByteArray =
        listOf("GMweb-command-v1", type, idempotencyKey)
            .mapIndexed { index, value -> if (index == 0) value else Base64.getEncoder().encodeToString(value.toByteArray()) }
            .joinToString("\n").toByteArray()

    private fun hkdf(shared: ByteArray, info: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
        val prk = mac.doFinal(shared)
        return try {
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(info)
            mac.doFinal(byteArrayOf(1)).copyOf(32)
        } finally { prk.fill(0) }
    }

    fun decrypt(envelopeBytes: ByteArray, type: String, idempotencyKey: String): ByteArray {
        val envelope = JSONObject(String(envelopeBytes, Charsets.UTF_8))
        require(envelope.optInt("v") == 1 && envelope.optString("kind") == "command")
        val raw = Base64.getDecoder().decode(envelope.getString("ephemeralPublicKey"))
        require(raw.size == 65 && raw[0] == 4.toByte())
        val parameters = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(java.security.spec.ECParameterSpec::class.java)
        val publicKey = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(
            ECPoint(BigInteger(1, raw.copyOfRange(1, 33)), BigInteger(1, raw.copyOfRange(33, 65))), parameters
        ))
        val privateKey = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            .getKey(DeviceIdentity.ALIAS_OP_ENCRYPTION, null) as? java.security.PrivateKey
            ?: error("operational encryption key unavailable")
        val shared = KeyAgreement.getInstance("ECDH").run {
            init(privateKey); doPhase(publicKey, true); generateSecret()
        }
        val aad = binding(type, idempotencyKey)
        val key = hkdf(shared, aad)
        shared.fill(0)
        return try {
            val iv = Base64.getDecoder().decode(envelope.getString("iv"))
            require(iv.size == 12)
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                updateAAD(aad)
                doFinal(Base64.getDecoder().decode(envelope.getString("ciphertext")))
            }
        } finally { key.fill(0) }
    }
}
