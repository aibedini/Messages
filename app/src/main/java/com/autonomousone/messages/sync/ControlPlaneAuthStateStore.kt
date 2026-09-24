package com.autonomousone.messages.sync

import com.autonomousone.messages.data.ControlPlaneAuthStateDao
import com.autonomousone.messages.data.ControlPlaneAuthStateEntity

/**
 * The one place a control-plane answer becomes durable state (mission §44/§56).
 *
 * It exists so that "we were refused" is decided once, by [ControlPlaneAuthPolicy], and recorded
 * once. Every authenticated agent call goes through the heartbeat, which is already documented as the
 * AUTH dimension's producer (`HeartbeatManager`), so there is a single producer — not because other
 * routes could not observe a 403, but because a second observer is a second rule, and the last time
 * this rule was restated at a call site it lost the 401/403 clause and deleted a device's credentials.
 */
class ControlPlaneAuthStateStore(private val dao: ControlPlaneAuthStateDao) {

    /**
     * Record one authenticated observation and return what the caller must do.
     *
     * Guarded end to end: if the durable write or read fails, the caller is told "no change, take no
     * action". That direction matters — an unreadable row must not fabricate a revocation (which would
     * hold all uploads) and must not fabricate a re-enrollment (which would destroy credentials).
     */
    suspend fun observe(
        signal: ControlPlaneAuthSignal,
        httpStatus: Int? = null,
        now: Long = System.currentTimeMillis(),
    ): ControlPlaneAuthDecision {
        val previous = runCatching { dao.get()?.state() }.getOrNull() ?: ControlPlaneAuthState()
        val decision = ControlPlaneAuthPolicy.decide(previous, signal, httpStatus, now)
        if (decision.state != previous) {
            runCatching { dao.upsert(ControlPlaneAuthStateEntity.of(decision.state, now)) }
        }
        return decision
    }

    /**
     * Whether the control plane currently refuses this device.
     *
     * False when the row cannot be read, deliberately: the caller uses this to decide whether to HOLD
     * all replication, and a failure to read a diagnostic row must not be able to stop the gateway.
     * The opposite default would let a corrupt row silently freeze every upload.
     */
    suspend fun isRevoked(): Boolean =
        runCatching { dao.get()?.state()?.revoked == true }.getOrDefault(false)
}
