package com.autonomousone.messages.messaging

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.autonomousone.messages.utils.DiagnosticLog
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * The device-local HMAC key behind [SimRef].
 *
 * ## Why a separate key and not an existing one
 *
 * This project already owns three AndroidKeyStore key pairs ([com.autonomousone.messages.gateway
 * .DeviceIdentity]): an EC trust root, an EC operational signing key and an EC key-agreement key.
 * None of them can serve here. EC keys are asymmetric — they can sign and agree, not compute a MAC —
 * and their private material is deliberately non-extractable, so they cannot be repurposed as a
 * symmetric secret without changing what they mean. Reusing a *device secret* where one exists is the
 * right instinct, but "the Keystore holds keys" is not the same as "a suitable symmetric secret
 * exists", and inventing a way to squeeze an HMAC out of an EC key would be a new cryptographic
 * construction — exactly the "new crypto authority" this design is told to avoid. So the choice
 * here is the narrower one: **one dedicated, purpose-named HMAC key that does nothing else.**
 *
 * ## Properties this guarantees
 *
 *  - **Local only.** The key is generated in and never leaves the AndroidKeyStore. It is not derived
 *    from anything server-side, not uploaded, not placed in any E2EE payload, and not logged.
 *  - **Durable.** AndroidKeyStore entries survive process death, reboot and app upgrade, which is what
 *    makes a stored `simRef` still resolve after the phone restarts.
 *  - **Least authority.** `PURPOSE_SIGN` + `DIGEST_SHA256` + `HmacSHA256`, with **no user
 *    authentication requirement**: this key must work while the phone is locked, because a background
 *    send may depend on resolving a preference then.
 *
 * ## Loss behaviour
 *
 * If the key is gone (Keystore reset, restore onto a new device, OEM wipe) a **new** one is
 * generated — refusing to work would be worse, since the app could then never identify a SIM at all.
 * The consequence is deliberate and safe: previously stored refs no longer resolve, so a conversation
 * with a preference reports `PREFERRED_SIM_UNAVAILABLE` until the user reselects a line. It never
 * falls back to a slot, because a slot is not identity.
 *
 * Nothing here is exercised by JVM unit tests — AndroidKeyStore does not exist there. The derivation
 * logic is tested through [SimRefProvider]'s injectable MAC instead.
 */
internal object SimRefSecret {

    private const val TAG_SIM_REF = "SIM_REF"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    /** Purpose-named so a future reader can tell what it is for and that it is safe to rotate alone. */
    const val KEY_ALIAS = "messages_sim_ref_hmac_v1"

    private const val MAC_ALGORITHM = "HmacSHA256"

    /** The in-process handle. The key material itself stays inside the Keystore. */
    @Volatile
    private var cached: SecretKey? = null

    /**
     * `HMAC-SHA256(deviceSecret, message)`.
     *
     * @throws IllegalStateException when the Keystore cannot provide a key. Failing loudly is correct:
     *   a ref derived from a *substitute* key would silently not match the refs already stored, which
     *   is a wrong answer rather than a missing one.
     */
    fun hmacSha256(message: String): ByteArray {
        val key = key()
        val mac = Mac.getInstance(MAC_ALGORITHM)
        mac.init(key)
        return mac.doFinal(message.toByteArray(Charsets.UTF_8))
    }

    /** Whether a key currently exists (or can be created). For diagnostics only. */
    fun isAvailable(): Boolean = try {
        key(); true
    } catch (e: Exception) {
        DiagnosticLog.event(TAG_SIM_REF, "state=UNAVAILABLE detail=keystore_key_unavailable")
        false
    }

    /** Test-only: drop the cached handle so the next call re-reads the Keystore. */
    internal fun resetForTest() {
        cached = null
    }

    private fun key(): SecretKey {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val loaded = loadOrCreate()
            cached = loaded
            return loaded
        }
    }

    private fun loadOrCreate(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { entry ->
            return entry.secretKey
        }
        return generate(keyStore)
    }

    private fun generate(keyStore: KeyStore): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setDigests(KeyProperties.DIGEST_SHA256)
                // No setUserAuthenticationRequired: a background send must be able to resolve a
                // preference while the device is locked.
                .build()
        )
        val generated = generator.generateKey()
        DiagnosticLog.event(TAG_SIM_REF, "state=CREATED alias=$KEY_ALIAS")
        // The alias is public metadata; the key material is not, and never is logged.
        return generated
    }
}
