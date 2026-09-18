package com.autonomousone.messages.repository

import com.autonomousone.messages.model.Sms
import kotlin.math.abs

/**
 * Stable identity of one conversation row: the composite provider key
 * (source, providerId).
 *
 * SMS and MMS are two different provider tables whose `_id` sequences are
 * independent, so SMS id 52 and MMS id 52 must never be treated as the same
 * row. The UI compatibility boundary keeps the historical convention of
 * SmsRepository/Entities: an SMS model id is positive, an MMS model id is
 * NEGATED. Deriving the identity from the sign + magnitude therefore gives
 * every merge/dedup/paging site a collision-free composite key without
 * changing the `Sms` model (which other owners control).
 *
 * Pure and Android-free on purpose: the identity rule is exercised by
 * ConversationWindowTest on the JVM.
 */
object MessageIdentity {

    const val SOURCE_SMS = "sms"
    const val SOURCE_MMS = "mms"

    data class Key(val source: String, val providerId: Long)

    /** "sms" for a positive/model-SMS id, "mms" for a negated/model-MMS id. */
    fun sourceOf(id: Long): String = if (id < 0L) SOURCE_MMS else SOURCE_SMS

    /** The real provider `_id`, sign-stripped. */
    fun providerIdOf(id: Long): Long = abs(id)

    /** Composite key for a raw model id. */
    fun keyOf(id: Long): Key = Key(sourceOf(id), providerIdOf(id))

    /** Composite key for a UI row. */
    fun keyOf(row: Sms): Key = keyOf(row.id)
}

/**
 * Pure window/merge state for one OPEN conversation.
 *
 * Model: `ReactiveRoomTail + LoadedOlderPages + OptimisticRows = VisibleMessages`.
 *
 * The three layers are merged — never replaced — so:
 *  - a Room invalidation refresh (reactive tail) updates/read/status of the
 *    rows it carries WITHOUT deleting pages the user already scrolled to;
 *  - a keyset page prepended by the pager extends history upward;
 *  - optimistic (not-yet-persisted) sends stay visible until the provider
 *    confirms them.
 *
 * Everything here is a pure function over `List<Sms>`, so the window
 * invariants are unit-testable without Room, Compose or a ContentResolver.
 */
object ConversationWindow {

    /**
     * Rows painted from Room on open. Room is a single merged table, so this
     * is the union of the provider pager's per-source initial windows.
     */
    const val OPEN_WINDOW = 20

    /** Rows per source on an older/interior keyset page (see ThreadPager). */
    const val OLDER_PAGE = ThreadPager.PAGE_PER_SOURCE

    /**
     * The ONE canonical order every window mutation ends in.
     *
     * Mirrors Home's newest-row rule `date DESC, source DESC, providerId DESC`
     * (MessageDao.newestPerThread), read in the ascending order the
     * conversation list stores: `date ASC, source ASC, providerId ASC`. The
     * LAST row at an equal timestamp is therefore exactly the row Home picked
     * ("mms" < "sms" ascending, so the SMS copy wins the newest slot), and
     * ties are broken deterministically instead of by insertion order.
     */
    val canonical: Comparator<Sms> =
        compareBy<Sms> { it.date }
            .thenBy { MessageIdentity.sourceOf(it.id) }
            .thenBy { MessageIdentity.providerIdOf(it.id) }

    /**
     * Open-time browser: keep only the newest [limit] rows, in canonical
     * ascending order. A 100 000-message conversation still opens reading at
     * most [limit] rows because every caller reads a bounded provider/Room
     * page first and this caps the painted window.
     */
    fun boundedNewest(rows: List<Sms>, limit: Int = OPEN_WINDOW): List<Sms> {
        if (limit <= 0 || rows.isEmpty()) return emptyList()
        val ordered = rows.sortedWith(canonical)
        return if (ordered.size <= limit) ordered
        else ordered.subList(ordered.size - limit, ordered.size)
    }

    /**
     * Folds a reactive Room tail emission into the visible window.
     *
     * @param visible every row the user can currently see: older pages plus
     *   the current tail plus optimistic rows.
     * @param roomTail the bounded newest-window rows Room just emitted.
     * @param optimistic not-yet-persisted sends that must survive if Room has
     *   not confirmed them yet.
     *
     * Invariants:
     *  - every identity present in [visible] but not in [roomTail] is KEPT
     *    (an older page is never dropped by a tail emission);
     *  - for an identity present in both, the Room copy wins (read flag /
     *    status / body are the fresh authoritative values);
     *  - SMS 52 and MMS 52 are distinct identities and both survive.
     */
    fun mergeRoomTail(
        visible: List<Sms>,
        roomTail: List<Sms>,
        optimistic: List<Sms> = emptyList()
    ): List<Sms> {
        val byKey = LinkedHashMap<MessageIdentity.Key, Sms>(visible.size + roomTail.size)
        // 1. LoadedOlderPages + the current visible history, kept verbatim.
        visible.forEach { byKey[MessageIdentity.keyOf(it)] = it }
        // 2. ReactiveRoomTail is authoritative for exactly the identities it
        //    carries — it never removes a row it does not mention.
        roomTail.forEach { byKey[MessageIdentity.keyOf(it)] = it }
        // 3. OptimisticRows Room has not confirmed stay visible.
        optimistic.forEach { opt ->
            if (byKey.values.none { ThreadMerge.sameMessage(it, opt) }) {
                byKey[MessageIdentity.keyOf(opt)] = opt
            }
        }
        return byKey.values.sortedWith(canonical)
    }

    /** Composite identity shortcut for ViewModel dedup sites. */
    fun identity(id: Long): MessageIdentity.Key = MessageIdentity.keyOf(id)
}
