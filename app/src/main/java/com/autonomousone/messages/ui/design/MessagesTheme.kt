package com.autonomousone.messages.ui.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable

/**
 * Design-system composition helper (PR-01 foundation).
 *
 * Exposes the centralized [MessagesTypography.Material] and
 * [MessagesShapes.Material] tokens so the active theme can be built from a
 * single source of truth. The app's runtime theme entry point remains
 * `ui/theme/MessagesTheme` (which resolves user light/dark/preset via
 * ThemeController and must not move — it owns appearance state). PR-01 wires
 * the tokens here; components adopt the [MessageThemeColors] accessors in
 * PR-03/PR-05.
 */
@Composable
fun MessagesDesignSystem(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        typography = MessagesTypography.Material,
        shapes = MessagesShapes.Material,
        content = content,
    )
}

// Reserved aliases so downstream code (PR-02+) reads the same names RFP §7 lists,
// without colliding with the runtime `ui/theme.MessagesTheme` composable.
internal val MessagesTypographyTokens: Typography = MessagesTypography.Material
internal val MessagesShapeTokens: Shapes = MessagesShapes.Material
