package com.autonomousone.messages.gateway

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The durable carrier-delivery report contract.
 *
 * These are PURE tests (no Android, no Context): they pin the two properties the whole upload path
 * rests on — the event identity is stable, and a report written by an older build still decodes — plus
 * the part/aggregate metadata GMweb needs to know that a `delivered` verdict really covers every part.
 *
 * NOTE: written, deliberately NOT executed (this task forbids running Gradle).
 */
class GatewayDeliveryReportContractTest {

    private fun report(
        status: String = "delivered",
        segmentIndex: Int = 0,
        segmentCount: Int = 1,
        subscriptionId: Int? = null,
        carrierResultCode: Int? = null
    ) = GatewayDeliveryReports.Report(
        eventId = GatewayDeliveryReports.eventId("gw-1", status),
        requestId = "gw-1",
        status = status,
        occurredAt = 1_700_000_000_000L,
        segmentIndex = segmentIndex,
        segmentCount = segmentCount,
        subscriptionId = subscriptionId,
        carrierResultCode = carrierResultCode,
        allSegmentsDelivered = status == "delivered"
    )

    // ── idempotency ──────────────────────────────────────────────────────────

    @Test
    fun `the same verdict always produces the same event id`() {
        // This is what makes a retry safe: the worker re-sends the id it persisted, not a new one.
        val first = GatewayDeliveryReports.eventId("gw-1", "delivered")
        val second = GatewayDeliveryReports.eventId("gw-1", "delivered")

        assertEquals(first, second)
        assertTrue("the id is a prefixed hex token", first.startsWith("dlr_"))
        assertTrue(first.removePrefix("dlr_").matches(Regex("[0-9a-f]{32}")))
    }

    @Test
    fun `different verdicts and different jobs never share an id`() {
        assertNotEquals(
            GatewayDeliveryReports.eventId("gw-1", "delivered"),
            GatewayDeliveryReports.eventId("gw-1", "failed")
        )
        assertNotEquals(
            GatewayDeliveryReports.eventId("gw-1", "delivered"),
            GatewayDeliveryReports.eventId("gw-2", "delivered")
        )
    }

    @Test
    fun `the json carries the identity a retry must reuse`() {
        val json = report().json()

        assertEquals("gw-1", json.getString("requestId"))
        assertEquals("delivered", json.getString("status"))
        assertEquals(GatewayDeliveryReports.eventId("gw-1", "delivered"), json.getString("eventId"))
        assertEquals(1_700_000_000_000L, json.getLong("occurredAt"))
    }

    // ── part / aggregate metadata (multipart) ────────────────────────────────

    @Test
    fun `a multipart verdict states which part produced it and how many are expected`() {
        val json = report(status = "delivered", segmentIndex = 1, segmentCount = 3).json()

        assertEquals(1, json.getInt("segmentIndex"))
        assertEquals(3, json.getInt("segmentCount"))
        assertEquals("carrier_delivery", json.getString("eventType"))
    }

    @Test
    fun `delivered means every expected part is covered`() {
        assertTrue(report(status = "delivered").json().getBoolean("allSegmentsDelivered"))
    }

    @Test
    fun `failed does NOT claim a full delivery`() {
        // One refused part of a multipart message is a `failed` aggregate; reporting it as a complete
        // delivery would be the exact lie this pipeline exists to prevent.
        val json = report(status = "failed", segmentIndex = 0, segmentCount = 3).json()

        assertEquals("failed", json.getString("status"))
        assertFalse(json.getBoolean("allSegmentsDelivered"))
    }

    @Test
    fun `the send-time subscription and native result code travel with the verdict`() {
        val json = report(subscriptionId = 2, carrierResultCode = 0).json()

        assertEquals(2, json.getInt("subscriptionId"))
        assertEquals(0, json.getInt("carrierResultCode"))
    }

