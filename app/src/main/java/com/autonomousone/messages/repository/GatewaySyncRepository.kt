package com.autonomousone.messages.repository

import androidx.room.withTransaction
import com.autonomousone.messages.data.DeadLetterBreakdownRow
import com.autonomousone.messages.data.DeadLetterSummary
import com.autonomousone.messages.data.GatewayEventOutboxDao
import com.autonomousone.messages.data.GatewayEventOutboxEntity
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.data.OutboxKeyRefCount
import com.autonomousone.messages.data.RemoteCommandDao
import com.autonomousone.messages.data.RemoteCommandEntity
import com.autonomousone.messages.data.RemoteCommandExecutionDao
import com.autonomousone.messages.data.RemoteCommandExecutionEntity
import com.autonomousone.messages.data.RemoteConversationMapDao
import com.autonomousone.messages.data.RemoteConversationMapEntity
import com.autonomousone.messages.data.SyncCursorDao
import com.autonomousone.messages.data.SyncCursorEntity
import com.autonomousone.messages.sync.HistoryAckCandidate
import com.autonomousone.messages.sync.HistoryAckWalk
import com.autonomousone.messages.sync.SyncErrorCode
import kotlin.math.min
import kotlin.random.Random

/**
 * PR-01 boundary over the durability tables (docs/adr/ADR-001..003). Deliberately
 * dumb: NO crypto, NO networking — upload/poll workers (PR-02/PR-09/PR-10) call
 * these suspend functions. The transactional claim below is the process-death
 * guarantee: a worker that dies between claim and upload leaves the row SENDING,
 * and [recoverSending] flips it back to PENDING on next start (Rule 4 / §16).
 */
