package com.autonomousone.messages.gateway.wake

/**
 * Client-side defence against a hostile or broken wake server (STAGE-4 §2.2).
 *
 * The conflated `wake` channel already collapses a burst of wakes into one pull cycle; this caps
 * the *pull* rate itself to one per second. Sustained excess — more than [ceilingPerWindow] frames
 * per window for [abusiveWindowsBeforeDisconnect] consecutive windows — is treated as server
 * misbehaviour: the caller disconnects, backs off and reports `TRANSPORT_FAILED`.
 *
 * Pure and clock-injected so the abuse rule is a test, not a comment.
 */
class WakeRateLimiter(
    private val minWakeIntervalMs: Long = 1_000L,
    private val windowMs: Long = 60_000L,
    private val ceilingPerWindow: Int = 60,
    private val abusiveWindowsBeforeDisconnect: Int = 2,
) {

    enum class Decision {
        /** Deliver exactly one `onWake` now. */
        WAKE,

        /** A valid wake frame arrived inside the one-second floor; counted, not delivered. */
        SUPPRESSED,

        /** The server has exceeded the ceiling for several windows: disconnect and fall back. */
        ABUSE
    }

    private var currentBucket: Long = Long.MIN_VALUE
    private var framesInBucket: Int = 0
    private var consecutiveAbusiveWindows: Int = 0
    private var lastWakeAt: Long? = null

    fun onFrame(now: Long): Decision {
        if (windowMs <= 0L) return deliver(now)
        val bucket = Math.floorDiv(now, windowMs)
        if (bucket != currentBucket) {
            // Finalise the bucket that just ended. Only count it as abusive when it genuinely
            // exceeded the ceiling; one noisy frame is not "several minutes of misbehaviour".
            if (currentBucket != Long.MIN_VALUE && framesInBucket > ceilingPerWindow) {
                consecutiveAbusiveWindows++
            } else if (currentBucket != Long.MIN_VALUE) {
                consecutiveAbusiveWindows = 0
            }
            currentBucket = bucket
            framesInBucket = 0
            if (consecutiveAbusiveWindows >= abusiveWindowsBeforeDisconnect) {
                return Decision.ABUSE
            }
        }
        framesInBucket++
        return deliver(now)
    }

    private fun deliver(now: Long): Decision {
        val last = lastWakeAt
        return if (last == null || now - last >= minWakeIntervalMs) {
            lastWakeAt = now
            Decision.WAKE
        } else {
            Decision.SUPPRESSED
        }
    }
}
