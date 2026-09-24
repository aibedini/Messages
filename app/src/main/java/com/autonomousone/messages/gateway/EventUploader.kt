package com.autonomousone.messages.gateway

import android.content.Context
import android.util.Log
import com.autonomousone.messages.BuildConfig
import com.autonomousone.messages.data.GatewayEventFactory
import com.autonomousone.messages.data.GatewayEventOutboxEntity
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.gateway.health.GatewayFailureKind
import com.autonomousone.messages.gateway.health.GatewayHealthRecorder
import com.autonomousone.messages.gateway.health.GatewayHealthText
import com.autonomousone.messages.gateway.health.GatewayLog
import com.autonomousone.messages.repository.GatewaySyncRepository
import com.autonomousone.messages.sync.BatchAckParser
import com.autonomousone.messages.sync.OutboxRetryPolicy
import com.autonomousone.messages.sync.ReplicationBlocker
import com.autonomousone.messages.sync.ReplicationUploadGate
import com.autonomousone.messages.sync.SyncErrorCode
import com.autonomousone.messages.sync.UploadGateInputs
import com.autonomousone.messages.sync.diagnostics.SyncDiagnosticsText
import com.autonomousone.messages.utils.DiagnosticLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min
import kotlin.random.Random

/**
 * PR-02: the durable outbox worker (TechSpec §11/§55, LOCK 13).
 *
 * Replaces the WebhookEngine cloud path (deleted): cloud events are COMMITTED
 * to `gateway_event_outbox` in the same Room transaction that lands the
 * message, and THIS loop is the only transmitter. Correctness lives in the
 * queue, never on the wire:
 *
 *   claimBatch (transactional → SENDING) → POST /api/v1/agent/events/batch
 *   → per-eventUuid partial ACK (accepted[] → ACKED + serverSequence)
 *   → transport failure → PENDING + full-jitter backoff (LOCK 13)
 *   → permanent reject / undecodable payload → DEAD_LETTER (never dropped silently)
 *
 * Process death between claim and ACK leaves rows SENDING; the first act of
 * [start] is recoverSending() → PENDING (PR-01 contract). No RAM-only state.
 *
 * The hold decision is NOT made here: it comes from
 * [com.autonomousone.messages.sync.ReplicationUploadGate], which derives it from the single
 * readiness evaluator, so the reason is a named blocker instead of a private enum, and it is
 * written to the durable diagnostic log rather than only to a RAM field.
 */
