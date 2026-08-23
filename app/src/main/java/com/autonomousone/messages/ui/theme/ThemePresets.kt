package com.autonomousone.messages.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Curated theme presets (Google Messages-style palettes).
 * Each preset defines the accent quartet for light and dark; neutral surfaces
 * are shared so every preset keeps the same clean, flat look.
 */
object ThemePresets {

    /** Accent colors per preset: primary, onPrimary, primaryContainer, onPrimaryContainer. */
    data class Accents(
        val primary: Color,
        val onPrimary: Color,
        val container: Color,
        val onContainer: Color
    )

    data class Preset(
        val id: String,
        val label: String,
        val swatch: Color,          // color shown in the picker UI
        val light: Accents,
        val dark: Accents
    )

    val all: List<Preset> = listOf(
        Preset(
            "ocean_blue", "Ocean Blue", Color(0xFF0B57D0),
            Accents(Color(0xFF0B57D0), Color(0xFFFFFFFF), Color(0xFFC2E7FF), Color(0xFF062E6F)),
            Accents(Color(0xFFA8C7FA), Color(0xFF062E6F), Color(0xFF004A77), Color(0xFFC2E7FF))
        ),
        Preset(
            "emerald_green", "Emerald Green", Color(0xFF286140),
            Accents(Color(0xFF286140), Color(0xFFFFFFFF), Color(0xFFD8EBDD), Color(0xFF123821)),
            Accents(Color(0xFF93D5A7), Color(0xFF10351E), Color(0xFF274C33), Color(0xFFC3F0CE))
        ),
        Preset(
            "royal_purple", "Royal Purple", Color(0xFF6750A4),
            Accents(Color(0xFF6750A4), Color(0xFFFFFFFF), Color(0xFFEADDFF), Color(0xFF21005D)),
            Accents(Color(0xFFD0BCFF), Color(0xFF381E72), Color(0xFF4F378B), Color(0xFFEADDFF))
        ),
        Preset(
            "sunset_amber", "Sunset Amber", Color(0xFF8F5B00),
            Accents(Color(0xFF8F5B00), Color(0xFFFFFFFF), Color(0xFFFFDF9E), Color(0xFF2C1A00)),
            Accents(Color(0xFFF5BD52), Color(0xFF432C00), Color(0xFF613F00), Color(0xFFFFDEA8))
        ),
        Preset(
            "rose_pink", "Rose Pink", Color(0xFF984061),
            Accents(Color(0xFF984061), Color(0xFFFFFFFF), Color(0xFFFFD9E2), Color(0xFF3E001D)),
            Accents(Color(0xFFFFB1C8), Color(0xFF5E1133), Color(0xFF7A2947), Color(0xFFFFD9E2))
        ),
        Preset(
            "teal_cyan", "Teal Cyan", Color(0xFF00696E),
            Accents(Color(0xFF00696E), Color(0xFFFFFFFF), Color(0xFF9CF1F5), Color(0xFF002023)),
            Accents(Color(0xFF4FD8EB), Color(0xFF00363B), Color(0xFF004F55), Color(0xFF9CF1F5))
        )
    )

    const val DEFAULT_ID = "ocean_blue"

    fun byId(id: String): Preset = all.firstOrNull { it.id == id } ?: all.first()

    // Shared neutral surfaces (slightly cool gray) â€” identical across presets.
    private val L_BG = 0xFFF8F9FC.toInt()
    private val L_SURFACE = 0xFFFFFFFF.toInt()
    private val L_SURFACE_VARIANT = 0xFFEEF1F5.toInt()
    private val L_TEXT_PRIMARY = 0xFF191C20.toInt()
    private val L_TEXT_SECONDARY = 0xFF5C5F66.toInt()
    private val L_DIVIDER = 0xFFDBDEE4.toInt()
    private val L_SECONDARY = 0xFF565E71.toInt()
    private val L_SECONDARY_CONTAINER = 0xFFDAE2F9.toInt()
    private val L_ON_SECONDARY_CONTAINER = 0xFF131B2C.toInt()

    private val D_BG = 0xFF0D0F12.toInt()
    private val D_SURFACE = 0xFF14171C.toInt()
    private val D_SURFACE_VARIANT = 0xFF23272E.toInt()
    private val D_TEXT_PRIMARY = 0xFFE2E2E9.toInt()
    private val D_TEXT_SECONDARY = 0xFFC4C6D0.toInt()
    private val D_DIVIDER = 0xFF3A3D45.toInt()
    private val D_SECONDARY = 0xFFBEC6DC.toInt()
    private val D_SECONDARY_CONTAINER = 0xFF3E4759.toInt()
    private val D_ON_SECONDARY_CONTAINER = 0xFFDAE2F9.toInt()

    private fun scheme(preset: Preset, dark: Boolean): ColorScheme {
        val a = if (dark) preset.dark else preset.light
        return if (dark) {
            darkColorScheme(
                primary = a.primary,
                onPrimary = a.onPrimary,
                primaryContainer = a.container,
                onPrimaryContainer = a.onContainer,
                secondary = Color(D_SECONDARY),
                onSecondary = Color(D_TEXT_PRIMARY),
                secondaryContainer = Color(D_SECONDARY_CONTAINER),
                onSecondaryContainer = Color(D_ON_SECONDARY_CONTAINER),
                tertiary = a.primary,
                background = Color(D_BG),
                onBackground = Color(D_TEXT_PRIMARY),
                surface = Color(D_SURFACE),
                onSurface = Color(D_TEXT_PRIMARY),
                surfaceVariant = Color(D_SURFACE_VARIANT),
                onSurfaceVariant = Color(D_TEXT_SECONDARY),
                outline = Color(D_DIVIDER),
                outlineVariant = Color(D_DIVIDER),
                error = StatusError
            )
        } else {
            lightColorScheme(
                primary = a.primary,
                onPrimary = a.onPrimary,
                primaryContainer = a.container,
                onPrimaryContainer = a.onContainer,
                secondary = Color(L_SECONDARY),
                onSecondary = Color(L_TEXT_PRIMARY),
                secondaryContainer = Color(L_SECONDARY_CONTAINER),
                onSecondaryContainer = Color(L_ON_SECONDARY_CONTAINER),
                tertiary = a.primary,
                background = Color(L_BG),
                onBackground = Color(L_TEXT_PRIMARY),
                surface = Color(L_SURFACE),
                onSurface = Color(L_TEXT_PRIMARY),
                surfaceVariant = Color(L_SURFACE_VARIANT),
                onSurfaceVariant = Color(L_TEXT_SECONDARY),
                outline = Color(L_DIVIDER),
                outlineVariant = Color(L_DIVIDER),
                error = StatusError
            )
        }
    }

    /** Resolve the full [ColorScheme] for a preset id + dark flag (falls back to default). */
    fun schemeFor(presetId: String, dark: Boolean): ColorScheme =
        scheme(byId(presetId), dark)
}
