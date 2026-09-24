package com.autonomousone.messages.observer

import android.content.Context
import android.util.Log
import com.autonomousone.messages.data.ChangeRouter
import com.autonomousone.messages.gateway.health.GatewayLog
import com.autonomousone.messages.gateway.health.GatewayLogSeverity
import com.autonomousone.messages.gateway.health.GatewayLogSubsystem
import com.autonomousone.messages.repository.SmsRepository

/**
 * Keeps provider → cloud replication alive while no UI is.
 *
 * THE DEFECT THIS FIXES: the SMS/MMS `ContentObserver` was registered ONLY by
 * `HomeViewModel.init` and `ConversationViewModel.init`, and `ChangeRouter.route` had exactly one
 * caller — `HomeViewModel`. So with no ViewModel alive (app swiped away, screen off after the
 * process was reclaimed, or any headless start) a newly arriving SMS produced **no replication at
 * all**: no outbox row, no log line, and a healthy-looking green gateway
 * (`docs/gateway-replication-audit.md`, Blocker 1). The mission's central promise — "new incoming
 * SMS/MMS must reliably replicate" — therefore held only while the UI happened to be on screen.
 *
 * WHY THIS IS SAFE TO RUN ALONGSIDE THE UI'S OBSERVER: both observers may fire for the same change,
 * so `ChangeRouter.route` can be called twice. That is idempotent by construction —
 * `ProviderRepairQueue.enqueue` is documented "idempotent for repeated notifications of the SAME
 * change" (it bumps a generation rather than inserting a duplicate), and the repair worker that
 * consumes it re-reads the same provider row and finds it UNCHANGED. So the cost of the overlap is
 * a redundant read, not a duplicate event. The alternative — making this relay the only router and
 * stripping the call out of `HomeViewModel` — would mean touching the UI's change handling for no
 * correctness gain.
 *
 * WHAT IT DELIBERATELY DOES NOT DO: it does not touch `ThreadMessageCache`. That is UI cache
 * invalidation and is meaningless without a UI; the ViewModel's own observer still owns it.
 *
 * LIFETIME: owned by [com.autonomousone.messages.gateway.GatewayService], so it runs exactly as
 * long as the gateway does — which is what "replication is on" means. It is registered even while
 * the device is offline, on purpose: an event must become durable locally whether or not it can be
 * uploaded yet, so nothing is lost during an outage.
 */
class GatewayChangeRelay(context: Context) {

    private val appContext = context.applicationContext
    private val repository = SmsRepository(appContext)

    private var observer: SmsContentObserver? = null

    /** True while the relay is registered. Surfaced so diagnostics can prove it is running. */
    val isRunning: Boolean
        get() = observer != null

    /** Registers the observer. Idempotent: a second call is a no-op. */
    fun start() {
        if (observer != null) return
        val relay = SmsContentObserver { batch ->
            // A failure here must never take down the observer: the next provider change is
            // another chance, and the durable repair queue is what makes that acceptable.
            runCatching { ChangeRouter.route(appContext, batch) }
                .onFailure { Log.w(TAG, "provider change could not be routed", it) }
        }
        try {
            repository.registerObserver(relay)
            observer = relay
            runningProcessWide = true
            Log.i(TAG, "telephony change relay started (replication is UI-independent)")
            GatewayLog.record(
                severity = GatewayLogSeverity.INFO,
                subsystem = GatewayLogSubsystem.SUPERVISOR,
                code = "TELEPHONY_RELAY",
                title = "Telephony relay started",
                detail = "provider changes now reach replication without the UI"
            )
        } catch (e: Exception) {
            // A missing READ_SMS grant throws here. That is a real, visible condition rather than
            // a silent one: the replication prerequisite evaluator reports it as
            // TelephonyPermissionMissing.
            runningProcessWide = false
            Log.w(TAG, "telephony change relay could not start", e)
        }
    }

    /** Unregisters the observer. Idempotent. */
    fun stop() {
        val current = observer ?: return
        observer = null
        runningProcessWide = false
        runCatching { repository.unregisterObserver(current) }
            .onFailure { Log.w(TAG, "telephony change relay could not stop", it) }
        Log.i(TAG, "telephony change relay stopped")
    }

    companion object {
        private const val TAG = "GW_CHANGE_RELAY"

        /**
         * Whether the relay is registered ANYWHERE in this process.
         *
         * Read by diagnostics so that "is background replication actually on?" has an answer that
         * does not require the service instance. This exists because the defect it reports — a
         * phone that replicates only while a screen is open — was completely invisible: nothing
         * logged, no outbox row, and a green gateway.
         */
        @Volatile
        var runningProcessWide: Boolean = false
            private set
    }
}
