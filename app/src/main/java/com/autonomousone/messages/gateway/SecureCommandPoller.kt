package com.autonomousone.messages.gateway

import android.content.Context
import android.util.Log
import com.autonomousone.messages.BuildConfig
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.data.RemoteCommandEntity
import com.autonomousone.messages.repository.GatewaySyncRepository
import com.autonomousone.messages.sms.GatewayOutgoingPipeline
import com.autonomousone.messages.sync.CommandDrainPolicy
import com.autonomousone.messages.sync.CommandRoute
import com.autonomousone.messages.sync.CommandRouting
import com.autonomousone.messages.sync.ReadCommandError
import com.autonomousone.messages.sync.ReadCommandFailure
import com.autonomousone.messages.sync.SyncErrorCode
import com.autonomousone.messages.utils.DiagnosticLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * PR-10 (TechSpec §40/§57/§58, LOCK 4): the strategic command transport.
 *
 * Outbound-only HTTPS long-poll against GMweb's /api/v1/agent/commands/claim.
 * Correctness comes from the DURABLE remote_commands queue (PR-01 tables),
 * never from the connection:
 *
 *   claim  → atomically QUEUED→DELIVERED_TO_AGENT server-side
 *   ingest → INSERT OR IGNORE by idempotencyKey (exactly-once; redelivery
 *            surfaces the existing row and is dropped here — §71)
 *   ACK    → guarded lifecycle ACCEPTED→EXECUTING→COMPLETED/FAILED (§58)
 *
 * The legacy [OutboxPoller] (/gateway/pull + EveSmsQueue) stays untouched and
 * remains the compatibility transport while the executor (Phase 3) learns to
 * drain remote_commands; until then this poller ingests and reports but hands
 * SEND_SMS rows to [GatewayOutgoingPipeline] which honours the same flag.
 *
 * Availability contract (ADR-003): this loop runs while
 * [ConnectionSupervisor] declares the gateway CONNECTED; <3s pickup SLO
 * applies only when the app process is alive (AGENT_AVAILABLE).
 */
