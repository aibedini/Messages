package com.autonomousone.messages.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * ADR-007 BLOCKER 2 — PrimaryTrustRoot: the Android-side root of trust for
 * pairing approvals.
 *
 * Contract:
 *  - The Trust Root signing key lives in Android Keystore, NON-EXPORTABLE,
 *    hardware-backed (StrongBox/TEE where available).
 *  - It is NOT the operational AgentAuth HTTP-signing key (separate alias,
 *    separate purpose, separate key). Web device certificates are signed
 *    HERE, not by the agent HTTP key.
 *  - The canonical certificate serialization is a byte-for-byte contract
 *    shared with the web client (certVerify.ts) — same fixed key order,
 *    UTF-8, compact separators.
 */
object PrimaryTrustRoot {

    private const val TAG = "PRIMARY_TRUST_ROOT"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "messages_primary_trust_root"
    private const val CERT_VERSION = 1

    /** Canonical certificate serialization — MUST match web/src/lib/certVerify.ts. */
    fun canonicalCertificate(c: JSONObject): String {
        val o = org.json.JSONObject()
        o.put("accountId", c.getString("accountId"))
        o.put("deviceId", c.getString("deviceId"))
        o.put("deviceType", c.getString("deviceType"))
        o.put("signingPublicKey", c.getString("signingPublicKey"))
        o.put("encryptionPublicKey", c.getString("encryptionPublicKey"))
        // capabilities: fixed order, comma-joined inside the canonical string
        val caps = c.getJSONArray("capabilities")
        val capsSorted = (0 until caps.length()).map { caps.getString(it) }.sorted()
        o.put("capabilities", org.json.JSONArray(capsSorted))
        o.put("historyGrant", c.getString("historyGrant"))
        o.put("trustSequence", c.getLong("trustSequence"))
        o.put("issuedAt", c.getLong("issuedAt"))
        o.put("expiresAt", c.getLong("expiresAt"))
        o.put("pairingTranscriptHash", c.getString("pairingTranscriptHash"))
        o.put("origin", c.getString("origin"))
        return o.toString() // org.json is compact & insertion-ordered
    }

    /** ECDSA-P256/SHA-256 signature over the canonical certificate bytes. */
    fun sign(certificate: JSONObject): String {
        val canonical = canonicalCertificate(certificate).toByteArray(Charsets.UTF_8)
        val privateKey = getOrCreatePrivateKey()
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(privateKey)
            update(canonical)
        }.sign()
        return Base64.encodeToString(signature, Base64.NO_WRAP)
    }

    /** Public key (base64 SPKI) so the web can pin/verify the root signature. */
    fun publicKeyBase64(): String {
        val privateKey = getOrCreatePrivateKey()
        val entry = keyStore().getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
        val pub = entry.certificate.publicKey
        return Base64.encodeToString(pub.encoded, Base64.NO_WRAP)
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(KEYSTORE).apply { load(null) }

    @Synchronized
    private fun getOrCreatePrivateKey(): java.security.PrivateKey {
        val ks = keyStore()
        if (ks.containsAlias(ALIAS)) {
            return ks.getKey(ALIAS, null) as java.security.PrivateKey
        }
        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC, KEYSTORE
        )
        generator.initialize(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false) // biometric gates the FLOW, not every key use
                .build()
        )
        generator.generateKeyPair()
        Log.i(TAG, "PrimaryTrustRoot key generated (non-exportable, hardware-backed where available)")
        return ks.getKey(ALIAS, null) as java.security.PrivateKey
    }

    const val VERSION = CERT_VERSION
}
