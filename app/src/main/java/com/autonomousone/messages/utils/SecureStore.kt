package com.autonomousone.messages.utils

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Lightweight encryption helpers backed by the hardware-backed Android Keystore.
 *
 * Secrets (API keys, cloud tokens, webhook signing secrets) are encrypted with a
 * non-exportable AES-256-GCM key before being persisted to SharedPreferences,
 * so they are never stored in plaintext at rest.
 */
object SecureStore {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "messages_secure_prefs_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    // Cipher.init triggers a binder IPC to KeyStoreSecurityLevel.createOperation.
    // A dedicated single thread keeps these binder calls OFF the shared arch_disk_io
    // pool that Room depends on, preventing ANR and "room-not-ready" starvation.
    private val keystoreExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "keystore-crypto").apply { isDaemon = true }
    }

    private val decryptCache = ConcurrentHashMap<String, String>()

    /** Encrypt [plainText]; returns Base64(iv || ciphertext) or null on failure. */
    fun encrypt(plainText: String): String? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(iv + cipherText, Base64.NO_WRAP)
        decryptCache[encoded] = plainText
        encoded
    } catch (e: Exception) {
        android.util.Log.e("SecureStore", "Encryption failed", e)
        null
    }

    /**
     * Encrypt on the dedicated keystore thread. Returns the ciphertext or null.
     * Never blocks arch_disk_io.
     */
    fun encryptAsync(plainText: String): Future<String?> =
        keystoreExecutor.submit<String?> { encrypt(plainText) }

    /** Decrypt a payload produced by [encrypt]; returns null on any failure. */
    fun decrypt(encoded: String): String? {
        decryptCache[encoded]?.let { return it }
        return try {
            val data = Base64.decode(encoded, Base64.NO_WRAP)
            if (data.size <= GCM_IV_BYTES) {
                null
            } else {
                val iv = data.copyOfRange(0, GCM_IV_BYTES)
                val cipherText = data.copyOfRange(GCM_IV_BYTES, data.size)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
                val plain = String(cipher.doFinal(cipherText), Charsets.UTF_8)
                decryptCache[encoded] = plain
                plain
            }
        } catch (e: Exception) {
            android.util.Log.e("SecureStore", "Decryption failed", e)
            null
        }
    }

    /**
     * Decrypt on the dedicated keystore thread. Never blocks arch_disk_io.
     */
    fun decryptAsync(encoded: String): Future<String?> {
        decryptCache[encoded]?.let {
            return keystoreExecutor.submit<String?> { it }
        }
        return keystoreExecutor.submit<String?> { decrypt(encoded) }
    }

    /** Cryptographically strong random hex string of [byteCount] bytes (2x hex chars). */
    fun randomHex(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
