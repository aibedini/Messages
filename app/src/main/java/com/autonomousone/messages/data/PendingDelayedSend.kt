package com.autonomousone.messages.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.autonomousone.messages.sms.DelayedSendState
import kotlinx.coroutines.flow.Flow

/**
 * v3.4.0 FEATURE 11 — the durable ledger of UNDO-SEND intents (Room v17).
 *
 * ── Why this is a table and not a preference ─────────────────────────────────
 * "Sending in 10 seconds" is a PROMISE about the future, and the only truthful
 * place to keep a promise is a store that survives process death. WorkManager
 * provides the timer; this table provides the STATE — and the two must be able
 * to disagree safely:
 *
 *  * WorkManager says "the job ran" — possibly twice. This table says whether
 *    the single send permission was ever actually handed out (the claim in
 *    [CLAIM_SEND]). The job is at-least-once; the claim is at-most-once; only
 *    the two together are exactly-once.
 *  * The user says "undo". This table decides whether that is still possible
 *    (PENDING) or whether the radio already owns the message (SENDING onward).
 *
 * ── What is NOT stored ───────────────────────────────────────────────────────
 * The recipient's number is stored as the SAME rendered token diagnostics use
 * ([phoneToken], i.e. `DiagnosticLog.phoneToken`) rather than dialable digits.
 * Nothing here needs to dial: by the time the executor runs, the durable
 * WorkManager request already carries the number, and the row only has to prove
 * WHICH message it is. Keeping the digits out means this table can never become
 * a second, unredacted address book — and no capability is lost.
 *
 * The body IS stored, for exactly one reason: Undo hands the text back to the
 * composer. [PRUNE_TERMINAL_BEFORE] bounds its lifetime, so a terminal row never
 * outlives its usefulness.
 *
 * ── Identity ────────────────────────────────────────────────────────────────
 * Keyed by a random [intentId], never by body or timestamp: two identical
 * messages to the same person in the same second are two different intents, and
 * collapsing them would silently drop one.
 *
 * ── State names in SQL ──────────────────────────────────────────────────────
 * The state literals below are compile-time constants ([PENDING_STATE] ...)
 * rather than Room bind placeholders. Room requires one function parameter per
 * `:name` occurrence, and a state is used both as a bound value and as a WHERE
 * literal; deriving both from the enum keeps the SQL and [DelayedSendState] from
 * drifting (pinned by `DelayedSendPersistenceTest`).
 */
@Entity(
    tableName = "pending_delayed_sends",
    indices = [
        Index("threadId"),
        Index("dueAt")
    ]
)
data class PendingDelayedSendEntity(
    @PrimaryKey
    val intentId: String,

    /** Message text, kept ONLY so Undo can restore the composer. */
    val body: String,

    /** `DiagnosticLog.phoneToken(phone)` — never the dialable number. */
    val phoneToken: String,

    val threadId: Long,

    /** [DelayedSendState] name. Persisted by NAME so enum reordering is inert. */
    val state: String,

    /** Epoch millis the send becomes due. A lower bound, never an equality. */
    val dueAt: Long,

    val createdAt: Long,

    /** When the single claim was taken (0 until then). */
    val claimedAt: Long = 0L,

    /** Telephony row id written on success (0 until then). */
    val sentRowId: Long = 0L,

    /**
     * How many times a claim was WON. Diagnostics only — it never re-opens a
     * claim, because that is precisely how one message becomes two submits.
     */
    val attempts: Int = 0,

    /** Stable failure code (`SmsSendFailure.code`, or `PROCESS_DIED_DURING_SEND`). */
    val failureCode: String? = null
)

/** Row shape for [PendingDelayedSendDao.countByState] — diagnostics only. */
data class DelayedSendStateCount(val state: String, val c: Int)

// ══════════════════════════════════════════════════════════════════════════════
// The SHIPPED SQL. Top-level in this file because Room/KSP resolves a `@Query`
// argument only from a same-file constant (see UxDaos.kt). The JVM tests run
// these exact strings against a real SQLite engine, so "exactly one send" is
// pinned by the statement that actually ships.
// ══════════════════════════════════════════════════════════════════════════════

