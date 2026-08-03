package com.autonomousone.messages.observer

import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper

/**
 * Observes changes to the SMS ContentProvider.
 * Debounces rapid back-to-back onChange events so we only reload once per SMS insert/update.
 */
class SmsContentObserver(
    private val onSmsChanged: () -> Unit
) : ContentObserver(Handler(Looper.getMainLooper())) {

    private val handler = Handler(Looper.getMainLooper())
    private val debounceRunnable = Runnable { onSmsChanged() }

    override fun onChange(selfChange: Boolean) {
        super.onChange(selfChange)
        scheduleDebounced()
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)
        scheduleDebounced()
    }

    private fun scheduleDebounced() {
        handler.removeCallbacks(debounceRunnable)
        // 300 ms debounce: lets the DB commit all rows before we re-read
        handler.postDelayed(debounceRunnable, 300L)
    }
}