package com.autonomousone.messages.ui.conversation

/**
 * v3.4.5 P0-H — what the conversation body renders while it holds no messages.
 *
 * Before this existed the decision was an implicit nest of three unrelated
 * conditions (`chatItems.isEmpty()` → launch snapshot → isLoading → empty
 * state), and the launch snapshot sat ABOVE loading and failure. That ordering
 * is exactly how a failed loader could look like a successfully opened
 * conversation forever: Home's one-bubble snapshot painted, the real window
 * never arrived, and nothing on screen said so.
 *
 * [FAILED] is therefore evaluated BEFORE [LAUNCH_PREVIEW]. The snapshot may
 * still be drawn underneath it, but it can no longer hide the failure.
 *
 * Pure on purpose: the ordering contract is unit-testable without Compose.
 */
enum class ConversationEmptyContent {
    /** The real window is on screen. */
    MESSAGES,

    /** The initial load failed and nothing was painted: Retry must be offered. */
    FAILED,

    /** Nothing painted yet, but Home's snapshot bridges the first frame. */
    LAUNCH_PREVIEW,

    /** Nothing painted yet and a bounded load is in flight. */
    LOADING,

    /** Genuinely empty conversation. */
    EMPTY
}

/**
 * Resolves the conversation body state. [hasMessages] wins over everything
 * else: a painted window is never hidden by a stale failure flag.
 */
fun resolveConversationEmptyContent(
    hasMessages: Boolean,
    hasLaunchSnapshot: Boolean,
    isLoading: Boolean,
    initialLoadFailed: Boolean
): ConversationEmptyContent = when {
    hasMessages -> ConversationEmptyContent.MESSAGES
    initialLoadFailed -> ConversationEmptyContent.FAILED
    hasLaunchSnapshot -> ConversationEmptyContent.LAUNCH_PREVIEW
    isLoading -> ConversationEmptyContent.LOADING
    else -> ConversationEmptyContent.EMPTY
}
