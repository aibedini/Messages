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
 * v2.6.21 (PR-11): registration now targets the ADR-004 control plane —
 * POST /api/v1/agent/identity (GMweb) — instead of the retired
 * /api/gateways/register endpoint. It is device-key bootstrap per PR-08b:
 * the payload carries the PR-05 publicKeys block (idempotent upsert keyed by
 * deviceId) and the response {ok:true} carries NO gatewayId/token —
 * authorization is per-device ECDSA (X-Agent-Auth) from then on, with the
 * shared device key (X-API-Key) accepted only until a device enrolls.
 * `gatewayToken`/`isRegistered` remain as local bookkeeping so the legacy
 * heartbeat flow keeps working unchanged.
 */
class RegistrationManager(
    private val context: Context,
    private val prefs: GatewayPreferences,
    private val client: BackendClient,
    private val onLog: (String) -> Unit = {},
) {

    companion object {
        private const val TAG = "REGISTRATION_MGR"

        /** ADR-004 control plane identity enrollment (PR-08b bootstrap). */
        private const val IDENTITY_PATH = "/api/v1/agent/identity"

        /**
         * Placeholder bearer token for the legacy isRegistered/gatewayToken
         * bookkeeping — the identity flow issues no server token. Heartbeat
         * requests against the v1 backend still carry it as a bearer header
         * (harmless); v2 callers ignore it entirely.
         */
        private const val LEGACY_TOKEN_SENTINEL = "identity-enrolled-v2"
    }

    /**
     * Ensure the gateway is registered.
     *
     * Returns true if registration succeeded (or was already done).
     * Returns false if it failed — the caller should retry with backoff.
     */
    suspend fun ensureRegistered(): Boolean = withContext(Dispatchers.IO) {
        if (!prefs.hasGatewayConsent) return@withContext false
        // If we already have credentials, assume registered. v2.6.21: the
        // identity flow sets isRegistered + the sentinel token together, so
        // either marker means "enrolled"; heartbeat will detect staleness.
        if (prefs.isRegistered && (prefs.gatewayToken.isNotBlank() || prefs.identityRegistered)) {
            return@withContext true
        }

        register()
    }

    private fun buildRegistrationPayload(): JSONObject {
        val deviceId = prefs.stableDeviceId(context)
        return JSONObject().apply {
            put("deviceId", deviceId)
            put("protocolVersion", 1)
            // BLOCKER 3 (ADR-007): this device IS the primary trust device —
            // the only role allowed to approve web pairings. Explicit, not
            // implicit: the server's approve gate checks this role.
            put("role", "PRIMARY_TRUST_AGENT")
            // Extra descriptive fields are tolerated by the schema (it only
            // requires deviceId + publicKeys) and aid server-side diagnostics.
            put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("appVersion", BuildConfig.APP_VERSION)
            put("androidVersion", Build.VERSION.RELEASE)
            // PR-05 (ADR-001): enroll the device's PUBLIC keys. Keystore
            // enrollment is idempotent; the raw uncompressed EC points
            // (0x04||X||Y) are Base64 for the wire. Private material NEVER
            // leaves the Keystore (§16/§17/§24).
            val identity = DeviceIdentity.ensureEnrolled()
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
        onLog("☁️ Enrolling device identity with control plane...")
        Log.d(TAG, "Enrolling identity with ${prefs.backendUrl}")

        val payload = buildRegistrationPayload()
        // PR-08b bootstrap: /api/v1/agent/identity is device-key authenticated.
        // The shared device key (X-API-Key) is the bootstrap credential accepted
        // until this device enrolls; X-Agent-Auth signing then takes over.
        val headers = buildMap {
            val secret = prefs.registrationSecret
            if (secret.isNotBlank()) put("X-Registration-Secret", secret)
        }
        val result = client.post(
            IDENTITY_PATH,
            payload,
            authenticated = false,
            extraHeaders = headers + mapOf("X-API-Key" to prefs.apiKey),
        )

        when (result) {
            is BackendClient.Result.Success -> {
                try {
                    // v2.6.21 (PR-11): the identity endpoint answers {ok:true} —
                    // no server-issued gatewayId/token exists on the ADR-004
                    // control plane. gatewayId collapses onto the SSOT
                    // stableDeviceId so every agent-bridge caller (command
                    // claim, X-Agent-Auth signing, events) binds the SAME
                    // identity the server just enrolled.
                    JSONObject(result.data).optBoolean("ok", false)
                    val deviceId = prefs.stableDeviceId(context)
                    prefs.gatewayId = deviceId
                    prefs.identityRegistered = true
                    // Legacy bookkeeping: keep the heartbeat flow working with
                    // no server-issued token (marked with the v2 sentinel).
                    prefs.gatewayToken = LEGACY_TOKEN_SENTINEL
                    prefs.isRegistered = true

                    onLog("✅ Device identity enrolled: $deviceId")
                    Log.i(TAG, "Identity enrolled as $deviceId")

                    // PR-11 hotfix: rescue anything dead-lettered while we were
                    // mid-enrollment (401 unknown_device from the identity
                    // race). Requeue with attemptCount intact — the uploader
                    // (now unblocked) redelivers them in normal order.
                    val rescued = com.autonomousone.messages.repository
                        .GatewaySyncRepository(com.autonomousone.messages.data.MessagesDatabase.get(context))
                        .recoverDeadLetter()
                    if (rescued > 0) {
                        onLog("♻️ $rescued dead-lettered event(s) rescued post-enrollment")
                        Log.i(TAG, "rescued $rescued DEAD_LETTER rows after enrollment")
                    }
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

    private fun getDeviceId(): String = prefs.stableDeviceId(context)
}
