package com.autonomousone.messages.eve

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.PriorityQueue
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * Priority send-queue implementing the EVE "Custom HTTP" provider contract.
 *
 * EVE POSTs /send with an Idempotency-Key; this queue persists a stable
 * requestId per key, processes messages highest-priority-first through a
 * single worker thread, and exposes per-request status/cancel plus queue
 * capacity — exactly what the EVE panel polls.
 *
 * Status flow: QUEUED → ACTIVE → SENT | FAILED (QUEUED → CANCELLED).
 */
object EveSmsQueue {

    private const val TAG = "EVE_QUEUE"
    const val ANNOUNCEMENT_LIMIT = 500
    const val RECOMMENDED_BATCH_SIZE = 50
    const val RETRY_AFTER_SECONDS = 30
    private const val MAX_IDEMPOTENCY_KEYS = 500
    private const val MAX_PERSISTED_RECORDS = 300

    val PRIORITY_LEVELS: Map<String, Int> = mapOf(
        "critical" to 1,
        "expired" to 3,
        "expiring" to 6,
        "announcement" to 10
    )

    enum class Status { QUEUED, ACTIVE, SENT, FAILED, CANCELLED }

    data class Record(
        val requestId: String,
        val jobId: String,
        val to: String,
        val text: String,
        val priority: String,
        val priorityLevel: Int,
        val status: Status,
        val createdAt: Long,
        val sentAt: Long = 0L,
        val failedReason: String? = null,
        val submittedOnce: Boolean = false
    ) {
        val terminal: Boolean get() = status == Status.SENT || status == Status.FAILED || status == Status.CANCELLED
        val successful: Boolean get() = status == Status.SENT
    }

    /** Pluggable persistence so the queue core stays JVM-unit-testable. */
    interface Store {
        fun load(): Pair<List<Record>, Map<String, String>>
        fun save(records: List<Record>, idempotency: Map<String, String>)
    }

    class MemoryStore : Store {
        var lastRecords: List<Record> = emptyList()
            private set
        var lastIdem: Map<String, String> = emptyMap()
            private set
        override fun load(): Pair<List<Record>, Map<String, String>> = emptyList<Record>() to emptyMap()
        override fun save(records: List<Record>, idempotency: Map<String, String>) {
            lastRecords = records.toList()
            lastIdem = idempotency.toMap()
        }
    }

    /** SharedPreferences-backed store used at runtime on the device. */
    class SharedPrefsStore(context: Context) : Store {
        private val prefs = context.getSharedPreferences("eve_queue_prefs", Context.MODE_PRIVATE)

        override fun load(): Pair<List<Record>, Map<String, String>> = try {
            val list = mutableListOf<Record>()
            val arr = JSONArray(prefs.getString(KEY_RECORDS, "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    Record(
                        requestId = o.getString("requestId"),
                        jobId = o.getString("jobId"),
                        to = o.getString("to"),
                        text = o.getString("text"),
                        priority = o.getString("priority"),
                        priorityLevel = o.getInt("priorityLevel"),
                        status = Status.valueOf(o.getString("status")),
                        createdAt = o.getLong("createdAt"),
                        sentAt = o.optLong("sentAt", 0L),
                        failedReason = o.optString("failedReason", "").ifBlank { null },
                        submittedOnce = o.optBoolean("submittedOnce", false)
                    )
                )
            }
            val idemObj = JSONObject(prefs.getString(KEY_IDEM, "{}") ?: "{}")
            val idem = mutableMapOf<String, String>()
            idemObj.keys().forEach { idem[it] = idemObj.getString(it) }
            list to idem
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load EVE queue state", e)
            emptyList<Record>() to emptyMap()
        }

