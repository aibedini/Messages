package com.autonomousone.messages.gateway

import android.content.Context
import android.os.Build
import android.util.Log
import com.autonomousone.messages.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Handles first-time registration and re-registration with the cloud backend.
 *
 * Registration is idempotent on the backend side (keyed by deviceId).
 * If the token is lost from SharedPreferences, a re-registration is triggered
 * automatically — the backend issues a new token and the old one becomes invalid.
 */
class RegistrationManager(
    private val context: Context,
    private val prefs: GatewayPreferences,
    private val client: BackendClient,
    private val onLog: (String) -> Unit = {},
) {

    companion object {
        private const val TAG = "REGISTRATION_MGR"
    }

    /**
     * Ensure the gateway is registered.
     *
     * Returns true if registration succeeded (or was already done).
     * Returns false if it failed — the caller should retry with backoff.
     */
    suspend fun ensureRegistered(): Boolean = withContext(Dispatchers.IO) {
        if (!prefs.hasGatewayConsent) return@withContext false
        // If we already have credentials, assume registered (heartbeat will detect stale token)
        if (prefs.isRegistered && prefs.gatewayToken.isNotBlank()) {
            return@withContext true
        }

        register()
    }

    private fun buildRegistrationPayload(): JSONObject {
        val deviceId = getDeviceId()
        return JSONObject().apply {
            put("deviceId", deviceId)
            put("name", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("appVersion", BuildConfig.APP_VERSION)
            put("androidVersion", Build.VERSION.RELEASE)
            put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}")
            // PR-05 (ADR-001): register the device's PUBLIC keys alongside the
            // legacy registration. Keystore enrollment is idempotent; the raw
            // uncompressed EC points (0x04||X||Y) are Base64 for the wire.
            // Private material NEVER leaves the Keystore (§16/§17/§24).
            val identity = DeviceIdentity.ensureEnrolled()
            put("protocolVersion", 1)
            put("publicKeys", JSONObject().apply {
                put(
                    "trustRoot",
                    android.util.Base64.encodeToString(identity.trustRootPublicPoint, android.util.Base64.NO_WRAP)
                )
                put(
                    "signing",
                    android.util.Base64.encodeToString(identity.signingPublicPoint, android.util.Base64.NO_WRAP)
                )
                put(
                    "encryption",
                    android.util.Base64.encodeToString(identity.encryptionPublicPoint, android.util.Base64.NO_WRAP)
                )
            })
        }
    }

    suspend fun register(): Boolean = withContext(Dispatchers.IO) {
        if (!prefs.hasGatewayConsent) {
            onLog("Cloud registration blocked: gateway consent is required")
            return@withContext false
        }
        onLog("☁️ Registering with cloud backend...")
        Log.d(TAG, "Registering with ${prefs.backendUrl}")

        val payload = buildRegistrationPayload()
        // Pairing secret (if configured) so the backend can reject unauthenticated
        // registration attempts that would hijack or invalidate this gateway.
        val headers = buildMap {
            val secret = prefs.registrationSecret
            if (secret.isNotBlank()) put("X-Registration-Secret", secret)
        }
        val result = client.post("/api/gateways/register", payload, authenticated = false, extraHeaders = headers)

        when (result) {
            is BackendClient.Result.Success -> {
                try {
                    val json = JSONObject(result.data)
                    val gatewayId = json.getString("gatewayId")
                    val token = json.getString("token")

                    prefs.gatewayId = gatewayId
                    prefs.gatewayToken = token
                    prefs.isRegistered = true

                    onLog("✅ Registered as gateway: $gatewayId")
                    Log.i(TAG, "Registered as $gatewayId")
                    true
                } catch (e: Exception) {
                    onLog("❌ Registration parse error: ${e.message}")
                    Log.e(TAG, "Parse error", e)
                    false
                }
            }
            is BackendClient.Result.Failure -> {
                val msg = "Registration failed: ${result.error}"
                onLog("❌ $msg")
                Log.e(TAG, msg)
                false
            }
        }
    }

    private fun getDeviceId(): String {
        // Use Android ID as the stable device identifier.
        // This is unique per app signing key, resetting on factory reset.
        // Fallback: a random ID generated once and persisted in encrypted prefs
        // (Build.SERIAL / Build.ID are deprecated or guessable).
        return try {
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID,
            )?.takeIf { it.isNotBlank() } ?: prefs.deviceFallbackId
        } catch (e: Exception) {
            prefs.deviceFallbackId
        }
    }
}
