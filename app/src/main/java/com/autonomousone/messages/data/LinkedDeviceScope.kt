package com.autonomousone.messages.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import com.autonomousone.messages.security.ScopeEntry
import com.autonomousone.messages.security.ScopeSelection
import com.autonomousone.messages.security.ScopeState

/**
 * LINKED DEVICE CONTROL — the durable per-conversation AI scope (LDV-004 / XR-GA-008).
 *
 * This is PR-E slice 2a: the Room state behind the pure model in
 * `security/ConversationScope.kt` (design `docs/status/ldv-004-cke-grant-design.md` §4.6).
 * It deliberately contains no UI, no publishing and no crypto — it only stores which
 * conversations an `AI_AGENT` device may read, and at which monotonic `scopeSequence`.
 *
 * ## Two tables, because they answer two different questions
 *
 * [LinkedDeviceScopeStateEntity] answers "what kind of scope does this device have, and at
 * which sequence?". [LinkedDeviceConversationScopeEntity] answers "which conversations are
 * enabled, and when was each one last decided?".
 *
 * `mode` is TEXT (`account` | `conversation`) rather than an enum so the additive migration
 * needs no type converter. The two values are exactly the wire `scopeMode` of the grant
 * producer in slice 1.
 *
 * ## How the slice-1 model maps onto the rows
 *
 *  - `mode=account` means [ScopeSelection.AccountWide]: every eligible conversation is
 *    granted, and any conversation rows are only historical tombstones (`enabled=0`).
 *  - `mode=conversation` with at least one `enabled=1` row is a
 *    [ScopeSelection.Conversations] selection.
 *  - `mode=conversation` with **no** enabled row is the revoked ("paused") state. An empty
 *    allowlist is not a legal scope in the pure model, so this encoding can never be
 *    confused with `AccountWide`; the state row still carries the last sequence/updatedAt
 *    and the disabled rows are the tombstones the publisher needs.
 *  - no state row at all means "never configured" (an unknown device), which is different
 *    from "explicitly paused". [LinkedDeviceScopeSnapshot.toScopeState] returns `null` for
 *    that case rather than inventing a decision.
 *
 * ## Monotonic `scopeSequence`
 *
 * Every write is guarded inside a Room `@Transaction`, so a late, lower-sequence write cannot
 * move `updatedAt`, resurrect a revoked conversation, or re-enable a paused device. The state
 * row only moves forward to a strictly greater sequence ([acceptsScopeSequence]). A
 * conversation row accepts its own batch's sequence as well ([acceptsConversationWrite]),
 * because the state row and its per-conversation rows share the one sequence the model
 * allocated for that decision; anything below the device's state is stale and ignored.
 */
@Entity(tableName = "linked_device_scope_state")
data class LinkedDeviceScopeStateEntity(
    @PrimaryKey val deviceId: String,
    /** [MODE_ACCOUNT] or [MODE_CONVERSATION] — the wire `scopeMode` values. */
    val mode: String,
    val scopeSequence: Long,
    val updatedAt: Long,
) {
    companion object {
        const val MODE_ACCOUNT = "account"
        const val MODE_CONVERSATION = "conversation"
    }
}

/**
 * One conversation's last scope decision for one device.
 *
 * The composite primary key is `(deviceId, conversationId)`; the index on `deviceId` is the
 * one `listEnabled`/`listAll` use. `enabled=0` is a tombstone, not a deleted row: the
 * publisher needs the conversation id and the sequence that revoked it.
 */
@Entity(
    tableName = "linked_device_conversation_scope",
    primaryKeys = ["deviceId", "conversationId"],
    indices = [Index(value = ["deviceId"])],
)
data class LinkedDeviceConversationScopeEntity(
    val deviceId: String,
    val conversationId: String,
    val enabled: Boolean,
    val scopeSequence: Long,
    val updatedAt: Long,
)

/** The rows [ScopeState.toEntities] would write for one device. */
data class LinkedDeviceScopeEntities(
    val state: LinkedDeviceScopeStateEntity,
    val conversations: List<LinkedDeviceConversationScopeEntity>,
) {
    fun toSnapshot(): LinkedDeviceScopeSnapshot =
        LinkedDeviceScopeSnapshot(state, conversations)
}

