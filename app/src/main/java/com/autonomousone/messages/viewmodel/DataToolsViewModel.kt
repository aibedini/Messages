package com.autonomousone.messages.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.StateFlow

/**
 * Backing logic for Settings → Data tools:
 *  - XML backup / restore through the SAF picker
 *  - export every conversation to a shareable JSON archive
 *  - bulk-delete messages before/after a chosen date
 *
 * The actual work is owned by [DataTransferManager] so it survives leaving the
 * screen and reports live progress; this ViewModel is the lifecycle-aware handle
 * the Settings composable observes.
 */
class DataToolsViewModel(application: Application) : AndroidViewModel(application) {

    private val manager = DataTransferManager.get(application)

    val state: StateFlow<DataToolsState> = manager.state

    fun backup(destination: Uri) = manager.backup(destination)

    fun restore(source: Uri) = manager.restore(source)

    fun export() = manager.export()

    fun deleteByRange(cutoffMillis: Long, before: Boolean) =
        manager.deleteByRange(cutoffMillis, before)

    fun cancel() = manager.cancel()

    fun consumePendingExport() = manager.consumePendingExport()
}
