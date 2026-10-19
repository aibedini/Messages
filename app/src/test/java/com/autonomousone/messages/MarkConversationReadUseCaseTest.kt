package com.autonomousone.messages

import com.autonomousone.messages.messaging.ReadCause
import com.autonomousone.messages.messaging.VisibleConversationTracker
import com.autonomousone.messages.repository.ConversationReadSource
import com.autonomousone.messages.repository.LocalThreadReadApplier
import com.autonomousone.messages.repository.MarkConversationReadUseCase
import com.autonomousone.messages.repository.ProviderReadRepairRequester
import com.autonomousone.messages.repository.ReadDiagnosticSink
import com.autonomousone.messages.repository.ReadHorizonProvider
import com.autonomousone.messages.repository.ThreadReadProviderWriter
import com.autonomousone.messages.repository.MarkReadProviderResult
import com.autonomousone.messages.repository.SourceWriteResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the ONE mark-read entry point.
 *
 * Covers the original contract — local read applied immediately and BEFORE the provider write; both
 * SMS and MMS requested; provider failure never reverts the local read; idempotent; address-only
 * targets degrade to SMS-only — PLUS the P0 authorization rule this file exists to protect:
 *
 *   a read whose authority is "this conversation is on screen" is validated at EXECUTION time, so a
 *   coroutine scheduled while the chat was visible cannot mark read a message that arrived after the
 *   user left.
 */
class MarkConversationReadUseCaseTest {

    @After
    fun tearDown() {
        VisibleConversationTracker.resetForTest()
    }

    private class RecordingLocal(
        private val order: MutableList<String>
    ) : LocalThreadReadApplier {
        var applied = false
        val threads = mutableListOf<Long>()
        val horizons = mutableListOf<Long?>()

        override suspend fun markThreadReadLocally(
            threadId: Long,
            upToProviderId: Long?,
            cause: ReadCause
        ) {
            order += "local:$threadId"
            applied = true
            threads += threadId
            horizons += upToProviderId
        }
    }

    private data class ProviderCall(
        val threadId: Long,
        val phone: String,
        val sources: Set<ConversationReadSource>,
        val allowUnbounded: Boolean
    )

    private class RecordingProvider(
        private val order: MutableList<String>,
        private val fail: Boolean = false
    ) : ThreadReadProviderWriter {
        val calls = mutableListOf<ProviderCall>()

        override fun markConversationRead(
            threadId: Long,
            phone: String,
            sources: Set<ConversationReadSource>,
            allowUnbounded: Boolean
        ): MarkReadProviderResult {
            order += "provider:$threadId"
            calls += ProviderCall(threadId, phone, sources, allowUnbounded)
            if (fail) throw IllegalStateException("provider read update failed")
            return MarkReadProviderResult(
                sms = if (ConversationReadSource.SMS in sources) SourceWriteResult.Success(1) else SourceWriteResult.NotApplicable,
                mms = if (ConversationReadSource.MMS in sources) SourceWriteResult.Success(1) else SourceWriteResult.NotApplicable
            )
        }
    }

    private class RecordingDiagnostics : ReadDiagnosticSink {
        val events = mutableListOf<Pair<String, String>>()

        override fun event(category: String, message: String, error: Throwable?) {
            events += category to message
        }
    }

    private class RecordingRepair : ProviderReadRepairRequester {
        val threads = mutableListOf<Long>()

        override suspend fun requestRepair(threadId: Long) {
            threads += threadId
        }
    }

    private class Fixture(
        failProvider: Boolean = false,
        /** null models "no boundary could be established". */
        horizon: Long? = 500L
    ) {
        val order = mutableListOf<String>()
        val local = RecordingLocal(order)
        val provider = RecordingProvider(order, fail = failProvider)
        val diagnostics = RecordingDiagnostics()
        val repair = RecordingRepair()
        val useCase = MarkConversationReadUseCase(
            localRead = local,
            providerRead = provider,
            diagnostics = diagnostics,
            repair = repair,
            readHorizon = ReadHorizonProvider { horizon }
        )
    }

    /** An explicit user action: authorised without the screen, and unbounded by design. */
    private val explicit = ReadCause.USER_MARK_READ

    // ── the original contract ────────────────────────────────────────────────

    @Test
    fun `local read is applied before the provider write`() = runBlocking {
        val f = Fixture()

        f.useCase.markRead(42L, "+98912", explicit)

        assertTrue(f.local.applied)
        assertEquals(listOf("local:42", "provider:42"), f.order)
    }

