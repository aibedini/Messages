package com.autonomousone.messages.navigation
import com.autonomousone.messages.navigation.Screen

sealed class Screen(val route: String) {

    object Home : Screen("home")

    object Conversation : Screen("conversation/{sender}") {

        fun createRoute(sender: String): String {
            return "conversation/$sender"
        }
    }
}