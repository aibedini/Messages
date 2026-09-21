package com.autonomousone.messages.gateway.health

/**
 * The gateway's health, as independent DIMENSIONS instead of one green light.
 *
 * THE PROBLEM THIS REPLACES
 * -------------------------
 * `ConnectionSupervisor` reports `CONNECTED` after it has *started* the heartbeat, the
 * event uploader, the trust publisher, the poller and the sync loop. That sentence means
 * "the components were started". The user reads it as "GMweb and this phone are talking".
 * They are not the same claim, and the gap between them is exactly the reported bug:
 *
 * ```text
 * Android → GMweb event sync     ✅  "2/2 event(s) ACKed by GMweb"
 * GMweb → Android delivery       ❌  /gateway/pull never succeeds
 * supervisor state               CONNECTED        ← green
 * ```
 *
 * An event upload ACK proves `/api/v1/agent/events/batch` worked. It is a DIFFERENT route,
 * a different direction and a different purpose from `/gateway/pull`, which is what
 * actually delivers an EVE send request to this phone. So [GatewayOverallHealth] is
 * derived from the transport dimensions, and an upload success can never set the pull
 * bridge healthy.
 *
 * THE FRESHNESS RULE
 * ------------------
 * A long-poll that returns HTTP 200 with `{"task": null}` is a SUCCESSFUL bridge poll. It
 * proves the phone reached GMweb, authenticated, was recognised as a gateway and got a
 * well-formed answer. Requiring an actual SMS task to call the bridge healthy would report
 * a perfectly working bridge as broken every time the queue is empty — which is almost
 * always. [PullBridgeHealth.lastSuccessfulPollAt] therefore updates on an empty poll too.
 */
enum class GatewayOverallHealth {
    /** Not desired (disabled, not enrolled, or no URL configured). */
    OFFLINE,

    /** Desired, but nothing has been observed yet. Never a success claim. */
    STARTING,

    /** Every required transport dimension is fresh. */
    HEALTHY,

    /** Working, but at least one dimension is stale or a poll failed transiently. */
    DEGRADED,

    /** An explicit failure that retrying cannot fix, or a persistent one. */
    ERROR
}

/**
 * Why the card says what it says. The message itself is a string resource resolved by the
 * UI; the CHOICE is part of the tested contract.
 */
enum class GatewayConclusion {
    NONE,
    DISABLED,
    NO_NETWORK,
    SERVER_UNREACHABLE,
    DNS_PROBLEM,
    TLS_PROBLEM,
    AUTH_REJECTED,
    WRONG_URL,
    RATE_LIMITED,
    SERVER_ERROR,
    BRIDGE_NOT_POLLING,
    BRIDGE_FAILING,
    UPLOAD_STALLED,
    UNKNOWN_FAILURE
}

data class NetworkHealth(
    val validatedInternet: Boolean = false,
    /** `Wi-Fi`, `Cellular`, `Other`, `None`. */
    val transport: String = "None",
    val observedAt: Long = 0L
)

data class EndpointHealth(
    val configured: Boolean = false,
    val host: String? = null,
    val port: Int? = null,
    /** Measured TCP connect latency. Never ICMP: mobile networks block it while HTTPS works. */
    val lastTcpConnectMs: Long? = null,
    val lastProbeAt: Long? = null
)

data class TlsHealth(
    val valid: Boolean? = null,
    val protocol: String? = null,
    val issuer: String? = null,
    val notAfter: Long? = null,
    /** False when the certificate does not cover the configured host or IP. */
    val hostMatched: Boolean? = null,
    val lastCheckedAt: Long? = null
) {
    /** Whole days until expiry, or null when unknown. Negative once expired. */
    fun daysUntilExpiry(now: Long): Long? =
        notAfter?.let { (it - now) / 86_400_000L }
}

data class AuthHealth(
    val enrolled: Boolean = false,
    val lastVerifiedAt: Long? = null,
    val clockSkewMs: Long? = null
)

data class EventUploadHealth(
    val running: Boolean = false,
    val pending: Int = 0,
    val sending: Int = 0,
    val deadLetter: Int = 0,
    val lastAttemptAt: Long? = null,
    val lastSuccessAt: Long? = null,
    val lastHttpStatus: Int? = null,
    val lastFailure: GatewayFailureKind? = null,
    val lastFailureSafeDetail: String? = null
) {
    /**
     * Healthy does NOT mean "recently uploaded something".
     *
     * A phone with nothing to say is not a broken phone: an empty queue with no failure is
     * the normal resting state, and reporting it as unhealthy would make every quiet device
     * look alarmed.
     */
    val healthy: Boolean
        get() = running && lastFailure == null && deadLetter == 0

    /** True only when an upload actually succeeded recently. Used for the "last sync" row. */
    fun uploadedWithin(windowMs: Long, now: Long): Boolean =
        lastSuccessAt?.let { now - it <= windowMs } ?: false
}

