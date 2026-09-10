package com.autonomousone.messages.ui.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Central shape scale (Material 3 token style).
 *
 * Values match the existing `ui/theme/Shape.kt` exactly so PR-01 changes no
 * visible corners (RFP §24). Shape refinement happens in PR-03/PR-05.
 */
object MessagesShapes {
    val ExtraSmall = RoundedCornerShape(6.dp)
    val Small = RoundedCornerShape(10.dp)
    val Medium = RoundedCornerShape(16.dp)
    val Large = RoundedCornerShape(22.dp)
    val ExtraLarge = RoundedCornerShape(28.dp)

    /** Bubble corner — slightly tighter on the sender edge, handled per-side in
     *  the bubble component (PR-04/PR-05). Base radius used today. */
    val Bubble = RoundedCornerShape(18.dp)

    val Material: Shapes = Shapes(
        extraSmall = ExtraSmall,
        small = Small,
        medium = Medium,
        large = Large,
        extraLarge = ExtraLarge,
    )
}
