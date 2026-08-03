package com.autonomousone.messages.event

import com.autonomousone.messages.model.Sms
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * App-wide singleton event bus for real-time incoming SMS events and app state tracking.
 * Uses replay=1 so late collectors always get the latest SMS.
 */
object SmsEventBus {

    // Use a dedicated scope that outlives any single ViewModel
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // replay=1 ensures late subscribers (e.g. ViewModel collecting after SMS arrives) still get it
    private val _incomingSmsFlow = MutableSharedFlow<Sms>(replay = 1, extraBufferCapacity = 64)
    val incomingSmsFlow: SharedFlow<Sms> = _incomingSmsFlow.asSharedFlow()

    // Flag indicating whether the application is currently in the foreground
    @Volatile
    var isAppInForeground: Boolean = false

    // Phone number or address of the active open conversation screen (blank if on Home/New screen)
    @Volatile
    var activeConversationPhone: String = ""

    /**
     * Broadcast an incoming SMS event to active subscribers.
     * Emits on Main dispatcher so Compose SnapshotState mutations are safe.
     */
    fun emitSms(sms: Sms) {
        scope.launch {
            _incomingSmsFlow.emit(sms)
        }
    }
}
