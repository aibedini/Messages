package com.autonomousone.messages.gateway.health

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where the LIVE components report what actually happened.
 *
 * Every dimension of [GatewayHealthSnapshot] is written here by the thing that owns it —
 * `OutboxPoller` for the pull bridge and the ACKs, `EventUploader` for the outbound sync,
 * the network monitor for the route, the connectivity probe for TLS — and nobody derives a
 * verdict on their own. The verdict is [GatewayHealthRules], one pure function, so the card
 * can never disagree with the data.
 *
 * WHY A SINGLETON RATHER THAN INJECTED STATE
 * ------------------------------------------
 * The producers are created in different places and different lifetimes (a foreground
 * service, a supervisor, a worker-driven uploader) and they all outlive any one screen.
 * The health has to be readable at any moment — including by a diagnostic report built
 * before the poller has ever run — so it lives in one process-wide, lock-guarded registry,
 * exactly like `ThreadMessageCache` and for the same reason: the contract is then
 * JVM-testable without spinning up Android.
 *
 * IT NEVER TOUCHES THE NETWORK, and nothing here is expensive: recording is a few field
 * writes, and [snapshot] is pure arithmetic. The costly checks (DNS, TCP, TLS, provider row
 * counts) belong to [GatewayConnectivityProbe] and are run on explicit user action.
 *
 * PRIVACY: only counts, HTTP statuses, enums and already-redacted captions are stored. No
 * message body, no full phone number and no key material ever enters this object.
 */
object GatewayHealthRecorder {

    private val lock = Any()

    /** Bumped on every recorded change, so a screen can recompose without polling. */
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    // ── Desired state / endpoint ────────────────────────────────────────────

    private var desired: Boolean = false
    private var endpoint: GatewayEndpoint? = null

    // ── Network ─────────────────────────────────────────────────────────────

    private var networkValidated: Boolean = false
    private var networkTransport: String = "None"
    private var networkObservedAt: Long = 0L

    // ── Probe-owned dimensions ──────────────────────────────────────────────

    private var tcpConnectMs: Long? = null
    private var probeAt: Long? = null
    private var dnsAddresses: List<String> = emptyList()
    private var dnsCheckedAt: Long? = null
    private var tls: TlsHealth = TlsHealth()
    private var authentication: AuthHealth = AuthHealth()

    // ── Outbound event sync ─────────────────────────────────────────────────

    private var uploaderRunning: Boolean = false
    private var uploadPending: Int = 0
    private var uploadSending: Int = 0
    private var uploadDeadLetter: Int = 0
    private var uploadLastAttemptAt: Long? = null
    private var uploadLastSuccessAt: Long? = null
    private var uploadLastHttpStatus: Int? = null
    private var uploadLastFailure: GatewayFailureKind? = null
    private var uploadLastFailureDetail: String? = null
    private var uploadConsecutiveFailures: Int = 0
    private var uploadLastFailureAt: Long? = null

    // ── Inbound delivery bridge ─────────────────────────────────────────────

    private var pollerRunning: Boolean = false
    private var pollerState: String = "IDLE"
    private var pullLastPollStartedAt: Long? = null
    private var pullLastSuccessfulPollAt: Long? = null
    private var pullLastEmptyPollAt: Long? = null
    private var pullLastTaskReceivedAt: Long? = null
    private var pullLastAckAt: Long? = null
    private var pullConsecutiveFailures: Int = 0
    private var pullLastHttpStatus: Int? = null
    private var pullLastFailure: GatewayFailureKind? = null
    private var pullLastFailureDetail: String? = null
    private var pullCurrentRequestStartedAt: Long? = null
    private var pullNextRetryAt: Long? = null
    private var ackFailureCount: Int = 0
    private var ackConsecutiveFailures: Int = 0
    private var lastAckFailureAt: Long? = null
    private var lastAckFailure: GatewayFailureKind? = null
    private var lastAckFailureDetail: String? = null

    // ── Local send queue ───────────────────────────────────────────────────

    private var eveQueue: EveQueueHealth = EveQueueHealth()

    // ════════════════════════════════════════════════════════════════════════
    // Desired state and endpoint
    // ════════════════════════════════════════════════════════════════════════

    fun setDesired(value: Boolean) = mutate { desired = value }

    /**
     * Records the configured GMweb URL.
     *
     * Deliberately parses instead of trusting: an unusable URL must show as "not
     * configured" rather than as a series of confusing DNS failures.
     */
    fun setEndpointUrl(url: String?) = mutate {
        endpoint = GatewayEndpoint.parse(url.orEmpty())
    }

