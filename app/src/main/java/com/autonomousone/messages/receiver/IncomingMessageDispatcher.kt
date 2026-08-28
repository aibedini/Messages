package com.autonomousone.messages.receiver

import android.content.Context
import android.provider.Telephony
import android.util.Log
import com.autonomousone.messages.data.MessageMutation
import com.autonomousone.messages.data.MessageEntity
import com.autonomousone.messages.data.TelephonySyncCoordinator
import com.autonomousone.messages.event.SmsEventBus
import com.autonomousone.messages.gateway.WebhookEngine
import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.repository.BlocklistRepository
import com.autonomousone.messages.repository.ContactRepository
import com.autonomousone.messages.repository.SmsRepository
import com.autonomousone.messages.utils.NotificationHelper

/**
 * Single fan-out point for INCOMING messages (SMS and MMS alike).
 *
 * Every path that lands a message in Telephony.Sms / Telephony.Mms ends by
 * calling [dispatch] with the persisted row's real id + threadId, so the UI,
 * webhooks and notifications all react to PROVIDER state — not to optimistic
 * local state (single source of truth).
 *
 * Hot path (O(1)):
 *   Provider INSERT → providerId + threadId
 *   → mutate(Upsert) → Room transaction { message + conversation }
 *   → Room Flow → UI
 *
 * Runs on a caller-supplied background context; does network/DB work freely.
 */
object IncomingMessageDispatcher {

    private const val TAG = "INCOMING_DISPATCH"

    /**
     * @param sms the persisted message: id/threadId MUST come from the provider
     *            row that was just written or read back.
     * @param source which provider table the row lives in. MMS rows persisted
     *               as SOURCE_SMS poison the shadow (wrong keyset space, wrong
     *               dedupe id) — each receiver states its own source.
     */
    fun dispatch(
        context: Context,
        sms: Sms,
        source: String = MessageEntity.SOURCE_SMS
    ) {
        // Always mirror into Room first (blocking is a notification policy,
        // not a sync policy — the row stays persisted either way).
        TelephonySyncCoordinator.get(context).mutate(
            MessageMutation.Upsert(source = source, message = sms)
        )

        // Blocked sender: no bus event, no webhook, no notification (silent).
        if (BlocklistRepository.isBlocked(context, sms.sender)) {
            Log.d(TAG, "Message from blocked sender ${sms.sender} — silent handling")
            return
        }

        // A brand-new sender must not render as "Unknown" on the next list
        // refresh — drop cached address maps so names re-resolve.
        SmsRepository(context).invalidateAddressCaches()

        // Optimistic UI update (Home list prepends instantly; both Home and
        // Conversation ViewModels then reconcile against the provider).
        SmsEventBus.emitSms(sms)

        // Gateway webhook / cloud event (fire-and-forget, consent-gated inside).
        WebhookEngine.sendIncomingSmsWebhook(context, sms)

        // Notification unless the user is actively viewing this conversation.
        val isViewingThis = ContactRepository.sameConversation(
            sms.sender, SmsEventBus.activeConversationPhone
        ) && SmsEventBus.isAppInForeground

        if (!isViewingThis) {
            NotificationHelper.showSmsNotification(context, sms)
        }
    }

    /**
     * Resolves the canonical thread id for [address] via the platform
     * (Telephony.Threads). Returns 0 when unavailable so callers keep a
     * well-formed model without inventing ids.
     */
    fun resolveThreadId(context: Context, address: String): Long = try {
        Telephony.Threads.getOrCreateThreadId(context, address)
    } catch (e: Exception) {
        Log.w(TAG, "getOrCreateThreadId failed for $address", e)
        0L
    }
}
