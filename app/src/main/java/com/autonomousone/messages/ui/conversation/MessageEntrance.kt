package com.autonomousone.messages.ui.conversation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * v2.6.9: dedicated ENTER motion for live messages only.
 *
 * animateItem() owns row *placement* (existing bubbles sliding apart with a
 * critical-damped spring); this owns the *appearance* of a genuinely new
 * bubble: a 10dp rise, a 0.965 -> 1 scale anchored at the bubble's bottom
 * corner, and a fast fade — ~190ms total. No bounce: messaging bubbles must
 * settle exactly once.
 *
 * Crucially, this is only ever mounted for entries the ViewModel marked as
 * live (own optimistic send, incoming SMS while at the latest edge). Initial
 * Room/cache hydration must NOT animate per-bubble — that is the difference
 * between "history quietly fills" and "20 bubbles play pop-up on open".
 */
@Composable
fun MessageEntrance(
    messageId: Long,
    animate: Boolean,
    outgoing: Boolean,
    modifier: Modifier = Modifier,
    onAnimationFinished: () -> Unit = {},
    content: @Composable () -> Unit
) {
    if (!animate) {
        Box(modifier = modifier) {
            content()
        }
        return
    }

    val visibleState = remember(messageId) {
        MutableTransitionState(false).apply {
            targetState = true
        }
    }

    val density = LocalDensity.current

    val verticalOffsetPx = remember(density) {
        with(density) {
            10.dp.roundToPx()
        }
    }

    AnimatedVisibility(
        visibleState = visibleState,
        modifier = modifier,
        enter =
            fadeIn(
                animationSpec = tween(
                    durationMillis = 110
                )
            ) +
            slideInVertically(
                animationSpec = tween(
                    durationMillis = 190,
                    easing = FastOutSlowInEasing
                ),
                initialOffsetY = { height ->
                    minOf(
                        verticalOffsetPx,
                        height / 2
                    )
                }
            ) +
            scaleIn(
                initialScale = 0.965f,
                transformOrigin =
                    if (outgoing) {
                        TransformOrigin(1f, 1f)
                    } else {
                        TransformOrigin(0f, 1f)
                    },
                animationSpec = spring(
                    dampingRatio = 0.95f,
                    stiffness = 520f
                )
            )
    ) {
        content()
    }

    LaunchedEffect(messageId) {
        delay(260)
        onAnimationFinished()
    }
}
