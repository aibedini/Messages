package com.autonomousone.messages.observer

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import com.autonomousone.messages.utils.DiagnosticLog

/**
 * Watches the Contacts provider so a contact created, renamed, deleted or renumbered
 * while the app is open reaches the UI without a force-stop.
 *
 * WHY A CONTENT OBSERVER (and not just ON_RESUME): the user can add or edit a contact
 * from within this app (the participant action "Add to contacts" hands off to the
 * system editor) or from the Contacts app, and the Home list is often still on screen
 * when they come back. Room-style invalidation does not cover the Contacts provider,
 * which is not part of our database.
 *
 * DEBOUNCED: a single edit produces a burst of cursor notifications (one per affected
 * table), so the change is coalesced for [DEBOUNCE_MILLIS] and the directory is
 * reloaded ONCE. Without the debounce a rename would trigger several full contact
 * queries.
 *
 * Registered on the main Looper because that is where ContentResolver delivers; the
 * reload it triggers is dispatched to IO by the caller.
 */
class ContactsChangeObserver(
    private val context: Context,
    private val debounceMillis: Long = DEBOUNCE_MILLIS,
    private val onContactsChanged: () -> Unit
) {

    private val handler = Handler(Looper.getMainLooper())
    private var registered = false

    private val pending = Runnable {
        DiagnosticLog.event("CONTACT_DIRECTORY", "provider-changed reload=1")
        onContactsChanged()
    }

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = schedule()
        override fun onChange(selfChange: Boolean, uri: Uri?) = schedule()
    }

    fun register() {
        if (registered) return
        registered = runCatching {
            context.contentResolver.registerContentObserver(
                ContactsContract.Contacts.CONTENT_URI,
                // Descendants too: the notification for a phone-number edit arrives on
                // the Data table rather than on Contacts itself.
                true,
                observer
            )
            true
        }.getOrElse { error ->
            // A provider that refuses registration must never break Home: the
            // ON_RESUME refresh is the documented fallback.
            DiagnosticLog.event("CONTACT_DIRECTORY", "observer-register-failed", error)
            false
        }
    }

    fun unregister() {
        if (!registered) return
        handler.removeCallbacks(pending)
        runCatching { context.contentResolver.unregisterContentObserver(observer) }
        registered = false
    }

    private fun schedule() {
        handler.removeCallbacks(pending)
        handler.postDelayed(pending, debounceMillis)
    }

    companion object {
        /** Coalescing window for the burst of notifications one edit produces. */
        const val DEBOUNCE_MILLIS = 400L
    }
}
