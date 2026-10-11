package com.autonomousone.messages.repository

import android.content.Context
import com.autonomousone.messages.data.TelephonySyncCoordinator
import com.autonomousone.messages.event.SmsEventBus
import com.autonomousone.messages.messaging.ConversationReadAudit
import com.autonomousone.messages.messaging.ReadCause
import com.autonomousone.messages.messaging.ReadSkipReason
import com.autonomousone.messages.messaging.VisibleConversationTracker
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
    /**
     * @param upToProviderId the highest provider row id that existed when the read was AUTHORISED, so a
     *   screen-authorised read cannot consume a message that arrived afterwards. Null means "no boundary
     *   could be established", which is only correct for an explicit user or remote command.
     */
    suspend fun markThreadReadLocally(threadId: Long, upToProviderId: Long?)
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
    /**
     * @param allowUnbounded whether an ABSENT horizon may fall back to reading the whole thread. False
     *   for a screen-authorised read, so an unknown boundary refuses the provider write rather than
     *   swallowing messages that arrived after the user left.
     */
    fun markConversationRead(
        threadId: Long,
        phone: String,
        sources: Set<ConversationReadSource>,
        allowUnbounded: Boolean
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
 * Supplies the READ HORIZON for a screen-authorised read: the highest provider row id that existed when
 * the read was authorised.
 *
 * A seam rather than a direct call so the boundary rule is testable without a database, and so the
 * use case has no dependency on the sync core's internals.
 */
fun interface ReadHorizonProvider {
    /** @return the boundary, or null when none can be established (which forbids an unbounded read). */
    suspend fun horizonFor(threadId: Long): Long?
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
    /**
     * Establishes the READ HORIZON for a screen-authorised read.
     *
     * Defaults to "no boundary", which makes a screen-authorised read SKIP rather than run unbounded.
     * That default is deliberately the safe one: a construction that forgets to wire it cannot turn into
     * a whole-thread read, it turns into a no-op that stays unread.
     */
    private val readHorizon: ReadHorizonProvider = ReadHorizonProvider { null },
    private val onLocalReadApplied: (threadId: Long, phone: String) -> Unit = { _, _ -> }
) {

    /**
     * Mark a conversation read, with an EXPLICIT authority.
     *
     * ## Why `cause` is a required parameter
     *
     * It used to take only `(threadId, phone)`, so every caller was implicitly authorised. That is
     * exactly how a conversation whose data had merely been *loaded* was marked read: the load paths
     * called this, and nothing here asked whether the user was looking at anything.
     *
     * There is deliberately NO default value. A default would be an implicit authorisation, which is
     * the bug. Every caller must now name the evidence it is acting on, and the two screen-dependent
     * causes are re-checked HERE, at execution time, because the caller's check happened before it
     * launched a coroutine — and by the time that coroutine runs the user may have left.
     *
     * | cause | authority | needs the screen |
     * | --- | --- | --- |
     * | [ReadCause.USER_OPEN_RESUMED] | the chat is RESUMED right now | yes, re-checked here |
     * | [ReadCause.INCOMING_WHILE_VISIBLE] | a message arrived while that chat was RESUMED | yes, re-checked here |
     * | [ReadCause.USER_MARK_READ] | the user pressed Mark as read | no |
     * | [ReadCause.REMOTE_MARK_READ] | an authenticated GMweb command | no |
     */
    suspend fun markRead(threadId: Long, phone: String, cause: ReadCause) {
        if (threadId <= 0L && phone.isBlank()) return

        // ── THE AUTHORIZATION GATE ───────────────────────────────────────────
        //
        // Screen-dependent authority is re-validated at EXECUTION time. A coroutine scheduled while
        // the chat was visible can run after the user navigated away, and a new SMS may have arrived in
        // between — marking that one read would be indistinguishable from the bug this fixes.
        if (requiresVisibleScreen(cause) && !isScreenAuthorised(threadId, phone)) {
            ConversationReadAudit.skipped(
                reason = ReadSkipReason.SCREEN_NOT_VISIBLE,
                threadId = threadId,
                source = "read_path",
                detail = "cause=${cause.name} revalidated_at_execution"
            )
            // Also through the INJECTED sink, so the refusal is observable where the operation is
            // decided rather than only in the device log. A refusal that cannot be asserted is a
            // refusal nobody can prove happened.
            diagnostics.event(
                CATEGORY,
                "skip=${ReadSkipReason.SCREEN_NOT_VISIBLE.name} thread=$threadId " +
                    "cause=${cause.name} revalidated_at_execution=true",
                null
            )
            return
        }

        com.autonomousone.messages.diagnostics.PerfTelemetry.noteMarkReadRequested(threadId)

        // ── THE READ HORIZON ─────────────────────────────────────────────────
        //
        // Captured HERE, before the mutation, so it describes what existed when the read was authorised
        // rather than whatever exists when the write lands. Without it, a screen-authorised read that
        // runs a moment late swallows a message that arrived in between — one nobody saw.
        //
        // Established only for the SCREEN-dependent causes. An explicit user action or an authenticated
        // remote command is a decision about the whole conversation, so it stays unbounded by design.
        val upToProviderId = if (requiresVisibleScreen(cause)) {
            readHorizonFor(threadId)
        } else {
            null
        }
        if (requiresVisibleScreen(cause) && upToProviderId == null) {
            // No boundary could be established, so the read cannot be bounded. Skipping is the safe
            // direction: the alternative is an unbounded write that may consume messages the user never
            // saw, and the conversation simply stays unread until a real user action clears it.
            ConversationReadAudit.skipped(
                reason = ReadSkipReason.NO_USER_EVIDENCE,
                threadId = threadId,
                source = "read_path",
                detail = "cause=${cause.name} no_read_horizon"
            )
            diagnostics.event(
                CATEGORY,
                "skip=${ReadSkipReason.NO_USER_EVIDENCE.name} thread=$threadId " +
                    "cause=${cause.name} reason=no_read_horizon",
                null
            )
            return
        }

        // (a) + (b) local-first, then the UI signal. The signal is emitted ONLY
        // when the durable local write actually succeeded.
        val localApplied = applyLocalRead(threadId, phone, upToProviderId)

        // (c) provider persistence, eventual and failure-isolated. Attempted even
        // when the local write failed: the provider READ is what makes the narrow
        // ForThread repair converge Room to "read", so it IS the recovery path.
        writeProviderRead(threadId, phone, requestedSources(threadId, phone), cause)

        if (!localApplied) {
            diagnostics.event(
                CATEGORY,
                "read-pending-recovery thread=$threadId provider-write-attempted=true cause=${cause.name}",
                null
            )
        }
    }

    /** True for the causes whose authority is "this chat is on screen". */
    private fun requiresVisibleScreen(cause: ReadCause): Boolean = when (cause) {
        ReadCause.USER_OPEN_RESUMED, ReadCause.INCOMING_WHILE_VISIBLE -> true
        ReadCause.USER_MARK_READ, ReadCause.REMOTE_MARK_READ -> false
    }

    /**
     * Whether the exact conversation is RESUMED right now.
     *
     * The address fallback is used ONLY when there is no thread id at all, mirroring
     * [VisibleConversationTracker.isVisibleForAddress]'s own rule: a known thread id must match exactly,
     * so a sender string that merely normalises similarly can never authorise a read.
     */
    private fun isScreenAuthorised(threadId: Long, phone: String): Boolean =
        if (threadId > 0L) {
            VisibleConversationTracker.isVisible(threadId)
        } else {
            VisibleConversationTracker.isVisibleForAddress(phone)
        }

    /**
     * The highest mirrored provider row id for this thread, or null when none can be established.
     *
     * Read through the sync core so the boundary comes from the same source of truth the mutation uses.
     * Null is returned rather than a substitute: an unknown boundary must NOT silently become an
     * unbounded read, which is the whole hazard the horizon exists to remove.
     */
    private suspend fun readHorizonFor(threadId: Long): Long? {
        if (threadId <= 0L) return null
        return try {
            readHorizon.horizonFor(threadId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            diagnostics.event(CATEGORY, "read-horizon-failed thread=$threadId", e)
            null
        }
    }

    /**
     * @return true when the LOCAL durable read is real (or was not required,
     *         because there is no thread to mark), false when the Room
     *         transaction failed.
     */
    private suspend fun applyLocalRead(threadId: Long, phone: String, upToProviderId: Long?): Boolean {
        if (threadId > 0L) {
            try {
                localRead.markThreadReadLocally(threadId, upToProviderId)
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
        sources: Set<ConversationReadSource>,
        cause: ReadCause
    ) {
        val result = try {
            providerRead.markConversationRead(threadId, phone, sources, !requiresVisibleScreen(cause))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            diagnostics.event(
                CATEGORY,
                "provider-read-failed thread=$threadId sources=" + sources.joinToString(",") +
                    " cause=${cause.name}",
                e
            )
            requestNarrowRepair(threadId)
            return
        }
        if (result.hasFailure) {
            diagnostics.event(
                CATEGORY,
                "provider-read-partial thread=$threadId sms=${result.sms} mms=${result.mms} " +
                    "cause=${cause.name}",
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
                    // The boundary comes from the same source of truth the mutation writes to.
                    readHorizon = ReadHorizonProvider { threadId ->
                        TelephonySyncCoordinator.get(appContext).readHorizonForThread(threadId)
                    },
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

/**
 * Room shadow half: delegates to the sync core's existing local API.
 *
 * It publishes the THREAD_READ event as part of the same call, and that is the fix for a P0: a read
 * performed ON THE PHONE previously reached GMweb by no route at all. The local write happened first
 * and the provider write second, so when the observer reconciled the thread the read flag already
 * matched (`ProviderTransition.UNCHANGED`) and no read-carrying event was ever built; THREAD_READ had
 * exactly one caller, the remote command. GMweb's unread badge therefore stayed set until some
 * unrelated event re-published the conversation.
 *
 * `applyMarkThreadRead` writes the shadow read state and enqueues the durable event in ONE
 * transaction, and the event identity is deterministic per conversation+revision — so a repeated read
 * of the same state is a dedupe, not a storm.
 */
internal class ShadowThreadReadApplier(
    private val context: Context
) : LocalThreadReadApplier {
    override suspend fun markThreadReadLocally(threadId: Long, upToProviderId: Long?) {
        if (threadId <= 0L) return
        TelephonySyncCoordinator.get(context).markThreadReadAndPublish(threadId, upToProviderId)
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
        sources: Set<ConversationReadSource>,
        allowUnbounded: Boolean
    ): MarkReadProviderResult {
        if (sources.isEmpty()) return MarkReadProviderResult(
            SourceWriteResult.NotApplicable,
            SourceWriteResult.NotApplicable
        )
        return repository.markThreadAsReadStrict(threadId, phone, allowUnbounded)
    }
}
