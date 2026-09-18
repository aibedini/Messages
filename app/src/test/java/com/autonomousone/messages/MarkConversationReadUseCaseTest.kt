package com.autonomousone.messages

import com.autonomousone.messages.repository.ConversationReadSource
import com.autonomousone.messages.repository.LocalThreadReadApplier
import com.autonomousone.messages.repository.MarkConversationReadUseCase
import com.autonomousone.messages.repository.ProviderReadRepairRequester
import com.autonomousone.messages.repository.ReadDiagnosticSink
import com.autonomousone.messages.repository.ThreadReadProviderWriter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the ONE mark-read entry point (PHASE 10).
 *
 * Covers: local read applied immediately and BEFORE the provider write;
 * provider write requested for both SMS and MMS; provider failure never
 * reverts the local read and emits a typed diagnostic; repeated mark-read is
 * idempotent; address-only targets degrade to SMS-only.
 */
class MarkConversationReadUseCaseTest {

    private class RecordingLocal(
        private val order: MutableList<String>
    ) : LocalThreadReadApplier {
        var applied = false
        val threads = mutableListOf<Long>()

        override suspend fun markThreadReadLocally(threadId: Long) {
            order += "local:$threadId"
            applied = true
            threads += threadId
        }
    }

    private data class ProviderCall(
        val threadId: Long,
        val phone: String,
        val sources: Set<ConversationReadSource>
    )

    private class RecordingProvider(
        private val order: MutableList<String>,
        private val fail: Boolean = false
    ) : ThreadReadProviderWriter {
        val calls = mutableListOf<ProviderCall>()

        override fun markConversationRead(
            threadId: Long,
            phone: String,
            sources: Set<ConversationReadSource>
        ) {
            order += "provider:$threadId"
            calls += ProviderCall(threadId, phone, sources)
            if (fail) throw IllegalStateException("provider read update failed")
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

    private class Fixture(failProvider: Boolean = false) {
        val order = mutableListOf<String>()
        val local = RecordingLocal(order)
        val provider = RecordingProvider(order, fail = failProvider)
        val diagnostics = RecordingDiagnostics()
        val repair = RecordingRepair()
        val useCase = MarkConversationReadUseCase(
            localRead = local,
            providerRead = provider,
            diagnostics = diagnostics,
            repair = repair
        )
    }

    @Test
    fun `local read is applied before the provider write`() = runBlocking {
        val f = Fixture()

        f.useCase.markRead(42L, "+98912")

        assertTrue(f.local.applied)
        assertEquals(listOf("local:42", "provider:42"), f.order)
    }

    @Test
    fun `provider write is invoked for BOTH SMS and MMS of a real thread`() = runBlocking {
        val f = Fixture()

        f.useCase.markRead(42L, "+98912")

        assertEquals(1, f.provider.calls.size)
        val call = f.provider.calls.single()
        assertEquals(42L, call.threadId)
        assertEquals("+98912", call.phone)
        assertEquals(
            setOf(ConversationReadSource.SMS, ConversationReadSource.MMS),
            call.sources
        )
    }

    @Test
    fun `address-only target requests SMS and cannot request MMS`() = runBlocking {
        val f = Fixture()

        f.useCase.markRead(0L, "+98912")

        assertFalse(f.local.applied) // no thread id => no local Room transaction
        assertEquals(
            setOf(ConversationReadSource.SMS),
            f.provider.calls.single().sources
        )
    }

    @Test
    fun `provider failure keeps the local read and emits a diagnostic plus repair`() = runBlocking {
        val f = Fixture(failProvider = true)

        f.useCase.markRead(42L, "+98912")

        // (a) local read stands — provider failure must never revert it.
        assertTrue(f.local.applied)
        assertEquals(listOf("local:42", "provider:42"), f.order)
        // (b) failure is typed + observable.
        assertTrue(
            f.diagnostics.events.any {
                it.first == "CONVERSATION_READ" && it.second.contains("provider-read-failed")
            }
        )
        assertFalse(f.diagnostics.events.any { it.second.contains("local-read-failed") })
        // (c) one narrow repair is requested for the affected thread.
        assertEquals(listOf(42L), f.repair.threads)
    }

    @Test
    fun `repeated mark-read is idempotent and never fails`() = runBlocking {
        val f = Fixture()

        f.useCase.markRead(42L, "+98912")
        f.useCase.markRead(42L, "+98912")

        assertTrue(f.local.applied)
        assertEquals(listOf(42L, 42L), f.local.threads)
        assertEquals(2, f.provider.calls.size)
        assertTrue(f.diagnostics.events.isEmpty())
    }

    @Test
    fun `blank target is a no-op`() = runBlocking {
        val f = Fixture()

        f.useCase.markRead(0L, "")

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
            onLocalReadApplied = { threadId, phone -> signals += "signal:$threadId:$phone" }
        )

        useCase.markRead(42L, "+98912")

        assertEquals(listOf("signal:42:+98912"), signals)
        assertEquals("local:42", order.first())
    }
}
