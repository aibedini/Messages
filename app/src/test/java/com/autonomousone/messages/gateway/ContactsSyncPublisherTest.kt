package com.autonomousone.messages.gateway

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactsSyncPublisherTest {
    @Test fun `snapshot chunks contain at most one hundred contacts`() {
        val chunks = ContactsSyncPublisher.chunks((1..205).toList())
        assertEquals(listOf(100, 100, 5), chunks.map { it.size })
    }
}
