package com.autonomousone.messages.sync

import com.autonomousone.messages.data.RemoteCommandEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The command drain's decision (mission §46/§47/§48), and in particular the one case where it must
 * refuse to work: a `SEND_SMS` that was claimed and then interrupted.
 *
 * The invariant under test is absolute (mission §78): *no remotely requested send may ever cause the
 * same SMS to be sent twice.* Everything else here is about not lying in the other direction —
 * never calling a command "interrupted" when it provably never ran.
 */
class CommandDrainPolicyTest {

    private val now = 1_700_000_000_000L

    private fun cmd(
        type: String = "SEND_SMS",
        state: String = RemoteCommandEntity.STATE_RECEIVED,
        attemptCount: Int = 0,
        leaseExpiresAt: Long? = null,
        expiresAt: Long = 0L,
        commandId: String = "cmd-1",
        senderDeviceId: String = "web-device-1",
    ) = RemoteCommandEntity(
        commandId = commandId,
        type = type,
        ciphertext = ByteArray(0),
        encoding = "application/json",
        schemaVersion = 1,
        receivedAt = now - 10_000,
        idempotencyKey = "idem-$commandId",
        state = state,
        attemptCount = attemptCount,
        leaseExpiresAt = leaseExpiresAt,
        expiresAt = expiresAt,
        senderDeviceId = senderDeviceId,
    )

    private fun decide(command: RemoteCommandEntity) = CommandDrainPolicy.decide(
        type = command.type,
        state = command.state,
        attemptCount = command.attemptCount,
        leaseExpiresAt = command.leaseExpiresAt,
        expiresAt = command.expiresAt,
        now = now,
    )

    // ── §78: the case that must never re-run ─────────────────────────────────

    @Test
    fun `aClaimedThenInterruptedSendIsNeverReRun`() {
        // Exactly what the row looks like after reclaimExpiredLeases put an abandoned EXECUTING row
        // back to RECEIVED: no lease at all, indistinguishable from a pristine row BY STATE, and
        // distinguishable by attemptCount. Re-running it is the duplicate send §78 forbids.
        val interrupted = cmd(
            type = "SEND_SMS",
            state = RemoteCommandEntity.STATE_RECEIVED,
            attemptCount = 1,
        )

        val decision = decide(interrupted)

        assertEquals(
            DrainDecision.Resolve(
                RemoteCommandEntity.STATE_FAILED,
                SyncErrorCode.COMMAND_INTERRUPTED_AFTER_SUBMIT
            ),
            decision
        )
    }

    @Test
    fun `anInterruptedSendIsResolvedWithANonTransientCode`() {
        // The code carries the policy. If it were transient, the retry machinery would be entitled
        // to run the command again — an automatic duplicate send, which is the whole thing §78
        // forbids. So "never retry this" has to be enforced by the taxonomy, not by a comment.
        assertFalse(SyncErrorCode.COMMAND_INTERRUPTED_AFTER_SUBMIT.isTransient)
    }

    @Test
    fun `anExpiredLeaseOnAnInterruptedSendIsNeverReRun`() {
        val abandoned = cmd(
            type = "SEND_SMS",
            state = RemoteCommandEntity.STATE_EXECUTING,
            attemptCount = 1,
            leaseExpiresAt = now - 1,
        )

        assertEquals(
            DrainDecision.Resolve(
                RemoteCommandEntity.STATE_FAILED,
                SyncErrorCode.COMMAND_INTERRUPTED_AFTER_SUBMIT
            ),
            decide(abandoned)
        )
    }

