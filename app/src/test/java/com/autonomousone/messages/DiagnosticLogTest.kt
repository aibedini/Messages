package com.autonomousone.messages

import com.autonomousone.messages.utils.DiagnosticLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DiagnosticLogTest {

    @Test
    fun `phone token is stable and does not contain the original number`() {
        val phone = "+989121234567"
        val first = DiagnosticLog.phoneToken(phone)
        val second = DiagnosticLog.phoneToken(phone)

        assertEquals(first, second)
        assertEquals(10, first.length)
        assertFalse(first.contains("9121234567"))
    }
}
