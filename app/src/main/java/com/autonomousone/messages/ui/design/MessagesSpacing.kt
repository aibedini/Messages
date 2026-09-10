package com.autonomousone.messages.ui.design

import androidx.compose.ui.unit.dp

/**
 * Central spacing & dimension scale for the Messages UI (Material 3 token style).
 *
 * These values intentionally match the current layout so PR-01 introduces the
 * token surface WITHOUT changing any visible behavior (RFP §24, §35). Components
 * migrate onto these tokens in PR-02+.
 */
object MessagesSpacing {
    val Xxs = 2.dp
    val Xs = 4.dp
    val Sm = 8.dp
    val Md = 12.dp
    val Lg = 16.dp
    val Xl = 20.dp
    val Xxl = 24.dp
    val Xxxl = 32.dp

    /** Standard screen horizontal padding. */
    val ScreenPadding = Lg

    /** Avatar size used in conversation rows. */
    val AvatarSize = 44.dp

    /** Horizontal padding inside a message bubble. */
    val BubblePaddingH = Md
    /** Vertical padding inside a message bubble. */
    val BubblePaddingV = 10.dp

    /** Gap between consecutive (grouped) message bubbles. */
    val BubbleGap = 2.dp
    /** Gap before a bubble in a different sender group. */
    val BubbleGroupGap = 8.dp

    /** Conversation row vertical padding. */
    val RowPaddingV = 12.dp

    /** Unread badge size. */
    val BadgeSize = 20.dp
}
