package com.autonomousone.messages.security

import android.content.Context
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.messaging.MessagingPreferences
import kotlinx.coroutines.runBlocking

/**
 * ADR-006 — per-message ASK ledger for FINANCIAL_NOTIFICATION messages.
 *
 * When the user's financial policy is ASK, each classified financial message
 * is held LOCAL until the user answers the notification prompt:
 *   "Sync once"  → this exact message becomes sync-eligible (allowSync)
 *   "Keep private" → stays LOCAL_ONLY (deny is durable, idempotent)
 *
 * Storage is message-keyed (source:providerId), never content-keyed — the
 * ledger holds no message text, senders, or OTPs (ADR-006 §21). An
 * unanswered (or swiped-away) prompt stays LOCAL forever: fail-closed.
 */
object AskPolicyLedger {

    private const val KEY_PREFIX = "firewall_ask_"

    private fun prefs(context: Context) =
        context.getSharedPreferences("firewall_ask_ledger", Context.MODE_PRIVATE)

    /** Alias kept for readability at call sites; same store. */
    private fun store(context: Context) = prefs(context)

    private fun key(source: String, providerId: Long) = KEY_PREFIX + source + "_" + providerId

    /**
     * Current ASK verdict for a message under the ASK policy:
     * true only when the user explicitly tapped "Sync once" — everything
     * else (never asked, denied, unknown) keeps the message local.
     */
    fun isSyncAllowed(context: Context, source: String, providerId: Long): Boolean =
        prefs(context).getBoolean(key(source, providerId), false)

    /** User chose "Sync once" on the prompt — record and reflect it. */
    fun allowSync(context: Context, source: String, providerId: Long) {
        store(context).edit().putBoolean(key(source, providerId), true).apply()
        stamp(context, source, providerId)
    }

    /**
     * Pure decision logic over a stored verdict — unit-testable without
     * Android: [stored] is the persisted flag (null = never answered).
     * ADR-006 §16: only an explicit TRUE grants sync; everything else
     * (null/false) keeps the message local.
     */
    fun resolveAskVerdict(stored: Boolean?): Boolean = stored == true

    /** User chose "Keep private" — durable, idempotent deny. */
    fun keepLocal(context: Context, source: String, providerId: Long) {
        prefs(context).edit().putBoolean(key(source, providerId), false).apply()
    }

    /**
     * Housekeeping: prune ledger entries older than [before] (mirrors the
     * send_segments prune pass). Call from TelephonySyncCoordinator
     * maintenance.
     */
    fun prune(context: Context, before: Long): Int {
        val p = prefs(context)
        val stale = p.all.keys.filter {
            it.startsWith(KEY_PREFIX)
        }.filter { k ->
            // Keys embed the provider id; approximate age via the stored row's
            // provider id being present is NOT a timestamp — instead we rely
            // on a parallel created-at stamp written with every entry.
            val ts = p.getLong(k + "_ts", 0L)
            ts in 1 until before
        }
        var n = 0
        val edit = p.edit()
        stale.forEach { k ->
            edit.remove(k).remove(k + "_ts")
            n++
        }
        edit.apply()
        return n
    }

    /** Internal: stamp creation time when a verdict is first recorded. */
    internal fun stamp(context: Context, source: String, providerId: Long) {
        prefs(context).edit()
            .putLong(key(source, providerId) + "_ts", System.currentTimeMillis())
            .apply()
    }
}