    @Test
    fun `provider write is invoked for BOTH SMS and MMS of a real thread`() = runBlocking {
        val f = Fixture()

        f.useCase.markRead(42L, "+98912", explicit)

        val call = f.provider.calls.single()
        assertEquals(42L, call.threadId)
        assertEquals("+98912", call.phone)
        assertEquals(setOf(ConversationReadSource.SMS, ConversationReadSource.MMS), call.sources)
    }

    @Test
    fun `address-only target requests SMS and cannot request MMS`() = runBlocking {
        val f = Fixture()

        f.useCase.markRead(0L, "+98912", explicit)

        assertFalse(f.local.applied) // no thread id => no local Room transaction
        assertEquals(setOf(ConversationReadSource.SMS), f.provider.calls.single().sources)
    }

    @Test
    fun `provider failure keeps the local read and emits a diagnostic plus repair`() = runBlocking {
        val f = Fixture(failProvider = true)

        f.useCase.markRead(42L, "+98912", explicit)

        assertTrue(f.local.applied)
        assertEquals(listOf("local:42", "provider:42"), f.order)
        assertTrue(
            f.diagnostics.events.any {
                it.first == "CONVERSATION_READ" && it.second.contains("provider-read-failed")
            }
        )
        assertFalse(f.diagnostics.events.any { it.second.contains("local-read-failed") })
        assertEquals(listOf(42L), f.repair.threads)
    }

    @Test
    fun `repeated mark-read is idempotent and never fails`() = runBlocking {
        val f = Fixture()

        f.useCase.markRead(42L, "+98912", explicit)
        f.useCase.markRead(42L, "+98912", explicit)

        assertEquals(listOf(42L, 42L), f.local.threads)
        assertEquals(2, f.provider.calls.size)
        assertTrue(f.diagnostics.events.isEmpty())
    }

    @Test
    fun `blank target is a no-op`() = runBlocking {
        val f = Fixture()

        f.useCase.markRead(0L, "", explicit)

        assertFalse(f.local.applied)
        assertTrue(f.provider.calls.isEmpty())
        assertTrue(f.diagnostics.events.isEmpty())
    }

    @Test
    fun `onLocalReadApplied signals optimistic observers after the local read`() = runBlocking {
        val order = mutableListOf<String>()
        val local = RecordingLocal(order)
        val provider = RecordingProvider(order)
        val signals = mutableListOf<String>()
        val useCase = MarkConversationReadUseCase(
            localRead = local,
            providerRead = provider,
            diagnostics = RecordingDiagnostics(),
            repair = RecordingRepair(),
            readHorizon = ReadHorizonProvider { 500L },
            onLocalReadApplied = { threadId, phone -> signals += "signal:$threadId:$phone" }
        )

        useCase.markRead(42L, "+98912", explicit)

        assertEquals(listOf("signal:42:+98912"), signals)
        assertEquals("local:42", order.first())
    }

    // ── P0: screen-dependent authority is validated at EXECUTION time ─────────

    @Test
    fun `a screen-authorised read is REFUSED when the conversation is not visible`() {
        // The whole bug in one assertion: data loaded, the chat is not on screen, so nothing may be
        // marked read. No tracker visibility is set here.
        val f = Fixture()

        runBlocking { f.useCase.markRead(42L, "+98912", ReadCause.USER_OPEN_RESUMED) }

        assertFalse("no read may happen", f.local.applied)
        assertTrue("and no provider write either", f.provider.calls.isEmpty())
    }

    @Test
    fun `a screen-authorised read is APPLIED while the conversation IS visible`() {
        VisibleConversationTracker.onScreenResumed(42L, "+98912")
        val f = Fixture()

        runBlocking { f.useCase.markRead(42L, "+98912", ReadCause.USER_OPEN_RESUMED) }

        assertTrue(f.local.applied)
        assertEquals(1, f.provider.calls.size)
    }

    @Test
    fun `a stale screen authorisation cannot read a message that arrived after the user left`() {
        // The race, verbatim:
        //   t=0 the chat is visible, a read is scheduled
        //   t=2 the user leaves
        //   t=3 a new SMS arrives
        //   t=4 the scheduled read runs
        // The execution-time revalidation must refuse it, so the new message stays unread.
        VisibleConversationTracker.onScreenResumed(42L, "+98912")
        val f = Fixture()
        VisibleConversationTracker.onScreenPaused(42L)

        runBlocking { f.useCase.markRead(42L, "+98912", ReadCause.USER_OPEN_RESUMED) }

        assertFalse("the message from t=3 must not be marked read", f.local.applied)
        assertTrue(f.provider.calls.isEmpty())
    }

