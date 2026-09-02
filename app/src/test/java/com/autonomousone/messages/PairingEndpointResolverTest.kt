package com.autonomousone.messages

import com.autonomousone.messages.security.PairingEndpointResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-007 — PairingEndpointResolver regression matrix (review follow-up):
 * pairing never requires manual Gateway configuration; origin comparison is
 * canonical (scheme+host+effective port, HTTPS mandatory). Pure-Kotlin logic
 * — no Android framework needed.
 */
class PairingEndpointResolverTest {

    @Test
    fun `canonical origin normalizes host, default port and trailing slash`() {
        assertEquals(
            "https://gmweb.example.com:443",
            PairingEndpointResolver.canonicalOrigin("https://gmweb.example.com/")
        )
        assertEquals(
            "https://gmweb.example.com:443",
            PairingEndpointResolver.canonicalOrigin("https://GMWeb.Example.com")
        )
        assertEquals(
            "https://gmweb.example.com:8443",
            PairingEndpointResolver.canonicalOrigin("https://gmweb.example.com:8443/some/path")
        )
    }

    @Test
    fun `http origin is rejected (HTTPS mandatory)`() {
        assertNull(PairingEndpointResolver.canonicalOrigin("http://gmweb.example.com"))
    }

    @Test
    fun `same origin matches regardless of path or slash`() {
        val a = PairingEndpointResolver.canonicalOrigin("https://gmweb.example.com")
        val b = PairingEndpointResolver.canonicalOrigin("https://gmweb.example.com/")
        assertEquals(a, b)
    }

    @Test
    fun `different host never matches`() {
        val good = PairingEndpointResolver.canonicalOrigin("https://gmweb.example.com")!!
        val evil = PairingEndpointResolver.canonicalOrigin("https://evil.example.com")!!
        assertFalse(good == evil)
    }

    @Test
    fun `blank url canonicalizes to null`() {
        assertNull(PairingEndpointResolver.canonicalOrigin(""))
        assertNull(PairingEndpointResolver.canonicalOrigin("   "))
    }
}
