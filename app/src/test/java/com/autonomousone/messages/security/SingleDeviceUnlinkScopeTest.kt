package com.autonomousone.messages.security

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * LDV-004 PR-E (slice 2d) — [SingleDeviceUnlinkScope], the "Unlink device" hook for ONE
 * AI device.
 *
 * Plain JVM test: the pause port, the row-delete port and the revocation write are all
 * injected lambdas, so the ORDER (`pause ▸ revoke ▸ forget`), the AI-vs-WEB_PWA split and the
 * "a failed pause never aborts the unlink" rule are proven without Android, Room or a device.
 */
class SingleDeviceUnlinkScopeTest {

    private val deviceId = "ai-1"
    private val publicKey = ByteArray(ConversationGrantSchema.P256_PUBLIC_KEY_BYTES) { index ->
        if (index == 0) ConversationGrantSchema.P256_UNCOMPRESSED_PREFIX else index.toByte()
    }
    private val device = GrantTarget(deviceId, publicKey)

    private fun revokedState() = ScopeState(
        scopeSequence = 2L,
        revoked = true,
        updatedAt = 1_000L,
        selection = null,
    )

    private fun pausedResult(tombstones: Int = 1): ScopeRevokeResult.Revoked =
        ScopeRevokeResult.Revoked(
            state = revokedState(),
            published = List(tombstones) { index ->
                PublishedGrant(
                    grantId = "tombstone-$index",
                    kind = GrantKind.REVOKE,
                    scopeMode = null,
                    conversationId = "c-$index",
                    scopeSequence = 2L,
                )
            },
        )

    // ── AI device: pause, revoke, forget ────────────────────────────────────

    @Test
    fun `an AI device is paused once before revocation and its rows are deleted after`() {
        val order = mutableListOf<String>()
        val pauseCalls = mutableListOf<Pair<String, GrantRevokeReason>>()
        val scope = SingleDeviceUnlinkScope(
            pauseAll = { target, reason ->
                order += "pause"
                pauseCalls += target.deviceId to reason
                pausedResult(tombstones = 2)
            },
            deleteScopeRows = { id ->
                order += "delete:$id"
            },
        )

        val outcome = runBlocking {
            scope.run(device, isAiDevice = true) {
                order += "revoke"
                7L
            }
        }

        assertEquals(
            "the scope is withdrawn before the trust-registry write and forgotten after it",
            listOf("pause", "revoke", "delete:$deviceId"),
            order,
        )
        assertEquals(listOf(deviceId to GrantRevokeReason.UNLINKED), pauseCalls)
        assertEquals(
            SingleDeviceUnlinkScope.Outcome(
                isAiDevice = true,
                pauseOutcome = SingleDeviceUnlinkScope.PauseOutcome.PAUSED,
                tombstonesEnqueued = 2,
                revocationSequence = 7L,
                scopeRowsCleared = true,
            ),
            outcome,
        )
    }

    @Test
    fun `already revoked and never granted are reported honestly`() {
        val cases = mapOf(
            ScopeRevokeResult.AlreadyRevoked to SingleDeviceUnlinkScope.PauseOutcome.ALREADY_REVOKED,
            ScopeRevokeResult.NotGranted to SingleDeviceUnlinkScope.PauseOutcome.NOT_GRANTED,
        )
        for ((pauseResult, expected) in cases) {
            var deleted = false
            val outcome = runBlocking {
                SingleDeviceUnlinkScope(
                    pauseAll = { _, _ -> pauseResult },
                    deleteScopeRows = { deleted = true },
                ).run(device, isAiDevice = true) { 9L }
            }

            assertEquals("result=$pauseResult", expected, outcome.pauseOutcome)
            assertEquals("result=$pauseResult", 0, outcome.tombstonesEnqueued)
            assertEquals("result=$pauseResult", 9L, outcome.revocationSequence)
            assertTrue("result=$pauseResult", deleted)
        }
    }

    // ── WEB_PWA: no scope operations at all ─────────────────────────────────

