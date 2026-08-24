package com.autonomousone.messages.sms

import android.util.Log
import java.util.ArrayDeque

/**
 * Simple sliding-window rate limiter for outgoing SMS.
 *
 * Protects the SIM from operator throttling / temporary blocking when a
 * burst of messages arrives (e.g. gateway automation). Tracks send timestamps
 * in-process; the window is per-app-lifetime which is fine because operators
 * count short bursts, not historical totals.
 *
 * Thread-safe: all sends funnel through [SmsSender] from worker threads.
 */
object SendRateLimiter {

    private const val TAG = "RATE_LIMITER"

    @Volatile
    var enabled: Boolean = false

    @Volatile
    var maxMessages: Int = 10

    @Volatile
    var windowMillis: Long = 60_000L

    // Timestamps of sends inside the current window.
    private val stamps = ArrayDeque<Long>()

    /** Blocks until a send slot is available. Returns wait time in ms (0 = immediate). */
    @Synchronized
    fun acquireSlot(): Long {
        if (!enabled) return 0L

        val now = System.currentTimeMillis()
        // Evict timestamps outside the window.
        while (stamps.isNotEmpty() && now - stamps.first() > windowMillis) {
            stamps.removeFirst()
        }

        if (stamps.size < maxMessages) {
            stamps.addLast(now)
            return 0L
        }

        // Wait until the oldest stamp exits the window.
        val oldest = stamps.first()
        val waitMs = (oldest + windowMillis - now).coerceAtLeast(100L)
        Log.i(TAG, "Rate limit reached ($maxMessages/$windowMillis ms) — waiting ${waitMs}ms")
        return waitMs
    }

    /** Records that a message was sent at [at] time. Call AFTER the actual send. */
    @Synchronized
    fun record(at: Long = System.currentTimeMillis()) {
        if (!enabled) return
        stamps.addLast(at)
    }

    /** Current number of recorded sends inside the window. For UI display. */
    @Synchronized
    fun usedInWindow(): Int {
        val now = System.currentTimeMillis()
        while (stamps.isNotEmpty() && now - stamps.first() > windowMillis) {
            stamps.removeFirst()
        }
        return stamps.size
    }
}
