package com.autonomousone.messages.messaging

import android.content.Context
import android.telephony.SubscriptionManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The device's ACTIVE SIMs, as a LIVE flow.
 *
 * ## Why `remember { simManager.getActiveSims() }` is not enough
 *
 * That call is a one-shot read cached for the lifetime of the composition. It answers "which SIMs
 * were present when this screen opened", which is the wrong question for a SIM selector: a user can
 * eject a card, disable a line in Settings, or insert a second SIM while the conversation is open, and
 * the cached list would keep offering a line that no longer exists — or hide the one just added. The
 * saved preference would then resolve against a stale inventory, and the selector could silently
 * disagree with what the send path sees at execution time.
 *
 * ## Why `OnSubscriptionsChangedListener`
 *
 * Android documents it as a HINT that the subscription set may have changed rather than a precise
 * event, so the flow RE-READS the inventory whenever it fires instead of trusting a payload. A hint
 * that triggers a fresh read cannot be wrong about the result, only about the timing — and a
 * `distinctUntilChanged` collapses the spurious wakeups.
 *
 * `READ_PHONE_STATE` is not held in every state: without it the read returns an empty list, which is
 * honestly "I cannot see any SIMs" and is exactly what [SimManager.discover] already distinguishes
 * from "there are none".
 */
object ActiveSimsFlow {

    /**
     * Emits the active SIM list now and again whenever Android hints the subscription set changed.
     *
     * The list is read on the caller's dispatcher; collect this on a background dispatcher or
     * `collectAsState` from a composable that already owns a lifecycle.
     */
    fun observe(context: Context): Flow<List<SimInfo>> = callbackFlow {
        val manager = SimManager(context)

        fun emitCurrent() {
            // trySend over a suspending send: the read happens on a platform callback that cannot
            // suspend, and a dropped intermediate list is harmless because the next hint re-reads.
            trySend(
                runCatching { manager.getActiveSims() }.getOrDefault(emptyList())
            )
        }

        emitCurrent()

        val subscriptionManager = runCatching {
            context.getSystemService(SubscriptionManager::class.java)
                ?: SubscriptionManager.from(context)
        }.getOrNull()

        val listener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
            override fun onSubscriptionsChanged() {
                emitCurrent()
            }
        }
        val registered = runCatching {
            subscriptionManager?.addOnSubscriptionsChangedListener(listener)
            subscriptionManager != null
        }.getOrDefault(false)

        if (!registered) {
            // No listener means no further updates, but the FIRST read is still correct and useful.
            // The flow stays open so the caller is not forced to handle a special case; it simply
            // never emits again.
            trySend(manager.getActiveSims())
        }

        awaitClose {
            runCatching { subscriptionManager?.removeOnSubscriptionsChangedListener(listener) }
        }
    }.distinctUntilChanged()
}
