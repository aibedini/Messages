package com.autonomousone.messages.gateway

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceTelemetryTest {
    @Test fun batteryPercentageIsBoundedAndHandlesMissingData() {
        assertEquals(50, DeviceTelemetry.batteryPercent(1, 2))
        assertEquals(100, DeviceTelemetry.batteryPercent(500, 100))
        assertEquals(-1, DeviceTelemetry.batteryPercent(-1, -1))
    }
}
