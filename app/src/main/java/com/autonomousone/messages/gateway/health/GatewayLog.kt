package com.autonomousone.messages.gateway.health

/**
 * Which part of the gateway a log line belongs to.
 *
 * The subsystem is what makes the log FILTERABLE, and filtering is what makes it readable:
 * the reported bug is exactly the case where the sync lines are all green and the bridge
 * lines are all red, and that is invisible in one undifferentiated feed.
 *
 * [SYNC_UPLOAD] and [PULL_BRIDGE] are separate on purpose. They are different routes in
 * different directions, and the whole P0 came from a UI that treated one as evidence for
 * the other.
 */
enum class GatewayLogSubsystem {
    NETWORK,
    SERVER,
    TLS,
    AUTH,
    SYNC_UPLOAD,
    PULL_BRIDGE,
    EVE_QUEUE,
    SMS_RADIO,
    ACK,
    SUPERVISOR
}

enum class GatewayLogSeverity {
    DEBUG,
    INFO,
    SUCCESS,
    WARNING,
    ERROR
}

/**
 * One structured line.
 *
 * @param code a stable, greppable identifier (`PULL_EMPTY`, `SYNC_UPLOADED`, `TLS_MISMATCH`).
 *   Stable because it is what a bug report quotes; the [title] is what the user reads and may
 *   be reworded or localized later.
 * @param advanced true for the technical rows the brief explicitly does NOT want in the main
 *   feed — `accepted=2/2`, `duplicates=0`, `serverSequence=…`. They are kept, never shown by
 *   default, and never dropped.
 * @param detail already redacted. Never a message body, never a full number.
 */
data class GatewayLogEntry(
    val at: Long,
    val severity: GatewayLogSeverity,
    val subsystem: GatewayLogSubsystem,
    val code: String,
    val title: String,
    val detail: String? = null,
    val advanced: Boolean = false
)

/**
 * The feed's filter chips: All, Errors, Connection, Bridge, Sync, EVE.
 *
 * A pure predicate so the filter's meaning is a tested contract rather than a `when` buried
 * in a composable — "Errors" that silently included warnings would be a bug nobody notices
 * until they are debugging at 2am.
 */
enum class GatewayLogFilter {
    ALL,
    ERRORS,
    CONNECTION,
    BRIDGE,
    SYNC,
    EVE;

    fun matches(entry: GatewayLogEntry): Boolean = when (this) {
        ALL -> true
        ERRORS -> entry.severity == GatewayLogSeverity.ERROR ||
            entry.severity == GatewayLogSeverity.WARNING
        // "Connection" is the transport chain: the network, the server itself and its
        // certificate. Authentication sits here too because a rejected key is a connection
        // problem the user fixes in the same place.
        CONNECTION -> entry.subsystem in CONNECTION_SUBSYSTEMS
        BRIDGE -> entry.subsystem in setOf(
            GatewayLogSubsystem.PULL_BRIDGE,
            GatewayLogSubsystem.ACK
        )
        SYNC -> entry.subsystem == GatewayLogSubsystem.SYNC_UPLOAD
        EVE -> entry.subsystem in setOf(
            GatewayLogSubsystem.EVE_QUEUE,
            GatewayLogSubsystem.SMS_RADIO
        )
    }

    companion object {
        private val CONNECTION_SUBSYSTEMS = setOf(
            GatewayLogSubsystem.NETWORK,
            GatewayLogSubsystem.SERVER,
            GatewayLogSubsystem.TLS,
            GatewayLogSubsystem.AUTH,
            GatewayLogSubsystem.SUPERVISOR
        )
    }
}

/**
 * A bounded, newest-first ring buffer.
 *
 * BOUNDED IS THE REQUIREMENT, not a nicety: this feed is fed by a long-poll that completes
 * roughly twice a minute plus every event batch, so an unbounded list is a slow memory leak
 * on a device that already holds 360 000 messages. Eviction is oldest-first and silent —
 * a log that stops accepting lines when it is full is worse than one that forgets the
 * beginning.
 *
 * Thread-safe: the producers are a foreground service, a supervisor scope and the uploader
 * loop, and they do not share a thread.
 */
class GatewayLogBuffer(private val capacity: Int = DEFAULT_CAPACITY) {

