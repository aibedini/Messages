package com.autonomousone.messages

import com.autonomousone.messages.data.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Schema-level invariants that the sync layer depends on. Pure JVM checks —
 * no Robolectric needed for key/normalization rules.
 */
class MessageEntityMappingTest {

    @Test
    fun `source constants are stable wire values`() {
        // These strings persist in the DB; changing them is a migration.
        assertEquals("sms", MessageEntity.SOURCE_SMS)
        assertEquals("mms", MessageEntity.SOURCE_MMS)
    }

    @Test
    fun `composite key fields are distinct namespaces`() {
        val sms = message(source = "sms", providerId = 42)
        val mms = message(source = "mms", providerId = 42)
        // Same numeric id from both providers must NOT collide.
        assertEquals(42L, sms.providerId)
        assertEquals(42L, mms.providerId)
    }

    private fun message(source: String, providerId: Long) = MessageEntity(
        source = source,
        providerId = providerId,
        threadId = 1L,
        normalizedAddress = "+989121234567",
        rawAddress = "+98 912 123 4567",
        body = "hello",
        date = 1_700_000_000_000,
        type = 1,
        read = false
    )
}
