package com.autonomousone.messages.ui.selection

/**
 * FEATURE 9 (multi-select) — the ONE selection reducer shared by BOTH list
 * surfaces (Home conversations and conversation messages).
 *
 * Deliberately Android-free, Compose-free and immutable: the rules
 * (start / toggle / add / remove / clear / exit / survive a durable list
 * refresh) are pinned by a plain-JVM unit test instead of being re-derived
 * inside a composable, and holding the value in a `mutableStateOf` gives
 * recomposition for free.
 *
 * [K] is the COMPOSITE identity of a row:
 *  - `MessageIdentity.Key(source, providerId)` for messages;
 *  - `Long` (threadId) for conversations.
 * A raw message id `Long` must NEVER be used as a message key: SMS 100 and
 * MMS 100 are two different messages.
 *
 * Contract:
 *  - [start] enters selection mode with exactly one key (the long-press entry);
 *  - [toggle] adds or removes; removing the LAST key leaves selection mode;
 *  - [clear] always leaves selection mode;
 *  - [afterListRefresh] reconciles against a new durable list: keys still
 *    present survive untouched, keys that provably vanished are dropped, and an
 *    empty result exits selection mode. A refresh that still contains the
 *    selected rows therefore CANNOT drop or corrupt the selection — the
 *    reconciliation is explicit, never an implicit reset.
 */
data class SelectionState<K>(
    val keys: Set<K> = emptySet(),
    val active: Boolean = false
) {

    val count: Int get() = keys.size
    val isEmpty: Boolean get() = keys.isEmpty()

    /** Long-press entry: one key selected, selection mode on. */
    fun start(key: K): SelectionState<K> =
        SelectionState(LinkedHashSet<K>(1).also { it.add(key) }, active = true)

    /**
     * Tap behaviour while selection mode is on. Toggling the LAST selected key
     * off leaves selection mode (there is nothing left to act on).
     */
    fun toggle(key: K): SelectionState<K> {
        if (!active) return start(key)
        val next = LinkedHashSet(keys)
        if (!next.remove(key)) next.add(key)
        return if (next.isEmpty()) idle() else SelectionState(next, active = true)
    }

    fun add(key: K): SelectionState<K> {
        if (key in keys) return if (active) this else copy(active = true)
        val next = LinkedHashSet(keys)
        next.add(key)
        return SelectionState(next, active = true)
    }

    fun remove(key: K): SelectionState<K> {
        if (key !in keys) return this
        val next = LinkedHashSet(keys)
        next.remove(key)
        return if (next.isEmpty()) idle() else SelectionState(next, active = true)
    }

    /** Adds every key (never removes). Keeps the current mode. */
    fun addAll(keysToAdd: Collection<K>): SelectionState<K> {
        if (keysToAdd.isEmpty()) return this
        val next = LinkedHashSet(keys)
        next.addAll(keysToAdd)
        return SelectionState(next, active = true)
    }

    /** Leaves selection mode entirely. */
    fun clear(): SelectionState<K> = idle()

    /**
     * Explicit reconciliation against the durable list that is now rendered.
     *
     * An EMPTY [present] set is treated as "no evidence" and keeps the
     * selection: a transient empty Room emission (bootstrap, rebuild, a
     * filtered projection) must not silently drop a live selection.
     */
    fun afterListRefresh(present: Set<K>): SelectionState<K> {
        if (!active) return this
        if (present.isEmpty()) return this
        var dropped = false
        val kept = LinkedHashSet<K>(keys.size)
        keys.forEach { key ->
            if (key in present) kept.add(key) else dropped = true
        }
        if (!dropped) return this
        return if (kept.isEmpty()) idle() else SelectionState(kept, active = true)
    }

    override fun toString(): String = "SelectionState(active=$active, count=$count)"

    companion object {
        /** Nothing selected, selection mode off. */
        fun <K> idle(): SelectionState<K> = SelectionState(emptySet(), active = false)
    }
}

/**
 * FEATURE 10: the bulk-action snackbar text, from already-localized templates.
 *
 * Pure and unit-testable on purpose: the ONE rule that must never regress is
 * that a result with failures can never be reported as plain success. A partial
 * result always uses the partial template ("12 updated, 2 could not be
 * updated"), and a wholly failed action never uses the success template.
 */
fun formatBulkFeedback(
    successTemplate: String,
    partialTemplate: String,
    failureTemplate: String,
    succeeded: Int,
    failed: Int
): String = when {
    failed <= 0 -> successTemplate.format(succeeded)
    succeeded <= 0 -> failureTemplate.format(failed)
    else -> partialTemplate.format(succeeded, failed)
}
