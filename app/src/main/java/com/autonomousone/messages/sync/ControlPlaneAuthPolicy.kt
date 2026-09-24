package com.autonomousone.messages.sync

/**
 * What the control plane's answer to an AUTHENTICATED agent call means (mission §44/§56/§57).
 *
 * The distinction this type exists for is the one the app used to collapse: **401 and 403 are not the
 * same fact.**
 *
 *  - 401 — "I do not accept this credential." Re-enrolling is the right response, because the
 *    credential is what is wrong.
 *  - 403 — "I know who you are and I refuse you." Re-enrolling is the WRONG response, and the app
 *    used to do something worse than that: `HeartbeatManager` treated `isAuthError` (which is
 *    `401 || 403`) as "clear the credentials and re-register", so a single 403 deleted the device id,
 *    the gateway token and the registered marker (`GatewayPreferences.clearCloudCredentials`).
 *
 * That the two must be told apart is not a new judgement: [SyncErrorCode.fromHttpStatus] has mapped
 * 401 → `AUTH_REQUIRED` and 403 → `DEVICE_REVOKED` from the start, and [GatewayFailureKind] separates
 * `HTTP_AUTH` from `HTTP_FORBIDDEN`. The distinction was available in three places and collapsed at
 * the one place that *acts* on it — the same "a rule restated at a call site loses clauses" pattern
 * as Blocker 2, with a destructive side effect.
 */
enum class ControlPlaneAuthSignal {

    /** The call succeeded. This is the only thing that proves the credential is accepted. */
    ACCEPTED,

    /** HTTP 401: the credential is not accepted. */
    UNAUTHORIZED,

    /** HTTP 403: the device is recognised and refused. */
    FORBIDDEN,

    /**
     * Anything else — a 400, a 5xx, a timeout.
     *
     * Proves nothing about the credential, so it changes nothing. Treating it as evidence is how a
     * probe timeout would otherwise revoke a device.
     */
    INCONCLUSIVE;

    companion object {
        /** Map an HTTP status from an authenticated agent call onto a signal. */
        fun fromHttpStatus(status: Int?): ControlPlaneAuthSignal = when (status) {
            401 -> UNAUTHORIZED
            403 -> FORBIDDEN
            else -> INCONCLUSIVE
        }
    }
}

/**
 * The durable fact: has the control plane refused this device, and how strong is the evidence?
 *
 * Kept as a value with no Android and no Room in it so the transition table below is directly
 * testable — this decides whether a device destroys its own enrollment, which is not a decision to
 * leave to an integration test.
 */
data class ControlPlaneAuthState(
    /** 0 = not revoked. */
    val revokedAt: Long = 0,
    /** Consecutive FORBIDDEN answers, reset by anything else. */
    val consecutiveRejections: Int = 0,
    val lastRejectionStatus: Int? = null,
    val lastAcceptedAt: Long = 0,
    /** When a revocation was last lifted, so "revoked, then fixed" is visible. */
    val clearedAt: Long = 0,
) {
    val revoked: Boolean get() = revokedAt > 0
}

/**
 * What the caller must do, and the state to persist.
 *
 * The action is part of the decision rather than something each call site derives from the signal,
 * because "should I delete this device's credentials?" is exactly the question that was answered
 * wrongly by reading `isAuthError` as a boolean.
 */
data class ControlPlaneAuthDecision(
    val state: ControlPlaneAuthState,
    /**
     * Only ever true for [ControlPlaneAuthSignal.UNAUTHORIZED].
     *
     * A credential that is not accepted is worthless, so replacing it is the fix. A device the server
     * REFUSES still holds a perfectly good credential — and the enrollment, the device id and the
     * pins — which is why a 403 must never destroy them.
     */
    val replaceCredentialAndReEnroll: Boolean,
    /** True when this answer is the evidence that the device is revoked. */
    val revoked: Boolean,
)

/**
 * The transition table (mission §44). Pure.
 *
 * | signal | credential | consecutive | revocation |
 * |---|---|---|---|
 * | ACCEPTED | kept | reset to 0 | CLEARED |
 * | UNAUTHORIZED | replaced + re-enroll | reset to 0 | **unchanged** |
 * | FORBIDDEN | **kept** | +1 | set once it reaches the threshold |
 * | INCONCLUSIVE | kept | unchanged | unchanged |
 *
 * Two deliberate asymmetries:
 *
 *  - **A 401 does not clear a revocation.** "Your credential is stale" says nothing about whether the
 *    device is still forbidden. Only a success clears it.
 *  - **A 401 resets the FORBIDDEN counter**, because the sequence "403, 401, 403" is not two
 *    consecutive refusals; a single flapping proxy must not add up to a revocation.
 */
object ControlPlaneAuthPolicy {

    /**
     * How many consecutive 403s are required before the device is called revoked.
     *
     * Two, not one. The signal is strong — an authenticated agent endpoint refused this device — but
     * a single 403 can come from an intercepting proxy, a WAF, or a misrouted host, and the cost of
     * the two errors is not symmetric: a false "revoked" holds uploads and tells the owner to
     * intervene, while a missed one is resolved by the next heartbeat seconds later. Nothing is lost
     * by waiting one interval, because the heartbeat is periodic and this is not a race.
     */
    const val REVOCATION_THRESHOLD = 2

    fun decide(
        previous: ControlPlaneAuthState,
        signal: ControlPlaneAuthSignal,
        httpStatus: Int?,
        now: Long,
    ): ControlPlaneAuthDecision = when (signal) {

        ControlPlaneAuthSignal.ACCEPTED -> ControlPlaneAuthDecision(
            state = ControlPlaneAuthState(
                revokedAt = 0,
                consecutiveRejections = 0,
                lastRejectionStatus = previous.lastRejectionStatus,
                lastAcceptedAt = now,
                clearedAt = if (previous.revoked) now else previous.clearedAt,
            ),
            replaceCredentialAndReEnroll = false,
            revoked = false,
        )

        ControlPlaneAuthSignal.UNAUTHORIZED -> ControlPlaneAuthDecision(
            state = previous.copy(
                consecutiveRejections = 0,
                lastRejectionStatus = 401,
            ),
            replaceCredentialAndReEnroll = true,
            revoked = previous.revoked,
        )

        ControlPlaneAuthSignal.FORBIDDEN -> {
            val consecutive = previous.consecutiveRejections + 1
            val nowRevoked = previous.revoked || consecutive >= REVOCATION_THRESHOLD
            ControlPlaneAuthDecision(
                state = previous.copy(
                    revokedAt = if (nowRevoked && previous.revokedAt == 0L) now else previous.revokedAt,
                    consecutiveRejections = consecutive,
                    lastRejectionStatus = httpStatus ?: 403,
                ),
                replaceCredentialAndReEnroll = false,
                revoked = nowRevoked,
            )
        }

        ControlPlaneAuthSignal.INCONCLUSIVE -> ControlPlaneAuthDecision(
            state = previous,
            replaceCredentialAndReEnroll = false,
            revoked = previous.revoked,
        )
    }
}
