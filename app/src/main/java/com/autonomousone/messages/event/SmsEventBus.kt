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

    // No replay: a NEW ViewModel collector must not re-receive the LAST message
    // (that caused a stale SMS to flash in a freshly opened conversation and an
    // extra refresh). Liveness comes from Room/provider state, not the bus; the
    // bus is a fire-and-forget nudge. extraBufferCapacity keeps fast bursts.
    private val _incomingSmsFlow = MutableSharedFlow<Sms>(extraBufferCapacity = 64)
    val incomingSmsFlow: SharedFlow<Sms> = _incomingSmsFlow.asSharedFlow()

    // Signals ViewModels to reload from DB (fired on onResume)
    private val _refreshFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val refreshFlow: SharedFlow<Unit> = _refreshFlow.asSharedFlow()

    // Thread-read events: fired when a conversation screen marks its messages
    // read, so the Home list can drop the unread badge instantly.
    data class ThreadRead(val threadId: Long, val phone: String)

    private val _threadReadFlow = MutableSharedFlow<ThreadRead>(extraBufferCapacity = 16)
    val threadReadFlow: SharedFlow<ThreadRead> = _threadReadFlow.asSharedFlow()

    /**
     * Outgoing-message events: fired right after a send is persisted, so the
     * Home list moves that thread to the top with the new snippet instantly —
     * even while the chat screen is still open (single-activity back stack).
     */
    data class OutgoingSent(
        val threadId: Long,
        val phone: String,
        val message: String,
        val date: Long
    )

    private val _outgoingSentFlow = MutableSharedFlow<OutgoingSent>(extraBufferCapacity = 16)
    val outgoingSentFlow: SharedFlow<OutgoingSent> = _outgoingSentFlow.asSharedFlow()

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

    /** Fired by ConversationScreen when its thread is marked read. */
    fun emitThreadRead(threadId: Long, phone: String) {
        scope.launch {
            _threadReadFlow.emit(ThreadRead(threadId, phone))
        }
    }

    /** Fired by SmsSender right after an outgoing message is persisted. */
    fun emitOutgoingSent(threadId: Long, phone: String, message: String, date: Long) {
        _outgoingSentFlow.tryEmit(
            OutgoingSent(threadId, phone, message, date)
        )
    }
}
