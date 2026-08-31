package com.autonomousone.messages.gateway

import android.content.Context
import android.util.Log
import com.autonomousone.messages.data.GatewayEventFactory
import com.autonomousone.messages.data.GatewayEventOutboxEntity
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.repository.GatewaySyncRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
 */
class EventUploader(
    context: Context,
    private val prefs: GatewayPreferences,
    private val client: BackendClient,
    private val scope: CoroutineScope,
    private val onLog: (String) -> Unit
) {
    companion object {
        private const val TAG = "EVENT_UPLOADER"
        private const val EVENTS_PATH = "/api/v1/agent/events/batch"
    }

    private val appContext = context.applicationContext
    private val repo = GatewaySyncRepository(MessagesDatabase.get(appContext))
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            // Process-death recovery FIRST: a crash between claim and upload
            // leaves SENDING rows behind — requeue them before claiming.
            val recovered = repo.recoverSending()
            if (recovered > 0) onLog("📤 Outbox recovery: $recovered in-flight event(s) requeued")

            var attempt = 0
            while (isActive) {
                if (!prefs.isEnabled || prefs.gmwebUrl.isBlank()) {
                    // Runtime gate (same semantics as the poller): zero HTTP
                    // while the supervisor has not declared us enabled.
                    delay(5_000)
                    continue
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
                    delay(2_000) // quiet drain cadence; durability ≠ latency here
                    continue
                }
                when (uploadBatch(claimed)) {
                    Outcome.ALL_ACKED -> attempt = 0
                    Outcome.PARTIAL -> attempt = 0 // un-ACKed rows retry on their own backoff
                    Outcome.TRANSPORT_FAILURE -> {
                        attempt = min(attempt + 1, 20)
                        delay(GatewaySyncRepository.Policy.backoffDelayMs(attempt, Random.Default))
                    }
                    Outcome.FATAL -> attempt = 0 // batch dead-lettered and visible
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
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
        for (event in batch) {
            // Envelope self-check: an undecodable/corrupt payload can never
            // succeed — DEAD_LETTER this row only (health alert surface) and
            // keep the rest of the batch. NOTE: we do NOT decode-and-rebuild —
            // the envelope bytes go on the wire verbatim (base64); the server
            // treats them as opaque (Rule 6, ADR-002). The decode here is a
            // cheap integrity gate only.
            try {
                GatewayEventFactory.decodePayloadEnvelope(event.ciphertext)
            } catch (e: Exception) {
                Log.e(TAG, "payload envelope decode failed for ${event.eventUuid}", e)
                repo.onDeadLetter(event.eventUuid)
                continue
            }
            events.put(
                JSONObject()
                    .put("eventId", event.eventUuid)
                    .put("type", event.eventType)
                    .put("conversationId", event.aggregateId)
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
        if (events.length() == 0) return Outcome.FATAL

        val requeue: suspend (List<GatewayEventOutboxEntity>) -> Unit = { rows ->
            rows.forEach { repo.onRetry(it.eventUuid, it.attemptCount, Random.Default, now) }
        }

        return when (val result = client.post(EVENTS_PATH, JSONObject().put("events", events))) {
            is BackendClient.Result.Success -> {
                val accepted = runCatching {
                    JSONObject(result.data).optJSONArray("accepted") ?: JSONArray()
                }.getOrDefault(JSONArray())
                val ackedUuids = HashSet<String>(accepted.length())
                for (i in 0 until accepted.length()) {
                    val a = accepted.optJSONObject(i) ?: continue
                    val eventId = a.optString("eventId")
                    if (eventId.isNotEmpty()) ackedUuids.add(eventId)
                }
                var acked = 0
                for (event in batch) {
                    if (event.eventUuid in ackedUuids) {
                        val rows = repo.onAcked(event.eventUuid, 0, now)
                        if (rows > 0) acked++
                    } else {
                        repo.onRetry(event.eventUuid, event.attemptCount, Random.Default, now)
                    }
                }
                if (acked > 0) onLog("📤 $acked/${batch.size} event(s) ACKed by GMweb")
                if (acked == batch.size) Outcome.ALL_ACKED
                else Outcome.PARTIAL
            }
            is BackendClient.Result.Failure -> {
                val status = result.httpStatus
                if (status != null && status in 400..499 && status != 429) {
                    // Permanent schema/auth reject: LOCK 13 — DEAD_LETTER +
                    // visible health signal, never a silent drop.
                    batch.forEach { repo.onDeadLetter(it.eventUuid) }
                    onLog("⛔ ${batch.size} event(s) dead-lettered: HTTP $status")
                    Outcome.FATAL
                } else {
                    requeue(batch)
                    Outcome.TRANSPORT_FAILURE
                }
            }
        }
    }
}