class GatewaySyncRepository(
    private val db: MessagesDatabase,
    private val outboxDao: GatewayEventOutboxDao = db.gatewayEventOutboxDao(),
    private val commandDao: RemoteCommandDao = db.remoteCommandDao(),
    private val executionDao: RemoteCommandExecutionDao = db.remoteCommandExecutionDao(),
    private val mapDao: RemoteConversationMapDao = db.remoteConversationMapDao(),
    private val cursorDao: SyncCursorDao = db.syncCursorDao()
) {

    /** LOCK 13 upload policy — pure math so the JVM tests can pin it. */
    object Policy {
        const val MAX_BATCH_EVENTS = 100
        const val MAX_BATCH_BYTES = 512 * 1024

        /**
         * How long a lease may be held before the row is presumed abandoned (mission §12).
         *
         * Five minutes, matching the mission's suggestion. Long enough that a slow upload over a
         * poor connection is never reclaimed mid-flight, short enough that a process death does
         * not strand a row for the life of the install.
         */
        const val DEFAULT_LEASE_TIMEOUT_MS = 5 * 60_000L

        /**
         * How long an acknowledged event is kept before cleanup (mission §19).
         *
         * 48 hours: long enough that a support conversation about "did my message sync?" still has
         * the row to look at, short enough that the outbox does not grow without bound. Before
         * this existed the outbox was append-only for the life of the install, and a 360k-message
         * history plus steady traffic grows that table forever (audit Blocker 10).
         */
        const val ACKED_RETENTION_MS = 48 * 60 * 60_000L

        /** Full-jitter exponential backoff: uniform in [0, min(cap, base·2^attempt)). */
        const val BACKOFF_BASE_MS = 2_000L
        const val BACKOFF_CAP_MS = 5 * 60_000L

        /**
         * How much of an execution result is stored (mission §45).
         *
         * The column exists to answer "what happened on attempt N?", not to archive a payload. A
         * bounded, non-secret summary keeps the audit trail from becoming a second copy of the
         * message, and callers pass a reason string, never a body.
         */
        const val MAX_EXECUTION_RESULT_CHARS = 200

        fun backoffDelayMs(attempt: Int, random: Random): Long {
            val ceiling = min(BACKOFF_CAP_MS, BACKOFF_BASE_MS shl attempt.coerceIn(0, 20))
            return if (ceiling <= 1) 0 else random.nextLong(0, ceiling)
        }

        /**
         * A fresh lease/batch identifier.
         *
         * Opaque and random on purpose: it must not be derivable from, or correlated with, any
         * device or message identity — it only answers "which claim owns this row".
         */
        fun newLeaseId(): String =
            "lease_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)

        /**
         * The share of a batch reserved for foreground work when BOTH groups have data
         * (mission §13: "batch size 100 → 70 realtime/high priority, 30 history/background").
         *
         * Without a reservation, a steady stream of realtime events consumes every slot and
         * history never uploads at all — the starvation the audit found (Blocker 11). Unused
         * quota is returned to the other group, so a quiet foreground never wastes slots.
         */
        const val FOREGROUND_SHARE_PERCENT = 70

        /** One upload batch: first [MAX_BATCH_EVENTS] rows up to [MAX_BATCH_BYTES] payload bytes. */
        data class Batch(val events: List<GatewayEventOutboxEntity>, val bytes: Long)

        /**
         * Fair selection across the two priority groups (mission §13).
         *
         * Foreground is placed first in the result so that, when a batch does carry both, the
         * realtime work is submitted ahead of the backfill within that batch.
         */
        fun selectFair(
            foreground: List<GatewayEventOutboxEntity>,
            background: List<GatewayEventOutboxEntity>
        ): Batch {
            if (background.isEmpty()) return takeWithinCaps(foreground)
            if (foreground.isEmpty()) return takeWithinCaps(background)

            val foregroundQuota = MAX_BATCH_EVENTS * FOREGROUND_SHARE_PERCENT / 100
            val backgroundQuota = MAX_BATCH_EVENTS - foregroundQuota
            val acc = Accumulator()
            val foregroundCursor = acc.add(foreground, 0, foregroundQuota)
            val backgroundCursor = acc.add(background, 0, backgroundQuota)
            // Hand back whatever either group did not use, so the batch is not sent half empty.
            // Each call resumes from that group's cursor: the first version restarted at 0 and
            // re-added the same rows, producing a batch with every event in it twice.
            acc.add(foreground, foregroundCursor, MAX_BATCH_EVENTS)
            acc.add(background, backgroundCursor, MAX_BATCH_EVENTS)
            return Batch(acc.events, acc.bytes)
        }

        /**
         * Select from a flat candidate list.
         *
         * Splits by group first, so callers that already have a mixed list still get fair
         * batching. The uploader passes the groups separately (it fetches them separately) so it
         * can bound the candidate window per group.
         */
        fun selectBatch(candidates: List<GatewayEventOutboxEntity>): Batch = selectFair(
            foreground = candidates.filterNot { GatewayEventOutboxEntity.isBackground(it.priority) },
            background = candidates.filter { GatewayEventOutboxEntity.isBackground(it.priority) }
        )

        /** The original rule: in order, up to the count cap and the byte cap. */
        private fun takeWithinCaps(candidates: List<GatewayEventOutboxEntity>): Batch {
            val acc = Accumulator()
            acc.add(candidates, 0, MAX_BATCH_EVENTS)
            return Batch(acc.events, acc.bytes)
        }

        /**
         * Count- and byte-bounded accumulation.
         *
         * [add] resumes from [startIndex] and the number of rows THIS CALL may contribute — not a
         * running total, and not from the beginning. Both details were wrong in the first version:
         * it compared the total against a per-call limit (so the background quota could never be
         * filled), and it restarted at index 0 (so the top-up pass selected every row twice).
         *
         * The byte rule is preserved exactly as it was: a single event larger than the cap is
         * still admitted when it is the first one, because a queue that can never ship an
         * oversized event would wedge permanently.
         *
         * @return the index of the first row not consumed, so the caller can resume.
         */
        private class Accumulator {
            val events = ArrayList<GatewayEventOutboxEntity>(MAX_BATCH_EVENTS)
            var bytes = 0L
            private var stopped = false

            fun add(
                from: List<GatewayEventOutboxEntity>,
                startIndex: Int,
                maxToAdd: Int
            ): Int {
                var index = startIndex
                var added = 0
                while (index < from.size && added < maxToAdd && !stopped &&
                    events.size < MAX_BATCH_EVENTS
                ) {
                    val event = from[index]
                    val size = event.ciphertext.size
                    if (events.isNotEmpty() && bytes + size > MAX_BATCH_BYTES) {
                        stopped = true
                        break
                    }
                    bytes += size
                    events.add(event)
                    index++
                    added++
                }
                return index
            }
        }
    }

    /** Idempotent enqueue — re-committing the same eventUuid is a no-op. */
    suspend fun enqueueEvent(event: GatewayEventOutboxEntity): Boolean =        outboxDao.insertOrIgnore(event) != -1L

    /**
     * Transactional claim: lease exactly the selected batch, atomically.
     *
     * A fresh `leaseId` per claim is what makes a stranded row identifiable: if the process dies,
     * the lease simply ages out and [recoverStaleLeases] returns the row to PENDING. A new
     * `batchId` groups the rows of this one upload so a failure can be correlated to it.
     */
    suspend fun claimBatch(now: Long, leaseId: String = Policy.newLeaseId()): List<GatewayEventOutboxEntity> =
        db.withTransaction {
            // Fetched per group so the candidate window cannot be filled entirely by one of them
            // (mission §13). Each group is bounded by MAX_BATCH_EVENTS, so the total read is the
            // same as the old single 2×MAX_BATCH_EVENTS query.
            val foreground = outboxDao.claimableForeground(now, Policy.MAX_BATCH_EVENTS)
            val background = outboxDao.claimableBackground(now, Policy.MAX_BATCH_EVENTS)
            val selected = Policy.selectFair(foreground, background).events
            if (selected.isNotEmpty()) {
                outboxDao.markSending(selected.map { it.id }, leaseId, leaseId, now)
            }
            selected
        }

    /** Partial ACK (LOCK 13): only the reported eventUuid moves to ACKED. Releases the lease. */
    suspend fun onAcked(
        eventUuid: String,
        serverSequence: Long,
        ackedAt: Long,
        httpStatus: Int? = null
    ): Int =
        db.withTransaction {
            val changed = outboxDao.markAcked(eventUuid, serverSequence, ackedAt, httpStatus)
            if (changed > 0) advanceHistoryAckWatermarks(ackedAt)
            changed
        }

    /**
     * Advance every source's contiguous ACK frontier (mission §19/§21).
     *
     * The loop is [HistoryAckWalk]'s, not this function's. Restating "stop at the first gap" here is how
     * the clause went missing before: the frontier is only meaningful while every ordinal below it has
     * actually been delivered, and anything that removes, re-orders or re-stamps a row freezes it.
     */
    private suspend fun advanceHistoryAckWatermarks(now: Long) {
        for (checkpoint in db.cloudHistoryCheckpointDao().all()) {
            val rows = outboxDao.historyAfter(
                checkpoint.source, checkpoint.generation, checkpoint.ackedContiguousOrdinal,
                Policy.MAX_BATCH_EVENTS * 20
            ).map {
                HistoryAckCandidate(
                    ordinal = it.historyOrdinal,
                    state = it.state,
                    date = it.historyDate,
                    providerId = it.historyProviderId,
                )
            }
            val frontier = HistoryAckWalk.advanceFrom(
                ackedContiguousOrdinal = checkpoint.ackedContiguousOrdinal,
                ackedCursorDate = checkpoint.ackedCursorDate,
                ackedCursorProviderId = checkpoint.ackedCursorProviderId,
                rows = rows,
            ) ?: continue
            db.cloudHistoryCheckpointDao().upsert(checkpoint.copy(
                ackedContiguousOrdinal = frontier.ordinal,
                ackedCursorDate = frontier.date,
                ackedCursorProviderId = frontier.providerId,
                updatedAt = now,
            ))
        }
    }

    suspend fun isHistoryDeliveryComplete(source: String): Boolean {
        val checkpoint = db.cloudHistoryCheckpointDao().get(source) ?: return false
        // The rule lives in HistoryAckWalk so the dead-letter clause cannot be dropped by a caller that
        // re-derives it — the mistake that once reported CAUGHT_UP for a source with permanently
        // failed rows.
        return HistoryAckWalk.isDelivered(
            sourceExhausted = checkpoint.sourceExhausted,
            nextOrdinal = checkpoint.nextOrdinal,
            ackedContiguousOrdinal = checkpoint.ackedContiguousOrdinal,
            deadLetters = outboxDao.historyDeadLetters(source, checkpoint.generation),
        )
    }

    /**
     * A retryable failure: RETRY_WAIT with the next due time AND the reason stored on the row.
     *
     * [errorCode] is a `SyncErrorCode` name so the cause is machine-readable; [errorMessage] has
     * already been through the safe-detail redaction that the health layer applies.
     */
    suspend fun onRetry(
        eventUuid: String,
        attempt: Int,
        random: Random,
        now: Long,
        httpStatus: Int? = null,
        errorCode: String = SyncErrorCode.UNKNOWN.name,
        errorMessage: String? = null,
        /**
         * A server-supplied delay, used verbatim when present (mission §17: honour `Retry-After`).
         *
         * Its whole purpose is to be respected rather than second-guessed: overriding a rate
         * limit with our own backoff is how a device gets throttled harder.
         */
        retryAfterMs: Long? = null
    ) {
        outboxDao.markRetry(
            eventUuid = eventUuid,
            nextAttemptAt = now + (retryAfterMs ?: Policy.backoffDelayMs(attempt, random)),
            lastAttemptAt = now,
            httpStatus = httpStatus,
            errorCode = errorCode,
            errorMessage = errorMessage
        )
    }

    /** Permanent reject (schema/auth) — visible as DEAD_LETTER, never silently dropped. */
    suspend fun onDeadLetter(
        eventUuid: String,
        failureCategory: String,
        httpStatus: Int?,
        at: Long,
        appVersion: String,
        errorCode: String = SyncErrorCode.UNKNOWN.name,
        errorMessage: String? = null
    ) {
        outboxDao.markDead(
            eventUuid = eventUuid,
            failureCategory = failureCategory,
            httpStatus = httpStatus,
            at = at,
            appVersion = appVersion,
            errorCode = errorCode,
            errorMessage = errorMessage
        )
    }

    /**
     * Lease timeout (mission §12): a row in flight longer than this is presumed abandoned.
     *
     * Configurable rather than hardcoded at the call site, and generous enough that a slow
     * upload over a poor connection is not reclaimed out from under a live uploader.
     */
    var leaseTimeoutMs: Long = Policy.DEFAULT_LEASE_TIMEOUT_MS

    /**
     * Recovery for the crash window between claim and ACK.
     *
     * AGE-BOUNDED: only leases older than [leaseTimeoutMs] are returned to PENDING. The previous
     * implementation requeued EVERY SENDING row unconditionally, which would also steal a row
     * another claimant was actively uploading — the exact interleaving that produces a duplicate
     * upload and an un-ACKed DUPLICATE response.
     */
    suspend fun recoverStaleLeases(now: Long): Int =
        outboxDao.recoverStaleLeases(now - leaseTimeoutMs)

    /**
     * ACKED retention (mission §19).
     *
     * Removes acknowledged rows older than the retention window. Safe by construction: the
     * predicate only matches `state = 'ACKED'`, and it refuses any history row that is not yet
     * behind its source's acked watermark — deleting one of those would stall the watermark walk.
     * See [GatewayEventOutboxEntity.PURGE_ACKED_SQL].
     *
     * @return how many rows were removed.
     */
    suspend fun purgeAcknowledged(now: Long): Int =
        outboxDao.purgeAckedBefore(now - Policy.ACKED_RETENTION_MS)

    /**
     * PR-11 hotfix: rescue dead letters a successful enrollment actually repairs (see DAO).
     *
     * NARROW BY DESIGN: only credential/identity-shaped failures (`AUTH_REQUIRED`, `AUTH_EXPIRED`,
     * `IDENTITY_NOT_REGISTERED`, `CRYPTO_KEY_UNAVAILABLE`) are requeued, and they get their full
     * retry budget back. Permanently rejected events — 400/422 contract failures, `DEVICE_REVOKED`
     * — are left dead and, more importantly, left with their recorded reason intact. The earlier
     * blanket `WHERE state = 'DEAD_LETTER'` reset ran on every auth-driven re-enrollment, put
     * doomed rows back into bounded batches ahead of live ones, and erased why they had failed.
     *
     * @return how many rows were rescued.
     */
    suspend fun recoverDeadLetter(): Int = outboxDao.resetDeadLetterToPending()

    suspend fun recoverCryptoDeadLetter(minCryptoVersion: Int): Int =
        outboxDao.resetCryptoDeadLetterToPending(minCryptoVersion)

    suspend fun pendingDepth(): Int = outboxDao.pendingDepth()
    suspend fun pendingBytes(): Long = outboxDao.pendingBytes()
    suspend fun pendingBackfillDepth(): Int = outboxDao.pendingBackfillDepth()

    /** Rows claimed by an in-flight batch; the rest of [pendingDepth] is still waiting. */
    suspend fun sendingDepth(): Int = outboxDao.sendingDepth()

    suspend fun deadLetterDepth(): Int = outboxDao.deadLetterDepth()

    /**
     * Which keys the not-yet-uploaded events are under (mission §42).
     *
     * This is what makes `keyRef` more than a write-only column: a rotation has to be able to ask
     * "what is still under the retired key?" without decrypting anything, and a diagnostic has to be
     * able to see that the outbox is spread across more than one key — which is what a partial or
     * in-progress rotation looks like from the device.
     */
    suspend fun outstandingKeyRefCounts(): List<OutboxKeyRefCount> =
        outboxDao.outstandingKeyRefCounts()

    /** How many OUTSTANDING events are under [keyId], in either position (mission §42). */
    suspend fun outstandingUnderKey(keyId: String): Int = outboxDao.outstandingUnderKey(keyId)

    /**
     * Aggregate-only dead-letter breakdown (v3.4.7). Never deletes anything: the SHAPE of the
     * population is what tells a historical cohort apart from an active defect.
     */
    suspend fun deadLetterBreakdown(): List<DeadLetterBreakdownRow> =
        outboxDao.deadLetterBreakdown()

    suspend fun deadLetterSummary(now: Long = System.currentTimeMillis()): DeadLetterSummary =
        outboxDao.deadLetterSummary(now - 60 * 60_000L, now - 24 * 60 * 60_000L)

    /** True = newly ingested; false = redelivery (exactly-once by unique index). */
    suspend fun ingestCommand(command: RemoteCommandEntity): Boolean =
        commandDao.insertOrIgnore(command) != -1L

    suspend fun markCommandAcceptedIfReceived(
        commandId: String,
        leaseId: String = Policy.newLeaseId(),
        now: Long = System.currentTimeMillis()
    ): Boolean =
        commandDao.markAcceptedIfReceived(
            commandId = commandId,
            leaseId = leaseId,
            leaseExpiresAt = now + commandLeaseTimeoutMs,
            at = now
        ) == 1

    /**
     * Command lease timeout (mission §48), configurable like the outbox's.
     *
     * A command claimed and never completed must become claimable again, or GMweb's ledger never
     * resolves.
     */
    var commandLeaseTimeoutMs: Long = RemoteCommandEntity.DEFAULT_LEASE_TIMEOUT_MS

    /** Returns expired command leases to RECEIVED so a drain can pick them up (mission §48). */
    suspend fun reclaimExpiredCommandLeases(now: Long = System.currentTimeMillis()): Int =
        commandDao.reclaimExpiredLeases(now)

    /** Commands a drain still has to resolve (mission §46). */
    suspend fun nonTerminalCommands(limit: Int = 50): List<RemoteCommandEntity> =
        commandDao.nonTerminal(limit)

    suspend fun nonTerminalCommandDepth(): Int = commandDao.nonTerminalDepth()

    /** Terminal outcome with its structured reason. */
    suspend fun finishCommand(
        commandId: String,
        state: String,
        errorCode: String? = null,
        now: Long = System.currentTimeMillis()
    ): Boolean = commandDao.markFinished(commandId, state, now, errorCode) == 1

    suspend fun markCommandExecuting(
        commandId: String,
        now: Long = System.currentTimeMillis()
    ): Boolean = commandDao.markExecuting(commandId, now) == 1

    suspend fun setCommandResultEvent(commandId: String, eventId: String): Boolean =
        commandDao.setResultEventId(commandId, eventId) == 1

    /**
     * Backfill a legacy row's web-bubble key from a redelivery (mission §49).
     *
     * Never overwrites a key already recorded, so this is safe on every redelivery. Exists because
     * commands live for up to 24h: without it a row ingested by a build that dropped the key stays
     * unreconcilable on the web until it expires.
     */
    suspend fun setCommandClientMessageIdIfMissing(commandId: String, clientMessageId: String): Boolean =
        commandDao.setClientMessageIdIfMissing(commandId, clientMessageId) == 1

    /** Guarded lifecycle transition used by the send executor (PR-03). */
    suspend fun markCommandState(commandId: String, state: String, fromStates: List<String>): Boolean =
        commandDao.markState(commandId, state, fromStates) == 1

    /**
     * Open an execution-attempt row (mission §45).
     *
     * `remote_command_executions` existed in the schema from the start and was **never written to**
     * by anything, so the claim "a command is never executed twice" rested entirely on the unique
     * `idempotencyKey` index and not at all on a record of what ran. This is the write side.
     *
     * The row is opened at claim time, BEFORE the side effect, so a process death during execution
     * still leaves evidence that an attempt was started — which is exactly the fact the drain needs
     * and cannot otherwise recover.
     *
     * @return the row id, or -1 if the recording itself failed. Recording is diagnostic: a failure
     *   to open the row must never stop a legitimate execution.
     */
    suspend fun beginCommandExecution(
        commandId: String,
        attempt: Int,
        now: Long = System.currentTimeMillis(),
    ): Long = runCatching {
        executionDao.insert(
            RemoteCommandExecutionEntity(
                commandId = commandId,
                attempt = attempt,
                startedAt = now,
            )
        )
    }.getOrDefault(-1L)

    /** Close an execution-attempt row opened by [beginCommandExecution]. No-op for -1. */
    suspend fun finishCommandExecution(
        executionId: Long,
        result: String,
        now: Long = System.currentTimeMillis(),
    ) {
        if (executionId <= 0L) return
        runCatching { executionDao.finish(executionId, now, result.take(Policy.MAX_EXECUTION_RESULT_CHARS)) }
    }

    /** How many execution attempts have been recorded for a command (mission §45). */
    suspend fun commandExecutionCount(commandId: String): Int =
        runCatching { executionDao.countFor(commandId) }.getOrDefault(0)

    /**
     * Commands that were never claimed and are past their expiry (mission §93).
     *
     * The DAO query had no caller, so a queued command that no transport ever picked up stayed
     * `RECEIVED` for the life of the install and the inbox depth never returned to zero.
     */
    suspend fun expireUnclaimedCommands(now: Long = System.currentTimeMillis()): Int =
        commandDao.expireStale(now)

    /** Durable row lookup for honest redelivery ACKs (PR-10, §58). */
    suspend fun getCommand(commandId: String): RemoteCommandEntity? = commandDao.get(commandId)

    suspend fun mapOrGet(threadId: Long, conversationId: String): RemoteConversationMapEntity {
        mapDao.getByThreadId(threadId)?.let { return it }
        mapDao.insertOrIgnore(RemoteConversationMapEntity(conversationId, threadId, System.currentTimeMillis()))
        return mapDao.getByThreadId(threadId)!!
    }

    suspend fun cursor(direction: String): SyncCursorEntity? = cursorDao.get(direction)

    suspend fun saveCursor(cursor: SyncCursorEntity) = cursorDao.upsert(cursor)

    /**
     * TechSpec §12: the provider threadId ↔ opaque conversation UUID mapping
     * lives ONLY on Android (remote_conversation_map). Idempotent — returns
     * the existing mapping when present.
     */
    suspend fun ensureConversationIdForThread(threadId: Long): String {
        mapDao.getByThreadId(threadId)?.let { return it.conversationId }
        val conversationId = java.util.UUID.randomUUID().toString()
        mapDao.insertOrIgnore(
            RemoteConversationMapEntity(conversationId, threadId, System.currentTimeMillis())
        )
        // Lost an insert race? The winner's row is the mapping — read it back.
        return mapDao.getByThreadId(threadId)?.conversationId ?: conversationId
    }
}
