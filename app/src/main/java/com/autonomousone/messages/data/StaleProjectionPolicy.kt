package com.autonomousone.messages.data

/**
 * The pure staleness decision behind the Home safety net.
 *
 * `conversations.lastMessageUiState` is a denormalized attention marker computed only when a
 * projection is written. A missed refresh leaves it stuck, while the chat bubble (which reads the
 * message row directly) already shows the resolved state. The safety net must therefore sweep every
 * attention state, not just SENDING: a thread stuck on SEND_STATUS_UNKNOWN or NOT_SENT is just as
 * stale.
 *
 * Kept Android-free so the boundary is a JVM test rather than a comment.
 */
object StaleProjectionPolicy {

    /** States for which Home shows an attention marker and therefore may be stale. */
    val ATTENTION_STATES: Set<String> = setOf(
        "SENDING",
        "SEND_STATUS_UNKNOWN",
        "NOT_SENT",
        "NOT_DELIVERED",
        "DELIVERY_UNKNOWN"
    )

    /**
     * @param uiState the persisted `lastMessageUiState`; null is the quiet case and is never swept.
     * @param newestDate the newest active message's timestamp; non-positive means it cannot be aged.
     * @param staleAfterMs how long a projection may sit unresolved before it is rebuilt.
     */
    fun isStale(
        uiState: String?,
        newestDate: Long,
        now: Long,
        staleAfterMs: Long
    ): Boolean {
        if (uiState == null || uiState !in ATTENTION_STATES) return false
        if (newestDate <= 0L || staleAfterMs < 0L) return false
        if (now < newestDate) return false
        return now - newestDate >= staleAfterMs
    }
}