    @Test
    fun `a WEB_PWA device is revoked with no pause and no row delete`() {
        var pauseCalled = false
        var revokeCalled = false
        var deleteCalled = false
        val scope = SingleDeviceUnlinkScope(
            pauseAll = { _, _ ->
                pauseCalled = true
                pausedResult()
            },
            deleteScopeRows = { deleteCalled = true },
        )

        val outcome = runBlocking {
            scope.run(device, isAiDevice = false) {
                revokeCalled = true
                3L
            }
        }

        assertTrue("the browser's existing revoke path still runs", revokeCalled)
        assertFalse("a WEB_PWA can never hold a conversation grant", pauseCalled)
        assertFalse("no scope row exists for a browser grant", deleteCalled)
        assertEquals(
            SingleDeviceUnlinkScope.Outcome(
                isAiDevice = false,
                pauseOutcome = SingleDeviceUnlinkScope.PauseOutcome.NOT_APPLICABLE,
                tombstonesEnqueued = 0,
                revocationSequence = 3L,
                scopeRowsCleared = false,
            ),
            outcome,
        )
    }

    // ── Failure isolation ───────────────────────────────────────────────────

    @Test
    fun `a failed pause still revokes and still deletes the rows`() {
        val warnings = mutableListOf<String>()
        var revoked = false
        var deleted = false
        val scope = SingleDeviceUnlinkScope(
            pauseAll = { _, _ -> ScopeRevokeResult.Failed(ScopeApplyStage.OUTBOX) },
            deleteScopeRows = { deleted = true },
            onWarning = { message, _ -> warnings += message },
        )

        val outcome = runBlocking {
            scope.run(device, isAiDevice = true) {
                revoked = true
                11L
            }
        }

        assertTrue("the device-level revocation is the primary action", revoked)
        assertTrue("a leftover scope row is inert after the revocation", deleted)
        assertEquals(SingleDeviceUnlinkScope.PauseOutcome.FAILED, outcome.pauseOutcome)
        assertEquals(11L, outcome.revocationSequence)
        assertTrue(outcome.scopeRowsCleared)
        assertEquals(1, warnings.size)
        assertTrue("the warning names the device", warnings.single().contains(deviceId))
        assertTrue("and the typed stage", warnings.single().contains(ScopeApplyStage.OUTBOX.name))
    }

    @Test
    fun `a throwing pause port is logged and never aborts the unlink`() {
        val warnings = mutableListOf<String>()
        var revoked = false
        var deleted = false
        val scope = SingleDeviceUnlinkScope(
            pauseAll = { _, _ -> throw IllegalStateException("coordinator wiring gone") },
            deleteScopeRows = { deleted = true },
            onWarning = { message, error -> warnings += "$message:${error?.message}" },
        )

        val outcome = runBlocking {
            scope.run(device, isAiDevice = true) {
                revoked = true
                4L
            }
        }

        assertTrue(revoked)
        assertTrue(deleted)
        assertEquals(SingleDeviceUnlinkScope.PauseOutcome.FAILED, outcome.pauseOutcome)
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains(deviceId))
        assertTrue(warnings.single().contains("coordinator wiring gone"))
        assertFalse("no conversation id may be logged", warnings.single().contains("c-"))
    }

    @Test
    fun `a failed row delete is warned but the unlink still succeeds`() {
        val warnings = mutableListOf<String>()
        val scope = SingleDeviceUnlinkScope(
            pauseAll = { _, _ -> pausedResult() },
            deleteScopeRows = { throw IllegalStateException("database gone") },
            onWarning = { message, _ -> warnings += message },
        )

        val outcome = runBlocking { scope.run(device, isAiDevice = true) { 5L } }

        assertFalse(outcome.scopeRowsCleared)
        assertEquals(5L, outcome.revocationSequence)
        assertEquals(SingleDeviceUnlinkScope.PauseOutcome.PAUSED, outcome.pauseOutcome)
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("not cleared"))
    }

    @Test
    fun `a failed revocation propagates and never forgets the still-trusted device's rows`() {
        var deleted = false
        val scope = SingleDeviceUnlinkScope(
            pauseAll = { _, _ -> pausedResult() },
            deleteScopeRows = { deleted = true },
        )

        try {
            runBlocking {
                scope.run(device, isAiDevice = true) {
                    throw IllegalStateException("trust statement write failed")
                }
            }
            fail("the revocation failure must reach the caller")
        } catch (expected: IllegalStateException) {
            assertEquals("trust statement write failed", expected.message)
        }
        assertFalse("the device is still trusted, so its scope rows stay", deleted)
    }
}
