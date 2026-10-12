package com.autonomousone.messages.ui.selection

import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.repository.MessageIdentity

/**
 * Resolving a selection to the ONE message Message Details may describe.
 *
 * ## Why this is a separate, pure rule
 *
 * Details is about a single message, and the identity it needs is the COMPOSITE `(source, providerId)`
 * pair, never a numeric `_id`: an SMS and an MMS can both be row 100, so resolving by number alone can
 * open the wrong message. Deriving the identity from the selected message's BODY would be worse still —
 * two messages can share a body — so the selection key is the only thing consulted.
 *
 * Pure and Android-free, so the cases that matter (multi-selection, a vanished row, a filtered window)
 * are pinned here instead of being re-derived inside a composable.
 */
object MessageSelectionResolver {

    /**
     * @param selectedKeys the composite keys currently selected.
     * @param displayedRows the rows the user can actually SEE — the conversation window, or the bounded
     *   search-hit window while search is active. Passing the wrong window is how a visible message
     *   fails to resolve.
     * @return the single selected message, or null.
     *
     * Null is the safe answer for every ambiguous or missing case:
     *  - a MULTI-selection has no single subject, and picking the "first" would open a message the user
     *    did not choose;
     *  - a key whose row has vanished (deleted, or outside the current window) must not fall back to a
     *    neighbouring row — a wrong message is worse than no navigation.
     */
    fun resolveSingle(selectedKeys: Set<MessageIdentity.Key>, displayedRows: List<Sms>): Sms? {
        if (selectedKeys.size != 1) return null
        val only = selectedKeys.first()
        return displayedRows.firstOrNull { MessageIdentity.keyOf(it.id) == only }
    }
}
