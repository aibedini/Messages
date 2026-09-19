package com.autonomousone.messages.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.first
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.autonomousone.messages.MainActivity
import com.autonomousone.messages.ui.screens.ConversationInfoScreen
import com.autonomousone.messages.ui.screens.ConversationMediaScreen
import com.autonomousone.messages.ui.screens.ConversationScreen
import com.autonomousone.messages.ui.screens.HomeScreen
import com.autonomousone.messages.ui.screens.NewConversationScreen
import com.autonomousone.messages.ui.screens.RecentlyDeletedScreen
import com.autonomousone.messages.ui.screens.SettingsScreen
import com.autonomousone.messages.ui.screens.SplashScreen
import com.autonomousone.messages.ui.screens.StarredMessagesScreen

@Composable
fun AppNavigation(
    hasPermission: Boolean,
    isDefaultSmsApp: Boolean,
    pendingShare: MainActivity.SharePayload? = null,
    onShareConsumed: () -> Unit = {},
    pendingNavigation: AppLaunchTarget? = null,
    onNavigationConsumed: () -> Unit = {},
    onRequestDefaultApp: () -> Unit,
    onRequestPermissions: () -> Unit
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val externalComposeResolver = remember(context) { ExternalComposeResolver(context) }

    // External share/send payload: resolve against the Room conversation
    // projection off-main, then route ONCE with the body as a DRAFT.
    pendingShare?.let { share ->
        LaunchedEffect(share) {
            navController.currentBackStackEntryFlow.first { entry ->
                val route = entry.destination.route
                route == Screen.Home.route || route?.startsWith("conversation/") == true
            }

            when (val target = externalComposeResolver.resolve(share.phone, share.text)) {
                is ExternalComposeTarget.ExistingConversation -> {
                    // popUpTo(Home) replaces any currently open chat instead
                    // of stacking another conversation destination. This also
                    // lets a same-thread external launch deliver a fresh draft.
                    navController.navigate(
                        Screen.Conversation.createRoute(
                            threadId = target.threadId,
                            phone = target.phone,
                            name = target.displayName,
                            draft = target.draft
                        )
                    ) {
                        popUpTo(Screen.Home.route) { inclusive = false }
                        launchSingleTop = true
                    }
                }
                is ExternalComposeTarget.NewConversation -> {
                    val route = if (target.phone.isBlank()) {
                        Screen.NewConversation.createDraftRoute("", target.draft)
                    } else {
                        Screen.Conversation.createNewRoute(
                            phone = target.phone,
                            name = target.phone,
                            draft = target.draft
                        )
                    }
                    navController.navigate(route) {
                        popUpTo(Screen.Home.route) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            }
            onShareConsumed()
        }
    }

    // v2.6.9: notification deep-link. The NavHost starts on "splash", so
    // navigating immediately would land the conversation behind (or on top
    // of) splash. Wait until the back stack actually reaches Home or an
    // existing conversation, then consume:
    //  - app closed  -> splash -> Home -> Conversation
    //  - app on Home -> onNewIntent -> Conversation
    //  - app on another conversation -> pop it, open the target thread
    //  - app locked  -> the state survives; consumed after unlock composes
    //    AppNavigation again (pendingNavigation is still set)
    // launchSingleTop + the current-thread guard keep a same-thread
    // notification from stacking a duplicate destination.
    LaunchedEffect(pendingNavigation) {
        val target = pendingNavigation ?: return@LaunchedEffect

        navController.currentBackStackEntryFlow.first { entry ->
            val route = entry.destination.route
            route == Screen.Home.route || route?.startsWith("conversation/") == true
        }

        if (target is AppLaunchTarget.Conversation) {
            val currentThread = navController.currentBackStackEntry
                ?.arguments
                ?.getLong("threadId")

            if (currentThread != target.threadId) {
                navController.navigate(
                    Screen.Conversation.createRoute(
                        threadId = target.threadId,
                        phone = target.phone,
                        name = target.name
                    )
                ) {
                    popUpTo(Screen.Home.route) { inclusive = false }
                    launchSingleTop = true
                }
            }
        }

        onNavigationConsumed()
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
                forwardText = Screen.cleanArg(backStackEntry.arguments?.getString("forward")),
                draftText = Screen.cleanArg(backStackEntry.arguments?.getString("draft")),
                sharedPhone = Screen.cleanArg(backStackEntry.arguments?.getString("shared_phone"))
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

        // ADR-007: linked-device pairing (primary trust device screen).
        composable(Screen.LinkedDevices.route) {
            com.autonomousone.messages.ui.screens.LinkedDevicesScreen(
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

        // ── v3.4.0 destinations ────────────────────────────────────────────
        //
        // Conversation Info (FEATURE 5). `phone`/`name` are carried from the
        // conversation the user came from so the header paints immediately; they
        // are read through Screen.cleanArg so a leaked route PATTERN can never be
        // rendered as user data.
        composable(
            route = Screen.ConversationInfo.route,
            arguments = listOf(
                navArgument("threadId") { type = NavType.LongType },
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
            ConversationInfoScreen(
                threadId = backStackEntry.arguments?.getLong("threadId") ?: 0L,
                phone = Screen.cleanArg(backStackEntry.arguments?.getString("phone")),
                name = Screen.cleanArg(backStackEntry.arguments?.getString("name")),
                navController = navController
            )
        }

        // Starred inside ONE conversation (FEATURE 7). A separate route from the
        // global list on purpose: a wired argument cannot be optional, so a lost
        // thread id can never silently show every conversation's stars under one
        // conversation's title.
        composable(
            route = Screen.ConversationStarred.route,
            arguments = listOf(navArgument("threadId") { type = NavType.LongType })
        ) { backStackEntry ->
            StarredMessagesScreen(
                threadId = backStackEntry.arguments?.getLong("threadId") ?: 0L,
                navController = navController,
                onOpenMessage = { threadId, source, providerId ->
                    navController.navigate(
                        Screen.Conversation.createHitRoute(threadId, source, providerId)
                    ) {
                        popUpTo(Screen.Home.route) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            )
        }

        // Global Starred browser (FEATURE 7). threadId = null means "every
        // conversation", which is why the thread argument lives on its own route.
        composable(Screen.StarredMessages.route) {
            StarredMessagesScreen(
                threadId = null,
                navController = navController,
                onOpenMessage = { threadId, source, providerId ->
                    navController.navigate(
                        Screen.Conversation.createHitRoute(threadId, source, providerId)
                    ) {
                        popUpTo(Screen.Home.route) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            )
        }

        // Media / Links / Files (FEATURE 6) for one conversation.
        composable(
            route = Screen.ConversationMedia.route,
            arguments = listOf(
                navArgument("threadId") { type = NavType.LongType },
                navArgument("name") {
                    type = NavType.StringType
                    defaultValue = ""
                }
            )
        ) { backStackEntry ->
            ConversationMediaScreen(
                threadId = backStackEntry.arguments?.getLong("threadId") ?: 0L,
                conversationName = Screen.cleanArg(backStackEntry.arguments?.getString("name")),
                navController = navController
            )
        }

        // Recently Deleted / Trash (FEATURE 8).
        composable(Screen.Trash.route) {
            RecentlyDeletedScreen(navController = navController)
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
                },
                // v3.4.0: optional "land on THIS exact message" identity, set by
                // the Starred browsers. Composite (source, providerId) on purpose —
                // SMS 100 and MMS 100 are different messages.
                navArgument("hitSource") {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument("hitProviderId") {
                    type = NavType.LongType
                    defaultValue = 0L
                }
            )
        ) { backStackEntry ->
            val threadId = backStackEntry.arguments?.getLong("threadId") ?: 0L
            // v2.6.18: NavType.StringType percent-decodes args at parse time
            // (Uri.getQueryParameter honours %20, NOT form-style +). The bug
            // was createRoute FORM-encoding names ("hamid dadash" ->
            // "hamid+dadash" survived the decode). encode() now emits %20,
            // so no extra decode call is needed here — and calling one would
            // double-decode message text that legitimately contains %XX.
            val phone = backStackEntry.arguments?.getString("phone") ?: ""
            val name = backStackEntry.arguments?.getString("name") ?: ""
            val forward = Screen.cleanArg(backStackEntry.arguments?.getString("forward"))
            val draft = Screen.cleanArg(backStackEntry.arguments?.getString("draft"))
            val hitSource = Screen.cleanArg(backStackEntry.arguments?.getString("hitSource"))
            val hitProviderId = backStackEntry.arguments?.getLong("hitProviderId") ?: 0L

            ConversationScreen(
                threadId = threadId,
                phone = phone,
                name = name,
                forwardText = forward,
                draftText = draft,
                hitSource = hitSource,
                hitProviderId = hitProviderId,
                navController = navController
            )
        }
    }
}