/**
 * Everything persisted for one device, read as one snapshot.
 *
 * The mapper is intentionally total and fail-closed: a missing state row is an unknown
 * device (`null`), an unknown [LinkedDeviceScopeStateEntity.mode] grants nothing, and
 * conversation rows belonging to another device are ignored.
 */
data class LinkedDeviceScopeSnapshot(
    val state: LinkedDeviceScopeStateEntity? = null,
    val conversations: List<LinkedDeviceConversationScopeEntity> = emptyList(),
) {
    private fun rows(): List<LinkedDeviceConversationScopeEntity> {
        val deviceId = state?.deviceId ?: return emptyList()
        return conversations.filter { it.deviceId == deviceId }
    }

    /**
     * The persisted [ScopeSelection], or `null` when the device has no active scope.
     *
     * `AccountWide` is the explicit account mode. A conversation mode with no enabled row is
     * the revoked state and therefore also `null` — never `Conversations(emptySet())`, which
     * the pure model rejects.
     */
    fun toSelection(): ScopeSelection? {
        val persisted = state ?: return null
        return when (persisted.mode) {
            LinkedDeviceScopeStateEntity.MODE_ACCOUNT -> ScopeSelection.AccountWide
            LinkedDeviceScopeStateEntity.MODE_CONVERSATION -> {
                val enabled = rows().filter { it.enabled }
                if (enabled.isEmpty()) {
                    null
                } else {
                    ScopeSelection.Conversations(
                        enabled.mapTo(sortedSetOf()) { it.conversationId }
                    )
                }
            }
            // Unknown mode: fail closed. An unrecognised future value must never be read as
            // "everything granted" (nor as a conversation allowlist we cannot interpret).
            else -> null
        }
    }

    /** The persisted state as the slice-1 model, or `null` for an unknown device. */
    fun toScopeState(): ScopeState? {
        val persisted = state ?: return null
        val selection = toSelection()
        val entries = rows()
            .map {
                ScopeEntry(
                    conversationId = it.conversationId,
                    scopeSequence = it.scopeSequence,
                    revoked = !it.enabled,
                    updatedAt = it.updatedAt,
                )
            }
            .distinctBy { it.conversationId }
            .sortedBy { it.conversationId }
        return ScopeState(
            scopeSequence = persisted.scopeSequence,
            revoked = selection == null,
            updatedAt = persisted.updatedAt,
            selection = selection,
            entries = entries,
        )
    }
}

/**
 * Rows for [state], ready to be written for [deviceId].
 *
 * `enabled` is derived from the authoritative [ScopeState.selection], not from
 * [ScopeEntry.revoked]: an entry that says "granted" but whose id is no longer selected is
 * written as a tombstone, so a stale entry can never widen the persisted scope. Selected ids
 * with no entry at all still get an enabled row at the state's sequence, so no grant can be
 * lost between the selection and its per-conversation record.
 *
 * A revoked state and an account-wide state both write `mode=conversation`/`mode=account`
 * respectively and only tombstone rows; they never produce an enabled conversation row.
 */
fun ScopeState.toEntities(deviceId: String): LinkedDeviceScopeEntities {
    require(deviceId.isNotBlank()) { "deviceId must not be blank" }
    val mode = when (selection) {
        ScopeSelection.AccountWide -> LinkedDeviceScopeStateEntity.MODE_ACCOUNT
        is ScopeSelection.Conversations, null -> LinkedDeviceScopeStateEntity.MODE_CONVERSATION
    }
    val activeIds = activeConversationIds

    // entries first, so a per-conversation sequence/updatedAt recorded by the model survives
    // the round trip; enabled is still decided solely by membership in the selection.
    val rows = LinkedHashMap<String, LinkedDeviceConversationScopeEntity>()
    for (entry in entries) {
        rows[entry.conversationId] = LinkedDeviceConversationScopeEntity(
            deviceId = deviceId,
            conversationId = entry.conversationId,
            enabled = entry.conversationId in activeIds,
            scopeSequence = entry.scopeSequence,
            updatedAt = entry.updatedAt,
        )
    }
    for (id in activeIds) {
        rows.putIfAbsent(
            id,
            LinkedDeviceConversationScopeEntity(
                deviceId = deviceId,
                conversationId = id,
                enabled = true,
                scopeSequence = scopeSequence,
                updatedAt = updatedAt,
            ),
        )
    }

    return LinkedDeviceScopeEntities(
        state = LinkedDeviceScopeStateEntity(
            deviceId = deviceId,
            mode = mode,
            scopeSequence = scopeSequence,
            updatedAt = updatedAt,
        ),
        conversations = rows.values.sortedBy { it.conversationId },
    )
}

