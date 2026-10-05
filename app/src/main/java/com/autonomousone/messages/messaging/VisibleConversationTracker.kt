package com.autonomousone.messages.messaging

import com.autonomousone.messages.diagnostics.DiagnosticsBreadcrumbs
import com.autonomousone.messages.utils.DiagnosticLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/**
 * THE authority for "a conversation may be marked read".
 *
 * ## The production bug this fixes
 *
 * An SMS for a conversation that had merely been opened *earlier* was being marked read, while a
 * message from another sender stayed unread. The cause was that nothing in the read path required the
 * conversation to be **on screen**: `ConversationViewModel.observeIncomingSms()` matched the incoming
 * sender against `currentPhone` — a field that survives navigation, backgrounding and even a
 * back-stack entry it no longer owns — and then wrote `unread = false` plus a durable mark-read.
 *
 * The old tracker made it worse. Visibility was set from *composition* and from the ViewModel's own
 * load path ("data was loaded" is not "the user is looking at it"), and cleared only from
 * `onCleared()`, so a Home-visible or backgrounded app kept claiming the last conversation was
 * visible. The sync core then folded a new unread provider row to `read = true` under that false
 * premise.
 *
 * ## The rule
 *
 * `isVisible` is true only while the conversation screen is **RESUMED** for that exact identity.
 * Anything else — Home, another conversation, background, a stale loader, a surviving ViewModel — is
 * not visibility and carries NO read authority.
 *
 * Exactly four authorities may turn unread into read:
 *  - [ReadCause.USER_OPEN_RESUMED]            the conversation is genuinely on screen
 *  - [ReadCause.USER_MARK_READ]               the user pressed Mark as read
 *  - [ReadCause.REMOTE_MARK_READ]             an authenticated GMweb command asked for it
 *  - [ReadCause.INCOMING_WHILE_VISIBLE]       a message arrived while that same screen was RESUMED
 */
enum class ReadCause {
    USER_OPEN_RESUMED,
    USER_MARK_READ,
    REMOTE_MARK_READ,
    INCOMING_WHILE_VISIBLE
}

/** Why a read was NOT performed. Recorded, so "it stayed unread" is explainable. */
enum class ReadSkipReason {
    SCREEN_NOT_VISIBLE,
    APP_NOT_FOREGROUND,
    STALE_GENERATION,
    CONVERSATION_MISMATCH,
    UNKNOWN_THREAD,
    NO_USER_EVIDENCE
}

/**
 * The canonical identity of the conversation currently on screen.
 *
 * `threadId` is authoritative whenever it is known; the address is only a fallback for the legacy
 * `threadId == 0` case. Keeping both in one object is what stops two visibility systems (a tracker
 * plus a stray `activeConversationPhone`) from disagreeing.
 */
data class VisibleConversationIdentity(
    val threadId: Long?,
    val normalizedAddress: String?,
    val lifecycleGeneration: Long
)

/**
 * Which conversation the user is ACTUALLY looking at, and whether its screen is RESUMED.
 *
 * In-process by design: it describes what is being drawn right now, and it must never outlive the
 * process or be treated as durable truth. [isVisible] is the only question the read paths may ask.
 */
object VisibleConversationTracker {

    private val _visibleThreadId = MutableStateFlow<Long?>(null)
    private val _visibleAddress = MutableStateFlow<String?>(null)
    private val generation = AtomicLong(0L)

    /** The thread currently rendered AND resumed, or null. Not a durable fact. */
    val visibleThreadId: StateFlow<Long?> = _visibleThreadId

    /** True only while the conversation screen is RESUMED. */
    @Volatile
    private var screenResumed: Boolean = false

    /** True only while the process is in the foreground for this screen's activity. */
    @Volatile
    private var appForeground: Boolean = false

    /** Cheap, allocation-free check for the ingest hot path — and the ONLY read authority check. */
    fun isVisible(threadId: Long): Boolean =
        threadId > 0L && screenResumed && appForeground && _visibleThreadId.value == threadId

    /** Legacy fallback: matches on the normalised address when no thread id is known. */
    fun isVisibleForAddress(address: String?): Boolean {
        if (!screenResumed || !appForeground) return false
        if (_visibleThreadId.value != null && _visibleThreadId.value != 0L) return false
        val current = _visibleAddress.value ?: return false
        return !address.isNullOrBlank() && current == address
    }

    /** The identity the read paths may act on, or null when nothing is genuinely visible. */
    fun identity(): VisibleConversationIdentity? {
        if (!screenResumed || !appForeground) return null
        if (_visibleThreadId.value == null) return null
        return VisibleConversationIdentity(
            threadId = _visibleThreadId.value,
            normalizedAddress = _visibleAddress.value,
            lifecycleGeneration = generation.get()
        )
    }

