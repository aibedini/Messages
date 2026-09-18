package com.autonomousone.messages.diagnostics

import com.autonomousone.messages.utils.DiagnosticLog
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Phase 15 severity of a main-thread stall. There is deliberately no
 * KILL/RESTART member: the watchdog can only OBSERVE. This enum is the
 * compile-time proof that the watchdog can never terminate the process or
 * relaunch an Activity.
 */
enum class StallSeverity { NONE, WARNING, CRITICAL }

/**
 * Consumer of watchdog reports. The interface exposes reporting only — no
 * kill, no restart, no process exit — so a fake implementation in a test can
 * assert exactly which observations the watchdog produced.
 *
 * Implementations run on the watchdog's background thread and must be quick
 * and must not touch the main thread.
 */
fun interface StallReportSink {
    fun report(severity: StallSeverity, breadcrumbs: StallBreadcrumbs)
}

/**
 * Optional pull-based breadcrumb contributor. Registered through
 * [DiagnosticsBreadcrumbs.registerSource] so feature owners can publish their
 * own current state without the watchdog depending on their classes.
 *
 * [contribute] is invoked on the watchdog background thread while a stall is
 * being reported. It must be cheap, allocation-light, non-blocking, must not
 * marshal to the main thread, and must only return privacy-safe values: never
 * a raw phone number, an SMS body or a token. Values are passed through
 * [ExitReasonSanitizer] before they reach the log as a final guard.
 */
fun interface StallBreadcrumbSource {
    fun contribute(): Map<String, String>
}

/**
 * The evidence attached to a watchdog report. Every field is optional on
 * purpose: when a producer has not published a value the field stays null and
 * [describe] prints `?` rather than inventing a measurement.
 *
 * Privacy: [conversationToken] is a hash, never a raw thread id; there is no
 * field for a phone number or a message body anywhere in this type.
 */
data class StallBreadcrumbs(
    val severity: StallSeverity? = null,
    val stallDurationMs: Long? = null,
    val currentScreen: String? = null,
    /** Hashed conversation token — see [DiagnosticsBreadcrumbs.setVisibleConversation]. */
    val conversationToken: String? = null,
    val visibleMessageCount: Int? = null,
    /** Current sync/reconcile operation label, e.g. `tail_delta`. */
    val syncOperation: String? = null,
    val exactRepairQueueDepth: Int? = null,
    val historyBackfillState: String? = null,
    val roomOperation: String? = null,
    val providerOperation: String? = null,
    val heapUsedBytes: Long? = null,
    val heapMaxBytes: Long? = null,
    val extra: Map<String, String> = emptyMap(),
) {

    /** One privacy-safe log line. Missing values are printed as `?`, never faked. */
    fun describe(): String = buildString {
        append("severity=").append(severity?.name ?: UNKNOWN)
        append(" stall_ms=").append(stallDurationMs?.toString() ?: UNKNOWN)
        append(" screen=").append(currentScreen ?: UNKNOWN)
        append(" conversation=").append(conversationToken ?: UNKNOWN)
        append(" visible_messages=").append(visibleMessageCount?.toString() ?: UNKNOWN)
        append(" sync_op=").append(syncOperation ?: UNKNOWN)
        append(" exact_repairs=").append(exactRepairQueueDepth?.toString() ?: UNKNOWN)
        append(" history_backfill=").append(historyBackfillState ?: UNKNOWN)
        append(" room_op=").append(roomOperation ?: UNKNOWN)
        append(" provider_op=").append(providerOperation ?: UNKNOWN)
        append(" heap=").append(formatHeap())
        extra.toSortedMap().forEach { (key, value) ->
            append(' ')
            append(ExitReasonSanitizer.sanitize(key))
            append('=')
            append(ExitReasonSanitizer.sanitize(value))
        }
    }

    private fun formatHeap(): String {
        if (heapUsedBytes == null && heapMaxBytes == null) return UNKNOWN
        val used = heapUsedBytes?.let { formatMib(it) } ?: UNKNOWN
        val max = heapMaxBytes?.let { formatMib(it) } ?: UNKNOWN
        return "$used/$max MiB"
    }

    companion object {
        private const val UNKNOWN = "?"

        /** Deterministic MiB rendering (Locale.US) so logs diff cleanly. */
        fun formatMib(bytes: Long): String =
            String.format(Locale.US, "%.1f", bytes / (1024.0 * 1024.0))
    }
}