    companion object {
        /**
         * Comfortably more than a user can scroll through, and a few tens of kilobytes.
         */
        const val DEFAULT_CAPACITY = 750

        /**
         * What the normal feed shows when [GatewayLogEntry.advanced] is false — the advanced
         * rows are still in the buffer for the report and for a deliberate "advanced" view.
         */
        const val MAX_VISIBLE = 750

        /** Longest title/detail kept, so one pathological line cannot dominate the buffer. */
        private const val MAX_TEXT = 200
    }

    private val lock = Any()
    private val entries = ArrayDeque<GatewayLogEntry>(capacity.coerceAtLeast(1))

    /** Newest first. */
    fun snapshot(): List<GatewayLogEntry> = synchronized(lock) { entries.toList() }

    fun size(): Int = synchronized(lock) { entries.size }

    fun clear() = synchronized(lock) { entries.clear() }

    /**
     * Adds an entry, evicting the oldest when full.
     *
     * @return the entry as stored, so a caller can render it without re-deriving anything.
     */
    fun add(entry: GatewayLogEntry): GatewayLogEntry {
        val bounded = entry.copy(
            title = entry.title.take(MAX_TEXT),
            detail = entry.detail?.let { GatewayHealthText.safeDetail(it) }
        )
        synchronized(lock) {
            entries.addFirst(bounded)
            while (entries.size > capacity) {
                entries.removeLast()
            }
        }
        return bounded
    }

    /** The feed as the UI shows it: filtered, newest first, advanced rows excluded. */
    fun visible(filter: GatewayLogFilter, includeAdvanced: Boolean = false): List<GatewayLogEntry> =
        snapshot()
            .asSequence()
            .filter { includeAdvanced || !it.advanced }
            .filter { filter.matches(it) }
            .take(MAX_VISIBLE)
            .toList()
}

/**
 * The process-wide feed the live components write to.
 *
 * Kept separate from [GatewayHealthRecorder] because they answer different questions: the
 * registry says "what is true NOW" (a snapshot, overwritten), the log says "what HAPPENED"
 * (a history, appended). Collapsing them would lose the sequence of events that led to the
 * current state — which is the thing a diagnosis actually needs.
 */
object GatewayLog {

    val buffer = GatewayLogBuffer()

    fun record(
        severity: GatewayLogSeverity,
        subsystem: GatewayLogSubsystem,
        code: String,
        title: String,
        detail: String? = null,
        advanced: Boolean = false,
        at: Long = System.currentTimeMillis()
    ) {
        buffer.add(
            GatewayLogEntry(
                at = at,
                severity = severity,
                subsystem = subsystem,
                code = code,
                title = title,
                detail = detail,
                advanced = advanced
            )
        )
    }

    // ── The transitions worth a line ────────────────────────────────────────
    // One function per event, so the subsystem, severity and code can never be chosen
    // inconsistently at two call sites.

    fun pullStarted(at: Long = System.currentTimeMillis()) = record(
        GatewayLogSeverity.INFO, GatewayLogSubsystem.PULL_BRIDGE, "PULL_START",
        "Pull started", at = at
    )

    /**
     * An EMPTY long-poll. Phrased as a completion, because that is what it is: the round trip
     * succeeded and there was nothing to do. Calling it "no task" without saying it succeeded
     * is what let a working bridge look idle-but-broken.
     */
    fun pullCompletedEmpty(durationMs: Long?, at: Long = System.currentTimeMillis()) = record(
        GatewayLogSeverity.SUCCESS, GatewayLogSubsystem.PULL_BRIDGE, "PULL_EMPTY",
        "Pull completed · no task", detail = durationMs?.let { "${it}ms" }, at = at
    )

    fun pullReceivedTask(token: String, at: Long = System.currentTimeMillis()) = record(
        GatewayLogSeverity.SUCCESS, GatewayLogSubsystem.PULL_BRIDGE, "PULL_TASK",
        "Task received", detail = "req $token", at = at
    )

