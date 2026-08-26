package com.autonomousone.messages.navigation

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

sealed class Screen(val route: String) {

    object Home : Screen("home")

    object NewConversation : Screen("new_conversation?forward={forward}&draft={draft}") {

        /** Route used from the nav graph declaration. */
        val baseRoute: String = "new_conversation"

        fun createForwardRoute(text: String): String {
            val encoded = URLEncoder.encode(text, StandardCharsets.UTF_8.toString())
            return "new_conversation?forward=$encoded"
        }

        /**
         * External share/send entry point. [phone] pre-fills the recipient
         * search, [draftText] lands in the composer as a DRAFT — the user
         * still presses Send.
         */
        fun createDraftRoute(phone: String, draftText: String): String {
            val encodedPhone = URLEncoder.encode(phone, StandardCharsets.UTF_8.toString())
            val encodedDraft = URLEncoder.encode(draftText, StandardCharsets.UTF_8.toString())
            return "new_conversation?forward=&draft=$encodedDraft&shared_phone=$encodedPhone"
        }
    }

    object Gateway : Screen("gateway")

    object Settings : Screen("settings")

    object MessagingSettings : Screen("messaging_settings")

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
        ): String {
            val encodedPhone = if (phone.isNotBlank()) {
                URLEncoder.encode(phone, StandardCharsets.UTF_8.toString())
            } else ""
            val encodedName = if (name.isNotBlank()) {
                URLEncoder.encode(name, StandardCharsets.UTF_8.toString())
            } else ""
            val encodedForward = if (forward.isNotBlank()) {
                URLEncoder.encode(forward, StandardCharsets.UTF_8.toString())
            } else ""
            val encodedDraft = if (draft.isNotBlank()) {
                URLEncoder.encode(draft, StandardCharsets.UTF_8.toString())
            } else ""

            return "conversation/$threadId?phone=$encodedPhone&name=$encodedName&forward=$encodedForward&draft=$encodedDraft"
        }

        fun createNewRoute(
            phone: String,
            name: String,
            forward: String = "",
            draft: String = ""
        ): String {
            val encodedPhone = URLEncoder.encode(phone, StandardCharsets.UTF_8.toString())
            val encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8.toString())
            val encodedForward = if (forward.isNotBlank()) {
                URLEncoder.encode(forward, StandardCharsets.UTF_8.toString())
            } else ""
            val encodedDraft = if (draft.isNotBlank()) {
                URLEncoder.encode(draft, StandardCharsets.UTF_8.toString())
            } else ""
            return "conversation/0?phone=$encodedPhone&name=$encodedName&forward=$encodedForward&draft=$encodedDraft"
        }
    }
}