package com.autonomousone.messages.media

import android.content.Context
import android.net.Uri
import android.provider.Telephony
import androidx.room.withTransaction
import com.autonomousone.messages.data.MessageAssetDao
import com.autonomousone.messages.data.MessageAssetKind
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.data.ProviderRead
import com.autonomousone.messages.utils.DiagnosticLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Writes the Media / Links / Files index for the messages the ingest path just
 * committed.
 *
 * DESIGN CONSTRAINTS (brief, verbatim requirements):
 *
 *  - **NO full Telephony scan, no O(total_messages) work.** LINKS are extracted
 *    from the body the ingest path ALREADY read; MMS parts cost ONE
 *    `content://mms/part` query per ingested BATCH (not per message, and never a
 *    sweep of history). The bounded history sweep is a separate, checkpointed
 *    worker ([MessageAssetBackfillWorker]).
 *  - **Fail-safe.** Every entry point swallows its own failures and reports a
 *    count through [DiagnosticLog]. An asset-indexing failure can therefore
 *    never fail — or delay — message ingest.
 *  - **Never delays ingest.** [enqueue] only puts an immutable, small request on
 *    a bounded channel and returns; a single consumer coroutine does the work.
 *  - **Convergent.** Re-indexing one message replaces its LINK set (and, when the
 *    part read SUCCEEDED, its MEDIA/FILE set), so an edited body cannot leave a
 *    stale link behind. A FAILED part read changes nothing — a failed read is
 *    never evidence that the attachment is gone.
 *  - **Deterministic identity.** Every row's key is
 *    `MessageAssetKeys.of(source, providerId, kind, value)`, so re-ingest is an
 *    idempotent UPSERT and two identical URLs in two different messages keep two
 *    distinct assets.
 */
class MessageAssetIndexer private constructor(private val appContext: Context) {

