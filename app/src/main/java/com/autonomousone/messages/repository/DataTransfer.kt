package com.autonomousone.messages.repository

/**
 * Shared, Android-free types for the long-running data-tools operations
 * (XML backup, XML restore, JSON export, bulk delete).
 *
 * Keeping these free of `android.*` imports means the formatting and
 * accounting logic that decides whether an archive is COMPLETE can be tested
 * on the JVM without a device.
 */

/** Which user action is currently running. */
enum class DataTransferOperation { BACKUP, RESTORE, EXPORT, DELETE }

/**
 * Where an operation currently is.
 *
 * - [COUNTING]   reading how many messages exist (backup/export prelude)
 * - [WRITING]    streaming rows into the private staging file
 * - [SAVING]     copying/labelling the staged archive to its final destination
 * - [RESTORING]  inserting parsed rows back into the SMS provider
 * - [DELETING]   provider delete in flight
 */
enum class DataTransferPhase { COUNTING, WRITING, SAVING, RESTORING, DELETING }

/**
 * A progress sample emitted by a repository.
 *
 * @param done       rows processed so far for this phase
 * @param total      rows the operation expects, or 0 while unknown
 * @param bytesDone  bytes copied so far (SAVING), otherwise 0
 * @param bytesTotal bytes to copy (SAVING), otherwise 0
 */
data class TransferProgress(
    val phase: DataTransferPhase,
    val done: Int = 0,
    val total: Int = 0,
    val bytesDone: Long = 0L,
    val bytesTotal: Long = 0L
)

/**
 * A failure the UI can explain to the user.
 *
 * [message] is deliberately human-readable: it is shown in Settings → Data
 * tools instead of a silent "failed" toast, because a partial archive is worse
 * than a visible error.
 */
class DataTransferException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/** Fallback label for SMS rows whose provider address is null or blank. */
internal const val UNKNOWN_ADDRESS = "Unknown"

/**
 * One SMS row as both archive writers consume it.
 *
 * [id] is the provider row id and is used ONLY for keyset paging. It is never
 * serialized: restored rows must get fresh ids.
 */
data class SmsTransferRow(
    val id: Long,
    val threadId: Long,
    val address: String,
    val body: String,
    val date: Long,
    val dateSent: Long,
    val read: Int,
    val type: Int,
    val status: Int
)
