package com.autonomousone.messages.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.autonomousone.messages.MainActivity
import com.autonomousone.messages.ui.screens.ConversationScreen
import com.autonomousone.messages.ui.screens.HomeScreen
import com.autonomousone.messages.ui.screens.NewConversationScreen
import com.autonomousone.messages.ui.screens.SettingsScreen
import com.autonomousone.messages.ui.screens.SplashScreen

@Composable
fun AppNavigation(
    hasPermission: Boolean,
    isDefaultSmsApp: Boolean,
    pendingShare: MainActivity.SharePayload? = null,
    onShareConsumed: () -> Unit = {},
    onRequestDefaultApp: () -> Unit,
    onRequestPermissions: () -> Unit
) {
    val navController = rememberNavController()

    // External share/send payload: route ONCE into the new-conversation flow
    // as a DRAFT (never auto-sent), then consume so rotation/recomposition
    // doesn't re-trigger it.
    pendingShare?.let { share ->
        LaunchedEffect(share) {
            navController.navigate(Screen.NewConversation.createDraftRoute(share.phone, share.text)) {
                popUpTo(Screen.Home.route)
            }
            onShareConsumed()
        }
    }

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

        composable(
            Screen.Home.route,
            // v2.6.8 motion polish: leaving Home is just a quick fade — the
            // incoming Conversation carries the (shallow) slide. Two full
            // 350ms slides over each other read cheap.
            exitTransition = { fadeOut(tween(90)) },
            popEnterTransition = { fadeIn(tween(140)) }
        ) {
            HomeScreen(
                hasPermission = hasPermission,
                isDefaultSmsApp = isDefaultSmsApp,
                onRequestDefaultApp = onRequestDefaultApp,
                onRequestPermissions = onRequestPermissions,
                navController = navController
            )
        }

        composable(
            route = Screen.NewConversation.route,
            arguments = listOf(
                navArgument("forward") {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument("draft") {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument("shared_phone") {
                    type = NavType.StringType
                    defaultValue = ""
                }
            )
        ) { backStackEntry ->
            NewConversationScreen(
                navController = navController,
                forwardText = backStackEntry.arguments?.getString("forward") ?: "",
                draftText = backStackEntry.arguments?.getString("draft") ?: "",
                sharedPhone = backStackEntry.arguments?.getString("shared_phone") ?: ""
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

        composable(Screen.AppearanceSettings.route) {
            com.autonomousone.messages.ui.screens.AppearanceSettingsScreen(
                navController = navController
            )
        }

        composable(Screen.QuickReplies.route) {
            com.autonomousone.messages.ui.screens.QuickRepliesScreen(
                navController = navController
            )
        }

        composable(Screen.ScheduledMessages.route) {
            com.autonomousone.messages.ui.screens.ScheduledMessagesScreen(
                navController = navController
            )
        }

        composable(
            route = Screen.Conversation.route,
            // v2.6.8 motion polish: Home → Conversation opens with a SHALLOW
            // slide — the page moves ~16% of its width, not the whole screen,
            // in 210ms with a FastOutSlowIn curve, over a quick 110ms fade.
            // Back glides the thread ~18% to the right in ~200ms while Home
            // fades underneath. Reads far more premium than two full 350ms
            // slides stacking on top of each other.
            enterTransition = {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = tween(
                        durationMillis = 210,
                        easing = FastOutSlowInEasing
                    ),
                    initialOffset = { (it * 0.16f).toInt() }
                ) + fadeIn(tween(110))
            },
            popExitTransition = {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = tween(
                        durationMillis = 200,
                        easing = FastOutSlowInEasing
                    ),
                    targetOffset = { (it * 0.18f).toInt() }
                ) + fadeOut(tween(200))
            },
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
                },
                navArgument("forward") {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument("draft") {
                    type = NavType.StringType
                    defaultValue = ""
                }
            )
        ) { backStackEntry ->
            val threadId = backStackEntry.arguments?.getLong("threadId") ?: 0L
            val phone = backStackEntry.arguments?.getString("phone") ?: ""
            val name = backStackEntry.arguments?.getString("name") ?: ""
            val forward = backStackEntry.arguments?.getString("forward") ?: ""
            val draft = backStackEntry.arguments?.getString("draft") ?: ""

            ConversationScreen(
                threadId = threadId,
                phone = phone,
                name = name,
                forwardText = forward,
                draftText = draft,
                navController = navController
            )
        }
    }
}