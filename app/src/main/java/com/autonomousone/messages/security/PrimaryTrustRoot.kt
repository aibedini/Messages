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
    fun canonicalCertificate(c: JSONObject): String = PairingProtocol.certificate(c)

    fun signBytes(bytes: ByteArray): String {
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(getOrCreatePrivateKey())
            update(bytes)
        }.sign()
        return Base64.encodeToString(signature, Base64.NO_WRAP)
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

    /**
     * P0 (v2.6.36): TRUST STATEMENT signer — SEPARATE from the certificate
     * signer. canonicalCertificate() requires certificate-only fields and
     * crashed on statement payloads (JSONException → Room rollback → empty
     * Linked Devices list). The statement has its own versioned contract:
     * sorted capabilities, fixed field set, exact bytes persisted/published.
     */
    const val STATEMENT_VERSION = 1

    fun canonicalTrustStatement(st: JSONObject): String {
        val caps = st.optJSONArray("capabilities") ?: org.json.JSONArray()
        val capsSorted = (0 until caps.length()).map { caps.getString(it) }.sorted()
        fun q(value: String): String = JSONObject.quote(value)
        // Fixed field order. JSONObject's backing map order differs between
        // Android and JVM runtimes, so its toString() cannot define signed bytes.
        return buildString {
            append("{\"version\":").append(st.optInt("version", STATEMENT_VERSION))
            append(",\"accountId\":").append(q(st.optString("accountId", "default")))
            append(",\"statementId\":").append(q(st.getString("statementId")))
            append(",\"operation\":").append(q(st.getString("operation")))
            append(",\"deviceId\":").append(q(st.getString("deviceId")))
            append(",\"trustSequence\":").append(st.getLong("trustSequence"))
            append(",\"capabilities\":[")
            capsSorted.forEachIndexed { index, capability ->
                if (index > 0) append(',')
                append(q(capability))
            }
            append(']')
            append(",\"historyGrant\":").append(q(st.optString("historyGrant", "")))
            append(",\"issuedAt\":").append(st.getLong("issuedAt"))
            // certificate is an opaque signed blob — hashed, never re-serialized
            append(",\"certificateHash\":").append(q(sha256Hex(st.optString("certificate", ""))))
            append('}')
        }
    }

    /** Sign the canonical statement bytes (the payload persisted/published). */
    fun signTrustStatement(st: JSONObject): String {
        val canonical = canonicalTrustStatement(st).toByteArray(Charsets.UTF_8)
        val privateKey = getOrCreatePrivateKey()
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(privateKey)
            update(canonical)
        }.sign()
        return Base64.encodeToString(signature, Base64.NO_WRAP)
    }

    private fun sha256Hex(data: String): String {
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(data.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
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
