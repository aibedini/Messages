package com.autonomousone.messages.navigation

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

sealed class Screen(val route: String) {

    object Home : Screen("home")

    object NewConversation : Screen("new_conversation")

    object Gateway : Screen("gateway")

    object Settings : Screen("settings")

    object Conversation :
        Screen("conversation/{threadId}?phone={phone}&name={name}") {

        fun createRoute(
            threadId: Long,
            phone: String = "",
            name: String = ""
        ): String {
            val encodedPhone = if (phone.isNotBlank()) {
                URLEncoder.encode(phone, StandardCharsets.UTF_8.toString())
            } else ""
            val encodedName = if (name.isNotBlank()) {
                URLEncoder.encode(name, StandardCharsets.UTF_8.toString())
            } else ""

            return "conversation/$threadId?phone=$encodedPhone&name=$encodedName"
        }

        fun createNewRoute(
            phone: String,
            name: String
        ): String {
            val encodedPhone = URLEncoder.encode(phone, StandardCharsets.UTF_8.toString())
            val encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8.toString())
            return "conversation/0?phone=$encodedPhone&name=$encodedName"
        }
    }
}