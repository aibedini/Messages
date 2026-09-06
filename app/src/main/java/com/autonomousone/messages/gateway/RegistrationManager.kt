package com.autonomousone.messages.gateway

import android.content.Context
import android.os.Build
import android.util.Log
import com.autonomousone.messages.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.autonomousone.messages.security.PrimaryTrustRoot
import java.security.MessageDigest

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
    data class PrimaryStatus(
        val state: State,
        val deviceId: String? = null,
        val trustRootFingerprint: String? = null,
        val lastVerifiedAt: Long? = null,
        val reason: String? = null,
    ) {
        enum class State { VERIFIED, NOT_ENROLLED, MISMATCH, OFFLINE }
    }
    @Volatile
    var lastFailureReason: String? = null
        private set

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

        internal fun registrationHeaders(identityRegistered: Boolean, apiKey: String, registrationSecret: String): Map<String, String> = buildMap {
            if (registrationSecret.isNotBlank()) put("X-Registration-Secret", registrationSecret)
            if (!identityRegistered) put("X-API-Key", apiKey)
        }
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

    internal fun buildRegistrationPayload(): JSONObject {
        val deviceId = prefs.stableDeviceId(context)
        return JSONObject().apply {
            put("deviceId", deviceId)
            put("protocolVersion", 1)
            // BLOCKER 3 (ADR-007): this device IS the primary trust device —
            // the only role allowed to approve web pairings. Explicit, not
            // implicit: the server's approve gate checks this role.
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
            // FIX 4 (review): protocol wire format for public EC keys is
            // DER SPKI → Base64 (was raw uncompressed point). The server
            // verifier also tolerates raw points during transition.
            fun toSpkiB64(rawPoint: ByteArray): String {
                val kf = java.security.KeyFactory.getInstance("EC")
                val pub = kf.generatePublic(
                    java.security.spec.ECPublicKeySpec(
                        java.security.spec.ECPoint(
                            java.math.BigInteger(1, rawPoint.copyOfRange(1, 33)),
                            java.math.BigInteger(1, rawPoint.copyOfRange(33, 65))
                        ),
                        java.security.spec.ECGenParameterSpec("secp256r1").let {
                            java.security.AlgorithmParameters.getInstance("EC").apply {
                                init(it)
                            }.getParameterSpec(java.security.spec.ECParameterSpec::class.java)
                        }
                    )
                )
                return android.util.Base64.encodeToString(pub.encoded, android.util.Base64.NO_WRAP)
            }
            put("publicKeys", JSONObject().apply {
                put("trustRoot", PrimaryTrustRoot.publicKeyBase64())
                put("signing", toSpkiB64(identity.signingPublicPoint))
                put("encryption", toSpkiB64(identity.encryptionPublicPoint))
            })
        }
    }

    suspend fun register(): Boolean = registerInternal(requireConsent = true)

    /** Explicit phone onboarding; independent of browser pairing. */
    suspend fun enrollPrimary(claim: JSONObject): Boolean {
        val origin = claim.getString("apiOrigin")
        if (!registerInternal(requireConsent = false, baseUrlOverride = origin, setupClaim = claim)) return false
        return verifyPrimaryStatus(origin).state == PrimaryStatus.State.VERIFIED
    }

    suspend fun verifyPrimaryStatus(baseUrlOverride: String? = null): PrimaryStatus = withContext(Dispatchers.IO) {
        if (!prefs.identityRegistered) return@withContext PrimaryStatus(
            PrimaryStatus.State.NOT_ENROLLED, reason = "local_identity_not_enrolled",
        )
        val path = "/api/v1/agent/status"
        val deviceId = prefs.stableDeviceId(context)
        val base = (baseUrlOverride ?: prefs.gmwebUrl).trimEnd('/')
        if (!base.startsWith("https://")) return@withContext PrimaryStatus(
            PrimaryStatus.State.OFFLINE, reason = "trusted_https_origin_required",
        )
        var conn: java.net.HttpURLConnection? = null
        try {
            conn = java.net.URL(base + path).openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            check(AgentAuth.sign(conn, deviceId, path, "GET", ByteArray(0))) { "agent_signing_failed" }
            val status = conn.responseCode
            if (status == 404) return@withContext PrimaryStatus(PrimaryStatus.State.NOT_ENROLLED, reason = "identity_not_found")
            if (status !in 200..299) return@withContext PrimaryStatus(
                PrimaryStatus.State.OFFLINE, reason = "http_$status",
            )
            val response = conn.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            if (!response.optBoolean("enrolled")) return@withContext PrimaryStatus(
                PrimaryStatus.State.NOT_ENROLLED, reason = "identity_not_enrolled",
            )
            val serverDeviceId = response.optString("deviceId")
            val serverRoot = response.optString("trustRootFingerprint")
            val localRoot = fingerprint(PrimaryTrustRoot.publicKeyBase64())
            val verified = response.optString("role") == "PRIMARY_TRUST_AGENT" &&
                response.optBoolean("isPrimary") && serverDeviceId == deviceId && serverRoot == localRoot
            PrimaryStatus(
                state = if (verified) PrimaryStatus.State.VERIFIED else PrimaryStatus.State.MISMATCH,
                deviceId = serverDeviceId,
                trustRootFingerprint = serverRoot,
                lastVerifiedAt = System.currentTimeMillis(),
                reason = if (verified) null else "server_identity_or_trust_root_mismatch",
            )
        } catch (error: Exception) {
            PrimaryStatus(PrimaryStatus.State.OFFLINE, reason = error.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }

    private fun fingerprint(base64Spki: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(android.util.Base64.decode(base64Spki, android.util.Base64.DEFAULT))
            .joinToString("") { "%02x".format(it) }

    private suspend fun registerInternal(
        requireConsent: Boolean,
        baseUrlOverride: String? = null,
        setupClaim: JSONObject? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        if (requireConsent && !prefs.hasGatewayConsent) {
            onLog("Cloud registration blocked: gateway consent is required")
            return@withContext false
        }
        onLog("☁️ Enrolling device identity with control plane...")
        Log.d(TAG, "Enrolling identity with ${prefs.backendUrl}")

        val payload = buildRegistrationPayload()
        val path = if (setupClaim != null) "/api/v1/primary-enrollment" else IDENTITY_PATH
        if (setupClaim != null) {
            require(setupClaim.getString("protocol") == com.autonomousone.messages.security.PairingProtocol.PROTOCOL)
            require(setupClaim.getString("kind") == "PRIMARY_SETUP")
            require(setupClaim.getLong("expiresAt") > System.currentTimeMillis())
            require(com.autonomousone.messages.security.PairingEndpointResolver.originMatches(context, setupClaim.getString("apiOrigin")))
            payload.put("claim", setupClaim.getString("claim"))
            payload.put("apiOrigin", setupClaim.getString("apiOrigin"))
            val bytes = com.autonomousone.messages.security.PairingProtocol.enrollment(payload).toByteArray(Charsets.UTF_8)
            payload.put("signature", android.util.Base64.encodeToString(DeviceIdentity.signWithOperationalKey(bytes), android.util.Base64.NO_WRAP))
            payload.put("rootSignature", PrimaryTrustRoot.signBytes(bytes))
        }
        val headers = if (setupClaim != null) emptyMap() else registrationHeaders(prefs.identityRegistered, prefs.apiKey, prefs.registrationSecret)
        val signer = if (setupClaim == null && prefs.identityRegistered) {
            { conn: java.net.HttpURLConnection, bodyBytes: ByteArray ->
                AgentAuth.sign(conn, prefs.stableDeviceId(context), path, "POST", bodyBytes)
            }
        } else null
        val result = client.post(
            path,
            payload,
            authenticated = false,
            extraHeaders = headers,
            signer = signer,
            baseUrlOverride = baseUrlOverride,
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
                    check(result.httpStatus == 200 && JSONObject(result.data).optBoolean("ok", false)) {
                        "identity endpoint did not confirm enrollment"
                    }
                    if (setupClaim != null) check(JSONObject(result.data).optString("role") == "PRIMARY_TRUST_AGENT")
                    val deviceId = prefs.stableDeviceId(context)
                    prefs.gatewayId = deviceId
                    prefs.identityRegistered = true
                    // Legacy bookkeeping: keep the heartbeat flow working with
                    // no server-issued token (marked with the v2 sentinel).
                    prefs.gatewayToken = LEGACY_TOKEN_SENTINEL
                    prefs.isRegistered = true
                    lastFailureReason = null

                    onLog("✅ Device identity enrolled: ${deviceId.take(8)}…")
                    Log.i(TAG, "Identity enrolled as ${deviceId.take(8)}…")

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
                lastFailureReason = result.error
                val msg = "Registration failed: ${result.error}"
                onLog("❌ $msg")
                Log.e(TAG, msg)
                false
            }
        }
    }

    private fun getDeviceId(): String = prefs.stableDeviceId(context)
}