    fun currentEndpoint(): GatewayEndpoint? = synchronized(lock) { endpoint }

    // ════════════════════════════════════════════════════════════════════════
    // Network
    // ════════════════════════════════════════════════════════════════════════

    fun onNetwork(
        validated: Boolean,
        transport: String,
        at: Long = System.currentTimeMillis()
    ) = mutate {
        networkValidated = validated
        networkTransport = transport.ifBlank { "None" }
        networkObservedAt = at
    }

    // ════════════════════════════════════════════════════════════════════════
    // Probe-owned dimensions
    // ════════════════════════════════════════════════════════════════════════

    fun onTcpProbe(latencyMs: Long?, at: Long = System.currentTimeMillis()) = mutate {
        tcpConnectMs = latencyMs
        probeAt = at
    }

    /**
     * The system-DNS answer for the configured host.
     *
     * Stored as-is and LABELLED at render time, because a VPN or proxy answering DNS with a
     * synthetic address is a legitimate configuration — it just must not be presented as the
     * server's real origin.
     */
    fun onDnsProbe(addresses: List<String>, at: Long = System.currentTimeMillis()) = mutate {
        dnsAddresses = addresses
        dnsCheckedAt = at
    }

    fun onTlsProbe(result: TlsHealth) = mutate {
        tls = result
    }

    fun onAuthProbe(result: AuthHealth) = mutate {
        authentication = result
    }

    // ════════════════════════════════════════════════════════════════════════
    // Outbound event sync (EventUploader)
    //
    // NOTE: nothing in this section may touch the pull bridge. An upload ACK proves
    // /api/v1/agent/events/batch worked; it says nothing about /gateway/pull, which is
    // what delivers an EVE send request to this phone.
    // ════════════════════════════════════════════════════════════════════════

    fun setUploaderRunning(running: Boolean) = mutate { uploaderRunning = running }

    fun setUploadQueue(pending: Int, sending: Int, deadLetter: Int) = mutate {
        uploadPending = pending
        uploadSending = sending
        uploadDeadLetter = deadLetter
    }

    fun onUploadAttempt(at: Long = System.currentTimeMillis()) = mutate {
        uploadLastAttemptAt = at
    }

    fun onUploadSuccess(
        at: Long = System.currentTimeMillis(),
        httpStatus: Int = 200
    ) = mutate {
        uploadLastAttemptAt = at
        uploadLastSuccessAt = at
        uploadLastHttpStatus = httpStatus
        uploadLastFailure = null
        uploadLastFailureDetail = null
        uploadConsecutiveFailures = 0
        uploadLastFailureAt = null
    }

    fun onUploadFailure(
        kind: GatewayFailureKind,
        httpStatus: Int? = null,
        safeDetail: String? = null,
        at: Long = System.currentTimeMillis()
    ) = mutate {
        uploadLastAttemptAt = at
        uploadLastHttpStatus = httpStatus
        uploadLastFailure = kind
        uploadLastFailureDetail = GatewayHealthText.safeDetail(safeDetail)
        uploadConsecutiveFailures += 1
        uploadLastFailureAt = at
    }

    // ════════════════════════════════════════════════════════════════════════
    // Inbound delivery bridge (OutboxPoller)
    // ════════════════════════════════════════════════════════════════════════

    fun setPollerRunning(running: Boolean) = mutate { pollerRunning = running }

    fun setPollerState(state: String) = mutate { pollerState = state }

    /** A long-poll was issued. The previous failure stays visible until a success. */
    fun onPullStart(at: Long = System.currentTimeMillis()) = mutate {
        pullLastPollStartedAt = at
        pullCurrentRequestStartedAt = at
        pullNextRetryAt = null
    }

    /**
     * The long-poll came back with HTTP 200 and NO task.
     *
     * THIS IS A SUCCESS. It proves the phone reached GMweb, authenticated, was recognised
     * as a gateway and received a well-formed answer. Requiring an actual SMS task would
     * report a perfectly working bridge as broken every time the queue is empty.
     */
    fun onPullEmpty(at: Long = System.currentTimeMillis(), httpStatus: Int = 200) =
        recordPullSuccess(at, httpStatus, empty = true)

    /** The long-poll came back with HTTP 200 and a task. */
    fun onPullTask(at: Long = System.currentTimeMillis(), httpStatus: Int = 200) =
        recordPullSuccess(at, httpStatus, empty = false)

    private fun recordPullSuccess(at: Long, httpStatus: Int, empty: Boolean) = mutate {
        pullLastPollStartedAt = pullLastPollStartedAt ?: at
        pullLastSuccessfulPollAt = at
        if (empty) pullLastEmptyPollAt = at else pullLastTaskReceivedAt = at
        pullCurrentRequestStartedAt = null
        pullLastHttpStatus = httpStatus
        pullConsecutiveFailures = 0
        pullLastFailure = null
        pullLastFailureDetail = null
        pullNextRetryAt = null
    }

