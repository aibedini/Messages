package com.autonomousone.messages.messaging

import android.content.Context

/**
 * User-controlled messaging preferences (Google Messages-style options).
 *
 * IMPORTANT: nothing here is enabled by default. Every behaviour below stays
 * OFF / unset until the user explicitly turns it on in Settings > Messaging.
 */
class MessagingPreferences(context: Context) {

    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /**
     * v2.6.13: escape hatch for feature-local keys that don't deserve a
     * full property (currently: per-SIM SMSC seeds "smsc_sim_<subId>").
     */
    fun rawPrefs() = prefs

    /**
     * v2.6.14: per-SIM MANUAL SMSC override. The key is deliberately
     * "...manual..." — v2.6.13 briefly auto-seeded plain "smsc_sim_<id>"
     * keys from a hidden carrier directory; those stale values must never
     * masquerade as user intent. Only user saves write this key.
     */
    fun smscForSim(subscriptionId: Int): String? =
        prefs.getString("smsc_sim_manual_$subscriptionId", null)?.trim()?.takeIf { it.isNotEmpty() }

    fun setSmscForSim(subscriptionId: Int, value: String?) {
        val v = value?.trim().orEmpty()
        val key = "smsc_sim_manual_$subscriptionId"
        if (v.isEmpty()) prefs.edit().remove(key).apply()
        else prefs.edit().putString(key, v).apply()
        // Purge the v2.6.13 hidden-directory seed for the same SIM so it can
        // never resurface as an implicit override.
        prefs.edit().remove("smsc_sim_$subscriptionId").apply()
    }

    companion object {
        private const val PREF_NAME = "messaging_prefs"

        private const val KEY_DELIVERY_REPORTS = "delivery_reports_enabled"
        private const val KEY_SUBSCRIPTION_ID = "send_subscription_id"
        private const val KEY_SMSC = "smsc_address"
        private const val KEY_IPHONE_REACTIONS = "show_iphone_reactions_as_emoji"
        private const val KEY_GROUP_MESSAGING = "group_messaging_enabled"
        private const val KEY_RATE_LIMIT_ENABLED = "send_rate_limit_enabled"
        private const val KEY_RATE_LIMIT_COUNT = "send_rate_limit_count"
        private const val KEY_RATE_LIMIT_WINDOW_MIN = "send_rate_limit_window_min"

        /** Sentinel meaning "the user has not picked a SIM line yet". */
        const val SUBSCRIPTION_UNSET = -1
    }

    /**
     * Request network SMS-STATUS-REPORT PDUs. Default: ON so delivery can be
     * proven when the carrier supports reports; users can still opt out.
     */
    var deliveryReportsEnabled: Boolean
        get() = prefs.getBoolean(KEY_DELIVERY_REPORTS, true)
        set(value) = prefs.edit().putBoolean(KEY_DELIVERY_REPORTS, value).apply()

    /**
     * Subscription ID of the SIM line used to send SMS.
     * [SUBSCRIPTION_UNSET] means the user has not chosen a line and the system
     * default subscription will be used instead. Default: UNSET.
     */
    var sendSubscriptionId: Int
        get() = prefs.getInt(KEY_SUBSCRIPTION_ID, SUBSCRIPTION_UNSET)
        set(value) = prefs.edit().putInt(KEY_SUBSCRIPTION_ID, value).apply()

    /**
     * Custom SMSC (Service Center) address used when sending.
     * Empty string means use the network-provided SMSC. Default: empty.
     */
    var smscAddress: String
        get() = prefs.getString(KEY_SMSC, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SMSC, value.trim()).apply()

    /** Render iPhone tapbacks ('Loved "…"') as emoji bubbles. Default: OFF. */
    var showIphoneReactionsAsEmoji: Boolean
        get() = prefs.getBoolean(KEY_IPHONE_REACTIONS, false)
        set(value) = prefs.edit().putBoolean(KEY_IPHONE_REACTIONS, value).apply()

    /** Enable group (multi-recipient) messaging behaviour. Default: OFF. */
    var groupMessagingEnabled: Boolean
        get() = prefs.getBoolean(KEY_GROUP_MESSAGING, false)
        set(value) = prefs.edit().putBoolean(KEY_GROUP_MESSAGING, value).apply()

    // ── Send rate limiting (protects the SIM from operator throttling) ──

    /** When true, sends are paced to [rateLimitCount] per [rateLimitWindowMin] minutes. Default: OFF. */
    var rateLimitEnabled: Boolean
        get() = prefs.getBoolean(KEY_RATE_LIMIT_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_RATE_LIMIT_ENABLED, value).apply()

    /** Max messages allowed inside the window. Default: 10. */
    var rateLimitCount: Int
        get() = prefs.getInt(KEY_RATE_LIMIT_COUNT, 10)
        set(value) = prefs.edit().putInt(KEY_RATE_LIMIT_COUNT, value.coerceIn(1, 1000)).apply()

    private val KEY_LOCAL_ONLY_SENDERS = "firewall_local_only_senders"
    private val KEY_SYNC_ALLOWLIST_SENDERS = "firewall_sync_allowlist_senders"
    private val KEY_FINANCIAL_POLICY = "firewall_financial_policy"
    private val KEY_AMBIGUITY_MODE = "firewall_ambiguity_mode"

    /** Window length in minutes. Default: 1 minute. */
    var rateLimitWindowMin: Int
        get() = prefs.getInt(KEY_RATE_LIMIT_WINDOW_MIN, 1)
        set(value) = prefs.edit().putInt(KEY_RATE_LIMIT_WINDOW_MIN, value.coerceIn(1, 60)).apply()

    // ── ADR-006: Sensitive Message Firewall (Privacy & Security settings) ──

    /** Senders whose messages are ALWAYS kept on-device (never synced). */
    var localOnlySenders: Set<String>
        get() = prefs.getStringSet(KEY_LOCAL_ONLY_SENDERS, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_LOCAL_ONLY_SENDERS, value).apply()

    /** Senders the user explicitly allows to sync (never overrides OTP/bank codes). */
    var syncAllowlistSenders: Set<String>
        get() = prefs.getStringSet(KEY_SYNC_ALLOWLIST_SENDERS, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_SYNC_ALLOWLIST_SENDERS, value).apply()

    /** Policy for FINANCIAL_NOTIFICATION (ADR-006 §10: user configurable). */
    var financialNotificationPolicy: com.autonomousone.messages.security.SensitiveMessageFirewall.Policy
        get() = runCatching {
            com.autonomousone.messages.security.SensitiveMessageFirewall.Policy.valueOf(
                prefs.getString(KEY_FINANCIAL_POLICY, "ASK") ?: "ASK"
            )
        }.getOrDefault(com.autonomousone.messages.security.SensitiveMessageFirewall.Policy.ASK)
        set(value) = prefs.edit().putString(KEY_FINANCIAL_POLICY, value.name).apply()

    /** Ambiguity handling (ADR-006 §16). Production default: privacy strict. */
    var ambiguityMode: com.autonomousone.messages.security.SensitiveMessageFirewall.AmbiguityMode
        get() = runCatching {
            com.autonomousone.messages.security.SensitiveMessageFirewall.AmbiguityMode.valueOf(
                prefs.getString(KEY_AMBIGUITY_MODE, "PRIVACY_STRICT") ?: "PRIVACY_STRICT"
            )
        }.getOrDefault(com.autonomousone.messages.security.SensitiveMessageFirewall.AmbiguityMode.PRIVACY_STRICT)
        set(value) = prefs.edit().putString(KEY_AMBIGUITY_MODE, value.name).apply()
}
