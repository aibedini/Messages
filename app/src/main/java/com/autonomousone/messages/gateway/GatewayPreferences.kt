package com.autonomousone.messages.gateway

import android.content.Context
import android.content.SharedPreferences
import com.autonomousone.messages.BuildConfig
import com.autonomousone.messages.utils.SecureStore
import org.json.JSONArray

class GatewayPreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREF_NAME = "sms_gateway_prefs"
        // Marker prefix identifying values encrypted with the Android Keystore.
        private const val ENC_PREFIX = "enc:v1:"
        // ── LAN server keys ──
        private const val KEY_ENABLED = "gateway_enabled"
        private const val KEY_PORT = "gateway_port"
        private const val KEY_API_KEY = "gateway_api_key"
        private const val KEY_WEBHOOK_URL = "gateway_webhook_url"
        private const val KEY_WEBHOOK_SECRET = "gateway_webhook_secret"
        private const val KEY_AUTO_START = "gateway_auto_start"
    /**
     * The USER's intent that the gateway run — persisted, survives reboot,
     * and NEVER touched by runtime teardown (network loss, stopServer,
     * service death). `KEY_ENABLED` below is the runtime mirror derived by
     * ConnectionSupervisor; components gate transmission on it, but only the
     * supervisor writes it. This split is what makes recovery possible:
     * after a crash/reboot the service reads desired=true and rebuilds.
     */
    private const val KEY_DESIRED_ENABLED = "gateway_desired_enabled"
        private const val KEY_BIND_ALL = "gateway_bind_all_interfaces"
        private const val KEY_CONSENT_VERSION = "gateway_consent_version"
        private const val KEY_CONSENT_ACCEPTED_AT = "gateway_consent_accepted_at"
        // ── Cloud backend keys ──
        private const val KEY_BACKEND_URL = "cloud_backend_url"
        private const val KEY_GATEWAY_ID = "cloud_gateway_id"
        private const val KEY_IDENTITY_REGISTERED = "cloud_identity_registered"
        private const val KEY_GATEWAY_TOKEN = "cloud_gateway_token"
        private const val KEY_LAST_HEARTBEAT = "cloud_last_heartbeat"
        private const val KEY_IS_REGISTERED = "cloud_is_registered"
        private const val KEY_DEVICE_FALLBACK_ID = "cloud_device_fallback_id"
        private const val KEY_REGISTRATION_SECRET = "cloud_registration_secret"
        // ── GMweb pull bridge (outbound-only; no tunnel needed) ──
        private const val KEY_GMWEB_URL = "gmweb_url"
        // ── Idempotency store ──
        private const val KEY_SENT_EVENT_IDS = "cloud_sent_event_ids"
        private const val MAX_EVENT_IDS = 500

        const val DEFAULT_PORT = 8080
        const val CURRENT_CONSENT_VERSION = 1
    }

    // ── LAN server ──

    /**
     * RUNTIME state — true while the gateway components are actually up.
     * Derived by ConnectionSupervisor; nothing else writes it. Transmission
     * gates (HeartbeatManager, OutboxPoller) check this so a supervisor-
     * stopped gateway stops sending even if the service still lives.
     */
    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** USER intent — see KEY_DESIRED_ENABLED. start()/stop() flip this. */
    var gatewayDesiredEnabled: Boolean
        get() = prefs.getBoolean(KEY_DESIRED_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_DESIRED_ENABLED, value).apply()

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    /**
     * Local REST API key. Encrypted at rest with the Android Keystore;
     * legacy plaintext values are migrated transparently on first read.
     */
    var apiKey: String
        get() {
            val stored = prefs.getString(KEY_API_KEY, null)
            if (!stored.isNullOrBlank()) {
                return if (stored.startsWith(ENC_PREFIX)) {
                    SecureStore.decrypt(stored.removePrefix(ENC_PREFIX)) ?: generateNewApiKey()
                } else {
                    storeEncrypted(KEY_API_KEY, stored)
                    stored
                }
            }
            return generateNewApiKey()
        }
        set(value) = storeEncrypted(KEY_API_KEY, value)

    var webhookUrl: String
        get() = prefs.getString(KEY_WEBHOOK_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_WEBHOOK_URL, value).apply()

    /**
     * Optional shared secret used to HMAC-SHA256 sign outgoing webhook
     * payloads (sent as the X-Signature header). Encrypted at rest.
     */
    var webhookSecret: String
        get() {
            val stored = prefs.getString(KEY_WEBHOOK_SECRET, null)
            if (stored.isNullOrBlank()) return ""
            return if (stored.startsWith(ENC_PREFIX)) {
                SecureStore.decrypt(stored.removePrefix(ENC_PREFIX)) ?: ""
            } else {
                storeEncrypted(KEY_WEBHOOK_SECRET, stored)
                stored
            }
        }
        set(value) = storeEncrypted(KEY_WEBHOOK_SECRET, value.trim())

    var autoStartOnBoot: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START, value).apply()

    /**
     * When false (default) the REST server binds only to the detected LAN IPv4
     * address; when true it binds to all interfaces (0.0.0.0).
     */
    var bindAllInterfaces: Boolean
        get() = prefs.getBoolean(KEY_BIND_ALL, false)
        set(value) = prefs.edit().putBoolean(KEY_BIND_ALL, value).apply()

    /** Consent is versioned so material data-use changes require a fresh opt-in. */
    val hasGatewayConsent: Boolean
        get() = prefs.getInt(KEY_CONSENT_VERSION, 0) >= CURRENT_CONSENT_VERSION

    val gatewayConsentAcceptedAt: Long
        get() = prefs.getLong(KEY_CONSENT_ACCEPTED_AT, 0L)

    fun acceptGatewayConsent() {
        prefs.edit()
            .putInt(KEY_CONSENT_VERSION, CURRENT_CONSENT_VERSION)
            .putLong(KEY_CONSENT_ACCEPTED_AT, System.currentTimeMillis())
            .apply()
    }

    fun revokeGatewayConsent() {
        prefs.edit()
            .remove(KEY_CONSENT_VERSION)
            .remove(KEY_CONSENT_ACCEPTED_AT)
            .putBoolean(KEY_ENABLED, false)
            .putBoolean(KEY_DESIRED_ENABLED, false)
            .putBoolean(KEY_AUTO_START, false)
            .apply()
        clearCloudCredentials()
    }

    fun generateNewApiKey(): String {
        val newKey = generateApiKey()
        apiKey = newKey
        return newKey
    }

    private fun generateApiKey(): String {
        // 128 bits of entropy from a cryptographically strong RNG.
        return "gw_" + SecureStore.randomHex(16)
    }

    // ── Cloud backend ──

    /**
     * Production backend URL.
     * Default from BuildConfig (set to https://gaitway.autonomousone.in at build time).
     * HTTPS-only: insecure http:// values are rejected so the bearer token can
     * never be sent over plaintext HTTP.
     */
    var backendUrl: String
        get() = prefs.getString(KEY_BACKEND_URL, null)
            ?: BuildConfig.GATEWAY_BACKEND_URL
        set(value) {
            val v = value.trim().trimEnd('/')
            require(v.isEmpty() || v.startsWith("https://")) {
                "Backend URL must use HTTPS"
            }
            prefs.edit().putString(KEY_BACKEND_URL, v).apply()
        }

    /** The public gateway ID returned by the backend on registration. */
    var gatewayId: String
        get() = prefs.getString(KEY_GATEWAY_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GATEWAY_ID, value).apply()

    /**
     * True once the device enrolled its PR-05 publicKeys on the ADR-004
     * control plane (POST /api/v1/agent/identity, PR-11). Unlike the legacy
     * [isRegistered]/gatewayToken bookkeeping (issued by the retired
     * /api/gateways/register flow), this is the source of truth for "the
     * platform knows this device".
     */
    var identityRegistered: Boolean
        get() = prefs.getBoolean(KEY_IDENTITY_REGISTERED, false)
        set(value) = prefs.edit().putBoolean(KEY_IDENTITY_REGISTERED, value).apply()

    /**
     * SSOT for the stable per-device identity on the agent bridge (PR-05/08b):
     * ANDROID_ID when available, else a persisted random hex
     * ([deviceFallbackId]). Identity enrollment MUST key on this value so the
     * backend binds exactly one identity per physical device.
     */
    fun stableDeviceId(context: Context): String = try {
        android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID,
        )?.takeIf { it.isNotBlank() } ?: deviceFallbackId
    } catch (e: Exception) {
        deviceFallbackId
    }

    /**
     * The wire identity used by agent-bridge callers (command claim, event
     * upload, X-Agent-Auth signing): the registered [gatewayId], falling back
     * to [stableDeviceId] before the first successful registration.
     */
    fun agentDeviceId(context: Context): String = gatewayId.ifBlank { stableDeviceId(context) }

    /** Bearer token issued by the backend. Encrypted at rest with the Android Keystore. */
    var gatewayToken: String
        get() {
            val stored = prefs.getString(KEY_GATEWAY_TOKEN, null)
            if (stored.isNullOrBlank()) return ""
            return if (stored.startsWith(ENC_PREFIX)) {
                SecureStore.decrypt(stored.removePrefix(ENC_PREFIX)) ?: ""
            } else {
                storeEncrypted(KEY_GATEWAY_TOKEN, stored)
                stored
            }
        }
        set(value) = storeEncrypted(KEY_GATEWAY_TOKEN, value.trim())

    var lastHeartbeatAt: Long
        get() = prefs.getLong(KEY_LAST_HEARTBEAT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_HEARTBEAT, value).apply()

    var isRegistered: Boolean
        get() = prefs.getBoolean(KEY_IS_REGISTERED, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_REGISTERED, value).apply()

    /** Stable random fallback device ID used when ANDROID_ID is unavailable. */
    var deviceFallbackId: String
        get() {
            val existing = prefs.getString(KEY_DEVICE_FALLBACK_ID, null)
            if (!existing.isNullOrBlank()) return existing
            val fresh = SecureStore.randomHex(16)
            prefs.edit().putString(KEY_DEVICE_FALLBACK_ID, fresh).apply()
            return fresh
        }
        private set(value) = prefs.edit().putString(KEY_DEVICE_FALLBACK_ID, value).apply()

    /**
     * Optional pairing secret the backend must require on POST /api/gateways/register
     * (sent as the X-Registration-Secret header) so arbitrary parties cannot register
     * a gateway or invalidate an existing registration. Encrypted at rest.
     */
    var registrationSecret: String
        get() {
            val stored = prefs.getString(KEY_REGISTRATION_SECRET, null)
            if (stored.isNullOrBlank()) return ""
            return if (stored.startsWith(ENC_PREFIX)) {
                SecureStore.decrypt(stored.removePrefix(ENC_PREFIX)) ?: ""
            } else {
                storeEncrypted(KEY_REGISTRATION_SECRET, stored)
                stored
            }
        }
        set(value) = storeEncrypted(KEY_REGISTRATION_SECRET, value.trim())

    fun clearCloudCredentials() {
        prefs.edit()
            .remove(KEY_GATEWAY_ID)
            .remove(KEY_GATEWAY_TOKEN)
            .putBoolean(KEY_IS_REGISTERED, false)
            .apply()
    }

    /**
     * GMweb-API base URL for the pull bridge (e.g. https://gmweb.example.com).
     * Outbound-only HTTPS: the phone dials the server, never the reverse, so
     * changing mobile IPs / firewalls need no tunnel. Empty = bridge disabled.
     */
    var gmwebUrl: String
        get() = prefs.getString(KEY_GMWEB_URL, "") ?: ""
        set(value) {
            val v = value.trim().trimEnd('/')
            require(v.isEmpty() || v.startsWith("https://")) { "GMweb URL must use HTTPS" }
            prefs.edit().putString(KEY_GMWEB_URL, v).apply()
        }

    // ── Idempotency: track sent event IDs (insertion-ordered FIFO trim) ──

    fun hasEventBeenSent(eventId: String): Boolean {
        return getSentEventIds().contains(eventId)
    }

    fun markEventSent(eventId: String) {
        val ids = getSentEventIds().toMutableList()
        ids.removeAll { it == eventId } // re-inserted at tail so retried events stay "newest"
        ids.add(eventId)
        while (ids.size > MAX_EVENT_IDS) ids.removeAt(0) // drop oldest first
        prefs.edit().putString(KEY_SENT_EVENT_IDS, JSONArray(ids).toString()).apply()
    }

    private fun getSentEventIds(): List<String> {
        return try {
            val arr = JSONArray(prefs.getString(KEY_SENT_EVENT_IDS, "[]") ?: "[]")
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ── Encryption helpers ──

    private fun storeEncrypted(key: String, plainValue: String) {
        val enc = SecureStore.encrypt(plainValue)
        // v2.6.10 fail-closed: secrets (API key, webhook secret, bearer token,
        // registration secret) must NEVER degrade to plaintext when the
        // Keystore is unavailable — that converts a crypto incident into a
        // data-at-rest incident. If encryption fails, the secret is not
        // persisted; the accessor re-encrypts any legacy plaintext values it
        // finds on read, and callers see an empty/blank value until the
        // Keystore recovers or the secret is re-issued (fail closed).
        checkNotNull(enc) {
            "SecureStore (Android Keystore) unavailable — refusing to persist $key as plaintext"
        }
        prefs.edit().putString(key, ENC_PREFIX + enc).apply()
    }
}
