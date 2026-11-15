package com.autonomousone.messages.sync

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `SUGGESTIONS_READY` wire contract (XR-GA-017).
 *
 * Every case here is a payload GMweb could actually send. The rule behind all of them is the same one
 * the read and preference commands already follow: **an omission is never a default**. A missing
 * schema version, a missing anchor, a missing expiry or an empty suggestion list used to be the kind of
 * field a parser happily fills in — and each of those defaults would produce a card that claims
 * something the sender never said.
 */
class SuggestionsReadyCommandTest {

    private fun suggestion(
        id: String = "sg_1",
        text: String = "باشه، الان می‌بینم",
        kind: String = "approved_template",
        badge: String = "knowledge",
        segments: Int? = 1
    ): JSONObject = JSONObject()
        .put("id", id)
        .put("text", text)
        .put("kind", kind)
        .put("badge", badge)
        .apply { segments?.let { put("segments", it) } }

    private fun payload(
        schemaVersion: Any? = 1,
        type: Any? = "SUGGESTIONS_READY",
        threadRef: Any? = "112233",
        forMessageRef: Any? = "sms:42",
        generation: Any? = 112,
        expiresAt: Any? = "2030-01-01T00:00:00Z",
        suggestions: Any? = JSONArray().put(suggestion())
    ): String {
        val json = JSONObject()
        if (type != null) json.put("type", type)
        if (schemaVersion != null) json.put("schema_version", schemaVersion)
        if (threadRef != null) json.put("thread_ref", threadRef)
        if (forMessageRef != null) json.put("for_message_ref", forMessageRef)
        if (generation != null) json.put("conversation_generation", generation)
        if (expiresAt != null) json.put("expires_at", expiresAt)
        if (suggestions != null) json.put("suggestions", suggestions)
        return json.toString()
    }

    private fun ok(parsed: SuggestionsReadyCommand.Parsed): SuggestionsReadyCommand.Request {
        assertTrue("expected a valid command, got $parsed", parsed is SuggestionsReadyCommand.Parsed.Ok)
        return (parsed as SuggestionsReadyCommand.Parsed.Ok).request
    }

    private fun reject(parsed: SuggestionsReadyCommand.Parsed): SuggestionsReadyCommand.Parsed.Invalid {
        assertTrue("expected a rejection, got $parsed", parsed is SuggestionsReadyCommand.Parsed.Invalid)
        return parsed as SuggestionsReadyCommand.Parsed.Invalid
    }

    // ── the valid contract ───────────────────────────────────────────────────

    @Test
    fun `a well formed payload is parsed field for field`() {
        val request = ok(SuggestionsReadyCommand.parse(payload()))

        assertEquals(1, request.schemaVersion)
        assertEquals("112233", request.threadRef)
        assertEquals("sms:42", request.forMessageRef)
        assertEquals(112, request.conversationGeneration)
        assertEquals(1_893_456_000_000L, request.expiresAtMs)
        assertEquals(1, request.suggestions.size)
        assertEquals("sg_1", request.suggestions[0].id)
        assertEquals("باشه، الان می‌بینم", request.suggestions[0].text)
        assertEquals("approved_template", request.suggestions[0].kind)
        assertEquals("knowledge", request.suggestions[0].badge)
        assertEquals(1, request.suggestions[0].segments)
        assertNull("reason_ref was absent and must stay absent", request.reasonRef)
    }

    @Test
    fun `a numeric expiry is accepted as epoch millis`() {
        val request = ok(SuggestionsReadyCommand.parse(payload(expiresAt = 1_893_456_000_000L)))

        assertEquals(1_893_456_000_000L, request.expiresAtMs)
    }

    @Test
    fun `three suggestions are accepted and their order is preserved`() {
        val array = JSONArray()
            .put(suggestion(id = "sg_1", text = "اول"))
            .put(suggestion(id = "sg_2", text = "دوم"))
            .put(suggestion(id = "sg_3", text = "سوم"))

        val request = ok(SuggestionsReadyCommand.parse(payload(suggestions = array)))

        assertEquals(listOf("sg_1", "sg_2", "sg_3"), request.suggestions.map { it.id })
    }

    @Test
    fun `an unknown badge is carried verbatim and mapped conservatively later`() {
        val request = ok(
            SuggestionsReadyCommand.parse(
                payload(suggestions = JSONArray().put(suggestion(badge = "brand_new_badge")))
            )
        )

        assertEquals("brand_new_badge", request.suggestions[0].badge)
    }

    // ── malformed payloads ───────────────────────────────────────────────────

    @Test
    fun `a payload that is not JSON is refused, not guessed`() {
        val invalid = reject(SuggestionsReadyCommand.parse("not json at all"))

        assertEquals(SyncErrorCode.SUGGESTIONS_INVALID_PAYLOAD, invalid.code)
    }

    @Test
    fun `an absent schema version is refused rather than assumed to be 1`() {
        val invalid = reject(SuggestionsReadyCommand.parse(payload(schemaVersion = null)))

        assertEquals(SyncErrorCode.SUGGESTIONS_INVALID_PAYLOAD, invalid.code)
        assertEquals("schema_missing", invalid.detail)
    }

