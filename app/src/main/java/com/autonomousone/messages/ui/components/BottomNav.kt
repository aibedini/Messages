package com.autonomousone.messages.ui.components

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.HeadsetMic
import androidx.compose.material.icons.outlined.HeadsetMic
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.autonomousone.messages.R
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.evechat.EveChatSession
import com.autonomousone.messages.navigation.Screen

/**
 * The app's two destinations. Labels are resources, never literals: a build running in English
 * used to show a Persian "پشتیبانی" here because the word was typed into the enum.
 */
private enum class BottomTab(
    val route: String,
    @StringRes val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    Messages(
        route = Screen.Home.route,
        labelRes = R.string.nav_messages,
        selectedIcon = Icons.AutoMirrored.Filled.Chat,
        unselectedIcon = Icons.AutoMirrored.Outlined.Chat
    ),
    Support(
        route = Screen.EveChat.route,
        labelRes = R.string.nav_support,
        selectedIcon = Icons.Filled.HeadsetMic,
        unselectedIcon = Icons.Outlined.HeadsetMic
    )
}

/** Badge text is capped like every messaging app: past 99 the exact number stops being useful. */
private fun badgeText(count: Int) = if (count > 99) "99+" else count.toString()

@Composable
fun MessagesBottomNav(
    navController: NavController,
    modifier: Modifier = Modifier
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val context = LocalContext.current

    // Two independent counts, each owned by the store that already tracks it:
    // support unread comes from the EveChat cache, SMS unread from the conversation projection.
    val supportUnreadFlow = remember(context) { EveChatSession.get(context).observeUnreadTotal() }
    val supportUnread by supportUnreadFlow.collectAsStateWithLifecycle(initialValue = 0)
    val smsUnreadFlow = remember(context) {
        MessagesDatabase.get(context).conversationDao().observeUnreadTotal()
    }
    val smsUnread by smsUnreadFlow.collectAsStateWithLifecycle(initialValue = 0)

    NavigationBar(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        BottomTab.entries.forEach { tab ->
            val selected = currentRoute == tab.route
            val unread = when (tab) {
                BottomTab.Messages -> smsUnread
                BottomTab.Support -> supportUnread
            }
            val label = stringResource(tab.labelRes)
            NavigationBarItem(
                selected = selected,
                onClick = {
                    if (currentRoute != tab.route) {
                        navController.navigate(tab.route) {
                            popUpTo(Screen.Home.route) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                icon = {
                    val description = if (unread > 0) {
                        stringResource(R.string.nav_unread_fmt, label, unread)
                    } else {
                        null
                    }
                    BadgedBox(
                        badge = {
                            if (unread > 0) {
                                Badge { Text(badgeText(unread)) }
                            }
                        },
                        modifier = if (description != null) {
                            // One announcement for icon + badge, instead of a stray "3".
                            Modifier.clearAndSetSemantics { contentDescription = description }
                        } else {
                            Modifier
                        }
                    ) {
                        Icon(
                            imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                            contentDescription = null
                        )
                    }
                },
                label = { Text(label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }
}
