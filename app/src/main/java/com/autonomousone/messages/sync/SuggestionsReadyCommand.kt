package com.autonomousone.messages.sync

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime

/**
 * `SUGGESTIONS_READY` — the GOD AI device's reply suggestions, as a pure contract (XR-GA-017).
 *
 * ## Why parsing is its own pure object
 *
 * The payload arrives through the E2EE command channel, and every field here is a decision:
 * which conversation, which inbound message, which generation, until when, and what may be shown.
 * Putting those decisions in a JVM-testable function means the reject rules are asserted directly
 * instead of being inferred from a poller branch that also talks HTTP.
 *
 * ## What is refused, and why an omission is never defaulted
 *
 * ```text
 * schema_version missing or != 1     => INVALID   (an unknown contract must not be guessed)
 * thread_ref blank                   => INVALID   (nothing to address)
 * for_message_ref blank              => INVALID   (a card with no anchor cannot be proven fresh)
 * conversation_generation <= 0       => INVALID   (a generation of 0 is not a generation)
 * expires_at missing/unparseable     => INVALID   (an unexpiring suggestion is not allowed)
 * suggestions empty / > MAX          => INVALID   (a card list is bounded, never open-ended)
 * any suggestion without id or text  => INVALID   (the whole payload is atomic)
 * ```
 *
 * `kind` and `badge` are carried as strings and are deliberately NOT validated against a closed set:
 * GMweb may add a badge later, and refusing the entire suggestion set because of a label this build
 * has not learned yet would drop useful content for a cosmetic reason. The UI maps unknown values to
 * the conservative "needs review" badge.
 *
 * The payload uses the contract's snake_case field names verbatim (see
 * `docs/contracts/gmweb-linked-device-ai.md` §6), not the camelCase used by older commands.
 */
object SuggestionsReadyCommand {

    const val TYPE = "SUGGESTIONS_READY"

    /** The only wire contract this build understands. */
    const val SUPPORTED_SCHEMA_VERSION = 1

    /**
     * Upper bound on one payload's suggestions.
     *
     * The UI shows at most three; the extra headroom exists so a payload that carries alternatives
     * still parses, while an unbounded array is refused rather than persisted.
     */
    const val MAX_SUGGESTIONS = 5

    /** Upper bound on one suggestion's text. Anything larger is not a reply draft. */
    const val MAX_TEXT_CHARS = 2000

    private const val FIELD_TYPE = "type"
    private const val FIELD_SCHEMA_VERSION = "schema_version"
    private const val FIELD_THREAD_REF = "thread_ref"
    private const val FIELD_FOR_MESSAGE_REF = "for_message_ref"
    private const val FIELD_CONVERSATION_GENERATION = "conversation_generation"
    private const val FIELD_EXPIRES_AT = "expires_at"
    private const val FIELD_SUGGESTIONS = "suggestions"
    private const val FIELD_REASON_REF = "reason_ref"

    private const val FIELD_ID = "id"
    private const val FIELD_TEXT = "text"
    private const val FIELD_KIND = "kind"
    private const val FIELD_BADGE = "badge"
    private const val FIELD_SEGMENTS = "segments"

    /** One ready-to-render suggestion. [id] is the feedback identity and is never synthesized. */
    data class Suggestion(
        val id: String,
        val text: String,
        /** `approved_template` | `generated` | anything a newer GMweb adds. */
        val kind: String,
        /** `knowledge` | `needs_review` | anything a newer GMweb adds. */
        val badge: String,
        /** The sender's own estimate, if it made one. Never computed here. */
        val segments: Int?
    )

    /** A structurally valid `SUGGESTIONS_READY` command, still unvalidated against local state. */
    data class Request(
        val schemaVersion: Int,
        val threadRef: String,
        val forMessageRef: String,
        val conversationGeneration: Int,
        val expiresAtMs: Long,
        val suggestions: List<Suggestion>,
        val reasonRef: String?
    )

    sealed interface Parsed {
        data class Ok(val request: Request) : Parsed
        data class Invalid(val code: SyncErrorCode, val detail: String) : Parsed
    }

    /** Parse the decrypted command body. */
    fun parse(payload: String): Parsed {
        val json = try {
            JSONObject(payload)
        } catch (e: JSONException) {
            return Parsed.Invalid(SyncErrorCode.SUGGESTIONS_INVALID_PAYLOAD, "not_json")
        } catch (e: Exception) {
            return Parsed.Invalid(SyncErrorCode.SUGGESTIONS_INVALID_PAYLOAD, "not_json")
        }
        return parse(json)
    }

