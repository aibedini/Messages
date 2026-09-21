package com.autonomousone.messages.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ONE GMweb server address.
 *
 * This is the boundary where a user's pasted panel URL becomes the single origin every GMweb
 * client speaks to, so the rules are asserted rather than trusted: what is accepted, what is
 * stripped, and — most importantly — what is REJECTED. A silently-mangled address is how the
 * phone ends up pulling from one server and uploading to another.
 */
class GmwebServerProfileTest {

    private fun valid(input: String): GmwebServerProfile {
        val result = GmwebServerProfile.normalize(input)
        assertTrue("expected <$input> to be accepted but got $result", result is GmwebServerNormalization.Valid)
        return (result as GmwebServerNormalization.Valid).profile
    }

    private fun error(input: String?): GmwebInputError {
        val result = GmwebServerProfile.normalize(input)
        assertTrue("expected <$input> to be rejected but got $result", result is GmwebServerNormalization.Invalid)
        return (result as GmwebServerNormalization.Invalid).error
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // The four forms the brief requires, all collapsing to ONE origin
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `acceptedFormsAllNormalizeToOneOrigin`() {
        val expected = "https://gmweb.okgfx.ir"

        assertEquals(expected, valid("https://gmweb.okgfx.ir").origin)
        assertEquals(expected, valid("https://gmweb.okgfx.ir/").origin)
        assertEquals(expected, valid("https://gmweb.okgfx.ir/app").origin)
        assertEquals(expected, valid("https://gmweb.okgfx.ir/app/").origin)
        // The dashboard route is the same panel under a different name.
        assertEquals(expected, valid("https://gmweb.okgfx.ir/dashboard").origin)
        assertEquals(expected, valid("https://gmweb.okgfx.ir/dashboard/").origin)
        // Surrounding whitespace is trimmed; a pasted value usually has some.
        assertEquals(expected, valid("  https://gmweb.okgfx.ir/app  ").origin)
    }

    @Test
    fun `thePanelRouteIsNeverStoredAsTheApiOrigin`() {
        val profile = valid("https://gmweb.okgfx.ir/app")

        assertEquals("https://gmweb.okgfx.ir", profile.origin)
        assertFalse("the panel path must not leak into the API origin", profile.origin.endsWith("/app"))
        // …but it is kept for the Open panel button.
        assertEquals("https://gmweb.okgfx.ir/app", profile.dashboardUrl)
    }

    @Test
    fun `theSchemeIsCaseInsensitiveAndTheHostIsLowercased`() {
        assertEquals("https://gmweb.okgfx.ir", valid("HTTPS://GMWEB.OKGFX.IR").origin)
        assertEquals("gmweb.okgfx.ir", valid("HTTPS://GMWEB.OKGFX.IR").host)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Rejections
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `plainHttpIsRejected`() {
        assertEquals(GmwebInputError.INSECURE_SCHEME, error("http://gmweb.okgfx.ir"))
        assertEquals(GmwebInputError.INSECURE_SCHEME, error("http://gmweb.okgfx.ir/app"))
        // A non-HTTP scheme is refused by the same rule, not treated as a host.
        assertEquals(GmwebInputError.INSECURE_SCHEME, error("ftp://gmweb.okgfx.ir"))
    }

    @Test
    fun `credentialsInTheUrlAreRejected`() {
        assertEquals(
            GmwebInputError.EMBEDDED_CREDENTIALS,
            error("https://user:secret@gmweb.okgfx.ir")
        )
        assertEquals(
            GmwebInputError.EMBEDDED_CREDENTIALS,
            error("https://user@gmweb.okgfx.ir")
        )
    }

    @Test
    fun `queryAndFragmentAreRejected`() {
        assertEquals(GmwebInputError.HAS_QUERY, error("https://gmweb.okgfx.ir?token=abc"))
        assertEquals(GmwebInputError.HAS_FRAGMENT, error("https://gmweb.okgfx.ir/app#section"))
        // An empty query still counts as a query: it is not part of an address.
        assertEquals(GmwebInputError.HAS_QUERY, error("https://gmweb.okgfx.ir?"))
    }

    @Test
    fun `blankAndMalformedInputAreRejected`() {
        assertEquals(GmwebInputError.BLANK, error(""))
        assertEquals(GmwebInputError.BLANK, error("   "))
        assertEquals(GmwebInputError.BLANK, error(null))
        assertEquals(GmwebInputError.MISSING_SCHEME, error("gmweb.okgfx.ir"))
        assertEquals(GmwebInputError.MISSING_SCHEME, error("gmweb.okgfx.ir/app"))
        // No authority at all is malformed …
        assertEquals(GmwebInputError.MALFORMED, error("https://"))
        assertEquals(GmwebInputError.MALFORMED, error("https:// gmweb .okgfx.ir"))
        // … while an authority with an EMPTY host is specifically a missing host.
        assertEquals(GmwebInputError.MISSING_HOST, error("https://:443"))
    }

    /**
     * An unknown path is REFUSED, not silently stripped.
     *
     * Silently dropping it would produce 404s that look like a server fault, and the user
     * would have no way to know their address was rewritten.
     */
    @Test
    fun `anUnknownPathIsRejectedRatherThanSilentlyDropped`() {
        assertEquals(GmwebInputError.UNSUPPORTED_PATH, error("https://gmweb.okgfx.ir/gmweb"))
        assertEquals(GmwebInputError.UNSUPPORTED_PATH, error("https://gmweb.okgfx.ir/app/settings"))
        assertEquals(GmwebInputError.UNSUPPORTED_PATH, error("https://gmweb.okgfx.ir/api/v1/agent"))
    }

    @Test
    fun `aNonDefaultPortIsKeptAndTheDefaultIsNotShown`() {
        val custom = valid("https://gmweb.okgfx.ir:8443")
        assertEquals("https://gmweb.okgfx.ir:8443", custom.origin)
        assertEquals("gmweb.okgfx.ir:8443", custom.displayHost)
        assertEquals(8443, custom.port)

        val explicitDefault = valid("https://gmweb.okgfx.ir:443")
        assertEquals("https://gmweb.okgfx.ir", explicitDefault.origin)
        assertEquals("gmweb.okgfx.ir", explicitDefault.displayHost)
        assertEquals(443, explicitDefault.port)
    }

    @Test
    fun `profileOfReturnsNullInsteadOfThrowing`() {
        assertNull(GmwebServerProfile.profileOf("nonsense"))
        assertNull(GmwebServerProfile.profileOf(null))
        assertEquals("https://gmweb.okgfx.ir", GmwebServerProfile.profileOf("https://gmweb.okgfx.ir")!!.origin)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Derived routes — ALL from the one origin
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `everyRouteIsDerivedFromTheOneOrigin`() {
        val p = valid("https://gmweb.okgfx.ir/app")

        val routes = listOf(
            p.dashboardUrl, p.healthUrl, p.gatewayPingUrl, p.gatewayStatusUrl,
            p.gatewayPullUrl, p.gatewayValidateUrl, p.gatewayAckUrl, p.agentApiBase
        )
        routes.forEach { route ->
            assertTrue("$route must be on the configured origin", route.startsWith(p.origin))
        }
        assertFalse("nothing may escape to another host", routes.any { it.contains("203.0.113.10") })

        assertEquals("https://gmweb.okgfx.ir/health", p.healthUrl)
        assertEquals("https://gmweb.okgfx.ir/gateway/pull", p.gatewayPullUrl)
        assertEquals("https://gmweb.okgfx.ir/gateway/validate", p.gatewayValidateUrl)
        assertEquals("https://gmweb.okgfx.ir/gateway/ack", p.gatewayAckUrl)
        assertEquals("https://gmweb.okgfx.ir/api/v1/agent", p.agentApiBase)
        // The paths are constants, so a signer and a sender can never disagree about the
        // string they are signing.
        assertEquals("/api/v1/agent/identity", p.identityPath)
        assertEquals("/api/v1/agent/events/batch", p.eventsBatchPath)
    }

    @Test
    fun `urlJoinsAPathOntoTheOriginAndRefusesAnythingElse`() {
        val p = valid("https://gmweb.okgfx.ir")

        assertEquals("https://gmweb.okgfx.ir/gateway/pull", p.url("/gateway/pull"))
        assertEquals("https://gmweb.okgfx.ir/api/v1/agent/identity", p.url(p.identityPath))

        // A relative path would silently produce a wrong URL, so it is a programming error.
        val thrown = runCatching { p.url("gateway/pull") }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException)
    }

    @Test
    fun `routesFollowACustomPortToo`() {
        val p = valid("https://gmweb.okgfx.ir:8443")

        assertEquals("https://gmweb.okgfx.ir:8443/gateway/pull", p.gatewayPullUrl)
        assertEquals("https://gmweb.okgfx.ir:8443/app", p.dashboardUrl)
    }

    @Test
    fun `theKnownPanelRoutesAreExactlyTheDocumentedOnes`() {
        assertEquals(setOf("/app", "/dashboard"), GmwebServerProfile.KNOWN_UI_PATHS)
        assertFalse("/app/" in GmwebServerProfile.KNOWN_UI_PATHS)
    }
}
