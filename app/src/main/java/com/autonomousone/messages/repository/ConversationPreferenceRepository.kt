package com.autonomousone.messages.repository

import android.content.Context
import com.autonomousone.messages.data.ConversationPreferenceDao
import com.autonomousone.messages.data.ConversationPreferenceEntity
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.utils.DiagnosticLog
import kotlinx.coroutines.flow.Flow

/**
 * Read/Write access to per-conversation USER state (v3.4.0).
 *
 * This is the ONLY place that writes `conversation_preferences`. Every writer
 * is field-scoped (see ConversationPreferenceDao) so an unrelated action can
 * never clobber another: marking unread cannot clear a mute, muting cannot clear
 * a category override, and a spam report cannot clear a channel flag.
 *
 * NOTHING here touches Telephony. In particular `manualUnread` never rewrites
 * the provider's READ column — it is a UI bookmark, and rewriting provider state
 * would make it fight the sync engine on every refresh.
 */
class ConversationPreferenceRepository(context: Context) {

    /**
     * Application context, held for the sticky-SIM path only.
     *
     * That path needs the SIM inventory and the event emitter, neither of which is reachable through
     * the DAO. The application context is kept (not the caller's) so a repository captured by a
     * screen cannot leak an Activity.
     */
    private val appContext: Context = context.applicationContext

    private val dao: ConversationPreferenceDao =
        MessagesDatabase.get(context).conversationPreferenceDao()

    fun observe(threadId: Long): Flow<ConversationPreferenceEntity?> = dao.observe(threadId)

    suspend fun get(threadId: Long): ConversationPreferenceEntity? = dao.get(threadId)

    /** Home unread = real unread OR the user bookmark. */
    fun observeManuallyUnreadThreadIds(): Flow<List<Long>> =
        dao.observeManuallyUnreadThreadIds()

    fun observeSpamThreadIds(): Flow<List<Long>> = dao.observeSpamThreadIds()

    fun observeMutedThreadIds(now: Long): Flow<List<Long>> =
        dao.observeMutedThreadIds(now, ConversationPreferenceEntity.MUTE_FOREVER)

    /**
     * Notification-side check. Expiry is a COMPARISON: no unmute worker is
     * scheduled, so `mutedUntil <= now` is already unmuted.
     */
    suspend fun isMuted(threadId: Long, now: Long): Boolean =
        dao.get(threadId)?.isMuted(now) ?: false

    /**
     * Blocking variant for `NotificationHelper` (background thread only — see
     * ConversationPreferenceDao.getBlocking). The mute gate must not suspend,
     * because showSmsNotification is a plain object method.
     */
    fun isMutedBlocking(threadId: Long, now: Long): Boolean =
        dao.getBlocking(threadId)?.isMuted(now) ?: false

    /** Blocking variant for the notification channel decision. */
    fun hasCustomNotificationChannelBlocking(threadId: Long): Boolean =
        dao.getBlocking(threadId)?.customNotificationChannel == true

    suspend fun hasCustomNotificationChannel(threadId: Long): Boolean =
        dao.get(threadId)?.customNotificationChannel == true

    suspend fun setManualUnread(threadId: Long, unread: Boolean, now: Long) {
        dao.setManualUnread(threadId, unread, now)
        DiagnosticLog.event("MARK_UNREAD", "thread=$threadId unread=$unread")
    }

    suspend fun setMutedUntil(threadId: Long, mutedUntil: Long, now: Long) {
        dao.setMutedUntil(threadId, mutedUntil, now)
        DiagnosticLog.event("MUTE", "thread=$threadId until=$mutedUntil")
    }

    suspend fun unmute(threadId: Long, now: Long) {
        dao.setMutedUntil(threadId, 0L, now)
        DiagnosticLog.event("MUTE", "thread=$threadId until=0")
    }

    suspend fun enableCustomNotificationChannel(threadId: Long, now: Long) {
        dao.enableCustomNotificationChannel(threadId, now)
        DiagnosticLog.event("CONV_NOTIFICATION", "thread=$threadId channel=custom")
    }

    /** null clears the override back to the automatic category. */
    suspend fun setCategoryOverride(threadId: Long, category: String?, now: Long) {
        dao.setCategoryOverride(threadId, category, now)
        DiagnosticLog.event("CATEGORY", "thread=$threadId override=${category ?: "auto"}")
    }

    suspend fun markSpam(threadId: Long, blockedByReport: Boolean, now: Long) {
        dao.markSpam(threadId, blockedByReport, now)
        DiagnosticLog.event("SPAM_ACTION", "thread=$threadId reported=1 blockedByReport=$blockedByReport")
    }

    suspend fun clearSpam(threadId: Long, clearBlockProvenance: Boolean, now: Long) {
        dao.clearSpam(threadId, clearBlockProvenance, now)
        DiagnosticLog.event(
            "SPAM_ACTION",
            "thread=$threadId reported=0 clearBlockProvenance=$clearBlockProvenance"
        )
    }

    /**
     * Outcome of a sticky-SIM change, as THREE facts rather than a boolean.
     *
     * ## The defect this replaces
     *
     * The previous signature returned one `Boolean`, and returned `false` for two unrelated
     * situations: "the preference was not saved" and "the preference WAS saved but the replication
     * event could not be queued". A caller treating `false` as "nothing happened" would either undo a
     * change the user had already made, or report a failure for a change that is durably on the device.
     * The two need different words, so they are different values.
     */
    enum class PreferenceUpdateResult {
        /** Durable on the device AND the canonical encrypted event is queued. */
        PERSISTED_AND_QUEUED,

