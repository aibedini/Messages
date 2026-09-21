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
        /** @deprecated legacy, kept only for rollback; the origin key below is authoritative. */
        private const val KEY_BACKEND_URL = "cloud_backend_url"
        private const val KEY_GATEWAY_ID = "cloud_gateway_id"
        private const val KEY_IDENTITY_REGISTERED = "cloud_identity_registered"
        private const val KEY_GATEWAY_TOKEN = "cloud_gateway_token"
        private const val KEY_LAST_HEARTBEAT = "cloud_last_heartbeat"
        private const val KEY_IS_REGISTERED = "cloud_is_registered"
        private const val KEY_DEVICE_FALLBACK_ID = "cloud_device_fallback_id"
        private const val KEY_REGISTRATION_SECRET = "cloud_registration_secret"
        // ── GMweb pull bridge (outbound-only; no tunnel needed) ──
        const val DEFAULT_CONTROL_PLANE_SENDS = true
        private const val KEY_CP_SENDS = "gateway_control_plane_sends_enabled"
        /** @deprecated legacy, kept only for the one-way migration; see [migrateLegacyServerConfigOnce]. */
        private const val KEY_GMWEB_URL = "gmweb_url"

        /**
         * v3.4.6 SSOT: the ONE GMweb origin. Every GMweb route — control plane and pull
         * bridge alike — is derived from it.
         */
        private const val KEY_SERVER_ORIGIN = "gmweb_server_origin"

        /** Set once the legacy `gmweb_url`/`cloud_backend_url` pair has been folded in. */
        private const val KEY_SERVER_MIGRATED = "gmweb_origin_migrated_v1"
        // ── Idempotency store ──
        private const val KEY_SENT_EVENT_IDS = "cloud_sent_event_ids"
        private const val MAX_EVENT_IDS = 500
        // ── FIX 2: startup cloud-backfill throttle ──
        // Versioned with the encrypted-history cursor. This makes an upgrade
        // schedule the v2 repair immediately even if v1 ran within 7 days.
        private const val KEY_CLOUD_BACKFILL_LAST_RUN_AT = "cloud_backfill_last_run_at_v2"
        private const val KEY_CRYPTO_V3_DEAD_LETTERS_RECOVERED =
            "crypto_v3_dead_letters_recovered"

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
     * THE single GMweb server origin (v3.4.6).
     *
     * This is the ONLY stored GMweb address. [backendUrl] and [gmwebUrl] below are deprecated
     * aliases that read and write THIS value, so the many existing call sites
     * (`BackendClient`, `OutboxPoller`, the enrollment path, the diagnostics probe) all speak
     * to one server without touching ten classes — while no second authority can be created.
     *
     * Reading it runs the one-time legacy migration, so every entry point sees the same answer
     * however the process was started.
     */
    var gmwebServerOrigin: String
        get() {
            migrateLegacyServerConfigOnce()
            return prefs.getString(KEY_SERVER_ORIGIN, "") ?: ""
        }
        set(value) {
            // Always stored as a bare origin: a stored `/app` would produce 404s on every API
            // route, so normalization happens on the way IN, not on the way out.
            val normalized = GmwebServerProfile.profileOf(value)?.origin
            require(normalized != null || value.isBlank()) {
                "GMweb server URL must be a valid https origin"
            }
            prefs.edit().putString(KEY_SERVER_ORIGIN, normalized ?: "").apply()
        }

    /** The parsed profile, or null when no server is configured. */
    fun gmwebServerProfile(): GmwebServerProfile? =
        GmwebServerProfile.profileOf(gmwebServerOrigin)

    /**
     * Validates and stores a pasted address — the panel URL, its trailing-slash form, or a bare
     * origin — as the single server origin.
     *
     * Returns the normalization result so the UI can explain a rejection instead of showing a
     * generic "invalid URL".
     */
    fun saveGmwebServer(input: String): GmwebServerNormalization {
        return when (val result = GmwebServerProfile.normalize(input)) {
            is GmwebServerNormalization.Valid -> {
                prefs.edit().putString(KEY_SERVER_ORIGIN, result.profile.origin).apply()
                result
            }
            is GmwebServerNormalization.Invalid -> {
                if (input.isBlank()) {
                    // Clearing the field is an explicit "no server", not a validation error.
                    prefs.edit().putString(KEY_SERVER_ORIGIN, "").apply()
                }
                result
            }
        }
    }

    /**
     * One-time migration to the single origin. IDEMPOTENT — guarded by a marker, so a rollback
     * to an older build (which writes the legacy keys again) does not re-trigger it and cannot
     * resurrect a second authority.
     *
     * PRIORITY, and why:
     *  1. the old `gmwebUrl` — it is what drove the pull bridge, the leg the user was actively
     *     configuring;
     *  2. an EXPLICITLY STORED `backendUrl` — read with `prefs.contains`, never through the
     *     getter, because the getter used to fall back to a domain compiled into the APK. That
     *     baked value was never a user choice and must not be adopted as one;
     *  3. otherwise blank: the app starts with no server, and the user configures one.
     */
    private fun migrateLegacyServerConfigOnce() {
        if (prefs.getBoolean(KEY_SERVER_MIGRATED, false)) return

        val decision = runCatching {
            GmwebServerMigration.decide(
                GmwebServerMigration.StoredConfig(
                    legacyGmwebUrl = prefs.getString(KEY_GMWEB_URL, null),
                    // `contains` — NOT the getter, and never BuildConfig. The getter used to
                    // fall back to the domain baked into the APK, which was never a user choice
                    // and must disappear rather than be inherited.
                    storedBackendUrl = if (prefs.contains(KEY_BACKEND_URL)) {
                        prefs.getString(KEY_BACKEND_URL, null)
                    } else {
                        null
                    },
                    alreadyMigrated = false
                )
            )
        }.getOrElse { GmwebServerMigration.Decision(origin = null) }

        prefs.edit().apply {
            if (decision.hasOriginToWrite) putString(KEY_SERVER_ORIGIN, decision.origin)
            putBoolean(KEY_SERVER_MIGRATED, true)
        }.apply()
    }

    /**
     * @deprecated The control plane and the pull bridge are the SAME GMweb deployment, so this
     * is an alias for [gmwebServerOrigin]. It exists so existing call sites keep working; it is
     * no longer an independent value, and it no longer falls back to a compiled-in domain.
     */
    @Deprecated("Use gmwebServerOrigin / gmwebServerProfile()", ReplaceWith("gmwebServerOrigin"))
    var backendUrl: String
        get() = gmwebServerOrigin
        set(value) {
            gmwebServerOrigin = value
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
     * FIX 2: when the startup one-shot cloud-history backfill last ran.
     * Throttles the app-start trigger to once per 7 days; the post-approve
     * trigger is NOT throttled (it is the primary path and is idempotent).
     */
    var lastCloudBackfillRunAt: Long
        get() = prefs.getLong(KEY_CLOUD_BACKFILL_LAST_RUN_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_CLOUD_BACKFILL_LAST_RUN_AT, value).apply()

    var cryptoV3DeadLettersRecovered: Boolean
        get() = prefs.getBoolean(KEY_CRYPTO_V3_DEAD_LETTERS_RECOVERED, false)
        set(value) = prefs.edit().putBoolean(KEY_CRYPTO_V3_DEAD_LETTERS_RECOVERED, value).apply()

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
     * @deprecated The pull bridge and the control plane are the SAME GMweb deployment, so this
     * is an alias for [gmwebServerOrigin]. Kept so the existing pull/validate/ack call sites and
     * `GmwebTaskValidator` keep compiling; a setter here writes the ONE origin.
     */
    @Deprecated("Use gmwebServerOrigin / gmwebServerProfile()", ReplaceWith("gmwebServerOrigin"))
    var gmwebUrl: String
        get() = gmwebServerOrigin
        set(value) {
            gmwebServerOrigin = value
        }

    /**
     * Intake ownership for SEND_SMS (no-dual-execution P0): when false
     * (default) the legacy pull bridge exclusively owns SMS delivery and
     * strategic control-plane SEND_SMS commands are ingested but deferred.
     * Flip ON only after strategic command E2E is proven on a real device.
     */
    var controlPlaneSendsEnabled: Boolean
        get() = prefs.getBoolean(KEY_CP_SENDS, DEFAULT_CONTROL_PLANE_SENDS)
        set(value) = prefs.edit().putBoolean(KEY_CP_SENDS, value).apply()

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
