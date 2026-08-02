package com.autonomousone.messages.navigation

sealed class Screen(val route: String) {

    object Home : Screen("home")

    object Conversation : Screen("conversation/{sender}") {

        fun createRoute(sender: String): String {
            return "conversation/$sender"
        }
    }
}