    @Test
    fun `a future schema version is refused`() {
        val invalid = reject(SuggestionsReadyCommand.parse(payload(schemaVersion = 2)))

        assertEquals(SyncErrorCode.SUGGESTIONS_INVALID_PAYLOAD, invalid.code)
        assertEquals("schema_unsupported", invalid.detail)
    }

    @Test
    fun `a plaintext type that disagrees with the envelope is refused`() {
        val invalid = reject(SuggestionsReadyCommand.parse(payload(type = "SEND_SMS")))

        assertEquals("type_mismatch", invalid.detail)
    }

    @Test
    fun `a blank thread reference is refused`() {
        val invalid = reject(SuggestionsReadyCommand.parse(payload(threadRef = "   ")))

        assertEquals("thread_ref_missing", invalid.detail)
    }

    @Test
    fun `a blank anchor reference is refused`() {
        val invalid = reject(SuggestionsReadyCommand.parse(payload(forMessageRef = "")))

        assertEquals("for_message_ref_missing", invalid.detail)
    }

    @Test
    fun `generation zero is not a generation`() {
        val invalid = reject(SuggestionsReadyCommand.parse(payload(generation = 0)))

        assertEquals("generation_invalid", invalid.detail)
    }

    @Test
    fun `a missing expiry is refused because a card may not live forever`() {
        val invalid = reject(SuggestionsReadyCommand.parse(payload(expiresAt = null)))

        assertEquals("expires_at_invalid", invalid.detail)
    }

    @Test
    fun `an unparseable expiry is refused`() {
        val invalid = reject(SuggestionsReadyCommand.parse(payload(expiresAt = "next tuesday")))

        assertEquals("expires_at_invalid", invalid.detail)
    }

    @Test
    fun `an empty suggestion list is refused`() {
        val invalid = reject(SuggestionsReadyCommand.parse(payload(suggestions = JSONArray())))

        assertEquals("suggestions_empty", invalid.detail)
    }

    @Test
    fun `an unbounded suggestion list is refused instead of truncated`() {
        val array = JSONArray()
        for (i in 1..SuggestionsReadyCommand.MAX_SUGGESTIONS + 1) {
            array.put(suggestion(id = "sg_$i"))
        }

        val invalid = reject(SuggestionsReadyCommand.parse(payload(suggestions = array)))

        assertEquals("suggestions_unbounded", invalid.detail)
    }

    @Test
    fun `a suggestion without text is refused`() {
        val invalid = reject(
            SuggestionsReadyCommand.parse(
                payload(suggestions = JSONArray().put(JSONObject().put("id", "sg_1")))
            )
        )

        assertEquals("suggestion_text_missing", invalid.detail)
    }

    @Test
    fun `a suggestion without an id is refused because feedback could not name it`() {
        val invalid = reject(
            SuggestionsReadyCommand.parse(
                payload(suggestions = JSONArray().put(JSONObject().put("text", "متن")))
            )
        )

        assertEquals("suggestion_id_missing", invalid.detail)
    }

    @Test
    fun `duplicate suggestion ids are refused`() {
        val array = JSONArray()
            .put(suggestion(id = "sg_1"))
            .put(suggestion(id = "sg_1", text = "متن دیگر"))

        val invalid = reject(SuggestionsReadyCommand.parse(payload(suggestions = array)))

        assertEquals("suggestion_id_duplicate", invalid.detail)
    }

    @Test
    fun `an over-long suggestion text is refused`() {
        val invalid = reject(
            SuggestionsReadyCommand.parse(
                payload(
                    suggestions = JSONArray().put(
                        suggestion(text = "x".repeat(SuggestionsReadyCommand.MAX_TEXT_CHARS + 1))
                    )
                )
            )
        )

        assertEquals("suggestion_text_too_long", invalid.detail)
    }

    @Test
    fun `a suggestion that is not an object is refused`() {
        val invalid = reject(
            SuggestionsReadyCommand.parse(payload(suggestions = JSONArray().put("bare string")))
        )

        assertEquals("suggestion_not_object", invalid.detail)
    }

    // ── the persisted canonical body ─────────────────────────────────────────

    @Test
    fun `the canonical body round-trips through decode with the resolved anchor`() {
        val request = ok(SuggestionsReadyCommand.parse(payload()))

        val decoded = SuggestionsReadyCommand.decodePayload(
            SuggestionsReadyCommand.payloadJson(request, canonicalMessageId = "msg-uuid")
        )

        requireNotNull(decoded)
        assertEquals("msg-uuid", decoded.forMessageRef)
        assertEquals(112, decoded.generation)
        assertEquals(1_893_456_000_000L, decoded.expiresAtMs)
        assertEquals(1, decoded.suggestions.size)
        assertEquals("باشه، الان می‌بینم", decoded.suggestions[0].text)
    }

    @Test
    fun `a corrupt canonical body decodes to null instead of partial cards`() {
        assertNull(SuggestionsReadyCommand.decodePayload("{not json"))
        assertNull(SuggestionsReadyCommand.decodePayload("{}"))
    }
}
