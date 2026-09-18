package com.autonomousone.messages.data

/**
 * Exact provider identities whose STRICT read previously failed, kept so the
 * exact work can be retried instead of being lost.
 *
 * IN-PROCESS ONLY, deliberately NOT durable: process death drops these entries.
 * That is acceptable because the provider still holds the truth — the next
 * observer event, the next pull, or startup reconciliation re-derives the row.
 * What must never happen is the opposite: turning "I could not read" into
 * "it is gone".
 *
 * A failed exact read is also NOT routed to TailDelta: TailDelta only finds rows
 * newer than the watermark, so it cannot repair an older row. Preserving the
 * exact (source, providerId) is the only honest option.
 */
object PendingExactRepairs {

    enum class Source { SMS, MMS }

    data class Entry(
        val source: Source,
        val providerId: Long,
        val attempts: Int,
        val nextRetryAt: Long
    )

    private const val MAX_ENTRIES = 256

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()

    /** Capped backoff: 1s, 2s, 4s ... up to 32s. */
    private fun backoffMs(attempt: Int): Long = 1_000L shl (attempt - 1).coerceIn(0, 5)

    private fun key(source: Source, providerId: Long) = source.name + ":" + providerId

    fun note(source: Source, providerId: Long, now: Long) {
        if (providerId <= 0L) return
        synchronized(lock) {
            val k = key(source, providerId)
            val attempts = (entries[k]?.attempts ?: 0) + 1
            entries[k] = Entry(source, providerId, attempts, now + backoffMs(attempts))
            while (entries.size > MAX_ENTRIES) {
                val oldest = entries.keys.firstOrNull() ?: break
                entries.remove(oldest)
            }
        }
    }

    /** Entries whose retry is due. They stay queued until [clear]. */
    fun due(now: Long): List<Entry> = synchronized(lock) {
        entries.values.filter { it.nextRetryAt <= now }
    }

    fun clear(source: Source, providerId: Long) {
        synchronized(lock) { entries.remove(key(source, providerId)) }
    }

    fun size(): Int = synchronized(lock) { entries.size }

    fun clearForTest() = synchronized(lock) { entries.clear() }
}
