package com.autonomousone.messages.onboarding

import android.content.Context

class OnboardingPreferences(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var disclosureAccepted: Boolean
        get() = prefs.getInt(KEY_DISCLOSURE_VERSION, 0) >= DISCLOSURE_VERSION
        set(value) = prefs.edit().putInt(KEY_DISCLOSURE_VERSION, if (value) DISCLOSURE_VERSION else 0).apply()

    var optionalStepCompleted: Boolean
        get() = prefs.getBoolean(KEY_OPTIONAL_COMPLETE, false)
        set(value) = prefs.edit().putBoolean(KEY_OPTIONAL_COMPLETE, value).apply()

    var smsPermissionRequested: Boolean
        get() = prefs.getBoolean(KEY_SMS_REQUESTED, false)
        set(value) = prefs.edit().putBoolean(KEY_SMS_REQUESTED, value).apply()

    var contactsPermissionRequested: Boolean
        get() = prefs.getBoolean(KEY_CONTACTS_REQUESTED, false)
        set(value) = prefs.edit().putBoolean(KEY_CONTACTS_REQUESTED, value).apply()

    var notificationsPermissionRequested: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS_REQUESTED, false)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFICATIONS_REQUESTED, value).apply()

    companion object {
        private const val PREFS_NAME = "onboarding_preferences"
        private const val KEY_DISCLOSURE_VERSION = "disclosure_version"
        private const val KEY_OPTIONAL_COMPLETE = "optional_step_complete"
        private const val KEY_SMS_REQUESTED = "sms_permission_requested"
        private const val KEY_CONTACTS_REQUESTED = "contacts_permission_requested"
        private const val KEY_NOTIFICATIONS_REQUESTED = "notifications_permission_requested"
        const val DISCLOSURE_VERSION = 1
    }
}
