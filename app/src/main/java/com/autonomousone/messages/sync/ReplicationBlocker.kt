package com.autonomousone.messages.sync

/**
 * One reason replication cannot proceed right now.
 *
 * A blocker is not "something went wrong" — it is a *cause with a known fix*, which is the whole
 * point: the diagnostic must be able to say `Replication blocked: IDENTITY_NOT_REGISTERED`
 * instead of `DEGRADED` (mission §57, audit Blocker 2). Everything here is therefore phrased as
 * a condition a human can act on, never as an observed symptom.
 *
 * The mission's seven blockers are all present and unchanged. Three more are added, each one
 * because the audit proved it is a real, currently-invisible stop with its own remedy:
 *
 *  - [ConsentRevoked] — consent is checked in eight places with different combinations
 *    (`docs/gateway-replication-audit.md`, Blocker 2), and revoking it does not stop the
 *    enqueue path at all (Blocker 17).
 *  - [TelephonyPermissionMissing] — a missing READ_SMS fails log-only and retries forever
 *    (Blocker 8). The mission's own §40/§44 require this state to be loud.
 *  - [NetworkUnavailable] — distinct from every other blocker because it is the only one that
 *    resolves by waiting; conflating it with a config fault is what makes "offline" look broken.
 *
 * [priority] orders blockers that can be true at the same time, so the reported one is stable and
 * is the earliest thing a human must fix. It is deliberately a property of the blocker rather
 * than a list held by the evaluator: adding a blocker must not be able to silently fall out of
 * the ordering.
 */
sealed interface ReplicationBlocker {

    /** The structured code this blocker is reported as. */
    val code: SyncErrorCode

    /** Lower sorts first. Resolving the lowest-priority blocker is the next useful action. */
    val priority: Int

    /**
     * What kind of fix this needs.
     *
     * Exists so the state mappers can be written once per CATEGORY rather than exhaustively per
     * blocker in three different places. Adding a blocker then forces exactly one real decision
     * (which category it belongs to) instead of three mechanical ones that are easy to get
     * subtly wrong.
     */
    val category: BlockerCategory

    /** The user switched the gateway off. Nothing else is worth reporting. */
    data object GatewayDisabled : ReplicationBlocker {
        override val code: SyncErrorCode = SyncErrorCode.GATEWAY_DISABLED
        override val priority: Int = 10
        override val category: BlockerCategory = BlockerCategory.SWITCHED_OFF
    }

    /** The gateway is desired but consent has been withdrawn. */
    data object ConsentRevoked : ReplicationBlocker {
        override val code: SyncErrorCode = SyncErrorCode.GATEWAY_DISABLED
        override val priority: Int = 15
        override val category: BlockerCategory = BlockerCategory.SWITCHED_OFF
    }

    /** No GMweb origin is stored, so there is nowhere to send anything. */
    data object MissingServerUrl : ReplicationBlocker {
        override val code: SyncErrorCode = SyncErrorCode.CONFIG_MISSING_SERVER_URL
        override val priority: Int = 20
        override val category: BlockerCategory = BlockerCategory.CONFIGURATION
    }

    /**
     * The server has revoked this device.
     *
     * Outranks [IdentityNotRegistered] because re-enrolling a revoked device is the wrong
     * response — retrying is what the audit found the code does today
     * (`HeartbeatManager` clears credentials and re-registers on 401/403).
     */
    data object DeviceRevoked : ReplicationBlocker {
        override val code: SyncErrorCode = SyncErrorCode.DEVICE_REVOKED
        override val priority: Int = 25
        override val category: BlockerCategory = BlockerCategory.AUTHORIZATION
    }

    /** Identity enrollment has never completed. */
    data object IdentityNotRegistered : ReplicationBlocker {
        override val code: SyncErrorCode = SyncErrorCode.IDENTITY_NOT_REGISTERED
        override val priority: Int = 30
        override val category: BlockerCategory = BlockerCategory.IDENTITY
    }

    /** The control plane rejected our credential. Only 401/403 may produce this. */
    data object AuthenticationRequired : ReplicationBlocker {
        override val code: SyncErrorCode = SyncErrorCode.AUTH_REQUIRED
        override val priority: Int = 35
        override val category: BlockerCategory = BlockerCategory.AUTHORIZATION
    }

    /** The key material needed to encrypt is unavailable. Never resolved by plaintext. */
    data object MissingCryptoKey : ReplicationBlocker {
        override val code: SyncErrorCode = SyncErrorCode.CRYPTO_KEY_UNAVAILABLE
        override val priority: Int = 40
        override val category: BlockerCategory = BlockerCategory.KEY
    }

    /** READ_SMS / the MMS equivalent is not granted, so history cannot be read at all. */
    data object TelephonyPermissionMissing : ReplicationBlocker {
        override val code: SyncErrorCode = SyncErrorCode.TELEPHONY_PERMISSION_MISSING
        override val priority: Int = 50
        override val category: BlockerCategory = BlockerCategory.PERMISSION
    }

    /**
     * No authorized consumer holds FULL_HISTORY.
     *
     * In this codebase `historyGrant` is a per-linked-web-device permission
     * (`FULL_HISTORY` | `FROM_NOW_ON`) that governs who receives the History Master Key, so this
     * blocker means "history backfill has no destination", not "Android lacks a permission".
     * Android-side permission is [TelephonyPermissionMissing].
     */
    data object MissingHistoryGrant : ReplicationBlocker {
        override val code: SyncErrorCode = SyncErrorCode.HISTORY_GRANT_MISSING
        override val priority: Int = 60
        override val category: BlockerCategory = BlockerCategory.GRANT
    }

    /** No validated internet. The only blocker that resolves by waiting. */
    data object NetworkUnavailable : ReplicationBlocker {
        override val code: SyncErrorCode = SyncErrorCode.NETWORK_UNAVAILABLE
        override val priority: Int = 70
        override val category: BlockerCategory = BlockerCategory.NETWORK
    }

    companion object {

        /**
         * Every blocker, in the order a human should resolve them.
         *
         * Declared explicitly so a new blocker cannot be added without appearing here — the
         * round-trip is asserted by a test.
         */
        val ALL: List<ReplicationBlocker> = listOf(
            GatewayDisabled,
            ConsentRevoked,
            MissingServerUrl,
            DeviceRevoked,
            IdentityNotRegistered,
            AuthenticationRequired,
            MissingCryptoKey,
            TelephonyPermissionMissing,
            MissingHistoryGrant,
            NetworkUnavailable
        )

        /** The earliest blocker in [blockers], or null when nothing blocks. */
        fun earliest(blockers: Set<ReplicationBlocker>): ReplicationBlocker? =
            blockers.minWithOrNull(compareBy({ it.priority }, { it.code.name }))
    }
}

/**
 * What kind of fix a blocker needs, so the state mappers can be written once per category.
 *
 * The tie-break in [ReplicationBlocker.earliest] uses the code name, so two blockers in the same
 * category still produce a stable answer.
 */
enum class BlockerCategory {
    /** The user turned it off, or withdrew consent. */
    SWITCHED_OFF,

    /** Something must be configured. */
    CONFIGURATION,

    /** Enrollment has not happened. */
    IDENTITY,

    /** A credential was rejected or a device revoked. A human must re-authorize. */
    AUTHORIZATION,

    /** Key material is missing. Never solved by downgrading the crypto. */
    KEY,

    /** An Android runtime permission is missing. */
    PERMISSION,

    /** An authorized consumer is missing, so there is nothing to replicate history to. */
    GRANT,

    /** Nothing is misconfigured; there is simply no network. Resolves by waiting. */
    NETWORK
}
