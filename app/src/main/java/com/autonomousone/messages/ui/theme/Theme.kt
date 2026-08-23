package com.autonomousone.messages.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

/**
 * App theme: resolves the user-selected preset + dark-mode preference from
 * [ThemeController] (falls back to system dark mode and the default preset).
 */
@Composable
fun MessagesTheme(
    content: @Composable () -> Unit
) {
    val appearance by ThemeController.state.collectAsState()

    val darkTheme = when (appearance.mode) {
        ThemeController.Mode.SYSTEM -> isSystemInDarkTheme()
        ThemeController.Mode.LIGHT -> false
        ThemeController.Mode.DARK -> true
    }

    val colorScheme = ThemePresets.schemeFor(appearance.presetId, darkTheme)

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = MessagesShapes,
        content = content
    )
}