    fun parse(json: JSONObject): Parsed {
        // The plaintext type must agree with the envelope when it is present; an envelope/plaintext
        // disagreement means one of the two was rewritten, and neither is trustworthy.
        val declaredType = json.optString(FIELD_TYPE).takeIf { it.isNotBlank() }
        if (declaredType != null && declaredType != TYPE) {
            return Parsed.Invalid(SyncErrorCode.SUGGESTIONS_INVALID_PAYLOAD, "type_mismatch")
        }

        // `has` vs `isNull`: an ABSENT schema_version is not "version 1 by assumption". A sender that
        // did not declare its contract may have sent fields this build would misread.
        if (!json.has(FIELD_SCHEMA_VERSION) || json.isNull(FIELD_SCHEMA_VERSION)) {
            return Parsed.Invalid(SyncErrorCode.SUGGESTIONS_INVALID_PAYLOAD, "schema_missing")
        }
        val schemaVersion = json.optInt(FIELD_SCHEMA_VERSION, -1)
        if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            return Parsed.Invalid(SyncErrorCode.SUGGESTIONS_INVALID_PAYLOAD, "schema_unsupported")
        }

        val threadRef = json.optString(FIELD_THREAD_REF).trim()
        if (threadRef.isBlank()) {
            return Parsed.Invalid(SyncErrorCode.SUGGESTIONS_INVALID_PAYLOAD, "thread_ref_missing")
        }
        val forMessageRef = json.optString(FIELD_FOR_MESSAGE_REF).trim()
        if (forMessageRef.isBlank()) {
            return Parsed.Invalid(SyncErrorCode.SUGGESTIONS_INVALID_PAYLOAD, "for_message_ref_missing")
        }

        // A generation of 0 cannot be compared: 0 is "unset", and treating it as the oldest possible
        // generation would make every such command stale for a thread that has any inbound message.
        val generation = json.optInt(FIELD_CONVERSATION_GENERATION, -1)
        if (generation <= 0) {
            return Parsed.Invalid(SyncErrorCode.SUGGESTIONS_INVALID_PAYLOAD, "generation_invalid")
        }

        val expiresAt = parseExpiry(json.opt(FIELD_EXPIRES_AT))
            ?: return Parsed.Invalid(SyncErrorCode.SUGGESTIONS_INVALID_PAYLOAD, "expires_at_invalid")
        if (expiresAt <= 0L) {
            return Parsed.Invalid(SyncErrorCode.SUGGESTIONS_INVALID_PAYLOAD, "expires_at_invalid")
        }

        val array = json.optJSONArray(FIELD_SUGGESTIONS)
            ?: return Parsed.Invalid(SyncErrorCode.SUGGESTIONS_INVALID_PAYLOAD, "suggestions_missing")
        if (array.length() == 0) {
            return Parsed.Invalid(SyncErrorCode.SUGGESTIONS_INVALID_PAYLOAD, "suggestions_empty")
        }
        if (array.length() > MAX_SUGGESTIONS) {
            // Unbounded input is refused, never truncated: truncating would ACK a payload the device
            // did not actually honour, and the sender would believe every suggestion was stored.
            return Parsed.Invalid(SyncErrorCode.SUGGESTIONS_INVALID_PAYLOAD, "suggestions_unbounded")
        }

