package com.autonomousone.messages.sync

import org.json.JSONException

/**
 * Why a `MARK_THREAD_READ` command failed, as a typed cause rather than prose.
 *
 * The read path used to report every failure the same way: the execution ledger recorded
 * `SMS_SEND_FAILED` (a SEND code, on a read) and GMweb received `FAILED` plus a free-text message.
 * So a conversation GMweb knew about but the phone had no mapping for looked identical to a
 * transient provider refusal — and the two need opposite responses (re-link the conversation, versus
 * retry).
 *
 * These types exist so the classification is a total function over what the code can actually throw,
 * and so the poller never has to pattern-match on exception text.
 */
sealed class ReadCommandFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** The command payload could not be decrypted. */
    class Decrypt(cause: Throwable? = null) :
        ReadCommandFailure("read command payload could not be decrypted", cause)

    /** The payload decrypted but is not the expected shape. */
    class InvalidPayload(cause: Throwable? = null) :
        ReadCommandFailure("read command payload is not valid", cause)

    /** No `remote_conversation_map` row for the requested conversation id. */
    class MappingMissing(val conversationId: String) :
        ReadCommandFailure("no thread mapping for conversation $conversationId")

    /** The mapping exists but the thread is not in the local projection. */
    class UnknownConversation(val threadId: Long) :
        ReadCommandFailure("mapped thread $threadId is not present locally")

    /** The Android provider refused the read write (SMS or MMS half). */
    class ProviderWriteFailed(val reason: String) :
        ReadCommandFailure("provider refused the read write: $reason")

    /** The provider was updated but the durable read event was refused by a local policy gate. */
    class EventPublishFailed(val attempt: String) :
        ReadCommandFailure("read event was not queued ($attempt)")
}

/**
 * Maps a read-command failure to the stable code GMweb and the local ledger see.
 *
 * Pure, so the taxonomy is asserted directly instead of being inferred from a catch block. Note what
 * it does NOT do: it never returns [SyncErrorCode.SMS_SEND_FAILED] — a read is not a send — and it
 * only returns [SyncErrorCode.UNKNOWN] for a cause it genuinely cannot name, rather than as a
 * default that hides a classifiable failure.
 */
object ReadCommandError {

    fun classify(error: Throwable): SyncErrorCode = when (error) {
        is ReadCommandFailure.Decrypt -> SyncErrorCode.READ_COMMAND_DECRYPT_FAILED
        is ReadCommandFailure.InvalidPayload -> SyncErrorCode.READ_INVALID_PAYLOAD
        is ReadCommandFailure.MappingMissing -> SyncErrorCode.READ_MAPPING_MISSING
        is ReadCommandFailure.UnknownConversation -> SyncErrorCode.READ_UNKNOWN_CONVERSATION
        is ReadCommandFailure.ProviderWriteFailed -> SyncErrorCode.READ_PROVIDER_WRITE_FAILED
        is ReadCommandFailure.EventPublishFailed -> SyncErrorCode.READ_EVENT_PUBLISH_FAILED
        is SecurityException -> SyncErrorCode.READ_PERMISSION_DENIED
        is JSONException -> SyncErrorCode.READ_INVALID_PAYLOAD
        else -> SyncErrorCode.UNKNOWN
    }

    /**
     * The code for a command type, so a read failure can never be recorded as a send failure.
     *
     * `SEND_SMS` keeps its own code; only the read type is classified here.
     */
    fun classifyFor(commandType: String, error: Throwable): SyncErrorCode =
        if (commandType == READ_COMMAND_TYPE) classify(error) else SyncErrorCode.SMS_SEND_FAILED

    const val READ_COMMAND_TYPE = "MARK_THREAD_READ"
}