/**
 * Process-wide, push-based breadcrumb board for the main-thread stall
 * watchdog.
 *
 * Why push and not pull: the watchdog must be able to describe a stalled main
 * thread without calling into the very code that may be blocking it. Every
 * setter here is an O(1) volatile write that any thread may call; the
 * watchdog reads the values from its own background thread.
 *
 * OWNERSHIP NOTE (gap, deliberately not faked): nothing in the app calls the
 * setters yet because the files that own that state (navigation, sync
 * coordinator, Room ingest, provider reads, repair queues) belong to other
 * changes. Until a producer publishes a value its field reads back as null and
 * the watchdog prints `?`. Wiring the setters is a one-line call at each
 * producer; [registerSource] exists for producers that prefer to compute a
 * snapshot lazily.
 */
object DiagnosticsBreadcrumbs {

    @Volatile
    private var currentScreen: String? = null

    @Volatile
    private var conversationToken: String? = null

    @Volatile
    private var visibleMessageCount: Int? = null

    @Volatile
    private var syncOperation: String? = null

    @Volatile
    private var exactRepairQueueDepth: Int? = null

    @Volatile
    private var historyBackfillState: String? = null

    @Volatile
    private var roomOperation: String? = null

    @Volatile
    private var providerOperation: String? = null

    private val sources = CopyOnWriteArrayList<StallBreadcrumbSource>()

    fun setCurrentScreen(screen: String?) {
        currentScreen = screen
    }

    /**
     * Publishes the open conversation without ever retaining the raw id: only
     * the SHA-256 token produced by [DiagnosticLog.phoneToken] is stored.
     */
    fun setVisibleConversation(threadId: Long?, messageCount: Int?) {
        conversationToken = threadId
            ?.takeIf { it > 0L }
            ?.let { DiagnosticLog.phoneToken(it.toString()) }
        visibleMessageCount = messageCount
    }

    fun setSyncOperation(label: String?) {
        syncOperation = label
    }

    fun setExactRepairQueueDepth(depth: Int?) {
        exactRepairQueueDepth = depth
    }

    fun setHistoryBackfillState(state: String?) {
        historyBackfillState = state
    }

    fun setRoomOperation(label: String?) {
        roomOperation = label
    }

    fun setProviderOperation(label: String?) {
        providerOperation = label
    }

    /** Register a lazy contributor. Duplicate registrations are ignored. */
    fun registerSource(source: StallBreadcrumbSource) {
        if (!sources.contains(source)) sources.add(source)
    }

    fun unregisterSource(source: StallBreadcrumbSource) {
        sources.remove(source)
    }

    /**
     * Builds the report payload. Called from the watchdog background thread
     * (never from the main thread), which is why pulling contributors here is
     * safe: a slow contributor delays only the watcher, not the UI.
     */
    fun snapshot(severity: StallSeverity, stallDurationMs: Long): StallBreadcrumbs {
        val runtime = Runtime.getRuntime()
        val extra = LinkedHashMap<String, String>()
        for (source in sources) {
            val contributed = try {
                source.contribute()
            } catch (_: Throwable) {
                null
            } ?: continue
            for ((key, value) in contributed) extra[key] = value
        }
        return StallBreadcrumbs(
            severity = severity,
            stallDurationMs = stallDurationMs,
            currentScreen = currentScreen,
            conversationToken = conversationToken,
            visibleMessageCount = visibleMessageCount,
            syncOperation = syncOperation,
            exactRepairQueueDepth = exactRepairQueueDepth,
            historyBackfillState = historyBackfillState,
            roomOperation = roomOperation,
            providerOperation = providerOperation,
            heapUsedBytes = runtime.totalMemory() - runtime.freeMemory(),
            heapMaxBytes = runtime.maxMemory(),
            extra = extra,
        )
    }
}
