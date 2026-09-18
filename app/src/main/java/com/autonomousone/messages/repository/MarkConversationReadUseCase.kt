package com.autonomousone.messages.repository

import android.content.Context
import com.autonomousone.messages.data.TelephonySyncCoordinator
import com.autonomousone.messages.event.SmsEventBus
import com.autonomousone.messages.utils.DiagnosticLog
import kotlinx.coroutines.CancellationException

/** Which provider table a mark-read operation must cover. */
enum class ConversationReadSource {
    SMS,
    MMS
}

/**
 * Local (Room shadow) read applier. One call == one local mutation and is
 * expected to be transactional on the DAO side (a single UPDATE).
 */
interface LocalThreadReadApplier {
    suspend fun markThreadReadLocally(threadId: Long)
}

/**
 * Provider READ writer for a conversation.
 *
 * [sources] is the set of tables the caller requires: SMS READ, MMS READ, or
 * both. A real thread requests both; an address-only target (threadId == 0)
 * can only request SMS, because MMS rows are reachable solely through a
 * THREAD_ID.
 *
 * The existing SmsRepository bulk API (markThreadAsRead) updates Telephony
 * .Sms.READ and, when the thread id is known, Telephony.Mms.READ in ONE
 * provider pass — so the production implementation performs exactly one pass
 * while this contract remains per-source and therefore testable.
 */
fun interface ThreadReadProviderWriter {
    fun markConversationRead(
        threadId: Long,
        phone: String,
        sources: Set<ConversationReadSource>
    ): MarkReadProviderResult
}

/** Typed diagnostic sink. Production wires DiagnosticLog.event. */
fun interface ReadDiagnosticSink {
    fun event(category: String, message: String, error: Throwable?)
}

/** Narrow shadow repair requested after a provider write failure. */
interface ProviderReadRepairRequester {
    suspend fun requestRepair(threadId: Long)
}

/**
 * The ONE entry point for "this conversation is now read".
 *
 * Every path funnels here — Home open, search open, deep link, notification
 * action, cache hit, Room hit, provider fallback, an explicit mark-read, and
 * an already-open conversation receiving an incoming message.
 *
 * Order of operations (never inverted):
 *  (a) the local Room transaction marks the thread read IMMEDIATELY;
 *  (b) [onLocalReadApplied] signals the optimistic/UI layer (SmsEventBus) so
 *      Home/list observe the read state without waiting on the provider;
 *  (c) the provider READ write is eventual persistence only. The UI is never
 *      blocked on a ContentResolver update, and a provider failure NEVER
 *      reverts the local read — it emits a typed diagnostic and asks for one
 *      narrow thread repair instead.
 *
 * Idempotent by construction: applying the same read twice is a no-op at the
 * DAO (UPDATE ... WHERE read = 0) and safe at the provider.
 */
