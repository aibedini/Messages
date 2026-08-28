package com.autonomousone.messages

import com.autonomousone.messages.data.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Mapping between the Room read-SSOT rows and the UI row shape must be
 * lossless: the cutover path paints the conversation screen from
 * [MessageEntity.toSms] and any dropped field shows up as a UI regression.
 */
class MessageEntityTest {

    private fun entity(
        rawAddress: String = "+989121234567",
        normalizedAddress: String = "+989121234567"
    ) = MessageEntity(
        source = MessageEntity.SOURCE_SMS,
        providerId = 42L,
        threadId = 7L,
        normalizedAddress = normalizedAddress,
        rawAddress = rawAddress,
        body = "hello",
        date = 1_000L,
        type = 1,
        status = -1,
        dateSent = 0L,
        read = false
    )

    @Test
    fun `toSms maps provider id, thread and body`() {
        val sms = entity().toSms()

        assertEquals(42L, sms.id)
        assertEquals(7L, sms.threadId)
        assertEquals("hello", sms.message)
        assertEquals(1_000L, sms.date)
        assertEquals(1, sms.type)
    }

    @Test
    fun `toSms preserves unread flag inverted from read`() {
        assertEquals(true, entity().copy(read = false).toSms().unread)
        assertEquals(false, entity().copy(read = true).toSms().unread)
    }

    @Test
    fun `toSms falls back to normalized address when raw is blank`() {
        val sms = entity(rawAddress = "", normalizedAddress = "+15551234567").toSms()

        assertEquals("+15551234567", sms.sender)
    }

    @Test
    fun `toSms carries status and delivery timestamp for outgoing rows`() {
        val sent = entity().copy(type = 2, status = 0, dateSent = 5_000L).toSms()

        assertEquals(0, sent.status)
        assertEquals(5_000L, sent.dateSent)
    }

    @Test
    fun `toSms negates MMS ids to keep UI identity distinct from SMS`() {
        val mms = entity().copy(source = MessageEntity.SOURCE_MMS).toSms()

        // Provider reader convention: SMS positive, MMS negative. A 42 in each
        // table must render as two different UI ids.
        assertEquals(-42L, mms.id)
    }
}
