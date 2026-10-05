package com.autonomousone.messages.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The encrypted logical payload contract: sender identity and thread correlation.
 *
 * Proven production bug: events were built from `normalizedAddress`, which is EMPTY for every branded
 * sender, so GMweb received `address: ""` and could only show "Unknown number / Unknown conversation"
 * for short codes, brands and alphanumeric ids. These tests pin the payload the producers now emit:
 *
 * ```text
 * address         = the RAW provider sender, byte for byte
 * androidThreadId = the exact Telephony thread id, INSIDE the ciphertext
 * ```
 *
 * They test the PLAINTEXT builders (the outbox row is encrypted at construction), which is exactly the
 * contract GMweb sees after decryption.
 *
 * NOTE: written and executed as part of this change (pure, no Android).
 */
class GatewayEventContractTest {

    @Test
    fun `MESSAGE_CREATED carries the raw branded sender byte for byte`() {
        val payload = GatewayEventFactory.messageCreatedPayload(
            source = "sms", providerId = 348201L, direction = "in", body = "x",
            dateMs = 1_791_180_000_000L, status = 0, address = "PARSIANBANK",
            contactName = null, read = false, originCommandId = null, clientMessageId = null,
            androidThreadId = 552L
        )

        assertEquals("PARSIANBANK", payload.getString("address"))
        assertEquals(552L, payload.getLong("androidThreadId"))
        assertEquals("sms", payload.getString("source"))
        assertEquals(348201L, payload.getLong("providerId"))
        assertFalse(payload.getBoolean("read"))
    }

    @Test
    fun `every branded and numeric sender spelling survives unchanged`() {
        for (raw in listOf("PARSIANBANK", "ResalatBank", "Google", "Ssh3-652", "3000", "09121234567", "+989121234567", "۰۹۱۲۱۲۳۴۵۶۷")) {
            val payload = GatewayEventFactory.messageCreatedPayload(
                source = "sms", providerId = 1L, direction = "in", body = "b", dateMs = 1L,
                status = 0, address = raw, contactName = null, read = false,
                originCommandId = null, clientMessageId = null, androidThreadId = 7L
            )
            assertEquals(raw, payload.getString("address"))
        }
    }

    @Test
    fun `an empty address stays empty only when the provider truly gave nothing`() {
        val payload = GatewayEventFactory.messageCreatedPayload(
            source = "sms", providerId = 1L, direction = "in", body = "b", dateMs = 1L,
            status = 0, address = "", contactName = null, read = false,
            originCommandId = null, clientMessageId = null, androidThreadId = 7L
        )

        assertEquals("", payload.getString("address"))
    }

    @Test
    fun `MESSAGE_STATUS_CHANGED keeps the raw address and the thread id`() {
        val payload = GatewayEventFactory.messageStatusChangedPayload(
            source = "sms", providerId = 3L, status = 0, dateMs = 6L, direction = "in",
            body = null, address = "Ssh3-652", contactName = null, read = true,
            originCommandId = "cmd-1", clientMessageId = "cm-1", androidThreadId = 11L
        )

        assertEquals("Ssh3-652", payload.getString("address"))
        assertEquals(11L, payload.getLong("androidThreadId"))
        assertEquals("sms", payload.getString("source"))
        assertEquals(3L, payload.getLong("providerId"))
    }

    @Test
    fun `CONVERSATION_UPSERTED carries the raw address and the exact thread id`() {
        val payload = GatewayEventFactory.conversationUpsertedPayload(
            conversationId = "conv-1", displayName = null, address = "PARSIANBANK",
            lastMessagePreview = "preview", lastMessageDirection = "in", lastMessageAt = 10L,
            unreadCount = 2, pinned = false, archived = false, androidThreadId = 552L
        )

        assertEquals("PARSIANBANK", payload.getString("address"))
        assertEquals(552L, payload.getLong("androidThreadId"))
        // displayName falls back to the address, never to an empty string.
        assertEquals("PARSIANBANK", payload.getString("displayName"))
    }

    @Test
    fun `an unknown thread id is omitted, never sent as zero`() {
        val payload = GatewayEventFactory.messageCreatedPayload(
            source = "sms", providerId = 4L, direction = "in", body = "b", dateMs = 7L,
            status = 0, address = "Google", contactName = null, read = false,
            originCommandId = null, clientMessageId = null, androidThreadId = 0L
        )

        assertFalse("a zero thread id is not a mapping", payload.has("androidThreadId"))
        // An absent thread id never costs the sender identity.
        assertEquals("Google", payload.getString("address"))
    }

    @Test
    fun `a contact name decorates the raw address and never replaces it`() {
        val payload = GatewayEventFactory.messageCreatedPayload(
            source = "sms", providerId = 5L, direction = "in", body = "b", dateMs = 9L,
            status = 0, address = "09121234567", contactName = "Ali",
            read = false, originCommandId = null, clientMessageId = null, androidThreadId = 12L
        )

        assertEquals("09121234567", payload.getString("address"))
        assertEquals("Ali", payload.getString("contactName"))
    }

    @Test
    fun `client and command correlation still travel with the message`() {
        val payload = GatewayEventFactory.messageCreatedPayload(
            source = "sms", providerId = 6L, direction = "out", body = "b", dateMs = 10L,
            status = 0, address = "+989121234567", contactName = null, read = true,
            originCommandId = "cmd-9", clientMessageId = "cm-9", androidThreadId = 13L
        )

        assertEquals("cmd-9", payload.getString("originCommandId"))
        assertEquals("cm-9", payload.getString("clientMessageId"))
    }

    @Test
    fun `the outer envelope is opaque and carries no PII or thread id`() {
        val entity = GatewayEventFactory.messageCreated(
            source = "sms", providerId = 7L, conversationId = "conv-1", direction = "in",
            body = "b", dateMs = 11L, status = 0, address = "PARSIANBANK", androidThreadId = 552L
        )

        val outer = listOf(entity.eventUuid, entity.eventType, entity.aggregateId, entity.messageId)
            .joinToString("|")
        assertFalse("the sender must not appear outside the ciphertext", outer.contains("PARSIANBANK"))
        assertFalse("the thread id must not appear outside the ciphertext", outer.contains("552"))
        assertTrue("the payload is sealed", entity.ciphertext.isNotEmpty())
    }
}
