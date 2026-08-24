package com.autonomousone.messages.navigation

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

sealed class Screen(val route: String) {

    object Home : Screen("home")

    object NewConversation : Screen("new_conversation?forward={forward}") {

        /** Route used from the nav graph declaration. */
        val baseRoute: String = "new_conversation"

        fun createForwardRoute(text: String): String {
            val encoded = URLEncoder.encode(text, StandardCharsets.UTF_8.toString())
            return "new_conversation?forward=$encoded"
        }
    }

    object Gateway : Screen("gateway")

    object Settings : Screen("settings")

    object MessagingSettings : Screen("messaging_settings")

    object AppearanceSettings : Screen("appearance_settings")

    object QuickReplies : Screen("quick_replies")

    object Conversation :
        Screen("conversation/{threadId}?phone={phone}&name={name}&forward={forward}") {

        fun createRoute(
            threadId: Long,
            phone: String = "",
            name: String = "",
            forward: String = ""
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

            return "conversation/$threadId?phone=$encodedPhone&name=$encodedName&forward=$encodedForward"
        }

        fun createNewRoute(
            phone: String,
            name: String,
            forward: String = ""
        ): String {
            val encodedPhone = URLEncoder.encode(phone, StandardCharsets.UTF_8.toString())
            val encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8.toString())
            val encodedForward = if (forward.isNotBlank()) {
                URLEncoder.encode(forward, StandardCharsets.UTF_8.toString())
            } else ""
            return "conversation/0?phone=$encodedPhone&name=$encodedName&forward=$encodedForward"
        }
    }
}