        val suggestions = ArrayList<Suggestion>(array.length())
        val seenIds = HashSet<String>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i)
                ?: return Parsed.Invalid(SyncErrorCode.SUGGESTIONS_INVALID_PAYLOAD, "suggestion_not_object")
            val id = item.optString(FIELD_ID).trim()
            if (id.isBlank()) {
                return Parsed.Invalid(SyncErrorCode.SUGGESTIONS_INVALID_PAYLOAD, "suggestion_id_missing")
            }
            if (!seenIds.add(id)) {
                // Duplicate ids make "which suggestion was selected?" ambiguous in the feedback event.
                return Parsed.Invalid(SyncErrorCode.SUGGESTIONS_INVALID_PAYLOAD, "suggestion_id_duplicate")
            }
            val text = item.optString(FIELD_TEXT)
            if (text.isBlank()) {
                return Parsed.Invalid(SyncErrorCode.SUGGESTIONS_INVALID_PAYLOAD, "suggestion_text_missing")
            }
            if (text.length > MAX_TEXT_CHARS) {
                return Parsed.Invalid(SyncErrorCode.SUGGESTIONS_INVALID_PAYLOAD, "suggestion_text_too_long")
            }
            suggestions.add(
                Suggestion(
                    id = id,
                    text = text,
                    kind = item.optString(FIELD_KIND).trim(),
                    badge = item.optString(FIELD_BADGE).trim(),
                    segments = if (item.has(FIELD_SEGMENTS) && !item.isNull(FIELD_SEGMENTS)) {
                        item.optInt(FIELD_SEGMENTS, 0).takeIf { it > 0 }
                    } else {
                        null
                    }
                )
            )
        }

        return Parsed.Ok(
            Request(
                schemaVersion = schemaVersion,
                threadRef = threadRef,
                forMessageRef = forMessageRef,
                conversationGeneration = generation,
                expiresAtMs = expiresAt,
                suggestions = suggestions,
                reasonRef = json.optString(FIELD_REASON_REF).trim().takeIf { it.isNotBlank() }
            )
        )
    }

    /**
     * The canonical body persisted (sealed) in `suggestion_cards.payload_ciphertext`.
     *
     * Canonical means the UI reads ONE shape regardless of which optional fields the sender used.
     * [canonicalMessageId] is the device's resolved identity for the anchor, so the stored body is
     * self-describing when read back (the row's `for_message_id` remains the queryable copy).
     */
    fun payloadJson(request: Request, canonicalMessageId: String): String {
        val array = JSONArray()
        request.suggestions.forEach { suggestion ->
            array.put(
                JSONObject()
                    .put(FIELD_ID, suggestion.id)
                    .put(FIELD_TEXT, suggestion.text)
                    .put(FIELD_KIND, suggestion.kind)
                    .put(FIELD_BADGE, suggestion.badge)
                    .apply { suggestion.segments?.let { put(FIELD_SEGMENTS, it) } }
            )
        }
        return JSONObject()
            .put(FIELD_SCHEMA_VERSION, request.schemaVersion)
            .put(FIELD_FOR_MESSAGE_REF, canonicalMessageId)
            .put(FIELD_CONVERSATION_GENERATION, request.conversationGeneration)
            .put(FIELD_EXPIRES_AT, request.expiresAtMs)
            .put(FIELD_SUGGESTIONS, array)
            .apply { request.reasonRef?.let { put(FIELD_REASON_REF, it) } }
            .toString()
    }

    /**
     * Read the canonical body back.
     *
     * Returns an empty list when the body cannot be parsed: a corrupt card is hidden, never rendered
     * from partial data and never allowed to crash the conversation screen.
     */
    fun decodePayload(payload: String): DecodedPayload? {
        val json = try {
            JSONObject(payload)
        } catch (e: Exception) {
            return null
        }
        val array = json.optJSONArray(FIELD_SUGGESTIONS) ?: return null
        val suggestions = ArrayList<Suggestion>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: return null
            val id = item.optString(FIELD_ID)
            val text = item.optString(FIELD_TEXT)
            if (id.isBlank() || text.isBlank()) return null
            suggestions.add(
                Suggestion(
                    id = id,
                    text = text,
                    kind = item.optString(FIELD_KIND),
                    badge = item.optString(FIELD_BADGE),
                    segments = if (item.has(FIELD_SEGMENTS) && !item.isNull(FIELD_SEGMENTS)) {
                        item.optInt(FIELD_SEGMENTS, 0).takeIf { it > 0 }
                    } else {
                        null
                    }
                )
            )
        }
        if (suggestions.isEmpty()) return null
        return DecodedPayload(
            suggestions = suggestions,
            forMessageRef = json.optString(FIELD_FOR_MESSAGE_REF),
            generation = json.optInt(FIELD_CONVERSATION_GENERATION, 0),
            expiresAtMs = json.optLong(FIELD_EXPIRES_AT, 0L),
            reasonRef = json.optString(FIELD_REASON_REF).takeIf { it.isNotBlank() }
        )
    }

    /** The persisted body, as read back by the UI. */
    data class DecodedPayload(
        val suggestions: List<Suggestion>,
        val forMessageRef: String,
        val generation: Int,
        val expiresAtMs: Long,
        val reasonRef: String?
    )

    /**
     * The contract writes `expires_at` as ISO-8601 (`2026-11-13T10:10:00Z`); a numeric value is
     * accepted as epoch millis so an older/newer sender is not rejected over representation alone.
     * Anything else is refused — an unparseable expiry must never become "never expires".
     */
    private fun parseExpiry(raw: Any?): Long? = when (raw) {
        null, JSONObject.NULL -> null
        is Number -> raw.toLong().takeIf { it > 0L }
        is String -> {
            val text = raw.trim()
            if (text.isEmpty()) {
                null
            } else {
                text.toLongOrNull()?.takeIf { it > 0L }
                    ?: runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
                    ?: runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
            }
        }
        else -> null
    }
}

