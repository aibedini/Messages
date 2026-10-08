package com.autonomousone.messages.messaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The contract of the cross-system SIM identity.
 *
 * These are the properties that keep a private message on the line the user chose. They are asserted
 * here rather than observed on a phone because the failure mode they prevent — a *plausible* wrong
 * SIM — is exactly what a manual check would miss.
 *
 * The MAC is injected with a **test key**, so the derivation runs on the JVM. Production always uses
 * the AndroidKeyStore-backed key ([SimRefSecret]); nothing here can weaken that, because the default
 * parameter is the real one and tests must opt in explicitly.
 */
class SimRefProviderTest {

    private val keyA = "test-key-A".toByteArray(Charsets.UTF_8)
    private val keyB = "test-key-B-completely-different".toByteArray(Charsets.UTF_8)

    private fun macWith(key: ByteArray): (String) -> ByteArray = { message ->
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        mac.doFinal(message.toByteArray(Charsets.UTF_8))
    }

    private fun provider(key: ByteArray = keyA) = SimRefProvider(macWith(key))

    // ── Determinism ──────────────────────────────────────────────────────────

    @Test
    fun `same install and same subscription always derive the same ref`() {
        val first = provider().simRefFor(5).value
        val second = provider().simRefFor(5).value
        val third = provider().simRefFor(5).value

        assertEquals(first, second)
        assertEquals(second, third)
    }

    @Test
    fun `different subscriptions derive different refs`() {
        val p = provider()

        val refs = listOf(1, 2, 5, 9, 100).map { p.simRefFor(it).value }

        assertEquals("refs collided across subscriptions", refs.size, refs.toSet().size)
    }

    @Test
    fun `a different install secret derives a different ref for the same subscription`() {
        // This is what makes a lost key safe: old refs stop resolving instead of resolving wrongly.
        assertNotEquals(
            provider(keyA).simRefFor(5).value,
            provider(keyB).simRefFor(5).value
        )
    }

    @Test
    fun `the derivation is keyed, so a bare hash of the subscription id is not the answer`() {
        val subscriptionId = 5
        // What an unkeyed sha256("subscription:5") would look like, truncated to the token length.
        val unkeyed = java.security.MessageDigest.getInstance("SHA-256")
            .digest(SimRefFormat.macInput(subscriptionId).toByteArray(Charsets.UTF_8))
        val unkeyedToken = SimRefFormat.hex(unkeyed).take(SimRefFormat.TOKEN_HEX_LENGTH)

        assertNotEquals(
            "an unkeyed digest would be enumerable over the tiny subscriptionId domain",
            unkeyedToken,
            provider().simRefFor(subscriptionId).token
        )
    }

    // ── Opacity ──────────────────────────────────────────────────────────────

    @Test
    fun `every derived ref is well formed and versioned`() {
        val p = provider()

        for (subId in 0..20) {
            val ref = p.simRefFor(subId).value
            assertTrue("not prefixed: $ref", ref.startsWith(SimRefFormat.PREFIX))
            assertEquals(SimRefFormat.PREFIX.length + SimRefFormat.TOKEN_HEX_LENGTH, ref.length)
            assertTrue("not parseable back: $ref", SimRefFormat.isValid(ref))
        }
    }

    @Test
    fun `the raw subscription id is not the token, and nothing shorter encodes it`() {
        val p = provider()

        for (subId in 1..200) {
            val token = p.simRefFor(subId).token

            // Not the bare id, in either of the two obvious encodings. A *substring* test is
            // deliberately NOT used here: `1` is a hex digit, so it appears inside almost every
            // 32-character hex string by chance, and asserting its absence would be a coin flip
            // rather than a property.
            assertNotEquals("token is the bare id", "$subId", token)
            assertNotEquals("token is the bare id in hex", Integer.toHexString(subId), token)
            assertEquals(SimRefFormat.TOKEN_HEX_LENGTH, token.length)
        }
    }

    @Test
    fun `the subscription id is not recoverable by enumerating the plausible domain`() {
        val secret = provider(keyA).simRefFor(9).value
        val wrongKey = provider(keyB)

        // An attacker who could invert the MAC could find subId=9 by trying 0..1000. Without the key
        // no candidate reproduces the ref, which is the whole reason the derivation is keyed.
        val matches = (0..1000).count { wrongKey.simRefFor(it).value == secret }

        assertEquals(0, matches)
        // And the true key does reproduce it, so the zero above is not a vacuous result.
        assertEquals(secret, provider(keyA).simRefFor(9).value)
    }

