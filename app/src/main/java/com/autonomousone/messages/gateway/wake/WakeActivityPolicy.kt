package com.autonomousone.messages.gateway.wake

/** The two states of the adaptive activity window (STAGE-4 §3). */
enum class WakeActivity { ACTIVE, IDLE }

/** Which input put the window in its current state. Reported on the diagnostic card. */
enum class WakeActivityReason {
    WEB_CLIENT_WATCHING,
    APP_FOREGROUND,
    RECENT_MESSAGE,
    OUTBOX_PENDING,
    NONE
}

/** The four facts the ACTIVE/IDLE decision is a pure function of. */
data class WakeActivityInputs(
    /** GMweb says a web client has a live session for this device. */
    val webClientLive: Boolean = false,
    val appForeground: Boolean = false,
    /** The newest SMS timestamp already stored by the existing sync path. */
    val lastMessageAt: Long? = null,
    val outboxPending: Boolean = false,
    val now: Long = 0L,
)

/**
 * The current window plus the transition bookkeeping that makes it debounced.
 *
 * [pendingActivity] is the state an input change is asking for but which has not yet survived
 * [WakeActivityPolicy.DEBOUNCE_MS]. A flapping web session therefore cannot reconnect in a loop.
 */
data class WakeActivityState(
    val activity: WakeActivity = WakeActivity.IDLE,
    val reason: WakeActivityReason = WakeActivityReason.NONE,
    val since: Long = 0L,
    val pendingActivity: WakeActivity? = null,
    val pendingSince: Long = 0L,
)

/**
 * ACTIVE/IDLE is a pure decision, in its own Android-free class, pinned by a test — the same shape
 * as `PollStatePolicy` and `GatewayAccessPolicy`.
 *
 * ACTIVE when a web client is watching, the app is in the foreground, a message moved within five
 * minutes, or the outbox is non-empty. IDLE otherwise: the socket is closed and the forever-floor
 * (the Stage 1.1 long-poll) carries the device.
 */
object WakeActivityPolicy {

    const val SMS_WINDOW_MS = 5 * 60_000L

    /** Reuse of the 3 s debounce already used by `ConnectionSupervisor`. */
    const val DEBOUNCE_MS = 3_000L

    /** The un-debounced decision and the input that produced it. Precedence is intentional. */
    fun desired(inputs: WakeActivityInputs): Pair<WakeActivity, WakeActivityReason> = when {
        inputs.webClientLive -> WakeActivity.ACTIVE to WakeActivityReason.WEB_CLIENT_WATCHING
        inputs.appForeground -> WakeActivity.ACTIVE to WakeActivityReason.APP_FOREGROUND
        inputs.lastMessageAt != null &&
            inputs.now - inputs.lastMessageAt <= SMS_WINDOW_MS ->
            WakeActivity.ACTIVE to WakeActivityReason.RECENT_MESSAGE
        inputs.outboxPending -> WakeActivity.ACTIVE to WakeActivityReason.OUTBOX_PENDING
        else -> WakeActivity.IDLE to WakeActivityReason.NONE
    }

    /**
     * Applies debounce to [WakeActivityPolicy.desired].
     *
     * The first evaluation is immediate: a fresh socket source must not wait three seconds before
     * it may connect. Afterwards a change only takes effect once it has been stable for
     * [DEBOUNCE_MS]; the previous state keeps its original `since`.
     */
    fun next(previous: WakeActivityState?, inputs: WakeActivityInputs): WakeActivityState {
        val (wantedActivity, wantedReason) = desired(inputs)
        if (previous == null) {
            return WakeActivityState(
                activity = wantedActivity,
                reason = wantedReason,
                since = inputs.now
            )
        }
        if (wantedActivity == previous.activity) {
            return previous.copy(
                reason = wantedReason,
                pendingActivity = null,
                pendingSince = 0L
            )
        }
        val pendingSince = when {
            previous.pendingActivity == wantedActivity -> previous.pendingSince
            else -> inputs.now
        }
        val stableFor = inputs.now - pendingSince
        return if (stableFor >= DEBOUNCE_MS) {
            WakeActivityState(
                activity = wantedActivity,
                reason = wantedReason,
                since = pendingSince,
                pendingActivity = null,
                pendingSince = 0L
            )
        } else {
            previous.copy(
                pendingActivity = wantedActivity,
                pendingSince = pendingSince
            )
        }
    }
}
