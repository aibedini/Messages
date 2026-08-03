package com.autonomousone.messages.navigation
import com.autonomousone.messages.navigation.Screen

sealed class Screen(val route: String) {

    object Home : Screen("home")

    object Conversation : Screen("conversation/{threadId}") {

        fun createRoute(threadId: Long): String {
            return "conversation/$threadId"
        }
    }
}