/**
 * The monotonic-write rule for the device state row.
 *
 * A first write always wins (`current == null`); afterwards only a **strictly** greater
 * sequence may replace the row. Equal sequences are stale by definition: replaying an
 * already-applied decision must not touch `updatedAt`. Conversation rows use the batch-aware
 * [acceptsConversationWrite] instead.
 */
internal fun acceptsScopeSequence(current: Long?, incoming: Long): Boolean {
    require(incoming >= ScopeState.INITIAL_SEQUENCE) {
        "scopeSequence must be >= ${ScopeState.INITIAL_SEQUENCE}"
    }
    return current == null || incoming > current
}

/**
 * The corresponding rule for one conversation row inside the device's current decision
 * batch.
 *
 * Unlike [acceptsScopeSequence], an **equal** sequence is accepted: the state row and its
 * conversation rows share one `scopeSequence` (the model allocates the sequence once per
 * decision), so completing or retrying that same batch must not be mistaken for a stale
 * write. A sequence below the device's state is always stale — accepting it would let a
 * late `setConversation` insert an enabled row for a conversation the current scope never
 * selected, which is exactly the widening this table must prevent.
 */
internal fun acceptsConversationWrite(
    stateSequence: Long?,
    currentRowSequence: Long?,
    incoming: Long,
): Boolean {
    require(incoming >= ScopeState.INITIAL_SEQUENCE) {
        "scopeSequence must be >= ${ScopeState.INITIAL_SEQUENCE}"
    }
    if (stateSequence != null && incoming < stateSequence) return false
    return currentRowSequence == null || incoming >= currentRowSequence
}

/**
 * Room DAO for the linked-device scope.
 *
 * Writes are concrete `@Transaction` methods so the read-check-write sequence is atomic:
 * Room serialises transactions, so two concurrent writers cannot both pass the
 * [acceptsScopeSequence] guard.
 */
@Dao
abstract class LinkedDeviceScopeDao {

    @Query("SELECT * FROM linked_device_scope_state WHERE deviceId = :deviceId")
    abstract suspend fun getScopeState(deviceId: String): LinkedDeviceScopeStateEntity?

    @Query(
        "SELECT * FROM linked_device_conversation_scope " +
            "WHERE deviceId = :deviceId ORDER BY conversationId"
    )
    abstract suspend fun listAll(deviceId: String): List<LinkedDeviceConversationScopeEntity>

    @Query(
        "SELECT * FROM linked_device_conversation_scope " +
            "WHERE deviceId = :deviceId AND enabled = 1 ORDER BY conversationId"
    )
    abstract suspend fun listEnabled(deviceId: String): List<LinkedDeviceConversationScopeEntity>