/** Persisted state vocabulary, derived from the enum. */
internal const val PENDING_STATE: String = "PENDING"
internal const val SENDING_STATE: String = "SENDING"
internal const val SENT_STATE: String = "SENT"
internal const val FAILED_STATE: String = "FAILED"
internal const val CANCELLED_STATE: String = "CANCELLED"

/** Every state literal the statements below may reference, for the drift test. */
internal val DELAYED_SEND_STATE_LITERALS: List<String> = listOf(
    PENDING_STATE, SENDING_STATE, SENT_STATE, FAILED_STATE, CANCELLED_STATE
)

internal const val INSERT_PENDING_DELAYED_SEND =
    "INSERT INTO `pending_delayed_sends` " +
        "(`intentId`,`body`,`phoneToken`,`threadId`,`state`,`dueAt`,`createdAt`," +
        "`claimedAt`,`sentRowId`,`attempts`,`failureCode`) " +
        "VALUES (:intentId,:body,:phoneToken,:threadId,:state,:dueAt,:createdAt," +
        "0,0,0,NULL)"

/**
 * THE CLAIM. PENDING -> SENDING, at most once, ever.
 *
 * 1 for the winner, 0 for every loser: a duplicate worker, an undo that
 * committed first, or a row that already sent. `attempts` counts claims for
 * diagnostics only — it never re-opens a claim.
 */
internal const val CLAIM_DELAYED_SEND =
    "UPDATE `pending_delayed_sends` " +
        "SET `state` = 'SENDING', `claimedAt` = :claimedAt, `attempts` = `attempts` + 1 " +
        "WHERE `intentId` = :intentId AND `state` = 'PENDING'"

/**
 * UNDO. PENDING -> CANCELLED.
 *
 * A zero-row result is the honest answer "the deadline was already claimed", so
 * the caller cancels the WorkManager job only AFTER this succeeds: cancelling
 * first could let a worker that already started send a message the UI has just
 * told the user was undone.
 */
internal const val CANCEL_PENDING_DELAYED_SEND =
    "UPDATE `pending_delayed_sends` " +
        "SET `state` = 'CANCELLED', `failureCode` = NULL " +
        "WHERE `intentId` = :intentId AND `state` = 'PENDING'"

/**
 * Last-chance refusal for a caller that never claimed.
 *
 * Deliberately NOT a claim: it can only fail a row nobody owns, so it can never
 * mask a duplicated send.
 */
internal const val FAIL_PENDING_DELAYED_SEND =
    "UPDATE `pending_delayed_sends` " +
        "SET `state` = 'FAILED', `failureCode` = :failureCode " +
        "WHERE `intentId` = :intentId AND `state` = 'PENDING'"

/** Terminal success of a claimed send. Only SENDING may reach SENT. */
internal const val MARK_DELAYED_SEND_SENT =
    "UPDATE `pending_delayed_sends` " +
        "SET `state` = 'SENT', `sentRowId` = :sentRowId, `failureCode` = NULL " +
        "WHERE `intentId` = :intentId AND `state` = 'SENDING'"

/** Terminal failure of a claimed send. Only SENDING may reach FAILED. */
internal const val MARK_DELAYED_SEND_FAILED =
    "UPDATE `pending_delayed_sends` " +
        "SET `state` = 'FAILED', `failureCode` = :failureCode " +
        "WHERE `intentId` = :intentId AND `state` = 'SENDING'"

/**
 * Startup recovery for a process that died INSIDE the radio call.
 *
 * Such a row is stuck in SENDING forever, and it must STAY stuck: re-issuing the
 * claim could submit the same SMS a second time. Recovery therefore only ever
 * marks it FAILED — visible, and resendable on purpose — and never sends.
 */
internal const val FAIL_STRANDED_DELAYED_SEND =
    "UPDATE `pending_delayed_sends` " +
        "SET `state` = 'FAILED', `failureCode` = :failureCode " +
        "WHERE `state` = 'SENDING' AND `claimedAt` > 0 AND `claimedAt` < :staleBefore"

/** Live (PENDING/SENDING) intents of one thread — the composer's pending bubbles. */
internal const val SELECT_LIVE_DELAYED_SENDS_FOR_THREAD =
    "SELECT * FROM `pending_delayed_sends` " +
        "WHERE `threadId` = :threadId " +
        "AND `state` IN ('PENDING', 'SENDING') " +
        "ORDER BY `createdAt` ASC"

