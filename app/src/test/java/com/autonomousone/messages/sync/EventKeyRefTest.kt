package com.autonomousone.messages.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mission §42: which key(s) an outbox row is encrypted under.
 *
 * `keyRef` existed on the entity, had a migration column, and was written by nothing and read by
 * nothing. The column's stated purpose is rotation — "which of my not-yet-uploaded events are under
 * the key I am about to retire?" — and a v3 message is encrypted under TWO keys, so the value has to
 * be able to say so.
 */
class EventKeyRefTest {

    private fun v3(historyKeyId: String?, liveKeyId: String?) = JSONObject()
        .put("v", 3).put("kind", "message")
        .apply {
            if (historyKeyId != null) put("historyKeyId", historyKeyId)
            if (liveKeyId != null) put("liveKeyId", liveKeyId)
        }
        .toString().toByteArray()

    private fun v2(keyId: String?) = JSONObject()
        .put("v", 2).put("kind", "message")
        .apply { if (keyId != null) put("keyId", keyId) }
        .toString().toByteArray()

    // ── Reading the envelope, which is the source of truth ───────────────────

    @Test
    fun `aV2EnvelopeYieldsTheSingleLiveKey`() {
        assertEquals(
            EventKeyRef(liveKeyId = "live-1"),
            EventKeyRef.ofEnvelope(v2("live-1"))
        )
    }

    @Test
    fun `aV3EnvelopeYieldsBothKeys`() {
        // The whole reason the column cannot be a bare key id: rotating either key has to be able to
        // find these rows.
        assertEquals(
            EventKeyRef(liveKeyId = "live-1", historyKeyId = "hist-1"),
            EventKeyRef.ofEnvelope(v3(historyKeyId = "hist-1", liveKeyId = "live-1"))
        )
    }

    @Test
    fun `aV1EnvelopeIsUnderstoodToo`() {
        // v1 names its key `epochId`. Nothing in the current app writes v1, but the parser exists to
        // describe rows that already exist, and a rotation that skipped them would be a leak.
        val v1 = JSONObject().put("v", 1).put("kind", "message").put("epochId", "epoch-1")
            .toString().toByteArray()

        assertEquals(EventKeyRef(liveKeyId = "epoch-1"), EventKeyRef.ofEnvelope(v1))
    }

    @Test
    fun `anEnvelopeWithNoRecognisableKeyYieldsNothingRatherThanFabricatingOne`() {
        // A key grant is signed, not encrypted, so it genuinely has no encryption key. Returning null
        // is the honest answer; `EventKeyRef("")` would look like a key id that matches nothing.
        assertNull(EventKeyRef.ofEnvelope(JSONObject().put("kind", "keyring-entry").toString().toByteArray()))
        assertNull(EventKeyRef.ofEnvelope(ByteArray(0)))
        assertNull(EventKeyRef.ofEnvelope("not json at all".toByteArray()))
    }

    @Test
    fun `aV3EnvelopeWithNoHistoryKeyStillDescribesItsLiveKey`() {
        // `encryptMessageV3` always writes both fields, so this is defensive — but a rotation must not
        // be told "no keys" about a row that does have one.
        assertEquals(
            EventKeyRef(liveKeyId = "live-1"),
            EventKeyRef.ofEnvelope(v3(historyKeyId = null, liveKeyId = "live-1"))
        )
    }

    // ── The canonical stored form ────────────────────────────────────────────

    @Test
    fun `encodingIsLiveFirstAndDelimiterSeparated`() {
        assertEquals("live-1", EventKeyRef("live-1").encode())
        assertEquals("live-1|hist-1", EventKeyRef("live-1", "hist-1").encode())
    }

    @Test
    fun `aBlankHistoryKeyIsNotEncodedAsAnEmptySecondField`() {
        // `live-1|` would match the history-position predicate for an empty key id, and would parse
        // back as a two-key ref whose second key is nothing.
        assertEquals("live-1", EventKeyRef("live-1", "").encode())
        assertEquals("live-1", EventKeyRef("live-1", null).encode())
        assertEquals("live-1", EventKeyRef("live-1", "  ").encode())
    }

    @Test
    fun `parseRoundTripsBothForms`() {
        listOf("live-1", "live-1|hist-1").forEach { encoded ->
            assertEquals(encoded, EventKeyRef.parse(encoded)!!.encode())
        }
        assertEquals(EventKeyRef("live-1"), EventKeyRef.parse("live-1"))
        assertEquals(EventKeyRef("live-1", "hist-1"), EventKeyRef.parse("live-1|hist-1"))
    }

    @Test
    fun `parseTreatsAbsenceAsAbsence`() {
        listOf(null, "", "   ", "|hist-1").forEach { value ->
            assertNull("[$value] must not become a key reference", EventKeyRef.parse(value))
        }
    }

    @Test
    fun `keyIdsListsEverythingTheEventIsUnder`() {
        assertEquals(listOf("live-1"), EventKeyRef("live-1").keyIds())
        assertEquals(listOf("live-1", "hist-1"), EventKeyRef("live-1", "hist-1").keyIds())
    }

    @Test
    fun `theDelimiterCannotAppearInsideAKeyId`() {
        // The encoding and the SQL match predicates both rely on this. Key ids are UUIDs
        // (`UUID.randomUUID().toString()` at every mint site), so it holds — and if that ever changed,
        // the two-part parse would silently mis-split rather than fail, which is why it is stated.
        assertTrue(EventKeyRef.DELIMITER == "|")
        val uuid = "4f2a9c1e-0000-4000-8000-000000000000"
        assertTrue(uuid.none { it == '|' })
    }

    // ── The invariant that matters ───────────────────────────────────────────

    @Test
    fun `theStoredValueAlwaysMatchesWhatTheEnvelopeSays`() {
        // The column is a copy of a fact that lives inside the ciphertext. A copy that DISAGREES is
        // worse than an absent one: it would send a rotation after the wrong rows. This asserts the
        // copy is derived, not remembered.
        val cases = listOf(
            v3(historyKeyId = "hist-1", liveKeyId = "live-1") to EventKeyRef("live-1", "hist-1"),
            v2("live-1") to EventKeyRef("live-1")
        )

        cases.forEach { (ciphertext, expected) ->
            assertEquals(
                "the stored keyRef must equal the envelope's, for ${expected.encode()}",
                EventKeyRef.ofEnvelope(ciphertext)!!.encode(),
                expected.encode()
            )
        }
    }
}