    @Test
    fun `the display form is truncated and still reveals no input`() {
        val ref = provider().simRefFor(5)

        assertTrue(ref.shortForm.startsWith(SimRefFormat.PREFIX))
        assertTrue("the short form must be shorter than the identity", ref.shortForm.length < ref.value.length)
        assertTrue(ref.shortForm.endsWith("…"))
    }

    // ── Strict parsing ───────────────────────────────────────────────────────

    @Test
    fun `malformed references are rejected, never guessed at`() {
        val token = provider().simRefFor(5).token
        val malformed = listOf(
            null,
            "",
            "sim",
            "sim:v1",
            "sim:v1:",
            "sim:v1:$token extra",
            "sim:v1:${token.uppercase()}",
            "sim:v2:$token",
            "SIM:v1:$token",
            " sha256:v1:$token",
            "sim:v1:${token.dropLast(1)}",
            "sim:v1:${token}0",
            "sim:v1:${token.dropLast(2)}zz",
            token,
            "5",
            "sim:v1:${"0".repeat(SimRefFormat.TOKEN_HEX_LENGTH)}" + "0"
        )

        for (value in malformed) {
            assertNull("accepted a malformed reference: $value", SimRefFormat.parse(value))
            assertFalse("validated a malformed reference: $value", SimRefFormat.isValid(value))
        }
    }

    @Test
    fun `a well formed reference parses back to exactly the same value`() {
        val ref = provider().simRefFor(5)

        val parsed = SimRefFormat.parse(ref.value)

        assertEquals(ref.value, parsed?.value)
        assertEquals(ref.token, parsed?.token)
    }

    @Test
    fun `constructing from a malformed string throws rather than substituting a default`() {
        val thrown = runCatching { SimRef("sim:v1:not-hex").value }.exceptionOrNull()

        assertTrue("a malformed ref must not become a value object", thrown is IllegalArgumentException)
    }

    // ── Resolution against the live inventory ────────────────────────────────

    @Test
    fun `a ref resolves to the active subscription that produced it`() {
        val p = provider()
        val refA = p.simRefFor(5).value

        val result = p.resolveSubscriptionId(refA, activeSubscriptionIds = listOf(5, 9))

        assertEquals(SimRefResolution.Resolved(5, SimRefFormat.parse(refA)!!), result)
    }

    @Test
    fun `a ref absent from the active inventory is unavailable`() {
        val p = provider()
        val refA = p.simRefFor(5).value

        val result = p.resolveSubscriptionId(refA, activeSubscriptionIds = listOf(9))

        assertTrue(result is SimRefResolution.Unavailable)
    }

    @Test
    fun `slot replacement cannot resolve to the new sim that took the old slot`() {
        // THE mandatory regression. Inventory 1: subId=5 in slot 0, ref A, preference A.
        // Inventory 2: subId=9 in slot 0 (the card was swapped), ref B.
        val p = provider()
        val refA = p.simRefFor(5).value
        val refB = p.simRefFor(9).value
        assertNotEquals("a swapped card must not reuse the old ref", refA, refB)

        val result = p.resolveSubscriptionId(refA, activeSubscriptionIds = listOf(9))

        assertTrue(
            "a slot-based fallback would have resolved to 9; identity must not work that way",
            result is SimRefResolution.Unavailable
        )
        assertEquals(refB, p.simRefFor(9).value)
    }

    @Test
    fun `an unreadable inventory is not reported as an unavailable sim`() {
        val p = provider()
        val refA = p.simRefFor(5).value

        val result = p.resolveSubscriptionId(
            refA,
            activeSubscriptionIds = emptyList(),
            inventoryReadable = false
        )

        assertEquals(SimRefResolution.InventoryUnavailable, result)
    }

    @Test
    fun `an empty readable inventory is unavailable, not unreadable`() {
        val p = provider()

        val result = p.resolveSubscriptionId(p.simRefFor(5).value, activeSubscriptionIds = emptyList())

        assertTrue(result is SimRefResolution.Unavailable)
    }

    @Test
    fun `a malformed reference never resolves to any active subscription`() {
        val p = provider()

        val result = p.resolveSubscriptionId("sim:v2:deadbeef", activeSubscriptionIds = listOf(1, 5, 9))

        assertTrue(result is SimRefResolution.Unavailable)
    }

