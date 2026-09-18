package com.autonomousone.messages.diagnostics

private const val NEVER_MS = Long.MIN_VALUE

/**
 * Pure rate limiter for watchdog reports.
 *
 * Rules (both intentionally conservative):
 *  - within a single stall episode at most one WARNING and one CRITICAL are
 *    emitted, no matter how many times the watcher samples;
 *  - a *new* stall episode is only allowed to emit its first report once
 *    [minIntervalMs] has elapsed since the previous report, so a device that
 *    hitches every few hundred milliseconds cannot flood the diagnostic log.
 *
 * The one deliberate exception: once an episode is already active, escalation
 * from WARNING to CRITICAL is never suppressed by the cooldown. Dropping a
 * "main thread was blocked for 5 s" report would defeat the point of the
 * watchdog, and the per-level dedupe already bounds it to a single critical.
 *
 * No Android APIs, no clock of its own: the caller passes monotonic
 * milliseconds, so this is fully unit testable on the JVM.
 */
class StallReportRateLimiter(
    private val minIntervalMs: Long = StallWatchdogEngine.DEFAULT_MIN_REPORT_INTERVAL_MS,
) {

    private var stallActive = false
    private var warningReported = false
    private var criticalReported = false
    private var lastReportAtMs = NEVER_MS

    /**
     * @return true when [severity] should be reported at [nowMs].
     */
    fun shouldReport(severity: StallSeverity, nowMs: Long): Boolean {
        if (severity == StallSeverity.NONE) {
            onHealthy()
            return false
        }
        if (!stallActive) {
            stallActive = true
            warningReported = false
            criticalReported = false
            val tooSoon = lastReportAtMs != NEVER_MS && nowMs - lastReportAtMs < minIntervalMs
            if (tooSoon) return false
        }
        when (severity) {
            StallSeverity.WARNING -> {
                if (warningReported) return false
                warningReported = true
            }
            StallSeverity.CRITICAL -> {
                if (criticalReported) return false
                criticalReported = true
            }
            StallSeverity.NONE -> return false
        }
        lastReportAtMs = nowMs
        return true
    }

    /** The monitored thread answered a probe: the stall episode is over. */
    fun onHealthy() {
        stallActive = false
        warningReported = false
        criticalReported = false
    }
}

/**
 * Pure core of the main-thread stall watchdog.
 *
 * The platform wrapper (see [MainThreadStallWatchdog]) posts a no-op probe to
 * the main [android.os.Handler] and feeds this engine monotonic millisecond
 * samples from its own background thread. Keeping the thresholding and
 * throttling here — with no Android, no threads and no timers — is what makes
 * "warning at ~2 s, critical at ~5 s, never kill the process" testable without
 * an emulator.
 *
 * The engine can only ever call [StallReportSink.report]. It has no reference
 * to a process handle, an Activity or a restart path.
 */
class StallWatchdogEngine(
    private val sink: StallReportSink,
    private val warningThresholdMs: Long = DEFAULT_WARNING_MS,
    private val criticalThresholdMs: Long = DEFAULT_CRITICAL_MS,
    minReportIntervalMs: Long = DEFAULT_MIN_REPORT_INTERVAL_MS,
    private val breadcrumbs: (StallSeverity, Long) -> StallBreadcrumbs = { severity, duration ->
        StallBreadcrumbs(severity = severity, stallDurationMs = duration)
    },
) {

    private val limiter = StallReportRateLimiter(minReportIntervalMs)
    private var probePostedAtMs = NEVER_MS

    init {
        require(warningThresholdMs > 0L) { "warning threshold must be positive" }
        require(criticalThresholdMs >= warningThresholdMs) {
            "critical threshold must not be below the warning threshold"
        }
        require(minReportIntervalMs >= 0L) { "minimum report interval must not be negative" }
    }

    /** Records that a probe was enqueued on the monitored thread at [nowMs]. */
    fun onProbePosted(nowMs: Long) {
        probePostedAtMs = nowMs
    }

    /**
     * Samples the current lag. Returns the severity that was reported, or
     * [StallSeverity.NONE] when the lag is healthy or the report was throttled.
     */
    fun onLagSample(nowMs: Long): StallSeverity {
        if (probePostedAtMs == NEVER_MS) return StallSeverity.NONE
        return evaluate(nowMs - probePostedAtMs, nowMs)
    }

    /** Evaluates an explicit lag; the unit-test seam for [onLagSample]. */
    fun evaluate(lagMs: Long, nowMs: Long): StallSeverity {
        val severity = classify(lagMs)
        if (severity == StallSeverity.NONE) {
            limiter.onHealthy()
            return StallSeverity.NONE
        }
        if (!limiter.shouldReport(severity, nowMs)) return StallSeverity.NONE
        // A reporting sink must never be able to kill the watchdog thread.
        runCatching { sink.report(severity, breadcrumbs(severity, lagMs)) }
        return severity
    }

    /** The monitored thread ran the probe: the stall episode is over. */
    fun onProbeExecuted(nowMs: Long) {
        probePostedAtMs = NEVER_MS
        limiter.onHealthy()
    }

    private fun classify(lagMs: Long): StallSeverity = when {
        lagMs >= criticalThresholdMs -> StallSeverity.CRITICAL
        lagMs >= warningThresholdMs -> StallSeverity.WARNING
        else -> StallSeverity.NONE
    }

    companion object {
        /** Phase 15 warning threshold: ~2 seconds of main-thread unresponsiveness. */
        const val DEFAULT_WARNING_MS: Long = 2_000L

        /** Phase 15 critical threshold: ~5 seconds of main-thread unresponsiveness. */
        const val DEFAULT_CRITICAL_MS: Long = 5_000L

        /** At most one report burst per this window across consecutive stalls. */
        const val DEFAULT_MIN_REPORT_INTERVAL_MS: Long = 30_000L
    }
}
