package com.autonomousone.messages.sync

import com.autonomousone.messages.messaging.SimRefFormat
import org.json.JSONException
import org.json.JSONObject

/**
 * `SET_CONVERSATION_PREFERRED_SIM` — the sticky per-conversation SIM, as a pure contract.
 *
 * ## The shape rule that matters
 *
 * JSON cannot distinguish "the field is missing" from "the field is null" on its own, and here the
 * difference is the whole point:
 *
 * ```text
 * simRef absent  => INVALID command   (the sender did not say what it wants)
 * simRef null    => explicit CLEAR    (the sender said: go back to the phone default)
 * simRef "sim:v1:..." => SET          (must resolve against the live inventory)
 * ```
 *
 * Treating an absent field as "clear" would silently unpin a conversation because a client forgot a
 * key — a user's explicit choice destroyed by an omission. Treating it as "keep the current value"
 * would ACK a change that never happened. So absence is rejected, loudly and terminally.
 *
 * Parsing is pure and Android-free so every branch is asserted directly rather than inferred from an
 * executor's behaviour.
 */
object ConversationSimCommand {

    const val TYPE = "SET_CONVERSATION_PREFERRED_SIM"

    private const val FIELD_THREAD = "androidThreadId"
    private const val FIELD_SIM_REF = "simRef"

    /** A parsed, still-unvalidated command. */
    sealed interface Parsed {

        /** A structurally valid request. [simRef] null means "clear the preference". */
        data class Ok(val androidThreadId: Long, val simRef: String?) : Parsed

        /** Structurally invalid: never executed, never ACKed as success. */
        data class Invalid(val code: SyncErrorCode) : Parsed
    }

    /**
     * Parse the decrypted payload.
     *
     * @param payload the decrypted command body.
     */
    fun parse(payload: String): Parsed {
        val json = try {
            JSONObject(payload)
        } catch (e: JSONException) {
            return Parsed.Invalid(SyncErrorCode.READ_INVALID_PAYLOAD)
        } catch (e: Exception) {
            return Parsed.Invalid(SyncErrorCode.READ_INVALID_PAYLOAD)
        }
        return parse(json)
    }

    fun parse(json: JSONObject): Parsed {
        // `has` is the presence test; `isNull` is the explicit-null test. They are NOT interchangeable
        // and the contract depends on the difference, so both are checked before anything is read.
        if (!json.has(FIELD_SIM_REF)) return Parsed.Invalid(SyncErrorCode.INVALID_SIM_REFERENCE)

        val threadId = json.optLong(FIELD_THREAD, -1L)
        // A thread id of 0 or less cannot address a real Telephony thread; accepting it would look up
        // the wrong conversation (or none) and ACK a change nobody asked for.
        if (threadId <= 0L) return Parsed.Invalid(SyncErrorCode.THREAD_NOT_FOUND)

        if (json.isNull(FIELD_SIM_REF)) return Parsed.Ok(threadId, simRef = null)

        // A present-but-not-a-string value (a number, an object, a boolean) is malformed, not null.
        val raw = json.optString(FIELD_SIM_REF, "")
        if (raw.isBlank()) return Parsed.Invalid(SyncErrorCode.INVALID_SIM_REFERENCE)
        if (!SimRefFormat.isValid(raw)) return Parsed.Invalid(SyncErrorCode.INVALID_SIM_REFERENCE)

        return Parsed.Ok(threadId, simRef = raw)
    }
}

/**
 * Why a `SET_CONVERSATION_PREFERRED_SIM` failed, as a typed cause.
 *
 * Kept separate from [ReadCommandFailure] because a preference is neither a read nor a send, and
 * reporting it with `SMS_SEND_FAILED` would tell GMweb that a message failed when none was ever
 * involved — the same conflation the read taxonomy exists to prevent.
 */
sealed class ConversationSimFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** The payload decrypted but is not the expected shape. */
    class InvalidPayload(val code: SyncErrorCode, cause: Throwable? = null) :
        ConversationSimFailure("conversation SIM command payload is not valid", cause)

    /** No conversation exists locally for the requested thread. */
    class ThreadNotFound(val threadId: Long) :
        ConversationSimFailure("no conversation for thread $threadId")

    /** The reference matches no active subscription. */
    class SimNotFound(val reference: String) :
        ConversationSimFailure("no active subscription matches the requested simRef")

    /** The subscription exists but is not usable right now. */
    class SimInactive(val reference: String) :
        ConversationSimFailure("the requested subscription is not active")

    /** The preference could not be persisted, so the change must not be reported as applied. */
    class PersistFailed(cause: Throwable? = null) :
        ConversationSimFailure("conversation SIM preference could not be persisted", cause)

    /**
     * The preference IS durable, but the canonical conversation event was not queued, so GMweb cannot
     * learn about it.
     *
     * Deliberately distinct from [PersistFailed]: the local change happened and must not be undone or
     * reported as "not applied", while the replication half genuinely failed. Collapsing the two would
     * tell the user their choice was lost when it was in fact saved on the device — or the opposite.
     */
    class PublishFailed(cause: Throwable? = null) :
        ConversationSimFailure("the canonical conversation event was not queued", cause)

    /** The provider's inventory could not be read, so the reference could not be validated. */
    class InventoryUnavailable :
        ConversationSimFailure("the SIM inventory could not be read")
}

/** Maps a preference-command failure to the stable code GMweb and the local ledger see. */
object ConversationSimError {

    /** The command type, so a caller cannot pass a hardcoded string that later drifts. */
    const val COMMAND_TYPE = ConversationSimCommand.TYPE

    fun classify(error: Throwable): SyncErrorCode = when (error) {
        is ConversationSimFailure.InvalidPayload -> error.code
        is ConversationSimFailure.ThreadNotFound -> SyncErrorCode.THREAD_NOT_FOUND
        is ConversationSimFailure.SimNotFound -> SyncErrorCode.SIM_NOT_FOUND
        is ConversationSimFailure.SimInactive -> SyncErrorCode.SIM_INACTIVE
        is ConversationSimFailure.PersistFailed -> SyncErrorCode.PREFERENCE_UPDATE_FAILED
        is ConversationSimFailure.PublishFailed -> SyncErrorCode.READ_EVENT_PUBLISH_FAILED
        is ConversationSimFailure.InventoryUnavailable -> SyncErrorCode.SIM_NOT_FOUND
        is JSONException -> SyncErrorCode.INVALID_SIM_REFERENCE
        else -> SyncErrorCode.PREFERENCE_UPDATE_FAILED
    }

    /** True when this command type is the preference command. */
    fun handles(commandType: String): Boolean = commandType == COMMAND_TYPE
}
