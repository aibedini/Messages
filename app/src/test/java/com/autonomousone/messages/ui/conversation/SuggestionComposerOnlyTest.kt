package com.autonomousone.messages.ui.conversation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * XR-GA-017's hardest rule, asserted structurally: **tapping a suggestion fills the composer and can
 * never send.**
 *
 * ## Why a source guard rather than a Compose test
 *
 * The rule is about ABSENCE: no SmsSender, no `sendMessage`, no `GatewayOutgoingPipeline`, no transport
 * gate anywhere in the suggestion path. A Compose test can prove that one tap did not send ONCE, on one
 * device, with the send path mocked; it cannot prove the next edit will not add a send call. This guard
 * states the invariant directly over the files that own suggestions and fails the build the moment a
 * sender appears in them. The same technique already protects the SMS transport and delivery code in
 * this repository ([com.autonomousone.messages.sms.SmsPhysicalSendOwnershipTest]).
 *
 * The composer's own send control lives in `ConversationScreen` and is untouched — it is allowed to
 * send, and is deliberately NOT in this list.
 */
class SuggestionComposerOnlyTest {

    private companion object {
        /**
         * Every production file that owns suggestion delivery, rendering, or feedback.
         *
         * None of these may reference a send primitive: the cards may only describe text.
         */
        val SUGGESTION_SOURCES = listOf(
            "app/src/main/java/com/autonomousone/messages/ui/conversation/SuggestionCards.kt",
            "app/src/main/java/com/autonomousone/messages/repository/SuggestionCardsRepository.kt",
            "app/src/main/java/com/autonomousone/messages/repository/SuggestionFeedbackPublisher.kt",
            "app/src/main/java/com/autonomousone/messages/sync/SuggestionsReadyCommand.kt",
            "app/src/main/java/com/autonomousone/messages/sync/SuggestionDeliveryPolicy.kt"
        )

        /** The message list renders the slot, so it must stay a display surface too. */
        val SLOT_RENDERING_SOURCES = listOf(
            "app/src/main/java/com/autonomousone/messages/ui/conversation/MessageList.kt"
        )

        val FORBIDDEN = listOf(
            "SmsSender",
            "sendMessage(",
            "sendSms(",
            "GatewayOutgoingPipeline",
            "SmsTransportGate",
            "SendSimPolicy",
            "commitSend",
            "enqueueSend"
        )
    }

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("../$relative"))
        val file = candidates.firstOrNull { it.isFile }
            ?: error("cannot locate $relative; looked in $candidates")
        return file.readText()
    }

    @Test
    fun `no suggestion source can reach a send primitive`() {
        (SUGGESTION_SOURCES + SLOT_RENDERING_SOURCES).forEach { relative ->
            val text = source(relative)
            FORBIDDEN.forEach { symbol ->
                assertFalse(
                    "$relative must not reference $symbol — a suggestion may fill the composer, never send",
                    text.contains(symbol)
                )
            }
        }
    }

    @Test
    fun `the suggestion files exist where the guard expects them`() {
        // A renamed/moved file would otherwise make the scan silently vacuous.
        (SUGGESTION_SOURCES + SLOT_RENDERING_SOURCES).forEach { relative ->
            assertTrue(
                "$relative is missing, so the composer-only guard would not cover it",
                source(relative).isNotBlank()
            )
        }
    }

    @Test
    fun `the card row exposes a selection callback and nothing that sends`() {
        val text = source("app/src/main/java/com/autonomousone/messages/ui/conversation/SuggestionCards.kt")

        assertTrue("the row must expose onSelect", text.contains("onSelect"))
        assertFalse(
            "a card's click handler may not be routed to a sender",
            text.contains("onClick = { onSend") || text.contains("onSend =")
        )
    }
}
