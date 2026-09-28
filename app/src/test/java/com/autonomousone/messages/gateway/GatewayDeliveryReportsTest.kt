package com.autonomousone.messages.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GatewayDeliveryReportsTest {
    @Test fun `report identity is stable across retries`() {
        assertEquals(36, GatewayDeliveryReports.eventId("pull_task_42", "delivered").length)
        assertEquals(
            GatewayDeliveryReports.eventId("pull_task_42", "delivered"),
            GatewayDeliveryReports.eventId("pull_task_42", "delivered")
        )
    }

    @Test fun `failure and delivery never share an event identity`() {
        assertNotEquals(
            GatewayDeliveryReports.eventId("pull_task_42", "failed"),
            GatewayDeliveryReports.eventId("pull_task_42", "delivered")
        )
    }
}
