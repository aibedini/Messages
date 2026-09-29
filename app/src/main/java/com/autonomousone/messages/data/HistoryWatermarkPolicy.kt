package com.autonomousone.messages.data

/** A legacy MMS migration can mark the scan complete without persisting its oldest cursor. */
internal object HistoryWatermarkPolicy {
    fun needsProviderScan(source: String, state: SyncStateEntity): Boolean =
        !state.historyBackfillComplete ||
            (source == MessageEntity.SOURCE_MMS &&
                (state.oldestDate == Long.MAX_VALUE || state.oldestId == Long.MAX_VALUE))
}