/**
 * Why a `SUGGESTIONS_READY` command did not become a card, as a typed cause.
 *
 * Separate from [ReadCommandFailure] and [ConversationSimFailure] because none of a suggestion's
 * refusals are a send, a read or a preference: reporting "stale generation" as `SMS_SEND_FAILED`
 * would tell GMweb a message failed when nothing was ever sent, and would hide the one fact that
 * matters — the suggestion arrived too late to be shown.
 */
sealed class SuggestionCommandFailure(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    /** The decrypted payload is not the contract this build understands. */
    class InvalidPayload(val code: SyncErrorCode, val detail: String, cause: Throwable? = null) :
        SuggestionCommandFailure("suggestion command payload is not valid ($detail)", cause)

    /** No conversation on this device is addressable by the command's `thread_ref`. */
    class ThreadNotFound(val threadRef: String) :
        SuggestionCommandFailure("no conversation for thread_ref")

    /** The thread is a LOCAL_ONLY conversation: GOD AI never saw it, so no card may be stored. */
    class LocalOnlyThread(val threadId: Long) :
        SuggestionCommandFailure("thread $threadId is LOCAL_ONLY")

    /** The command was produced for an older view of the conversation than this device has. */
    class StaleGeneration(val commandGeneration: Int, val currentGeneration: Int) :
        SuggestionCommandFailure(
            "command generation $commandGeneration is older than $currentGeneration"
        )

    /**
     * The anchor message exists but a NEWER inbound message has arrived since.
     *
     * This is the authoritative staleness rule from the contract ("reject if a newer message has
     * arrived in the thread"). [StaleGeneration] is the cheap early filter; this one is the fact.
     */
    class SupersededMessage(val forMessageRef: String) :
        SuggestionCommandFailure("a newer inbound message has superseded for_message_ref")

    /** The suggestion expired before it arrived (or while it was being validated). */
    class Expired(val expiresAtMs: Long) :
        SuggestionCommandFailure("suggestions expired at $expiresAtMs")

    /** The anchor message named by `for_message_ref` is not on this device. */
    class UnknownMessage(val forMessageRef: String) :
        SuggestionCommandFailure("no inbound message matches for_message_ref")

    /** The card could not be sealed/durably written, so the command must not be ACKed as stored. */
    class PersistFailed(cause: Throwable? = null) :
        SuggestionCommandFailure("suggestion cards could not be persisted", cause)
}

/** Maps a suggestion-command failure to the stable code GMweb and the local ledger see. */
object SuggestionCommandError {

    const val COMMAND_TYPE = SuggestionsReadyCommand.TYPE

    fun classify(error: Throwable): SyncErrorCode = when (error) {
        is SuggestionCommandFailure.InvalidPayload -> error.code
        is SuggestionCommandFailure.ThreadNotFound -> SyncErrorCode.THREAD_NOT_FOUND
        is SuggestionCommandFailure.LocalOnlyThread -> SyncErrorCode.SUGGESTIONS_LOCAL_ONLY_THREAD
        is SuggestionCommandFailure.StaleGeneration -> SyncErrorCode.SUGGESTIONS_STALE
        is SuggestionCommandFailure.SupersededMessage -> SyncErrorCode.SUGGESTIONS_STALE
        is SuggestionCommandFailure.Expired -> SyncErrorCode.SUGGESTIONS_EXPIRED
        is SuggestionCommandFailure.UnknownMessage -> SyncErrorCode.SUGGESTIONS_UNKNOWN_MESSAGE
        is SuggestionCommandFailure.PersistFailed -> SyncErrorCode.SUGGESTIONS_PERSIST_FAILED
        is JSONException -> SyncErrorCode.SUGGESTIONS_INVALID_PAYLOAD
        else -> SyncErrorCode.SUGGESTIONS_PERSIST_FAILED
    }

    /** True when this command type is the suggestion command. */
    fun handles(commandType: String): Boolean = commandType == COMMAND_TYPE
}
