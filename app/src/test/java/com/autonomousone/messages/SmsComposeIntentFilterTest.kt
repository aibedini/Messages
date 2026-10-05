package com.autonomousone.messages

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.4.28 resolver contract.
 *
 * Being the default SMS app does not make MainActivity a candidate for an
 * intent: only a matching intent-filter does. Chrome turns every non-http(s)
 * URL — including `sms:` — into Intent(ACTION_VIEW).setData(uri) and only
 * routes an "sms:" URL straight to the default SMS app when that package
 * resolves that very intent, so the filters below are what put Messages in the
 * EVE / browser / Contacts chooser.
 *
 * These assertions read the source manifest (the merged one is a build output)
 * so the guard fails on the edit that removes the contract, not after a
 * device test.
 */
class SmsComposeIntentFilterTest {

    private val manifest: String = sequenceOf(
        File("src/main/AndroidManifest.xml"),
        File("app/src/main/AndroidManifest.xml")
    ).first { it.exists() }.readText()

    /** Intent-filter bodies declaring [action], e.g. "VIEW" or "SENDTO". */
    private fun filtersFor(action: String): List<String> =
        Regex("<intent-filter[^>]*>(.*?)</intent-filter>", RegexOption.DOT_MATCHES_ALL)
            .findAll(manifest)
            .map { it.groupValues[1] }
            .filter { it.contains("android.intent.action.$action\"") }
            .toList()

    @Test
    fun `SMS and MMS schemes are advertised for ACTION_SENDTO and ACTION_VIEW`() {
        listOf("SENDTO", "VIEW").forEach { action ->
            val smsFilter = filtersFor(action).firstOrNull { it.contains("android:scheme=\"sms\"") }
            assertTrue("no $action intent-filter with an sms: scheme", smsFilter != null)
            listOf("sms", "smsto", "mms", "mmsto").forEach { scheme ->
                assertTrue(
                    "$action filter misses the $scheme scheme",
                    smsFilter!!.contains("android:scheme=\"$scheme\"")
                )
            }
            // DEFAULT makes it resolvable at all; BROWSABLE is what a browser
            // adds when it hands the URI over.
            assertTrue(
                "$action filter is not a resolver candidate",
                smsFilter!!.contains("android.intent.category.DEFAULT")
            )
            assertTrue(
                "$action filter cannot be reached from a browser",
                smsFilter!!.contains("android.intent.category.BROWSABLE")
            )
        }
    }

    @Test
    fun `ACTION_VIEW stays scheme-scoped`() {
        val viewFilters = filtersFor("VIEW")
        assertTrue("ACTION_VIEW not declared", viewFilters.isNotEmpty())
        viewFilters.forEach { filter ->
            assertFalse("http(s) must not reach the compose path", filter.contains("android:scheme=\"http"))
            assertFalse("wildcard mime type claims every chooser", filter.contains("android:mimeType=\"*/*\""))
            assertFalse("a generic VIEW filter is not wanted", filter.contains("android:mimeType="))
        }
    }

    @Test
    fun `plain text share is advertised and images are not`() {
        val sendFilter = filtersFor("SEND").firstOrNull { it.contains("android:mimeType=\"text/plain\"") }
        assertTrue("ACTION_SEND text/plain is no longer advertised", sendFilter != null)
        assertTrue(
            "ACTION_SEND text/plain must be a resolver candidate",
            sendFilter!!.contains("android.intent.category.DEFAULT")
        )
        filtersFor("SEND").forEach { filter ->
            assertFalse("image/* must not be claimed", filter.contains("image/"))
        }
    }
}
