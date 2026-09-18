package com.autonomousone.messages.diagnostics

/**
 * Phase 18: the latency boundaries the app measures locally.
 *
 * These are cross-component spans. Neither side is owned by this change, so
 * nothing records them yet — see the wiring notes on [PerfTelemetry]. The
 * metric set exists so producers and consumers agree on the boundary names.
 */
enum class PerfMetric {
    /** Incoming provider row persisted -> the corresponding Room commit. */
    PROVIDER_PERSISTED_TO_ROOM_COMMIT,

    /** Room commit -> the Home/conversation UI observed the emission. */
    ROOM_COMMIT_TO_HOME_OBSERVED,

    /** Conversation tap -> first bubbles rendered. */
    CONVERSATION_TAP_TO_FIRST_BUBBLES,

    /** Mark-read request issued -> unread=0 UI state. */
    MARK_READ_TO_UNREAD_ZERO,

    /** TailDelta provider-delta duration. */
    TAIL_DELTA,

    /** ForThread targeted provider-read duration. */
    FOR_THREAD,

    /** Durable exact provider-repair claim through ACK/NACK. */
    EXACT_REPAIR,

    /** One two-sided integrity-audit batch. */
    INTEGRITY_BATCH,

    /** One history-backfill batch duration. */
    HISTORY_BACKFILL_BATCH,
}

/**
 * Summary for one metric. [p50Ms]/[p95Ms] are null when no sample exists —
 * the type makes it impossible to report a fabricated zero. Use [hasSamples]
 * before reading the percentiles.
 */
data class MetricStats(
    val count: Int,
    val p50Ms: Long?,
    val p95Ms: Long?,
) {
    val hasSamples: Boolean get() = count > 0

    /** e.g. `n=42 p50=13ms p95=88ms`, or [NO_SAMPLES]. Never invents a zero. */
    fun describe(): String = if (hasSamples) {
        "n=" + count + " p50=" + p50Ms + "ms p95=" + p95Ms + "ms"
    } else {
        NO_SAMPLES
    }

    companion object {
        const val NO_SAMPLES = "no samples"
        val EMPTY = MetricStats(count = 0, p50Ms = null, p95Ms = null)
    }
}

/**
 * Bounded, thread-safe rolling sample window.
 *
 * Deliberately a fixed-size ring buffer, never an unbounded list: the process
 * must not grow a history of every operation it has ever done. The oldest
 * sample is overwritten once [capacity] is reached.
 *
 * Percentiles use the nearest-rank definition on the retained, sorted window:
 * rank = ceil(fraction * n), clamped to [1, n]. It is deterministic and needs
 * no interpolation, so the tests can assert exact values.
 */
class RollingSamples(val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val values = LongArray(capacity)
    private var nextIndex = 0
    private var filled = 0

    @Synchronized
    fun add(value: Long) {
        values[nextIndex] = value
        nextIndex = (nextIndex + 1) % capacity
        if (filled < capacity) filled++
    }

    @Synchronized
    fun size(): Int = filled

    @Synchronized
    fun clear() {
        nextIndex = 0
        filled = 0
    }

    /** Retained samples, oldest first. */
    @Synchronized
    fun snapshot(): LongArray {
        val out = LongArray(filled)
        val start = if (filled < capacity) 0 else nextIndex
        for (index in 0 until filled) {
            out[index] = values[(start + index) % capacity]
        }
        return out
    }

    /** Nearest-rank percentile, or null when there are no samples. */
    fun percentile(fraction: Double): Long? = percentileOf(sortedSnapshot(), fraction)

    fun stats(): MetricStats {
        val sorted = sortedSnapshot()
        if (sorted.isEmpty()) return MetricStats.EMPTY
        return MetricStats(
            count = sorted.size,
            p50Ms = percentileOf(sorted, 0.50),
            p95Ms = percentileOf(sorted, 0.95),
        )
    }

    private fun sortedSnapshot(): LongArray = snapshot().apply { sort() }

    companion object {
        const val DEFAULT_CAPACITY = 256

        /**
         * Nearest-rank percentile over an ascending array. Returns null for an
         * empty array — absence, not zero.
         */
        fun percentileOf(sorted: LongArray, fraction: Double): Long? {
            if (sorted.isEmpty()) return null
            if (fraction <= 0.0) return sorted.first()
            if (fraction >= 1.0) return sorted.last()
            val rank = kotlin.math.ceil(fraction * sorted.size).toInt()
                .coerceIn(1, sorted.size)
            return sorted[rank - 1]
        }
    }
}

