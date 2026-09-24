package com.autonomousone.messages.sync

import org.json.JSONArray
import org.json.JSONObject

/**
 * What the server said about each event in a batch (mission §15/§16).
 *
 * WHY THIS EXISTS: the shipped parser reads a flat `accepted[]` array and requires
 * `serverSequence > 0`. Anything else is re-uploaded — forever. That breaks the mission's own
 * Test F: when the server persisted an event but the response was lost, the retry returns
 * DUPLICATE, which the old parser could not interpret, so the row looped instead of converging.
 * `duplicates` was read as a scalar and used only for logging.
 *
 * Two response shapes are supported, chosen per response rather than by configuration, so the
 * client keeps working against the deployed server while GMweb V2 rolls out (mission §62):
 *
 * ```text
 * legacy  {"accepted":[{"eventId":"...","serverSequence":58193}], "duplicates":2, ...}
 * V2      {"results":[{"eventId":"...","status":"ACCEPTED","serverSequence":92110},
 *                     {"eventId":"...","status":"DUPLICATE","serverSequence":92000},
 *                     {"eventId":"...","status":"REJECTED","retryable":false,
 *                      "errorCode":"INVALID_SCHEMA"}]}
 * ```
 *
 * Pure and Android-free so every branch is unit-tested rather than discovered on a device.
 */
object BatchAckParser {

    /** A per-item verdict, in the mission's vocabulary. */
    enum class ItemStatus { ACCEPTED, DUPLICATE, REJECTED }

    /**
     * The outcome for one event.
     *
     * [serverSequence] is 0 when the server did not supply one. That is NOT a reason to withhold
     * the ACK: the server stating it accepted the event is the evidence, and treating a missing
     * sequence as "not accepted" is exactly the infinite-retry defect.
     */
    data class Item(
        val eventId: String,
        val status: ItemStatus,
        val serverSequence: Long = 0L,
        /** Only meaningful for [ItemStatus.REJECTED]; false means the event can never succeed. */
        val retryable: Boolean = false,
        val errorCode: String? = null
    )

    /**
     * The parsed batch response.
     *
     * [mentioningNothing] is true when the body carried no item-level information at all (an
     * empty object, or a server that only reports a count). Callers must NOT read that as
     * "everything failed permanently" — it means the response told us nothing.
     */
    data class Parsed(
        val items: List<Item>,
        val duplicates: Int,
        val mentioningNothing: Boolean
    ) {
        /** Event ids the server has durably persisted: ACCEPTED or DUPLICATE (mission §16). */
        val acknowledged: Map<String, Long>
            get() = items
                .filter { it.status == ItemStatus.ACCEPTED || it.status == ItemStatus.DUPLICATE }
                .associate { it.eventId to it.serverSequence }

        /** Events the server will never accept, with the server's own reason. */
        val rejected: Map<String, Item>
            get() = items
                .filter { it.status == ItemStatus.REJECTED && !it.retryable }
                .associateBy { it.eventId }

        /** Events the server explicitly asked us to send again. */
        val retryable: Map<String, Item>
            get() = items
                .filter { it.status == ItemStatus.REJECTED && it.retryable }
                .associateBy { it.eventId }
    }

    /**
     * Parse a batch response body. Never throws: an unparseable body yields
     * [Parsed.mentioningNothing] so the caller retries rather than dead-lettering on garbage.
     */
    fun parse(body: String?): Parsed {
        val json = body?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return Parsed(items = emptyList(), duplicates = 0, mentioningNothing = true)

        val results = json.optJSONArray("results")
        val items = if (results != null) parseResults(results) else parseLegacyAccepted(json)
        val duplicates = json.optInt("duplicates", 0)

        return Parsed(
            items = items,
            duplicates = duplicates,
            mentioningNothing = items.isEmpty() && duplicates == 0 && !json.has("accepted") &&
                !json.has("results")
        )
    }

    /** V2: `results[]` with an explicit per-item status. */
    private fun parseResults(results: JSONArray): List<Item> {
        val items = ArrayList<Item>(results.length())
        for (i in 0 until results.length()) {
            val row = results.optJSONObject(i) ?: continue
            val eventId = row.optString("eventId")
            if (eventId.isEmpty()) continue
            val status = when (row.optString("status").uppercase()) {
                "ACCEPTED" -> ItemStatus.ACCEPTED
                "DUPLICATE" -> ItemStatus.DUPLICATE
                "REJECTED" -> ItemStatus.REJECTED
                // An unrecognised status is not evidence of anything: treat it as retryable so
                // the event is preserved rather than silently dropped.
                else -> ItemStatus.REJECTED
            }
            val retryable = when (status) {
                ItemStatus.REJECTED -> row.optBoolean("retryable", true)
                else -> false
            }
            items += Item(
                eventId = eventId,
                status = status,
                serverSequence = row.optLong("serverSequence", 0L),
                retryable = retryable,
                errorCode = row.optString("errorCode").takeIf { it.isNotEmpty() }
            )
        }
        return items
    }

    /**
     * Legacy: a flat `accepted[]` of `{eventId, serverSequence}`.
     *
     * Absence from this list means only "not reported", which the caller treats as retryable —
     * it must never be read as a rejection.
     */
    private fun parseLegacyAccepted(json: JSONObject): List<Item> {
        val accepted = json.optJSONArray("accepted") ?: return emptyList()
        val items = ArrayList<Item>(accepted.length())
        for (i in 0 until accepted.length()) {
            val row = accepted.optJSONObject(i) ?: continue
            val eventId = row.optString("eventId")
            if (eventId.isEmpty()) continue
            items += Item(
                eventId = eventId,
                status = ItemStatus.ACCEPTED,
                serverSequence = row.optLong("serverSequence", 0L)
            )
        }
        return items
    }
}