class SecureCommandPoller(
    private val context: Context,
    private val prefs: GatewayPreferences,
    private val scope: CoroutineScope,
    private val onLog: (String) -> Unit = {},
) {
    companion object {
        private const val TAG = "CMD_POLLER"
        private const val CLAIM_PATH = "/api/v1/agent/commands/claim"
        private const val STATUS_PATH = "/api/v1/agent/commands/%s/status"
        private const val QUIET_MS = 2_000L        // empty-claim cadence (§57)
        private const val ERROR_RETRY_MS = 5_000L
        private const val CLAIM_LIMIT = 25

        /**
         * How often the drain pass runs (mission §46).
         *
         * Not every loop iteration: the quiet cadence is 2s, and a `nonTerminalCommands` query every
         * 2s would be pure overhead on a healthy device. One minute bounds how long a command
         * stranded by a previous process can stay unresolved while still costing nothing in steady
         * state. `lastDrainAt` starts at 0, so the FIRST iteration after a start always drains —
         * which is precisely the crash-recovery case this exists for.
         */
        private const val DRAIN_INTERVAL_MS = 60_000L

        /** How many non-terminal rows one drain pass looks at. */
        private const val DRAIN_BATCH = 50

        /** The encrypted command types this build can execute. Advertised in telemetry capabilities. */
        private val COMMAND_TYPES = listOf(
            SEND_SMS,
            ReadCommandError.READ_COMMAND_TYPE,
            REFRESH_DEVICE_TELEMETRY,
        )

        internal val SUPPORTED_TYPES = COMMAND_TYPES.toSet()

        /** Ask the phone for a fresh device/SIM telemetry report (GMweb → Android). */
        const val REFRESH_DEVICE_TELEMETRY = "REFRESH_DEVICE_TELEMETRY"

        /** Classic SMS send. The only type that may enter the SMS pipeline. */
        const val SEND_SMS = "SEND_SMS"

        /** Result code for a command this build does not implement. */
        const val RESULT_UNSUPPORTED_COMMAND_TYPE = "UNSUPPORTED_COMMAND_TYPE"

        /** The only `reason` this build accepts for a telemetry refresh. */
        const val REASON_SIM_REFRESH = "SIM_REFRESH"

        /** How long a web-requested refresh may wait for its POST before reporting a timeout. */
        internal const val REMOTE_REFRESH_TIMEOUT_MS = 10_000L

        internal fun buildClaimBody(context: Context, deviceId: String): ByteArray {
            val runtime = installedRuntime(context)
            return buildClaimBody(deviceId, runtime.versionName, runtime.versionCode)
        }

        /** The installed APK's own identity, straight from PackageManager. */
        internal data class InstalledRuntime(val versionName: String, val versionCode: Long)

        internal fun installedRuntime(context: Context): InstalledRuntime {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            @Suppress("DEPRECATION")
            val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                packageInfo.versionCode.toLong()
            }
            return InstalledRuntime(packageInfo.versionName ?: "", versionCode)
        }
        /** Compatibility helper for JVM contract tests; production never uses this overload. */
        internal fun buildClaimBody(deviceId: String): ByteArray =
            buildClaimBody(deviceId, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong())

        /** JVM-test helper; production claims always use [buildClaimBody] with a Context. */
        internal fun buildClaimBody(
            deviceId: String,
            versionName: String,
            versionCode: Long,
        ): ByteArray = JSONObject()
            .put("agentId", deviceId)
            .put("limit", CLAIM_LIMIT)
            .put(
                "runtime",
                JSONObject()
                    .put("protocolVersion", 1)
                    .put("appVersionName", versionName)
                    .put("appVersionCode", versionCode)
                    .put("commandTypes", JSONArray(COMMAND_TYPES))
            )
            .toString()
            .toByteArray(Charsets.UTF_8)

        internal fun decodeCiphertext(command: JSONObject): ByteArray? {
            val encoded = command.optString("ciphertext")
            if (encoded.isBlank()) return null
            return runCatching {
                java.util.Base64.getDecoder().decode(encoded)
            }.getOrNull()?.takeIf { it.isNotEmpty() }
        }

        /**
         * Map one claim-response row onto the durable command row (mission §49).
         *
         * Extracted from `claim()` and made pure so the field mapping is testable without a server:
         * an inlined mapping is exactly where a field silently stops being carried, and one already
         * had. `clientMessageId` was declared on the entity, read back by the executor, and **never
         * populated at intake** — so on the strategic path it was always null and the web's
         * optimistic-bubble key could not survive a round trip at all.
         *
         * [decodeSignature] is a parameter because the signature decode is the only Android-specific
         * step; the test supplies its own so the rest of the mapping is exercised for real.
         *
         * @param ciphertext already decoded by the caller, so an unusable payload is reported once.
         * @return null when the row cannot be identified (no id, or no idempotency key) — an
         *   unidentifiable command must not be ingested under a made-up identity.
         */
        internal fun commandFromEnvelope(
            command: JSONObject,
            ciphertext: ByteArray,
            now: Long,
            decodeSignature: (String) -> ByteArray = { encoded ->
                runCatching {
                    android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
                }.getOrDefault(ByteArray(0))
            },
        ): RemoteCommandEntity? {
            val id = command.optString("id")
            val idempotencyKey = command.optString("idempotencyKey")
            if (id.isBlank() || idempotencyKey.isBlank()) return null
            return RemoteCommandEntity(
                commandId = id,
                type = command.optString("type", "UNKNOWN"),
                ciphertext = ciphertext,
                encoding = command.optString("encoding", "application/json"),
                schemaVersion = command.optInt("schemaVersion", 1),
                cryptoVersion = command.optInt("cryptoVersion", 0),
                signature = decodeSignature(command.optString("clientSignature", "")),
                issuedAt = command.optLong("createdAt", now),
                receivedAt = now,
                expiresAt = command.optLong("expiresAt", 0L),
                idempotencyKey = idempotencyKey,
                state = RemoteCommandEntity.STATE_RECEIVED,
                clientMessageId = clientMessageIdOf(command),
            )
        }

        /**
         * The web's optimistic-bubble key, as the claim response declares it.
         *
         * Blank is normalised to null rather than stored: an empty string is not an identity, and
         * once persisted it is indistinguishable from a real key that happens to be empty. The same
         * normalisation the executor applies, so the two cannot disagree.
         */
        internal fun clientMessageIdOf(command: JSONObject): String? =
            command.optString("clientMessageId").takeIf { it.isNotBlank() }
    }

    enum class State { IDLE, POLLING, INGESTING, ERROR }

    private val repo = GatewaySyncRepository(MessagesDatabase.get(context.applicationContext))
    private var job: Job? = null
    private val _stateFlow = MutableStateFlow(State.IDLE)
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    /**
     * Interrupts whatever wait the loop is sitting on.
     *
     * Conflated, because the only thing a wake-up can mean is "poll now": five nudges are one poll.
     * Used by the supervisor when the network comes back — the 2-second quiet cadence and the
     * 5-second error backoff are both *waits*, and waiting them out after connectivity has returned
     * is exactly the delay the reconnect path exists to remove.
     */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** Poll immediately instead of waiting out the current quiet/backoff interval. */
    fun retryNow() {
        wake.trySend(Unit)
    }

    private suspend fun waitOrWake(ms: Long) {
        kotlinx.coroutines.withTimeoutOrNull(ms) { wake.receive() }
    }

    /** Stable device id — the SAME one sent at registration (PR-05/08b). */
    private fun deviceId(): String = prefs.gatewayId.ifBlank {
        android.provider.Settings.Secure.getString(
            context.contentResolver, android.provider.Settings.Secure.ANDROID_ID
        ) ?: "unknown-device"
    }

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                // The SAME consent gate the legacy poller uses. This poller previously checked only
                // `isEnabled` and the URL, so revoking gateway consent stopped the pull bridge but
                // NOT the strategic command channel: a consent-revoked device would still claim and
                // execute remote commands (docs/gateway-replication-audit.md, Blocker 16).
                //
                // `GatewayAccessPolicy.canTransmit` is the single rule for "may this device talk to
                // the control plane"; re-deriving it here is how the clause went missing.
                if (!GatewayAccessPolicy.canTransmit(prefs.hasGatewayConsent, prefs.isEnabled) ||
                    prefs.gmwebServerOrigin.isBlank()
                ) {
                    // Same runtime gate as EventUploader: the supervisor owns
                    // enablement; zero HTTP until CONNECTED.
                    _stateFlow.value = State.IDLE
                    waitOrWake(5_000)
                    continue
                }
                _stateFlow.value = State.POLLING
                try {
                    // Recovery BEFORE intake: a command stranded by a previous process must reach a
                    // terminal state even on a pass where GMweb has nothing new to hand over.
                    drainIfDue()
                    val commands = claim()
                    if (commands.isEmpty()) {
                        waitOrWake(QUIET_MS)
                        continue
                    }
                    _stateFlow.value = State.INGESTING
                    var fresh = 0
                    for (cmd in commands) {
                        val accepted = ingest(cmd)
                        if (accepted) {
                            fresh++
                            ack(cmd.commandId, "ACCEPTED", null)
                            execute(cmd)
                        } else {
                            // Redelivery of an already-ingested command: it is
                            // EITHER completed (durable state) or executing —
                            // report honestly; never double-execute (§71).
                            //
                            // §49: a freshly ingested row always carries clientMessageId now, but a
                            // row ingested by an older build is still in flight for up to 24h. Fill
                            // it in from the redelivery rather than waiting for the install to age
                            // out — the write is conditional on the column being empty, so it can
                            // never overwrite a key this device already committed to.
                            val key = cmd.clientMessageId
                            if (key != null) {
                                withContext(Dispatchers.IO) {
                                    repo.setCommandClientMessageIdIfMissing(cmd.commandId, key)
                                }
                            }
                            ackIfTerminal(cmd.commandId)
                        }
                    }
                    if (fresh > 0) onLog("📥 $fresh new command(s) ingested")
                } catch (e: Exception) {
                    Log.w(TAG, "poll cycle failed: ${e.message}")
                    _stateFlow.value = State.ERROR
                    waitOrWake(ERROR_RETRY_MS)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _stateFlow.value = State.IDLE
    }

    /**
     * Whether the command loop is alive RIGHT NOW.
     *
     * Diagnostics asks this because "start() was called once" and "the loop is running" are different
     * facts, and the read channel is useless without the second: MARK_THREAD_READ has no other
     * transport.
     */
    val isRunning: Boolean get() = job?.isActive == true

    /** When the drain last ran; 0 makes the first loop iteration after a start always drain. */
    private var lastDrainAt = 0L

    private suspend fun drainIfDue() {
        val now = System.currentTimeMillis()
        if (now - lastDrainAt < DRAIN_INTERVAL_MS) return
        lastDrainAt = now
        drainOnce(now)
    }

    /**
     * Drive every non-terminal command left behind by a previous process to a terminal state
     * (mission §46/§47/§48).
     *
     * Why this exists: a command reaches `RECEIVED` the moment it is ingested, and only the poll
     * loop that ingested it ever executes it. A process death in that window — or during a send —
     * left the row non-terminal forever, and GMweb has no way to learn that: every redelivery got
     * silence (`ackIfTerminal` does nothing for a non-terminal row), so the web's ledger hung
     * (docs/gateway-replication-audit.md, Blocker 15).
     *
     * The two requirements this has to satisfy at once are §46 (nothing stays non-terminal) and §78
     * (no remote send may ever happen twice), and they conflict for a command that was claimed and
     * interrupted: after the hand-off the row is indistinguishable from one that never ran.
     * [CommandDrainPolicy] resolves that conflict on the only fact that CAN distinguish them —
     * whether the row was ever claimed — and this function just carries out its verdict. All
     * judgement lives there, in one place, because a rule restated at a call site loses clauses.
     */
    private suspend fun drainOnce(now: Long) {
        try {
            // Expiry first: a command nobody ever claimed and whose window has closed provably
            // never executed, so it is closed as EXPIRED rather than reported as uncertain.
            val expired = withContext(Dispatchers.IO) { repo.expireUnclaimedCommands(now) }
            // Then return abandoned leases. This is what makes the policy's view consistent: an
            // ACCEPTED/EXECUTING row with a dead lease becomes RECEIVED again, while its
            // attemptCount keeps the fact that somebody did claim it.
            val reclaimed = withContext(Dispatchers.IO) { repo.reclaimExpiredCommandLeases(now) }
            val pending = withContext(Dispatchers.IO) { repo.nonTerminalCommands(DRAIN_BATCH) }
            if (pending.isEmpty()) {
                if (expired > 0 || reclaimed > 0) {
                    onLog("🧹 command drain: expired=$expired reclaimed=$reclaimed")
                }
                return
            }
            val plan = CommandDrainPolicy.plan(pending, now)
            for (resolution in plan.resolutions) {
                val id = resolution.command.commandId
                withContext(Dispatchers.IO) {
                    repo.finishCommand(id, resolution.state, resolution.code.name, now)
                }
                // Report it, so GMweb stops waiting. A lost ACK is not fatal: the durable row is
                // truth and the next redelivery reports the same terminal state.
                ack(id, resolution.state, resolution.code.name)
                onLog(
                    "🧹 command drain: ${resolution.command.type} $id → ${resolution.state} " +
                        "(${resolution.code.name})"
                )
                DiagnosticLog.event(
                    "COMMAND_DRAIN",
                    "resolved type=${resolution.command.type} state=${resolution.state} " +
                        "attempts=${resolution.command.attemptCount} code=${resolution.code.name}"
                )
            }
            for (command in plan.drive) {
                DiagnosticLog.event(
                    "COMMAND_DRAIN",
                    "driving type=${command.type} attempts=${command.attemptCount}"
                )
                execute(command)
            }
            if (expired > 0 || reclaimed > 0) {
                onLog("🧹 command drain: expired=$expired reclaimed=$reclaimed")
            }
        } catch (e: Exception) {
            // A drain failure must never take down intake: the next pass retries the same rows.
            Log.w(TAG, "drain pass failed: ${e.message}")
        }
    }

    /** POST /api/v1/agent/commands/claim → opaque rows (base64 payloads). */
    private suspend fun claim(): List<RemoteCommandEntity> {
        val base = prefs.gmwebUrl.trimEnd('/')
        val path = "/api/v1/agent/commands/claim"
        val conn = URL("$base$path").openConnection() as HttpURLConnection
        return try {
            conn.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connectTimeout = 15_000
                readTimeout = 30_000
                doOutput = true
            }
            val currentDeviceId = deviceId()
            // RUNTIME ADVERTISEMENT, and it depends on NOTHING else: the installed APK's own metadata
            // (PackageManager) and this build's command contract. DeviceTelemetry is deliberately not
            // consulted — a phone whose telemetry has never succeeded must still tell GMweb which
            // build is running and what it can execute.
            val runtime = installedRuntime(context)
            CommandRuntimeHealth.onClaimAttempt(
                versionName = runtime.versionName,
                versionCode = runtime.versionCode,
                commandTypes = COMMAND_TYPES
            )
            val bodyBytes = buildClaimBody(currentDeviceId, runtime.versionName, runtime.versionCode)
            // PR-08b: per-device signature over the canonical request (ADR-001).
            // X-API-Key stays as a legacy fallback for older GMweb builds.
            conn.setRequestProperty("X-API-Key", prefs.apiKey)
            if (!AgentAuth.sign(conn, currentDeviceId, path, "POST", bodyBytes)) {
                throw IllegalStateException("agent signing failed (keystore unavailable)")
            }
            // The SAME array that was signed: the signature covers the runtime block above, and no
            // regeneration happens between signing and transmission.
            conn.outputStream.use { it.write(bodyBytes) }
            val status = conn.responseCode
            CommandRuntimeHealth.onClaimResult(status)
            if (status != 200) {
                throw IllegalStateException("claim HTTP $status")
            }
            val body = conn.inputStream.use { it.bufferedReader().readText() }
            val rows = JSONObject(body).optJSONArray("commands") ?: JSONArray()
            val now = System.currentTimeMillis()
            (0 until rows.length()).mapNotNull { i ->
                val c = rows.optJSONObject(i) ?: return@mapNotNull null
                val id = c.optString("id")
                val ciphertext = decodeCiphertext(c)
                if (ciphertext == null) {
                    if (id.isNotBlank()) {
                        Log.e(TAG, "protocol_error: command $id has missing or invalid ciphertext")
                        ack(id, "FAILED", "protocol_error: missing or invalid ciphertext")
                    }
                    return@mapNotNull null
                }
                commandFromEnvelope(c, ciphertext, now)
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Exactly-once ingest: INSERT OR IGNORE by unique idempotencyKey.
     * True = fresh command (caller executes + ACKs); False = redelivery.
     */
    private suspend fun ingest(cmd: RemoteCommandEntity): Boolean =
        withContext(Dispatchers.IO) { repo.ingestCommand(cmd) }

    /** SEND_SMS executes immediately through the single funnel (§19/§20). */
    private suspend fun execute(cmd: RemoteCommandEntity) {
        // UNKNOWN TYPES REACH A TERMINAL STATE. This used to `return` silently, which left a claimed
        // row non-terminal for ever: GMweb's optimistic entry spun with nothing on the device working
        // on it, and the row could never be resolved. An unsupported type is now a durable FAILED,
        // ACKed as such — the honest answer, and the one that makes forward compatibility possible.
        if (cmd.type !in SUPPORTED_TYPES) {
            Log.w(TAG, "unsupported command type=${cmd.type} id=${cmd.commandId}")
            DiagnosticLog.event(
                "GM_COMMAND",
                "unsupported_type id=${cmd.commandId.take(8)} type=${cmd.type.take(40)}"
            )
            withContext(Dispatchers.IO) {
                repo.finishCommandFrom(
                    commandId = cmd.commandId,
                    state = RemoteCommandEntity.STATE_FAILED,
                    errorCode = RESULT_UNSUPPORTED_COMMAND_TYPE,
                    fromStates = listOf(
                        RemoteCommandEntity.STATE_RECEIVED,
                        RemoteCommandEntity.STATE_ACCEPTED,
                        RemoteCommandEntity.STATE_EXECUTING
                    )
                )
            }
            ack(cmd.commandId, "FAILED", RESULT_UNSUPPORTED_COMMAND_TYPE)
            return
        }
        // Intake ownership (P0, no-dual-execution): SEND_SMS is owned by
        // exactly ONE transport. Until the strategic command path passes
        // real-device E2E, the legacy pull bridge owns delivery — strategic
        // SEND_SMS commands are NOT executed here, so a backlog can never run
        // twice through two channels.
        if (cmd.type == "SEND_SMS" && !prefs.controlPlaneSendsEnabled) {
            // The durable row MUST become terminal here, not just the ACK.
            //
            // This used to report FAILED to GMweb and leave the row RECEIVED — telling the server
            // one thing and recording another. It also made the row permanently non-terminal, so
            // the drain would re-drive it and re-ACK it on every pass forever. A refusal that the
            // server is told about is a terminal outcome and has to be recorded as one.
            val code = SyncErrorCode.SMS_SEND_FAILED
            withContext(Dispatchers.IO) {
                repo.finishCommand(cmd.commandId, RemoteCommandEntity.STATE_FAILED, code.name)
            }
            ack(cmd.commandId, "FAILED", "deferred: SEND_SMS owned by legacy pull intake")
            return
        }
        scope.launch {
            try {
                if (cmd.cryptoVersion != 1) error("remote command must use cryptoVersion=1")
                val plaintext = CommandCrypto.decrypt(cmd.ciphertext, cmd.type, cmd.idempotencyKey)
                // Open the attempt record BEFORE the side effect (mission §45). If the process dies
                // during execution, this row is the only evidence that an attempt was started.
                val executionId = withContext(Dispatchers.IO) {
                    repo.beginCommandExecution(cmd.commandId, cmd.attemptCount + 1)
                }
                var result = "completed"
                try {
                    ack(cmd.commandId, "EXECUTING", null)
                    when (CommandRouting.routeOf(cmd.type)) {
                        CommandRoute.READ_THREAD -> executeMarkThreadRead(cmd, plaintext, repo)
                        CommandRoute.TELEMETRY_REFRESH -> executeRefreshDeviceTelemetry(cmd, plaintext, repo)
                        // SEND_SMS is the ONLY type that may enter the SMS pipeline.
                        CommandRoute.SMS_PIPELINE -> withContext(Dispatchers.IO) {
                            GatewayOutgoingPipeline.executeIngested(
                                cmd.copy(ciphertext = plaintext, cryptoVersion = 0), repo
                            )
                        }
                        // Handled above, before execution starts; unreachable by construction.
                        CommandRoute.UNSUPPORTED -> error(RESULT_UNSUPPORTED_COMMAND_TYPE)
                    }
                } catch (e: Exception) {
                    // TYPE-AWARE: a read failure is classified by what actually went wrong and is
                    // NEVER recorded as SMS_SEND_FAILED. That conflation is why a missing conversation
                    // mapping and a transient provider refusal looked identical on GMweb.
                    result = ReadCommandError.classifyFor(cmd.type, e).name
                    throw e
                } finally {
                    plaintext.fill(0)
                    withContext(Dispatchers.IO) {
                        repo.finishCommandExecution(executionId, result)
                    }
                }
                ackIfTerminal(cmd.commandId)
            } catch (e: Exception) {
                Log.e(TAG, "command execution failed type=${cmd.type} id=${cmd.commandId}", e)
                val code = ReadCommandError.classifyFor(cmd.type, e)
                runCatching {
                    repo.finishCommandFrom(
                        commandId = cmd.commandId,
                        state = RemoteCommandEntity.STATE_FAILED,
                        // The typed cause, so the ledger names the condition instead of saying
                        // "unknown" about a failure that was classified two frames up.
                        errorCode = code.name,
                        fromStates = listOf(
                            RemoteCommandEntity.STATE_ACCEPTED,
                            RemoteCommandEntity.STATE_EXECUTING
                        )
                    )
                    ack(cmd.commandId, "FAILED", code.name)
                }
            }
        }
    }

    /**
     * `REFRESH_DEVICE_TELEMETRY`: ask the live reporter for a FRESH report and complete only on a
     * real 2xx.
     *
     * The browser's "Refresh SIMs" button must not be answered by a queued wake-up. It needs proof
     * that a telemetry POST reached GMweb, so this command awaits the structured result and reports
     * the exact failure code when there is none. The report goes through the SAME reporter and the
     * SAME report mutex as the heartbeat: no second loop, no second transport, no overlapping POST.
     */
    private suspend fun executeRefreshDeviceTelemetry(
        cmd: RemoteCommandEntity,
        plaintext: ByteArray,
        repo: GatewaySyncRepository
    ) {
        check(repo.markCommandAcceptedIfReceived(cmd.commandId)) { "command already owned" }
        repo.markCommandState(
            cmd.commandId, RemoteCommandEntity.STATE_EXECUTING,
            listOf(RemoteCommandEntity.STATE_ACCEPTED)
        )
        val payload = try {
            JSONObject(String(plaintext, Charsets.UTF_8))
        } catch (e: Exception) {
            throw ReadCommandFailure.InvalidPayload(e)
        }
        // Contract: the plaintext type must agree with the envelope type, and the only reason this
        // build accepts is a SIM refresh. Unknown FIELDS are ignored (additive-safe); an unknown
        // reason is refused, because silently treating it as a SIM refresh would answer a question
        // nobody asked.
        val declaredType = payload.optString("type").takeIf { it.isNotBlank() }
        if (declaredType != null && declaredType != REFRESH_DEVICE_TELEMETRY) {
            throw ReadCommandFailure.InvalidPayload()
        }
        val reason = payload.optString("reason", REASON_SIM_REFRESH)
        if (reason != REASON_SIM_REFRESH) throw ReadCommandFailure.InvalidPayload()
        if (payload.has("requestedAt") && !payload.isNull("requestedAt")) {
            val requestedAt = payload.optLong("requestedAt", 0L)
            // A timestamp far in the future is a broken clock, not a request: refuse it rather than
            // record it as this device's own time.
            if (requestedAt > System.currentTimeMillis() + 24L * 3600_000) {
                throw ReadCommandFailure.InvalidPayload()
            }
        }

        DiagnosticLog.event(
            "GM_TELEMETRY",
            "REMOTE_TELEMETRY_REFRESH_BEGIN id=${cmd.commandId.take(8)} reason=$reason"
        )
        val outcome = DeviceTelemetry.requestImmediateAndAwait(
            TelemetryTrigger.REMOTE_REFRESH,
            timeoutMs = REMOTE_REFRESH_TIMEOUT_MS
        )
        val statusText = when (outcome) {
            is TelemetryReportResult.Success -> outcome.httpStatus.toString()
            is TelemetryReportResult.Failure -> outcome.httpStatus?.toString() ?: "none"
        }
        when (outcome) {
            is TelemetryReportResult.Success -> {
                DiagnosticLog.event(
                    "GM_TELEMETRY",
                    "REMOTE_TELEMETRY_REFRESH_END result=success http=$statusText"
                )
                repo.finishCommandFrom(
                    commandId = cmd.commandId,
                    state = RemoteCommandEntity.STATE_COMPLETED,
                    errorCode = null,
                    fromStates = listOf(
                        RemoteCommandEntity.STATE_ACCEPTED,
                        RemoteCommandEntity.STATE_EXECUTING
                    )
                )
            }
            is TelemetryReportResult.Failure -> {
                DiagnosticLog.event(
                    "GM_TELEMETRY",
                    "REMOTE_TELEMETRY_REFRESH_END result=failure " +
                        "code=${outcome.code.commandCode} http=$statusText"
                )
                // Durable FAILED, and the SAME stable code goes to GMweb: the command is not
                // completed merely because the refresh was requested.
                repo.finishCommandFrom(
                    commandId = cmd.commandId,
                    state = RemoteCommandEntity.STATE_FAILED,
                    errorCode = outcome.code.commandCode,
                    fromStates = listOf(
                        RemoteCommandEntity.STATE_ACCEPTED,
                        RemoteCommandEntity.STATE_EXECUTING
                    )
                )
            }
        }
    }

    /**
     * `MARK_THREAD_READ`: mark the conversation read on this device and PROVE it before completing.
     *
     * The previous version acked `COMPLETED` whenever none of the four steps threw — and three of
     * them could not throw: the provider write's result was discarded, the read event's
     * `EnqueueAttempt` was discarded (a LOCAL_ONLY conversation silently publishes nothing), and
     * `finishCommandFrom`'s own boolean was discarded. So "read on the phone" could be reported to
     * GMweb having changed nothing and told nobody.
     *
     * Order is now load-bearing: resolve → provider write → durable event → only then COMPLETED.
     */
    private suspend fun executeMarkThreadRead(
        cmd: RemoteCommandEntity,
        plaintext: ByteArray,
        repo: GatewaySyncRepository
    ) {
        check(repo.markCommandAcceptedIfReceived(cmd.commandId)) { "command already owned" }
        repo.markCommandState(
            cmd.commandId, RemoteCommandEntity.STATE_EXECUTING,
            listOf(RemoteCommandEntity.STATE_ACCEPTED)
        )
        val payload = try {
            JSONObject(String(plaintext, Charsets.UTF_8))
        } catch (e: Exception) {
            throw ReadCommandFailure.InvalidPayload(e)
        }
        val conversationId = payload.optString("conversationId").takeIf { it.isNotBlank() }
            ?: throw ReadCommandFailure.InvalidPayload()
        val mapping = MessagesDatabase.get(context).remoteConversationMapDao()
            .getByConversationId(conversationId)
            ?: throw ReadCommandFailure.MappingMissing(conversationId)
        if (mapping.threadId <= 0L) throw ReadCommandFailure.UnknownConversation(mapping.threadId)

        val providerResult = com.autonomousone.messages.repository.SmsRepository(context)
            .markThreadAsReadStrict(mapping.threadId)
        if (providerResult.hasFailure) {
            val reason = listOfNotNull(
                (providerResult.sms as? com.autonomousone.messages.repository.SourceWriteResult.Failure)?.reason,
                (providerResult.mms as? com.autonomousone.messages.repository.SourceWriteResult.Failure)?.reason
            ).joinToString(",").ifBlank { "unspecified" }
            throw ReadCommandFailure.ProviderWriteFailed(reason)
        }

        // The durable event must exist BEFORE the command is completed: GMweb learns the read state
        // from this event, so completing first would be a claim the phone cannot back up.
        when (val outcome = com.autonomousone.messages.data.TelephonySyncCoordinator.get(context)
            .applyMarkThreadRead(mapping.threadId)
        ) {
            is com.autonomousone.messages.data.TelephonySyncCoordinator.ThreadReadOutcome.Published -> Unit
            is com.autonomousone.messages.data.TelephonySyncCoordinator.ThreadReadOutcome.NotPublished ->
                throw ReadCommandFailure.EventPublishFailed(outcome.attempt)
            com.autonomousone.messages.data.TelephonySyncCoordinator.ThreadReadOutcome.Failed ->
                throw ReadCommandFailure.UnknownConversation(mapping.threadId)
        }

        repo.finishCommandFrom(
            commandId = cmd.commandId,
            state = RemoteCommandEntity.STATE_COMPLETED,
            errorCode = null,
            fromStates = listOf(
                RemoteCommandEntity.STATE_ACCEPTED,
                RemoteCommandEntity.STATE_EXECUTING
            )
        )
    }

    /** Report lifecycle to GMweb (§58); failures are logged, never fatal. */
    private suspend fun ack(commandId: String, state: String, result: String?) {
        val base = prefs.gmwebUrl.trimEnd('/')
        val path = "/api/v1/agent/commands/$commandId/status"
        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            conn.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
            }
            val bodyBytes = JSONObject()
                .put("state", state)
                .put("result", result ?: JSONObject.NULL)
                .toString().toByteArray(Charsets.UTF_8)
            conn.setRequestProperty("X-API-Key", prefs.apiKey)
            AgentAuth.sign(conn, deviceId(), path, "POST", bodyBytes)
            conn.outputStream.use { it.write(bodyBytes) }
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "ack $state for $commandId → HTTP ${conn.responseCode}")
            }
        } catch (e: Exception) {
            // A lost ack must not re-execute; the durable state row is truth.
            Log.w(TAG, "ack failed for $commandId: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    /** Report the DURABLE local state of a redelivered command (§58 honest). */
    private suspend fun ackIfTerminal(commandId: String) {
        val cmd = withContext(Dispatchers.IO) { repo.getCommand(commandId) } ?: return
        val resultText = String(cmd.ciphertext, Charsets.UTF_8).take(120)
        when (cmd.state) {
            RemoteCommandEntity.STATE_COMPLETED -> ack(commandId, "COMPLETED", "already executed")
            RemoteCommandEntity.STATE_FAILED -> ack(commandId, "FAILED", resultText)
            else -> Unit // still in-flight locally; its own executor will report
        }
    }
}
