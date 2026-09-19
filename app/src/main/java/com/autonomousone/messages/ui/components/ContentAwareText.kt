package com.autonomousone.messages.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import com.autonomousone.messages.utils.ContentDirection
import com.autonomousone.messages.utils.ContentDirectionResolver

/**
 * Content-aware text style (v3.4.2).
 *
 * WHY ONLY THE PARAGRAPH: [TextDirection] + [TextAlign] are TEXT properties. They
 * change where a paragraph starts without touching layout, so the bubble keeps its
 * ownership side, the tail, the timestamp/status row, the resend action and every
 * icon — which is exactly what wrapping a bubble in an RTL [LocalLayoutDirection]
 * would have broken.
 *
 * `NEUTRAL` (digits, emoji, punctuation, empty) falls back to the surrounding UI
 * layout, so a number-only message reads naturally in both a Persian and an English
 * app rather than being forced one way.
 *
 * `TextAlign.Start` is paired with the direction on purpose: start-of-RTL is the
 * right edge and start-of-LTR is the left edge, so a short `سلام` hugs the right
 * inside its bubble and a short `Hi` hugs the left, with no hardcoded Left/Right.
 */
@Composable
fun rememberContentDirection(text: CharSequence?): ContentDirection =
    remember(text?.toString()) { ContentDirectionResolver.resolve(text) }

/**
 * Applies [rememberContentDirection] to [base], keeping every other typography
 * property (family, size, weight, line height, colour) exactly as the caller set it.
 */
@Composable
fun contentAwareTextStyle(text: CharSequence?, base: TextStyle): TextStyle {
    val uiLayoutDirection = LocalLayoutDirection.current
    val direction = rememberContentDirection(text)
    return base.withContentDirection(direction, uiLayoutDirection)
}

/**
 * Non-composable core of the mapping, so the RTL/LTR/NEUTRAL contract is unit-testable
 * without a Compose runtime.
 */
fun TextStyle.withContentDirection(
    direction: ContentDirection,
    uiLayoutDirection: LayoutDirection
): TextStyle = when (direction) {
    ContentDirection.RTL -> copy(
        textDirection = TextDirection.Rtl,
        textAlign = TextAlign.Start
    )
    ContentDirection.LTR -> copy(
        textDirection = TextDirection.Ltr,
        textAlign = TextAlign.Start
    )
    ContentDirection.NEUTRAL -> copy(
        textDirection = if (uiLayoutDirection == LayoutDirection.Rtl) {
            TextDirection.Rtl
        } else {
            TextDirection.Ltr
        },
        textAlign = TextAlign.Start
    )
}
