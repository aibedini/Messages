package com.autonomousone.messages.gateway

import com.autonomousone.messages.data.TrustStatementOutboxEntity
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustPublicationPolicyTest {
    private fun row(sequence: Int, state: String) = TrustStatementOutboxEntity(
        statementId = "statement-$sequence", trustSequence = sequence,
        operation = TrustStatementOutboxEntity.OP_DEVICE_APPROVED, deviceId = "browser",
        payload = "{}", rootSignature = "signed", state = state, attemptCount = 0,
        createdAt = 1L, ackedAt = null,
    )

    @Test fun `HTTP 200 alone never proves a trust statement was applied`() {
        assertFalse(TrustPublicationPolicy.durableReceipt(JSONObject("""{"ok":true,"applied":false,"reason":"sequence_gap"}"""), 44))
        assertFalse(TrustPublicationPolicy.durableReceipt(JSONObject("""{"ok":true,"applied":true,"trustSequence":43}"""), 44))
        assertTrue(TrustPublicationPolicy.durableReceipt(JSONObject("""{"ok":true,"applied":true,"trustSequence":44}"""), 44))
        assertTrue(TrustPublicationPolicy.durableReceipt(JSONObject("""{"ok":true,"applied":false,"reason":"duplicate","trustSequence":44}"""), 44))
    }

    @Test fun `replay preserves the full signed sequence and stops at a local gap`() {
        assertTrue(TrustPublicationPolicy.hasContiguousReplay(43, listOf(
            row(44, TrustStatementOutboxEntity.STATE_PUBLISHED),
            row(45, TrustStatementOutboxEntity.STATE_PENDING),
        )))
        assertFalse(TrustPublicationPolicy.hasContiguousReplay(43, listOf(
            row(44, TrustStatementOutboxEntity.STATE_PUBLISHED),
            row(46, TrustStatementOutboxEntity.STATE_PUBLISHED),
        )))
        assertFalse(TrustPublicationPolicy.hasContiguousReplay(43, listOf(
            row(44, TrustStatementOutboxEntity.STATE_WAITING_SERVER_APPROVAL),
        )))
    }

    @Test fun `recovery identifies only absent sequence numbers and is bounded`() {
        val published = row(45, TrustStatementOutboxEntity.STATE_PUBLISHED)
        val waiting = row(47, TrustStatementOutboxEntity.STATE_WAITING_SERVER_APPROVAL)
        assertEquals(listOf(44, 46), TrustPublicationPolicy.missingSequences(43, listOf(published, waiting)))
        assertEquals(emptyList<Int>(), TrustPublicationPolicy.missingSequences(43,
            listOf(row(44, TrustStatementOutboxEntity.STATE_PENDING))))
        assertNull(TrustPublicationPolicy.missingSequences(43, listOf(row(77, TrustStatementOutboxEntity.STATE_PENDING))))
        assertNull(TrustPublicationPolicy.missingSequences(43, listOf(published, published)))
    }

    @Test fun `waiting approval requires matching server evidence or a live pairing session`() {
        val confirmed = setOf(54 to "browser")
        val pending = setOf("session-45")
        assertEquals(TrustPublicationPolicy.WaitingApprovalAction.ACTIVATE,
            TrustPublicationPolicy.waitingApprovalAction(54, "browser", "old-session", confirmed, pending))
        assertEquals(TrustPublicationPolicy.WaitingApprovalAction.WAIT,
            TrustPublicationPolicy.waitingApprovalAction(45, "browser", "session-45", confirmed, pending))
        assertEquals(TrustPublicationPolicy.WaitingApprovalAction.VOID,
            TrustPublicationPolicy.waitingApprovalAction(44, "browser", "expired", confirmed, pending))
        assertEquals(TrustPublicationPolicy.WaitingApprovalAction.VOID,
            TrustPublicationPolicy.waitingApprovalAction(54, "another-browser", null, confirmed, pending))
    }
}