    @Test
    fun `anUnknownTypeThatWasClaimedIsNeverReRun`() {
        // Fail-safe default: a type nobody has reasoned about cannot claim idempotence, so an
        // interrupted one is resolved rather than re-run. Silence here would be a duplicate.
        val abandoned = cmd(
            type = "SOME_FUTURE_COMMAND",
            state = RemoteCommandEntity.STATE_ACCEPTED,
            attemptCount = 1,
            leaseExpiresAt = now - 1,
        )

        assertEquals(
            DrainDecision.Resolve(
                RemoteCommandEntity.STATE_FAILED,
                SyncErrorCode.COMMAND_INTERRUPTED_AFTER_SUBMIT
            ),
            decide(abandoned)
        )
    }

    // ── The work the drain is actually allowed to do ─────────────────────────

    @Test
    fun `aCommandNobodyEverClaimedIsDriven`() {
        // The crash window between ingest and execute. Nothing was handed to the radio, so running
        // it is both safe and required — otherwise §46's "nothing stays non-terminal" is unmet.
        assertEquals(DrainDecision.Drive, decide(cmd(attemptCount = 0)))
    }

    @Test
    fun `anInterruptedIdempotentCommandIsDrivenAgain`() {
        // Marking a thread read twice changes nothing, which is the only reason re-running is
        // allowed at all.
        val interrupted = cmd(
            type = "MARK_THREAD_READ",
            state = RemoteCommandEntity.STATE_RECEIVED,
            attemptCount = 2,
        )

        assertEquals(DrainDecision.ReclaimAndDrive, decide(interrupted))
    }

    @Test
    fun `aThreadReadIsTheOnlyReDrivableType`() {
        assertEquals(setOf("MARK_THREAD_READ"), CommandDrainPolicy.RE_DRIVABLE_TYPES)
        assertTrue("SEND_SMS" !in CommandDrainPolicy.RE_DRIVABLE_TYPES)
    }

    // ── A live owner is never raced ──────────────────────────────────────────

    @Test
    fun `aCommandWithALiveLeaseIsLeftAlone`() {
        val running = cmd(
            state = RemoteCommandEntity.STATE_EXECUTING,
            attemptCount = 1,
            leaseExpiresAt = now + 60_000,
        )

        assertEquals(DrainDecision.Leave, decide(running))
    }

    @Test
    fun `aLeaseExpiringExactlyNowStillCounts`() {
        // Boundary: the DAO's reclaim predicate is `leaseExpiresAt < now`, so `== now` is NOT yet
        // expired and the drain must agree with it. Disagreeing would hand the row to a second
        // executor while the first still holds it.
        val boundary = cmd(
            state = RemoteCommandEntity.STATE_ACCEPTED,
            attemptCount = 1,
            leaseExpiresAt = now,
        )

        assertEquals(DrainDecision.Leave, decide(boundary))
    }

    @Test
    fun `aClaimedCommandWithNoLeaseTimestampIsLeftAlone`() {
        // Unreachable through the DAO (a claim always sets a lease), but if the column were ever
        // null we cannot tell whether it is live, and guessing "abandoned" would re-run a send.
        val noLease = cmd(state = RemoteCommandEntity.STATE_EXECUTING, attemptCount = 1, leaseExpiresAt = null)

        assertEquals(DrainDecision.Leave, decide(noLease))
    }

    // ── Expiry, before anything else ─────────────────────────────────────────

    @Test
    fun `aCommandPastItsExpiryThatNobodyClaimedIsExpired`() {
        val stale = cmd(attemptCount = 0, expiresAt = now - 1)

        assertEquals(
            DrainDecision.Resolve(
                RemoteCommandEntity.STATE_EXPIRED,
                SyncErrorCode.COMMAND_EXPIRED_BEFORE_CLAIM
            ),
            decide(stale)
        )
    }

    @Test
    fun `aClaimedCommandPastItsExpiryIsNotReportedAsNeverExecuted`() {
        // The discriminator. `attemptCount > 0` means somebody claimed it, so "expired before
        // claim" would be a false reassurance: it may well have reached the radio.
        val claimedAndStale = cmd(attemptCount = 1, expiresAt = now - 1)

        assertEquals(
            DrainDecision.Resolve(
                RemoteCommandEntity.STATE_FAILED,
                SyncErrorCode.COMMAND_INTERRUPTED_AFTER_SUBMIT
            ),
            decide(claimedAndStale)
        )
    }