        /**
         * Durable on the device, event NOT queued.
         *
         * The local truth is correct and must NOT be reverted — reverting would discard a choice the
         * user actually made. Replication is owed, and the durable outbox is what will carry it, so the
         * honest report is "saved, not yet replicated" rather than a failure.
         */
        PERSISTED_PENDING_REPLICATION,

        /** The write itself failed; nothing changed. */
        NOT_PERSISTED
    }

    /**
     * Set (or clear) this conversation's sticky SIM — the LOCAL user path.
     *
     * ## Why this is one method and not two
     *
     * The durable row and the replication event must move together. If a caller could persist a
     * preference without publishing it, the app and GMweb would disagree about which line a
     * conversation uses, with no error anywhere — the worst possible shape for a misroute.
     *
     * ## What it deliberately does NOT do
     *
     * It does not send anything. Choosing a line is a preference, not a message: sending here would
     * put an unintended SMS on the wire the moment a user tapped a selector.
     *
     * It also performs no remote I/O. The event is written to the durable outbox with one local insert
     * and uploaded later by the existing pipeline, so no HTTP call can sit inside a database write.
     *
     * @param simRef the chosen line, or null to return to the phone's default line.
     * @param requestedSubscriptionId the Android subscription id behind [simRef], used ONLY to snapshot
     *   the display fields. It is never stored as authority — routing re-resolves the ref.
     */
    suspend fun setPreferredSim(
        threadId: Long,
        simRef: String?,
        requestedSubscriptionId: Int?,
        now: Long
    ): PreferenceUpdateResult {
        val simManager = com.autonomousone.messages.messaging.SimManager(appContext)
        // Snapshot the display fields from the LIVE inventory. A snapshot for a line we cannot see is
        // absent rather than guessed, because a wrong label on a routing control is worse than none.
        val chosen = requestedSubscriptionId?.let { id ->
            runCatching { simManager.getActiveSims().firstOrNull { it.subscriptionId == id } }.getOrNull()
        }
        try {
            dao.setPreferredSim(
                threadId = threadId,
                simRef = simRef,
                slotIndex = chosen?.slotIndex,
                displayName = chosen?.displayName,
                carrierName = chosen?.carrierName,
                now = now
            )
        } catch (e: Exception) {
            DiagnosticLog.event("SMS_SIM_PREF", "persist-failed thread=$threadId")
            return PreferenceUpdateResult.NOT_PERSISTED
        }
        DiagnosticLog.event(
            "SMS_SIM_PREF",
            "applied thread=$threadId action=${if (simRef == null) "CLEAR" else "SET"}"
        )

        // Publish the canonical encrypted update immediately: GMweb must not wait for the next
        // message, a full sync or an app restart to learn which line this conversation uses.
        //
        // Everything from here on is a REPLICATION concern. A failure is not a persistence failure,
        // and the local change stays exactly as the user set it.
        val conversationId = runCatching {
            com.autonomousone.messages.data.TelephonySyncCoordinator.get(appContext)
                .conversationIdForThreadForPreference(threadId)
        }.getOrNull()
        if (conversationId.isNullOrBlank()) {
            DiagnosticLog.event(
                "SMS_SIM_PREF",
                "replication-owed thread=$threadId reason=no-conversation-id"
            )
            return PreferenceUpdateResult.PERSISTED_PENDING_REPLICATION
        }

        val queued = runCatching {
            com.autonomousone.messages.data.TelephonySyncCoordinator.get(appContext)
                .emitConversationUpsertForThread(
                    threadId = threadId,
                    conversationId = conversationId,
                    preferredSim = if (simRef == null) {
                        com.autonomousone.messages.data.GatewayEventFactory.PreferredSimPayload.Clear
                    } else {
                        com.autonomousone.messages.data.GatewayEventFactory.PreferredSimPayload.Set(
                            simRef = simRef,
                            displayName = chosen?.displayName,
                            carrierName = chosen?.carrierName,
                            slotIndex = chosen?.slotIndex
                        )
                    }
                )
        }.getOrDefault(false)

        if (!queued) {
            // The event row is not durable, so replication is OWED. Reported distinctly rather than as
            // a failure, and the local preference is left exactly as the user set it.
            DiagnosticLog.event(
                "SMS_SIM_PREF",
                "replication-owed thread=$threadId reason=event-not-queued"
            )
            return PreferenceUpdateResult.PERSISTED_PENDING_REPLICATION
        }
        return PreferenceUpdateResult.PERSISTED_AND_QUEUED
    }

    /** The stored sticky SIM of one conversation, or null when it has none. */
    suspend fun preferredSimRef(threadId: Long): String? = dao.preferredSimRef(threadId)

    /**
     * Production wiring for [SpamRepository]: the ONE blocklist
     * ([BlocklistRepository]) plus the field-scoped spam writers above.
     *
     * Reads use the exact-match provenance probe; NOTHING here deletes a message,
     * and an explicit manual block is never recorded as report provenance.
     */
    fun spamBlockPort(blocklist: BlocklistRepository): SpamBlockPort = object : SpamBlockPort {
        override fun isBlocked(normalizedAddress: String): Boolean =
            blocklist.isBlockedNorm(normalizedAddress)

        override fun block(address: String) {
            blocklist.block(address)
        }

        override fun unblock(address: String) {
            blocklist.unblock(address)
        }

        override suspend fun markSpam(threadId: Long, blockedByReport: Boolean, now: Long) {
            this@ConversationPreferenceRepository.markSpam(threadId, blockedByReport, now)
        }

        override suspend fun clearSpam(
            threadId: Long,
            clearBlockProvenance: Boolean,
            now: Long
        ) {
            this@ConversationPreferenceRepository.clearSpam(threadId, clearBlockProvenance, now)
        }
    }
}