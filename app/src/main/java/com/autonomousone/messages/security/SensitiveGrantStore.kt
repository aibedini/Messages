package com.autonomousone.messages.security

import android.content.Context

/**
 * ADR-006 Amendment — per-device sensitive message grants.
 *
 * Policy model: LOCAL_ONLY (no grant) / SELECTED_DEVICES (grants stored
 * here) / ALL_TRUSTED_DEVICES (every linked device gets the category).
 *
 * - Grants are ANDROID-OWNED: the web device can never change them silently;
 *   changes go through biometric-confirmed DEVICE_CAPABILITIES_CHANGED
 *   (Trust Root signature, trustSequence++).
 * - A device without the category grant never receives the sensitive DEK
 *   grant — ciphertext may exist, decryption is cryptographically blocked.
 */
object SensitiveGrantStore {

    private const val PREFS = "sensitive_device_grants"

    /** Categories exactly matching certificate capabilities. */
    val CATEGORIES = listOf(
        "READ_OTP",
        "READ_BANK_SECURITY",
        "READ_PASSWORD_RESET",
        "READ_AUTH_CODES",
        "READ_FINANCIAL_NOTIFICATIONS"
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** "device:category" -> true. Default false (privacy-first). */
    fun hasGrant(context: Context, deviceId: String, category: String): Boolean =
        prefs(context).getBoolean("$deviceId:$category", false)

    fun setGrant(context: Context, deviceId: String, category: String, granted: Boolean) {
        require(category in CATEGORIES) { "unknown sensitive category: $category" }
        prefs(context).edit().putBoolean("$deviceId:$category", granted).apply()
    }

    /** All enabled categories for a device (mirrors its certificate grants). */
    fun grantsFor(context: Context, deviceId: String): Set<String> =
        CATEGORIES.filterTo(mutableSetOf()) { hasGrant(context, deviceId, it) }

    /** Persist the grants chosen during pairing confirmation. */
    fun savePairingGrants(
        context: Context,
        deviceId: String,
        grantedCapabilities: Collection<String>
    ) {
        val editor = prefs(context).edit()
        for (category in CATEGORIES) {
            editor.putBoolean("$deviceId:$category", category in grantedCapabilities)
        }
        editor.apply()
    }
}
