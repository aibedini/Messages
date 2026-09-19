package com.autonomousone.messages.media

import com.autonomousone.messages.data.MessageAssetEntity
import com.autonomousone.messages.data.MessageAssetKeys
import com.autonomousone.messages.data.MessageAssetKind
import com.autonomousone.messages.data.MessageEntity

/**
 * One message as the ingest path knows it — the minimum an indexer needs, and
 * nothing that ties the mapping to a Cursor, a Repository or an Android Context.
 *
 * The body travels WITH the identity so a batch never needs a provider read for
 * link extraction: the ingest path already read the row.
 */
data class IndexableMessage(
    val source: String,
    val providerId: Long,
    val threadId: Long,
    val body: String,
    val date: Long
) {
    val isMms: Boolean get() = source == MessageEntity.SOURCE_MMS

    companion object {
        fun of(source: String, providerId: Long, threadId: Long, body: String, date: Long) =
            IndexableMessage(source, providerId, threadId, body, date)
    }
}

/**
 * Pure LINKS → asset mapping.
 *
 * The identity rule is the brief's, and it is the whole reason re-indexing is
 * idempotent:
 *
 *  - the [MessageAssetKeys.of] hash includes `(source, providerId)`, so two
 *    IDENTICAL urls in two DIFFERENT messages keep two DISTINCT assets;
 *  - within ONE message, duplicates collapse to a single asset because
 *    [LinkExtractor.extract] deduplicates by normalized identity.
 *
 * `displayName` carries the host: the Links tab shows the domain as the title
 * without re-parsing the URL, and it never contains a query string, so a
 * token-bearing URL is never rendered as a "name" by mistake.
 */
object MessageAssetMapper {

    /** Pure LINKS projection for one message. */
    fun linkAssets(
        source: String,
        providerId: Long,
        threadId: Long,
        body: String,
        date: Long
    ): List<MessageAssetEntity> {
        if (source.isBlank() || providerId <= 0L || body.isBlank()) return emptyList()
        return LinkExtractor.extract(body).map { link ->
            MessageAssetEntity(
                assetKey = MessageAssetKeys.of(
                    source = source,
                    providerId = providerId,
                    kind = MessageAssetKind.LINK,
                    value = link.normalized
                ),
                source = source,
                providerId = providerId,
                threadId = threadId,
                kind = MessageAssetKind.LINK.name,
                value = link.normalized,
                mimeType = "",
                displayName = link.host,
                date = date
            )
        }
    }

    /** Every LINKS asset for a whole batch, in one pass. */
    fun linkAssets(messages: List<IndexableMessage>): List<MessageAssetEntity> =
        messages.flatMap { linkAssets(it.source, it.providerId, it.threadId, it.body, it.date) }
}
