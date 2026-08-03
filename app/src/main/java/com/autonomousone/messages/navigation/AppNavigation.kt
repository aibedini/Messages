package com.autonomousone.messages.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.autonomousone.messages.ui.screens.ConversationScreen
import com.autonomousone.messages.ui.screens.HomeScreen
import com.autonomousone.messages.ui.screens.NewConversationScreen

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

        composable(Screen.NewConversation.route) {

            NewConversationScreen(
                navController = navController
            )

        }

        composable(

            route = Screen.Conversation.route,

            arguments = listOf(

                navArgument("threadId") {
                    type = NavType.LongType
                },

                navArgument("phone") {
                    type = NavType.StringType
                    defaultValue = ""
                },

                navArgument("name") {
                    type = NavType.StringType
                    defaultValue = ""
                }

            )

        ) { backStackEntry ->

            val threadId =
                backStackEntry.arguments?.getLong("threadId") ?: 0L

            val phone =
                backStackEntry.arguments?.getString("phone") ?: ""

            val name =
                backStackEntry.arguments?.getString("name") ?: ""

            ConversationScreen(

                threadId = threadId,

                phone = phone,

                name = name,

                navController = navController

            )

        }

    }

}