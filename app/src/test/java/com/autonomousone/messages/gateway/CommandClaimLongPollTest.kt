package com.autonomousone.messages.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 1.1 — the command channel's long-poll, and the guard that keeps it from becoming a hot loop.
 *
 * The 2 s claim cadence (43 200 authenticated requests/day) was not carelessness: it was a literal
 * reading of ADR-003's `command pickup P95 < 3s`. The long-poll keeps that promise better (the server
 * answers the instant work appears) while claiming ~2.4 times a minute. What must never happen is the
 * opposite failure: an older GMweb ignores `waitMs`, answers instantly, and the loop re-claims for
 * ever.
 */
class CommandClaimLongPollTest {

    // ── The claim request itself ─────────────────────────────────────────────

    @Test
    fun `the claim URL asks for a 25 second hold`() {
        assertEquals(
            "https://gmweb.example/api/v1/agent/commands/claim?waitMs=25000",
            SecureCommandPoller.claimUrl("https://gmweb.example/")
        )
        // A trailing slash in the configured origin must not double up.
        assertEquals(
            "https://gmweb.example/api/v1/agent/commands/claim?waitMs=25000",
            SecureCommandPoller.claimUrl("https://gmweb.example")
        )
    }

    @Test
    fun `the signed path still carries no query string`() {
        // AgentAuth signs METHOD\n<path-without-query>\n… . The claim passes CLAIM_PATH to sign(),
        // not the URL, so adding waitMs changes no signature material. This pins that separation.
        val url = java.net.URI(SecureCommandPoller.claimUrl("https://gmweb.example"))
        assertEquals("/api/v1/agent/commands/claim", url.rawPath)
        assertTrue("the hold must be requested", url.rawQuery.contains("waitMs=25000"))

        val body = "{\"deviceId\":\"d\"}".toByteArray()
        val ts = 1_700_000_000_000L
        val canonical = AgentAuth.canonicalString("POST", url.rawPath, body, ts)

        assertEquals(
            AgentAuth.canonicalString(
                "POST",
                "/api/v1/agent/commands/claim",
                body,
                ts
            ),
            canonical
        )
        assertTrue(canonical.startsWith("POST\n/api/v1/agent/commands/claim\n"))
        assertTrue(canonical.endsWith("\nX-AGENT-TS:1700000000000\n"))
        assertFalse("the query must never be signed", canonical.contains("waitMs"))
    }

    @Test
    fun `the read timeout outlives the server hold`() {
        assertEquals(40_000L, CommandClaimPacing.TIMEOUT_MS)
        assertTrue(
            "a read timeout below the hold would abort the wait it exists for",
            CommandClaimPacing.TIMEOUT_MS > CommandClaimPacing.LONG_POLL_MS
        )
    }

    // ── Capability detection ─────────────────────────────────────────────────

    @Test
    fun `a held claim proves the server honours waitMs and needs no extra sleep`() {
        val decision = CommandClaimPacing.afterEmptyClaim(CommandClaimPacing.LONG_POLL_MS)
        assertTrue(decision.longPollSupported == true)
        assertEquals("the hold already absorbed the idle wait", 0L, decision.waitMs)
    }

    @Test
    fun `the threshold is half the requested hold`() {
        assertEquals(12_500L, CommandClaimPacing.HOLD_THRESHOLD_MS)
        assertTrue(CommandClaimPacing.afterEmptyClaim(12_500L).waitMs == 0L)
        assertEquals(
            "just below half must be treated as unsupported",
            CommandClaimPacing.QUIET_MS,
            CommandClaimPacing.afterEmptyClaim(12_499L).waitMs
        )
    }

    @Test
    fun `an instant answer falls back to the documented 2 second cadence`() {
        val decision = CommandClaimPacing.afterEmptyClaim(35L)
        assertTrue(decision.longPollSupported == false)
        assertEquals(2_000L, decision.waitMs)
    }

    // ── The regression guard ─────────────────────────────────────────────────

    @Test
    fun `an instant-answering server cannot produce more than one claim per QUIET_MS`() {
        // Simulate a minute of wall-clock against a server that ignores waitMs and answers in 10 ms.
        // Every request must be followed by the fallback sleep; the pre-fix loop (no sleep) would
        // produce ~6 000 requests in this window.
        val answerLatencyMs = 10L
        val windowMs = 60_000L
        var elapsed = 0L
        var requests = 0

        while (elapsed < windowMs) {
            requests++
            elapsed += answerLatencyMs
            val decision = CommandClaimPacing.afterEmptyClaim(answerLatencyMs)
            elapsed += decision.waitMs
        }

        val maxExpected = windowMs / CommandClaimPacing.QUIET_MS + 1 // 31
        assertTrue("hot loop detected: $requests claims in one minute", requests <= maxExpected)
        assertTrue("the simulation must actually poll: $requests", requests >= 25)
    }

    @Test
    fun `the fallback constant is still the ADR-003 cadence`() {
        // Raising this quietly would trade the accepted pickup SLO for battery; Stage 1 forbids it.
        assertEquals(2_000L, CommandClaimPacing.QUIET_MS)
    }
}
