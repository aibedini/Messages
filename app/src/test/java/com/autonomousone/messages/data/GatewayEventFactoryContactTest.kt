package com.autonomousone.messages.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Issue 2 — contactName travels inside the (opaque, encrypted) envelope payload. */
class GatewayEventFactoryContactTest {

    @Test
    fun `contactName is embedded in the MESSAGE_CREATED payload when present`() {
        val row = GatewayEventFactory.messageCreated(
            source = "sms",
            providerId = 1L,
            conversationId = "conversation-1",
            direction = "in",
            body = "Salam",
            dateMs = 1000L,
            status = 1,
            address = "+989121234567",
            contactName = "Ali Rezaei"
        )
        val payload = JSONObject(GatewayEventFactory.decodePayloadEnvelope(row.ciphertext))
        assertEquals("Ali Rezaei", payload.getString("contactName"))
        assertEquals("in", payload.getString("direction"))
        assertEquals("+989121234567", payload.getString("address"))
    }

    @Test
    fun `contactName is omitted when there is no saved contact`() {
        val row = GatewayEventFactory.messageCreated(
            source = "sms",
            providerId = 2L,
            conversationId = "conversation-1",
            direction = "out",
            body = "Hello",
            dateMs = 2000L,
            status = 2,
            address = "+989190000000",
            contactName = null
        )
        val payload = JSONObject(GatewayEventFactory.decodePayloadEnvelope(row.ciphertext))
        assertFalse(payload.has("contactName"))
    }
}
