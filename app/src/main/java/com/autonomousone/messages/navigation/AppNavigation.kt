package com.autonomousone.messages.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.autonomousone.messages.ui.screens.ConversationScreen
import com.autonomousone.messages.ui.screens.HomeScreen
import com.autonomousone.messages.ui.screens.NewConversationScreen
import com.autonomousone.messages.ui.screens.SettingsScreen
import com.autonomousone.messages.ui.screens.SplashScreen

@Composable
fun AppNavigation(
    hasPermission: Boolean,
    isDefaultSmsApp: Boolean,
    onRequestDefaultApp: () -> Unit,
    onRequestPermissions: () -> Unit
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "splash",
        enterTransition = {
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Left,
                animationSpec = tween(350)
            ) + fadeIn(animationSpec = tween(350))
        },
        exitTransition = {
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Left,
                animationSpec = tween(350)
            ) + fadeOut(animationSpec = tween(350))
        },
        popEnterTransition = {
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Right,
                animationSpec = tween(350)
            ) + fadeIn(animationSpec = tween(350))
        },
        popExitTransition = {
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Right,
                animationSpec = tween(350)
            ) + fadeOut(animationSpec = tween(350))
        }
    ) {
        composable(
            route = "splash",
            enterTransition = { fadeIn(tween(300)) },
            exitTransition = { fadeOut(tween(300)) }
        ) {
            SplashScreen(
                onNavigate = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo("splash") { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Home.route) {
            HomeScreen(
                hasPermission = hasPermission,
                isDefaultSmsApp = isDefaultSmsApp,
                onRequestDefaultApp = onRequestDefaultApp,
                onRequestPermissions = onRequestPermissions,
                navController = navController
            )
        }

        composable(Screen.NewConversation.route) {
            NewConversationScreen(
                navController = navController
            )
        }

        composable(Screen.Gateway.route) {
            com.autonomousone.messages.ui.screens.GatewayScreen(
                navController = navController
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                hasPermission = hasPermission,
                isDefaultSmsApp = isDefaultSmsApp,
                onRequestPermissions = onRequestPermissions,
                onRequestDefaultApp = onRequestDefaultApp,
                navController = navController
            )
        }

        composable(Screen.MessagingSettings.route) {
            com.autonomousone.messages.ui.screens.MessagingSettingsScreen(
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
            val threadId = backStackEntry.arguments?.getLong("threadId") ?: 0L
            val phone = backStackEntry.arguments?.getString("phone") ?: ""
            val name = backStackEntry.arguments?.getString("name") ?: ""

            ConversationScreen(
                threadId = threadId,
                phone = phone,
                name = name,
                navController = navController
            )
        }
    }
}