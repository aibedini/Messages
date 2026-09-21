package com.autonomousone.messages.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The legacy two-URL configuration folding into ONE origin.
 *
 * The case that matters most is the one that looks like nothing: an install where the user
 * configured only `gmwebUrl` and `cloud_backend_url` was NEVER written. The old getter
 * returned the domain compiled into the APK for that key, so a migration that asked for "the
 * backend URL" would adopt a server the user never chose. `storedBackendUrl = null` is how the
 * caller says "never written", and these tests pin that it is treated as absence.
 */
class GmwebServerMigrationTest {

    private fun decide(
        legacyGmwebUrl: String? = null,
        storedBackendUrl: String? = null,
        alreadyMigrated: Boolean = false
    ) = GmwebServerMigration.decide(
        GmwebServerMigration.StoredConfig(
            legacyGmwebUrl = legacyGmwebUrl,
            storedBackendUrl = storedBackendUrl,
            alreadyMigrated = alreadyMigrated
        )
    )

    // ═══════════════════════════════════════════════════════════════════════════
    // Priority
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `anExplicitlyStoredGmwebUrlIsMigrated`() {
        val decision = decide(legacyGmwebUrl = "https://gmweb.okgfx.ir")

        assertEquals("https://gmweb.okgfx.ir", decision.origin)
        assertTrue(decision.hasOriginToWrite)
    }

    @Test
    fun `aPanelUrlIsMigratedToItsOrigin`() {
        assertEquals(
            "https://gmweb.okgfx.ir",
            decide(legacyGmwebUrl = "https://gmweb.okgfx.ir/app").origin
        )
        assertEquals(
            "https://gmweb.okgfx.ir",
            decide(legacyGmwebUrl = "https://gmweb.okgfx.ir/").origin
        )
    }

    @Test
    fun `gmwebUrlWinsOverBackendUrl`() {
        val decision = decide(
            legacyGmwebUrl = "https://gmweb.okgfx.ir",
            storedBackendUrl = "https://old.example.com"
        )

        assertEquals("https://gmweb.okgfx.ir", decision.origin)
    }

    @Test
    fun `anExplicitlyStoredBackendUrlIsUsedWhenNoGmwebUrlExists`() {
        val decision = decide(storedBackendUrl = "https://legacy.example.com")

        assertEquals("https://legacy.example.com", decision.origin)
    }

    @Test
    fun `nothingConfiguredMigratesToNothingRatherThanToADefault`() {
        val decision = decide()

        assertNull(decision.origin)
        assertFalse(decision.hasOriginToWrite)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // The baked historical default must NOT be inherited
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * `storedBackendUrl = null` means the key was never written, which is exactly the install
     * population that used to receive the compiled-in domain through the getter's fallback.
     * The migration must leave the app unconfigured instead.
     */
    @Test
    fun `aNeverWrittenBackendKeyIsNotMigrated`() {
        val decision = decide(legacyGmwebUrl = null, storedBackendUrl = null)

        assertNull(decision.origin)
        assertNull(
            "the historical baked domain must never be adopted as a user choice",
            decision.origin
        )
    }

    @Test
    fun `aSchemeLessLegacyValueIsSkippedRatherThanGuessedAt`() {
        // The historical baked value had no scheme either. Even if some older build HAD
        // persisted a hostname, it is only adopted when it is a valid https origin — and it is
        // never resurrected from BuildConfig by this code.
        val decision = decide(storedBackendUrl = "gmweb.example.com")

        assertNull("a scheme-less legacy value is skipped, not guessed at", decision.origin)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Idempotency and unusable legacy values
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `aCompletedMigrationWritesNothingAgain`() {
        val decision = decide(
            legacyGmwebUrl = "https://gmweb.okgfx.ir",
            alreadyMigrated = true
        )

        assertNull("a rollback that rewrites the legacy key must not re-run the migration", decision.origin)
        assertFalse(decision.hasOriginToWrite)
    }

    @Test
    fun `anUnusableGmwebUrlFallsThroughToTheBackendValue`() {
        // http, a stray path, a typo — a migration must not install an address the current
        // build would reject if the user typed it.
        val decision = decide(
            legacyGmwebUrl = "http://gmweb.okgfx.ir",
            storedBackendUrl = "https://gmweb.okgfx.ir"
        )

        assertEquals("https://gmweb.okgfx.ir", decision.origin)
    }

    @Test
    fun `everyUnusableCombinationLeavesTheAppUnconfigured`() {
        listOf(
            "http://gmweb.okgfx.ir",
            "gmweb.okgfx.ir",
            "",
            "   ",
            "https://gmweb.okgfx.ir/some/unknown/path"
        ).forEach { bad ->
            assertNull("<$bad> must not be adopted", decide(legacyGmwebUrl = bad).origin)
        }
    }

    @Test
    fun `blankLegacyValuesAreTreatedAsAbsent`() {
        val decision = decide(legacyGmwebUrl = "", storedBackendUrl = "   ")

        assertNull(decision.origin)
    }

    @Test
    fun `migratingTwiceFromTheSameInputGivesTheSameAnswer`() {
        val first = decide(legacyGmwebUrl = "https://gmweb.okgfx.ir/app")
        val second = decide(legacyGmwebUrl = "https://gmweb.okgfx.ir/app")

        assertEquals(first.origin, second.origin)
        // …and the second run of a COMPLETED migration is a no-op.
        assertNull(decide(legacyGmwebUrl = "https://gmweb.okgfx.ir", alreadyMigrated = true).origin)
    }
}
