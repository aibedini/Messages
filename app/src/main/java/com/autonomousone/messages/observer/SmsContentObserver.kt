package com.autonomousone.messages.observer

import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper

/**
 * Observes changes to the SMS/MMS ContentProvider.
 *
 * LEADING-EDGE dispatch: the FIRST change fires immediately (millisecond-live
 * UI). Further changes inside [COALESCE_MS] are accumulated — NOT discarded —
 * and dispatched once as a [ProviderChangeBatch].
 *
 * The accumulator is the fix for the escalation bug: the trailing edge used to
 * dispatch `null` ("unknown change"), which ChangeRouter mapped to a full
 * dual-source reconcile. A burst therefore turned one exact row insert into a
 * FullSync 150 ms later.
 */
class SmsContentObserver(
    private val onChange: (ProviderChangeBatch) -> Unit
) : ContentObserver(Handler(Looper.getMainLooper())) {

    companion object {
        /** Window in which extra provider notifications collapse into one call. */
        private const val COALESCE_MS = 150L
    }

    private val handler = Handler(Looper.getMainLooper())

    /** Accumulated, still-undispatched changes. Guarded by the main looper. */
    private var pending = ProviderChangeBatch.EMPTY

    private val trailingRunnable = Runnable {
        pendingTrailing = false
        lastFiredAt = System.currentTimeMillis()
        val batch = pending
        pending = ProviderChangeBatch.EMPTY
        onChange(batch)
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
        val change = ProviderChangeBatch.from(uri?.authority, uri?.path)
        val now = System.currentTimeMillis()
        val wasLeading = now - lastFiredAt >= COALESCE_MS
        android.util.Log.i(
            "SmsObserver",
            "observer_fired uri=" + (uri ?: "<unknown>") + " leading=" + wasLeading +
                " sms=" + change.smsIds.size + " mms=" + change.mmsIds.size +
                " threads=" + change.threadIds.size + " unknown=" + change.unknownCount
        )
        if (wasLeading) {
            // Leading edge: no waiting at all.
            lastFiredAt = now
            handler.removeCallbacks(trailingRunnable)
            pendingTrailing = false
            pending = ProviderChangeBatch.EMPTY
            onChange(change)
            return
        }
        // Inside the coalesce window: keep everything the burst told us and
        // dispatch exactly ONE trailing batch with the narrowest possible
        // repair. Never blind, never "null".
        pending = pending.merge(change)
        if (!pendingTrailing) {
            pendingTrailing = true
            handler.postDelayed(trailingRunnable, COALESCE_MS)
        }
    }
}
