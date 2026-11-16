package com.autonomousone.messages.security

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * LDV-004 PR-E (slice 2b) — the producer coordinator: persisted scope in, `KEY_GRANT`
 * events out.
 *
 * This file joins the three pure pieces already on the branch — [ConversationScope]
 * (slice 1 policy), [ConversationGrantPublisher] (slice 1 payload builder) and the
 * `linked_device_scope_*` Room tables (slice 2a) — into one operation,
 * [ConversationGrantCoordinator.applySelection], without importing Android, Room, Compose or
 * a network client. Everything with a side effect is an injected port:
 *
 *  - [ConversationScopeStore] — the durable per-device scope (slice 2a's DAO behind an
 *    interface, so this file stays JVM-testable);
 *  - [ConversationKeySource] — the plaintext `READ_MESSAGES` CKE to wrap;
 *  - [GrantEpochSource] — the epoch id the grant authorises (design §4.2 `epochId`);
 *  - [GrantOutbox] — the durable outbox row `EventUploader` drains;
 *  - [GrantSigner]/[GrantKeyWrapper] — ECDSA over the binding and the HPKE wrap
 *    (slice 1's interfaces, unchanged).
 *
 * ## What one call does (design §4.6)
 *
 * 1. Validate the requested selection with the slice-1 policy, including the sensitive /
 *    `LOCAL_ONLY` category gate. A rejected selection publishes nothing and persists
 *    nothing — a scope is never partially accepted.
 * 2. Load the persisted [ScopeState] and diff it against the request. Unchanged input is a
 *    no-op: no new sequence, no event, so re-saving the same screen cannot flood the outbox.
 * 3. Allocate the decision's new `scopeSequence` (strictly greater than the persisted one)
 *    and build, in deterministic order: an account tombstone when narrowing away from
 *    account-wide, then a tombstone per removed conversation, then a grant per added
 *    conversation (or the single account grant).
 * 4. Enqueue every built payload (deterministic event id = payload `grantId`).
 * 5. Only after **every** enqueue succeeded, persist the new state. A failure at any earlier
 *    step leaves the persisted scope untouched, so the device keeps the last scope the
 *    operator actually approved (design §4.6 failure mode: never a silently broader one).
 *
 * ## One sequence per decision
 *
 * Every event of one decision carries the same `scopeSequence` — the value
 * [ScopeState.withSelection] allocates for the decision. This matches slice 2a's persistence
 * contract (one state row and its conversation rows share the batch sequence, and its
 * monotonic-write guards accept exactly that) and design §4.6's "every change produces a new
 * `scopeSequence`". NOTE for god-ai (PR-A/PR-C): a consumer that enforces
 * `scopeSequence > lastSeen[deviceId]` **per event** would reject all but the first event of
 * a multi-grant batch; it must accept the whole batch at one sequence (or key `lastSeen` by
 * `(deviceId, conversationId)`). This is recorded as a remaining integration gap in the PR.
 *
 * Only the CHANGED conversations are published. A conversation that stays selected keeps its
 * existing grant and is not re-sent, even though [ScopeState.withSelection] re-records its
 * persisted entry at the decision's sequence (that is the pure model's bookkeeping, not a
 * wire event). Re-granting it would be harmless but pointless traffic.
 *
 * ## Account-wide is an explicit mode, not an empty allowlist
 *
 * [ScopeSelection.AccountWide] publishes exactly one `scopeMode="account"` grant. The
 * previously granted conversations are retired in the DATABASE (their rows become
 * `enabled = 0` tombstones through [ScopeState.withSelection]) but **not** published as
 * `gmweb-conversation-key-revoke` events: god-ai's decision order is revoke ▸ deny ▸
 * allowlist ▸ grant registry (`client.go:149-169`) and a conversation tombstone is a
 * sticky explicit deny, so publishing one while switching to "all conversations" would
 * permanently deny exactly the conversations the operator just granted. The account grant
 * supersedes them (design §4.5 "whole scope: one account grant replacing all conversation
 * grants"). Narrowing the other way — account-wide → explicit list — does publish the
 * account tombstone, because nothing else on the wire can withdraw a whole-account grant.
 *
 * ### Known wire limitation (needs slice 2c, not fixable here)
 *
 * A tombstone built by this coordinator carries its OWN deterministic `grantId`
 * (`tombstoneGrantIdFor(deviceId, scopeMode, conversationId, sequence)`), not the id of the
 * grant it withdraws: the v30 scope tables persist the per-conversation DECISION but not the
 * `grantId`/`epochId` that was actually published, so the superseded id is not recoverable.
 * god-ai's policy half is keyed by conversation (`RevokeGrant(ref)`), which a tombstone naming
 * the conversation satisfies; a consumer that keys revocations by `(device_id, grant_id)` row
 * identity (design §4.7.4) needs the persisted id to flip the exact row. Slice 2c should add
 * `grantId`/`epochId` to the conversation scope row and use
 * [ConversationGrantPublisher.buildTombstone]'s superseded-grant overload.
 *
 * ## Failure semantics
 *
 * Enqueues are individual durable writes, so a failure *between* enqueues leaves the rows
 * already written in the outbox. That is deliberate: event ids are deterministic
 * ([ConversationGrantPublisher.grantIdFor]), so a retry of the same decision re-enqueues the
 * same ids ([GrantOutbox] implementations use `INSERT OR IGNORE`) and the DB state is still
 * the old one, i.e. the device keeps the last approved scope. [ScopeApplyResult.Failed]
 * reports the stage and how many payloads were enqueued before the failure, never a payload
 * or a key.
 *
 * ## Key material
 *
 * The plaintext CKE returned by [ConversationKeySource] is passed to the builder (which hands
 * it to [GrantKeyWrapper] and retains no copy) and zeroed in a `finally` block. This file
 * logs nothing, and no payload or key ever reaches a log line through it
 * ([ConversationGrantPayload.toString] redacts the ciphertext and signature anyway).
 */
fun interface ConversationKeySource {
    /**
     * The plaintext `READ_MESSAGES` CKE to authorise.
     *
     * @param conversationId an opaque conversation reference, or
     *   [ConversationGrantCoordinator.ACCOUNT_CONVERSATION_ID] (`""`) for the account-wide
     *   grant. The caller owns the returned buffer; the coordinator zeroes it after use.
     * @return the 32-byte AES-256 key, or `null` when this device cannot produce one yet
     *   (no epoch, locked Keystore, …). `null` fails the whole decision closed.
     */
    suspend fun ckeFor(conversationId: String): ByteArray?
}

/**
 * The durable outbox a built grant/tombstone is handed to.
 *
 * Implementations must persist [payloadJson] verbatim and target it by [deviceId]: the
 * production adapter writes a `KEY_GRANT` row into `gateway_event_outbox`, which
 * `EventUploader` drains and GMweb routes by `json_extract(ciphertext, '$.deviceId')`
 * (design §2.3). [eventId] is deterministic (the payload `grantId`), so re-enqueueing the
 * same decision must be an idempotent no-op.
 */
fun interface GrantOutbox {
    suspend fun enqueue(eventId: String, deviceId: String, payloadJson: ByteArray)
}

/** The account `READ_MESSAGES` epoch the grants authorise (design §4.2 `epochId`). */
fun interface GrantEpochSource {
    /**
     * @return the current account/keyring epoch id (`KEYRING_ENTRY.keyId` for the generation).
     * @throws IllegalStateException when no epoch exists yet; the coordinator reports that as
     *   a typed failure instead of publishing a grant bound to a made-up epoch.
     */
    suspend fun epochId(): String
}

/**
 * Durable per-device scope storage (slice 2a's [com.autonomousone.messages.data.LinkedDeviceScopeDao]
 * behind an interface).
 *
 * [load] returns `null` for a device that was never configured — which is different from the
 * revoked ("paused") state, whose `selection` is also `null` but which keeps its sequence.
 * [save] must be atomic and monotonic: it returns `false` for a write whose sequence is below
 * the persisted one (a stale writer) and never partially applies the batch.
 */
interface ConversationScopeStore {
    suspend fun load(deviceId: String): ScopeState?

    /** @return true when the state was persisted; false when a newer decision won. */
    suspend fun save(deviceId: String, state: ScopeState): Boolean
}

/** Which step of [ConversationGrantCoordinator.applySelection] failed. */
enum class ScopeApplyStage {
    /** Reading the persisted scope failed. */
    LOAD,

    /** The account epoch is missing/unreadable. */
    EPOCH,

    /** The CKE for a granted conversation is unavailable. */
    KEY,

    /** A payload could not be built (invalid input, failing signer/wrapper). */
    BUILD,

    /** The grant lifetime would already be expired. */
    EXPIRY,

    /** Enqueueing a payload into the durable outbox failed. */
    OUTBOX,

    /** Every payload was enqueued, but the new scope was not persisted (or lost a race). */
    PERSIST,
}

/**
 * The outcome of one [ConversationGrantCoordinator.applySelection] call.
 *
 * [Failed.publishedBeforeFailure] counts enqueued payloads (never keys or payload bytes).
 */
sealed interface ScopeApplyResult {
    /**
     * The scope changed and is now durable; [published] is the batch that was enqueued, in
     * the order it was written (account tombstone, conversation tombstones, grants).
     */
    data class Applied(
        val state: ScopeState,
        val published: List<PublishedGrant>,
    ) : ScopeApplyResult

    /** The requested selection already is the persisted one: no sequence change, no events. */
    data object Unchanged : ScopeApplyResult

    /** The selection is not publishable; nothing was enqueued and nothing was persisted. */
    data class Rejected(
        val violation: ScopeViolation,
        val conversationId: String? = null,
    ) : ScopeApplyResult

    /** A step failed; the persisted scope is unchanged. */
    data class Failed(
        val stage: ScopeApplyStage,
        val publishedBeforeFailure: Int = 0,
        val cause: Throwable? = null,
    ) : ScopeApplyResult
}

/**
 * What was published for one built payload — metadata only, no ciphertext or signature.
 *
 * [scopeMode] is `null` for a tombstone, which carries no scope fields at all (design §4.2);
 * a tombstone's target is [conversationId] (`null` = the whole account).
 */
data class PublishedGrant(
    val grantId: String,
    val kind: GrantKind,
    val scopeMode: GrantScopeMode?,
    val conversationId: String?,
    val scopeSequence: Long,
)

/**
 * The one producer entry point the scope UI calls.
 *
 * Stateless apart from [mutex] (which serialises overlapping saves in this process) and the
 * injected clock; every durable decision is read from, and written to, [store].
 */
class ConversationGrantCoordinator(
    private val store: ConversationScopeStore,
    private val keys: ConversationKeySource,
    private val outbox: GrantOutbox,
    private val signer: GrantSigner,
    private val wrapper: GrantKeyWrapper,
    private val epoch: GrantEpochSource,
    private val now: () -> Long = System::currentTimeMillis,
    private val grantValidityMillis: Long = DEFAULT_GRANT_VALIDITY_MILLIS,
) {
    /**
     * Serialises decisions inside this process. Two overlapping saves would otherwise both
     * diff the same persisted state and allocate the same sequence; the store's monotonic
     * guard would reject the loser only after it had enqueued its batch, which is exactly the
     * partial publication this coordinator exists to avoid.
     */
    private val mutex = Mutex()

    /**
     * Diff [newSelection] against the persisted scope and publish the difference.
     *
     * @param device the AI device the grants are addressed to (its P-256 key is the HPKE
     *   recipient).
     * @param newSelection what the operator approved. [ScopeSelection.AccountWide] means
     *   "every eligible conversation"; an empty [ScopeSelection.Conversations] set is
     *   rejected by policy, never treated as "nothing".
     * @param conversationCategory the stored classification for a conversation (firewall
     *   category, keyring domain or local `MessageCategory` name), or `null` when the app has
     *   no row for it — `null` means the production normal category (""), not "sensitive".
     *   A non-null value the policy does not recognise fails closed (design §5.8).
     * @param expiresAtLimit the device certificate expiry, which a grant must never exceed
     *   (design §4.2). `null` falls back to [grantValidityMillis] from issue time.
     */
    suspend fun applySelection(
        device: GrantTarget,
        newSelection: ScopeSelection,
        conversationCategory: (String) -> String?,
        expiresAtLimit: Long? = null,
    ): ScopeApplyResult = mutex.withLock {
        // ── 1. Policy, including the sensitive / LOCAL_ONLY gate ────────────────
        val validation = ConversationScope.validate(newSelection) { conversationId ->
            conversationCategory(conversationId) ?: NORMAL_CONVERSATION_CATEGORY
        }
        if (validation is ScopeValidation.Invalid) {
            return@withLock ScopeApplyResult.Rejected(validation.violation, validation.conversationId)
        }
        val selection = (validation as ScopeValidation.Valid).selection

        // ── 2. Persisted state (null = never configured) ────────────────────────
        val previous = try {
            store.load(device.deviceId)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            return@withLock ScopeApplyResult.Failed(ScopeApplyStage.LOAD, cause = t)
        }
        if (previous != null && !previous.revoked && previous.selection == selection) {
            return@withLock ScopeApplyResult.Unchanged
        }

        // ── 3. What changed ─────────────────────────────────────────────────────
        val previousIds = previous?.activeConversationIds ?: emptySet()
        val previousWasAccountWide = previous?.selection is ScopeSelection.AccountWide
        val newIds = (selection as? ScopeSelection.Conversations)?.ids ?: emptySet()
        val added = (newIds - previousIds).sorted()
        val removed = (previousIds - newIds).sorted()

        // ── 4. The decision's sequence and the state it will persist ────────────
        val issuedAt = now()
        val expiresAt = try {
            require(issuedAt > 0L) { "issuedAt must be positive" }
            val lifetimeEnd = issuedAt + grantValidityMillis
            val bounded = expiresAtLimit?.let { minOf(it, lifetimeEnd) } ?: lifetimeEnd
            require(bounded > issuedAt) { "the device certificate expires before the grant" }
            bounded
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            return@withLock ScopeApplyResult.Failed(ScopeApplyStage.EXPIRY, cause = t)
        }
        val newState = try {
            if (previous == null) {
                ScopeState.initial(selection, issuedAt)
            } else {
                previous.withSelection(selection, issuedAt)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            return@withLock ScopeApplyResult.Failed(ScopeApplyStage.BUILD, cause = t)
        }
        val sequence = newState.scopeSequence

        // ── 5. Build every payload BEFORE enqueueing any of them ────────────────
        // Building first is what makes a signing/wrapping/key failure publish nothing at
        // all: there is no "half a batch" to reason about for a failure that is local.
        val needsGrant = selection is ScopeSelection.AccountWide || added.isNotEmpty()
        val epochId = if (needsGrant) {
            val resolved = try {
                epoch.epochId()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                return@withLock ScopeApplyResult.Failed(ScopeApplyStage.EPOCH, cause = t)
            }
            if (resolved.isBlank()) {
                return@withLock ScopeApplyResult.Failed(ScopeApplyStage.EPOCH)
            }
            resolved
        } else {
            ""
        }
        val payloads = ArrayList<ConversationGrantPayload>(added.size + removed.size + 2)
        try {
            if (previousWasAccountWide && selection is ScopeSelection.Conversations) {
                // Nothing else on the wire can withdraw an account-wide grant (design §4.5).
                payloads += ConversationGrantPublisher.buildTombstone(
                    target = device,
                    conversationId = null,
                    issuedAt = issuedAt,
                    sequence = sequence,
                    reason = GrantRevokeReason.SUPERSEDED,
                    signer = signer,
                )
            }
            when (selection) {
                ScopeSelection.AccountWide -> {
                    val key = try {
                        keys.ckeFor(ConversationGrantCoordinator.ACCOUNT_CONVERSATION_ID)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        return@withLock ScopeApplyResult.Failed(
                            ScopeApplyStage.KEY,
                            publishedBeforeFailure = payloads.size,
                            cause = t,
                        )
                    } ?: return@withLock ScopeApplyResult.Failed(
                        ScopeApplyStage.KEY,
                        publishedBeforeFailure = payloads.size,
                    )
                    try {
                        payloads += ConversationGrantPublisher.buildAccountGrant(
                            target = device,
                            epochId = epochId,
                            key = key,
                            issuedAt = issuedAt,
                            expiresAt = expiresAt,
                            sequence = sequence,
                            wrapper = wrapper,
                            signer = signer,
                        )
                    } finally {
                        key.fill(0)
                    }
                }

                is ScopeSelection.Conversations -> {
                    for (conversationId in removed) {
                        payloads += ConversationGrantPublisher.buildTombstone(
                            target = device,
                            conversationId = conversationId,
                            issuedAt = issuedAt,
                            sequence = sequence,
                            reason = GrantRevokeReason.REVOKED,
                            signer = signer,
                        )
                    }
                    for (conversationId in added) {
                        val key = try {
                            keys.ckeFor(conversationId)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            return@withLock ScopeApplyResult.Failed(
                                ScopeApplyStage.KEY,
                                publishedBeforeFailure = payloads.size,
                                cause = t,
                            )
                        } ?: return@withLock ScopeApplyResult.Failed(
                            ScopeApplyStage.KEY,
                            publishedBeforeFailure = payloads.size,
                        )
                        try {
                            payloads += ConversationGrantPublisher.buildGrant(
                                target = device,
                                conversationId = conversationId,
                                epochId = epochId,
                                key = key,
                                issuedAt = issuedAt,
                                expiresAt = expiresAt,
                                sequence = sequence,
                                wrapper = wrapper,
                                signer = signer,
                            )
                        } finally {
                            key.fill(0)
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Nothing has been enqueued yet, so this is a clean failure.
            return@withLock ScopeApplyResult.Failed(ScopeApplyStage.BUILD, 0, t)
        }

        // ── 6. Enqueue, then ────────────────────────────────────────────────────
        var enqueued = 0
        for (payload in payloads) {
            try {
                outbox.enqueue(
                    eventId = payload.grantId,
                    deviceId = device.deviceId,
                    payloadJson = payload.toJson().toByteArray(Charsets.UTF_8),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                return@withLock ScopeApplyResult.Failed(
                    ScopeApplyStage.OUTBOX,
                    publishedBeforeFailure = enqueued,
                    cause = t,
                )
            }
            enqueued++
        }

        // ── 7. Persist the new scope only now ───────────────────────────────────
        val saved = try {
            store.save(device.deviceId, newState)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            return@withLock ScopeApplyResult.Failed(
                ScopeApplyStage.PERSIST,
                publishedBeforeFailure = enqueued,
                cause = t,
            )
        }
        if (!saved) {
            return@withLock ScopeApplyResult.Failed(
                ScopeApplyStage.PERSIST,
                publishedBeforeFailure = enqueued,
            )
        }
        ScopeApplyResult.Applied(
            state = newState,
            published = payloads.map { payload ->
                PublishedGrant(
                    grantId = payload.grantId,
                    kind = payload.kind,
                    scopeMode = payload.scopeMode,
                    conversationId = payload.conversationId,
                    scopeSequence = payload.scopeSequence,
                )
            },
        )
    }

    companion object {
        /**
         * The conversation id that asks a [ConversationKeySource] for the ACCOUNT-wide
         * `READ_MESSAGES` CKE (an account grant has no conversation; design §4.2 leaves
         * `conversationId` absent).
         */
        const val ACCOUNT_CONVERSATION_ID = ""

        /** The production "normal" category (`NORMAL` is stored as the empty string). */
        const val NORMAL_CONVERSATION_CATEGORY = ""

        /**
         * Default grant lifetime when the caller does not bound it with the device
         * certificate expiry. Production passes `TrustedDeviceEntity.expiresAt` as
         * `expiresAtLimit`, so a grant never outlives the certificate it was authorised
         * against (design §4.2).
         */
        const val DEFAULT_GRANT_VALIDITY_MILLIS = 30L * 24 * 60 * 60 * 1000
    }
}
