package com.autonomousone.messages.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `Retry-After` handling (mission §17).
 *
 * The rule that matters: when the server states how long to wait, we wait that long. Replacing it
 * with our own backoff is how a device that has just been rate-limited immediately gets
 * rate-limited again.
 *
 * `parseRetryAfter` is deliberately internal and pure so this needs no Android or network.
 */
class ControlPlaneRetryAfterTest {

    private fun parse(header: String?): Long? = ControlPlaneClient.parseRetryAfter(header)

    @Test
    fun `deltaSecondsAreConvertedToMilliseconds`() {
        assertEquals(120_000L, parse("120"))
        assertEquals(1_000L, parse("1"))
        // Servers commonly pad the header.
        assertEquals(5_000L, parse("  5 "))
    }

    @Test
    fun `anAbsentOrUnusableHeaderYieldsNoHint`() {
        // Null means "fall back to our own backoff", so anything unusable must land here rather
        // than producing a nonsense delay.
        listOf(null, "", "   ", "later", "0", "-30", "1.5", "Mon, 21 Sep 2026 07:28:00 GMT")
            .forEach { header ->
                assertNull("header=$header", parse(header))
            }
    }

    @Test
    fun `anAbsurdDelayIsCapped`() {
        // A hostile or merely broken value must not park the outbox for a day.
        val capped = parse("86400")
        assertEquals(ControlPlaneClient.MAX_RETRY_AFTER_MS, capped)
        assertEquals(60 * 60_000L, capped)
    }

    @Test
    fun `theCapAllowsARealRateLimitThrough`() {
        // 30 minutes is a plausible rate limit and must not be clipped.
        assertEquals(30 * 60_000L, parse("1800"))
    }

    @Test
    fun `aFailureCarriesTheHintAndDefaultsToNone`() {
        val withHint = ControlPlaneClient.Result.Failure(
            error = "HTTP 429", httpStatus = 429, retryAfterMs = 60_000L
        )
        assertEquals(60_000L, withHint.retryAfterMs)

        // Every existing construction site stays valid and means "no hint".
        val without = ControlPlaneClient.Result.Failure("network error")
        assertNull(without.retryAfterMs)
        assertNull(without.httpStatus)
    }
}
