package com.autonomousone.messages

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression guard for the stale conversation row behind "Sending in the list, Delivered in the chat".
 *
 * The provider row is re-read by [com.autonomousone.messages.data.MessageMutation.RefreshStatus], but
 * the app-owned transport/delivery state is not part of that row, so the ingest can classify it as
 * unchanged and never re-project the thread. These are source-level guards because the defect is a
 * missing CALL, not a wrong computation: a behavioural test over Room would need a device, and the
 * production projection already has SQL-level coverage in ConversationProjectionReplaceTest.
 */
class SendStateProjectionRefreshTest {

    private fun mainSource(relative: String): File {
        val candidates = listOf(File("src/main/$relative"), File("app/src/main/$relative"))
        return candidates.firstOrNull { it.isFile }
            ?: error("cannot locate $relative; looked in $candidates")
    }

    private fun refreshStatusBranch(): String {
        val source = mainSource(
            "java/com/autonomousone/messages/data/TelephonySyncCoordinator.kt"
        ).readText()
        val start = source.indexOf("is MessageMutation.RefreshStatus ->")
        val end = source.indexOf("is MessageMutation.MarkThreadRead ->")
        assertTrue("RefreshStatus branch not found", start >= 0 && end > start)
        return source.substring(start, end)
    }

    @Test
    fun `RefreshStatus re-projects the thread from the local mirror`() {
        val branch = refreshStatusBranch()
        assertTrue(
            "RefreshStatus must rebuild the conversation projection even when the provider row is " +
                "UNCHANGED; otherwise Home keeps the stale Sending state",
            branch.contains("rebuildConversationProjection")
        )
    }

    @Test
    fun `SmsSender asks for a refresh after writing app-owned send state`() {
        val source = mainSource("java/com/autonomousone/messages/sms/SmsSender.kt").readText()
        val write = source.indexOf("recordSendState(")
        assertTrue("recordSendState call not found", write >= 0)
        val after = source.substring(write)
        assertTrue(
            "recordSendState writes app-owned state that the provider row does not carry; the sender " +
                "must enqueue RefreshStatus afterwards or the list stays stale until restart",
            after.contains("MessageMutation.RefreshStatus")
        )
    }
}
