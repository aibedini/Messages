package com.autonomousone.messages.repository

import android.content.Context
import com.autonomousone.messages.data.MessageAssetDao
import com.autonomousone.messages.data.MessageAssetEntity
import com.autonomousone.messages.data.MessageAssetKind
import com.autonomousone.messages.data.MessageAssetPageRow
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.media.AssetPaging
import com.autonomousone.messages.utils.DiagnosticLog
import kotlinx.coroutines.flow.Flow

/**
 * Read side of the Media / Links / Files browser.
 *
 * Every query is per-conversation and BOUNDED ([AssetPaging]) — the 360K-message
 * history table is never scanned here. Diagnostics record counts and kinds only:
 * a URL can carry a token, so it is never logged.
 */
class MessageAssetRepository(context: Context) {

    private val dao: MessageAssetDao =
        MessagesDatabase.get(context).messageAssetDao()

    fun observeCount(threadId: Long, kind: MessageAssetKind): Flow<Int> =
        dao.observeCountByKind(threadId, kind.name)

    suspend fun count(threadId: Long, kind: MessageAssetKind): Int =
        dao.countByKind(threadId, kind.name)

    /** Tab badge counts, one COUNT per kind (three indexed counts, no scan). */
    suspend fun countsFor(threadId: Long): Map<MessageAssetKind, Int> {
        val counts = MessageAssetKind.entries.associateWith { kind ->
            dao.countByKind(threadId, kind.name)
        }
        DiagnosticLog.event(
            "ASSETS",
            "counts thread=$threadId media=${counts[MessageAssetKind.MEDIA] ?: 0}" +
                " links=${counts[MessageAssetKind.LINK] ?: 0}" +
                " files=${counts[MessageAssetKind.FILE] ?: 0}"
        )
        return counts
    }

    /** One bounded, newest-first page for MEDIA / FILE (metadata only). */
    suspend fun page(
        threadId: Long,
        kind: MessageAssetKind,
        offset: Int,
        limit: Int = AssetPaging.PAGE_SIZE
    ): List<MessageAssetEntity> {
        val bounded = AssetPaging.limit(limit)
        val rows = dao.pageByKind(threadId, kind.name, bounded, AssetPaging.nextOffset(offset))
        DiagnosticLog.event(
            "ASSETS",
            "page kind=${kind.name} thread=$threadId offset=$offset rows=${rows.size}"
        )
        return rows
    }

    /** One bounded, newest-first LINKS page WITH the source body for snippets. */
    suspend fun pageLinks(
        threadId: Long,
        offset: Int,
        limit: Int = AssetPaging.PAGE_SIZE
    ): List<MessageAssetPageRow> {
        val bounded = AssetPaging.limit(limit)
        val rows = dao.pageWithBodyByKind(
            threadId,
            MessageAssetKind.LINK.name,
            bounded,
            AssetPaging.nextOffset(offset)
        )
        DiagnosticLog.event(
            "ASSETS",
            "page kind=LINK thread=$threadId offset=$offset rows=${rows.size}"
        )
        return rows
    }

    /**
     * Assets of ONE message — the hook a conversation bubble uses for its own
     * link/attachment chip. Composite identity: SMS 100 and MMS 100 differ.
     */
    suspend fun forMessage(source: String, providerId: Long): List<MessageAssetEntity> =
        dao.forMessage(source, providerId)
}
