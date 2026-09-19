package com.autonomousone.messages.media

import com.autonomousone.messages.data.MessageAssetKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MMS part metadata → asset entity, plus the kind mapping the brief pins:
 * images/video/audio → MEDIA, every OTHER attachment type → FILE.
 *
 * The mapper is pure, so the mapping is proven without a device — the Cursor
 * reading that feeds it is exercised by the strict part reader.
 */
class MmsAssetMapperTest {

    private val mms = "mms"

    private fun part(
        id: Long,
        contentType: String,
        name: String = "",
        fileName: String = "",
        size: Long = 0L,
        messageId: Long = 1L
    ) = MmsPartMetadata(
        partId = id,
        messageId = messageId,
        contentType = contentType,
        name = name,
        fileName = fileName,
        size = size
    )

    // ── Kind mapping ────────────────────────────────────────────────────────

    @Test
    fun `image video and audio map to MEDIA`() {
        assertEquals(MessageAssetKind.MEDIA, MmsAssetMapper.kindFor("image/jpeg"))
        assertEquals(MessageAssetKind.MEDIA, MmsAssetMapper.kindFor("video/mp4"))
        assertEquals(MessageAssetKind.MEDIA, MmsAssetMapper.kindFor("audio/mpeg"))
        // Case and parameters must not change the kind.
        assertEquals(MessageAssetKind.MEDIA, MmsAssetMapper.kindFor("IMAGE/PNG; name=x.png"))
    }

    @Test
    fun `every other attachment type maps to FILE`() {
        assertEquals(MessageAssetKind.FILE, MmsAssetMapper.kindFor("application/pdf"))
        assertEquals(MessageAssetKind.FILE, MmsAssetMapper.kindFor("application/zip"))
        assertEquals(MessageAssetKind.FILE, MmsAssetMapper.kindFor("application/vnd.android.package-archive"))
        assertEquals(MessageAssetKind.FILE, MmsAssetMapper.kindFor("text/vcard"))
        assertEquals(MessageAssetKind.FILE, MmsAssetMapper.kindFor("text/x-vcard"))
        assertEquals(MessageAssetKind.FILE, MmsAssetMapper.kindFor(""))
    }

    @Test
    fun `an image part lands in MEDIA and a pdf part in FILE`() {
        val assets = MmsAssetMapper.toAssets(
            source = mms,
            providerId = 42L,
            threadId = 7L,
            messageDate = 1_700_000_000_000L,
            parts = listOf(
                part(1L, "image/jpeg", fileName = "IMG_1.jpg"),
                part(2L, "application/pdf", fileName = "doc.pdf")
            )
        )
        assertEquals(2, assets.size)
        assertEquals(MessageAssetKind.MEDIA.name, assets[0].kind)
        assertEquals(MessageAssetKind.FILE.name, assets[1].kind)
    }

    // ── Non-attachments ─────────────────────────────────────────────────────

    @Test
    fun `the text body and the smil layout part are not attachments`() {
        assertFalse(MmsAssetMapper.isAttachment(part(1L, "text/plain")))
        assertFalse(MmsAssetMapper.isAttachment(part(2L, "application/smil")))
        assertFalse(MmsAssetMapper.isAttachment(part(3L, "multipart/related")))
        assertTrue(MmsAssetMapper.toAssets(mms, 1L, 1L, 1L, listOf(
            part(1L, "text/plain"),
            part(2L, "application/smil")
        )).isEmpty())
    }

    // ── Identity + metadata ─────────────────────────────────────────────────

    @Test
    fun `part content uri and mime type are indexed`() {
        val asset = MmsAssetMapper.toAssets(
            mms, 42L, 7L, 99L, listOf(part(31L, "image/JPEG; name=a.jpg", fileName = "a.jpg"))
        ).single()
        assertEquals("content://mms/part/31", asset.value)
        assertEquals("image/jpeg", asset.mimeType)
        assertEquals("a.jpg", asset.displayName)
        assertEquals(99L, asset.date)
        assertEquals(7L, asset.threadId)
        assertEquals(42L, asset.providerId)
        assertEquals(mms, asset.source)
    }

