package com.autonomousone.messages.gateway

import com.autonomousone.messages.gateway.UploadGate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue 2 regression: the outbox worker must expose WHY it is holding still,
 * with stable diagnostic tags for logcat/UI.
 */
class EventUploaderGateTest {

    @Test
    fun `disabled gateway is the first reported gate`() {
        assertEquals(UploadGate.GATEWAY_DISABLED, UploadGate.reason(enabled = false, urlBlank = true, registered = false))
        assertEquals("gateway_disabled", UploadGate.GATEWAY_DISABLED.tag)
    }

    @Test
    fun `blank gmweb url is reported when the gateway is enabled`() {
        assertEquals(UploadGate.URL_NOT_CONFIGURED, UploadGate.reason(enabled = true, urlBlank = true, registered = true))
        assertEquals("gmweb_url_not_configured", UploadGate.URL_NOT_CONFIGURED.tag)
    }

    @Test
    fun `unenrolled device is reported after url is configured`() {
        assertEquals(UploadGate.DEVICE_NOT_ENROLLED, UploadGate.reason(enabled = true, urlBlank = false, registered = false))
        assertEquals("device_not_enrolled", UploadGate.DEVICE_NOT_ENROLLED.tag)
    }

    @Test
    fun `all gates clear when ready`() {
        assertEquals(UploadGate.ENABLED, UploadGate.reason(enabled = true, urlBlank = false, registered = true))
        assertEquals("enabled", UploadGate.ENABLED.tag)
    }
}
