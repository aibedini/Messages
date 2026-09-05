package com.autonomousone.messages.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** CKE-at-rest protection; a lost Keystore key never silently replaces existing ciphertext keys. */
object ConversationKeyVault {
    private const val ALIAS = "messages_cke_storage_v1"
    @Synchronized private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "CKE storage key unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun wrap(epochId: String, raw: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, key(true))
        updateAAD(epochId.toByteArray(Charsets.UTF_8))
        iv + doFinal(raw)
    }
    fun unwrap(epochId: String, wrapped: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        require(wrapped.size == 60)
        init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, wrapped.copyOfRange(0, 12)))
        updateAAD(epochId.toByteArray(Charsets.UTF_8))
        doFinal(wrapped.copyOfRange(12, wrapped.size))
    }
}
