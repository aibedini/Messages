package com.autonomousone.messages.observer

import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper

/**
 * Observes changes to the SMS/MMS ContentProvider.
 *
 * V2: Passes the URI through to the callback so ChangeRouter can extract
 * the row ID for targeted O(1) mutations when available.
 *
 * LEADING-EDGE dispatch: the FIRST change fires immediately (millisecond-live
 * UI), and any further changes inside [COALESCE_MS] are collapsed into a single
 * trailing call.
 */
class SmsContentObserver(
    private val onChange: (uri: Uri?) -> Unit
) : ContentObserver(Handler(Looper.getMainLooper())) {

    companion object {
        /** Window in which extra provider notifications collapse into one call. */
        private const val COALESCE_MS = 150L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val trailingRunnable = Runnable {
        pendingTrailing = false
        lastFiredAt = System.currentTimeMillis()
        onChange(null)  // null = unknown change type → reconcile
    }

    @Volatile
    private var lastFiredAt = 0L

    @Volatile
    private var pendingTrailing = false

    override fun onChange(selfChange: Boolean) {
        dispatch(null)
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        // NOTE: do NOT call super here — the base ContentObserver delegates
        // onChange(selfChange, uri) back into onChange(selfChange), which we
        // also override. That produced TWO dispatches per provider change.
        dispatch(uri)
    }

    private fun dispatch(uri: Uri?) {
        val now = System.currentTimeMillis()
        if (now - lastFiredAt >= COALESCE_MS) {
            // Leading edge: no waiting at all.
            lastFiredAt = now
            handler.removeCallbacks(trailingRunnable)
            pendingTrailing = false
            onChange(uri)
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