    fun pullFailed(
        kind: GatewayFailureKind,
        httpStatus: Int?,
        safeDetail: String?,
        at: Long = System.currentTimeMillis()
    ) = record(
        GatewayLogSeverity.ERROR, GatewayLogSubsystem.PULL_BRIDGE, "PULL_FAILED",
        title = when (kind) {
            GatewayFailureKind.HTTP_AUTH -> "Pull failed · device key rejected"
            GatewayFailureKind.TLS -> "Pull failed · TLS problem"
            GatewayFailureKind.DNS -> "Pull failed · DNS resolution failed"
            GatewayFailureKind.TCP_CONNECT -> "Pull failed · server unreachable"
            GatewayFailureKind.READ_TIMEOUT -> "Pull failed · read timeout"
            GatewayFailureKind.HTTP_NOT_FOUND -> "Pull failed · gateway route not found"
            GatewayFailureKind.NETWORK_OFFLINE -> "Pull paused · no network"
            else -> "Pull failed"
        },
        detail = listOfNotNull(kind.name, httpStatus?.let { "HTTP $it" }, safeDetail)
            .joinToString(" · "),
        at = at
    )

    fun ackSucceeded(at: Long = System.currentTimeMillis()) = record(
        GatewayLogSeverity.SUCCESS, GatewayLogSubsystem.ACK, "ACK_SUCCESS",
        "Result ACKed to GMweb", at = at
    )

    fun ackFailed(
        kind: GatewayFailureKind,
        httpStatus: Int?,
        safeDetail: String?,
        at: Long = System.currentTimeMillis()
    ) = record(
        GatewayLogSeverity.ERROR, GatewayLogSubsystem.ACK, "ACK_FAILED",
        "Result not ACKed", detail = listOfNotNull(
            kind.name, httpStatus?.let { "HTTP $it" }, safeDetail
        ).joinToString(" · "), at = at
    )

    /**
     * A completed upload batch.
     *
     * The title is the user-facing form; the counts are [advanced], which is where the brief
     * puts `accepted=2/2` and `duplicates=0`.
     */
    fun syncUploaded(
        accepted: Int,
        duplicates: Int,
        failed: Int,
        httpStatus: Int,
        at: Long = System.currentTimeMillis()
    ) {
        record(
            GatewayLogSeverity.SUCCESS, GatewayLogSubsystem.SYNC_UPLOAD, "SYNC_UPLOADED",
            "Sync uploaded $accepted event" + (if (accepted == 1) "" else "s"),
            at = at
        )
        record(
            GatewayLogSeverity.DEBUG, GatewayLogSubsystem.SYNC_UPLOAD, "SYNC_UPLOAD_DETAIL",
            "Batch accepted", detail = "accepted=$accepted duplicates=$duplicates " +
                "failed=$failed status=$httpStatus",
            advanced = true, at = at
        )
    }

    fun syncFailed(
        httpStatus: Int?,
        kind: GatewayFailureKind,
        safeDetail: String?,
        count: Int,
        at: Long = System.currentTimeMillis()
    ) = record(
        GatewayLogSeverity.ERROR, GatewayLogSubsystem.SYNC_UPLOAD, "SYNC_FAILED",
        "Sync failed", detail = listOfNotNull(
            "$count event(s)", kind.name, httpStatus?.let { "HTTP $it" }, safeDetail
        ).joinToString(" · "), at = at
    )

    fun authRejected(httpStatus: Int?, at: Long = System.currentTimeMillis()) = record(
        GatewayLogSeverity.ERROR, GatewayLogSubsystem.AUTH, "AUTH_REJECTED",
        "Device key rejected by GMweb",
        detail = httpStatus?.let { "HTTP $it" }, at = at
    )

    fun serverReachable(latencyMs: Long, at: Long = System.currentTimeMillis()) = record(
        GatewayLogSeverity.SUCCESS, GatewayLogSubsystem.SERVER, "SERVER_REACHABLE",
        "Server reachable", detail = "${latencyMs}ms", at = at
    )

    fun tlsVerified(protocol: String?, at: Long = System.currentTimeMillis()) = record(
        GatewayLogSeverity.SUCCESS, GatewayLogSubsystem.TLS, "TLS_VERIFIED",
        "TLS handshake", detail = protocol, at = at
    )

    fun resumeFailed(context: String, detail: String? = null, at: Long = System.currentTimeMillis()) =
        record(
            GatewayLogSeverity.ERROR, GatewayLogSubsystem.SUPERVISOR, "RESUME_FAILED",
            context, detail = detail, at = at
        )

    /** Test seam. Never call from production code. */
    internal fun resetForTest() = buffer.clear()
}
