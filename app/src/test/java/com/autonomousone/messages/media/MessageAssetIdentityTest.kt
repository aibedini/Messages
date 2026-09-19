package com.autonomousone.messages.media

import com.autonomousone.messages.data.MessageAssetKeys
import com.autonomousone.messages.data.MessageAssetKind
import com.autonomousone.messages.data.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Asset identity — the invariant that makes re-indexing idempotent instead of
 * duplicated, and the one the whole browser rests on:
 *
 *   key = MessageAssetKeys.of(source, providerId, kind, value)
 *
 * Two IDENTICAL urls in two DIFFERENT messages must keep two DISTINCT assets
 * (they are two different shares), while the same URL twice inside ONE message
 * must produce ONE asset.
 */
class MessageAssetIdentityTest {

    private val sms = MessageEntity.SOURCE_SMS
    private val mms = MessageEntity.SOURCE_MMS

    @Test
    fun `deterministic assetKey - same input always yields the same key`() {
        val a = MessageAssetKeys.of(sms, 100L, MessageAssetKind.LINK, "https://example.com/")
        val b = MessageAssetKeys.of(sms, 100L, MessageAssetKind.LINK, "https://example.com/")
        assertEquals(a, b)
        // SHA-256 hex, stable width.
        assertEquals(64, a.length)
    }

    @Test
    fun `different source or providerId yields a different key`() {
        val value = "https://example.com/"
        val sms100 = MessageAssetKeys.of(sms, 100L, MessageAssetKind.LINK, value)
        val mms100 = MessageAssetKeys.of(mms, 100L, MessageAssetKind.LINK, value)
        val sms101 = MessageAssetKeys.of(sms, 101L, MessageAssetKind.LINK, value)
        assertNotEquals("SMS 100 and MMS 100 are different messages", sms100, mms100)
        assertNotEquals(sms100, sms101)
    }

    @Test
    fun `different kind yields a different key`() {
        assertNotEquals(
            MessageAssetKeys.of(sms, 1L, MessageAssetKind.MEDIA, "content://mms/part/1"),
            MessageAssetKeys.of(sms, 1L, MessageAssetKind.FILE, "content://mms/part/1")
        )
    }

    @Test
    fun `two identical urls in two different messages both survive`() {
        val body = "look at https://example.com/deal"
        val first = MessageAssetMapper.linkAssets(sms, 500L, 7L, body, 1_000L)
        val second = MessageAssetMapper.linkAssets(sms, 501L, 7L, body, 2_000L)

        assertEquals(1, first.size)
        assertEquals(1, second.size)
        assertNotEquals(first.single().assetKey, second.single().assetKey)
        // Same normalized value, same thread: only the (source, providerId) half
        // of the identity separates them.
        assertEquals(first.single().value, second.single().value)
    }

    @Test
    fun `the same url twice inside one message is one asset`() {
        val body = "https://example.com/a and again https://example.com/a?utm_source=x"
        val assets = MessageAssetMapper.linkAssets(sms, 5L, 3L, body, 1L)
        assertEquals(1, assets.size)
        assertEquals("https://example.com/a", assets.single().value)
    }

    @Test
    fun `re-indexing the same message produces identical rows - stable across re-index`() {
        val body = "https://example.com/a and https://other.org/b"
        val first = MessageAssetMapper.linkAssets(sms, 5L, 3L, body, 1_000L)
        val second = MessageAssetMapper.linkAssets(sms, 5L, 3L, body, 1_000L)
        assertEquals(first, second)
        assertEquals(2, first.size)
    }

    @Test
    fun `a link asset carries the host in displayName and the normalized url in value`() {
        val body = "See HTTPS://Example.com/Path?utm_source=x#frag"
        val asset = MessageAssetMapper.linkAssets(sms, 9L, 4L, body, 77L).single()
        assertEquals(MessageAssetKind.LINK.name, asset.kind)
        assertEquals("https://example.com/Path", asset.value)
        assertEquals("example.com", asset.displayName)
        assertEquals("", asset.mimeType)
        assertEquals(4L, asset.threadId)
        assertEquals(77L, asset.date)
    }

    @Test
    fun `a link-only message with a phone number produces no asset`() {
        assertTrue(MessageAssetMapper.linkAssets(sms, 1L, 1L, "call +989121234567", 1L).isEmpty())
    }

    @Test
    fun `blank identity never produces an asset`() {
        assertTrue(MessageAssetMapper.linkAssets(sms, 0L, 1L, "https://example.com", 1L).isEmpty())
        assertTrue(MessageAssetMapper.linkAssets("", 1L, 1L, "https://example.com", 1L).isEmpty())
        assertTrue(MessageAssetMapper.linkAssets(sms, 1L, 1L, "", 1L).isEmpty())
    }

    @Test
    fun `batch link mapping equals per-message mapping`() {
        val messages = listOf(
            IndexableMessage(sms, 1L, 3L, "https://a.com", 10L),
            IndexableMessage(mms, 2L, 3L, "https://b.com", 20L)
        )
        assertEquals(
            messages.flatMap { MessageAssetMapper.linkAssets(it.source, it.providerId, it.threadId, it.body, it.date) },
            MessageAssetMapper.linkAssets(messages)
        )
    }
}
