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
 * App-wide singleton event bus for real-time SMS events and app state.
 */
object SmsEventBus {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // replay=1: late collectors (e.g. ViewModel subscribing after SMS arrives) still get it
    private val _incomingSmsFlow = MutableSharedFlow<Sms>(replay = 1, extraBufferCapacity = 64)
    val incomingSmsFlow: SharedFlow<Sms> = _incomingSmsFlow.asSharedFlow()

    // Signals ViewModels to reload from DB (fired on onResume)
    private val _refreshFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val refreshFlow: SharedFlow<Unit> = _refreshFlow.asSharedFlow()

    @Volatile
    var isAppInForeground: Boolean = false

    @Volatile
    var activeConversationPhone: String = ""

    fun emitSms(sms: Sms) {
        scope.launch {
            _incomingSmsFlow.emit(sms)
        }
    }

    /** Call from onResume so all ViewModels reload fresh data from DB */
    fun notifyResume() {
        _refreshFlow.tryEmit(Unit)
    }
}