    @Test
    fun `a read authorised for ONE conversation cannot mark ANOTHER read`() {
        VisibleConversationTracker.onScreenResumed(42L, "+98 912")
        val f = Fixture()

        runBlocking { f.useCase.markRead(99L, "+98 913", ReadCause.USER_OPEN_RESUMED) }

        assertFalse("thread 99 was never on screen", f.local.applied)
    }

    @Test
    fun `an incoming-while-visible read needs the SAME thread to be visible`() {
        VisibleConversationTracker.onScreenResumed(42L, "+98912")
        val f = Fixture()

        // A message for another thread arriving while 42 is open must stay unread.
        runBlocking { f.useCase.markRead(99L, "+98913", ReadCause.INCOMING_WHILE_VISIBLE) }

        assertFalse(f.local.applied)
    }

    // ── P0: the read horizon ─────────────────────────────────────────────────

    @Test
    fun `a screen-authorised read is BOUNDED by the horizon captured at authorisation`() {
        VisibleConversationTracker.onScreenResumed(42L, "+98912")
        val f = Fixture(horizon = 500L)

        runBlocking { f.useCase.markRead(42L, "+98912", ReadCause.USER_OPEN_RESUMED) }

        assertEquals(listOf(500L), f.local.horizons)
        // The provider write must use the same bounded form, not the unbounded one.
        assertFalse("an automatic read must not be unbounded", f.provider.calls.single().allowUnbounded)
    }

    @Test
    fun `a screen-authorised read with NO horizon refuses instead of reading everything`() {
        // The provider horizon query failed. Falling back to the whole thread would swallow messages
        // that arrived after the user left, so the read does not happen at all and the conversation
        // stays unread — the recoverable direction.
        VisibleConversationTracker.onScreenResumed(42L, "+98912")
        val f = Fixture(horizon = null)

        runBlocking { f.useCase.markRead(42L, "+98912", ReadCause.USER_OPEN_RESUMED) }

        assertFalse(f.local.applied)
        assertTrue(f.provider.calls.isEmpty())
        assertTrue(
            "the refusal must be explainable as 'no authoritative evidence'; events=${f.diagnostics.events}",
            f.diagnostics.events.any {
                it.second.contains("NO_USER_EVIDENCE") || it.second.contains("no_read_horizon")
            }
        )
    }

    @Test
    fun `an explicit user action is NOT bounded by a horizon`() {
        // "Mark this conversation read" is a decision about the conversation, not about a snapshot of
        // it, so it keeps the whole-thread form even when a horizon is available.
        val f = Fixture(horizon = 500L)

        runBlocking { f.useCase.markRead(42L, "+98912", ReadCause.USER_MARK_READ) }

        assertTrue(f.local.applied)
        assertNull("an explicit action is deliberately unbounded", f.local.horizons.single())
        assertTrue(f.provider.calls.single().allowUnbounded)
    }

    @Test
    fun `an explicit user action works while NO conversation is visible`() {
        // Mark-as-read from Home, a selection menu or a notification must not require the chat to be
        // open — only the screen-dependent causes do.
        val f = Fixture()

        runBlocking { f.useCase.markRead(42L, "+98912", ReadCause.USER_MARK_READ) }

        assertTrue(f.local.applied)
    }

    @Test
    fun `an authenticated remote command works while NO conversation is visible`() {
        // GMweb's MARK_THREAD_READ is an explicit, authenticated instruction from another device, so it
        // must not be treated as screen-dependent.
        val f = Fixture()

        runBlocking { f.useCase.markRead(42L, "+98912", ReadCause.REMOTE_MARK_READ) }

        assertTrue(f.local.applied)
        assertTrue(f.provider.calls.single().allowUnbounded)
    }

    @Test
    fun `a default horizon provider refuses rather than reading everything`() {
        // A construction that forgets to wire the horizon must fail SAFE. This is the default, and the
        // safe direction is "stay unread", never "read the whole thread".
        VisibleConversationTracker.onScreenResumed(42L, "+98912")
        val order = mutableListOf<String>()
        val local = RecordingLocal(order)
        val provider = RecordingProvider(order)
        val useCase = MarkConversationReadUseCase(
            localRead = local,
            providerRead = provider,
            diagnostics = RecordingDiagnostics(),
            repair = RecordingRepair()
            // readHorizon deliberately omitted
        )

        runBlocking { useCase.markRead(42L, "+98912", ReadCause.USER_OPEN_RESUMED) }

        assertFalse(local.applied)
        assertTrue(provider.calls.isEmpty())
    }
}
