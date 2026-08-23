package com.autonomousone.messages.ui.theme

import androidx.compose.ui.graphics.Color

// ─────────────────────────────────────────────────────────────────────────────
// Light Theme Palette — Google Messages-style blue on neutral gray.
// Sent bubbles use primaryContainer (flat, no gradient); received bubbles
// use surfaceVariant, exactly like the leading SMS apps.
// ─────────────────────────────────────────────────────────────────────────────
val LightBackground = Color(0xFFF8F9FC)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFEEF1F5)
val LightPrimary = Color(0xFF0B57D0)          // Google Blue
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFC2E7FF) // outgoing bubble (flat light blue)
val LightOnPrimaryContainer = Color(0xFF062E6F)
val LightSecondary = Color(0xFF565E71)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFDAE2F9)
val LightOnSecondaryContainer = Color(0xFF131B2C)
val LightAccent = Color(0xFF715573)
val LightTextPrimary = Color(0xFF191C20)
val LightTextSecondary = Color(0xFF5C5F66)
val LightTextTertiary = Color(0xFF78797E)
val LightDivider = Color(0xFFDBDEE4)

// ─────────────────────────────────────────────────────────────────────────────
// OLED Dark Theme Palette — deep neutral surfaces with tonal blue accents.
// ─────────────────────────────────────────────────────────────────────────────
val DarkBackground = Color(0xFF0D0F12)
val DarkSurface = Color(0xFF14171C)
val DarkSurfaceVariant = Color(0xFF23272E)
val DarkPrimary = Color(0xFFA8C7FA)
val DarkOnPrimary = Color(0xFF062E6F)
val DarkPrimaryContainer = Color(0xFF004A77)  // outgoing bubble (deep blue)
val DarkOnPrimaryContainer = Color(0xFFC2E7FF)
val DarkSecondary = Color(0xFFBEC6DC)
val DarkOnSecondary = Color(0xFF283141)
val DarkSecondaryContainer = Color(0xFF3E4759)
val DarkOnSecondaryContainer = Color(0xFFDAE2F9)
val DarkAccent = Color(0xFFDFBBE1)
val DarkTextPrimary = Color(0xFFE2E2E9)
val DarkTextSecondary = Color(0xFFC4C6D0)
val DarkTextTertiary = Color(0xFF8E9099)
val DarkDivider = Color(0xFF3A3D45)

// Status & Indicator Colors
val UnreadBadgeColor = Color(0xFF1967D2)
val StatusSuccess = Color(0xFF22C55E)
val StatusWarning = Color(0xFFF59E0B)
val StatusError = Color(0xFFEF4444)

// Delivery-status tint for failed chat bubbles (readable on both palettes).
val FailedTint = Color(0xFFFF8A80)

// Glassmorphism & Overlay Tints
val LightGlassOverlay = Color(0xCCFFFFFF)
val DarkGlassOverlay = Color(0xCC111111)

// Avatar Gradient Pairs (Start, End)
val AvatarGradients = listOf(
    Pair(Color(0xFF10B981), Color(0xFF059669)), // Emerald
    Pair(Color(0xFF3B82F6), Color(0xFF1D4ED8)), // Blue
    Pair(Color(0xFF8B5CF6), Color(0xFF6D28D9)), // Purple
    Pair(Color(0xFFEC4899), Color(0xFFBE185D)), // Pink
    Pair(Color(0xFFF59E0B), Color(0xFFD97706)), // Amber
    Pair(Color(0xFF06B6D4), Color(0xFF0F766E)), // Cyan
    Pair(Color(0xFF6366F1), Color(0xFF4338CA)), // Indigo
    Pair(Color(0xFF14B8A6), Color(0xFF0D9488))  // Teal
)
