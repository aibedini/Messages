package com.autonomousone.messages.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * At-rest protection for `suggestion_cards.payload_ciphertext` (XR-GA-017).
 *
 * ## Why a dedicated key
 *
 * The existing [ConversationKeyVault] wraps CKE material with a per-epoch key id and caches unwrapped
 * copies; suggestion payloads are ephemeral and keyed per conversation, so reusing that vault would
 * either grow its cache one entry per card or require inventing a new epoch per row. This follows the
 * established pattern instead (see `SimRefSecret`): **one purpose-named AndroidKeystore key that does
 * nothing else**, so it can be rotated or dropped without touching message crypto.
 *
 * ## The format
 *
 * ```text
 * [version:1][iv:12][AES-256-GCM ciphertext…]
 * ```
 *
 * The associated data binds the blob to its conversation (`thread:<id>`), so a sealed payload copied
 * from one thread's row cannot be opened in another — a database-level mix-up fails closed instead of
 * rendering somebody else's suggestions.
 *
 * ## Loss behaviour
 *
 * AndroidKeystore keys do not survive a restore onto a new device. [open] therefore returns **null**
 * rather than throwing: the caller deletes the undecryptable row and falls back to the existing quick
 * replies, which is exactly what a user should see when the cards cannot be read. Losing a suggestion
 * is recoverable; crashing the conversation screen or rendering garbage is not.
 *
 * Not exercised by JVM unit tests (AndroidKeystore does not exist there); the *visibility* logic that
 * consumes cards is pure and tested directly.
 */
object SuggestionCardVault {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    /** Purpose-named so a future reader knows what it protects and that it is safe to rotate alone. */
    private const val KEY_ALIAS = "messages_suggestion_cards_aes_v1"

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val VERSION: Byte = 1

    @Volatile
    private var cachedKey: SecretKey? = null

    @Synchronized
    private fun key(create: Boolean): SecretKey {
        cachedKey?.let { return it }
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { cachedKey = it; return it }
        check(create) { "suggestion card key unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    // No user-authentication requirement: cards may be sealed by a background command
                    // while the phone is locked, and rendered on the next unlock.
                    .build()
            )
        }.generateKey().also { cachedKey = it }
    }

    /** The associated data for one conversation's cards. */
    fun aad(threadId: Long): ByteArray = "thread:$threadId".toByteArray(Charsets.UTF_8)

    /** Seal [plaintext] for [threadId]. Throws when the Keystore key cannot be created. */
    fun seal(plaintext: ByteArray, threadId: Long): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        cipher.updateAAD(aad(threadId))
        val iv = cipher.iv
        val body = cipher.doFinal(plaintext)
        return ByteArray(1 + iv.size + body.size).also { out ->
            out[0] = VERSION
            iv.copyInto(out, destinationOffset = 1)
            body.copyInto(out, destinationOffset = 1 + iv.size)
        }
    }

    /** Open a sealed payload, or null when it is corrupt or its key is gone. */
    fun open(blob: ByteArray, threadId: Long): ByteArray? {
        if (blob.size <= 1 + IV_BYTES + TAG_BITS / 8) return null
        if (blob[0] != VERSION) return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(create = false),
                GCMParameterSpec(TAG_BITS, blob.copyOfRange(1, 1 + IV_BYTES))
            )
            cipher.updateAAD(aad(threadId))
            cipher.doFinal(blob.copyOfRange(1 + IV_BYTES, blob.size))
        }.getOrNull()
    }
}
