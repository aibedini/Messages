package com.autonomousone.messages.data

import com.autonomousone.messages.sms.SmsStatusEvidence
import com.autonomousone.messages.sms.SmsStatusPresentationMapper
import com.autonomousone.messages.sms.SmsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision the Home safety net makes, plus the computation that clears the marker once a
 * delivered message is re-projected.
 *
 * A full Room sweep cannot run in this JVM unit suite (no Android SQLite/Room runtime), so the
 * sweep's wiring is kept deliberately tiny; these tests pin the boundary and the mapper result that
 * makes a re-projection clear a stale `SEND_STATUS_UNKNOWN`.
 */
class StaleProjectionPolicyTest {

    private val now = 10_000_000L
    private val staleAfter = 120_000L

    @Test
    fun `every attention state is swept at the threshold`() {
        for (state in StaleProjectionPolicy.ATTENTION_STATES) {
            assertTrue(
                "$state must be swept once its newest message is stale",
                StaleProjectionPolicy.isStale(
                    uiState = state,
                    newestDate = now - staleAfter,
                    now = now,
                    staleAfterMs = staleAfter
                )
            )
        }
        assertEquals(
            setOf(
                "SENDING", "SEND_STATUS_UNKNOWN", "NOT_SENT", "NOT_DELIVERED", "DELIVERY_UNKNOWN"
            ),
            StaleProjectionPolicy.ATTENTION_STATES
        )
    }

    @Test
    fun `an attention state younger than two minutes is left alone`() {
        for (state in StaleProjectionPolicy.ATTENTION_STATES) {
            assertFalse(
                "$state is still in flight and must not be rebuilt early",
                StaleProjectionPolicy.isStale(
                    uiState = state,
                    newestDate = now - staleAfter + 1,
                    now = now,
                    staleAfterMs = staleAfter
                )
            )
        }
    }

    @Test
    fun `the quiet states are never swept`() {
        for (state in listOf(null, "", "SENT", "DELIVERED", "not_a_state")) {
            assertFalse(
                "quiet/unknown state $state must not be treated as attention",
                StaleProjectionPolicy.isStale(
                    uiState = state,
                    newestDate = now - staleAfter * 10,
                    now = now,
                    staleAfterMs = staleAfter
                )
            )
        }
    }

    @Test
    fun `a non-positive message date is not aged`() {
        assertFalse(
            StaleProjectionPolicy.isStale(
                uiState = "SEND_STATUS_UNKNOWN",
                newestDate = 0L,
                now = now,
                staleAfterMs = staleAfter
            )
        )
    }

    @Test
    fun `delivered evidence has no conversation attention summary`() {
        val presentation = SmsStatusPresentationMapper.present(
            SmsStatusEvidence(
                isOutgoing = true,
                hasSentConfirmation = true,
                hasDeliveryConfirmation = true
            )
        )

        assertEquals(SmsUiState.DELIVERED, presentation.state)
        assertNull(
            "a re-projection of delivered evidence must clear the list marker",
            SmsStatusPresentationMapper.conversationSummary(presentation, isOutgoing = true)
        )
    }
}
