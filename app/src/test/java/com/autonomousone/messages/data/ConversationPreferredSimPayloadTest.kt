package com.autonomousone.messages.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sticky SIM travels INSIDE the encrypted conversation payload, and nowhere else.
 *
 * ## The two defects this pins
 *
 * 1. **A leak.** `preferredSim` names which line a person uses for a conversation. If it appeared in
 *    the outer envelope it would be readable by anything that can see the transport but not the
 *    ciphertext — the exact boundary the encrypted-payload design exists to hold.
 * 2. **A collapse.** The three states (`ABSENT` / `null` / value) are not interchangeable. An ordinary
 *    message-driven upsert must not be able to CLEAR a preference by simply not mentioning it, and an
 *    explicit clear must not be indistinguishable from "no news".
 *
 * Both are silent on the wire, so both are asserted here rather than left to the executor's behaviour.
 */
class ConversationPreferredSimPayloadTest {

    private fun upserted(preferredSim: GatewayEventFactory.PreferredSimPayload) =
        GatewayEventFactory.conversationUpserted(
            conversationId = "conv-1",
            displayName = "Alice",
            address = "+989120000000",
            lastMessagePreview = "hello",
            lastMessageDirection = "outbound",
            lastMessageAt = 1_700_000_000_000,
            unreadCount = 0,
            pinned = false,
            archived = false,
            revision = 42L,
            androidThreadId = 123L,
            preferredSim = preferredSim
        )

    private fun decrypted(event: GatewayEventOutboxEntity): JSONObject =
        JSONObject(GatewayEventFactory.decodePayloadEnvelope(event.ciphertext))

    private fun envelope(event: GatewayEventOutboxEntity): String =
        String(event.ciphertext, Charsets.UTF_8)

    // ── the three states ─────────────────────────────────────────────────────

    @Test
    fun `ABSENT omits the field entirely`() {
        val payload = decrypted(upserted(GatewayEventFactory.PreferredSimPayload.Absent))

        assertFalse(
            "a message-driven upsert must not be able to clear a preference by omission",
            payload.has("preferredSim")
        )
    }

    @Test
    fun `NULL is an explicit, present clear`() {
        val payload = decrypted(upserted(GatewayEventFactory.PreferredSimPayload.Clear))

        assertTrue("an explicit clear must be distinguishable from no news", payload.has("preferredSim"))
        assertTrue("the clear must be a real JSON null", payload.isNull("preferredSim"))
    }

    @Test
    fun `a SET carries the reference and its display snapshots`() {
        val payload = decrypted(
            upserted(
                GatewayEventFactory.PreferredSimPayload.Set(
                    simRef = "sim:v1:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    displayName = "SIM 1",
                    carrierName = "MCI",
                    slotIndex = 0
                )
            )
        )

        val preferred = payload.getJSONObject("preferredSim")
        assertEquals("sim:v1:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", preferred.getString("simRef"))
        assertEquals("SIM 1", preferred.getString("displayName"))
        assertEquals("MCI", preferred.getString("carrierName"))
        assertEquals(0, preferred.getInt("slotIndex"))
    }

    @Test
    fun `ABSENT, NULL and VALUE are three distinguishable payloads`() {
        val absent = decrypted(upserted(GatewayEventFactory.PreferredSimPayload.Absent))
        val cleared = decrypted(upserted(GatewayEventFactory.PreferredSimPayload.Clear))
        val set = decrypted(
            upserted(GatewayEventFactory.PreferredSimPayload.Set("sim:v1:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
        )

        assertFalse(absent.has("preferredSim"))
        assertTrue(cleared.has("preferredSim") && cleared.isNull("preferredSim"))
        assertTrue(set.has("preferredSim") && !set.isNull("preferredSim"))
        // The three are pairwise different, which is what makes a replica able to act correctly.
        assertFalse(absent.toString() == cleared.toString())
        assertFalse(cleared.toString() == set.toString())
    }

    @Test
    fun `a set with no snapshots still emits the reference`() {
        // The snapshot fields are UI-only; a reference with no display data is still a complete,
        // actionable preference and must not be dropped.
        val payload = decrypted(
            upserted(GatewayEventFactory.PreferredSimPayload.Set("sim:v1:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
        )

        val preferred = payload.getJSONObject("preferredSim")
        assertEquals("sim:v1:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", preferred.getString("simRef"))
        assertTrue(preferred.isNull("displayName"))
        assertTrue(preferred.isNull("carrierName"))
        assertTrue(preferred.isNull("slotIndex"))
    }

    // ── privacy: the outer envelope ──────────────────────────────────────────

    @Test
    fun `the outer envelope never contains the preference or any SIM identifier`() {
        val event = upserted(
            GatewayEventFactory.PreferredSimPayload.Set(
                simRef = "sim:v1:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                displayName = "SIM 1",
                carrierName = "MCI",
                slotIndex = 0
            )
        )
        val outer = envelope(event)

        for (forbidden in listOf(
            "preferredSim", "simRef", "carrierName", "slotIndex", "subscriptionId"
        )) {
            assertFalse(
                "$forbidden must exist ONLY inside the encrypted payload",
                outer.contains(forbidden)
            )
        }
        // And the value itself must not appear in the clear either.
        assertFalse(outer.contains("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
        assertFalse(outer.contains("MCI"))
    }

    @Test
    fun `the outer envelope carries only transport metadata`() {
        val event = upserted(
            GatewayEventFactory.PreferredSimPayload.Set("sim:v1:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        )
        val outer = JSONObject(envelope(event))

        assertEquals(
            setOf("ciphertextB64", "encoding", "schemaVersion", "cryptoVersion"),
            outer.keys().asSequence().toSet()
        )
        assertEquals("application/json", outer.getString("encoding"))
    }

    @Test
    fun `the clear state also stays inside the ciphertext`() {
        val outer = envelope(upserted(GatewayEventFactory.PreferredSimPayload.Clear))

        assertFalse(outer.contains("preferredSim"))
    }

    @Test
    fun `the decrypted payload still carries the ordinary conversation fields`() {
        // Adding the preference must not disturb the existing contract.
        val payload = decrypted(
            upserted(
                GatewayEventFactory.PreferredSimPayload.Set("sim:v1:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
            )
        )

        assertEquals("conv-1", payload.getString("conversationId"))
        assertEquals("Alice", payload.getString("displayName"))
        assertEquals("hello", payload.getString("lastMessagePreview"))
        assertEquals("outbound", payload.getString("lastMessageDirection"))
        assertEquals(123L, payload.getLong("androidThreadId"))
        assertEquals(0, payload.getInt("unreadCount"))
        assertEquals(false, payload.getBoolean("pinned"))
        assertEquals(false, payload.getBoolean("archived"))
    }

    @Test
    fun `an event with no preference keeps the legacy payload shape`() {
        // Every pre-existing caller passes nothing, and its wire bytes must not change: a client that
        // has never heard of preferredSim must see exactly what it saw before.
        val legacy = decrypted(upserted(GatewayEventFactory.PreferredSimPayload.Absent))

        assertEquals(
            setOf(
                "conversationId", "displayName", "address", "lastMessagePreview",
                "lastMessageDirection", "lastMessageAt", "unreadCount", "pinned", "archived",
                "androidThreadId"
            ),
            legacy.keys().asSequence().toSet()
        )
    }
}
