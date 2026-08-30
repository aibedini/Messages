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

    /** Request SMS delivery reports and surface Delivered/Failed status. Default: OFF. */
    var deliveryReportsEnabled: Boolean
        get() = prefs.getBoolean(KEY_DELIVERY_REPORTS, false)
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

    /** Window length in minutes. Default: 1 minute. */
    var rateLimitWindowMin: Int
        get() = prefs.getInt(KEY_RATE_LIMIT_WINDOW_MIN, 1)
        set(value) = prefs.edit().putInt(KEY_RATE_LIMIT_WINDOW_MIN, value.coerceIn(1, 60)).apply()
}
