package com.autonomousone.messages.data

/**
 * ADR-006 — SyncEligibility boundary (Android Data Plane security gate).
 *
 * The single choke point through which every cloud event must pass
 * ([TelephonySyncCoordinator.enqueueCloudEvent]). A message that fails
 * eligibility produces NO GatewayEventOutbox row — not a deferred row, not a
 * redacted row: the event never exists.
 *
 * v2.6.23 boundary scope (deliberately minimal, no crypto per ADR timing):
 *   - a per-thread sticky LOCAL_ONLY decision, seeded by explicit user
 *     policy (ADR-006 §4 per-sender policy lands with the full
 *     SensitiveMessageFirewall PR — the table + gate are live now so no
 *     future classifier PR has to migrate event semantics).
 *
 * Invariant (ADR-006 §7, regression-tested):
 *   LOCAL_ONLY ⇒ zero rows in gateway_event_outbox, ever, even transiently.
 */
object SyncEligibility {

    /** Policy verdicts (ADR-006 §1 categories map onto these two outcomes). */
    enum class Decision { SYNC, LOCAL_ONLY }

    /**
     * Decide for a message body + sender. v1 (this boundary PR): deterministic
     * sender-prefix pass-through — everything is SYNC unless a sticky
     * local-only rule exists. The SensitiveMessageFirewall PR replaces this
     * body with the full classifier (sender rules + keywords + code patterns
     * + DigitNormalizer + user overrides) behind this exact signature.
     */
    fun decide(
        source: String,
        providerId: Long,
        normalizedAddress: String,
        body: String,
        stickyLocalOnly: Boolean
    ): Decision =
        if (stickyLocalOnly) Decision.LOCAL_ONLY else Decision.SYNC
}