/** Every live intent, newest first. Drives the undo host and diagnostics. */
internal const val SELECT_LIVE_DELAYED_SENDS =
    "SELECT * FROM `pending_delayed_sends` " +
        "WHERE `state` IN ('PENDING', 'SENDING') ORDER BY `createdAt` DESC"

/**
 * One-shot undo candidate: the newest PENDING intent. `LIMIT` lives in SQL
 * because this table is a bounded work queue, never a user history.
 */
internal const val SELECT_UNDOABLE_DELAYED_SEND =
    "SELECT * FROM `pending_delayed_sends` " +
        "WHERE `state` = 'PENDING' ORDER BY `createdAt` DESC LIMIT 1"

/** Retention: terminal rows older than the cutoff carry no live information. */
internal const val PRUNE_TERMINAL_DELAYED_SENDS =
    "DELETE FROM `pending_delayed_sends` " +
        "WHERE `state` IN ('SENT', 'FAILED', 'CANCELLED') " +
        "AND `createdAt` < :before"

/** Diagnostics only: how many intents sit in each state. */
internal const val COUNT_DELAYED_SENDS_BY_STATE =
    "SELECT `state`, COUNT(*) AS `c` FROM `pending_delayed_sends` GROUP BY `state`"

/**
 * All reads and writes of the undo-send ledger.
 *
 * Every mutating statement is a single compare-and-set whose WHERE clause
 * carries its precondition, so "check then act" never happens in Kotlin.
 */
@Dao
interface PendingDelayedSendDao {

    /** Records a new intent. INSERT (not REPLACE): an intentId is never reused. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: PendingDelayedSendEntity)

    @Query("SELECT * FROM `pending_delayed_sends` WHERE `intentId` = :intentId LIMIT 1")
    suspend fun byId(intentId: String): PendingDelayedSendEntity?

    /** Live (PENDING/SENDING) intents of one thread. */
    @Query(SELECT_LIVE_DELAYED_SENDS_FOR_THREAD)
    suspend fun liveForThread(threadId: Long): List<PendingDelayedSendEntity>

    /**
     * The composer observes this, so a bubble appears the instant the intent is
     * durable and disappears the instant it is claimed, undone or failed.
     */
    @Query(SELECT_LIVE_DELAYED_SENDS_FOR_THREAD)
    fun observeLiveForThread(threadId: Long): Flow<List<PendingDelayedSendEntity>>

    @Query(SELECT_LIVE_DELAYED_SENDS)
    suspend fun live(): List<PendingDelayedSendEntity>

    @Query(SELECT_UNDOABLE_DELAYED_SEND)
    suspend fun undoable(): PendingDelayedSendEntity?

    /**
     * THE CLAIM — PENDING -> SENDING. 1 exactly once in the lifetime of an
     * intent, 0 for every other caller.
     */
    @Query(CLAIM_DELAYED_SEND)
    suspend fun claim(intentId: String, claimedAt: Long): Int

    /** UNDO — PENDING -> CANCELLED. 0 means the claim got there first. */
    @Query(CANCEL_PENDING_DELAYED_SEND)
    suspend fun cancelPending(intentId: String): Int

    /** PENDING -> FAILED for a caller that never claimed. */
    @Query(FAIL_PENDING_DELAYED_SEND)
    suspend fun failPending(intentId: String, failureCode: String): Int

    /** SENDING -> SENT. Cannot touch a terminal row. */
    @Query(MARK_DELAYED_SEND_SENT)
    suspend fun markSent(intentId: String, sentRowId: Long): Int

    /** SENDING -> FAILED. Cannot touch a terminal row. */
    @Query(MARK_DELAYED_SEND_FAILED)
    suspend fun markFailed(intentId: String, failureCode: String): Int

    /** Startup recovery: a run that died mid-radio-call is failed, never resent. */
    @Query(FAIL_STRANDED_DELAYED_SEND)
    suspend fun failStrandedSending(failureCode: String, staleBefore: Long): Int

    /** Retention. Terminal rows older than [before] are deleted. */
    @Query(PRUNE_TERMINAL_DELAYED_SENDS)
    suspend fun pruneTerminalBefore(before: Long): Int

    @Query(COUNT_DELAYED_SENDS_BY_STATE)
    suspend fun countByState(): List<DelayedSendStateCount>
}
