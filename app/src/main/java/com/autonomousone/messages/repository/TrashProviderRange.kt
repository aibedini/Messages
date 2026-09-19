package com.autonomousone.messages.repository

import android.provider.Telephony
import com.autonomousone.messages.data.MessageEntity

/**
 * The provider-side range a TRASH tombstone stands for — as a PURE function.
 *
 * WHY THIS IS SEPARATE AND ANDROID-FREE
 * -------------------------------------
 * A permanent purge performs an irreversible provider write. The one thing it
 * must never do is delete a message that arrived AFTER the conversation was
 * deleted, so the boundary arithmetic is pinned by a JVM test
 * (`TrashProviderRangeTest`) instead of being buried in a ContentResolver call.
 *
 * The semantics are the EXACT complement of the tombstone's ACTIVE-UI predicate
 * ([MessageCutoff.HIDDEN_BY_TOMBSTONE_SQL]), evaluated per provider source:
 *
 *   same source as the cutoff  -> date < cutoffDate
 *                                 OR (date = cutoffDate AND providerId <= cutoffProviderId)
 *   other source               -> date < cutoffDate   (same-date rows are NEW)
 *
 * The second rule is the cross-source guard. A naive shared `<=` would classify a
 * brand-new SMS that shares the millisecond of an MMS cutoff as deleted history
 * ("sms" < "mms") and a purge would then DESTROY it, which is strictly worse than
 * merely hiding it. Rows on the same date from the other source are therefore
 * never part of the snapshot.
 *
 * UNIT TRAP: `Telephony.Mms.DATE` — and therefore every MMS row in the provider —
 * is in SECONDS while `Telephony.Sms.DATE` and the whole app model are in
 * milliseconds. The conversion happens here, once, floor-divided (an MMS row's
 * modelled millis value is always `seconds * 1000`, so the division is exact for
 * rows this app wrote or read).
 */
object TrashProviderRange {

    /** One provider query's selection. */
    data class Selection(
        val selection: String,
        val selectionArgs: Array<String>
    ) {
        // Array equality is identity-based; data-class equality is only used by
        // tests, which compare `selectionArgs.toList()`.
        override fun equals(other: Any?): Boolean =
            other is Selection && other.selection == selection &&
                other.selectionArgs.toList() == selectionArgs.toList()

        override fun hashCode(): Int = 31 * selection.hashCode() + selectionArgs.contentHashCode()
    }

    /**
     * The selection for ONE source, or null when the source is not a provider
     * source this app mirrors.
     */
    fun selectionFor(
        source: String,
        threadId: Long,
        cutoffDate: Long,
        cutoffSource: String,
        cutoffProviderId: Long
    ): Selection? {
        if (threadId <= 0L) return null
        val sameSource = source == cutoffSource
        return when (source) {
            MessageEntity.SOURCE_SMS -> Selection(
                selection = if (sameSource) {
                    "${SMS_THREAD} = ? AND (${SMS_DATE} < ? OR (${SMS_DATE} = ? AND ${SMS_ID} <= ?))"
                } else {
                    "${SMS_THREAD} = ? AND ${SMS_DATE} < ?"
                },
                selectionArgs = if (sameSource) {
                    arrayOf(
                        threadId.toString(),
                        cutoffDate.toString(),
                        cutoffDate.toString(),
                        cutoffProviderId.toString()
                    )
                } else {
                    arrayOf(threadId.toString(), cutoffDate.toString())
                }
            )

            MessageEntity.SOURCE_MMS -> {
                val cutoffSeconds = cutoffDate / 1000L
                Selection(
                    selection = if (sameSource) {
                        "${MMS_THREAD} = ? AND (${MMS_DATE} < ? OR (${MMS_DATE} = ? AND ${MMS_ID} <= ?))"
                    } else {
                        "${MMS_THREAD} = ? AND ${MMS_DATE} < ?"
                    },
                    selectionArgs = if (sameSource) {
                        arrayOf(
                            threadId.toString(),
                            cutoffSeconds.toString(),
                            cutoffSeconds.toString(),
                            cutoffProviderId.toString()
                        )
                    } else {
                        arrayOf(threadId.toString(), cutoffSeconds.toString())
                    }
                )
            }

            else -> null
        }
    }

    /**
     * Kotlin mirror of [selectionFor] as a predicate — used by tests and by
     * diagnostics, never as a substitute for the SQL.
     */
    fun isInSnapshot(
        date: Long,
        source: String,
        providerId: Long,
        cutoffDate: Long,
        cutoffSource: String,
        cutoffProviderId: Long
    ): Boolean {
        if (date != cutoffDate) return date < cutoffDate
        if (source != cutoffSource) return false
        return providerId <= cutoffProviderId
    }

    // Provider column names. `Telephony.*` here is a compile-time String constant
    // (inlined by the compiler, exactly like ThreadPager does), so this object
    // stays Android-free and JVM-testable while never re-typing a column name.
    private const val SMS_ID = Telephony.Sms._ID
    private const val SMS_THREAD = Telephony.Sms.THREAD_ID
    private const val SMS_DATE = Telephony.Sms.DATE
    private const val MMS_ID = Telephony.Mms._ID
    private const val MMS_THREAD = Telephony.Mms.THREAD_ID
    private const val MMS_DATE = Telephony.Mms.DATE
}
