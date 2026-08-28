package com.autonomousone.messages

import com.autonomousone.messages.data.MessageEntity
import com.autonomousone.messages.data.MessageKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Message identity at scale. The provider's SMS and MMS `_id` sequences
 * overlap (SMS id 52 and MMS id 52 both exist), so the UI identity must keep
 * them apart — otherwise `distinctBy { it.id }` and Compose keys collide.
 */
class MessageKeyTest {

    private fun entity(source: String, providerId: Long) = MessageEntity(
        source = source,
        providerId = providerId,
        threadId = 7L,
        normalizedAddress = "+989121234567",
        rawAddress = "+989121234567",
        body = "hello",
        date = 1_000L,
        type = 1,
        read = false
    )

    @Test
    fun `composite keys keep overlapping provider ids distinct`() {
        val smsKey = MessageKey(MessageEntity.SOURCE_SMS, 52L)
        val mmsKey = MessageKey(MessageEntity.SOURCE_MMS, 52L)

        assertNotEquals("SMS 52 and MMS 52 must not collide", smsKey, mmsKey)
        assertEquals(smsKey, MessageKey(MessageEntity.SOURCE_SMS, 52L))
    }

    @Test
    fun `toSms negates MMS ids so UI identity never collides with SMS`() {
        val smsRow = entity(MessageEntity.SOURCE_SMS, 52L).toSms()
        val mmsRow = entity(MessageEntity.SOURCE_MMS, 52L).toSms()

        // Provider-reader convention: SMS positive, MMS negative.
        assertEquals(52L, smsRow.id)
        assertEquals(-52L, mmsRow.id)
        assertNotEquals("UI ids must stay distinct for SMS 52 / MMS 52", smsRow.id, mmsRow.id)

        // distinctBy over a mixed list keeps both rows.
        val deduped = listOf(smsRow, mmsRow).distinctBy { it.id }
        assertEquals(2, deduped.size)
    }

    @Test
    fun `provider ids survive a roundtrip through the entity key`() {
        for (source in listOf(MessageEntity.SOURCE_SMS, MessageEntity.SOURCE_MMS)) {
            for (id in listOf(1L, 52L, 481_729L)) {
                val roundTripped = entity(source, id).toSms().let { sms ->
                    entity(
                        source = source,
                        providerId = kotlin.math.abs(sms.id)
                    ).providerId
                }
                assertEquals(id, roundTripped)
            }
        }
    }

    @Test
    fun `100k mixed identities map without collision`() {
        // Scale smoke test: same provider id reused across both sources at 50k
        // each must produce 100k distinct entries.
        val map = HashMap<MessageKey, Int>()
        for (i in 1L..50_000L) {
            map[MessageKey(MessageEntity.SOURCE_SMS, i)] = 1
            map[MessageKey(MessageEntity.SOURCE_MMS, i)] = 1
        }
        assertEquals(100_000, map.size)
        assertTrue(map.containsKey(MessageKey(MessageEntity.SOURCE_MMS, 48_172)))
    }
}
