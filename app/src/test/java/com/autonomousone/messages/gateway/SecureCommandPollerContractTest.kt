package com.autonomousone.messages.gateway

import com.autonomousone.messages.data.RemoteCommandEntity
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Test

class SecureCommandPollerContractTest {
    @Test
    fun `claim body uses real device identity`() {
        val body = JSONObject(String(SecureCommandPoller.buildClaimBody("real-device-id")))

        assertEquals("real-device-id", body.getString("agentId"))
        assertEquals(25, body.getInt("limit"))
    }

    @Test
    fun `ciphertext field decodes exact bytes`() {
        val expected = "command-body".toByteArray()
        val json = JSONObject().put(
            "ciphertext",
            java.util.Base64.getEncoder().encodeToString(expected)
        )

        assertArrayEquals(expected, SecureCommandPoller.decodeCiphertext(json))
    }

    @Test
    fun `legacy payload field is rejected`() {
        val json = JSONObject().put("payload", "Y29tbWFuZA==")

        assertNull(SecureCommandPoller.decodeCiphertext(json))
    }

    @Test
    fun `missing or invalid ciphertext fails closed`() {
        assertNull(SecureCommandPoller.decodeCiphertext(JSONObject()))
        assertNull(SecureCommandPoller.decodeCiphertext(JSONObject().put("ciphertext", "%%%")))
    }

    @Test
    fun `intake default is strategic command ownership`() {
        org.junit.Assert.assertTrue(GatewayPreferences.DEFAULT_CONTROL_PLANE_SENDS)
    }

    // ── §49: the web's optimistic-bubble key is captured at intake ───────────
    //
    // The key was declared on the entity and read back by the executor, but never POPULATED here,
    // so on the strategic path it was always null. These pin the mapping.

    private fun envelope(vararg extra: Pair<String, Any>): JSONObject {
        val json = JSONObject()
            .put("id", "cmd-1")
            .put("idempotencyKey", "idem-1")
            .put("type", "SEND_SMS")
            .put("cryptoVersion", 1)
            .put("createdAt", 5_000L)
            .put("expiresAt", 9_000L)
            .put("ciphertext", java.util.Base64.getEncoder().encodeToString("body".toByteArray()))
        extra.forEach { (k, v) -> json.put(k, v) }
        return json
    }

    private fun fromEnvelope(json: JSONObject) = SecureCommandPoller.commandFromEnvelope(
        command = json,
        // The caller decodes the ciphertext before mapping, so the mapping takes bytes. Passing
        // bytes here keeps these tests about the FIELD MAPPING rather than about base64.
        ciphertext = "body".toByteArray(),
        now = 1_000L,
        decodeSignature = { "signed".toByteArray() },
    )

    @Test
    fun `aClaimedCommandKeepsTheWebOptimisticBubbleKey`() {
        val cmd = fromEnvelope(envelope("clientMessageId" to "bubble-7"))!!

        assertEquals("bubble-7", cmd.clientMessageId)
    }

    @Test
    fun `aBlankBubbleKeyIsStoredAsAbsentNotAsAnEmptyString`() {
        // An empty string is not an identity, and once persisted it is indistinguishable from a real
        // key that happens to be empty — so absence has to be represented as absence.
        assertNull(fromEnvelope(envelope("clientMessageId" to ""))!!.clientMessageId)
        assertNull(fromEnvelope(envelope())!!.clientMessageId)
    }

    @Test
    fun `theRestOfTheCommandIsStillMappedExactlyAsBefore`() {
        val cmd = fromEnvelope(envelope("clientMessageId" to "bubble-7"))!!

        assertEquals("cmd-1", cmd.commandId)
        assertEquals("SEND_SMS", cmd.type)
        assertEquals("idem-1", cmd.idempotencyKey)
        assertEquals(1, cmd.cryptoVersion)
        assertEquals(5_000L, cmd.issuedAt)
        assertEquals(9_000L, cmd.expiresAt)
        assertEquals(1_000L, cmd.receivedAt)
        assertEquals(RemoteCommandEntity.STATE_RECEIVED, cmd.state)
        assertArrayEquals("body".toByteArray(), cmd.ciphertext)
    }

    @Test
    fun `theSignatureIsDecodedThroughTheSuppliedDecoder`() {
        // If the injected decoder were ignored, a JVM test would still pass while the Android call
        // silently ran instead — so the injection point itself has to be asserted.
        val cmd = SecureCommandPoller.commandFromEnvelope(
            command = envelope("clientSignature" to "sig"),
            ciphertext = "body".toByteArray(),
            now = 1_000L,
            decodeSignature = { encoded -> "decoded:$encoded".toByteArray() },
        )!!

        assertArrayEquals("decoded:sig".toByteArray(), cmd.signature)
    }

    @Test
    fun `aCommandThatCannotBeIdentifiedIsNotIngested`() {
        // An unidentifiable command must never be ingested under a made-up identity: the
        // idempotencyKey is the exactly-once guarantee, and inventing one fabricates a guarantee.
        assertNull(fromEnvelope(JSONObject().put("id", "").put("idempotencyKey", "idem-1")))
        assertNull(fromEnvelope(JSONObject().put("id", "cmd-1").put("idempotencyKey", "")))
        assertNull(fromEnvelope(JSONObject()))
    }
}