        override fun save(records: List<Record>, idempotency: Map<String, String>) {
            try {
                val arr = JSONArray()
                records.takeLast(MAX_PERSISTED_RECORDS).forEach { r ->
                    arr.put(
                        JSONObject()
                            .put("requestId", r.requestId)
                            .put("jobId", r.jobId)
                            .put("to", r.to)
                            .put("text", r.text)
                            .put("priority", r.priority)
                            .put("priorityLevel", r.priorityLevel)
                            .put("status", r.status.name)
                            .put("createdAt", r.createdAt)
                            .put("sentAt", r.sentAt)
                            .put("failedReason", r.failedReason ?: "")
                            .put("submittedOnce", r.submittedOnce)
                    )
                }
                val idemObj = JSONObject()
                idempotency.forEach { (k, v) -> idemObj.put(k, v) }
                prefs.edit()
                    .putString(KEY_RECORDS, arr.toString())
                    .putString(KEY_IDEM, idemObj.toString())
                    .apply()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist EVE queue state", e)
            }
        }

        companion object {
            private const val KEY_RECORDS = "records_json"
            private const val KEY_IDEM = "idempotency_json"
        }
    }

    private data class Job(val requestId: String, val level: Int, val seq: Long)

    data class EnqueueResult(val record: Record, val created: Boolean)
    data class CancelResult(val ok: Boolean, val reason: String? = null)

    private val records = LinkedHashMap<String, Record>()
    private val idempotency = HashMap<String, String>()
    // Highest priority first; FIFO within the same priority via insertion seq.
    private val queue = PriorityQueue<Job>(11, compareBy({ it.level }, { it.seq }))
    private var seq = 0L

    private lateinit var store: Store
    @Volatile private var senderFn: ((String, String) -> Boolean)? = null
    @Volatile private var running = false
    private var worker: Thread? = null
    private val persistExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "eve-persist").apply { isDaemon = true }
    }

    val isRunning: Boolean get() = running

    /** Starts the queue against device persistence. No-op when already running. */
    @Synchronized
    fun start(context: Context, sender: (String, String) -> Boolean) {
        if (running) return
        bootstrap(SharedPrefsStore(context.applicationContext), sender)
    }

    /** Shared setup used by both production start and tests. */
    internal fun bootstrap(storeImpl: Store, sender: (String, String) -> Boolean) {
        store = storeImpl
        senderFn = sender
        synchronized(records) {
            records.clear()
            idempotency.clear()
            queue.clear()
            seq = 0
            val (loaded, loadedIdem) = store.load()
            for (r in loaded.sortedBy { it.createdAt }) {
                var rec = r
                if (!rec.terminal && rec.status == Status.ACTIVE) {
                    rec = rec.copy(status = Status.QUEUED) // interrupted mid-send → requeue
                }
                records[rec.requestId] = rec
                if (!rec.terminal) offer(rec.requestId, rec.priorityLevel)
            }
            idempotency.putAll(loadedIdem)
        }
        running = true
        worker = Thread {
            while (running) {
                try {
                    if (!drainOne()) Thread.sleep(400)
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Worker loop error", e)
                }
            }
        }.apply {
            name = "eve-sender"
            isDaemon = true
            start()
        }
        Log.i(TAG, "EVE queue started")
    }

    @Synchronized
    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
    }

    /**
     * Accepts a send request. When [idempotencyKey] was seen before, returns
     * the ORIGINAL record with created=false — no duplicate SMS is created.
     */
    fun enqueue(to: String, text: String, priority: String, idempotencyKey: String?): EnqueueResult {
        synchronized(records) {
            if (!idempotencyKey.isNullOrBlank()) {
                idempotency[idempotencyKey]?.let { existingId ->
                    records[existingId]?.let { return EnqueueResult(it, created = false) }
                }
            }
            val level = PRIORITY_LEVELS[priority] ?: PRIORITY_LEVELS.getValue("announcement")
            val requestId = newId("sms_", 20)
            val record = Record(
                requestId = requestId,
                jobId = newId("job_", 12),
                to = to,
                text = text,
                priority = PRIORITY_LEVELS.entries.firstOrNull { it.value == level }?.key ?: "announcement",
                priorityLevel = level,
                status = Status.QUEUED,
                createdAt = System.currentTimeMillis()
            )
            records[requestId] = record
            if (!idempotencyKey.isNullOrBlank()) {
                idempotency[idempotencyKey] = requestId
                trimIdempotency()
            }
            offer(requestId, level)
            persistAsync()
            return EnqueueResult(record, created = true)
        }
    }

    fun status(requestId: String): Record? = synchronized(records) { records[requestId] }

    /** Cancels a QUEUED message. Returns ok=false + reason otherwise, null if unknown. */
    fun cancel(requestId: String): CancelResult? = synchronized(records) {
        val rec = records[requestId] ?: return null
        when (rec.status) {
            Status.QUEUED -> {
                records[requestId] = rec.copy(status = Status.CANCELLED)
                queue.removeAll { it.requestId == requestId }
                persistAsync()
                CancelResult(ok = true)
            }
            else -> CancelResult(ok = false, reason = "not_cancellable")
        }
    }

    /** Pending (queued) counts per priority name. */
    fun pendingByPriority(): Map<String, Int> = synchronized(records) {
        val counts = linkedMapOf(
            "critical" to 0, "expired" to 0, "expiring" to 0, "announcement" to 0
        )
        records.values.forEach {
            if (it.status == Status.QUEUED) counts[it.priority] = (counts[it.priority] ?: 0) + 1
        }
        counts
    }

    fun totalPending(): Int = synchronized(records) {
        records.values.count { it.status == Status.QUEUED || it.status == Status.ACTIVE }
    }

    /**
     * Takes the next queued job (highest priority first) and sends it.
     * Returns false when nothing was processed. Also driven directly by tests.
     */
    fun drainOne(): Boolean {
        val job = synchronized(records) { queue.poll() } ?: return false
        val current = synchronized(records) { records[job.requestId] } ?: return true
        if (current.status != Status.QUEUED) return true // cancelled meanwhile

        synchronized(records) {
            records[job.requestId] = current.copy(status = Status.ACTIVE, submittedOnce = true)
            persistAsync()
        }
        val fn = senderFn
        val ok = try {
            fn?.invoke(current.to, current.text) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Send failed for ${job.requestId}", e)
            false
        }
        synchronized(records) {
            val base = records[job.requestId] ?: current
            records[job.requestId] = if (ok) {
                base.copy(status = Status.SENT, sentAt = System.currentTimeMillis())
            } else {
                base.copy(status = Status.FAILED, failedReason = "provider_error")
            }
            persistAsync()
        }
        return true
    }

    private fun offer(requestId: String, level: Int) {
        seq += 1
        queue.offer(Job(requestId, level, seq))
    }

    private fun trimIdempotency() {
        val iterator = idempotency.entries.iterator()
        while (idempotency.size > MAX_IDEMPOTENCY_KEYS && iterator.hasNext()) {
            iterator.next()
            iterator.remove()
        }
    }

    private fun persistAsync() {
        val snapshot = synchronized(records) { records.values.toList() to idempotency.toMap() }
        persistExecutor.execute { store.save(snapshot.first, snapshot.second) }
    }

    private fun newId(prefix: String, hexLength: Int): String =
        prefix + java.util.UUID.randomUUID().toString().replace("-", "").take(hexLength)

    /** Test hook: clears all state and installs the given store/sender. */
    fun resetForTest(storeImpl: Store = MemoryStore(), sender: (String, String) -> Boolean = { _, _ -> true }) {
        stop()
        synchronized(records) {
            records.clear(); idempotency.clear(); queue.clear(); seq = 0
        }
        store = storeImpl
        senderFn = sender
    }
}

/** ISO-8601 UTC timestamp for EVE status responses ("2026-08-23T10:00:02Z"). */
internal fun eveIsoTimestamp(epochMillis: Long): String? {
    if (epochMillis <= 0) return null
    val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    fmt.timeZone = TimeZone.getTimeZone("UTC")
    return fmt.format(Date(epochMillis))
}
