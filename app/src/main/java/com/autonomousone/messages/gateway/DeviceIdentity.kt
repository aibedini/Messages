package com.autonomousone.messages.gateway

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * PR-05 (ADR-001): Android Agent device identity — THREE Keystore key pairs.
 *
 * 1. ACCOUNT_TRUST_ROOT — signs DeviceCertificates / trust statements ONLY
 *    (never day-to-day signing). Created once at first enrollment.
 * 2. OPERATIONAL_SIGNING — signs events/commands (Phase 2 wire protocol).
 * 3. OPERATIONAL_ENCRYPTION — ECDH P-256 key-agreement target for
 *    point-to-point command encryption (ADR-002 LOCK 4).
 *
 * All keys are non-exportable (SECURITY_LEVEL_STRONGBOX when available, else
 * hardware TEE). Private material NEVER leaves the Keystore; only public keys
 * (raw/uncompressed point encoding) are handed out for registration. Fail
 * closed: if the Keystore cannot provide a key, identity operations throw —
 * there is no plaintext fallback (TechSpec §83).
 */
object DeviceIdentity {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TAG = "DEVICE_IDENTITY"
    private const val CURVE = "secp256r1"

    const val ALIAS_TRUST_ROOT = "messages_trust_root_key"
    const val ALIAS_OP_SIGNING = "messages_device_signing_key"
    const val ALIAS_OP_ENCRYPTION = "messages_device_encryption_key"

    /** Immutable identity snapshot for registration (public parts only). */
    data class PublicIdentity(
        val trustRootPublicPoint: ByteArray,
        val signingPublicPoint: ByteArray,
        val encryptionPublicPoint: ByteArray
    )

    /**
     * Idempotent: creates any missing key pair, leaves existing ones intact
     * (rotation is a separate, explicit operation per ADR-001 LOCK rules).
     * Returns the public identity for GMweb registration.
     */
    fun ensureEnrolled(): PublicIdentity {
        val trustRoot = getOrCreateEcdhCapableSigningKey(ALIAS_TRUST_ROOT, strongBox = true)
        val signing = getOrCreateEcdhCapableSigningKey(ALIAS_OP_SIGNING, strongBox = true)
        val encryption = getOrCreateEncryptionKey(ALIAS_OP_ENCRYPTION, strongBox = true)
        return PublicIdentity(
            trustRootPublicPoint = trustRoot.uncompressedPoint(),
            signingPublicPoint = signing.uncompressedPoint(),
            encryptionPublicPoint = encryption.uncompressedPoint()
        )
    }

    /** True when all three key pairs exist and are usable. */
    fun isEnrolled(): Boolean = try {
        aliasesPresent() &&
            getPrivateKey(ALIAS_TRUST_ROOT) != null &&
            getPrivateKey(ALIAS_OP_SIGNING) != null &&
            getPrivateKey(ALIAS_OP_ENCRYPTION) != null
    } catch (e: Exception) {
        Log.e(TAG, "isEnrolled failed", e)
        false
    }

    /** Sign [data] (raw bytes) with the operational signing key → DER signature. */
    fun signWithOperationalKey(data: ByteArray): ByteArray {
        val privateKey = getPrivateKey(ALIAS_OP_SIGNING)
            ?: throw IllegalStateException("operational signing key unavailable (fail closed)")
        return Signature.getInstance("SHA256withECDSA").apply {
            initSign(privateKey)
            update(data)
        }.sign()
    }

    /** Sign a trust statement / certificate with the ACCOUNT TRUST ROOT key. */
    fun signWithTrustRoot(data: ByteArray): ByteArray {
        val privateKey = getPrivateKey(ALIAS_TRUST_ROOT)
            ?: throw IllegalStateException("trust root key unavailable (fail closed)")
        return Signature.getInstance("SHA256withECDSA").apply {
            initSign(privateKey)
            update(data)
        }.sign()
    }

    /** Keystore validity probe — cheap, called by heartbeat (§89). */
    fun keystoreHealthy(): Boolean = isEnrolled()

    // ── Internals ───────────────────────────────────────────────────────────

    private fun aliasesPresent(): Boolean {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.containsAlias(ALIAS_TRUST_ROOT) &&
            keyStore.containsAlias(ALIAS_OP_SIGNING) &&
            keyStore.containsAlias(ALIAS_OP_ENCRYPTION)
    }

    private fun getPrivateKey(alias: String) =
        (KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            .getKey(alias, null) as? java.security.PrivateKey)

    /**
     * EC P-256 key pair usable for BOTH ES256 signing and (via agreement) X
     * ECDH; setDigests both families so one generator serves all three roles.
     */
    private fun getOrCreateEcdhCapableSigningKey(alias: String, strongBox: Boolean): ECPublicKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry)?.let {
            return it.certificate.publicKey as ECPublicKey
        }
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY or
                KeyProperties.PURPOSE_AGREE_KEY
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec(CURVE))
            .setDigests(KeyProperties.DIGEST_SHA256)
        return generate(alias, builder, strongBox) as ECPublicKey
    }

    /** Pure ECDH key-agreement key (encryption/kem role in ADR-002). */
    private fun getOrCreateEncryptionKey(alias: String, strongBox: Boolean): ECPublicKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry)?.let {
            return it.certificate.publicKey as ECPublicKey
        }
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_AGREE_KEY
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec(CURVE))
            .setDigests(KeyProperties.DIGEST_SHA256)
        return generate(alias, builder, strongBox) as ECPublicKey
    }

    private fun generate(alias: String, builder: KeyGenParameterSpec.Builder, strongBox: Boolean): java.security.PublicKey {
        var spec = builder.build()
        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE
        )
        if (strongBox && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            try {
                // Rebuild the spec with StrongBox backing (setIsStrongBoxBacked
                // must be set before build(); there is no copy constructor).
                spec = KeyGenParameterSpec.Builder(alias, spec.purposes)
                    .setAlgorithmParameterSpec(ECGenParameterSpec(CURVE))
                    .setDigests(*spec.digests)
                    .setIsStrongBoxBacked(true)
                    .build()
                generator.initialize(spec)
                return generator.generateKeyPair().public
            } catch (e: Exception) {
                Log.w(TAG, "StrongBox unavailable for $alias — falling back to TEE", e)
            }
        }
        generator.initialize(spec)
        return generator.generateKeyPair().public
    }

    /** Raw uncompressed EC point (0x04 || X || Y) — the wire format we register. */
    private fun ECPublicKey.uncompressedPoint(): ByteArray {
        val w = this.w
        val x = w.affineX.toByteArray()
        val y = w.affineY.toByteArray()
        // BigInteger strips leading zeros — pad each coordinate to 32 bytes.
        fun pad(b: ByteArray): ByteArray = when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
            else -> ByteArray(32 - b.size) + b
        }
        return byteArrayOf(0x04) + pad(x) + pad(y)
    }
}
