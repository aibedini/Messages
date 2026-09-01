package com.autonomousone.messages.navigation

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

sealed class Screen(val route: String) {

    object Home : Screen("home")

    object NewConversation : Screen("new_conversation?forward={forward}&draft={draft}") {

        /** Route used from the nav graph declaration. */
        val baseRoute: String = "new_conversation"

        fun createForwardRoute(text: String): String =
            "new_conversation?forward=${encode(text)}"

        /**
         * External share/send entry point. [phone] pre-fills the recipient
         * search, [draftText] lands in the composer as a DRAFT — the user
         * still presses Send.
         */
        fun createDraftRoute(phone: String, draftText: String): String =
            "new_conversation?forward=&draft=${encode(draftText)}&shared_phone=${encode(phone)}"
    }

    object Gateway : Screen("gateway")

    companion object {
        /**
         * v2.6.12: a navigation argument that still contains a route
         * placeholder ("{forward}", "{draft}") is a leaked PATTERN, never
         * user data. Navigating with a pattern route (instead of a filled
         * route) makes Navigation hand the literal "{forward}" back as the
         * argument value and the composer "types" it. Sanitizing at the
         * argument layer covers every current and future caller — including
         * a process-death back-stack restore of a stale bad route.
         */
        fun cleanArg(raw: String?): String =
            raw?.takeIf { it.isNotEmpty() && '{' !in it && '}' !in it } ?: ""

        /**
         * v2.6.18: percent-encode a navigation argument. Deliberately NOT
         * URLEncoder.encode — that is a FORM encoder ("a b" -> "a+b") while
         * URI/Nav query decoding is percent-style ("a+b" means the literal
         * name "a+b"). Form-encoding a contact display name turned
         * "hamid dadash" into the header text "hamid+dadash".
         */
        fun encode(text: String): String =
            URLEncoder.encode(text, StandardCharsets.UTF_8.name())
                .replace("+", "%20")
    }

    object Settings : Screen("settings")

    object MessagingSettings : Screen("messaging_settings")

    object LinkedDevices : Screen("linked_devices")

    object AppearanceSettings : Screen("appearance_settings")

    object QuickReplies : Screen("quick_replies")

    object ScheduledMessages : Screen("scheduled_messages")

    object Conversation :
        Screen("conversation/{threadId}?phone={phone}&name={name}&forward={forward}&draft={draft}") {

        fun createRoute(
            threadId: Long,
            phone: String = "",
            name: String = "",
            forward: String = "",
            draft: String = ""
        ): String =
            "conversation/$threadId" +
                "?phone=${encode(phone)}&name=${encode(name)}" +
                "&forward=${encode(forward)}&draft=${encode(draft)}"

        fun createNewRoute(
            phone: String,
            name: String,
            forward: String = "",
            draft: String = ""
        ): String =
            "conversation/0" +
                "?phone=${encode(phone)}&name=${encode(name)}" +
                "&forward=${encode(forward)}&draft=${encode(draft)}"
    }
}