    @Test
    fun `expiryOfZeroMeansNoExpiry`() {
        // GMweb's older rows carry 0, which must not read as "expired at the epoch".
        assertEquals(DrainDecision.Drive, decide(cmd(attemptCount = 0, expiresAt = 0L)))
    }

    // ── Unsupported and terminal rows ────────────────────────────────────────

    @Test
    fun `aTypeThisTransportCannotRunIsReportedNotParked`() {
        // A row left RECEIVED forever is a web bubble that spins forever with nothing working on
        // it. Reporting FAILED is the honest outcome; the device genuinely does not know the type.
        val unsupported = cmd(type = "SOMETHING_NEW", attemptCount = 0)

        assertEquals(
            DrainDecision.Resolve(RemoteCommandEntity.STATE_FAILED, SyncErrorCode.UNKNOWN),
            decide(unsupported)
        )
    }

    @Test
    fun `aTerminalCommandIsLeftAlone`() {
        for (state in RemoteCommandEntity.TERMINAL_STATES) {
            assertEquals(state, DrainDecision.Leave, decide(cmd(state = state, attemptCount = 1)))
        }
    }

    @Test
    fun `anUnrecognisedStateIsLeftAlone`() {
        assertEquals(DrainDecision.Leave, decide(cmd(state = "SOMETHING_ELSE", attemptCount = 1)))
        // Even when it looks drive-able, a blank state is not a licence to execute.
        assertEquals(DrainDecision.Leave, decide(cmd(state = "", attemptCount = 0)))
    }

    // ── The pass as a whole ──────────────────────────────────────────────────

    @Test
    fun `noRowIsEverPlannedIntoTwoGroups`() {
        val batch = listOf(
            cmd(commandId = "a", state = RemoteCommandEntity.STATE_RECEIVED, attemptCount = 0),
            cmd(commandId = "b", state = RemoteCommandEntity.STATE_RECEIVED, attemptCount = 1),
            cmd(
                commandId = "c", state = RemoteCommandEntity.STATE_EXECUTING,
                attemptCount = 1, leaseExpiresAt = now + 1
            ),
            cmd(
                commandId = "d", state = RemoteCommandEntity.STATE_EXECUTING,
                attemptCount = 1, leaseExpiresAt = now - 1
            ),
            cmd(commandId = "e", state = RemoteCommandEntity.STATE_COMPLETED, attemptCount = 1),
            cmd(commandId = "f", type = "MARK_THREAD_READ", state = RemoteCommandEntity.STATE_RECEIVED, attemptCount = 1),
            cmd(commandId = "g", type = "WHATEVER", state = RemoteCommandEntity.STATE_RECEIVED, attemptCount = 0),
        )

        val plan = CommandDrainPolicy.plan(batch, now)
        val placed = plan.drive.map { it.commandId } +
            plan.resolutions.map { it.command.commandId } +
            plan.leave.map { it.commandId }

        assertEquals("every row is classified exactly once", batch.size, placed.size)
        assertEquals("and only once each", batch.size, placed.toSet().size)
    }

    @Test
    fun `aDrainNeverDrivesASendThatWasEverClaimed`() {
        // The property, stated over the whole space rather than one example: no combination of
        // state and lease may put a claimed SEND_SMS into the drive group.
        val states = RemoteCommandEntity.NON_TERMINAL_STATES
        val leases = listOf(null, now - 1, now, now + 1)
        val expiries = listOf(0L, now - 1, now + 1)

        for (state in states) {
            for (lease in leases) {
                for (expiresAt in expiries) {
                    val command = cmd(
                        type = "SEND_SMS",
                        state = state,
                        attemptCount = 1,
                        leaseExpiresAt = lease,
                        expiresAt = expiresAt,
                    )
                    val plan = CommandDrainPolicy.plan(listOf(command), now)
                    assertTrue(
                        "claimed SEND_SMS must never be driven: state=$state lease=$lease expires=$expiresAt",
                        plan.drive.isEmpty()
                    )
                }
            }
        }
    }

