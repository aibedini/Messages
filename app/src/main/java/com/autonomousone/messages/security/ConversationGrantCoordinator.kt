package com.autonomousone.messages.security

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * LDV-004 PR-E (slice 2b + 2c) — the producer coordinator: persisted scope in, `KEY_GRANT`
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
 *  - [AiAgentDeviceSource] — the linked `AI_AGENT` devices a sensitive reclassification must
 *    reach (slice 2c);
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
 * ## Revocation (slice 2c, design §4.5)
 *
 * Two explicit revoke operations complete the lifecycle. Both are **key-less**: every event
 * they build is a `gmweb-conversation-key-revoke` tombstone (no `wrappedKey`, no `epochId`,
 * no `expiresAt`), so neither asks [ConversationKeySource] for a CKE and neither can leak key
 * material. Both use the same one-sequence-per-decision rule and the same all-or-nothing
 * order as [applySelection]: build every tombstone, enqueue every one, and only then persist
 * the revoked state.
 *
 *  - [pauseAll] is the "Pause AI access to all conversations" decision and the unlink hook.
 *    It withdraws an account-wide grant with an account tombstone, and a conversation-mode
 *    grant with one tombstone per ENABLED conversation. The persisted state becomes
 *    `mode="conversation"` with no enabled row (slice 2a's encoding of "revoked"), which is
 *    why re-pausing is idempotent ([ScopeRevokeResult.AlreadyRevoked]) and why the next grant
 *    must start a new sequence. It publishes tombstone denies explicitly for every level
 *    rather than relying on the DB state: god-ai treats a tombstone as an explicit deny, and a
 *    "pause" that only changed local rows would leave the device reading everything.
 *  - [revokeConversation] is the single-conversation revoke (the sensitive-reclassification
 *    hook, design §4.5 "conversation" level). It tombstones exactly one conversation and
 *    persists that conversation's `enabled=0` decision plus the narrowed selection. A
 *    conversation that is not granted locally is not tombstoned ([ScopeRevokeResult.NotGranted]):
 *    there is no grant to withdraw, and publishing a deny for a conversation the operator
 *    never granted would be an unrequested sticky deny in god-ai. An already-revoked
 *    conversation (or a fully paused device) is [ScopeRevokeResult.AlreadyRevoked].
 *    An **account-wide** device has no enumerable conversation list locally, so a
 *    single-conversation revoke cannot narrow it without inventing a scope; the fail-closed
 *    answer is the account-level pause ([pauseAll]) — the device loses more than one
 *    conversation rather than keeping a grant for a sensitive one.
 *  - [revokeForSensitiveReclassification] fans [revokeConversation] out over every linked
 *    `AI_AGENT` device, with reason `superseded` (the wire reasons are
 *    revoked|superseded|unlinked|expired; a classification-superseded grant is literally
 *    superseded, so no new wire value is invented). Non-sensitive categories are skipped
 *    without touching the devices. A failure for one device never stops another; the caller
 *    gets one typed outcome per device.
 *
 * ### Residual gaps this slice records but does not close
 *
 *  - Revokes do not retry. A failed enqueue is reported ([ScopeRevokeResult.Failed]) and the
 *    DB state is left unchanged, so the next classification of a sensitive message in that
 *    conversation tries again; there is no durable revocation job yet.
 *  - A tombstone still carries its own deterministic id rather than the superseded grant's
 *    (slice 2b's gap: the v30 rows do not persist the published `grantId`/`epochId`).
 *  - The unlink hook enqueues its tombstones before the primary identity is un-enrolled, but
 *    `EventUploader`'s gate closes with that identity: the tombstones are durable and
 *    deterministic, yet the device-level `DEVICE_REVOKED` statement (design §4.5 device level)
 *    is the authoritative kill switch for that path.
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
 * ### Known wire limitation (still open after slice 2c)
 *
 * A tombstone built by this coordinator carries its OWN deterministic `grantId`
 * (`tombstoneGrantIdFor(deviceId, scopeMode, conversationId, sequence)`), not the id of the
 * grant it withdraws: the v30 scope tables persist the per-conversation DECISION but not the
 * `grantId`/`epochId` that was actually published, so the superseded id is not recoverable.
 * god-ai's policy half is keyed by conversation (`RevokeGrant(ref)`), which a tombstone naming
 * the conversation satisfies; a consumer that keys revocations by `(device_id, grant_id)` row
 * identity (design §4.7.4) needs the persisted id to flip the exact row. A later slice should
 * add `grantId`/`epochId` to the conversation scope row and use
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

/**
 * The linked AI devices a [ConversationGrantCoordinator.revokeForSensitiveReclassification]
 * must reach.
 *
 * The coordinator cannot read the trust registry (it is Android-free), so the production
 * adapter enumerates `trusted_devices` rows whose `deviceType` is `AI_AGENT` (see
 * [com.autonomousone.messages.security.TrustedAiAgentDeviceSource]).
 *
 * Only [GrantTarget.deviceId] is used: a revoke is key-less, so an unreadable or rotated
 * `encryptionPublicKey` must never hide a device from revocation. The adapter therefore fills
 * the key best-effort.
 */
fun interface AiAgentDeviceSource {
    suspend fun aiAgentDevices(): List<GrantTarget>
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
 * The outcome of one revoke — [ConversationGrantCoordinator.pauseAll] or
 * [ConversationGrantCoordinator.revokeConversation] (slice 2c).
 *
 * The variants answer three different questions, and the caller must not confuse them:
 * `Revoked` means tombstones were enqueued AND the revoked state is durable; `AlreadyRevoked`
 * means there was nothing left to withdraw (a second pause, a conversation already disabled —
 * the idempotent no-op, with no new sequence and no event); `NotGranted` means this phone has
 * no grant to withdraw for that conversation/device at all (fail-closed by doing nothing,
 * because publishing a deny nobody asked for would be an unrequested sticky deny in god-ai).
 *
 * [Failed] means nothing was persisted: `publishedBeforeFailure` counts the tombstones that
 * already reached the outbox (never a payload or a key), and a retry re-enqueues the same
 * deterministic event ids.
 */
sealed interface ScopeRevokeResult {
    /** The tombstones were enqueued and the revoked state is now durable. */
    data class Revoked(
        val state: ScopeState,
        val published: List<PublishedGrant>,
    ) : ScopeRevokeResult

    /** Nothing to revoke: the scope/conversation was already disabled. No event, no sequence. */
    data object AlreadyRevoked : ScopeRevokeResult

    /** Nothing was ever granted here: no local state, so no tombstone is published. */
    data object NotGranted : ScopeRevokeResult

    /** A step failed; the persisted scope is unchanged. Revokes only use LOAD/BUILD/OUTBOX/PERSIST. */
    data class Failed(
        val stage: ScopeApplyStage,
        val publishedBeforeFailure: Int = 0,
        val cause: Throwable? = null,
    ) : ScopeRevokeResult
}

/** One device's outcome of a sensitive-reclassification revoke. */
data class DeviceRevokeOutcome(
    val deviceId: String,
    val result: ScopeRevokeResult,
)

/**
 * The outcome of [ConversationGrantCoordinator.revokeForSensitiveReclassification].
 *
 * [NotSensitive] is the common case and deliberately cheap: the category is not in
 * [ConversationScope.SENSITIVE_CATEGORIES], so no device was read and nothing was published.
 * [Evaluated] carries one entry per linked `AI_AGENT` device — including devices that held no
 * grant for the conversation (`ScopeRevokeResult.NotGranted`) — so a caller can report honest
 * counts. [Failed] means the device enumeration itself failed, so it is unknown whether any
 * grant was withdrawn; the caller must treat that as "not revoked".
 */
sealed interface SensitiveRevocationResult {
    /** The category cannot make a granted conversation sensitive: nothing was done. */
    data object NotSensitive : SensitiveRevocationResult

    /** Every linked AI device was evaluated; see [outcomes]. */
    data class Evaluated(val outcomes: List<DeviceRevokeOutcome>) : SensitiveRevocationResult

    /** The linked AI devices could not be enumerated; nothing is known to be revoked. */
    data class Failed(val cause: Throwable? = null) : SensitiveRevocationResult
}

/**
 * The producer entry points: the scope UI, the unlink hook and the classification hook.
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
    private val aiDevices: AiAgentDeviceSource,
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

        // ── 6. Enqueue every payload ────────────────────────────────────────────
        val enqueued = when (val outcome = enqueuePayloads(device.deviceId, payloads)) {
            is EnqueueOutcome.Enqueued -> outcome.count
            is EnqueueOutcome.Failed -> return@withLock ScopeApplyResult.Failed(
                ScopeApplyStage.OUTBOX,
                publishedBeforeFailure = outcome.enqueued,
                cause = outcome.cause,
            )
        }

        // ── 7. Persist the new scope only now ───────────────────────────────────
        persistDecision(device.deviceId, newState)?.let { failure ->
            return@withLock ScopeApplyResult.Failed(
                failure.stage,
                publishedBeforeFailure = enqueued,
                cause = failure.cause,
            )
        }
        ScopeApplyResult.Applied(
            state = newState,
            published = publishedFrom(payloads),
        )
    }

    /**
     * Revoke the WHOLE scope of [device] — the "Pause AI access to all conversations" decision
     * and the primary-unlink hook (slice 2c, design §4.5 "whole scope").
     *
     * Builds one key-less tombstone per level that is actually live:
     *
     *  - an **account** tombstone when the persisted mode is [ScopeSelection.AccountWide]
     *    (nothing else on the wire can withdraw a whole-account grant), and
     *  - one tombstone per ENABLED conversation for a conversation-mode scope, in sorted id
     *    order (deterministic payloads, deterministic event ids).
     *
     * Then persists the revoked state: mode `conversation` with no enabled row (slice 2a's
     * encoding of "paused"), at this decision's sequence. Every tombstone of the batch shares
     * that one sequence, exactly like a grant batch.
     *
     * Idempotent: an already revoked (paused) device is [ScopeRevokeResult.AlreadyRevoked] —
     * no sequence, no event, no save. A device with no scope row at all was never configured
     * and has nothing to withdraw: [ScopeRevokeResult.NotGranted], and deliberately no
     * tombstone (a deny for a device that was never granted is an unrequested sticky deny).
     *
     * No key material: the tombstones carry none, so neither [ConversationKeySource] nor
     * [GrantEpochSource] is consulted.
     *
     * @param reason `revoked` for the operator's pause (default), `unlinked` for an unlink.
     */
    suspend fun pauseAll(
        device: GrantTarget,
        reason: GrantRevokeReason = GrantRevokeReason.REVOKED,
    ): ScopeRevokeResult = mutex.withLock {
        val loaded = loadForRevoke(device)
        if (loaded is LoadOutcome.Failed) {
            return@withLock ScopeRevokeResult.Failed(ScopeApplyStage.LOAD, cause = loaded.cause)
        }
        val previous = (loaded as LoadOutcome.Loaded).state
            ?: return@withLock ScopeRevokeResult.NotGranted
        if (previous.revoked) return@withLock ScopeRevokeResult.AlreadyRevoked
        pauseLocked(device, previous, reason)
    }

    /**
     * Revoke ONE conversation of [device] (slice 2c, design §4.5 "conversation" level).
     *
     * Publishes exactly one key-less tombstone for [conversationId] at the decision's new
     * sequence and persists the narrowed scope (the conversation's row becomes `enabled=0`;
     * revoking the LAST granted conversation leaves the device in the revoked/paused state,
     * because an empty allowlist is not a legal scope).
     *
     * Fail-closed bookkeeping, in order:
     *  - no scope row for the device → [ScopeRevokeResult.NotGranted] (never granted);
     *  - already paused, or this conversation already disabled →
     *    [ScopeRevokeResult.AlreadyRevoked] (idempotent, no event);
     *  - the device is account-wide → the account-level [pauseAll] with the same [reason]:
     *    the model cannot narrow an account scope without inventing a conversation list, and
     *    leaving the sensitive conversation granted would be the fail-open answer.
     *
     * @param reason a wire reason: `revoked` for an operator action, `superseded` when a
     *   classification or a newer decision replaced the grant.
     */
    suspend fun revokeConversation(
        device: GrantTarget,
        conversationId: String,
        reason: GrantRevokeReason = GrantRevokeReason.REVOKED,
    ): ScopeRevokeResult {
        if (conversationId.isBlank()) {
            return ScopeRevokeResult.Failed(ScopeApplyStage.BUILD)
        }
        return mutex.withLock {
            val loaded = loadForRevoke(device)
            if (loaded is LoadOutcome.Failed) {
                return@withLock ScopeRevokeResult.Failed(ScopeApplyStage.LOAD, cause = loaded.cause)
            }
            val previous = (loaded as LoadOutcome.Loaded).state
                ?: return@withLock ScopeRevokeResult.NotGranted
            if (previous.revoked) return@withLock ScopeRevokeResult.AlreadyRevoked
            if (previous.selection is ScopeSelection.AccountWide) {
                // No enumerable conversation list locally: the only consistent, fail-closed
                // revoke is the account-level tombstone.
                return@withLock pauseLocked(device, previous, reason)
            }
            if (!previous.grants(conversationId)) {
                // "Already disabled" (recorded as revoked) and "never granted" are different
                // facts: the former is an idempotent retry, the latter is a no-op.
                return@withLock if (previous.entries.any { it.conversationId == conversationId }) {
                    ScopeRevokeResult.AlreadyRevoked
                } else {
                    ScopeRevokeResult.NotGranted
                }
            }

            val issuedAt = now()
            if (issuedAt <= 0L) return@withLock ScopeRevokeResult.Failed(ScopeApplyStage.BUILD)
            val newState = try {
                previous.revoke(conversationId, issuedAt)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                return@withLock ScopeRevokeResult.Failed(ScopeApplyStage.BUILD, cause = t)
            }
            val payload = try {
                ConversationGrantPublisher.buildTombstone(
                    deviceId = device.deviceId,
                    conversationId = conversationId,
                    issuedAt = issuedAt,
                    sequence = newState.scopeSequence,
                    reason = reason,
                    signer = signer,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                return@withLock ScopeRevokeResult.Failed(ScopeApplyStage.BUILD, cause = t)
            }
            val payloads = listOf(payload)
            val enqueued = when (val outcome = enqueuePayloads(device.deviceId, payloads)) {
                is EnqueueOutcome.Enqueued -> outcome.count
                is EnqueueOutcome.Failed -> return@withLock ScopeRevokeResult.Failed(
                    ScopeApplyStage.OUTBOX,
                    publishedBeforeFailure = outcome.enqueued,
                    cause = outcome.cause,
                )
            }
            persistDecision(device.deviceId, newState)?.let { failure ->
                return@withLock ScopeRevokeResult.Failed(
                    failure.stage,
                    publishedBeforeFailure = enqueued,
                    cause = failure.cause,
                )
            }
            ScopeRevokeResult.Revoked(
                state = newState,
                published = publishedFrom(payloads),
            )
        }
    }

    /**
     * Withdraw a conversation from every linked AI device after a classification made it
     * sensitive (slice 2c).
     *
     * The classification hook calls this for EVERY newly stored category, so the first thing
     * it does is the cheap, in-memory [ConversationScope.isSensitiveCategory] check:
     * a non-sensitive category returns [SensitiveRevocationResult.NotSensitive] without
     * reading the trust registry or any scope row. (An unknown category is not sensitive here
     * because [ConversationScope.eligible] would never have granted it.)
     *
     * For a sensitive category every linked `AI_AGENT` device is evaluated with
     * [revokeConversation] at reason `superseded`; a device with no grant for the
     * conversation answers [ScopeRevokeResult.NotGranted] and publishes nothing. One device's
     * failure never conceals another's result — each outcome is reported.
     *
     * Nothing here logs, and the conversation id never reaches a string: this method only
     * carries it to the store and the payload builder (which treats `conversationId` as public
     * routing metadata, design §4.2). The caller is responsible for not logging it.
     */
    suspend fun revokeForSensitiveReclassification(
        conversationId: String,
        category: String,
    ): SensitiveRevocationResult {
        if (!ConversationScope.isSensitiveCategory(category)) {
            return SensitiveRevocationResult.NotSensitive
        }
        if (conversationId.isBlank()) {
            // No device can hold a grant for a blank conversation reference.
            return SensitiveRevocationResult.Evaluated(emptyList())
        }
        val targets = try {
            aiDevices.aiAgentDevices()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            return SensitiveRevocationResult.Failed(t)
        }
        val outcomes = targets.distinctBy { it.deviceId }.map { target ->
            DeviceRevokeOutcome(
                deviceId = target.deviceId,
                result = revokeConversation(
                    device = target,
                    conversationId = conversationId,
                    reason = GrantRevokeReason.SUPERSEDED,
                ),
            )
        }
        return SensitiveRevocationResult.Evaluated(outcomes)
    }

    /**
     * The shared body of [pauseAll] and [revokeConversation]'s account-wide fallback.
     * The caller holds [mutex] and has loaded a non-revoked [previous].
     */
    private suspend fun pauseLocked(
        device: GrantTarget,
        previous: ScopeState,
        reason: GrantRevokeReason,
    ): ScopeRevokeResult {
        val issuedAt = now()
        if (issuedAt <= 0L) return ScopeRevokeResult.Failed(ScopeApplyStage.BUILD)
        val newState = try {
            previous.revokeAll(issuedAt)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            return ScopeRevokeResult.Failed(ScopeApplyStage.BUILD, cause = t)
        }
        if (newState === previous) return ScopeRevokeResult.AlreadyRevoked

        // Every conversation that is currently authorised must be named explicitly: the wire
        // has no "paused" flag, only per-conversation/account denies (design §4.5).
        val conversationIds = buildList {
            addAll(previous.activeConversationIds)
            for (entry in previous.entries) {
                if (!entry.revoked) add(entry.conversationId)
            }
        }.distinct().sorted()
        val accountWide = previous.selection is ScopeSelection.AccountWide

        val payloads = try {
            buildList<ConversationGrantPayload> {
                if (accountWide) {
                    add(
                        ConversationGrantPublisher.buildTombstone(
                            deviceId = device.deviceId,
                            conversationId = null,
                            issuedAt = issuedAt,
                            sequence = newState.scopeSequence,
                            reason = reason,
                            signer = signer,
                        )
                    )
                }
                for (conversationId in conversationIds) {
                    add(
                        ConversationGrantPublisher.buildTombstone(
                            deviceId = device.deviceId,
                            conversationId = conversationId,
                            issuedAt = issuedAt,
                            sequence = newState.scopeSequence,
                            reason = reason,
                            signer = signer,
                        )
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            return ScopeRevokeResult.Failed(ScopeApplyStage.BUILD, cause = t)
        }

        val enqueued = when (val outcome = enqueuePayloads(device.deviceId, payloads)) {
            is EnqueueOutcome.Enqueued -> outcome.count
            is EnqueueOutcome.Failed -> return ScopeRevokeResult.Failed(
                ScopeApplyStage.OUTBOX,
                publishedBeforeFailure = outcome.enqueued,
                cause = outcome.cause,
            )
        }
        persistDecision(device.deviceId, newState)?.let { failure ->
            return ScopeRevokeResult.Failed(
                failure.stage,
                publishedBeforeFailure = enqueued,
                cause = failure.cause,
            )
        }
        return ScopeRevokeResult.Revoked(
            state = newState,
            published = publishedFrom(payloads),
        )
    }

    /**
     * Load the persisted scope for a revoke.
     *
     * [LoadOutcome.Loaded.state] is `null` for a device that was never configured; a failed
     * read is a typed outcome instead of a `null` scope, because "never granted" and "the
     * database is unreadable" must not be reported as the same thing.
     */
    private suspend fun loadForRevoke(device: GrantTarget): LoadOutcome = try {
        LoadOutcome.Loaded(store.load(device.deviceId))
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        LoadOutcome.Failed(t)
    }

    /** Enqueue every built payload in order; fail at the first outbox error. */
    private suspend fun enqueuePayloads(
        deviceId: String,
        payloads: List<ConversationGrantPayload>,
    ): EnqueueOutcome {
        var enqueued = 0
        for (payload in payloads) {
            try {
                outbox.enqueue(
                    eventId = payload.grantId,
                    deviceId = deviceId,
                    payloadJson = payload.toJson().toByteArray(Charsets.UTF_8),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                return EnqueueOutcome.Failed(enqueued, t)
            }
            enqueued++
        }
        return EnqueueOutcome.Enqueued(enqueued)
    }

    /** Persist a decision; `null` on success. The store's monotonic guard is the authority. */
    private suspend fun persistDecision(
        deviceId: String,
        state: ScopeState,
    ): PersistFailure? = try {
        if (store.save(deviceId, state)) {
            null
        } else {
            PersistFailure(ScopeApplyStage.PERSIST, null)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        PersistFailure(ScopeApplyStage.PERSIST, t)
    }

    private fun publishedFrom(payloads: List<ConversationGrantPayload>): List<PublishedGrant> =
        payloads.map { payload ->
            PublishedGrant(
                grantId = payload.grantId,
                kind = payload.kind,
                scopeMode = payload.scopeMode,
                conversationId = payload.conversationId,
                scopeSequence = payload.scopeSequence,
            )
        }

    private sealed interface EnqueueOutcome {
        data class Enqueued(val count: Int) : EnqueueOutcome

        data class Failed(val enqueued: Int, val cause: Throwable?) : EnqueueOutcome
    }

    private data class PersistFailure(val stage: ScopeApplyStage, val cause: Throwable?)

    private sealed interface LoadOutcome {
        data class Loaded(val state: ScopeState?) : LoadOutcome

        data class Failed(val cause: Throwable) : LoadOutcome
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
