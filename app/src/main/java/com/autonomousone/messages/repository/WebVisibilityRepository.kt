package com.autonomousone.messages.repository

import android.content.Context
import com.autonomousone.messages.data.TelephonySyncCoordinator
import com.autonomousone.messages.messaging.MessagingPreferences

/**
 * "Hide from web" — the phone-side authority for one conversation/contact's web visibility.
 *
 * The preference is stored here (address aliases + opaque conversation id), and the coordinator
 * performs the durable sync consequences: suppress future events and queue `CONVERSATION_DELETED`
 * for the web replica. Hiding does NOT delete anything from this phone.
 */
class WebVisibilityRepository private constructor(private val context: Context) {

    private val prefs = MessagingPreferences(context.applicationContext)

    companion object {
        @Volatile
        private var instance: WebVisibilityRepository? = null

        fun get(context: Context): WebVisibilityRepository =
            instance ?: synchronized(this) {
                instance ?: WebVisibilityRepository(context.applicationContext).also {
                    instance = it
                }
            }
    }

    /** True when any spelling of [address] is hidden from the web companion. */
    fun isHidden(address: String): Boolean = prefs.isHiddenFromWebAddress(address)

    /**
     * Hides or shows [threadId] on the web companion.
     *
     * @return true when the local decision and its control event were durably written.
     */
    suspend fun setHidden(threadId: Long, address: String, hidden: Boolean): Boolean =
        TelephonySyncCoordinator.get(context)
            .setConversationWebVisibility(threadId, address, hidden)
}
