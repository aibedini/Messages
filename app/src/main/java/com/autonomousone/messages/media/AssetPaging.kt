package com.autonomousone.messages.media

/**
 * Pure paging arithmetic for the Media / Links / Files tabs.
 *
 * The tabs page the `message_assets` window of ONE conversation, so the bound is
 * small — but it is still a bound, and "paged, newest first" must not degrade
 * into "load the whole conversation's media". Keeping the arithmetic here (rather
 * than inline in the ViewModel) is what lets the boundary behaviour be unit
 * tested: the last page, an exact-multiple page, and an absurd request are all
 * value-level assertions.
 */
object AssetPaging {

    /** Rows per tab page. Small enough to paint instantly, large enough to fill. */
    const val PAGE_SIZE = 60

    /** Hard cap, so no caller can ask for an unbounded page. */
    const val MAX_PAGE_SIZE = 200

    /** Clamp any requested limit into `1..MAX_PAGE_SIZE`. */
    fun limit(requested: Int): Int = requested.coerceIn(1, MAX_PAGE_SIZE)

    /** Offset of the NEXT page given how many rows are already loaded. */
    fun nextOffset(loadedRows: Int): Int = loadedRows.coerceAtLeast(0)

    /**
     * True when the page just returned was the LAST one: a short page (or an
     * empty one) means there is nothing after it.
     */
    fun reachedEnd(returnedRows: Int, requestedLimit: Int): Boolean =
        returnedRows < limit(requestedLimit)
}
