package com.autonomousone.messages.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class DeviceTelemetrySimTest {
    @Test fun `subscription labels do not relay a phone number`() {
        assertEquals("Irancell", DeviceTelemetry.safeSimLabel("Irancell"))
        val label = DeviceTelemetry.safeSimLabel("My SIM +989121234567\nprivate")
        assertFalse(label.contains("989121234567"))
        assertFalse(label.contains("\n"))
        assertFalse(DeviceTelemetry.safeSimLabel("SIM ۰۹۱۲۱۲۳۴۵۶۷").contains("۰۹۱۲۱۲۳۴۵۶۷"))
    }

    @Test fun `SIM label sanitizer does not depend on platform regex flags`() {
        val source = listOf(
            File("src/main/java/com/autonomousone/messages/gateway/DeviceTelemetry.kt"),
            File("app/src/main/java/com/autonomousone/messages/gateway/DeviceTelemetry.kt")
        ).firstOrNull { it.isFile }?.readText() ?: error("cannot locate DeviceTelemetry.kt")
        val sanitizer = source.substringAfter("internal fun safeSimLabel")
            .substringBefore("    private val appContext")

        assertFalse("Android ICU must never see Java-only inline (?U) flags", sanitizer.contains("(?U)"))
        assertFalse("SIM label sanitization must remain regex-free", sanitizer.contains("Regex("))
    }
}