    fun onPullFailure(
        kind: GatewayFailureKind,
        httpStatus: Int? = null,
        safeDetail: String? = null,
        retryInMs: Long? = null,
        at: Long = System.currentTimeMillis()
    ) = mutate {
        pullLastPollStartedAt = pullLastPollStartedAt ?: at
        pullCurrentRequestStartedAt = null
        pullConsecutiveFailures += 1
        pullLastHttpStatus = httpStatus
        pullLastFailure = kind
        pullLastFailureDetail = GatewayHealthText.safeDetail(safeDetail)
        pullNextRetryAt = retryInMs?.let { at + it }
    }

    /**
     * The result of a delivered task reached GMweb.
     *
     * This is ONE fact, so it is recorded in one place: the bridge's [lastAckAt] and the
     * queue's [EveQueueHealth.lastGatewayAckAt] are the same event seen from two angles, and
     * letting two call sites set them separately is how they end up disagreeing.
     */
    fun onAckSuccess(at: Long = System.currentTimeMillis()) = mutate {
        pullLastAckAt = at
        ackConsecutiveFailures = 0
        lastAckFailure = null
        lastAckFailureDetail = null
        eveQueue = eveQueue.copy(lastGatewayAckAt = at)
    }

    fun onAckFailure(
        kind: GatewayFailureKind,
        httpStatus: Int? = null,
        safeDetail: String? = null,
        at: Long = System.currentTimeMillis()
    ) = mutate {
        ackFailureCount += 1
        ackConsecutiveFailures += 1
        lastAckFailureAt = at
        lastAckFailure = kind
        lastAckFailureDetail = GatewayHealthText.safeDetail(safeDetail)
    }

    // ════════════════════════════════════════════════════════════════════════
    // Local send queue
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Replaces the QUEUE-OWNED fields from a fresh `EveSmsQueue.healthSnapshot()`.
     *
     * MERGES rather than replaces, and that is the whole point. `healthSnapshot()` deliberately
     * does not set [EveQueueHealth.lastGatewayAckAt] — the ACK leg is owned by whoever performed
     * it — but the poller calls this every ~25 seconds with a whole object. Assigning it
     * wholesale therefore ERASED the ack stamp that [onAckSuccess] had just written, which is
     * how the card came to show "Last result ACKed: 25s ago" on one row and "Last gateway ACK:
     * never" on another for the same event.
     *
     * So: the snapshot owns the counts and the queue's own timestamps; this object keeps
     * ownership of the gateway-ACK stamp.
     */
    fun setEveQueue(health: EveQueueHealth) = mutate {
        eveQueue = health.copy(lastGatewayAckAt = eveQueue.lastGatewayAckAt)
    }

    fun onEveLocalTransition(at: Long = System.currentTimeMillis()) = mutate {
        eveQueue = eveQueue.copy(lastLocalTransitionAt = at)
    }

    fun onEveNativeSubmit(at: Long = System.currentTimeMillis()) = mutate {
        eveQueue = eveQueue.copy(lastNativeSubmitAt = at)
    }

    // ════════════════════════════════════════════════════════════════════════
    // Reading
    // ════════════════════════════════════════════════════════════════════════

    /** The current dimensions with the verdict derived from them. Pure arithmetic. */
    fun snapshot(now: Long = System.currentTimeMillis()): GatewayHealthSnapshot =
        GatewayHealthRules.evaluate(rawSnapshot(now), now)

