package com.autonomousone.messages.viewmodel

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autonomousone.messages.repository.ExportRepository
import com.autonomousone.messages.repository.SmsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Backing logic for Settings > Data tools:
 *  - bulk-delete messages before/after a chosen date+time (SMS + MMS)
 *  - export every conversation to a shareable JSON file
 */
class DataToolsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SmsRepository(application)
    private val exporter = ExportRepository(application)

    var busy by mutableStateOf(false)
        private set

    var lastStatus by mutableStateOf<String?>(null)
        private set

    /** Deletes SMS+MMS around [cutoffMillis]; reports total rows via [onResult]. */
    fun deleteByRange(cutoffMillis: Long, before: Boolean, onResult: (Int, Int) -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch(Dispatchers.IO) {
            val sms = repository.deleteSmsByRange(cutoffMillis, before)
            val mms = repository.deleteMmsByRange(cutoffMillis, before)
            withContext(Dispatchers.Main) {
                busy = false
                lastStatus = "Deleted $sms SMS, $mms MMS"
                onResult(sms, mms)
            }
        }
    }

    /** Builds the JSON archive and hands the shareable [Uri] to [onReady]. */
    fun exportAll(onReady: (Uri?) -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch(Dispatchers.IO) {
            val uri = exporter.exportAllChats()
            withContext(Dispatchers.Main) {
                busy = false
                lastStatus = if (uri != null) "Export ready" else "Export failed"
                onReady(uri)
            }
        }
    }
}
