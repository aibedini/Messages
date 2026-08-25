package com.autonomousone.messages.observer

import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper

/**
 * Observes changes to the SMS/MMS ContentProvider.
 *
 * LEADING-EDGE dispatch: the FIRST change fires immediately (millisecond-live
 * UI), and any further changes inside [COALESCE_MS] are collapsed into a single
 * trailing call. The old implementation was trailing-only with a fixed 300 ms
 * postDelayed, which added 300 ms of dead time to EVERY message — the app felt
 * laggy even though the data was already in the provider.
 */
class SmsContentObserver(
    private val onSmsChanged: () -> Unit
) : ContentObserver(Handler(Looper.getMainLooper())) {

    companion object {
        /** Window in which extra provider notifications collapse into one call. */
        private const val COALESCE_MS = 150L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val trailingRunnable = Runnable {
        pendingTrailing = false
        lastFiredAt = System.currentTimeMillis()
        onSmsChanged()
    }

    @Volatile
    private var lastFiredAt = 0L

    @Volatile
    private var pendingTrailing = false

    override fun onChange(selfChange: Boolean) {
        super.onChange(selfChange)
        dispatch()
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)
        dispatch()
    }

    private fun dispatch() {
        val now = System.currentTimeMillis()
        if (now - lastFiredAt >= COALESCE_MS) {
            // Leading edge: no waiting at all.
            lastFiredAt = now
            handler.removeCallbacks(trailingRunnable)
            pendingTrailing = false
            onSmsChanged()
            return
        }
        // Inside the coalesce window: schedule exactly ONE trailing call so a
        // burst of provider notifications (multipart SMS, MMS parts) still ends
        // with a final reconcile.
        if (!pendingTrailing) {
            pendingTrailing = true
            handler.postDelayed(trailingRunnable, COALESCE_MS)
        }
    }
}
