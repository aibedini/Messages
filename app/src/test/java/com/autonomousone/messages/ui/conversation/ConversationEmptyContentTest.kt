package com.autonomousone.messages.ui.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.4.5 P0-H — a failed initial load must be visible and retryable.
 *
 * The device symptom was a conversation showing ONLY the single bubble Home's
 * launch snapshot carries. `ConversationScreen` decided what to render with an
 * implicit nest — `chatItems.isEmpty()` → launch snapshot → isLoading → empty
 * state — in which the snapshot outranked everything except real messages. A
 * loader that produced nothing therefore looked exactly like a quietly opening
 * conversation, forever, and the only recovery was force-closing the app.
 *
 * [resolveConversationEmptyContent] makes that ordering explicit and puts
 * [ConversationEmptyContent.FAILED] above the snapshot.
 */
class ConversationEmptyContentTest {

    private val viewModelCandidates = listOf(
        "src/main/java/com/autonomousone/messages/viewmodel/ConversationViewModel.kt",
        "app/src/main/java/com/autonomousone/messages/viewmodel/ConversationViewModel.kt"
    )

    private val screenCandidates = listOf(
        "src/main/java/com/autonomousone/messages/ui/screens/ConversationScreen.kt",
        "app/src/main/java/com/autonomousone/messages/ui/screens/ConversationScreen.kt"
    )

    private fun source(candidates: List<String>, name: String): String {
        val file = candidates
            .map { java.io.File(it) }
            .firstOrNull { it.isFile }
            ?: error("cannot locate $name; looked in ${candidates.joinToString()}")
        return file.readText()
    }

    // ── The ordering contract ───────────────────────────────────────────────

    @Test
    fun `providerFailure_surfacesRetryInsteadOfSilentPreview`() {
        assertEquals(
            "a failed load must NOT be hidden behind Home's launch snapshot",
            ConversationEmptyContent.FAILED,
            resolveConversationEmptyContent(
                hasMessages = false,
                hasLaunchSnapshot = true,
                isLoading = false,
                initialLoadFailed = true
            )
        )
        assertEquals(
            "the same failure without a snapshot is still a failure",
            ConversationEmptyContent.FAILED,
            resolveConversationEmptyContent(
                hasMessages = false,
                hasLaunchSnapshot = false,
                isLoading = false,
                initialLoadFailed = true
            )
        )
        // A failure while a load is still in flight still owes the user Retry.
        assertEquals(
            ConversationEmptyContent.FAILED,
            resolveConversationEmptyContent(
                hasMessages = false,
                hasLaunchSnapshot = true,
                isLoading = true,
                initialLoadFailed = true
            )
        )
    }

    @Test
    fun `homeSnapshotRemainsPreviewOnly_neverSourceOfTruth`() {
        // The snapshot is a BRIDGE: it paints the first frame …
        assertEquals(
            ConversationEmptyContent.LAUNCH_PREVIEW,
            resolveConversationEmptyContent(
                hasMessages = false,
                hasLaunchSnapshot = true,
                isLoading = false,
                initialLoadFailed = false
            )
        )
        // … and it is never a substitute for a real window.
        assertEquals(
            ConversationEmptyContent.MESSAGES,
            resolveConversationEmptyContent(
                hasMessages = true,
                hasLaunchSnapshot = true,
                isLoading = false,
                initialLoadFailed = false
            )
        )
        // A stale failure flag may never hide a painted window.
        assertEquals(
            ConversationEmptyContent.MESSAGES,
            resolveConversationEmptyContent(
                hasMessages = true,
                hasLaunchSnapshot = true,
                isLoading = false,
                initialLoadFailed = true
            )
        )
    }

    @Test
    fun `loadingAndEmptyStatesAreDistinct`() {
        assertEquals(
            ConversationEmptyContent.LOADING,
            resolveConversationEmptyContent(false, false, isLoading = true, initialLoadFailed = false)
        )
        assertEquals(
            ConversationEmptyContent.EMPTY,
            resolveConversationEmptyContent(false, false, isLoading = false, initialLoadFailed = false)
        )
        assertEquals(
            "a cold open with a snapshot still shows the snapshot",
            ConversationEmptyContent.LAUNCH_PREVIEW,
            resolveConversationEmptyContent(false, true, isLoading = true, initialLoadFailed = false)
        )
    }

    // ── Retry wiring ────────────────────────────────────────────────────────

    /**
     * `retryAfterFailure_loadsConversationWithoutProcessRestart`.
     *
     * The retry path itself lives in an AndroidViewModel, so it cannot be
     * executed on the JVM. The two things that matter are structural and are
     * pinned here: Retry must clear the failure state and re-run the BOUNDED
     * conversation load in place — never a restart, never a new Activity.
     */
    @Test
    fun `retryAfterFailure_loadsConversationWithoutProcessRestart`() {
        val source = source(viewModelCandidates, "ConversationViewModel.kt")
        val start = source.indexOf("fun retryInitialLoad()")
        assertTrue("ConversationViewModel must expose retryInitialLoad()", start >= 0)
        val body = source.substring(start, minOf(source.length, start + 600))

        assertTrue(
            "Retry must clear the failure state before reloading:\n$body",
            body.contains("initialLoadFailed = false")
        )
        assertTrue(
            "Retry must re-run the bounded conversation load:\n$body",
            body.contains("loadConversation(")
        )
        assertFalse("Retry must never kill the process", body.contains("exitProcess"))
        assertFalse("Retry must never restart the Activity", body.contains("recreate()"))

        // After the flag clears, the body is no longer a failure screen.
        assertFalse(
            resolveConversationEmptyContent(false, true, isLoading = true, initialLoadFailed = false) ==
                ConversationEmptyContent.FAILED
        )
    }

    /** The screen must actually consult the resolver and offer the action. */
    @Test
    fun `conversationScreenRendersFailureWithRetry`() {
        val screen = source(screenCandidates, "ConversationScreen.kt")

        assertTrue(
            "the conversation body state must be resolved by the tested contract",
            screen.contains("resolveConversationEmptyContent(")
        )
        assertTrue(
            "the launch snapshot must no longer be the fallback that hides failure",
            screen.contains("ConversationEmptyContent.FAILED")
        )
        assertTrue(
            "the failure state must offer a Retry action",
            screen.contains("retryInitialLoad()")
        )
        assertTrue(
            "the failure card must reuse the localized Retry label",
            screen.contains("R.string.action_retry")
        )
    }
}
