package com.autonomousone.messages.media

import com.autonomousone.messages.data.MessageAssetEntity
import com.autonomousone.messages.data.MessageAssetKeys
import com.autonomousone.messages.data.MessageAssetKind

/**
 * ONE row of `content://mms/part`, reduced to the metadata the Media / Links /
 * Files browser needs.
 *
 * Deliberately carries NO bytes: a 360K-message database must not become a media
 * archive, and Android already owns the attachment data behind the part URI.
 */
data class MmsPartMetadata(
    val partId: Long,
    val messageId: Long,
    /** Raw provider `ct` value, e.g. `image/jpeg` (may carry `; name=…`). */
    val contentType: String,
    /** `name` column — most stacks store the attachment file name here. */
    val name: String = "",
    /** `fn` (FILENAME) column, when present. */
    val fileName: String = "",
    /** `_size` when the provider exposes it; 0 = unknown. */
    val size: Long = 0L
)

/**
 * Pure MMS-part → asset mapping.
 *
 * This is the ONLY place that decides what an MMS part means for the browser, and
 * it is Android-free on purpose: the part reader hands it already-read metadata,
 * so the kind mapping and the identity rule are unit-testable without a device.
 *
 * Kind mapping (brief, verbatim): MIME types starting `image/`, `video/` or
 * `audio/` → [MEDIA]; every other attachment type → [FILE].
 *
 * NON-ATTACHMENTS ARE SKIPPED. A `text/plain` part IS the message body (already
 * shown in the conversation) and `application/smil` is the presentation
 * descriptor that Android attaches to nearly every MMS — indexing either would
 * fill the Files tab with rows the user never sent.
 */
object MmsAssetMapper {

    /** Part content URIs live under this prefix; `content://mms/part/<partId>`. */
    const val PART_URI_PREFIX = "content://mms/part/"

    private val NON_ATTACHMENTS = setOf(
        "text/plain",
        "application/smil",
        "multipart/related",
        "multipart/alternative",
        "multipart/mixed",
        "multipart/digest"
    )

    fun partContentUri(partId: Long): String = PART_URI_PREFIX + partId

    /** Base content type: lower-cased, parameters (`; name=…`, charset) stripped. */
    fun baseContentType(contentType: String): String =
        contentType.substringBefore(';').trim().lowercase()

    /** MIME types starting `image/`, `video/` or `audio/` → MEDIA; else FILE. */
    fun kindFor(contentType: String): MessageAssetKind {
        val base = baseContentType(contentType)
        return when {
            base.startsWith("image/") ||
                base.startsWith("video/") ||
                base.startsWith("audio/") -> MessageAssetKind.MEDIA
            else -> MessageAssetKind.FILE
        }
    }

    /** True only for parts the user actually received as attachments. */
    fun isAttachment(part: MmsPartMetadata): Boolean {
        val base = baseContentType(part.contentType)
        if (base.isEmpty()) return false
        if (base in NON_ATTACHMENTS) return false
        // `text/*` other than text/plain (e.g. text/vcard, text/x-vcard) IS an
        // attachment (a contact card) and belongs in Files.
        if (base.startsWith("multipart/")) return false
        return true
    }

    /** FILENAME wins, then NAME; blank when the provider gave neither. */
    fun displayNameOf(part: MmsPartMetadata): String =
        part.fileName.trim().ifBlank { part.name.trim() }

    /**
     * Deterministic assets for ONE message.
     *
     * Identity is [MessageAssetKeys.of], so re-reading the same part is an UPSERT.
     * A part appearing twice in the cursor (some stacks join part rows) yields
     * exactly one asset: duplicates are collapsed by `partId` AND by key.
     */
    fun toAssets(
        source: String,
        providerId: Long,
        threadId: Long,
        messageDate: Long,
        parts: List<MmsPartMetadata>
    ): List<MessageAssetEntity> {
        if (source.isBlank() || providerId <= 0L || parts.isEmpty()) return emptyList()
        val seenParts = HashSet<Long>()
        val seenKeys = HashSet<String>()
        val result = ArrayList<MessageAssetEntity>(parts.size)
        for (part in parts) {
            if (part.partId <= 0L) continue
            if (!seenParts.add(part.partId)) continue
            if (!isAttachment(part)) continue
            val kind = kindFor(part.contentType)
            val value = partContentUri(part.partId)
            val key = MessageAssetKeys.of(source, providerId, kind, value)
            if (!seenKeys.add(key)) continue
            result += MessageAssetEntity(
                assetKey = key,
                source = source,
                providerId = providerId,
                threadId = threadId,
                kind = kind.name,
                value = value,
                mimeType = baseContentType(part.contentType),
                displayName = displayNameOf(part),
                date = messageDate
            )
        }
        return result
    }
}
