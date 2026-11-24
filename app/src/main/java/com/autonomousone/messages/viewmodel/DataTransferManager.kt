package com.autonomousone.messages.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import android.text.format.Formatter
import android.util.Log
import com.autonomousone.messages.R
import com.autonomousone.messages.repository.BackupRepository
import com.autonomousone.messages.repository.DataTransferException
import com.autonomousone.messages.repository.DataTransferOperation
import com.autonomousone.messages.repository.DataTransferPhase
import com.autonomousone.messages.repository.ExportRepository
import com.autonomousone.messages.repository.SmsRepository
import com.autonomousone.messages.repository.TransferProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A progress sample bound to the operation that produced it. */
data class DataTransferProgress(
    val operation: DataTransferOperation,
    val phase: DataTransferPhase,
    val done: Int = 0,
    val total: Int = 0,
    val bytesDone: Long = 0L,
    val bytesTotal: Long = 0L
)

/** The last finished operation's message, shown under its Settings row. */
data class DataToolsResult(
    val operation: DataTransferOperation,
    val message: String,
    val isError: Boolean
)

private typealias TransferBlock = suspend ((TransferProgress) -> Unit) -> DataToolsResult

/** A finished JSON export that has not been shared/dismissed yet. */
data class PendingExport(
    val uri: Uri,
    val messageCount: Int,
    val conversationCount: Int,
    val bytes: Long
)

data class DataToolsState(
    val progress: DataTransferProgress? = null,
    val lastResult: DataToolsResult? = null,
    val pendingExport: PendingExport? = null
)

/**
 * Single owner of the long-running Settings → Data tools operations.
 *
 * ## Why this is not scoped to the ViewModel
 *
 * A backup of a 40–50k-message inbox is a long operation. The old code ran it
 * in `viewModelScope`, so leaving the Settings screen (or Android destroying the
 * activity while the screen was off) cancelled the coroutine — or worse, left
 * `busy = true` with nothing running, which is exactly the "stuck on Working"
 * report. This manager lives for the process: progress survives screen changes,
 * operations finish even if the screen goes away, and re-opening Settings shows
 * the live progress (or the result) again.
 *
 * Only one operation runs at a time; starting another while one is active is a
 * no-op so two writers can never fight over the same archive.
 */
class DataTransferManager private constructor(private val app: Application) {

