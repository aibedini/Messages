package com.autonomousone.messages.diagnostics

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.autonomousone.messages.utils.DiagnosticLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Phase 15 main-thread stall watchdog.
 *
 * Shape: one daemon background thread posts a no-op probe to the main
 * [Looper] and waits for it. If the main thread is wedged the probe does not
 * run, and the watcher samples the growing lag every POLL_INTERVAL_MS until
 * the probe finally executes. All thresholding and throttling lives in the
 * pure [StallWatchdogEngine]; this object only owns the Android plumbing.
 *
 * Hard constraints (see [StallReportSink]):
 *  - the watchdog NEVER kills the process and NEVER restarts an Activity — the
 *    sink interface has no such method and this object holds no such reference;
 *  - nothing the watchdog does runs on the main thread: the probe is a single
 *    [CountDownLatch.countDown], the sampling is on the watcher thread, and
 *    the [DiagnosticLog] write happens on the watcher thread too, so no disk
 *    I/O is added to the UI thread;
 *  - when the main thread is healthy the watcher sleeps between probes, so it
 *    costs one post every 250 ms (four per second).
 *
 * Install once, from [com.autonomousone.messages.MessagesApp.onCreate], and
 * never again: [install] is idempotent.
 */
object MainThreadStallWatchdog {

    private const val THREAD_NAME = "main-thread-stall-watchdog"
    private const val POLL_INTERVAL_MS = 250L

    @Volatile
    private var installed = false

    @Volatile
    private var running = false

    private var watchdogThread: Thread? = null

    /**
     * Idempotent entry point. Safe to call from the main thread: it starts the
     * watcher thread and returns immediately.
     */
    fun install(
        sink: StallReportSink = DiagnosticLogStallReporter,
        breadcrumbs: (StallSeverity, Long) -> StallBreadcrumbs = { severity, duration ->
            DiagnosticsBreadcrumbs.snapshot(severity, duration)
        },
    ) {
        if (installed) return
        installed = true
        val engine = StallWatchdogEngine(sink, breadcrumbs = breadcrumbs)
        val mainHandler = Handler(Looper.getMainLooper())
        running = true
        val worker = Thread({ runLoop(mainHandler, engine) }, THREAD_NAME).apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
        watchdogThread = worker
        worker.start()
    }

    /**
     * Teardown seam. Production never calls this: the watchdog lives as long
     * as the process does.
     */
    fun stop() {
        running = false
        watchdogThread?.interrupt()
        watchdogThread = null
        installed = false
    }

    private fun runLoop(mainHandler: Handler, engine: StallWatchdogEngine) {
        // Logged here, on the watcher thread: install() runs on the main
        // thread and must not perform disk I/O (StrictMode is watching it).
        DiagnosticLog.event(
            "STALL_WATCHDOG",
            "installed warn_ms=" + StallWatchdogEngine.DEFAULT_WARNING_MS +
                " critical_ms=" + StallWatchdogEngine.DEFAULT_CRITICAL_MS
        )
        while (running) {
            val latch = CountDownLatch(1)
            val postedAt = SystemClock.uptimeMillis()
            engine.onProbePosted(postedAt)
            mainHandler.post { latch.countDown() }
            var nextSampleAt = postedAt + POLL_INTERVAL_MS
            while (running) {
                val waitForMs = (nextSampleAt - SystemClock.uptimeMillis()).coerceAtLeast(0L)
                val settled = try {
                    latch.await(waitForMs, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    return
                }
                if (settled) {
                    engine.onProbeExecuted(SystemClock.uptimeMillis())
                    break
                }
                // Still blocked: sample the lag. The engine throttles the
                // reports, so this loop only ever reads a volatile and, at
                // most, writes one log line per severity per stall. Resetting
                // nextSampleAt from "now" keeps the sampling at one probe per
                // POLL_INTERVAL_MS instead of spinning once we fall behind.
                val sampledAt = SystemClock.uptimeMillis()
                engine.onLagSample(sampledAt)
                nextSampleAt = sampledAt + POLL_INTERVAL_MS
            }
            // Pace healthy probes so we never hammer the main looper.
            val pauseMs = postedAt + POLL_INTERVAL_MS - SystemClock.uptimeMillis()
            if (pauseMs > 0L) {
                try {
                    Thread.sleep(pauseMs)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }
}

/**
 * Default sink: one privacy-safe line in the rotating on-device diagnostic
 * log. It runs on the watchdog thread, never on the main thread, and it never
 * logs a phone number, an SMS body or a token — [StallBreadcrumbs.describe]
 * only emits hashed tokens, counts and labels.
 */
object DiagnosticLogStallReporter : StallReportSink {

    override fun report(severity: StallSeverity, breadcrumbs: StallBreadcrumbs) {
        val category = when (severity) {
            StallSeverity.CRITICAL -> "STALL_CRITICAL"
            StallSeverity.WARNING -> "STALL_WARNING"
            StallSeverity.NONE -> return
        }
        DiagnosticLog.event(category, breadcrumbs.describe())
    }
}
