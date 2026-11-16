package com.autonomousone.messages.security

import java.util.Locale

/**
 * LDV-004 / XR-GA-008 — which conversations an AI device (`AI_AGENT`) may read.
 *
 * This file is the **pure policy model** for the Messages side of the per-conversation CKE
 * grant design (`docs/status/ldv-004-cke-grant-design.md` §4.6). It is deliberately free of
 * Android, Room, Compose, JSON and crypto dependencies so the rules can be unit-tested
 * instead of living only in a Composable, and so the UI cannot invent a broader scope than
 * the publisher can express.
 *
 * ## The rules this model encodes
 *
 * - [ScopeSelection.AccountWide] is the explicit "every conversation" choice. An empty
 *   [ScopeSelection.Conversations] set is **invalid**: "nothing selected" and "everything
 *   selected" must never be confused, and an empty set would otherwise publish no grants
 *   while looking like a scope.
 * - A scope has at most [ConversationScope.MAX_CONVERSATIONS] conversations and every id is
 *   a non-blank opaque reference of at most [ConversationScope.MAX_CONVERSATION_ID_LENGTH]
 *   characters (the same bound god-ai enforces on `conversationId`, design §4.2).
 * - Duplicate ids collapse: a scope is a set, and the canonical form is sorted so two
 *   otherwise-equal selections compare equal and serialise the same way every time.
 * - A sensitive conversation is **never** grantable. [ConversationScope.eligible] rejects the
 *   `SensitiveMessageFirewall` categories, the sensitive keyring domains and a
 *   `LOCAL_ONLY` marker. It fails closed on a category it does not recognise, so adding a
 *   new sensitive category upstream cannot silently become grantable.
 *
 * ## What this model does NOT decide
 *
 * `eligible` does not know the conversation's sender/content. The authoritative LOCAL_ONLY
 * verdict for a whole conversation is [ThreadLocalOnlyPolicy.isLocalOnly], which reuses the
 * exact `classify` + `resolvePolicy` calls of the message firewall. The UI **must** call both
 * (or use the `localOnly` overload) and must never list an ineligible conversation in the
 * first place. Hiding it is an extra guard; the firewall having produced no events for it is
 * the real boundary (design §5.8).
 *
 * Nothing here publishes, persists or signs anything — see [ConversationGrantPublisher] for
 * the producer core and PR-E's later slices for Room and the UI.
 */
sealed interface ScopeSelection {
    /**
     * Every eligible conversation of the account. Maps to the grant payload's
     * `scopeMode="account"` (design §4.2). Sensitive conversations still produce no events
     * at all, because [SensitiveMessageFirewall] runs before the outbox.
     */
    data object AccountWide : ScopeSelection

    /**
     * An explicit, non-empty set of opaque conversation references. Maps to one
     * `scopeMode="conversation"` grant per id.
     *
     * The set is only a transport of caller input; [ConversationScope.validate] collapses
     * duplicates and produces the canonical (sorted) form. The constructor does not enforce
     * non-emptiness so that validation can report it as a typed violation instead of throwing.
     */
    data class Conversations(val ids: Set<String>) : ScopeSelection
}

/** Why a [ScopeSelection] (or a raw set of ids) is not publishable. */
enum class ScopeViolation {
    /** `Conversations(emptySet())`: use [ScopeSelection.AccountWide] explicitly. */
    EMPTY_CONVERSATION_SET,

    /** More than [ConversationScope.MAX_CONVERSATIONS] distinct ids. */
    TOO_MANY_CONVERSATIONS,

    /** An id is blank, so it cannot identify a conversation. */
    BLANK_CONVERSATION_ID,

    /** An id is longer than [ConversationScope.MAX_CONVERSATION_ID_LENGTH]. */
    CONVERSATION_ID_TOO_LONG,

    /** [ConversationScope.eligible] rejected the conversation (sensitive / LOCAL_ONLY). */
    SENSITIVE_CONVERSATION,
}

/** Typed result of [ConversationScope.validate]. */
sealed interface ScopeValidation {
    /** [selection] is the canonical form (duplicates collapsed, ids sorted). */
    data class Valid(val selection: ScopeSelection) : ScopeValidation

    /**
     * @param conversationId the offending id when it is safe to name (blank, sensitive);
     *   `null` for an oversized id, whose echo would only bloat the report.
     */
    data class Invalid(
        val violation: ScopeViolation,
        val conversationId: String? = null,
    ) : ScopeValidation
}

