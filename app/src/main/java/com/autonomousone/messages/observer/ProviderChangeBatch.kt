package com.autonomousone.messages.observer

/**
 * What a ContentObserver burst actually told us, accumulated over the coalesce
 * window instead of being thrown away.
 *
 * Why this type exists: the observer used to dispatch the leading URI and then
 * a trailing `null` ("unknown change"). ChangeRouter mapped every unknown
 * notification to ReconcileRequest.FullSync, so ONE exact-row INSERT became
 *
 *   O(1) exact mutation  ->  150 ms later  ->  FullSync
 *
 * (a dual-source re-read of the newest 500 SMS + 500 MMS rows, plus a
 * conversation projection rebuild). A multipart/MMS arrival fires several
 * notifications, so ordinary traffic escalated to full work constantly.
 *
 * The accumulator keeps the narrowest repair the burst can justify, so the
 * trailing dispatch is never blind:
 *
 *   exact row id  -> exact mutation
 *   thread id     -> that thread only
 *   nothing known -> the (rare) genuinely unknown provider event
 *
 * Pure string parsing on purpose: it is unit-testable on the JVM exactly like
 * ChangeRouter.extractRowIdFromPath, with no Android Uri stub in the way.
 */
data class ProviderChangeBatch(
    val smsIds: Set<Long> = emptySet(),
    val mmsIds: Set<Long> = emptySet(),
    val threadIds: Set<Long> = emptySet(),
    /** Notifications with no usable identity at all. */
    val unknownCount: Int = 0
) {

    /** True when at least one exact provider row can be read directly. */
    val hasExactRows: Boolean get() = smsIds.isNotEmpty() || mmsIds.isNotEmpty()

    /** True when the burst carries no information whatsoever. */
    val isUnknownOnly: Boolean
        get() = !hasExactRows && threadIds.isEmpty() && unknownCount > 0

    val totalEvents: Int get() = smsIds.size + mmsIds.size + threadIds.size + unknownCount

    fun merge(other: ProviderChangeBatch): ProviderChangeBatch = ProviderChangeBatch(
        smsIds = smsIds + other.smsIds,
        mmsIds = mmsIds + other.mmsIds,
        threadIds = threadIds + other.threadIds,
        unknownCount = unknownCount + other.unknownCount
    )

    companion object {

        val EMPTY = ProviderChangeBatch()

        /**
         * Classifies one provider notification.
         *
         * @param authority the URI authority ("sms", "mms", "mms-sms", ...)
         * @param path      the URI path ("/12345", "/thread/42", ...)
         */
        fun from(authority: String?, path: String?): ProviderChangeBatch {
            if (path.isNullOrBlank()) return ProviderChangeBatch(unknownCount = 1)

            // Thread-scoped notification: content://sms/thread/123 or .../thread.
            // The trailing number is a THREAD id, never a row id.
            threadIdFromPath(path)?.let { return ProviderChangeBatch(threadIds = setOf(it)) }

            val id = extractRowIdFromPath(path)
                ?: return ProviderChangeBatch(unknownCount = 1)

            return if (authority?.startsWith("mms") == true) {
                ProviderChangeBatch(mmsIds = setOf(id))
            } else {
                ProviderChangeBatch(smsIds = setOf(id))
            }
        }

        /**
         * content://sms/thread/123 -> 123 ; content://sms/thread -> null
         * Returns null when the path is not a thread URI.
         */
        fun threadIdFromPath(path: String): Long? {
            val marker = "/thread/"
            if (path.endsWith("/thread")) return null
            val idx = path.indexOf(marker)
            if (idx < 0) return null
            val tail = path.substring(idx + marker.length)
            return tail.toLongOrNull()
        }

        /**
         * Try to extract a numeric row ID from a content URI path.
         * content://sms/12345 -> path "/12345" -> 12345
         * content://sms -> path "/sms" -> null
         * content://sms/thread/123 -> null — 123 is a THREAD id, not a row id;
         * reading it as _ID=123 would upsert a random unrelated message.
         */
        fun extractRowIdFromPath(path: String?): Long? {
            if (path.isNullOrBlank()) return null
            if (path.contains("/thread/") || path.endsWith("/thread")) return null
            val lastSegment = path.substringAfterLast('/')
            if (lastSegment.isBlank() || !lastSegment.all { it.isDigit() }) return null
            return lastSegment.toLongOrNull()
        }
    }
}
