package com.autonomousone.messages.messaging

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Which conversation, if any, the user is currently looking at.
 *
 * Why this exists: an incoming SMS for a thread that is on screen right now
 * must NOT increment the conversation unread counter, even for one frame.
 * Otherwise the badge flashes 0 -> 1 -> 0 while the user is reading the very
 * message that arrived. The sync core consults [isVisible] while it builds the
 * Room mutation, so the unread count is written as 0 in the same transaction
 * that inserts the message; the provider READ write is only eventual
 * persistence and is never the thing that clears the badge.
 *
 * Scope: exactly one conversation can be visible at a time in this app, hence a
 * single value rather than a set. This is deliberately in-process state: it
 * describes what the user is looking at, which does not survive (nor outlive)
 * the process that is drawing it. It must never be used to decide what is
 * durable.
 *
 * Thread-safe: the value is only ever read/written through the StateFlow's
 * atomic value, and every writer is called from a single main-thread lifecycle.
 */
object VisibleConversationTracker {

    private val _visibleThreadId = MutableStateFlow<Long?>(null)

    /** The thread currently rendered, or null when no conversation is open. */
    val visibleThreadId: StateFlow<Long?> = _visibleThreadId

    /** Cheap, allocation-free check for the ingest hot path. */
    fun isVisible(threadId: Long): Boolean = _visibleThreadId.value == threadId

    fun onOpened(threadId: Long) {
        if (threadId > 0L) _visibleThreadId.value = threadId
    }

    /**
     * Only clears the value if the closing conversation is the one currently
     * tracked: a fast A -> B navigation must not let A's teardown hide B.
     */
    fun onClosed(threadId: Long) {
        if (_visibleThreadId.value == threadId) _visibleThreadId.value = null
    }

    /** Test seam. Never call from production code. */
    internal fun resetForTest() {
        _visibleThreadId.value = null
    }
}