class EventUploader(
    context: Context,
    private val prefs: GatewayPreferences,
    // P0 (control-plane SSOT): events go to GMweb (gmwebUrl), never the
    // legacy cloud backendUrl.
    private val client: ControlPlaneClient,
    private val scope: CoroutineScope,
    private val onLog: (String) -> Unit
) {
    companion object {
        private const val TAG = "EVENT_UPLOADER"
        private const val EVENTS_PATH = "/api/v1/agent/events/batch"

        /** How often the loop sweeps for expired leases. */
        private const val LEASE_SWEEP_INTERVAL_MS = 60_000L
        private val _running = MutableStateFlow(false)
        val running = _running.asStateFlow()
    }

    private val appContext = context.applicationContext
    private val database = MessagesDatabase.get(appContext)
    private val repo = GatewaySyncRepository(database)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val outboxObserver = object : androidx.room.InvalidationTracker.Observer("gateway_event_outbox") {
        override fun onInvalidated(tables: Set<String>) { wake.trySend(Unit) }
    }
    private var job: Job? = null
    /**
     * The blocker currently holding the loop, so the pause is logged once per state change
     * instead of every 30 seconds. The REASON is also written to the durable diagnostic log, so
     * it survives process death — the previous RAM-only `lastGate` field did not.
     */
    private var lastHold: ReplicationBlocker? = null

    fun start() {
        if (job != null) return
        database.invalidationTracker.addObserver(outboxObserver)
        _running.value = true
        GatewayHealthRecorder.setUploaderRunning(true)
        val launched = scope.launch {
            // Process-death recovery FIRST: a crash between claim and upload leaves leased rows
            // behind. AGE-BOUNDED (mission §12): only leases older than the timeout are returned,
            // so a row another claimant is actively uploading is never stolen.
            val recovered = repo.recoverStaleLeases(System.currentTimeMillis())
            if (recovered > 0) onLog("📤 Outbox recovery: $recovered stale in-flight event(s) requeued")

            // v3 was briefly emitted before the matching server contract was
            // live. Requeue that rollout cohort once; normal retries take over.
            if (!prefs.cryptoV3DeadLettersRecovered) {
                val rescued = repo.recoverCryptoDeadLetter(minCryptoVersion = 2)
                prefs.cryptoV3DeadLettersRecovered = true
                if (rescued > 0) {
                    onLog("♻️ Outbox recovery: $rescued v2/v3 event(s) requeued")
                    Log.i(TAG, "requeued $rescued v2/v3 DEAD_LETTER rows")
                }
            }

            var attempt = 0
            var lastLeaseSweepAt = 0L
            while (isActive) {
                // Periodic lease recovery (mission §12). Startup-only recovery would leave a row
                // stranded for the whole life of a long-running process, which is the stall the
                // audit found. Throttled so it is not a database write on every iteration.
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastLeaseSweepAt >= LEASE_SWEEP_INTERVAL_MS) {
                    lastLeaseSweepAt = nowMs
                    runCatching { repo.recoverStaleLeases(nowMs) }
                        .onSuccess { if (it > 0) onLog("📤 Outbox recovery: $it stale lease(s) requeued") }
                        .onFailure { Log.w(TAG, "lease sweep failed", it) }
                }
                val hold = ReplicationUploadGate.hold(
                    UploadGateInputs(
                        // USER intent, never the supervisor's derived transmission gate: passing
                        // that would report an offline device as "the gateway is switched off".
                        gatewayDesired = prefs.gatewayDesiredEnabled,
                        consentGranted = prefs.hasGatewayConsent,
                        serverOriginConfigured = prefs.gmwebServerOrigin.isNotBlank(),
                        identityRegistered = prefs.identityRegistered,
                        supervisorOffline = GatewayService.supervisorState ==
                            ConnectionSupervisor.State.WAITING_FOR_NETWORK,
                        authenticationRejected =
                            GatewayHealthRecorder.rawSnapshot().authentication.rejected
                    )
                )
                if (hold != null) {
                    // Log loudly on every state CHANGE, then wait in the background — never spam
                    // at 30 s cadence.
                    if (lastHold != hold) {
                        lastHold = hold
                        val code = SyncDiagnosticsText.codeName(hold)
                        Log.w(TAG, "UPLOAD_BLOCKED: $code")
                        onLog("⛔ Event upload paused: $code")
                        // Durable, so the cause outlives the process that observed it.
                        DiagnosticLog.event("SYNC_BLOCKED", "scope=upload code=$code")
                    }
                    delay(30_000)
                    continue
                }
                val previouslyHeld = lastHold
                if (previouslyHeld != null) {
                    val code = SyncDiagnosticsText.codeName(previouslyHeld)
                    Log.i(TAG, "upload gate cleared: $code → ENABLED")
                    onLog("📤 Event upload resumed")
                    DiagnosticLog.event("SYNC_UNBLOCKED", "scope=upload was=$code")
                    lastHold = null
                }
                // Key-grant drain is best-effort and MUST NOT block the event
                // upload: a failure here (DB lock/corruption) would otherwise
                // silently freeze the whole outbox (Issue 4).
                try {
                    com.autonomousone.messages.security.ConversationKeyRepository(MessagesDatabase.get(appContext))
                        .drainHistoryGrants()
                } catch (e: Exception) {
                    Log.e(TAG, "drainHistoryGrants failed, continuing with upload", e)
                }
                val claimed = try {
                    repo.claimBatch(System.currentTimeMillis())
                } catch (e: Exception) {
                    Log.e(TAG, "outbox claim failed", e)
                    delay(5_000)
                    continue
                }
                if (claimed.isEmpty()) {
                    attempt = 0
                    publishQueueDepth()
                    withTimeoutOrNull(2_000) { wake.receive() }
                    continue
                }
                publishQueueDepth()
                when (uploadBatch(claimed)) {
                    Outcome.ALL_ACKED -> {
                        attempt = 0
                        if (claimed.any { it.priority == GatewayEventOutboxEntity.PRIORITY_BACKFILL }) {
                            com.autonomousone.messages.data.TelephonySyncCoordinator.get(appContext)
                                .requestCloudBackfillForLinkedDevice("outbox-drain")
                        }
                    }
                    Outcome.PARTIAL -> attempt = 0 // un-ACKed rows retry on their own backoff
                    Outcome.TRANSPORT_FAILURE -> {
                        attempt = min(attempt + 1, 20)
                        delay(GatewaySyncRepository.Policy.backoffDelayMs(attempt, Random.Default))
                    }
                    Outcome.FATAL -> attempt = 0 // batch dead-lettered and visible
                }
            }
        }
        job = launched
        launched.invokeOnCompletion {
            database.invalidationTracker.removeObserver(outboxObserver)
            if (job === launched) job = null
            _running.value = false
            GatewayHealthRecorder.setUploaderRunning(false)
        }
    }

    fun stop() {
        job?.cancel()
        _running.value = false
        GatewayHealthRecorder.setUploaderRunning(false)
    }

    /**
     * Try again immediately: wake the idle wait so a server change (or a manual reconnect)
     * reaches the OUTBOUND sync without waiting for the next invalidation.
     *
     * Does NOT start a second uploader — [start] is idempotent and this only nudges the loop.
     */
    fun retryNow() {
        wake.trySend(Unit)
    }

    /**
     * Publishes the outbox depth to the health registry.
     *
     * Best-effort: a health read must never be the reason an upload loop stalls, so a
     * failure here is swallowed. `pendingDepth()` counts PENDING and SENDING together, so
     * the two are separated here — a row claimed for minutes is a STUCK upload and the card
     * has to be able to say so.
     */
    private suspend fun publishQueueDepth() {
        try {
            val sending = repo.sendingDepth()
            val pending = (repo.pendingDepth() - sending).coerceAtLeast(0)
            GatewayHealthRecorder.setUploadQueue(
                pending = pending,
                sending = sending,
                deadLetter = repo.deadLetterDepth()
            )
        } catch (e: Exception) {
            Log.w(TAG, "queue depth unavailable for health", e)
        }
    }

    private enum class Outcome { ALL_ACKED, PARTIAL, TRANSPORT_FAILURE, FATAL }

    /**
     * One batch POST (TechSpec §55). Response:
     * `{"accepted":[{"eventId":"…","serverSequence":58193}, …]}`
     * Events missing from `accepted` go back to PENDING with their own
     * attempt-counted backoff (partial ACK, LOCK 13).
     */
    private suspend fun uploadBatch(batch: List<GatewayEventOutboxEntity>): Outcome {
        val now = System.currentTimeMillis()
        val events = JSONArray()
        val submitted = mutableListOf<GatewayEventOutboxEntity>()
        for (event in batch) {
            // Envelope self-check: an undecodable/corrupt payload can never
            // succeed — DEAD_LETTER this row only (health alert surface) and
            // keep the rest of the batch. NOTE: we do NOT decode-and-rebuild —
            // the envelope bytes go on the wire verbatim (base64); the server
            // treats them as opaque (Rule 6, ADR-002). The decode here is a
            // transport metadata gate only for encrypted versions. Legacy v0
            // is additionally checked with its JSON decoder.
            try {
                GatewayEventFactory.validateForTransport(event)
            } catch (e: Exception) {
                Log.e(TAG, "SECURITY_EVENT_REJECTED eventId=${event.eventUuid} type=${event.eventType}")
                repo.onDeadLetter(
                    eventUuid = event.eventUuid,
                    failureCategory = GatewayFailureKind.VALIDATION_FAILED.name,
                    httpStatus = null,
                    at = now,
                    appVersion = BuildConfig.APP_VERSION,
                    errorCode = SyncErrorCode.INVALID_EVENT_SCHEMA.name,
                    errorMessage = GatewayHealthText.safeDetail(e.message)
                )
                continue
            }
            submitted += event
            events.put(
                JSONObject()
                    .put("eventId", event.eventUuid)
                    .put("type", event.eventType)
                    .put("conversationId", event.aggregateId)
                    .put("messageId", event.messageId.takeIf { it.isNotBlank() })
                    .put("revision", event.revision)
                    .put("sortKey", event.sortKey)
                    .put("encoding", event.encoding)
                    .put("schemaVersion", event.schemaVersion)
                    .put("cryptoVersion", event.cryptoVersion)
                    // Wire contract (GMweb /api/v1/agent/events/batch): payload is
                    // a base64 STRING of the opaque envelope bytes — the server
                    // never parses message content (Rule 1/6, ADR-002).
                    .put(
                        "payload",
                        android.util.Base64.encodeToString(
                            event.ciphertext,
                            android.util.Base64.NO_WRAP
                        )
                    )
            )
        }
        if (submitted.isEmpty()) return Outcome.ALL_ACKED

        // PR-11: per-device X-Agent-Auth signature (GMweb requires it once the
        // deviceId has enrolled). The deviceId bound in the signature MUST be
        // the same one identity enrollment keyed on (SSOT stableDeviceId).
        val deviceId = prefs.agentDeviceId(appContext)
        val sign: (java.net.HttpURLConnection, ByteArray) -> Boolean = { conn, bodyBytes ->
            AgentAuth.sign(conn, deviceId, EVENTS_PATH, "POST", bodyBytes)
        }

        Log.i(TAG, "batch_upload_attempt events=${events.length()} sourceDeviceId=$deviceId")
        GatewayHealthRecorder.onUploadAttempt(now)
        // Captured BEFORE the attempt so the success branch can tell a recovery from an
        // ordinary upload without a second registry read.
        val wasFailing = GatewayHealthRecorder.rawSnapshot(now).eventUpload.lastFailure != null

        return when (val result = client.post(EVENTS_PATH, JSONObject().put("events", events), signer = sign)) {
            is ControlPlaneClient.Result.Success -> {
                val parsed = BatchAckParser.parse(result.data)
                val acknowledged = parsed.acknowledged
                var acked = 0
                for (event in submitted) {
                    val sequence = acknowledged[event.eventUuid]
                    if (sequence != null) {
                        // ACCEPTED and DUPLICATE both mean the server durably has the event
                        // (mission §16). A missing serverSequence is no longer a reason to
                        // withhold the ACK — that was the loop that never converged.
                        val rows = repo.onAcked(
                            eventUuid = event.eventUuid,
                            serverSequence = sequence,
                            ackedAt = now,
                            httpStatus = result.httpStatus
                        )
                        if (rows > 0) acked++
                        continue
                    }
                    val rejected = parsed.rejected[event.eventUuid]
                    if (rejected != null) {
                        // The server named this event as permanently unacceptable, so only THIS
                        // row dies — the rest of the batch is untouched (mission §17/§18).
                        repo.onDeadLetter(
                            eventUuid = event.eventUuid,
                            failureCategory = GatewayFailureKind.VALIDATION_FAILED.name,
                            httpStatus = result.httpStatus,
                            at = now,
                            appVersion = BuildConfig.APP_VERSION,
                            errorCode = rejected.errorCode
                                ?: SyncErrorCode.INVALID_EVENT_SCHEMA.name,
                            errorMessage = rejected.errorCode
                        )
                        continue
                    }
                    // Unreported, or explicitly retryable: keep it, with the reason.
                    val item = parsed.retryable[event.eventUuid]
                    repo.onRetry(
                        eventUuid = event.eventUuid,
                        attempt = event.attemptCount,
                        random = Random.Default,
                        now = now,
                        httpStatus = result.httpStatus,
                        errorCode = SyncErrorCode.UNKNOWN.name,
                        errorMessage = item?.errorCode
                    )
                }
                val duplicates = parsed.duplicates
                val failed = submitted.size - acked
                // The technical form stays in the ADVANCED log…
                Log.i(
                    TAG,
                    "batch_upload_result events=${submitted.size} accepted=$acked duplicates=$duplicates failed=$failed"
                )
                GatewayHealthRecorder.onUploadSuccess(
                    at = System.currentTimeMillis(),
                    httpStatus = result.httpStatus
                )
                publishQueueDepth()
                // Durable transitions only: a successful batch happens constantly on an
                // active device, so only a RECOVERY from a recorded failure is worth a
                // diagnostic line. Together with the failures below and the pull bridge's
                // own lines, that is a complete story without a redundant entry.
                if (wasFailing) {
                    DiagnosticLog.event(
                        "GATEWAY_UPLOAD",
                        "recovered status=${result.httpStatus} accepted=$acked duplicates=$duplicates"
                    )
                }
                // …and the NORMAL feed says what happened in the user's terms. A raw
                // "2/2 event(s) ACKed by GMweb" is technically correct and completely
                // unhelpful: it reads like a delivery confirmation for a message, when it
                // is only the outbound sync of this device's own state.
                if (acked > 0) {
                    onLog(
                        "☁️ Sync uploaded $acked event" + (if (acked == 1) "" else "s") +
                            if (failed > 0) " · $failed still pending" else ""
                    )
                }
                GatewayLog.syncUploaded(
                    accepted = acked,
                    duplicates = duplicates,
                    failed = failed,
                    httpStatus = result.httpStatus
                )
                if (acked == submitted.size) Outcome.ALL_ACKED
                else Outcome.PARTIAL
            }
            is ControlPlaneClient.Result.Failure -> {
                val status = result.httpStatus
                Log.e(TAG, "batch_upload_http_error status=${status ?: "n/a"} reason=${result.error}")
                GatewayHealthRecorder.onUploadFailure(
                    kind = GatewayFailureKind.classify(httpStatus = status),
                    httpStatus = status,
                    safeDetail = result.error
                )
                // One durable line per upload failure: this is the OUTBOUND sync, and the
                // whole point of the health work is that it must never be read as proof
                // that the INBOUND delivery bridge works.
                DiagnosticLog.event(
                    "GATEWAY_UPLOAD",
                    "failed status=${status ?: "n/a"} events=${submitted.size} " +
                        "kind=${GatewayFailureKind.classify(httpStatus = status).name} " +
                        "detail=${GatewayHealthText.safeDetail(result.error) ?: "none"}"
                )
                GatewayLog.syncFailed(
                    httpStatus = status,
                    kind = GatewayFailureKind.classify(httpStatus = status),
                    safeDetail = result.error,
                    count = submitted.size
                )
                val kind = GatewayFailureKind.classify(httpStatus = status)
                val code = OutboxRetryPolicy.codeFor(status, kind)
                val safeDetail = GatewayHealthText.safeDetail(result.error)

                // Which failures may kill a row at all (mission §17). The rule lives in
                // OutboxRetryPolicy so every status the mission names is asserted by a test
                // rather than read out of a boolean here.
                val action = OutboxRetryPolicy.actionFor(status, kind)

                if (action == OutboxRetryPolicy.Action.DEAD_LETTER) {
                    // A contract failure the server will repeat: this batch cannot succeed as
                    // sent. Every row is retained and visible as DEAD_LETTER with the server's
                    // own status and code, never silently dropped.
                    submitted.forEach {
                        repo.onDeadLetter(
                            eventUuid = it.eventUuid,
                            failureCategory = kind.name,
                            httpStatus = status,
                            at = System.currentTimeMillis(),
                            appVersion = BuildConfig.APP_VERSION,
                            errorCode = code.name,
                            errorMessage = safeDetail
                        )
                    }
                    onLog("⛔ ${submitted.size} event(s) dead-lettered: HTTP $status")
                    Outcome.FATAL
                } else {
                    // Retryable — including auth, payload-too-large, conflict and rate limiting.
                    // Keep the rows and record why on each one.
                    val nowMs = System.currentTimeMillis()
                    // A server-supplied Retry-After is honoured verbatim when present (mission
                    // §17): second-guessing a rate limit with our own backoff is how a device
                    // gets throttled harder.
                    val retryAfterMs = result.retryAfterMs
                    submitted.forEach {
                        if (OutboxRetryPolicy.exhausted(it.attemptCount)) {
                            // Bounded retries: a row that has tried long enough becomes visible
                            // instead of looping for the life of the install. Nothing is lost —
                            // a dead-lettered row is retained and rescuable.
                            repo.onDeadLetter(
                                eventUuid = it.eventUuid,
                                failureCategory = kind.name,
                                httpStatus = status,
                                at = nowMs,
                                appVersion = BuildConfig.APP_VERSION,
                                errorCode = code.name,
                                errorMessage = safeDetail
                            )
                        } else {
                            repo.onRetry(
                                eventUuid = it.eventUuid,
                                attempt = it.attemptCount,
                                random = Random.Default,
                                now = nowMs,
                                httpStatus = status,
                                errorCode = code.name,
                                errorMessage = safeDetail,
                                retryAfterMs = retryAfterMs
                            )
                        }
                    }
                    Outcome.TRANSPORT_FAILURE
                }
            }
        }
    }
}
