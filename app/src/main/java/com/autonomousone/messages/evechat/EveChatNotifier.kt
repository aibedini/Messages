package com.autonomousone.messages.evechat

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import com.autonomousone.messages.MainActivity
import com.autonomousone.messages.R
import com.autonomousone.messages.evechat.model.EveChatConversation
import com.autonomousone.messages.evechat.model.EveChatMessage
import com.autonomousone.messages.evechat.push.EveChatPush
import com.autonomousone.messages.navigation.AppLaunchIntent
import java.util.concurrent.ConcurrentHashMap

class EveChatNotifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)
    private val recent = ConcurrentHashMap<String, ArrayDeque<Pair<String, Long>>>()

    /** Socket, push and polling can all report the same message; each is announced once. */
    private val announced = object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 500
    }

    private fun firstAnnouncement(messageId: String?): Boolean {
        if (messageId == null) return true
        synchronized(announced) {
            if (announced.containsKey(messageId)) return false
            announced[messageId] = true
            return true
        }
    }

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, "پیام‌های پشتیبانی", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "پیام‌های جدید بازدیدکنندگان EveChat"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_NEW, "گفتگوهای جدید پشتیبانی", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "گفتگوهایی که منتظر پاسخ هستند"
            }
        )
    }

    /** Only conversations that are unassigned or assigned to this agent interrupt the user. */
    fun message(conversation: EveChatConversation, message: EveChatMessage, isMine: Boolean) {
        if (!isMine || message.isInternal || !firstAnnouncement(message.id)) return
        val lines = recent.getOrPut(conversation.id) { ArrayDeque() }
        synchronized(lines) {
            if (lines.none { it.second == message.createdAt && it.first == message.content }) {
                lines.addLast(message.content.take(300) to message.createdAt)
                while (lines.size > 6) lines.removeFirst()
            }
        }
        val visitor = Person.Builder().setName(conversation.displayName).setKey(conversation.visitorId).build()
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("من").build())
            .setConversationTitle(conversation.displayName)
        synchronized(lines) { lines.forEach { (text, at) -> style.addMessage(text, at, visitor) } }
        post(
            conversation.id,
            NotificationCompat.Builder(context, CHANNEL_MESSAGES)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setStyle(style)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setGroup(GROUP)
                .setAutoCancel(true)
                .setOnlyAlertOnce(false)
                .setWhen(message.createdAt)
                .setContentIntent(openIntent(conversation.id))
                .build()
        )
    }

    fun newConversation(conversation: EveChatConversation, first: EveChatMessage?) {
        post(
            conversation.id,
            NotificationCompat.Builder(context, CHANNEL_NEW)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("گفتگوی جدید: ${conversation.displayName}")
                .setContentText(first?.content?.take(300) ?: conversation.subject ?: "منتظر پاسخ")
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setGroup(GROUP)
                .setAutoCancel(true)
                .setContentIntent(openIntent(conversation.id))
                .build()
        )
    }

    /** Used only when the conversation could not be fetched: shows what the push itself carried. */
    fun fallback(push: EveChatPush) {
        val conversationId = push.conversationId ?: return
        if (!firstAnnouncement(push.messageId)) return
        val newConversation = push.type == EveChatPush.Type.NEW_CONVERSATION
        post(
            conversationId,
            NotificationCompat.Builder(context, if (newConversation) CHANNEL_NEW else CHANNEL_MESSAGES)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(push.title ?: if (newConversation) "گفتگوی جدید پشتیبانی" else "پیام جدید پشتیبانی")
                .setContentText(push.body ?: "برای مشاهده باز کنید")
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setGroup(GROUP)
                .setAutoCancel(true)
                .apply { push.sentAt?.let(::setWhen) }
                .setContentIntent(openIntent(conversationId))
                .build()
        )
    }

    fun cancel(conversationId: String) {
        recent.remove(conversationId)
        manager.cancel(TAG, conversationId.hashCode())
    }

    fun cancelAll() {
        recent.keys.toList().forEach(::cancel)
    }

    private fun post(conversationId: String, notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        if (!manager.areNotificationsEnabled()) return
        try {
            manager.notify(TAG, conversationId.hashCode(), notification)
        } catch (e: SecurityException) {
            EveChatLog.w("notify", "notification refused", e)
        }
    }

    private fun openIntent(conversationId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = AppLaunchIntent.ACTION_OPEN_SUPPORT_CONVERSATION
            putExtra(AppLaunchIntent.EXTRA_SUPPORT_CONVERSATION_ID, conversationId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context, conversationId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private companion object {
        const val CHANNEL_MESSAGES = "evechat_messages"
        const val CHANNEL_NEW = "evechat_new_conversations"
        const val GROUP = "evechat"
        const val TAG = "evechat"
    }
}
