package com.autonomousone.messages

import android.provider.Telephony
import com.autonomousone.messages.data.GatewayEventFactory
import com.autonomousone.messages.data.cloudMessageDirection
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageEventDirectionTest {
    @Test
    fun `only inbox is incoming and drafts never enter cloud history`() {
        assertEquals("in", cloudMessageDirection(Telephony.Sms.MESSAGE_TYPE_INBOX))
        listOf(
            Telephony.Sms.MESSAGE_TYPE_SENT,
            Telephony.Sms.MESSAGE_TYPE_OUTBOX,
            Telephony.Sms.MESSAGE_TYPE_FAILED,
            Telephony.Sms.MESSAGE_TYPE_QUEUED,
        ).forEach { assertEquals("out", cloudMessageDirection(it)) }
        assertNull(cloudMessageDirection(Telephony.Sms.MESSAGE_TYPE_DRAFT))
        assertNull(cloudMessageDirection(99))
    }

    @Test
    fun `outgoing event carries exact web command correlation`() {
        val row = GatewayEventFactory.messageCreated(
            source = "sms",
            providerId = 42,
            conversationId = "conversation",
            direction = "out",
            body = "hello",
            dateMs = 123,
            status = Telephony.Sms.STATUS_PENDING,
            originCommandId = "command-1",
            clientMessageId = "client-1",
        )
        val payload = JSONObject(GatewayEventFactory.decodePayloadEnvelope(row.ciphertext))
        assertEquals("out", payload.getString("direction"))
        assertEquals("command-1", payload.getString("originCommandId"))
        assertEquals("client-1", payload.getString("clientMessageId"))
    }
}
