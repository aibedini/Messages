package com.autonomousone.messages.gateway

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.SubscriptionManager
import android.util.Log
import com.autonomousone.messages.messaging.SimDiscoveryResult
import com.autonomousone.messages.messaging.SimManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/** The two facts about subscriptions GMweb can act on. */
data class SubscriptionSnapshot(
    val subscriptionIds: List<Int>,
    val defaultSmsSubscriptionId: Int
)

/**
 * Did anything actually change? Pure, so "we were told about a change" and "there was a change" stay
 * two different questions.
 *
 * `OnSubscriptionsChangedListener` is documented as a hint: it fires for changes this app has no
 * interest in, and it fires several times for one physical event (an eSIM toggle produces a burst).
 * Reporting telemetry on every callback would be a burst of identical POSTs; reporting only when the
 * observable facts differ is both cheaper and more honest.
 */
object SubscriptionChangePolicy {

    /** True when the set of active subscriptions or the default SMS subscription differs. */
    fun changed(previous: SubscriptionSnapshot?, current: SubscriptionSnapshot): Boolean {
        if (previous == null) return true
        // Compared as SETS: the order a subscription list arrives in is not a fact about the device,
        // and treating it as one would fire a telemetry report for a reordering.
        return previous.subscriptionIds.toSet() != current.subscriptionIds.toSet() ||
            previous.defaultSmsSubscriptionId != current.defaultSmsSubscriptionId
    }

    /** True when only the default SMS line moved — a distinct reason to tell GMweb about. */
    fun defaultChanged(previous: SubscriptionSnapshot?, current: SubscriptionSnapshot): Boolean {
        if (previous == null) return false
        return previous.defaultSmsSubscriptionId != current.defaultSmsSubscriptionId &&
            previous.subscriptionIds.toSet() == current.subscriptionIds.toSet()
    }
}

/**
 * Watches the device's SIMs and tells telemetry the moment they move.
 *
 * **What was wrong.** SIM state reached GMweb only through the 60-second telemetry loop, so a SIM
 * inserted, ejected, or re-designated as the default SMS line could be stale for up to a minute —
 * and GMweb's SIM picker is exactly the surface where a stale list makes the user choose a line that
 * is no longer there. The subscription callback closes that window.
 *
 * Lifecycle-safety, deliberately:
 *  - [start] and [stop] are idempotent, so a supervisor that reconciles often cannot register two
 *    listeners (which would double every report);
 *  - registration happens on the main looper, because the one-argument listener form binds to the
 *    calling thread's looper and a background thread without one would throw;
 *  - a missing `READ_PHONE_STATE` is not an error here: registering without it throws
 *    `SecurityException`, and the permission-grant path is what re-triggers discovery. Nothing is
 *    registered and nothing is spammed;
 *  - every callback is debounced, so a burst collapses into one telemetry report.
 */
class SubscriptionChangeMonitor(
    context: Context,
    private val scope: CoroutineScope,
    private val onLog: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "GM_SIM"
        /** Let an eSIM toggle's burst settle before reading the settled state. */
        internal const val DEBOUNCE_MS = 900L
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var executor: java.util.concurrent.ExecutorService? = null
    private var listener: SubscriptionManager.OnSubscriptionsChangedListener? = null
    private var debounceJob: Job? = null
    private var lastSnapshot: SubscriptionSnapshot? = null

    /** Intent, separate from "the callback is registered now" (registration hops to the main looper). */
    @Volatile
    private var started = false

    /** Register once. Calling twice does nothing (the second registration would double every report). */
    fun start() {
        if (started) return
        started = true
        executor = Executors.newSingleThreadExecutor()
        mainHandler.post { registerOnMain() }
    }

    /** Unregister and cancel any pending debounce. Calling twice does nothing. */
    fun stop() {
        if (!started && listener == null) return
        started = false
        debounceJob?.cancel()
        debounceJob = null
        lastSnapshot = null
        val active = listener
        listener = null
        val currentExecutor = executor
        executor = null
        mainHandler.post {
            if (active != null) {
                runCatching { subscriptionManager()?.removeOnSubscriptionsChangedListener(active) }
                    .onFailure { Log.w(TAG, "subscription listener unregister failed", it) }
            }
        }
        currentExecutor?.shutdown()
    }

    private fun subscriptionManager(): SubscriptionManager? =
        appContext.getSystemService(SubscriptionManager::class.java)

    private fun registerOnMain() {
        // stop() may have run while this post was queued: registering after stop would leak a
        // listener that reports changes for a gateway the user has switched off.
        if (listener != null || !started) return
        val manager = subscriptionManager()
        val granted = SimManager(appContext).hasReadPhoneState()
        val callback = object : SubscriptionManager.OnSubscriptionsChangedListener() {
            override fun onSubscriptionsChanged() {
                onSubscriptionsMaybeChanged()
            }
        }
        val registered = runCatching {
            val mgr = manager ?: error("no SubscriptionManager")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val exec = executor ?: error("monitor not started")
                mgr.addOnSubscriptionsChangedListener(exec, callback)
            } else {
                @Suppress("DEPRECATION")
                mgr.addOnSubscriptionsChangedListener(callback)
            }
        }
        if (registered.isFailure) {
            // SecurityException without READ_PHONE_STATE, or an OEM that refuses the callback. Not
            // fatal: the permission-grant and reconnect triggers still refresh the SIM list, and this
            // is recorded rather than thrown into a service callback.
            Log.w(TAG, "subscription listener unavailable", registered.exceptionOrNull())
            return
        }
        listener = callback
        lastSnapshot = currentSnapshot()
        if (!granted) {
            Log.i(TAG, "subscription listener registered without READ_PHONE_STATE")
        }
    }

    /**
     * Read the current facts. Uses the tri-state discovery so "permission missing" is not turned into
     * "a SIM was removed" — a distinction that would otherwise fire a bogus change report every time
     * the permission is absent.
     */
    private fun currentSnapshot(): SubscriptionSnapshot? {
        val manager = SimManager(appContext)
        return when (val discovery = manager.discover()) {
            is SimDiscoveryResult.Available -> SubscriptionSnapshot(
                subscriptionIds = discovery.sims.map { it.subscriptionId }.sorted(),
                defaultSmsSubscriptionId = manager.defaultSmsSubscriptionId()
            )
            // Not observable: keep the previous snapshot and report nothing. A non-answer is not a
            // change.
            SimDiscoveryResult.PermissionMissing -> lastSnapshot
            is SimDiscoveryResult.Failed -> lastSnapshot
        }
    }

    private fun onSubscriptionsMaybeChanged() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            val current = currentSnapshot() ?: return@launch
            val previous = lastSnapshot
            if (!SubscriptionChangePolicy.changed(previous, current)) return@launch
            lastSnapshot = current
            val defaultMoved = SubscriptionChangePolicy.defaultChanged(previous, current)
            TelemetryHealth.onSubscriptionChange()
            onLog(
                "📶 SIM state changed (${current.subscriptionIds.size} active" +
                    (if (defaultMoved) ", default SMS line moved" else "") + ")"
            )
            com.autonomousone.messages.utils.DiagnosticLog.event(
                "GM_SIM",
                "subscription_change active=${current.subscriptionIds.size} " +
                    "defaultChanged=$defaultMoved"
            )
            DeviceTelemetry.requestImmediate(
                if (defaultMoved) TelemetryTrigger.DEFAULT_SMS_CHANGED
                else TelemetryTrigger.SUBSCRIPTIONS_CHANGED
            )
        }
    }
}