data class PullBridgeHealth(
    val running: Boolean = false,
    /** The poller's own state name (`IDLE`, `POLLING`, `DELIVERING`, `ERROR`). */
    val state: String = "IDLE",
    val lastPollStartedAt: Long? = null,
    val lastSuccessfulPollAt: Long? = null,
    val lastEmptyPollAt: Long? = null,
    val lastTaskReceivedAt: Long? = null,
    val lastAckAt: Long? = null,
    val consecutiveFailures: Int = 0,
    val lastHttpStatus: Int? = null,
    val lastFailure: GatewayFailureKind? = null,
    val lastFailureSafeDetail: String? = null,
    /** When the in-flight long-poll started, so the card can show it is waiting. */
    val currentRequestStartedAt: Long? = null,
    val nextRetryAt: Long? = null,
    /**
     * Failed result deliveries. Separate from [lastFailure], which describes the PULL: a
     * task can be delivered and its result still fail to reach GMweb, and those are two
     * different problems with two different fixes.
     */
    val ackFailures: Int = 0,
    val lastAckFailure: GatewayFailureKind? = null,
    val lastAckFailureSafeDetail: String? = null
) {
    /** A successful poll within [windowMs], whether or not it carried a task. */
    fun freshWithin(windowMs: Long, now: Long): Boolean =
        lastSuccessfulPollAt?.let { now - it <= windowMs } ?: false

    /** How long since the last successful poll, or null when there has never been one. */
    fun stalenessMs(now: Long): Long? = lastSuccessfulPollAt?.let { now - it }
}

data class EveQueueHealth(
    val queued: Int = 0,
    val active: Int = 0,
    val deferred: Int = 0,
    val sentRecent: Int = 0,
    val failedRecent: Int = 0,
    val cancelledRecent: Int = 0,
    /** Short token only — never a full request id, never a body, never a number. */
    val lastPulledRequestToken: String? = null,
    val lastLocalTransitionAt: Long? = null,
    val lastNativeSubmitAt: Long? = null,
    val lastGatewayAckAt: Long? = null
) {
    val idle: Boolean get() = queued == 0 && active == 0 && deferred == 0
}

data class GatewayHealthSnapshot(
    val generatedAt: Long,
    val desired: Boolean = false,
    val network: NetworkHealth = NetworkHealth(),
    val endpoint: EndpointHealth = EndpointHealth(),
    val tls: TlsHealth = TlsHealth(),
    val authentication: AuthHealth = AuthHealth(),
    val eventUpload: EventUploadHealth = EventUploadHealth(),
    val pullBridge: PullBridgeHealth = PullBridgeHealth(),
    val eveQueue: EveQueueHealth = EveQueueHealth(),
    val overall: GatewayOverallHealth = GatewayOverallHealth.OFFLINE,
    val conclusion: GatewayConclusion = GatewayConclusion.NONE
)

/**
 * The ONE derivation of the overall state and the conclusion.
 *
 * It is a pure function of the snapshot so the acceptance cases are unit tests:
 *
 *  - an upload ACK while the bridge is dead → DEGRADED, conclusion BRIDGE_NOT_POLLING;
 *  - `{"task": null}` → the bridge is fresh, HEALTHY;
 *  - a stale successful poll → DEGRADED;
 *  - HTTP 401 → ERROR, AUTH_REJECTED (retrying a rejected key cannot help);
 *  - no validated internet → OFFLINE, not ERROR (there is nothing to fix in the app);
 *  - supervisor CONNECTED with nothing observed yet → STARTING, never HEALTHY.
 */
object GatewayHealthRules {

    /**
     * How long a successful poll stays fresh.
     *
     * The long-poll holds for ~25 s and the client timeout is 40 s, so a healthy bridge
     * necessarily has gaps of tens of seconds; 90 s leaves room for one missed cycle plus
     * scheduling jitter without flapping.
     */
    const val PULL_FRESH_MS = 90_000L

    /** Long enough that a quiet device is not reported as a stalled uploader. */
    const val UPLOAD_FRESH_MS = 15 * 60_000L

    /** A transient poll failure becomes an ERROR only after this many in a row. */
    const val PERSISTENT_FAILURE_THRESHOLD = 3

