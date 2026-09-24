package com.autonomousone.messages

import com.autonomousone.messages.data.GatewayEventFactory
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mission §49: the web's optimistic-bubble key must survive the whole round trip.
 *
 * It survived the *creation* half already — `MESSAGE_CREATED` has carried `originCommandId` and
 * `clientMessageId` from the start. It did not survive the *status* half: `MESSAGE_STATUS_CHANGED`
 * carried neither, so when a web-requested send later turned DELIVERED or FAILED, GMweb could not
 * tie the update to the bubble it had already drawn. `docs/gateway-replication-audit.md` recorded
 * that as "§49 is therefore half-implemented" and this closes it.
 *
 * The subtlety these tests exist for is that closing it must not touch event IDENTITY (mission §33).
 * A status event's id is derived from the (source, provider row, date, state) tuple; if it started
 * depending on *which* send caused the change, one logical transition would acquire two ids and the
 * outbox's unique index could no longer dedupe it.
 */
class ClientMessageIdPropagationTest {

    private fun payload(row: com.autonomousone.messages.data.GatewayEventOutboxEntity) =
        JSONObject(GatewayEventFactory.decodePayloadEnvelope(row.ciphertext))

    private fun status(
        originCommandId: String? = null,
        clientMessageId: String? = null,
    ) = GatewayEventFactory.messageStatusChanged(
        source = "sms",
        providerId = 42,
        conversationId = "conversation",
        status = 0,
        dateMs = 1_700_000_000_000L,
        direction = "out",
        body = "hello",
        address = "09120000000",
        read = false,
        originCommandId = originCommandId,
        clientMessageId = clientMessageId,
    )

    @Test
    fun `aStatusChangeCarriesTheSameWebCorrelationAsTheCreationDid`() {
        val payload = payload(status(originCommandId = "command-1", clientMessageId = "bubble-7"))

        assertEquals("command-1", payload.getString("originCommandId"))
        assertEquals("bubble-7", payload.getString("clientMessageId"))
    }

    @Test
    fun `aStatusChangeWithNoCorrelationEmitsNoEmptyKeys`() {
        // A transition the phone observed on its own (an incoming message being read) has no web
        // bubble to point at. Emitting `"clientMessageId": ""` would be a key that looks present and
        // matches nothing, so absence must be emitted as absence.
        val payload = payload(status())

        assertFalse(payload.has("originCommandId"))
        assertFalse(payload.has("clientMessageId"))
        assertFalse(payload.has("clientMessageId") && payload.getString("clientMessageId").isEmpty())
    }

    @Test
    fun `aBlankCorrelationIsTreatedAsAbsent`() {
        val payload = payload(status(originCommandId = "", clientMessageId = ""))

        assertFalse(payload.has("originCommandId"))
        assertFalse(payload.has("clientMessageId"))
    }

    @Test
    fun `theStatusEventIdentityIsUnchangedByTheCorrelation`() {
        // mission §33, pinned. This is the regression a careless fix produces: threading the
        // correlation into the identity derivation would make one transition produce two ids.
        val uncorrelated = status()
        val correlated = status(originCommandId = "command-1", clientMessageId = "bubble-7")

        assertEquals(uncorrelated.eventUuid, correlated.eventUuid)
        assertEquals(uncorrelated.eventType, correlated.eventType)
        assertEquals(uncorrelated.aggregateId, correlated.aggregateId)
        assertEquals(uncorrelated.messageId, correlated.messageId)
    }

    @Test
    fun `theStatusEventStaysAStatusUpdateNoMatterWhatCausedIt`() {
        // `source` is accounting, not causality. Switching a command-caused status change to
        // COMMAND_RESULT would move the row between accounting buckets for a reason that has nothing
        // to do with what the event IS; the causal link belongs in the payload.
        assertEquals(
            com.autonomousone.messages.data.GatewayEventOutboxEntity.SOURCE_STATUS_UPDATE,
            status(originCommandId = "command-1").source
        )
    }

    @Test
    fun `theStatusPayloadStillCarriesEverythingItDidBefore`() {
        // Guards the additive change: the pre-existing fields must be untouched, because GMweb's
        // current contract reads them.
        val payload = payload(status(originCommandId = "command-1"))

        assertEquals(0, payload.getInt("status"))
        assertEquals(1_700_000_000_000L, payload.getLong("dateMs"))
        assertEquals("out", payload.getString("direction"))
        assertEquals("hello", payload.getString("body"))
        assertEquals("09120000000", payload.getString("address"))
        assertFalse(payload.getBoolean("read"))
        assertTrue(payload.has("messageId"))
    }

    @Test
    fun `bothHalvesOfTheRoundTripNowCarryTheSameTwoKeys`() {
        // The property the mission actually asks for, stated as one assertion rather than two
        // separate field checks: whatever the creation carries, the status carries too.
        val created = payload(
            GatewayEventFactory.messageCreated(
                source = "sms",
                providerId = 42,
                conversationId = "conversation",
                direction = "out",
                body = "hello",
                dateMs = 1_700_000_000_000L,
                status = 0,
                originCommandId = "command-1",
                clientMessageId = "bubble-7",
            )
        )
        val changed = payload(status(originCommandId = "command-1", clientMessageId = "bubble-7"))

        for (key in listOf("originCommandId", "clientMessageId")) {
            assertTrue("the creation must carry $key", created.has(key))
            assertTrue("the status change must carry $key", changed.has(key))
            assertEquals(created.getString(key), changed.getString(key))
        }
    }

    @Test
    fun `aDeletionDeliberatelyCarriesNoCorrelation`() {
        // Documented absence, asserted so it stays a decision rather than becoming an oversight.
        //
        // `MessageMutation.Delete` carries neither key, so there is no producer for one on this
        // event. This round gave `messageDeleted` the two parameters, could not populate them from
        // any call site, and removed them again — adding a field that is always null is exactly the
        // defect being fixed one function over. A correlation belongs here only if a remotely
        // requested delete ever exists.
        val deleted = payload(
            GatewayEventFactory.messageDeleted(
                source = "sms", providerId = 42, conversationId = "conversation", dateMs = 7L
            )
        )

        assertFalse(deleted.has("originCommandId"))
        assertFalse(deleted.has("clientMessageId"))
        // The identity is still the stable one, so a re-notified deletion dedupes.
        assertEquals(
            GatewayEventFactory.messageDeleted(
                source = "sms", providerId = 42, conversationId = "conversation", dateMs = 7L
            ).eventUuid,
            GatewayEventFactory.messageDeleted(
                source = "sms", providerId = 42, conversationId = "other", dateMs = 7L
            ).eventUuid
        )
    }
}