    companion object {
        private const val TAG = "DataTransferManager"

        @Volatile
        private var instance: DataTransferManager? = null

        fun get(context: Context): DataTransferManager {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val app = context.applicationContext as? Application
                    ?: throw IllegalStateException("DataTransferManager needs an Application context")
                return DataTransferManager(app).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val backupRepository = BackupRepository(app)
    private val exportRepository = ExportRepository(app)
    private val smsRepository = SmsRepository(app)

    private val _state = MutableStateFlow(DataToolsState())
    val state: StateFlow<DataToolsState> = _state.asStateFlow()

    private val activeLock = Any()
    private var activeJob: Job? = null

    fun backup(destination: Uri) {
        start(DataTransferOperation.BACKUP) { report ->
            val result = backupRepository.backupTo(destination, report)
            DataToolsResult(
                operation = DataTransferOperation.BACKUP,
                message = app.getString(
                    R.string.data_backup_ok_detail_fmt,
                    result.messageCount,
                    formatBytes(result.bytes)
                ),
                isError = false
            )
        }
    }

    fun restore(source: Uri) {
        start(DataTransferOperation.RESTORE) { report ->
            val result = backupRepository.restoreFrom(source, report)
            val message = if (result.skippedCount > 0) {
                app.getString(
                    R.string.data_restore_ok_skipped_fmt,
                    result.insertedCount,
                    result.skippedCount
                )
            } else {
                app.getString(R.string.data_restore_ok_fmt, result.insertedCount)
            }
            DataToolsResult(DataTransferOperation.RESTORE, message, isError = false)
        }
    }

    fun export() {
        start(DataTransferOperation.EXPORT) { report ->
            val result = exportRepository.exportAllChats(report)
            _state.update { current ->
                current.copy(
                    pendingExport = PendingExport(
                        uri = result.uri,
                        messageCount = result.messageCount,
                        conversationCount = result.conversationCount,
                        bytes = result.bytes
                    )
                )
            }
            DataToolsResult(
                operation = DataTransferOperation.EXPORT,
                message = app.getString(
                    R.string.data_export_ok_detail_fmt,
                    result.messageCount,
                    result.conversationCount,
                    formatBytes(result.bytes)
                ),
                isError = false
            )
        }
    }

    fun deleteByRange(cutoffMillis: Long, before: Boolean) {
        start(DataTransferOperation.DELETE) {
            val sms = smsRepository.deleteSmsByRange(cutoffMillis, before)
            val mms = smsRepository.deleteMmsByRange(cutoffMillis, before)
            if (sms < 0 || mms < 0) {
                DataToolsResult(
                    operation = DataTransferOperation.DELETE,
                    message = app.getString(R.string.data_delete_failed),
                    isError = true
                )
            } else {
                DataToolsResult(
                    operation = DataTransferOperation.DELETE,
                    message = app.getString(R.string.data_deleted_fmt, sms, mms),
                    isError = false
                )
            }
        }
    }

    /** Cancels the active operation; its repository cleans up partial files. */
    fun cancel() {
        synchronized(activeLock) {
            activeJob?.cancel()
        }
    }

    fun consumePendingExport() {
        _state.update { it.copy(pendingExport = null) }
    }

    private fun start(operation: DataTransferOperation, block: TransferBlock) {
        synchronized(activeLock) {
            if (activeJob?.isActive == true) return

            _state.update { current ->
                current.copy(
                    progress = DataTransferProgress(
                        operation = operation,
                        phase = initialPhase(operation)
                    ),
                    lastResult = null,
                    pendingExport = if (operation == DataTransferOperation.EXPORT) null else current.pendingExport
                )
            }

            activeJob = scope.launch {
                try {
                    val result = block { progress -> publish(operation, progress) }
                    _state.update { it.copy(lastResult = result) }
                } catch (canceled: CancellationException) {
                    _state.update {
                        it.copy(
                            lastResult = DataToolsResult(
                                operation = operation,
                                message = app.getString(R.string.data_canceled),
                                isError = false
                            )
                        )
                    }
                } catch (failure: DataTransferException) {
                    _state.update {
                        it.copy(
                            lastResult = DataToolsResult(
                                operation = operation,
                                message = failureMessage(operation, failure.message),
                                isError = true
                            )
                        )
                    }
                } catch (failure: Exception) {
                    Log.e(TAG, "$operation failed", failure)
                    _state.update {
                        it.copy(
                            lastResult = DataToolsResult(
                                operation = operation,
                                message = failureMessage(operation, failure.message),
                                isError = true
                            )
                        )
                    }
                } finally {
                    _state.update { it.copy(progress = null) }
                    synchronized(activeLock) { activeJob = null }
                }
            }
        }
    }

    private fun publish(operation: DataTransferOperation, progress: TransferProgress) {
        _state.update { current ->
            if (current.progress?.operation != operation) {
                current
            } else {
                current.copy(
                    progress = DataTransferProgress(
                        operation = operation,
                        phase = progress.phase,
                        done = progress.done,
                        total = progress.total,
                        bytesDone = progress.bytesDone,
                        bytesTotal = progress.bytesTotal
                    )
                )
            }
        }
    }

    private fun initialPhase(operation: DataTransferOperation): DataTransferPhase =
        when (operation) {
            DataTransferOperation.BACKUP, DataTransferOperation.EXPORT -> DataTransferPhase.COUNTING
            DataTransferOperation.RESTORE -> DataTransferPhase.RESTORING
            DataTransferOperation.DELETE -> DataTransferPhase.DELETING
        }

    private fun failureMessage(operation: DataTransferOperation, detail: String?): String {
        val reason = detail?.takeIf { it.isNotBlank() }
            ?: app.getString(R.string.data_unknown_error)
        val template = when (operation) {
            DataTransferOperation.BACKUP -> R.string.data_backup_failed_detail_fmt
            DataTransferOperation.RESTORE -> R.string.data_restore_failed_detail_fmt
            DataTransferOperation.EXPORT -> R.string.data_export_failed_detail_fmt
            DataTransferOperation.DELETE -> R.string.data_delete_failed_detail_fmt
        }
        return app.getString(template, reason)
    }

    private fun formatBytes(bytes: Long): String =
        Formatter.formatFileSize(app, bytes).toString()
}
