package com.autonomousone.messages.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.autonomousone.messages.ui.screens.ConversationScreen
import com.autonomousone.messages.ui.screens.HomeScreen
import com.autonomousone.messages.navigation.Screen

@Composable
fun AppNavigation(
    hasPermission: Boolean
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {

        composable(Screen.Home.route) {

            HomeScreen(
                hasPermission = hasPermission,
                navController = navController
            )

        }

        composable(
            route = Screen.Conversation.route,
            arguments = listOf(
                navArgument("threadId") {
                    type = NavType.LongType
                }
            )
        ) {

            val threadId =
                it.arguments?.getLong("threadId") ?: 0L

            ConversationScreen(
                threadId = threadId,
                navController = navController
            )

        }
    }
}