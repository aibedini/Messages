package com.autonomousone.messages.sync

import com.autonomousone.messages.data.RemoteCommandEntity

/**
 * What a drain must do with one non-terminal command row (mission §46/§47/§48).
 *
 * The whole reason this is a separate, pure decision is the interaction between two requirements
 * that pull in opposite directions:
 *
 *  - §46: a command that was ingested but never resolved must be driven to a terminal state,
 *    because a non-terminal row means GMweb's ledger never resolves.
 *  - §78: no remotely requested `SEND_SMS` may ever cause the same SMS to be sent twice.
 *
 * The tempting implementation of §46 — "requeue anything that is not terminal and run it again" —
 * breaks §78 outright for a `SEND_SMS` that was claimed and then interrupted, because after the
 * hand-off the row looks exactly like a row that was never run. So the drain does not re-run
 * commands; it **resolves** them, and the only case it may re-run is one where re-running is
 * idempotent by nature.
 */
sealed interface DrainDecision {

    /** Never claimed and not expired: consumed by nothing, safe to execute. */
    data object Drive : DrainDecision

    /**
     * Claimed, abandoned, and idempotent by nature: safe to execute again.
     *
     * Only ever reached for [CommandDrainPolicy.RE_DRIVABLE_TYPES]. The caller must still go
     * through the normal atomic claim, so a live owner can never be raced.
     */
    data object ReclaimAndDrive : DrainDecision

    /**
     * Terminal, with the reason recorded. Must NOT be executed.
     *
     * [state] is a [RemoteCommandEntity] terminal state and [code] the structured reason.
     */
    data class Resolve(val state: String, val code: SyncErrorCode) : DrainDecision

    /** A live lease (or an unrecognised state) owns this row. Hands off. */
    data object Leave : DrainDecision
}

/** One row that must reach a terminal state, with the reason to record. */
data class CommandResolution(
    val command: RemoteCommandEntity,
    val state: String,
    val code: SyncErrorCode,
)

/** The full outcome of planning a drain pass: three disjoint groups, no row in two of them. */
data class DrainPlan(
    val drive: List<RemoteCommandEntity>,
    val resolutions: List<CommandResolution>,
    val leave: List<RemoteCommandEntity>,
) {
    /** True when there is nothing for the drain to do. */
    val isEmpty: Boolean get() = drive.isEmpty() && resolutions.isEmpty()
}

/**
 * The single rule for "may this command row run again?" (mission §46/§47/§48/§78).
 *
 * Pure and Android-free on purpose: the answer to that question is the difference between one SMS
 * and two, so it has to be testable directly rather than inferred from a device log.
 */
object CommandDrainPolicy {

    /**
     * Command types whose execution is idempotent, so a re-run cannot cause a second side effect.
     *
     * `MARK_THREAD_READ` sets a flag that is already set. `SEND_SMS` is absent and must stay
     * absent: it is the one command whose second run is a second billable SMS. An unknown or future
     * type is also absent, so the default is "never re-run" — the fail-safe direction, because an
     * unlisted type has not been reasoned about and cannot claim idempotence.
     */
    val RE_DRIVABLE_TYPES: Set<String> = setOf("MARK_THREAD_READ")

    /**
     * Command types this transport knows how to run at all.
     *
     * A command of any other type cannot be driven and must not be left non-terminal: a row stuck
     * in `RECEIVED` forever makes GMweb's optimistic bubble spin forever with nothing on the device
     * working on it. It is reported as a failure with `UNKNOWN` instead, which is the honest code —
     * the device genuinely does not know what the command means.
     */
    val EXECUTABLE_TYPES: Set<String> = setOf("SEND_SMS", "MARK_THREAD_READ")