    @Test
    fun `resolution ignores anything that is not the reference`() {
        val p = provider()
        val refA = p.simRefFor(5).value

        // Only the subscription ids are inputs. There is no slot, display name or carrier parameter
        // that could redirect the answer, which is asserted by the signature being what it is.
        assertEquals(
            SimRefResolution.Resolved(5, SimRefFormat.parse(refA)!!),
            p.resolveSubscriptionId(refA, listOf(5))
        )
        assertTrue(
            p.resolveSubscriptionId(refA, listOf(5, 9, 11)) is SimRefResolution.Resolved
        )
    }

    // ── Comparison ───────────────────────────────────────────────────────────

    @Test
    fun `constant time equality matches plain equality`() {
        val a = provider().simRefFor(5).value
        val b = provider().simRefFor(5).value
        val c = provider().simRefFor(9).value

        assertTrue(SimRefFormat.constantTimeEquals(a, b))
        assertFalse(SimRefFormat.constantTimeEquals(a, c))
        assertFalse(SimRefFormat.constantTimeEquals(a, null))
        assertFalse(SimRefFormat.constantTimeEquals(null, c))
        assertTrue(SimRefFormat.constantTimeEquals(null, null))
    }

    @Test
    fun `constant time equality rejects different lengths without throwing`() {
        assertFalse(SimRefFormat.constantTimeEquals("sim:v1:abc", "sim:v1:abcd"))
    }

    // ── Hex formatting ───────────────────────────────────────────────────────

    @Test
    fun `hex keeps leading zeroes so the token length never varies`() {
        assertEquals("000f", SimRefFormat.hex(byteArrayOf(0x00, 0x0F)))
        assertEquals("00", SimRefFormat.hex(byteArrayOf(0x00)))
        assertEquals("ff", SimRefFormat.hex(byteArrayOf(0xFF.toByte())))
        assertEquals("", SimRefFormat.hex(byteArrayOf()))
    }

    @Test
    fun `mac input is domain separated from the bare integer`() {
        assertEquals("subscription:5", SimRefFormat.macInput(5))
        assertNotEquals("5", SimRefFormat.macInput(5))
    }

    // ── Architecture guard: no privileged identifier on the identity path ────

    /**
     * The identity path must not read a privileged identifier.
     *
     * ICCID, IMSI and SIM serial are restricted for ordinary apps and unnecessary here, and — worse
     * than being unavailable — they are *durable across a card move*, so folding one in would make
     * the preference follow the physical card rather than the subscription the user chose. This is a
     * SOURCE guard rather than a runtime one because the failure is a single stray call in a file
     * whose behaviour is otherwise correct, and no behavioural test would notice it.
     *
     * Scoped deliberately to the files that mint or model SIM identity, so unrelated legacy code
     * elsewhere cannot make this fail for reasons that have nothing to do with the new path.
     */
    @Test
    fun `the sim identity path reads no iccid, imsi or sim serial`() {
        // Built from fragments so this test's own source does not contain the call names it forbids
        // (an `assertFalse(source.contains(x))` would otherwise have to exclude itself).
        val forbiddenCalls = listOf(
            "get" + "IccId",
            "get" + "SimSerialNumber",
            "get" + "SubscriberId"
        )
        val identityFiles = listOf(
            "app/src/main/java/com/autonomousone/messages/messaging/SimRef.kt",
            "app/src/main/java/com/autonomousone/messages/messaging/SimRefSecret.kt",
            "app/src/main/java/com/autonomousone/messages/messaging/SimManager.kt"
        )

        val checked = mutableListOf<String>()
        for (relative in identityFiles) {
            val file = findRepoFile(relative) ?: continue
            checked += relative
            val source = file.readText()
            for (call in forbiddenCalls) {
                assertFalse(
                    "$relative must not CALL $call on the SIM identity path",
                    source.contains(call)
                )
            }
        }
        // If the sources were not found the guard proved nothing, which must not read as success.
        assertEquals(
            "identity sources not located from the test working directory",
            identityFiles.size,
            checked.size
        )
    }

    /** Walk up from the test working directory to the repository root. */
    private fun findRepoFile(relative: String): java.io.File? {
        var dir: java.io.File? = java.io.File(".").absoluteFile
        while (dir != null) {
            val candidate = java.io.File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        return null
    }
}
