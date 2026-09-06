package com.autonomousone.messages.security

import com.autonomousone.messages.ui.screens.deviceCapabilityLabels
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

/**
 * DRIFT GUARD (P0 — prevent future contract drift).
 *
 * Android's runtime capability lists must EXACTLY match
 * `protocol/pairing-protocol-v1.json::capability_definitions` — the same file
 * GMweb's server certificate validation reads. This test fails the Android
 * build the moment one side changes without the other. When the protocol JSON
 * changes, update PairingCapabilityContract + LinkedDevicesScreen labels and
 * mirror the file into GMweb-API/shared/ in the SAME commit.
 */
class PairingCapabilityContractTest {

    private fun fixtureJson(): JSONObject {
        val raw = checkNotNull(javaClass.classLoader?.getResourceAsStream("pairing-protocol-v1.json"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        return JSONObject(raw)
    }

    private fun schemaDefinitions(): JSONObject =
        fixtureJson().getJSONObject("capability_definitions")

    private fun strings(list: org.json.JSONArray): List<String> =
        (0 until list.length()).map { list.getString(it) }

    @Test
    fun `Kotlin base capabilities match the protocol schema base set`() {
        assertEquals(
            strings(schemaDefinitions().getJSONArray("base")),
            PairingCapabilityContract.BASE_CAPABILITIES,
        )
    }

    @Test
    fun `Kotlin sensitive capabilities match the protocol schema and the grant store`() {
        val schemaSensitive = strings(schemaDefinitions().getJSONArray("sensitive"))
        assertEquals(schemaSensitive, PairingCapabilityContract.SENSITIVE_CAPABILITIES)
        // SensitiveGrantStore persists per-device grants by capability name;
        // it must stay index-aligned with the schema category order.
        assertEquals(PairingCapabilityContract.SENSITIVE_CAPABILITIES, SensitiveGrantStore.CATEGORIES)
    }

    @Test
    fun `Kotlin reserved capabilities match the protocol schema reserved set`() {
        assertEquals(
            strings(schemaDefinitions().getJSONArray("reserved")),
            PairingCapabilityContract.RESERVED_CAPABILITIES,
        )
    }

    @Test
    fun `capability groups are disjoint, non-empty and fully labeled in the UI`() {
        assertTrue(PairingCapabilityContract.BASE_CAPABILITIES.isNotEmpty())
        assertTrue(PairingCapabilityContract.SENSITIVE_CAPABILITIES.isNotEmpty())
        val all = PairingCapabilityContract.ALLOWLISTED_BY_ANDROID
        assertEquals(all.size, all.toSet().size)
        assertFalse(PairingCapabilityContract.BASE_CAPABILITIES.any { it in PairingCapabilityContract.SENSITIVE_CAPABILITIES })
        assertFalse(PairingCapabilityContract.SENSITIVE_CAPABILITIES.any { it in PairingCapabilityContract.RESERVED_CAPABILITIES })

        // The linked-device edit UI shows exactly the grantable universe, in
        // contract order, with a human label for every capability.
        val labels = deviceCapabilityLabels
        assertEquals(all, labels.keys.toList())
        assertTrue(labels.values.all { it.isNotBlank() })
    }

    @Test
    fun `shared android_linked_browser_default vector canonicalizes byte-for-byte`() {
        val fixture = fixtureJson()
        val vectors = fixture.getJSONArray("vectors")
        val vector = (0 until vectors.length())
            .map { vectors.getJSONObject(it) }
            .first { it.optString("label") == "android_linked_browser_default" }

        // Cross-runtime proof: the canonical UTF-8 netstring bytes and their
        // SHA-256 produced by Android's PairingProtocol MUST equal the values
        // Node computed from the same input (also asserted by GMweb CI).
        val canonical = PairingProtocol.certificate(vector.getJSONObject("input"))
        val bytes = canonical.toByteArray(Charsets.UTF_8)
        assertEquals(vector.getString("canonicalBase64"), Base64.getEncoder().encodeToString(bytes))
        assertEquals(
            vector.getString("sha256"),
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
        )

        // The vector input is exactly what the current UI builder produces:
        // all base capabilities + one enabled sensitive grant (READ_OTP).
        val capsArray = vector.getJSONObject("input").getJSONArray("capabilities")
        val actualCaps = (0 until capsArray.length()).map { capsArray.getString(it) }.sorted()
        val expectedCaps = (PairingCapabilityContract.BASE_CAPABILITIES + "READ_OTP").sorted()
        assertEquals(expectedCaps, actualCaps)
    }
}