    /**
     * Decide one row.
     *
     * [attemptCount] carries the load-bearing distinction that `state` alone cannot: it counts
     * claims (the DAO's `markAcceptedIfReceived` increments it), and the DAO's
     * `reclaimExpiredLeases` puts an abandoned row back to `RECEIVED`. So a row that was claimed
     * and then reclaimed is indistinguishable from a pristine one by state, and completely
     * distinguishable by `attemptCount == 0`.
     */
    fun decide(
        type: String,
        state: String,
        attemptCount: Int,
        leaseExpiresAt: Long?,
        expiresAt: Long,
        now: Long,
    ): DrainDecision {
        // Unknown or terminal states are left alone rather than guessed at: acting on a state this
        // policy does not model is how a drain becomes a second executor.
        if (state !in RemoteCommandEntity.NON_TERMINAL_STATES) {
            return DrainDecision.Leave
        }

        // Expiry is checked BEFORE anything is driven, and only for a row nobody ever claimed:
        // `attemptCount == 0` is what makes "provably never executed" true.
        if (attemptCount == 0 && expiresAt in 1 until now) {
            return DrainDecision.Resolve(
                RemoteCommandEntity.STATE_EXPIRED,
                SyncErrorCode.COMMAND_EXPIRED_BEFORE_CLAIM
            )
        }

        val abandoned = when (state) {
            RemoteCommandEntity.STATE_RECEIVED ->
                // Reclaimed rows land here without a lease, so state cannot answer "was this ever
                // started?" — attemptCount does.
                attemptCount > 0
            RemoteCommandEntity.STATE_ACCEPTED,
            RemoteCommandEntity.STATE_EXECUTING ->
                leaseExpiresAt != null && leaseExpiresAt < now
            else -> false
        }

        if (!abandoned) {
            // RECEIVED with no attempt: nothing has touched it, so running it is the drain's job —
            // but only if this transport understands the type.
            return if (state == RemoteCommandEntity.STATE_RECEIVED) {
                if (type in EXECUTABLE_TYPES) {
                    DrainDecision.Drive
                } else {
                    DrainDecision.Resolve(RemoteCommandEntity.STATE_FAILED, SyncErrorCode.UNKNOWN)
                }
            } else {
                DrainDecision.Leave
            }
        }

        return if (type in RE_DRIVABLE_TYPES) {
            DrainDecision.ReclaimAndDrive
        } else {
            // The interrupted-send case. Resolve, never re-run (§78).
            DrainDecision.Resolve(
                RemoteCommandEntity.STATE_FAILED,
                SyncErrorCode.COMMAND_INTERRUPTED_AFTER_SUBMIT
            )
        }
    }

    /** Plan a whole pass over [commands], oldest first as the DAO returns them. */
    fun plan(commands: List<RemoteCommandEntity>, now: Long): DrainPlan {
        val drive = ArrayList<RemoteCommandEntity>()
        val resolutions = ArrayList<CommandResolution>()
        val leave = ArrayList<RemoteCommandEntity>()
        for (command in commands) {
            // A row THIS DEVICE queued for itself is not a remote instruction. It lives in the same
            // table because that table is the durable idempotency ledger for every send, but draining
            // it would mean executing — or failing, or ACKing back to GMweb — a request nobody made.
            // Worse, a row enqueued before its send crashed is indistinguishable from a fresh one by
            // state alone, so draining it could hand the same SMS to the radio a second time (§78).
            //
            // The marker is `senderDeviceId`, which the enqueue path records and which used to be
            // accepted and dropped.
            if (command.senderDeviceId == LOCAL_SOURCE_DEVICE_ID) {
                leave += command
                continue
            }
            when (
                val decision = decide(
                    type = command.type,
                    state = command.state,
                    attemptCount = command.attemptCount,
                    leaseExpiresAt = command.leaseExpiresAt,
                    expiresAt = command.expiresAt,
                    now = now,
                )
            ) {
                DrainDecision.Drive, DrainDecision.ReclaimAndDrive -> drive += command
                is DrainDecision.Resolve -> resolutions += CommandResolution(
                    command = command,
                    state = decision.state,
                    code = decision.code,
                )
                DrainDecision.Leave -> leave += command
            }
        }
        return DrainPlan(drive, resolutions, leave)
    }

    /**
     * The `senderDeviceId` a locally-queued command carries.
     *
     * A local constant rather than a reference to the sms package, so this policy stays free of the
     * send layer and testable without it — and so the value sits next to the rule that depends on it.
     */
    const val LOCAL_SOURCE_DEVICE_ID = "android-local"
}