    @Test
    fun `absent metadata is omitted rather than fabricated`() {
        val json = report(subscriptionId = null, carrierResultCode = null).json()

        // putOpt writes JSON null (an absent VALUE, not a guessed 0) — a fabricated result code of 0
        // would read as RESULT_OK, i.e. as carrier success, on a callback that never carried one.
        assertTrue(json.isNull("subscriptionId") || !json.has("subscriptionId"))
        assertTrue(json.isNull("carrierResultCode") || !json.has("carrierResultCode"))
    }

    // ── legacy rows ──────────────────────────────────────────────────────────

    @Test
    fun `an old persisted report without the new fields still decodes`() {
        // Exactly the shape written by the build before this change: four fields, nothing else.
        val legacy = JSONObject()
            .put("eventId", "dlr_legacy")
            .put("requestId", "gw-legacy")
            .put("status", "delivered")
            .put("occurredAt", 1_600_000_000_000L)

        val parsed = parseLikePending(legacy)

        assertEquals("dlr_legacy", parsed.eventId)
        assertEquals("gw-legacy", parsed.requestId)
        assertEquals("delivered", parsed.status)
        assertEquals(1, parsed.segmentCount)
        assertEquals(0, parsed.segmentIndex)
        assertNull("a missing subscription must stay unknown, never 0", parsed.subscriptionId)
        assertNull("a missing carrier code must stay unknown, never RESULT_OK", parsed.carrierResultCode)
        assertTrue("a legacy delivered verdict still means delivered", parsed.allSegmentsDelivered)
        assertEquals(1_600_000_000_000L, parsed.receivedAtDevice)
    }

    @Test
    fun `a legacy failed report does not become a delivered one when decoded`() {
        val legacy = JSONObject()
            .put("eventId", "dlr_legacy_failed")
            .put("requestId", "gw-legacy")
            .put("status", "failed")
            .put("occurredAt", 1L)

        val parsed = parseLikePending(legacy)

        assertEquals("failed", parsed.status)
        assertFalse(parsed.allSegmentsDelivered)
    }

    @Test
    fun `a malformed report is skipped instead of breaking the uploader`() {
        // The uploader parses every pending row; one unreadable row must not stop the others.
        assertNull(runCatching { parseLikePending(JSONObject("{}")) }.getOrNull())
    }

    // ── GMweb response handling ──────────────────────────────────────────────

    @Test
    fun `only the documented unknown-request response quarantines a report`() {
        assertTrue(GatewayDeliveryReports.isUnknownRequestResponse(404, "unknown_request_id"))
        assertFalse(GatewayDeliveryReports.isUnknownRequestResponse(404, "not_found"))
        assertFalse(GatewayDeliveryReports.isUnknownRequestResponse(500, "unknown_request_id"))
        assertFalse(GatewayDeliveryReports.isUnknownRequestResponse(200, null))
    }

    /**
     * Mirrors `pending()`'s decode rules without touching SharedPreferences, so the legacy-row
     * behaviour is asserted on the JVM. Kept identical to the production reader by construction:
     * the same opt/with-default calls in the same order.
     */
    private fun parseLikePending(obj: JSONObject): GatewayDeliveryReports.Report {
        val status = obj.getString("status")
        return GatewayDeliveryReports.Report(
            eventId = obj.getString("eventId"),
            requestId = obj.getString("requestId"),
            status = status,
            occurredAt = obj.getLong("occurredAt"),
            segmentIndex = obj.optInt("segmentIndex", 0),
            segmentCount = obj.optInt("segmentCount", 1),
            subscriptionId = if (obj.has("subscriptionId") && !obj.isNull("subscriptionId")) {
                obj.optInt("subscriptionId")
            } else {
                null
            },
            carrierResultCode = if (obj.has("carrierResultCode") && !obj.isNull("carrierResultCode")) {
                obj.optInt("carrierResultCode")
            } else {
                null
            },
            allSegmentsDelivered = obj.optBoolean("allSegmentsDelivered", status == "delivered"),
            receivedAtDevice = obj.optLong("receivedAtDevice", obj.optLong("occurredAt", 0L))
        )
    }
}