class MarkConversationReadUseCase(
    private val localRead: LocalThreadReadApplier,
    private val providerRead: ThreadReadProviderWriter,
    private val diagnostics: ReadDiagnosticSink,
    private val repair: ProviderReadRepairRequester,
    private val onLocalReadApplied: (threadId: Long, phone: String) -> Unit = { _, _ -> }
) {

    suspend fun markRead(threadId: Long, phone: String) {
        if (threadId <= 0L && phone.isBlank()) return

        // (a) + (b) local-first, then the UI signal. The signal is emitted ONLY
        // when the durable local write actually succeeded.
        val localApplied = applyLocalRead(threadId, phone)

        // (c) provider persistence, eventual and failure-isolated. Attempted even
        // when the local write failed: the provider READ is what makes the narrow
        // ForThread repair converge Room to "read", so it IS the recovery path.
        writeProviderRead(threadId, phone, requestedSources(threadId, phone))

        if (!localApplied) {
            diagnostics.event(
                CATEGORY,
                "read-pending-recovery thread=$threadId provider-write-attempted=true",
                null
            )
        }
    }

    /**
     * @return true when the LOCAL durable read is real (or was not required,
     *         because there is no thread to mark), false when the Room
     *         transaction failed.
     */
    private suspend fun applyLocalRead(threadId: Long, phone: String): Boolean {
        if (threadId > 0L) {
            try {
                localRead.markThreadReadLocally(threadId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // The Room transaction IS the durable read. If it failed, the read
                // did NOT happen locally, so the UI must not be told that it did:
                // an overlay claiming "read" while Room still says unread is
                // exactly how unread resurrects (the optimistic state is later
                // overwritten by the durable truth).
                diagnostics.event(CATEGORY, "local-read-failed thread=$threadId", e)
                requestNarrowRepair(threadId)
                return false
            }
        }
        try {
            onLocalReadApplied(threadId, phone)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // UI signalling must never break the durable read path.
        }
        return true
    }

    private suspend fun writeProviderRead(
        threadId: Long,
        phone: String,
        sources: Set<ConversationReadSource>
    ) {
        val result = try {
            providerRead.markConversationRead(threadId, phone, sources)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            diagnostics.event(
                CATEGORY,
                "provider-read-failed thread=$threadId sources=" + sources.joinToString(","),
                e
            )
            requestNarrowRepair(threadId)
            return
        }
        if (result.hasFailure) {
            diagnostics.event(
                CATEGORY,
                "provider-read-partial thread=$threadId sms=${result.sms} mms=${result.mms}",
                null
            )
            requestNarrowRepair(threadId)
        }
    }

    private suspend fun requestNarrowRepair(threadId: Long) {
        if (threadId <= 0L) return
        try {
            repair.requestRepair(threadId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            diagnostics.event(CATEGORY, "read-repair-failed thread=$threadId", e)
        }
    }

    private fun requestedSources(threadId: Long, phone: String): Set<ConversationReadSource> {
        val sources = LinkedHashSet<ConversationReadSource>(2)
        if (threadId > 0L || phone.isNotBlank()) sources.add(ConversationReadSource.SMS)
        if (threadId > 0L) sources.add(ConversationReadSource.MMS)
        return sources
    }

    companion object {
        private const val CATEGORY = "CONVERSATION_READ"

        @Volatile
        private var instance: MarkConversationReadUseCase? = null

        /** Process-wide singleton — the single entry point all call sites use. */
        fun get(context: Context): MarkConversationReadUseCase {
            instance?.let { return it }
            val appContext = context.applicationContext
            return synchronized(this) {
                instance ?: MarkConversationReadUseCase(
                    localRead = ShadowThreadReadApplier(appContext),
                    providerRead = SmsRepositoryThreadReadWriter(appContext),
                    diagnostics = ReadDiagnosticSink { category, message, error ->
                        DiagnosticLog.event(category, message, error)
                    },
                    repair = CoordinatorReadRepairRequester(appContext),
                    onLocalReadApplied = { threadId, phone ->
                        // (b) optimistic UI/list observation; never a DB read.
                        SmsEventBus.emitThreadRead(threadId, phone)
                    }
                ).also { instance = it }
            }
        }
    }
}

/**
 * Narrow repair half: asks the sync core to re-read ONE thread's bounded
 * window. Used only after a provider READ write failed, so the shadow can
 * catch up without a global reconcile.
 */
internal class CoordinatorReadRepairRequester(
    private val context: Context
) : ProviderReadRepairRequester {
    override suspend fun requestRepair(threadId: Long) {
        if (threadId <= 0L) return
        TelephonySyncCoordinator.get(context).repairThreadInShadow(threadId)
    }
}

/** Room shadow half: delegates to the sync core's existing local API. */
internal class ShadowThreadReadApplier(
    private val context: Context
) : LocalThreadReadApplier {
    override suspend fun markThreadReadLocally(threadId: Long) {
        if (threadId <= 0L) return
        TelephonySyncCoordinator.get(context).markThreadReadInShadow(threadId)
    }
}

/**
 * Provider half.
 *
 * SmsRepository.markThreadAsRead is the existing bulk mark-read: ONE provider
 * pass that sets Telephony.Sms.READ (by THREAD_ID, else by ADDRESS) and, when
 * the thread id is known, Telephony.Mms.READ. We deliberately do not issue a
 * second pass per source — that duplicated ContentObserver burst is the exact
 * regression SmsRepository documents against.
 */
internal class SmsRepositoryThreadReadWriter(
    context: Context
) : ThreadReadProviderWriter {
    private val repository = SmsRepository(context)

    override fun markConversationRead(
        threadId: Long,
        phone: String,
        sources: Set<ConversationReadSource>
    ): MarkReadProviderResult {
        if (sources.isEmpty()) return MarkReadProviderResult(
            SourceWriteResult.NotApplicable,
            SourceWriteResult.NotApplicable
        )
        return repository.markThreadAsReadStrict(threadId, phone)
    }
}
