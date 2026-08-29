package com.autonomousone.messages.navigation

import android.content.Intent

/**
 * v2.6.9: a real navigation target for external launch intents.
 *
 * Bug this fixes: NotificationHelper put extra_thread_id/extra_phone/
 * extra_name on the tap intent, but MainActivity only ever parsed
 * ACTION_SEND / ACTION_SENDTO — nobody read the conversation extras, so
 * tapping a notification just opened the app on Home.
 */
sealed interface AppLaunchTarget {

    data class Conversation(
        val threadId: Long,
        val phone: String,
        val name: String
    ) : AppLaunchTarget
}

object AppLaunchIntent {

    const val ACTION_OPEN_CONVERSATION =
        "com.autonomousone.messages.OPEN_CONVERSATION"

    const val EXTRA_THREAD_ID =
        "extra_thread_id"

    const val EXTRA_PHONE =
        "extra_phone"

    const val EXTRA_NAME =
        "extra_name"

    fun parse(
        intent: Intent?
    ): AppLaunchTarget? {

        if (
            intent?.action !=
            ACTION_OPEN_CONVERSATION
        ) {
            return null
        }

        val threadId =
            intent.getLongExtra(
                EXTRA_THREAD_ID,
                0L
            )

        val phone =
            intent.getStringExtra(
                EXTRA_PHONE
            ).orEmpty()

        val name =
            intent.getStringExtra(
                EXTRA_NAME
            ).orEmpty()

        if (
            threadId <= 0L &&
            phone.isBlank()
        ) {
            return null
        }

        return AppLaunchTarget.Conversation(
            threadId = threadId,
            phone = phone,
            name = name
        )
    }
}
