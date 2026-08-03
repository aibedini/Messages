package com.autonomousone.messages.event

import com.autonomousone.messages.model.Sms
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * App-wide singleton event bus for real-time incoming SMS events and app state tracking.
 */
object SmsEventBus {

    private val _incomingSmsFlow = MutableSharedFlow<Sms>(extraBufferCapacity = 64)
    val incomingSmsFlow: SharedFlow<Sms> = _incomingSmsFlow.asSharedFlow()

    // Flag indicating whether the application is currently in the foreground
    @Volatile
    var isAppInForeground: Boolean = false

    // Phone number or address of the active open conversation screen (blank if on Home/New screen)
    @Volatile
    var activeConversationPhone: String = ""

    /**
     * Broadcast an incoming SMS event to active subscribers
     */
    fun emitSms(sms: Sms) {
        _incomingSmsFlow.tryEmit(sms)
    }
}
