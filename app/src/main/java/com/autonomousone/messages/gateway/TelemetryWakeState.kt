package com.autonomousone.messages.gateway

/**
 * Single-flight + dirty, for telemetry reports.
 *
 * **The bug this prevents.** A report is an HTTP POST with a device payload. Two triggers arriving
 * together must not produce two overlapping POSTs (wasted radio and battery, and two racing writers
 * of the same "last success" fact), and a trigger arriving DURING a report must not be dropped
 * either — dropping it means GMweb keeps a stale SIM list until the next 60-second tick, which is
 * exactly the staleness this work exists to remove.
 *
 * So: at most one report in flight, and at most ONE more pass queued behind it, however many
 * triggers arrive while it runs (three SIM callbacks in a burst become one additional report, not
 * three).
 *
 * Pure, Android-free and independently testable: the burst semantics are asserted directly rather
 * than inferred from timing.
 */
internal class TelemetryWakeState {

    private val lock = Any()
    private var inFlight = false
    private var dirty = false

    /** A trigger arrived (any thread). */
    fun markTriggered() = synchronized(lock) {
        if (inFlight) dirty = true
    }

    /**
     * Claim the right to run a report.
     *
     * @return true when this caller may run; false when a report is already in flight (its own
     *   finish pass will pick the trigger up).
     */
    fun beginRun(): Boolean = synchronized(lock) {
        if (inFlight) false else { inFlight = true; true }
    }

    /**
     * Release the run.
     *
     * @return true when a trigger arrived while it ran, in which case the caller must run exactly
     *   one more pass. `inFlight` deliberately stays set for that pass, so a trigger arriving during
     *   it is queued the same way instead of racing it.
     */
    fun endRun(): Boolean = synchronized(lock) {
        if (dirty) {
            dirty = false
            true
        } else {
            inFlight = false
            false
        }
    }

    val isInFlight: Boolean get() = synchronized(lock) { inFlight }
}