    /** The dimensions WITHOUT a verdict — for tests that assert on the raw record. */
    fun rawSnapshot(now: Long = System.currentTimeMillis()): GatewayHealthSnapshot =
        synchronized(lock) {
            GatewayHealthSnapshot(
                generatedAt = now,
                desired = desired,
                network = NetworkHealth(
                    validatedInternet = networkValidated,
                    transport = networkTransport,
                    observedAt = networkObservedAt
                ),
                endpoint = EndpointHealth(
                    configured = endpoint != null,
                    host = endpoint?.host,
                    port = endpoint?.port,
                    lastTcpConnectMs = tcpConnectMs,
                    lastProbeAt = probeAt,
                    dnsAddresses = dnsAddresses,
                    dnsCheckedAt = dnsCheckedAt
                ),
                tls = tls,
                authentication = authentication,
                eventUpload = EventUploadHealth(
                    running = uploaderRunning,
                    pending = uploadPending,
                    sending = uploadSending,
                    deadLetter = uploadDeadLetter,
                    lastAttemptAt = uploadLastAttemptAt,
                    lastSuccessAt = uploadLastSuccessAt,
                    lastHttpStatus = uploadLastHttpStatus,
                    lastFailure = uploadLastFailure,
                    lastFailureSafeDetail = uploadLastFailureDetail,
                    consecutiveFailures = uploadConsecutiveFailures,
                    lastFailureAt = uploadLastFailureAt
                ),
                pullBridge = PullBridgeHealth(
                    running = pollerRunning,
                    state = pollerState,
                    lastPollStartedAt = pullLastPollStartedAt,
                    lastSuccessfulPollAt = pullLastSuccessfulPollAt,
                    lastEmptyPollAt = pullLastEmptyPollAt,
                    lastTaskReceivedAt = pullLastTaskReceivedAt,
                    lastAckAt = pullLastAckAt,
                    consecutiveFailures = pullConsecutiveFailures,
                    lastHttpStatus = pullLastHttpStatus,
                    lastFailure = pullLastFailure,
                    lastFailureSafeDetail = pullLastFailureDetail,
                    currentRequestStartedAt = pullCurrentRequestStartedAt,
                    nextRetryAt = pullNextRetryAt,
                    ackFailures = ackFailureCount,
                    ackConsecutiveFailures = ackConsecutiveFailures,
                    lastAckFailureAt = lastAckFailureAt,
                    lastAckFailure = lastAckFailure,
                    lastAckFailureSafeDetail = lastAckFailureDetail
                ),
                eveQueue = eveQueue
            )
        }

    // ════════════════════════════════════════════════════════════════════════
    // Reconnect support
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Clears a failure that a reconnect is about to retry, so the card can show
     * RECONNECTING rather than a stale error.
     *
     * It does NOT clear [pullLastSuccessfulPollAt] and it does NOT set anything healthy: a
     * reconnect is a request to try again, not evidence that it worked. The card goes green
     * only when a poll actually succeeds.
     */
    fun onReconnectRequested(at: Long = System.currentTimeMillis()) = mutate {
        pullLastFailure = null
        pullLastFailureDetail = null
        pullLastHttpStatus = null
        pullNextRetryAt = null
        pullCurrentRequestStartedAt = null
        uploadLastFailure = null
        uploadLastFailureDetail = null
        uploadConsecutiveFailures = 0
    }

    /** Test seam. Never call from production code. */
    internal fun resetForTest() {
        synchronized(lock) {
            desired = false
            endpoint = null
            networkValidated = false
            networkTransport = "None"
            networkObservedAt = 0L
            tcpConnectMs = null
            probeAt = null
            tls = TlsHealth()
            authentication = AuthHealth()
            uploaderRunning = false
            uploadPending = 0
            uploadSending = 0
            uploadDeadLetter = 0
            uploadLastAttemptAt = null
            uploadLastSuccessAt = null
            uploadLastHttpStatus = null
            uploadLastFailure = null
            uploadLastFailureDetail = null
            uploadConsecutiveFailures = 0
            uploadLastFailureAt = null
            pollerRunning = false
            pollerState = "IDLE"
            pullLastPollStartedAt = null
            pullLastSuccessfulPollAt = null
            pullLastEmptyPollAt = null
            pullLastTaskReceivedAt = null
            pullLastAckAt = null
            pullConsecutiveFailures = 0
            pullLastHttpStatus = null
            pullLastFailure = null
            pullLastFailureDetail = null
            pullCurrentRequestStartedAt = null
            pullNextRetryAt = null
            ackFailureCount = 0
            ackConsecutiveFailures = 0
            lastAckFailureAt = null
            lastAckFailure = null
            lastAckFailureDetail = null
            eveQueue = EveQueueHealth()
        }
        _revision.value = 0L
    }

    private inline fun mutate(block: () -> Unit) {
        synchronized(lock) { block() }
        _revision.value = _revision.value + 1L
    }
}

/**
 * A pull that produced a response the bridge cannot use, carrying the fact the health model
 * needs.
 *
 * `IllegalStateException("pull HTTP 401")` used to be thrown here, which lost the status
 * into a string: the user saw "Pull failed: pull HTTP 401", and nothing downstream could
 * tell a rejected key from a timeout. This type keeps the status a first-class value and is
 * still an [java.io.IOException], so the poller's existing backoff path is unchanged.
 */
class GatewayPullFailure(
    val kind: GatewayFailureKind,
    val httpStatus: Int?,
    val safeDetail: String?
) : java.io.IOException(safeDetail ?: kind.name)
