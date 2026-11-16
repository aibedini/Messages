package com.autonomousone.messages.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `SUGGESTION_FEEDBACK` wire body (XR-GA-017).
 *
 * The shape is small on purpose: ids the device already knows, one action, and — only when the user
 * actually rewrote the draft — the edited text. A test per action pins the "only when applicable"
 * rule, because shipping an `editedText` for a plain selection would send message content the user
 * never typed.
 */
class SuggestionFeedbackTest {

    private fun feedback(
        action: SuggestionFeedback.Action,
        editedText: String? = null,
        generation: Int = 12
    ) = SuggestionFeedback(
        threadId = 42L,
        suggestionId = "sg_1",
        action = action,
        forMessageRef = "msg-anchor",
        editedText = editedText,
        conversationGeneration = generation,
        atMs = 1_800_000_000_000L
    )

    private fun json(feedback: SuggestionFeedback) = JSONObject(feedback.payloadJson())

    @Test
    fun `a selection carries the suggestion id and the action`() {
        val body = json(feedback(SuggestionFeedback.Action.SELECTED))

        assertEquals("sg_1", body.getString("suggestionId"))
        assertEquals("selected", body.getString("action"))
        assertEquals("msg-anchor", body.getString("forMessageRef"))
        assertEquals(12, body.getInt("conversationGeneration"))
        assertEquals(1_800_000_000_000L, body.getLong("atMs"))
    }

    @Test
    fun `an edited draft carries the text the user actually sent`() {
        val body = json(
            feedback(SuggestionFeedback.Action.EDITED, editedText = "باشه، تا ساعت ۵ می‌رسم")
        )

        assertEquals("edited", body.getString("action"))
        assertEquals("باشه، تا ساعت ۵ می‌رسم", body.getString("editedText"))
    }

    @Test
    fun `a selection never carries edited text`() {
        assertFalse(json(feedback(SuggestionFeedback.Action.SELECTED)).has("editedText"))
    }

    @Test
    fun `a rejection and a report carry no text at all`() {
        assertFalse(json(feedback(SuggestionFeedback.Action.REJECTED)).has("editedText"))
        assertFalse(json(feedback(SuggestionFeedback.Action.REPORTED)).has("editedText"))
    }

    @Test
    fun `a zero generation is omitted rather than sent as zero`() {
        val body = json(feedback(SuggestionFeedback.Action.SELECTED, generation = 0))

        assertFalse(body.has("conversationGeneration"))
    }

    @Test
    fun `the wire vocabulary is the contract's, not the enum name`() {
        assertEquals("selected", SuggestionFeedback.Action.SELECTED.wire)
        assertEquals("edited", SuggestionFeedback.Action.EDITED.wire)
        assertEquals("rejected", SuggestionFeedback.Action.REJECTED.wire)
        assertEquals("reported", SuggestionFeedback.Action.REPORTED.wire)
        assertTrue(SuggestionFeedback.Action.entries.all { it.wire == it.wire.lowercase() })
    }
}
