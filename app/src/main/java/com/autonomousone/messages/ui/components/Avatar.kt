package com.autonomousone.messages.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autonomousone.messages.ui.theme.AvatarGradients
import com.autonomousone.messages.ui.theme.StatusSuccess
import kotlin.math.abs

enum class AvatarSize(val dp: Dp, val fontSize: Int) {
    Small(36.dp, 14),
    Medium(52.dp, 19),
    Large(62.dp, 23),
    XLarge(84.dp, 32)
}

/**
 * Flagship Avatar Composable with multi-stop dynamic gradients, status ring, micro-interactions,
 * and support for custom sizes.
 */
@Composable
fun Avatar(
    name: String,
    modifier: Modifier = Modifier,
    size: AvatarSize = AvatarSize.Medium,
    isOnline: Boolean = false,
    showBorder: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val animatedScale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(stiffness = 400f),
        label = "avatarScale"
    )

    // Compute initials from name or phone number
    val initials = remember(name) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) "?"
        else {
            val words = trimmed.split(" ").filter { it.isNotBlank() }
            if (words.size >= 2 && words[0].first().isLetter() && words[1].first().isLetter()) {
                "${words[0].first().uppercaseChar()}${words[1].first().uppercaseChar()}"
            } else {
                // Find first letter or digit
                val firstChar = trimmed.firstOrNull { it.isLetterOrDigit() }
                if (firstChar != null) {
                    if (firstChar.isLetter()) {
                        firstChar.uppercase()
                    } else {
                        // Phone number - display first 1-2 digits or first digit cleanly
                        val digitsOnly = trimmed.filter { it.isDigit() }
                        if (digitsOnly.length >= 2) digitsOnly.take(2)
                        else digitsOnly.take(1).ifEmpty { "?" }
                    }
                } else {
                    "?"
                }
            }
        }
    }

    // Deterministic gradient index from name hash
    val gradientPair = remember(name) {
        val index = abs(name.hashCode()) % AvatarGradients.size
        AvatarGradients[index]
    }

    Box(
        modifier = modifier
            .scale(animatedScale)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick
                    )
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(size.dp)
                .shadow(
                    elevation = if (isPressed) 1.dp else 2.dp,
                    shape = CircleShape,
                    clip = false
                )
                .clip(CircleShape)
                .then(
                    if (showBorder) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    } else Modifier
                )
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(gradientPair.first, gradientPair.second)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = initials,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = size.fontSize.sp,
                letterSpacing = 0.sp
            )
        }

        // Online dot indicator
        if (isOnline) {
            val dotSize = when (size) {
                AvatarSize.Small -> 10.dp
                AvatarSize.Medium -> 14.dp
                AvatarSize.Large -> 16.dp
                AvatarSize.XLarge -> 20.dp
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 1.dp, y = 1.dp)
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
            ) {
                Box(
                    modifier = Modifier
                        .size(dotSize - 4.dp)
                        .align(Alignment.Center)
                        .clip(CircleShape)
                        .background(StatusSuccess)
                )
            }
        }
    }
}