/**
 * The single conversation-scope policy. Pure functions only: no state, no side effects.
 */
object ConversationScope {

    /** Design §4.2 bounds `conversationId` at 256 characters; god-ai enforces the same. */
    const val MAX_CONVERSATION_ID_LENGTH = 256

    /** A scope larger than this is rejected as a UI/DoS guard (god-ai caps a page at 500). */
    const val MAX_CONVERSATIONS = 500

    /** Category token a caller may pass after it evaluated [ThreadLocalOnlyPolicy]. */
    const val LOCAL_ONLY = "LOCAL_ONLY"

    /**
     * `SensitiveMessageFirewall.Category` names that must never be grantable (every value
     * except `NORMAL`). Spelled as wire names rather than enum references so this model stays
     * dependency-free; `ConversationScopeTest` asserts the set matches the enum.
     */
    val SENSITIVE_FIREWALL_CATEGORIES: Set<String> = setOf(
        "OTP_SECURITY_CODE",
        "BANK_SECURITY_CODE",
        "PASSWORD_RESET_CODE",
        "AUTHENTICATION_CODE",
        "FINANCIAL_NOTIFICATION",
    )

    /**
     * Sensitive keyring domains assigned by `TelephonySyncCoordinator.enqueueCloudEvent`
     * (the same values as `ConversationKeyRepository.KEYRING_DOMAINS` minus `READ_MESSAGES`).
     * A conversation carrying one of these is sensitive by construction.
     */
    val SENSITIVE_DOMAINS: Set<String> = setOf(
        "CONTACTS_READ",
        "READ_OTP",
        "READ_BANK_SECURITY",
        "READ_PASSWORD_RESET",
        "READ_AUTH_CODES",
        "READ_FINANCIAL_NOTIFICATIONS",
    )

    /**
     * Local `MessageCategory` names whose meaning is sensitive (OTP and bank/financial
     * transactions). The local classifier vocabulary is a different axis from the firewall
     * category, so both are refused here.
     */
    val SENSITIVE_LOCAL_CATEGORIES: Set<String> = setOf("OTP", "TRANSACTION")

    /** Every category value that makes a conversation ineligible. */
    val SENSITIVE_CATEGORIES: Set<String> =
        SENSITIVE_FIREWALL_CATEGORIES + SENSITIVE_DOMAINS + SENSITIVE_LOCAL_CATEGORIES + LOCAL_ONLY

    /**
     * Values known to be non-sensitive. [eligible] accepts only these, so an unknown value is
     * refused (fail closed) instead of becoming grantable by accident.
     *
     * The empty string is the production "normal" category
     * (`TelephonySyncCoordinator.enqueueCloudEvent` maps `NORMAL` to `""`), and
     * `READ_MESSAGES` is the normal keyring domain.
     */
    val NON_SENSITIVE_CATEGORIES: Set<String> = setOf(
        "",
        "NORMAL",
        "READ_MESSAGES",
        "PERSONAL",
        "PROMOTION",
        "UNKNOWN",
    )

    private fun categoryKey(category: String): String =
        category.trim().uppercase(Locale.ROOT)

    /**
     * True only if this conversation may receive an AI grant.
     *
     * Returns `false` for:
     *  - a blank or oversized [conversationId];
     *  - every [SENSITIVE_CATEGORIES] value (case-insensitive, trimmed) — the firewall enum
     *    names, the sensitive keyring domains, the sensitive local categories and
     *    [LOCAL_ONLY];
     *  - every category not listed in [NON_SENSITIVE_CATEGORIES] (fail closed).
     *
     * The UI **must not list** a conversation for which this returns false. This is a
     * structural guard layered on top of the firewall: OTP/bank messages never become cloud
     * events in the first place (design §5.8 / ADR-006), so a UI that hid nothing would still
     * not leak their contents — but a scope screen must never offer them either.
     */
    fun eligible(conversationId: String, category: String): Boolean =
        eligible(conversationId, category, localOnly = false)

