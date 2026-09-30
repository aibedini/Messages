package com.autonomousone.messages.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DeviceTelemetrySimTest {
    @Test fun `subscription labels do not relay a phone number`() {
        assertEquals("Irancell", DeviceTelemetry.safeSimLabel("Irancell"))
        val label = DeviceTelemetry.safeSimLabel("My SIM +989121234567\nprivate")
        assertFalse(label.contains("989121234567"))
        assertFalse(label.contains("\n"))
        assertFalse(DeviceTelemetry.safeSimLabel("SIM ۰۹۱۲۱۲۳۴۵۶۷").contains("۰۹۱۲۱۲۳۴۵۶۷"))
    }
}
