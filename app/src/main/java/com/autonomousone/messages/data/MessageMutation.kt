package com.autonomousone.messages.data

/**
 * Identity of a message in the app's read model.
 * Composite key mirrors MessageEntity's primary key (source, providerId).
 */
data class MessageKey(val source: String, val providerId: Long)

/**
 * Exact mutations: NEVER conflated — every insert, delete, and status
 * change must reach Room exactly once.
 */
sealed interface MessageMutation {

    /** Insert or update a single message by exact identity. */
    data class Upsert(
        val source: String,
        val message: com.autonomousone.messages.model.Sms
    ) : MessageMutation

    /** Delete a single message by exact identity. */
    data class Delete(
        val source: String,
        val providerId: Long,
        val threadId: Long? = null
    ) : MessageMutation

    /** Update delivery/send status for a single outgoing message. */
    data class RefreshStatus(
        val source: String,
        val providerId: Long
    ) : MessageMutation

    /** Mark all messages in a thread as read. */
    data class MarkThreadRead(
        val threadId: Long
    ) : MessageMutation

    /** Delete all messages in a thread. */
    data class DeleteThread(
        val threadId: Long
    ) : MessageMutation
}

/**
 * Reconcile requests: CONFLATED — N nudges collapse into 1 execution.
 * Used for startup, crash recovery, and fallback when the observer
 * cannot provide a specific URI/id.
 */
sealed interface ReconcileRequest {
    data object FullSync : ReconcileRequest
    data class ForThread(val threadId: Long) : ReconcileRequest
}