    /**
     * [eligible] plus an explicit LOCAL_ONLY verdict (normally from
     * [ThreadLocalOnlyPolicy.isLocalOnly]). A conversation the firewall classifies as
     * LOCAL_ONLY is never grantable, whatever its category says.
     */
    fun eligible(conversationId: String, category: String, localOnly: Boolean): Boolean {
        if (localOnly) return false
        if (conversationId.isBlank() || conversationId.length > MAX_CONVERSATION_ID_LENGTH) {
            return false
        }
        val key = categoryKey(category)
        if (key in SENSITIVE_CATEGORIES) return false
        return key in NON_SENSITIVE_CATEGORIES
    }

    /**
     * Structural validation of [selection], without conversation categories.
     *
     * [ScopeSelection.AccountWide] is always valid (it is the explicit "everything eligible"
     * choice). A conversation set is canonicalised: duplicates collapse, ids are sorted, the
     * set must be non-empty and within [MAX_CONVERSATIONS].
     */
    fun validate(selection: ScopeSelection): ScopeValidation = when (selection) {
        ScopeSelection.AccountWide -> ScopeValidation.Valid(ScopeSelection.AccountWide)
        is ScopeSelection.Conversations -> validateIds(selection.ids)
    }

    /** Structural validation of raw ids, with duplicate collapsing. */
    fun validateIds(ids: Collection<String>): ScopeValidation {
        val distinct = ids.toSet()
        if (distinct.isEmpty()) {
            return ScopeValidation.Invalid(ScopeViolation.EMPTY_CONVERSATION_SET)
        }
        if (distinct.size > MAX_CONVERSATIONS) {
            return ScopeValidation.Invalid(ScopeViolation.TOO_MANY_CONVERSATIONS)
        }
        for (id in distinct.sorted()) {
            if (id.isBlank()) {
                return ScopeValidation.Invalid(ScopeViolation.BLANK_CONVERSATION_ID, id)
            }
            if (id.length > MAX_CONVERSATION_ID_LENGTH) {
                return ScopeValidation.Invalid(ScopeViolation.CONVERSATION_ID_TOO_LONG)
            }
        }
        return ScopeValidation.Valid(
            ScopeSelection.Conversations(distinct.sorted().toSet())
        )
    }

    /**
     * Structural validation plus the sensitive-category gate.
     *
     * [categoryOf] returns the stored classification for a conversation (firewall category,
     * keyring domain or local `MessageCategory` name). A conversation for which
     * [eligible] returns false makes the whole scope invalid with
     * [ScopeViolation.SENSITIVE_CONVERSATION]: a scope is never partially accepted, because
     * accepting the rest would publish grants for a selection the operator was told was
     * refused.
     */
    fun validate(
        selection: ScopeSelection,
        categoryOf: (String) -> String,
    ): ScopeValidation {
        val structural = validate(selection)
        if (structural !is ScopeValidation.Valid) return structural
        val conversations = structural.selection
        if (conversations !is ScopeSelection.Conversations) return structural
        for (id in conversations.ids) {
            if (!eligible(id, categoryOf(id))) {
                return ScopeValidation.Invalid(ScopeViolation.SENSITIVE_CONVERSATION, id)
            }
        }
        return structural
    }

    /**
     * Canonical valid selection or an [IllegalArgumentException] naming the violation.
     * The UI/publisher path should prefer this over carrying an invalid selection forward.
     */
    fun requireValid(selection: ScopeSelection): ScopeSelection =
        when (val result = validate(selection)) {
            is ScopeValidation.Valid -> result.selection
            is ScopeValidation.Invalid -> throw IllegalArgumentException(
                "invalid conversation scope: ${result.violation}" +
                    (result.conversationId?.let { " (conversation $it)" } ?: "")
            )
        }

    /** Canonical valid [ScopeSelection.Conversations] built from arbitrary input ids. */
    fun select(ids: Collection<String>): ScopeSelection =
        requireValid(ScopeSelection.Conversations(ids.toSet()))
}

/**
 * One conversation's last known scope decision.
 *
 * Mirrors what the (future) Room scope table must persist per conversation: the monotonic
 * [scopeSequence] at which the decision was made, whether the conversation is revoked, and
 * when. Pure data — no Android types.
 */
data class ScopeEntry(
    val conversationId: String,
    val scopeSequence: Long,
    val revoked: Boolean,
    val updatedAt: Long,
) {
    init {
        require(conversationId.isNotBlank()) { "conversationId must not be blank" }
        require(scopeSequence >= ScopeState.INITIAL_SEQUENCE) {
            "scopeSequence must be >= ${ScopeState.INITIAL_SEQUENCE}"
        }
        require(updatedAt >= 0L) { "updatedAt must be >= 0" }
    }
}