    // ── A locally-queued row is not a remote command ─────────────────────────

    @Test
    fun `aRowThisDeviceQueuedForItselfIsNeverDriven`() {
        // The same table is the durable idempotency ledger for EVERY send, including the ones this
        // device queues for itself (the local REST gateway, the composer's durable path). Those rows
        // carry the local device id as their sender, and the drain must leave them alone: it would
        // otherwise execute — or fail, or ACK back to GMweb — a request nobody made.
        val local = cmd(
            state = RemoteCommandEntity.STATE_RECEIVED,
            attemptCount = 0,
            senderDeviceId = CommandDrainPolicy.LOCAL_SOURCE_DEVICE_ID,
        )

        val plan = CommandDrainPolicy.plan(listOf(local), now)

        assertEquals("nothing may be driven", 0, plan.drive.size)
        assertEquals("and nothing may be reported to the control plane", 0, plan.resolutions.size)
        assertEquals(listOf("cmd-1"), plan.leave.map { it.commandId })
    }

    @Test
    fun `aClaimedLocalRowIsNotResolvedAsAnInterruptedRemoteSend`() {
        // The dangerous case: the local send crashed between claim and completion, so the row looks
        // exactly like an interrupted remote send. Reporting it to GMweb would invent a command, and a
        // future executor that accepted these rows would hand the same SMS to the radio again (§78).
        val local = cmd(
            state = RemoteCommandEntity.STATE_EXECUTING,
            attemptCount = 1,
            leaseExpiresAt = now - 1,
            senderDeviceId = CommandDrainPolicy.LOCAL_SOURCE_DEVICE_ID,
        )

        val plan = CommandDrainPolicy.plan(listOf(local), now)

        assertEquals(0, plan.drive.size)
        assertEquals(0, plan.resolutions.size)
        assertEquals(1, plan.leave.size)
    }

    @Test
    fun `theLocalMarkerMatchesTheOneTheEnqueuePathWrites`() {
        // Two constants in two packages for one fact is a drift risk, so the value is pinned. The
        // enqueue path DROPPED this field entirely before, which is why the marker had to be added
        // there as well as honoured here.
        assertEquals(
            com.autonomousone.messages.sms.GatewayOutgoingPipeline.LOCAL_SOURCE_DEVICE_ID,
            CommandDrainPolicy.LOCAL_SOURCE_DEVICE_ID
        )
    }

    @Test
    fun `aRemoteRowIsStillDrainedAsBefore`() {
        // The negative control for the two tests above: with a web sender the policy behaves exactly as
        // it did, so the local-origin rule cannot silently disable the whole drain.
        val remote = cmd(state = RemoteCommandEntity.STATE_RECEIVED, attemptCount = 0)

        val plan = CommandDrainPolicy.plan(listOf(remote), now)

        assertEquals(listOf("cmd-1"), plan.drive.map { it.commandId })
        assertEquals(0, plan.leave.size)
    }

    @Test
    fun `everyResolutionNamesATerminalState`() {
        for (code in listOf(
            SyncErrorCode.COMMAND_INTERRUPTED_AFTER_SUBMIT,
            SyncErrorCode.COMMAND_EXPIRED_BEFORE_CLAIM
        )) {
            assertFalse("a resolution must never be retryable: $code", code.isTransient)
        }
        assertTrue(RemoteCommandEntity.STATE_FAILED in RemoteCommandEntity.TERMINAL_STATES)
        assertTrue(RemoteCommandEntity.STATE_EXPIRED in RemoteCommandEntity.TERMINAL_STATES)
    }
}
