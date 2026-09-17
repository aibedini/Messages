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
 * INVARIANT: every provider notification contributes to exactly ONE dispatched
 * batch. The previous version broke it: the leading path cancelled the pending
 * trailing flush AND cleared the accumulator, so
 *
 *   A @ t=0    -> leading dispatch of A
 *   B @ t=20   -> accumulated
 *   C @ t=151  -> new leading: cancels B's flush, clears the accumulator
 *   => B WAS LOST FOREVER
 *
 * The leading path now flushes the accumulated batch BEFORE dispatching the new
 * one, so nothing can disappear across a window boundary.
 */
class SmsContentObserver(
    /**
     * Time source. Injectable so the coalescing window is testable
     * deterministically instead of racing a real 150 ms wall-clock window on a
     * loaded machine. Declared FIRST so [onChange] stays last and every
     * trailing-lambda call site keeps compiling.
     */
    private val clock: () -> Long = { System.currentTimeMillis() },
    /**
     * Schedules the trailing flush. Injectable so tests can actually PUMP the
     * trailing runnable — the previous JVM tests could not, which is exactly why
     * the loss across the window boundary was never caught.
     */
    private val scheduler: Scheduler = HandlerScheduler(Handler(Looper.getMainLooper())),
    private val onChange: (ProviderChangeBatch) -> Unit
) : ContentObserver(Handler(Looper.getMainLooper())) {

    /** Schedules/cancels the trailing flush. */
    interface Scheduler {
        fun schedule(delayMs: Long, action: Runnable)
        fun cancel(action: Runnable)
    }

    class HandlerScheduler(private val handler: Handler) : Scheduler {
        override fun schedule(delayMs: Long, action: Runnable) {
            handler.postDelayed(action, delayMs)
        }

        override fun cancel(action: Runnable) {
            handler.removeCallbacks(action)
        }
    }

    companion object {
        /** Window in which extra provider notifications collapse into one call. */
        const val COALESCE_MS = 150L

        /** Shared by the isLoggable() guard and the log call itself (lint: LogTagMismatch). */
        private const val TAG = "SmsObserver"
    }

    /** Accumulated, still-undispatched changes. Guarded by the main looper. */
    private var pending = ProviderChangeBatch.EMPTY

    private val trailingRunnable = Runnable { flushTrailing() }

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

    /** Owner/test hook: run the scheduled trailing flush immediately. */
    internal fun flushTrailingNow() = flushTrailing()

    private fun flushTrailing() {
        pendingTrailing = false
        lastFiredAt = clock()
        val batch = pending
        pending = ProviderChangeBatch.EMPTY
        if (batch.totalEvents > 0) onChange(batch)
    }

    private fun dispatch(uri: Uri?) {
        val change = ProviderChangeBatch.from(uri?.authority, uri?.path)
        val now = clock()
        val wasLeading = now - lastFiredAt >= COALESCE_MS
        if (android.util.Log.isLoggable(TAG, android.util.Log.INFO)) {
            android.util.Log.i(
                TAG,
                "observer_fired uri=" + (uri ?: "<unknown>") + " leading=" + wasLeading +
                    " sms=" + change.smsIds.size + " mms=" + change.mmsIds.size +
                    " threads=" + change.threadIds.size + " unknown=" + change.unknownCount
            )
        }
        if (wasLeading) {
            lastFiredAt = now
            scheduler.cancel(trailingRunnable)
            pendingTrailing = false
            // Flush the previous window's accumulated work FIRST. Cancelling the
            // trailing flush without this would discard it (see the class doc).
            val carryOver = pending
            pending = ProviderChangeBatch.EMPTY
            if (carryOver.totalEvents > 0) onChange(carryOver)
            onChange(change)
            return
        }
        pending = pending.merge(change)
        if (!pendingTrailing) {
            pendingTrailing = true
            scheduler.schedule(COALESCE_MS, trailingRunnable)
        }
    }
}