    companion object {
        /**
         * Requests waiting to be indexed. Bounded because the payload carries
         * message BODIES: an unbounded queue under a 360K-row backfill burst
         * would be a memory leak by construction. A dropped request is not lost
         * work — the checkpointed backfill sweep re-covers it, and the sweep is
         * scheduled by the Media screen (and by the first drop, see [enqueue]).
         */
        private const val QUEUE_CAPACITY = 1024

        /** The MMS part table. Same URI the existing MMS body reader queries. */
        private const val PART_CONTENT_URI = "content://mms/part"

        /**
         * `part` has no size column on AOSP; some OEM providers add one. It is
         * requested optimistically and the query is retried without it, because a
         * projection naming a missing column fails the WHOLE read.
         */
        private const val SIZE_COLUMN = "_size"

        @Volatile
        private var instance: MessageAssetIndexer? = null

        fun get(context: Context): MessageAssetIndexer =
            instance ?: synchronized(this) {
                instance ?: MessageAssetIndexer(context.applicationContext).also { instance = it }
            }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<List<IndexableMessage>>(QUEUE_CAPACITY)
    private val consumerStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Never touches the database: the DAO is resolved lazily so constructing the
     * singleton (e.g. from the sync coordinator's field initializer) opens nothing.
     */
    private val dao: MessageAssetDao get() = MessagesDatabase.get(appContext).messageAssetDao()
    private val database: MessagesDatabase get() = MessagesDatabase.get(appContext)

    // ── Non-blocking ingest hook ───────────────────────────────────────────

    /**
     * Queue indexing for the messages an ingest batch just committed. O(1),
     * non-suspending, never throws.
     */
    fun enqueue(messages: List<IndexableMessage>) {
        val unique = messages
            .filter { it.providerId > 0L && it.source.isNotBlank() }
            .distinctBy { it.source to it.providerId }
        if (unique.isEmpty()) return
        startConsumer()
        if (queue.trySend(unique).isFailure) {
            // The consumer is saturated. Report the COUNT only (never a URL or a
            // body) and ask the bounded sweep to pick the work up later.
            DiagnosticLog.event("ASSETS", "index_queue_full dropped=${unique.size}")
            MessageAssetBackfillWorker.scheduleOnce(appContext)
        }
    }

    private fun startConsumer() {
        if (!consumerStarted.compareAndSet(false, true)) return
        scope.launch {
            // A plain `for` loop (not consumeEach): single consumer, sequential
            // processing, exactly like the coordinator's mutation channel.
            for (batch in queue) {
                runCatching { index(batch) }
                    .onFailure { DiagnosticLog.event("ASSETS", "index_worker_failed", it) }
            }
        }
    }

    // ── Indexing ───────────────────────────────────────────────────────────

    /**
     * Index one batch synchronously (the deterministic entry point used by the
     * ingest consumer, the backfill worker and unit/manual verification).
     *
     * @return how many asset rows were written (0 on any failure).
     */
    suspend fun index(messages: List<IndexableMessage>): Int {
        val unique = messages
            .filter { it.providerId > 0L && it.source.isNotBlank() }
            .distinctBy { it.source to it.providerId }
        if (unique.isEmpty()) return 0
        return try {
            withContext(Dispatchers.IO) { applyIndex(unique) }
        } catch (t: Throwable) {
            DiagnosticLog.event("ASSETS", "index_failed messages=${unique.size}", t)
            0
        }
    }

    private suspend fun applyIndex(unique: List<IndexableMessage>): Int {
        val linkAssets = MessageAssetMapper.linkAssets(unique)
        val mmsMessages = unique.filter { it.isMms }
        val partsResult: ProviderRead<Map<Long, List<MmsPartMetadata>>> =
            if (mmsMessages.isEmpty()) {
                ProviderRead.Success(emptyMap())
            } else {
                readMmsPartMetadata(mmsMessages.map { it.providerId })
            }
        val parts = (partsResult as? ProviderRead.Success)?.value
        val partAssets = if (parts == null) {
            emptyList()
        } else {
            mmsMessages.flatMap { message ->
                MmsAssetMapper.toAssets(
                    source = message.source,
                    providerId = message.providerId,
                    threadId = message.threadId,
                    messageDate = message.date,
                    parts = parts[message.providerId].orEmpty()
                )
            }
        }

        database.withTransaction {
            // LINKS: the body came from the provider row itself, so it is
            // authoritative — replacing the set is what removes a link the user
            // deleted from the message.
            for (message in unique) {
                dao.deleteForMessageKind(
                    message.source, message.providerId, MessageAssetKind.LINK.name
                )
            }
            dao.upsertAll(linkAssets)

            if (parts != null) {
                for (message in mmsMessages) {
                    dao.deleteForMessageKind(
                        message.source, message.providerId, MessageAssetKind.MEDIA.name
                    )
                    dao.deleteForMessageKind(
                        message.source, message.providerId, MessageAssetKind.FILE.name
                    )
                }
                dao.upsertAll(partAssets)
            }
        }

        if (linkAssets.isNotEmpty() || partAssets.isNotEmpty()) {
            DiagnosticLog.event(
                "ASSETS",
                "indexed messages=${unique.size} links=${linkAssets.size}" +
                    " media=${partAssets.count { it.kind == MessageAssetKind.MEDIA.name }}" +
                    " files=${partAssets.count { it.kind == MessageAssetKind.FILE.name }}" +
                    " partsRead=${if (parts == null) "failed" else "ok"}"
            )
        }
        return linkAssets.size + partAssets.size
    }

    // ── MMS part metadata (reuses the provider part table) ──────────────────

    /**
     * Metadata for every part of [messageIds] in ONE query.
     *
     * This is the SAME `content://mms/part` table the existing MMS body reader
     * (SmsRepository.loadMmsBodiesStrict) and the MMS broadcast ingest path use —
     * no second MMS parser is introduced. It is wrapped in the app's strict
     * [ProviderRead] contract for the same reason the body reader is: a null
     * cursor or a provider failure is UNKNOWN, and must never be mistaken for
     * "this message has no attachments".
     */
    suspend fun readMmsPartMetadata(
        messageIds: List<Long>
    ): ProviderRead<Map<Long, List<MmsPartMetadata>>> = withContext(Dispatchers.IO) {
        val ids = messageIds.filter { it > 0L }.distinct()
        if (ids.isEmpty()) return@withContext ProviderRead.Success(emptyMap())
        val selection = Telephony.Mms.Part.MSG_ID + " IN (" + ids.joinToString(",") + ")"
        val safeProjection = arrayOf(
            Telephony.Mms.Part._ID,
            Telephony.Mms.Part.MSG_ID,
            Telephony.Mms.Part.CONTENT_TYPE,
            Telephony.Mms.Part.NAME,
            Telephony.Mms.Part.FILENAME
        )
        val withSize = safeProjection + SIZE_COLUMN
        try {
            val cursor = queryParts(withSize, selection)
                ?: return@withContext ProviderRead.Failure(ProviderRead.Reason.QUERY_RETURNED_NULL)
            ProviderRead.Success(cursor.use { parseParts(it) })
        } catch (e: SecurityException) {
            DiagnosticLog.event("ASSETS", "mms_part_read_denied", e)
            ProviderRead.Failure(ProviderRead.Reason.SECURITY, e)
        } catch (e: Exception) {
            // Retry once without the optional `_size` column: a provider that
            // rejects an unknown projected column must not cost the whole index.
            try {
                val cursor = queryParts(safeProjection, selection)
                    ?: return@withContext ProviderRead.Failure(
                        ProviderRead.Reason.QUERY_RETURNED_NULL
                    )
                ProviderRead.Success(cursor.use { parseParts(it) })
            } catch (retry: Exception) {
                DiagnosticLog.event("ASSETS", "mms_part_read_failed ids=${ids.size}", retry)
                ProviderRead.Failure(ProviderRead.Reason.UNEXPECTED, retry)
            }
        }
    }

    private fun queryParts(projection: Array<String>, selection: String) =
        appContext.contentResolver.query(
            Uri.parse(PART_CONTENT_URI),
            projection,
            selection,
            null,
            null
        )

    /** The single part-cursor parser. Pure cursor → metadata, no provider access. */
    private fun parseParts(cursor: android.database.Cursor): Map<Long, List<MmsPartMetadata>> {
        val idIndex = cursor.getColumnIndex(Telephony.Mms.Part._ID)
        val msgIndex = cursor.getColumnIndex(Telephony.Mms.Part.MSG_ID)
        val ctIndex = cursor.getColumnIndex(Telephony.Mms.Part.CONTENT_TYPE)
        val nameIndex = cursor.getColumnIndex(Telephony.Mms.Part.NAME)
        val fileNameIndex = cursor.getColumnIndex(Telephony.Mms.Part.FILENAME)
        val sizeIndex = cursor.getColumnIndex(SIZE_COLUMN)
        if (idIndex < 0 || msgIndex < 0) return emptyMap()
        val result = HashMap<Long, MutableList<MmsPartMetadata>>()
        while (cursor.moveToNext()) {
            val messageId = cursor.getLong(msgIndex)
            val partId = cursor.getLong(idIndex)
            if (messageId <= 0L || partId <= 0L) continue
            result.getOrPut(messageId) { ArrayList(2) } += MmsPartMetadata(
                partId = partId,
                messageId = messageId,
                contentType = if (ctIndex >= 0) cursor.getString(ctIndex).orEmpty() else "",
                name = if (nameIndex >= 0) cursor.getString(nameIndex).orEmpty() else "",
                fileName = if (fileNameIndex >= 0) cursor.getString(fileNameIndex).orEmpty() else "",
                size = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0L
            )
        }
        return result
    }

    // ── Cleanup (proven-absence paths only) ────────────────────────────────

    /**
     * Remove exactly one message's assets. Called on the PROVEN-delete path (the
     * provider positively answered that the row is gone), never on a refresh: a
     * refresh that deletes and re-inserts a row must not destroy the index.
     */
    suspend fun deleteForMessage(source: String, providerId: Long): Int = try {
        withContext(Dispatchers.IO) { dao.deleteForMessage(source, providerId); 1 }
    } catch (t: Throwable) {
        DiagnosticLog.event("ASSETS", "delete_failed source=$source", t)
        0
    }

    /**
     * Explicit orphan cleanup (no FK CASCADE — see [MessageAssetEntity]).
     * Bounded by the asset table, used after a conversation delete and at the end
     * of the history sweep.
     */
    suspend fun deleteOrphans(): Int = try {
        withContext(Dispatchers.IO) {
            dao.deleteOrphans().also {
                if (it > 0) DiagnosticLog.event("ASSETS", "orphans_removed=$it")
            }
        }
    } catch (t: Throwable) {
        DiagnosticLog.event("ASSETS", "orphan_cleanup_failed", t)
        0
    }
}