    fun overall(snapshot: GatewayHealthSnapshot, now: Long): GatewayOverallHealth {
        if (!snapshot.desired) return GatewayOverallHealth.OFFLINE
        if (!snapshot.network.validatedInternet) {
            // Nothing observed before the network went away is not a failure of anything
            // the app can fix.
            return if (snapshot.pullBridge.lastPollStartedAt == null) {
                GatewayOverallHealth.OFFLINE
            } else {
                GatewayOverallHealth.DEGRADED
            }
        }
        if (snapshot.pullBridge.lastPollStartedAt == null &&
            snapshot.eventUpload.lastAttemptAt == null
        ) {
            // "Nothing has been tried yet." Deliberately NOT gated on the auth probe or on
            // a configured endpoint: knowing the key is good and the URL is set is not
            // evidence that any transport worked, and treating it as evidence is how a
            // gateway that has never moved a byte comes up green.
            return GatewayOverallHealth.STARTING
        }

        val failure = snapshot.pullBridge.lastFailure
        if (failure != null) {
            return when {
                // A rejected key, a bad certificate, a wrong route or a malformed API does
                // not heal by retrying, so it is an ERROR on the first occurrence.
                failure.needsConfigurationChange -> GatewayOverallHealth.ERROR
                snapshot.pullBridge.consecutiveFailures >= PERSISTENT_FAILURE_THRESHOLD ->
                    GatewayOverallHealth.ERROR
                else -> GatewayOverallHealth.DEGRADED
            }
        }

        // A TLS or endpoint probe that failed on its own is a real fault even when a poll
        // has not run yet.
        if (snapshot.tls.valid == false || snapshot.tls.hostMatched == false) {
            return GatewayOverallHealth.ERROR
        }
        if (!snapshot.authentication.enrolled) return GatewayOverallHealth.ERROR

        val bridgeFresh = snapshot.pullBridge.freshWithin(PULL_FRESH_MS, now)
        if (!bridgeFresh) {
            // The uploader working does NOT rescue this: outbound event sync and inbound
            // task delivery are different routes, and only the second one delivers an EVE
            // send request to this phone.
            return GatewayOverallHealth.DEGRADED
        }

        return if (snapshot.eventUpload.healthy) {
            GatewayOverallHealth.HEALTHY
        } else {
            // The bridge is fine but the uploader is broken: still degraded, and the
            // conclusion says which half.
            GatewayOverallHealth.DEGRADED
        }
    }

    fun conclusion(snapshot: GatewayHealthSnapshot, now: Long): GatewayConclusion {
        if (!snapshot.desired) return GatewayConclusion.DISABLED
        if (!snapshot.network.validatedInternet) return GatewayConclusion.NO_NETWORK

        val failure = snapshot.pullBridge.lastFailure
        if (failure != null) {
            return when (failure) {
                GatewayFailureKind.HTTP_AUTH -> GatewayConclusion.AUTH_REJECTED
                GatewayFailureKind.HTTP_FORBIDDEN -> GatewayConclusion.AUTH_REJECTED
                GatewayFailureKind.HTTP_NOT_FOUND -> GatewayConclusion.WRONG_URL
                GatewayFailureKind.HTTP_RATE_LIMITED -> GatewayConclusion.RATE_LIMITED
                GatewayFailureKind.HTTP_SERVER -> GatewayConclusion.SERVER_ERROR
                GatewayFailureKind.DNS -> GatewayConclusion.DNS_PROBLEM
                GatewayFailureKind.TCP_CONNECT -> GatewayConclusion.SERVER_UNREACHABLE
                GatewayFailureKind.TLS -> GatewayConclusion.TLS_PROBLEM
                GatewayFailureKind.INVALID_RESPONSE -> GatewayConclusion.UNKNOWN_FAILURE
                GatewayFailureKind.VALIDATION_FAILED -> GatewayConclusion.UNKNOWN_FAILURE
                else -> GatewayConclusion.BRIDGE_FAILING
            }
        }

        if (snapshot.tls.hostMatched == false || snapshot.tls.valid == false) {
            return GatewayConclusion.TLS_PROBLEM
        }
        if (!snapshot.authentication.enrolled) return GatewayConclusion.AUTH_REJECTED

        if (!snapshot.pullBridge.freshWithin(PULL_FRESH_MS, now)) {
            return GatewayConclusion.BRIDGE_NOT_POLLING
        }
        if (!snapshot.eventUpload.healthy) return GatewayConclusion.UPLOAD_STALLED
        return GatewayConclusion.NONE
    }

    /** One call for the whole derivation, so the two can never disagree. */
    fun evaluate(
        snapshot: GatewayHealthSnapshot,
        now: Long
    ): GatewayHealthSnapshot = snapshot.copy(
        overall = overall(snapshot, now),
        conclusion = conclusion(snapshot, now)
    )
}
