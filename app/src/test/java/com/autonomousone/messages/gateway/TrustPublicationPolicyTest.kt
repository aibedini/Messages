package com.autonomousone.messages.gateway

import com.autonomousone.messages.data.TrustStatementOutboxEntity
import org.json.JSONObject
import org.junit.Assert.assertFalse
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
}
