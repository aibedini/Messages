package com.autonomousone.messages.diagnostics

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import com.autonomousone.messages.BuildConfig
import com.autonomousone.messages.utils.DiagnosticLog
import java.util.IdentityHashMap
import java.util.concurrent.Executors

/**
 * Phase 17.13: DEBUG-only, dependency-free jank instrumentation.
 *
 * Uses the platform's [FrameMetrics] via
 * [Window.addOnFrameMetricsAvailableListener] — no new dependency and no
 * third-party analytics. Nothing is uploaded and nothing runs in release
 * builds: [install] returns before it even touches the listener object, so the
 * reporter executor is never created there.
 *
 * Cost control:
 *  - only DEBUG builds;
 *  - the per-frame callback touches a handful of longs; it never allocates
 *    beyond the platform-provided [FrameMetrics] and never writes to disk;
 *  - the summary is emitted at most once every [REPORT_EVERY_FRAMES] frames
 *    and once on Activity pause, and the disk write happens on a dedicated
 *    daemon thread, never on the main thread.
 *
 * Frame timing here is a heuristic (a frame slower than
 * [JANKY_FRAME_NS] counts as janky), not a replacement for Perfetto.
 */
object DebugFrameMetrics {

    private const val TAG = "FRAME_METRICS"

    /** One 60 Hz frame. A frame slower than this is counted as janky. */
    private const val JANKY_FRAME_NS = 16_000_000L

    /** At most this many frames between two summary lines. */
    private const val REPORT_EVERY_FRAMES = 600L

    /**
     * DEBUG-only entry point; a no-op in release. Call once, from
     * Application.onCreate.
     */
    fun install(application: Application) {
        if (!BuildConfig.DEBUG) return
        application.registerActivityLifecycleCallbacks(Listener)
        Log.i(TAG, "frame_metrics_listener_installed janky_threshold_ns=" + JANKY_FRAME_NS)
    }

    private object Listener : Application.ActivityLifecycleCallbacks {

        private val mainHandler = Handler(Looper.getMainLooper())
        private val counters = IdentityHashMap<Activity, FrameCounter>()
        private val reporter = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "frame-metrics-reporter").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }

        override fun onActivityResumed(activity: Activity) {
            val counter = FrameCounter(reporter)
            counters[activity] = counter
            activity.window?.addOnFrameMetricsAvailableListener(counter, mainHandler)
        }

        override fun onActivityPaused(activity: Activity) {
            val counter = counters.remove(activity) ?: return
            activity.window?.removeOnFrameMetricsAvailableListener(counter)
            counter.report()
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private class FrameCounter(
        private val reporter: java.util.concurrent.Executor,
    ) : Window.OnFrameMetricsAvailableListener {

        private var frames = 0L
        private var jankyFrames = 0L
        private var maxFrameNs = 0L
        private var framesAtLastReport = 0L

        override fun onFrameMetricsAvailable(
            window: Window,
            frameMetrics: FrameMetrics,
            dropCountSinceLastInvocation: Int,
        ) {
            val totalNs = frameMetrics.getMetric(FrameMetrics.TOTAL_DURATION)
            frames++
            if (totalNs > JANKY_FRAME_NS) jankyFrames++
            if (totalNs > maxFrameNs) maxFrameNs = totalNs
            if (frames - framesAtLastReport >= REPORT_EVERY_FRAMES) {
                framesAtLastReport = frames
                report()
            }
        }

        fun report() {
            if (frames == 0L) return
            val summary = "frames=" + frames + " janky=" + jankyFrames +
                " max_ms=" + (maxFrameNs / 1_000_000L)
            frames = 0L
            jankyFrames = 0L
            maxFrameNs = 0L
            framesAtLastReport = 0L
            reporter.execute {
                // Off the main thread: DiagnosticLog writes to app-private disk.
                DiagnosticLog.event("FRAME_METRICS", summary)
            }
        }
    }
}