    @Test
    fun `never copies bytes - only the provider uri is stored`() {
        val asset = MmsAssetMapper.toAssets(mms, 1L, 1L, 1L, listOf(part(9L, "image/png"))).single()
        assertTrue(asset.value.startsWith(MmsAssetMapper.PART_URI_PREFIX))
        // The entity has no byte/blob field at all: metadata only, by construction.
        // The count is pinned on purpose — adding a bytes column WOULD change it,
        // which is the point. It is 10 as of v3.4.0: assetKey, source, providerId,
        // threadId, kind, value, mimeType, displayName, date, and the `$stable`
        // field the Compose compiler adds to a data class with an unstable member.
        assertEquals(
            10,
            com.autonomousone.messages.data.MessageAssetEntity::class.java.declaredFields
                .count { !it.isSynthetic }
        )
    }

    @Test
    fun `displayName prefers FILENAME then NAME then blank`() {
        assertEquals("f.jpg", MmsAssetMapper.displayNameOf(part(1L, "image/jpeg", name = "n.jpg", fileName = "f.jpg")))
        assertEquals("n.jpg", MmsAssetMapper.displayNameOf(part(1L, "image/jpeg", name = "n.jpg")))
        assertEquals("", MmsAssetMapper.displayNameOf(part(1L, "image/jpeg")))
    }

    @Test
    fun `size is carried when the provider exposed it`() {
        val asset = MmsAssetMapper.toAssets(mms, 1L, 1L, 1L, listOf(part(5L, "application/pdf", size = 4096L))).single()
        assertTrue(asset.value.endsWith("/5"))
    }

    // ── Duplicates / re-index ───────────────────────────────────────────────

    @Test
    fun `no duplicate asset for the same message and part`() {
        val parts = listOf(part(1L, "image/jpeg"), part(2L, "application/pdf"))
        val first = MmsAssetMapper.toAssets(mms, 10L, 3L, 1_000L, parts)
        val again = MmsAssetMapper.toAssets(mms, 10L, 3L, 1_000L, parts)
        assertEquals(2, first.size)
        // Identical rows => identical keys => an UPSERT, never a second row.
        assertEquals(first, again)
        assertEquals(first.map { it.assetKey }.toSet().size, first.size)
    }

    @Test
    fun `a part row repeated by the cursor yields one asset`() {
        val repeated = listOf(part(1L, "image/jpeg"), part(1L, "image/jpeg"))
        assertEquals(1, MmsAssetMapper.toAssets(mms, 10L, 3L, 1_000L, repeated).size)
    }

    @Test
    fun `parts of two messages never share a key even with the same part id`() {
        val a = MmsAssetMapper.toAssets(mms, 1L, 1L, 1L, listOf(part(7L, "image/jpeg"))).single()
        val b = MmsAssetMapper.toAssets(mms, 2L, 1L, 1L, listOf(part(7L, "image/jpeg"))).single()
        assertTrue(a.assetKey != b.assetKey)
    }

    @Test
    fun `invalid part rows are skipped`() {
        assertTrue(MmsAssetMapper.toAssets(mms, 1L, 1L, 1L, listOf(part(0L, "image/jpeg"))).isEmpty())
        assertTrue(MmsAssetMapper.toAssets(mms, 0L, 1L, 1L, listOf(part(1L, "image/jpeg"))).isEmpty())
        assertTrue(MmsAssetMapper.toAssets("", 1L, 1L, 1L, listOf(part(1L, "image/jpeg"))).isEmpty())
    }

    @Test
    fun `base content type strips parameters and lower-cases`() {
        assertEquals("image/jpeg", MmsAssetMapper.baseContentType("Image/JPEG; name=a.jpg; charset=utf-8"))
        assertEquals("", MmsAssetMapper.baseContentType("   "))
    }
}
