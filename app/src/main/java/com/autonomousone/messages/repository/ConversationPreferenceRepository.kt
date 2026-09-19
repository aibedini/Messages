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
}