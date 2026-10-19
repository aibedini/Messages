package com.autonomousone.messages.gateway

/**
 * Holds a cycle wake lock for exactly the duration of the work inside [withLock].
 *
 * The lock is a battery cost with a correctness purpose (a Doze-suspended socket kills the
 * long-poll), so it must never leak: release runs in `finally`, on every exit path including a
 * throwing cycle. The acquire/release seams are injected so `CycleWakeLockGuardTest` can prove the
 * balance without an Android `PowerManager.WakeLock`.
 */
internal class CycleWakeLockGuard(
    private val acquire: () -> Unit = {},
    private val release: () -> Unit = {},
) {

    suspend fun <T> withLock(block: suspend () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }
}
