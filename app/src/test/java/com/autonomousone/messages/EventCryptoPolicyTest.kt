package com.autonomousone.messages

import com.autonomousone.messages.security.EventCryptoPolicy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class EventCryptoPolicyTest {
    @Test
    fun `Android crypto policy matches shared protocol fixture`() {
        val fixture = JSONObject(File("../protocol/event-crypto-policy-v1.json").readText())
        fun read(group: String): Map<String, Set<Int>> {
            val objectValue = fixture.getJSONObject(group)
            return objectValue.keys().asSequence().associateWith { type ->
                val versions = objectValue.getJSONArray(type)
                (0 until versions.length()).mapTo(mutableSetOf()) { versions.getInt(it) }
            }
        }
        assertEquals(EventCryptoPolicy.contentVersions, read("contentBearing"))
        assertEquals(EventCryptoPolicy.keyVersions, read("controlKey"))
        assertEquals(EventCryptoPolicy.nonContentVersions, read("nonContentControl"))
    }
}
