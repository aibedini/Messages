package com.autonomousone.messages.ui.design

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * Centralized motion system (RFP §20).
 *
 * Restrained motion that communicates only what changed:
 * - item moved / placed
 * - content changed
 * - sheet / menu / button state changed
 *
 * Deliberately no decorative bounce on bubbles — messenger messages must never
 * feel springy (this matches the settled `animateItem` critical-damping rule in
 * `ConversationScreen.kt`). PR-06 migrates call sites onto these tokens.
 */
object MessagesMotion {

    /** Quick UI feedback (button press, badge, chip). */
    val Fast: TweenSpec<Float> = tween(durationMillis = 120, easing = FastOutSlowInEasing)

    /** Default content/visibility change. */
    val Normal: TweenSpec<Float> = tween(durationMillis = 200, easing = FastOutSlowInEasing)

    /** Emphasized / full-screen or sheet transitions. */
    val Emphasized: TweenSpec<Float> = tween(durationMillis = 300, easing = FastOutSlowInEasing)

    /** List placement — movement of EXISTING rows to make room. Critical damped,
     *  placement-only, no bounce. Matches the settled spring(550, damping 1). */
    val ListPlacement: SpringSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium,
    )

    /** A genuinely new bubble entering (rise + alpha). Rise is part of the enter
     *  motion in PR-06; this token carries the translate/alpha tween. */
    val ContentChange: TweenSpec<Float> = tween(durationMillis = 190, easing = FastOutSlowInEasing)

    /** Bottom sheet / menu appearance. */
    val Sheet: TweenSpec<Float> = tween(durationMillis = 250, easing = FastOutSlowInEasing)
}