    @Query(
        "SELECT * FROM linked_device_conversation_scope " +
            "WHERE deviceId = :deviceId AND conversationId = :conversationId"
    )
    abstract suspend fun getConversation(
        deviceId: String,
        conversationId: String,
    ): LinkedDeviceConversationScopeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putState(state: LinkedDeviceScopeStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putConversation(row: LinkedDeviceConversationScopeEntity)

    /** Insert-or-update the device's state row, ignoring a stale or repeated sequence. */
    @Transaction
    open suspend fun upsertState(state: LinkedDeviceScopeStateEntity) {
        val current = getScopeState(state.deviceId)
        if (!acceptsScopeSequence(current?.scopeSequence, state.scopeSequence)) return
        putState(state)
    }

    /** The `mode`-only form of [upsertState] (account-wide <-> conversation). */
    @Transaction
    open suspend fun setMode(
        deviceId: String,
        mode: String,
        sequence: Long,
        updatedAt: Long,
    ) {
        upsertState(
            LinkedDeviceScopeStateEntity(
                deviceId = deviceId,
                mode = mode,
                scopeSequence = sequence,
                updatedAt = updatedAt,
            )
        )
    }

    /**
     * Enable/disable one conversation at [sequence].
     *
     * A missing row is inserted; a row is rewritten only while [sequence] belongs to the
     * device's current decision batch (see [acceptsConversationWrite]). A late revoke
     * therefore cannot disable a newer grant, and — equally important — a late grant cannot
     * insert an enabled row for a conversation the current scope never selected.
     */
    @Transaction
    open suspend fun setConversation(
        deviceId: String,
        conversationId: String,
        enabled: Boolean,
        sequence: Long,
        updatedAt: Long,
    ) {
        require(conversationId.isNotBlank()) { "conversationId must not be blank" }
        val state = getScopeState(deviceId)
        val current = getConversation(deviceId, conversationId)
        if (!acceptsConversationWrite(state?.scopeSequence, current?.scopeSequence, sequence)) {
            return
        }
        putConversation(
            LinkedDeviceConversationScopeEntity(
                deviceId = deviceId,
                conversationId = conversationId,
                enabled = enabled,
                scopeSequence = sequence,
                updatedAt = updatedAt,
            )
        )
    }

    @Query(
        "UPDATE linked_device_conversation_scope " +
            "SET enabled = 0, scopeSequence = :sequence, updatedAt = :updatedAt " +
            "WHERE deviceId = :deviceId AND enabled = 1"
    )
    abstract suspend fun disableAll(
        deviceId: String,
        sequence: Long,
        updatedAt: Long,
    ): Int

    /**
     * The "Pause AI access to all conversations" write: disable every enabled conversation
     * and move the device to the revoked (no-selection) state at [sequence].
     *
     * Mirrors `ScopeState.revokeAll`: already-revoked state is a no-op, so re-pausing a
     * paused device does not advance its sequence.
     */
    @Transaction
    open suspend fun revokeAll(deviceId: String, sequence: Long, updatedAt: Long) {
        val current = maxSequence(deviceId)
        val state = getScopeState(deviceId)
        val alreadyRevoked = state?.mode == LinkedDeviceScopeStateEntity.MODE_CONVERSATION &&
            listEnabled(deviceId).isEmpty()
        if (alreadyRevoked || !acceptsScopeSequence(current, sequence)) return
        putState(
            LinkedDeviceScopeStateEntity(
                deviceId = deviceId,
                mode = LinkedDeviceScopeStateEntity.MODE_CONVERSATION,
                scopeSequence = sequence,
                updatedAt = updatedAt,
            )
        )
        disableAll(deviceId, sequence, updatedAt)
    }

    @Query("SELECT scopeSequence FROM linked_device_scope_state WHERE deviceId = :deviceId")
    abstract suspend fun stateSequence(deviceId: String): Long?

    @Query(
        "SELECT MAX(scopeSequence) FROM linked_device_conversation_scope " +
            "WHERE deviceId = :deviceId"
    )
    abstract suspend fun maxConversationSequence(deviceId: String): Long?

    /** The highest sequence recorded for the device in either table, if any. */
    @Transaction
    open suspend fun maxSequence(deviceId: String): Long? =
        listOfNotNull(stateSequence(deviceId), maxConversationSequence(deviceId)).maxOrNull()

    @Query("DELETE FROM linked_device_conversation_scope WHERE deviceId = :deviceId")
    abstract suspend fun deleteConversations(deviceId: String): Int

    @Query("DELETE FROM linked_device_scope_state WHERE deviceId = :deviceId")
    abstract suspend fun deleteState(deviceId: String): Int

    /** Unlink/forget: remove every scope row for the device (there is nothing to revoke). */
    @Transaction
    open suspend fun deleteAll(deviceId: String) {
        deleteConversations(deviceId)
        deleteState(deviceId)
    }

    /** A consistent read of both tables for one device. */
    @Transaction
    open suspend fun snapshot(deviceId: String): LinkedDeviceScopeSnapshot =
        LinkedDeviceScopeSnapshot(
            state = getScopeState(deviceId),
            conversations = listAll(deviceId),
        )
}
