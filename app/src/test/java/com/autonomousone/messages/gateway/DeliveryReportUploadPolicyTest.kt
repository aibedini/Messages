package com.autonomousone.messages.gateway

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The uploader's decision for one GMweb answer.
 *
 * The regression this pins: a single report GMweb will never accept used to stop the drain cycle,
 * so it sat at the head of the queue and every later carrier verdict stayed on the phone for ever.
 */
class DeliveryReportUploadPolicyTest {

    @Test
    fun `success acknowledges`() {
        for (code in listOf(200, 201, 202, 204)) {
            assertEquals(
                "HTTP $code",
                DeliveryReportUploadPolicy.Action.ACCEPT,
                DeliveryReportUploadPolicy.classify(code, null)
            )
        }
    }

    @Test
    fun `a permanently rejected report is quarantined, not retried for ever`() {
        assertEquals(
            DeliveryReportUploadPolicy.Action.QUARANTINE,
            DeliveryReportUploadPolicy.classify(404, "unknown_request_id")
        )
        for (code in listOf(400, 409, 422)) {
            assertEquals(
                "HTTP $code",
                DeliveryReportUploadPolicy.Action.QUARANTINE,
                DeliveryReportUploadPolicy.classify(code, "invalid_delivery_report")
            )
        }
    }

    @Test
    fun `a missing endpoint or a broken link is retried`() {
        // An older GMweb has no route at all: 404 without the ledger code. Quarantining would
        // silently discard every verdict, so this is a link problem to retry, not poison.
        assertEquals(
            DeliveryReportUploadPolicy.Action.RETRY,
            DeliveryReportUploadPolicy.classify(404, null)
        )
        for (code in listOf(401, 403, 408, 429, 500, 502, 503, 504)) {
            assertEquals(
                "HTTP $code",
                DeliveryReportUploadPolicy.Action.RETRY,
                DeliveryReportUploadPolicy.classify(code, null)
            )
        }
    }

    @Test
    fun `the whole sender stops for auth problems rather than draining the batch`() {
        // 401/403 are not about this report; every other report in the batch would fail too.
        assertEquals(
            DeliveryReportUploadPolicy.Action.RETRY,
            DeliveryReportUploadPolicy.classify(401, "unauthorized")
        )
        assertEquals(
            DeliveryReportUploadPolicy.Action.RETRY,
            DeliveryReportUploadPolicy.classify(403, "forbidden")
        )
    }
}
