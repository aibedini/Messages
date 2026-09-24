package com.autonomousone.messages.sync

import org.json.JSONObject

/**
 * Which key(s) an outbox row's payload is encrypted under (mission §42).
 *
 * **Why the row needs this at all.** The encrypted envelope already names its key ids — `keyId` for
 * v2, `historyKeyId` + `liveKeyId` for v3 — so the server can always find the right key. The reason to
 * also record it on the ROW is rotation: "which of my not-yet-uploaded events are under the key I am
 * about to retire?" has to be answerable without decrypting or re-reading a single envelope, and a
 * rotation that cannot answer it either re-encrypts everything or misses rows.
 *
 * **Why a type and not a string.** A v3 message is encrypted under TWO keys, so a bare key id would
 * be a half-truth exactly where the answer matters most. And a rotation query has to match a key id
 * inside that value, which is where a naive `LIKE 'prefix%'` silently matches a *different* key whose
 * id merely starts the same way. Encoding is therefore canonical and delimiter-separated, and the
 * match predicates live in one reviewed place (the DAO) rather than at each call site.
 *
 * Canonical form — live key first, because it is the one that always exists:
 *
 * ```text
 * <liveKeyId>                    v2 (and v1/v3 rows with no history key)
 * <liveKeyId>|<historyKeyId>     v3 message events
 * ```
 *
 * Key ids are UUIDs, so they cannot contain the delimiter.
 */
data class EventKeyRef(
    /** The account/live key the payload is readable under today. */
    val liveKeyId: String,
    /**
     * The history master key, when the event was also wrapped for a FULL_HISTORY reader.
     *
     * Null for v2 rows, and null for anything encrypted after the history master stops being used —
     * which is precisely the transition a rotation has to be able to see.
     */
    val historyKeyId: String? = null,
) {

    /** The value stored in `gateway_event_outbox.keyRef`. */
    fun encode(): String =
        if (historyKeyId.isNullOrBlank()) liveKeyId else "$liveKeyId|$historyKeyId"

    /** Every key this event is under, for a rotation that must consider both. */
    fun keyIds(): List<String> =
        if (historyKeyId.isNullOrBlank()) listOf(liveKeyId) else listOf(liveKeyId, historyKeyId)

    companion object {

        /** The delimiter between ids. Not valid inside a key id (they are UUIDs). */
        const val DELIMITER = "|"

        /**
         * Parse the canonical form.
         *
         * Returns null for null/blank input rather than an empty ref: `null` is the honest
         * representation of "this row predates the column" or "this row is not encrypted under a
         * content key" (key grants are signed, not encrypted), and a fabricated `EventKeyRef("")`
         * would look like a key id that matches nothing.
         */
        fun parse(value: String?): EventKeyRef? {
            val trimmed = value?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            val parts = trimmed.split(DELIMITER)
            val live = parts.firstOrNull()?.trim().orEmpty()
            if (live.isEmpty()) return null
            val history = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
            return EventKeyRef(liveKeyId = live, historyKeyId = history)
        }

        /**
         * Derive the reference from an encrypted envelope.
         *
         * The envelope is the source of truth, and this exists so the stored column can be checked
         * against it: a `keyRef` that disagrees with the bytes it describes is worse than a null one,
         * because it would send a rotation after the wrong rows. It also makes the column derivable
         * for rows written before it was populated.
         *
         * @return null when the envelope carries no key id this app recognises — a v1 envelope
         *   (`epochId`), an unknown version, an unparseable payload. The caller must treat null as
         *   "unknown", never as "no key".
         */
        fun ofEnvelope(ciphertext: ByteArray): EventKeyRef? {
            val envelope = runCatching { JSONObject(String(ciphertext, Charsets.UTF_8)) }.getOrNull()
                ?: return null
            // v3 message events only: `liveKeyId`/`historyKeyId` are what encryptMessageV3 writes.
            val liveV3 = envelope.optString("liveKeyId").takeIf { it.isNotBlank() }
            val historyV3 = envelope.optString("historyKeyId").takeIf { it.isNotBlank() }
            if (liveV3 != null) return EventKeyRef(liveKeyId = liveV3, historyKeyId = historyV3)
            // v2: `keyId`. v1: `epochId`. Both are a single live key with no history wrap.
            val single = envelope.optString("keyId").takeIf { it.isNotBlank() }
                ?: envelope.optString("epochId").takeIf { it.isNotBlank() }
                ?: return null
            return EventKeyRef(liveKeyId = single)
        }
    }
}
