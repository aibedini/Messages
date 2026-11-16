package com.autonomousone.messages.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * XR-GA-008 / LDV-002+003 — AI linked-device capability builder and the
 * explicit approval-state rules. Plain JVM test (no Android, no Room).
 */
class AiDevicePairingTest {

    @Test
    fun `AI capability builder emits exactly READ_MESSAGES + AI_SUGGEST`() {
        val caps = PairingCapabilityContract.capabilitiesFor(
            PairingCapabilityContract.DEVICE_TYPE_AI_AGENT,
        )
        assertEquals(listOf("READ_MESSAGES", "AI_SUGGEST"), caps)
        assertFalse(caps.contains("SEND_MESSAGES"))
        assertFalse(caps.contains("MARK_READ"))
        assertFalse(caps.contains("MANAGE_DEVICES"))
        // Sensitive grants are structurally ignored for an AI device.
        assertEquals(
            caps,
            PairingCapabilityContract.capabilitiesFor(
                PairingCapabilityContract.DEVICE_TYPE_AI_AGENT,
                listOf("CONTACTS_READ", "READ_OTP", "SEND_MESSAGES"),
            ),
        )
    }

    @Test
    fun `AI policy rejects every forbidden capability and missing required ones`() {
        val ai = PairingCapabilityContract.DEVICE_TYPE_AI_AGENT
        val exact = listOf("READ_MESSAGES", "AI_SUGGEST")
        assertTrue(PairingCapabilityContract.capabilitiesAreValidFor(ai, exact))
        assertFalse(PairingCapabilityContract.capabilitiesAreValidFor(ai, exact + "SEND_MESSAGES"))
        assertFalse(PairingCapabilityContract.capabilitiesAreValidFor(ai, exact + "MARK_READ"))
        assertFalse(PairingCapabilityContract.capabilitiesAreValidFor(ai, exact + "MANAGE_DEVICES"))
        assertFalse(PairingCapabilityContract.capabilitiesAreValidFor(ai, exact + "READ_OTP"))
        assertFalse(PairingCapabilityContract.capabilitiesAreValidFor(ai, exact + "RECEIVE_NOTIFICATIONS"))
        assertFalse(PairingCapabilityContract.capabilitiesAreValidFor(ai, listOf("READ_MESSAGES")))
        assertFalse(PairingCapabilityContract.capabilitiesAreValidFor(ai, listOf("AI_SUGGEST")))
        assertFalse(PairingCapabilityContract.capabilitiesAreValidFor(ai, emptyList()))
        assertFalse(PairingCapabilityContract.capabilitiesAreValidFor("ROBOT", exact))
    }

    @Test
    fun `WEB_PWA capability builder and validation are unchanged`() {
        val base = PairingCapabilityContract.BASE_CAPABILITIES
        assertEquals(base, PairingCapabilityContract.capabilitiesFor(
            PairingCapabilityContract.DEVICE_TYPE_WEB_PWA,
        ))
        assertEquals(
            base + listOf("CONTACTS_READ"),
            PairingCapabilityContract.capabilitiesFor(
                PairingCapabilityContract.DEVICE_TYPE_WEB_PWA,
                listOf("CONTACTS_READ"),
            ),
        )
        assertFalse(PairingCapabilityContract.capabilitiesFor(
            PairingCapabilityContract.DEVICE_TYPE_WEB_PWA,
            listOf("AI_SUGGEST"),
        ).contains("AI_SUGGEST"))
        assertTrue(PairingCapabilityContract.capabilitiesAreValidFor(
            PairingCapabilityContract.DEVICE_TYPE_WEB_PWA, base,
        ))
        assertTrue(PairingCapabilityContract.capabilitiesAreValidFor(
            PairingCapabilityContract.DEVICE_TYPE_WEB_PWA, base + "READ_OTP",
        ))
        assertFalse(PairingCapabilityContract.capabilitiesAreValidFor(
            PairingCapabilityContract.DEVICE_TYPE_WEB_PWA, base + "AI_SUGGEST",
        ))
        assertFalse(PairingCapabilityContract.capabilitiesAreValidFor(
            PairingCapabilityContract.DEVICE_TYPE_WEB_PWA, listOf("READ_MESSAGES"),
        ))
    }

    @Test
    fun `AI history is always from now on`() {
        val ai = PairingCapabilityContract.DEVICE_TYPE_AI_AGENT
        val web = PairingCapabilityContract.DEVICE_TYPE_WEB_PWA
        assertEquals("FROM_NOW_ON", PairingCapabilityContract.historyGrantFor(ai, true))
        assertEquals("FROM_NOW_ON", PairingCapabilityContract.historyGrantFor(ai, false))
        assertEquals("FULL_HISTORY", PairingCapabilityContract.historyGrantFor(web, true))
        assertEquals("FROM_NOW_ON", PairingCapabilityContract.historyGrantFor(web, false))
    }

    @Test
    fun `AI approval requires an explicit acknowledgement and from-now-on history`() {
        // Nothing is approvable by default.
        assertFalse(AiDeviceApprovalState().isReadyToConfirm)
        // An acknowledgement alone is required for an AI device...
        assertTrue(AiDeviceApprovalState(acknowledged = true).isReadyToConfirm)
        // ...and a full-history request can never make it ready.
        assertFalse(
            AiDeviceApprovalState(
                acknowledged = true,
                historyGrant = PairingCapabilityContract.HISTORY_FULL,
            ).isReadyToConfirm,
        )
        assertEquals(
            listOf("READ_MESSAGES", "AI_SUGGEST"),
            AiDeviceApprovalState().requestedCapabilities,
        )
        assertEquals(
            listOf("SEND_MESSAGES"),
            AiDeviceApprovalState().neverGrantedCapabilities,
        )
        assertTrue(AiDeviceApprovalState.ACKNOWLEDGEMENT.contains("never send messages"))
        assertTrue(AiDeviceApprovalState.ACKNOWLEDGEMENT.contains("from now on"))
    }
}
