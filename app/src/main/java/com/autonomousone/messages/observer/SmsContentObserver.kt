package com.autonomousone.messages.observer

import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

class SmsContentObserver(
    private val onSmsChanged: () -> Unit
) : ContentObserver(
    Handler(Looper.getMainLooper())
) {
    private var lastTriggerTime = 0L

    override fun onChange(selfChange: Boolean) {
        super.onChange(selfChange)
        debounceAndTrigger()
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)
        debounceAndTrigger()
    }

    private fun debounceAndTrigger() {
        val currentTime = SystemClock.elapsedRealtime()
        if (currentTime - lastTriggerTime > 500) {
            lastTriggerTime = currentTime
            onSmsChanged()
        }
    }
}