package com.autonomousone.messages.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * STRUCTURAL GUARD for the v3.4.1 Home contact-name regression.
 *
 * The bug was not a wrong value anywhere — it was a WIRING mistake: the contact
 * directory was loaded only inside `HomeViewModel.loadProviderConversations()`, the
 * explicitly gated provider fallback, and the normal Room read-SSOT path returns
 * before reaching it:
 *
 *     performLoad() -> ensureRoomGate() -> roomReadEnabled == true
 *                   -> roomConversations() -> return   // contactNames never loaded
 *
 * A behavioural test cannot catch that without a device (it needs a real Contacts
 * provider and the Room cutover latch), so the invariant is pinned at the source
 * level, in the same spirit as the existing SQL-literal drift guard. The rule:
 *
 *   1. `HomeViewModel.init` must trigger the contact-directory load, and
 *   2. the Room-ready early `return` must still come BEFORE the provider fallback, so
 *      the two concerns stay independent rather than accidentally re-coupled.
 */
class HomeContactLoadingWiringTest {

    private val candidates = listOf(
        "src/main/java/com/autonomousone/messages/viewmodel/HomeViewModel.kt",
        "app/src/main/java/com/autonomousone/messages/viewmodel/HomeViewModel.kt"
    )

    private fun source(): String {
        val file = candidates.map { File(it) }.firstOrNull { it.isFile }
            ?: error("HomeViewModel.kt not found; looked in " + candidates.joinToString())
        return file.readText()
    }

    @Test
    fun `contact loading is triggered from init, not only from the provider fallback`() {
        val text = source()

        val initStart = text.indexOf("    init {")
        assertTrue("HomeViewModel must still have an init block", initStart >= 0)
        val initEnd = text.indexOf("\n    }", initStart)
        assertTrue("init block must be closed", initEnd > initStart)
        val initBody = text.substring(initStart, initEnd)

        assertTrue(
            "HomeViewModel.init must refresh the contact directory: on the Room path " +
                "loadProviderConversations() never runs, so Home would render raw numbers " +
                "while the chat header shows the contact name",
            initBody.contains("refreshContactNames()")
        )
    }

    @Test
    fun `the contact directory has its own loader independent of provider conversations`() {
        val text = source()

        assertTrue(
            "HomeViewModel must define refreshContactNames(force)",
            text.contains("fun refreshContactNames(force: Boolean")
        )
        assertTrue(
            "the contact query must run on IO, never on the main thread",
            text.contains("getContactNameMapAsync()")
        )
    }

    @Test
    fun `the room-ready path still returns before the provider fallback`() {
        val text = source()

        val roomGuard = text.indexOf("if (roomReadEnabled) {", text.indexOf("private suspend fun performLoad()"))
        val providerFallback = text.indexOf("loadProviderConversations(", roomGuard)
        assertTrue("performLoad must still guard on roomReadEnabled", roomGuard >= 0)
        assertTrue("performLoad must still have the gated provider fallback", providerFallback > roomGuard)
        assertTrue(
            "the Room-ready branch must return BEFORE the provider fallback: if the " +
                "fallback could run on the Room path, every Home launch would scan the " +
                "SMS provider again",
            text.substring(roomGuard, providerFallback).contains("return")
        )
    }

    @Test
    fun `Home resolves display names through one resolver`() {
        val home = listOf(
            "src/main/java/com/autonomousone/messages/ui/screens/HomeScreen.kt",
            "app/src/main/java/com/autonomousone/messages/ui/screens/HomeScreen.kt"
        ).map { File(it) }.firstOrNull { it.isFile } ?: error("HomeScreen.kt not found")

        val text = home.readText()
        assertFalse(
            "HomeScreen must not do its own contact-map lookup: use viewModel.displayNameFor(...) " +
                "so the row, the search hit and the dialog label can never disagree",
            text.contains("contactNames[\n")
        )
    }
}
