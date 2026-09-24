package com.autonomousone.messages.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * The agent-auth canonical string — the cross-repo contract that nothing pinned.
 *
 * `AgentAuth`'s KDoc claimed `DeviceIdentityFormatTest` pinned it. That test pins the uncompressed public
 * point (0x04‖X‖Y, 65 bytes) and an ES256 round-trip, and **not** this string. So the one thing whose
 * silent drift would break every authenticated call to GMweb had no test at all.
 *
 * Why that matters more than a normal missing test: the failure mode is a **401**. Since the control
 * plane's auth handling was corrected, a 401 makes this device replace its credential and re-enroll — so a
 * refactor here would not merely fail requests, it would destroy a working enrollment against a server
 * that revoked nothing, and the diagnostic would report a rejected key that was never rejected.
 *
 * The digests below are SHA-256 of the empty string and of `hello`, verified independently before being
 * written down. They are literals on purpose: a golden value is the only kind of assertion that catches a
 * change to the HASH as well as to the framing.
 */
class AgentAuthTest {

    private val emptyBodyHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    private val helloBodyHash = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"

    // ── The golden string ────────────────────────────────────────────────────

    @Test
    fun `the canonical string is exactly what GMweb verifies`() {
        // Byte for byte. GMweb's agentAuth.js reconstructs this from the request; anything the two sides
        // disagree about — order, separators, the trailing newline, the header spelling — is a 401.
        val canonical = AgentAuth.canonicalString(
            method = "POST",
            path = "/api/v1/agent/events/batch",
            bodyBytes = ByteArray(0),
            timestamp = 1_700_000_000_000L
        )

        assertEquals(
            "POST\n/api/v1/agent/events/batch\n$emptyBodyHash\nX-AGENT-TS:1700000000000\n",
            canonical
        )
    }

    @Test
    fun `thebodyIsCoveredByItsHashAndTheHashIsLowercaseHex`() {
        val canonical = AgentAuth.canonicalString(
            method = "POST",
            path = "/api/v1/agent/events/batch",
            bodyBytes = "hello".toByteArray(Charsets.UTF_8),
            timestamp = 42L
        )

        assertEquals("POST\n/api/v1/agent/events/batch\n$helloBodyHash\nX-AGENT-TS:42\n", canonical)
        val hashLine = canonical.split("\n")[2]
        assertTrue("lowercase hex, 64 chars: $hashLine", Regex("^[0-9a-f]{64}$").matches(hashLine))
    }

    @Test
    fun `aNullBodyHashesTheEmptyByteStringRatherThanTheWordNull`() {
        // A null body is a GET with nothing to send. Hashing "null" — or worse, skipping the field — would
        // produce a different canonical string from the one GMweb builds for an empty body.
        val fromNull = AgentAuth.canonicalString("GET", "/api/v1/agent/status", null, 7L)
        val fromEmpty = AgentAuth.canonicalString("GET", "/api/v1/agent/status", ByteArray(0), 7L)

        assertEquals(fromEmpty, fromNull)
        assertTrue(fromNull, fromNull.contains(emptyBodyHash))
    }

    // ── What the string must make tamper-evident ─────────────────────────────

    @Test
    fun `theTimestampIsInsideTheSignedMaterial`() {
        // The whole point of a replay window: a timestamp carried OUTSIDE the signature could be rewritten
        // by anyone, and a captured request could then be replayed for ever.
        val early = AgentAuth.canonicalString("POST", "/p", ByteArray(0), 1_000L)
        val later = AgentAuth.canonicalString("POST", "/p", ByteArray(0), 2_000L)

        assertNotEquals(early, later)
        assertTrue(later, later.contains("X-AGENT-TS:2000"))
    }

    @Test
    fun `everyFieldIsSignedSoNoneCanBeSwapped`() {
        val base = AgentAuth.canonicalString("POST", "/a", "x".toByteArray(), 5L)

        assertNotEquals("method", base, AgentAuth.canonicalString("GET", "/a", "x".toByteArray(), 5L))
        assertNotEquals("path", base, AgentAuth.canonicalString("POST", "/b", "x".toByteArray(), 5L))
        assertNotEquals("body", base, AgentAuth.canonicalString("POST", "/a", "y".toByteArray(), 5L))
        assertNotEquals("timestamp", base, AgentAuth.canonicalString("POST", "/a", "x".toByteArray(), 6L))
    }

    @Test
    fun `theSeparatorAndTheTrailingNewlineArePartOfTheContract`() {
        // Stated separately because they are the parts a "tidy-up" removes: a trailing newline looks like
        // noise, and a space separator looks like a sensible normalisation. Both change every signature.
        val canonical = AgentAuth.canonicalString("POST", "/p", ByteArray(0), 1L)

        assertTrue("ends with a newline", canonical.endsWith("\n"))
        // Four content lines: method, path, body hash, timestamp header.
        assertEquals("four content lines", 4, canonical.trimEnd('\n').split("\n").size)
        assertFalse("no spaces as separators", canonical.contains(" POST "))
        assertFalse(canonical.startsWith("/p"))
    }

    /** A signature over the canonical bytes verifies against them, and against nothing else. */
    @Test
    fun `aSignatureOverTheCanonicalStringDoesNotVerifyADifferentOne`() {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val pair = generator.generateKeyPair()

        val canonical = AgentAuth.canonicalString("POST", "/p", "hello".toByteArray(), 1_000L)
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(pair.private)
            update(canonical.toByteArray(Charsets.UTF_8))
        }.sign()

        fun verifies(candidate: String): Boolean = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(pair.public)
            update(candidate.toByteArray(Charsets.UTF_8))
        }.verify(signature)

        assertTrue(verifies(canonical))
        // A bumped timestamp — the replay attempt — does not.
        assertFalse(verifies(AgentAuth.canonicalString("POST", "/p", "hello".toByteArray(), 1_001L)))
    }

    // ── The timestamp itself ─────────────────────────────────────────────────

    @Test
    fun `theTimestampNeverRepeatsEvenWithinTheSameMillisecond`() {
        // Two requests in one millisecond must not carry the same signed timestamp: a server that treats a
        // repeated timestamp as a replay would reject the second one, and the device would see a failure it
        // could not explain.
        val first = AgentAuth.freshTimestamp(now = 5_000L)
        val second = AgentAuth.freshTimestamp(now = 5_000L)
        val third = AgentAuth.freshTimestamp(now = 5_000L)

        assertTrue(first < second && second < third)
    }

    @Test
    fun `theTimestampNeverGoesBackwardsWhenTheClockDoes`() {
        // A device whose clock steps backwards (NTP correction, timezone tooling) must not start emitting
        // timestamps the server has already seen.
        val high = AgentAuth.freshTimestamp(now = 9_000_000L)
        val afterBackwardsStep = AgentAuth.freshTimestamp(now = 1_000L)

        assertTrue("$afterBackwardsStep must exceed $high", afterBackwardsStep > high)
    }

    @Test
    fun `theReplayWindowTheServerEnforcesIsExposedAndSane`() {
        // The device's monotonic timestamps are useless if the window is enormous: 90 seconds is long
        // enough for a slow upload and short enough to bound a captured request's usefulness.
        assertTrue(AgentAuth.SERVER_REPLAY_WINDOW_MS in 30_000..300_000)
    }
}
