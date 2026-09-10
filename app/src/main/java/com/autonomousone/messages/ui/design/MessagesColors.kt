package com.autonomousone.messages.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color

/**
 * Semantic color surface for the Messages design system.
 *
 * PR-01 introduces this as the centralized place components read colors from,
 * backed by the existing Material [ColorScheme] so behavior is unchanged
 * (RFP §24 — preserve presets/light/dark). Components migrate to these
 * accessors in PR-03/PR-05; today they read MaterialTheme.colorScheme directly.
 */

/** Static light scheme used for the message-bubble sent colors (matches the
 *  current `LightPrimaryContainer` sent-bubble fill). Kept literal so a
 *  component can reuse the exact same blue container in either theme without
 *  guessing. */
object MessagesColors {
    val SentBubbleLight = Color(0xFFC2E7FF)
    val SentBubbleLightText = Color(0xFF062E6F)
    val SentBubbleDark = Color(0xFF004A77)
    val SentBubbleDarkText = Color(0xFFC2E7FF)

    val ReceivedBubbleLight = Color(0xFFEEF1F5)
    val ReceivedBubbleDark = Color(0xFF23272E)

    val FailedTint = Color(0xFFFF8A80)
    val UnreadBadge = Color(0xFF1967D2)
}

/** Theme-derived semantic accessors (resolved from the active scheme). */
object MessageThemeColors {
    val background: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.background
    val surface: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surface
    val surfaceVariant: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceVariant
    val primary: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary
    val onPrimary: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onPrimary
    val primaryContainer: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primaryContainer
    val onPrimaryContainer: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onPrimaryContainer
    val onSurface: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant
    val outline: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.outline
    val error: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.error
}
