package com.autonomousone.messages.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryWatermarkPolicyTest {
    @Test fun `completed MMS with an untouched oldest cursor needs one repair scan`() {
        val legacy = SyncStateEntity(
            source = MessageEntity.SOURCE_MMS,
            newestDate = 1787224702000L,
            newestId = 180L,
            initialWindowReady = true,
            historyBackfillComplete = true,
        )
        assertTrue(HistoryWatermarkPolicy.needsProviderScan(MessageEntity.SOURCE_MMS, legacy))
        assertFalse(HistoryWatermarkPolicy.needsProviderScan(MessageEntity.SOURCE_SMS,
            legacy.copy(source = MessageEntity.SOURCE_SMS)))
        assertFalse(HistoryWatermarkPolicy.needsProviderScan(MessageEntity.SOURCE_MMS,
            legacy.copy(oldestDate = 1690835758392L, oldestId = 1L)))
        assertTrue(HistoryWatermarkPolicy.needsProviderScan(MessageEntity.SOURCE_MMS,
            legacy.copy(historyBackfillComplete = false)))
    }
}