/**
 * The scope of one AI device, as the UI/publisher sees it.
 *
 * [scopeSequence] is the monotonic per-device value that every grant and tombstone carries
 * (design §4.2/§4.5): **every change** allocates a new, strictly greater sequence, so a
 * device can reject a stale or replayed scope. [revoked] is the account-level pause: it is
 * `true` exactly when there is no active [selection], which is what "pause AI access to all
 * conversations" means. [entries] keeps the last per-conversation decision (sorted by id for
 * deterministic equality) so removals can be turned into tombstones by the publisher path.
 *
 * Invariant: `revoked == (selection == null)`. A non-revoked state always has a selection and
 * a revoked state never does, so "paused" cannot be mistaken for "empty allowlist".
 */
data class ScopeState(
    val scopeSequence: Long,
    val revoked: Boolean,
    val updatedAt: Long,
    val selection: ScopeSelection? = null,
    val entries: List<ScopeEntry> = emptyList(),
) {
    init {
        require(scopeSequence >= INITIAL_SEQUENCE) {
            "scopeSequence must be >= $INITIAL_SEQUENCE"
        }
        require(revoked == (selection == null)) {
            "revoked and selection must agree: revoked=$revoked selection=$selection"
        }
        require(updatedAt >= 0L) { "updatedAt must be >= 0" }
        require(entries.map { it.conversationId }.zipWithNext().all { (a, b) -> a < b }) {
            "entries must be sorted and unique by conversationId"
        }
    }

    /** True when the device currently holds a scope (possibly account-wide). */
    val isActive: Boolean get() = !revoked

    /** The explicit ids in scope; empty for [ScopeSelection.AccountWide] or a revoked scope. */
    val activeConversationIds: Set<String>
        get() = (selection as? ScopeSelection.Conversations)?.ids ?: emptySet()

    /** Conversation ids recorded as revoked, sorted. */
    val revokedConversationIds: Set<String>
        get() = entries.asSequence()
            .filter { it.revoked }
            .map { it.conversationId }
            .toSortedSet()

    /** Whether a grant for [conversationId] is currently authorised by this state. */
    fun grants(conversationId: String): Boolean {
        if (revoked || conversationId.isBlank()) return false
        return when (val current = selection ?: return false) {
            ScopeSelection.AccountWide -> true
            is ScopeSelection.Conversations -> conversationId in current.ids
        }
    }

    /**
     * Allocate the next [scopeSequence] without changing the scope, for a publication that
     * re-issues the current selection (or an epoch rotation, design §4.5).
     */
    fun nextSequence(updatedAt: Long = this.updatedAt): ScopeState {
        require(scopeSequence < Long.MAX_VALUE) { "scopeSequence exhausted" }
        return copy(scopeSequence = scopeSequence + 1, updatedAt = updatedAt)
    }

    /**
     * Replace the selection and allocate a new sequence (design §4.6: every change produces
     * a new `scopeSequence` and therefore new grant/revoke events).
     *
     * Conversations that leave the scope get a revoked [ScopeEntry] at the new sequence so a
     * caller can publish tombstones for them. When the previous state was
     * [ScopeSelection.AccountWide] the removed ids are unknown (the device was granted every
     * conversation), so no per-conversation tombstones can be derived here; the caller must
     * publish an account-level revoke instead.
     */
    fun withSelection(
        newSelection: ScopeSelection,
        updatedAt: Long = this.updatedAt,
    ): ScopeState {
        val valid = ConversationScope.requireValid(newSelection)
        require(scopeSequence < Long.MAX_VALUE) { "scopeSequence exhausted" }
        val next = scopeSequence + 1
        val newIds = (valid as? ScopeSelection.Conversations)?.ids ?: emptySet()
        val previousIds = activeConversationIds
        val entries = buildList {
            addAll(this@ScopeState.entries)
            for (id in previousIds - newIds) {
                add(ScopeEntry(id, next, revoked = true, updatedAt = updatedAt))
            }
            for (id in newIds) {
                add(ScopeEntry(id, next, revoked = false, updatedAt = updatedAt))
            }
        }.associateBy { it.conversationId }.values.sortedBy { it.conversationId }
        return ScopeState(
            scopeSequence = next,
            revoked = false,
            updatedAt = updatedAt,
            selection = valid,
            entries = entries,
        )
    }

    /**
     * Revoke one conversation and allocate a new sequence.
     *
     * Idempotent: revoking an already-revoked conversation, or revoking when the whole scope
     * is already revoked, returns `this` without advancing the sequence. Revoking the last
     * granted conversation turns the state into the revoked ("paused") state, because an
     * empty allowlist is not a legal scope.
     *
     * Per-conversation revoke requires an explicit [ScopeSelection.Conversations] selection;
     * an [ScopeSelection.AccountWide] scope must first be narrowed (its conversation list is
     * not known to this model). Use [revokeAll] to revoke an account-wide scope.
     *
     * @throws IllegalArgumentException if [conversationId] is blank or the scope is
     *   account-wide.
     */
    fun revoke(conversationId: String, updatedAt: Long = this.updatedAt): ScopeState {
        require(conversationId.isNotBlank()) { "conversationId must not be blank" }
        if (revoked) return this
        val current = selection ?: return this
        if (current !is ScopeSelection.Conversations) {
            throw IllegalArgumentException(
                "per-conversation revoke requires an explicit conversation scope; " +
                    "use revokeAll() for account scope"
            )
        }
        if (conversationId !in current.ids) {
            // Never granted (or already revoked and removed): nothing left to revoke.
            return this
        }
        require(scopeSequence < Long.MAX_VALUE) { "scopeSequence exhausted" }
        val next = scopeSequence + 1
        val remaining = current.ids - conversationId
        val entries = (this.entries + ScopeEntry(
            conversationId = conversationId,
            scopeSequence = next,
            revoked = true,
            updatedAt = updatedAt,
        )).associateBy { it.conversationId }.values.sortedBy { it.conversationId }
        return if (remaining.isEmpty()) {
            ScopeState(
                scopeSequence = next,
                revoked = true,
                updatedAt = updatedAt,
                selection = null,
                entries = entries,
            )
        } else {
            ScopeState(
                scopeSequence = next,
                revoked = false,
                updatedAt = updatedAt,
                selection = ScopeSelection.Conversations(remaining.toSortedSet()),
                entries = entries,
            )
        }
    }

    /**
     * Revoke every conversation (the "Pause AI access to all conversations" master switch).
     *
     * Idempotent: an already-revoked state is returned unchanged. Every known granted
     * conversation gets a revoked entry at the new sequence; for an account-wide scope there
     * are no per-conversation ids to record, so the revoked state itself is the tombstone the
     * publisher must express (`scopeMode` account revoke).
     */
    fun revokeAll(updatedAt: Long = this.updatedAt): ScopeState {
        if (revoked) return this
        require(scopeSequence < Long.MAX_VALUE) { "scopeSequence exhausted" }
        val next = scopeSequence + 1
        val ids = buildSet {
            addAll(activeConversationIds)
            addAll(entries.asSequence().filter { !it.revoked }.map { it.conversationId })
        }
        val entries = (this.entries + ids.map {
            ScopeEntry(it, next, revoked = true, updatedAt = updatedAt)
        }).associateBy { it.conversationId }.values.sortedBy { it.conversationId }
        return ScopeState(
            scopeSequence = next,
            revoked = true,
            updatedAt = updatedAt,
            selection = null,
            entries = entries,
        )
    }

    companion object {
        /** The first sequence a scope may have; grant payloads require a positive value. */
        const val INITIAL_SEQUENCE = 1L

        /**
         * A freshly approved scope at [INITIAL_SEQUENCE]. For an explicit selection every
         * conversation starts with a granted entry at sequence 1; an account-wide scope has
         * no enumerable ids, hence no entries.
         *
         * @throws IllegalArgumentException when [selection] is not valid
         *   (see [ConversationScope.validate]).
         */
        fun initial(selection: ScopeSelection, updatedAt: Long): ScopeState {
            val valid = ConversationScope.requireValid(selection)
            val entries = when (valid) {
                ScopeSelection.AccountWide -> emptyList()
                is ScopeSelection.Conversations -> valid.ids
                    .map { ScopeEntry(it, INITIAL_SEQUENCE, revoked = false, updatedAt) }
                    .sortedBy { it.conversationId }
            }
            return ScopeState(
                scopeSequence = INITIAL_SEQUENCE,
                revoked = false,
                updatedAt = updatedAt,
                selection = valid,
                entries = entries,
            )
        }
    }
}