/**
 * Phase 18 local-only performance telemetry.
 *
 * Properties this type guarantees:
 *  - everything stays in this process: no upload, no network, no disk. A
 *    snapshot is only ever read by diagnostics (e.g. [DiagnosticsBreadcrumbs]
 *    contributors) or debug tooling;
 *  - bounded memory: one fixed-size ring buffer per metric;
 *  - no fabrication: an unrecorded metric reports [MetricStats.EMPTY] /
 *    [MetricStats.NO_SAMPLES] rather than a made-up zero or p95;
 *  - no payload: the API only accepts a metric and a millisecond duration, so
 *    an SMS body, a phone number or a token cannot be handed to it.
 *
 * Producers are wired in the sync, Home, conversation, read and repair hot paths.
 * A missing runtime sample remains "no samples"; this class never manufactures
 * latency numbers.
 */
object PerfTelemetry {

    /** Latest canonical Room mutation commit, consumed by Home's authoritative Flow. */
    private val lastRoomCommitNanos = java.util.concurrent.atomic.AtomicLong(0L)
    private val markReadStarts = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    private val buffers: Map<PerfMetric, RollingSamples> =
        PerfMetric.entries.associateWith { RollingSamples() }

    /** Marks that the sync core just committed durable Room state. */
    fun noteRoomCommit() {
        lastRoomCommitNanos.set(System.nanoTime())
    }

    /** Records commit -> Home-observed latency once; stale marks are discarded. */
    fun recordRoomCommitToHome() {
        val started = lastRoomCommitNanos.getAndSet(0L)
        if (started != 0L) recordSince(PerfMetric.ROOM_COMMIT_TO_HOME_OBSERVED, started)
    }

    fun noteMarkReadRequested(threadId: Long) {
        if (threadId > 0L) markReadStarts[threadId] = System.nanoTime()
    }

    fun recordMarkReadObserved(threadId: Long) {
        if (threadId <= 0L) return
        markReadStarts.remove(threadId)?.let { recordSince(PerfMetric.MARK_READ_TO_UNREAD_ZERO, it) }
    }

    /** Records a completed measurement. Negative durations are ignored. */
    fun record(metric: PerfMetric, durationMs: Long) {
        if (durationMs < 0L) return
        buffers.getValue(metric).add(durationMs)
    }

    /** Monotonic start mark for [recordSince]. */
    fun mark(): Long = System.nanoTime()

    /** Milliseconds elapsed since [startNanos], never negative. */
    fun elapsedMs(startNanos: Long): Long =
        ((System.nanoTime() - startNanos) / 1_000_000L).coerceAtLeast(0L)

    /** Records the elapsed time since a [mark] for [metric]. */
    fun recordSince(metric: PerfMetric, startNanos: Long) {
        record(metric, elapsedMs(startNanos))
    }

    fun stats(metric: PerfMetric): MetricStats = buffers.getValue(metric).stats()

    fun hasSamples(metric: PerfMetric): Boolean = buffers.getValue(metric).size() > 0

    fun reset(metric: PerfMetric) {
        buffers.getValue(metric).clear()
    }

    fun resetAll() {
        for (buffer in buffers.values) buffer.clear()
    }

    /** Every metric, including the untouched ones (which report no samples). */
    fun snapshot(): Map<PerfMetric, MetricStats> =
        PerfMetric.entries.associateWith { stats(it) }

    /** One privacy-safe summary line for diagnostics. */
    fun describe(): String = PerfMetric.entries.joinToString(separator = " | ") { metric ->
        metric.name + " " + stats(metric).describe()
    }
}