    /**
     * The screen became RESUMED for [threadId].
     *
     * Called from the SCREEN's lifecycle observer — never from a data load.
     */
    fun onScreenResumed(threadId: Long, normalizedAddress: String? = null) {
        if (threadId <= 0L) return
        generation.incrementAndGet()
        _visibleThreadId.value = threadId
        _visibleAddress.value = normalizedAddress
        screenResumed = true
        appForeground = true
        DiagnosticsBreadcrumbs.setVisibleConversation(threadId, null)
        ConversationReadAudit.visibilityChanged(threadId, resumed = true)
    }

    /**
     * The screen stopped being RESUMED (paused, stopped, destroyed, disposed, or navigated away).
     *
     * Requires a matching identity so a fast A → B transition cannot let A's teardown hide B.
     */
    fun onScreenPaused(threadId: Long) {
        if (threadId <= 0L) return
        if (_visibleThreadId.value != threadId) return
        generation.incrementAndGet()
        screenResumed = false
        appForeground = false
        _visibleThreadId.value = null
        _visibleAddress.value = null
        DiagnosticsBreadcrumbs.setVisibleConversation(null, null)
        ConversationReadAudit.visibilityChanged(threadId, resumed = false)
    }

    /** The whole process left the foreground: nothing is visible, whatever the composition says. */
    fun onAppBackgrounded() {
        if (!screenResumed && !appForeground) return
        generation.incrementAndGet()
        screenResumed = false
        appForeground = false
        val previous = _visibleThreadId.value
        _visibleThreadId.value = null
        _visibleAddress.value = null
        DiagnosticsBreadcrumbs.setVisibleConversation(null, null)
        previous?.let { ConversationReadAudit.visibilityChanged(it, resumed = false) }
    }

    /**
     * Deprecated compatibility hooks.
     *
     * They are kept only so existing callers compile; they deliberately do NOT grant visibility
     * anymore, because a ViewModel calling `onOpened` is exactly the bug being fixed (it means "this
     * thread was loaded or remembered", not "the user is looking at it").
     */
    @Deprecated("Use onScreenResumed from the screen lifecycle", ReplaceWith("onScreenResumed(threadId)"))
    fun onOpened(threadId: Long) = Unit

    @Deprecated("Use onScreenPaused from the screen lifecycle", ReplaceWith("onScreenPaused(threadId)"))
    fun onClosed(threadId: Long) = Unit

    /** Test seam. Never call from production code. */
    internal fun resetForTest() {
        _visibleThreadId.value = null
        _visibleAddress.value = null
        screenResumed = false
        appForeground = false
        generation.set(0L)
        DiagnosticsBreadcrumbs.setVisibleConversation(null, null)
    }
}

/**
 * Privacy-safe audit of every read decision.
 *
 * The question this answers is *"WHY did this SMS become read?"* — so it records the AUTHORITY that
 * allowed the transition (or the reason it was refused), never just "markRead was called". Thread ids
 * are hashed; no body, no phone number and no credential is ever emitted.
 */
object ConversationReadAudit {

    private const val CATEGORY = "CONVERSATION_READ"

    fun allowed(
        cause: ReadCause,
        threadId: Long,
        source: String,
        beforeRead: Boolean,
        screenLifecycle: String = if (VisibleConversationTracker.isVisible(threadId)) "RESUMED" else "NOT_RESUMED"
    ) {
        DiagnosticLog.event(
            CATEGORY,
            "cause=${cause.name} thread=${token(threadId)} source=$source " +
                "beforeRead=$beforeRead afterRead=true screen=$screenLifecycle " +
                "generation=${VisibleConversationTracker.identity()?.lifecycleGeneration ?: -1L}"
        )
    }

    fun skipped(
        reason: ReadSkipReason,
        threadId: Long,
        source: String,
        detail: String? = null
    ) {
        DiagnosticLog.event(
            CATEGORY,
            "cause=none skip=${reason.name} thread=${token(threadId)} source=$source" +
                (detail?.let { " detail=$it" } ?: "")
        )
    }

    /** The screen reported its own visibility transition — useful when a read did NOT happen. */
    fun visibilityChanged(threadId: Long, resumed: Boolean) {
        DiagnosticLog.event(
            CATEGORY,
            "screen=${if (resumed) "RESUMED" else "PAUSED"} thread=${token(threadId)}"
        )
    }

    /** Non-reversible, short reference to a thread id. Never the id itself. */
    internal fun token(threadId: Long): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(threadId.toString().toByteArray(Charsets.UTF_8))
        return "t_" + bytes.take(4).joinToString("") { "%02x".format(it) }
    }
}
