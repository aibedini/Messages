package com.autonomousone.messages.sync.diagnostics

import android.content.Context
import com.autonomousone.messages.BuildConfig
import com.autonomousone.messages.data.MessageEntity
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.gateway.ConnectionSupervisor
import com.autonomousone.messages.gateway.GatewayPreferences
import com.autonomousone.messages.gateway.GatewayService
import com.autonomousone.messages.gateway.health.GatewayHealthRecorder
import com.autonomousone.messages.repository.GatewaySyncRepository
import com.autonomousone.messages.sync.MirrorReconcileStatus
import com.autonomousone.messages.sync.ReplicationInputsReader
import com.autonomousone.messages.sync.ReplicationPrerequisiteEvaluator
import com.autonomousone.messages.sync.SyncActivity
import com.autonomousone.messages.sync.SyncStateMachine
import com.autonomousone.messages.sync.TrustedDevicePolicy

/**
 * Fills [SyncDiagnostics] from the places that own each fact (mission §56).
 *
 * THE RULE THIS FILE FOLLOWS: report what is known, and `null` for what is not. It is very easy to
 * make a diagnostic look complete by defaulting an unmeasured counter to zero, and that is how a
 * report starts asserting things nobody observed. Where the app genuinely does not track a number
 * (history `skipped`/`failed` per source, the last reconnect time), the field stays null and the
 * reader can see it was never measured.
 *
 * Read-only: every query here is an aggregate or a state read. Nothing deletes, updates, or reads
 * a message payload.
 *
 * NOT ON THE MAIN THREAD: touches Room and the Keystore. Call from `Dispatchers.IO`.
 */
class SyncDiagnosticsCollector(context: Context) {

    private val appContext = context.applicationContext
    private val db = MessagesDatabase.get(appContext)
    private val prefs = GatewayPreferences(appContext)

    suspend fun collect(now: Long = System.currentTimeMillis()): SyncDiagnostics {
        val inputs = ReplicationInputsReader(appContext).read(now)
        val prerequisites = ReplicationPrerequisiteEvaluator.evaluate(inputs)
        val health = GatewayHealthRecorder.rawSnapshot(now)

        val outbox = runCatching { db.gatewayEventOutboxDao().stateSummary(now) }.getOrNull()
        val repository = GatewaySyncRepository(db)
        val checkpoints = runCatching { db.cloudHistoryCheckpointDao().all() }.getOrDefault(emptyList())
        val syncStates = SOURCES.associateWith { source ->
            runCatching { db.syncStateDao().forSource(source) }.getOrNull()
        }
        val commandSummary = runCatching { db.remoteCommandDao().summary() }.getOrNull()
        val lastCommandType = runCatching { db.remoteCommandDao().lastCommandType() }.getOrNull()
        // §42: which keys the outstanding events are under. Guarded like every other read here, and
        // defaulting to "not measured" rather than to an empty list, which would read as "one key".
        val keyRefs = runCatching { repository.outstandingKeyRefCounts() }.getOrNull()
        val trustedDevices = runCatching { db.trustedDeviceDao().all() }.getOrDefault(emptyList())
        val sessions = SOURCES.associateWith { source ->
            runCatching { db.historySyncSessionDao().latestFor(source) }.getOrNull()
        }

        val historySources = SOURCES.map { source ->
            val state = syncStates[source]
            val cloud = checkpoints.firstOrNull { it.source == source }
            // `nextOrdinal` is the next ordinal to assign, so the produced count is one less.
            val produced = cloud?.let { (it.nextOrdinal - 1L).coerceAtLeast(0L) }
            // COUNTED, not inferred from the watermark: a dead letter freezes the contiguous
            // frontier, so `ackedContiguousOrdinal` is a position in the sequence and not a number
            // of acknowledged rows. Reporting it as `acked` invented thousands of pending events.
            val outboxDao = db.gatewayEventOutboxDao()
            val counts = historySourceCounts(
                produced = produced,
                ackedCount = cloud?.let {
                    runCatching { outboxDao.historyAckedCount(source, it.generation) }
                        .getOrNull()?.toLong()
                },
                deadLetters = cloud?.let {
                    runCatching { outboxDao.historyDeadLetters(source, it.generation) }
                        .getOrNull()?.toLong()
                }
            )
            val sessionRow = sessions[source]
            val session = sessionRow?.counters()?.let { it ->
                SyncDiagnostics.HistorySessionSection(
                    sessionId = it.sessionId,
                    eligible = it.eligible,
                    enqueued = it.enqueued,
                    skipped = it.skipped,
                    failed = it.failed,
                    skippedLocalOnly = it.skippedLocalOnly,
                    skippedAskPending = it.skippedAskPending,
                    skippedNoDirection = it.skippedNoDirection,
                    skippedSyncOff = it.skippedSyncOff,
                    scanExhausted = it.scanExhausted,
                    open = it.isOpen,
                    startedAt = it.startedAt,
                    finishedAt = it.finishedAt,
                )
            }
            SyncDiagnostics.HistorySourceSection(
                source = source,
                // Not tracked per source anywhere in the app. Null, not zero.
                scanned = null,
                enqueued = produced?.toInt(),
                acked = counts.acked?.toInt(),
                pending = counts.pending?.toInt(),
                skipped = null,
                // A permanent failure is now REPORTED as one, rather than left unmeasured while the
                // number sat unused in the DAO. Mission §70: nothing unexplained, and nothing
                // disguised as work in progress.
                failed = counts.failed?.toInt(),
                // The PROVIDER crawl watermark, which is "where the scan is". Long.MAX_VALUE is
                // the "never advanced" sentinel, and printing it as a date would be nonsense.
                checkpointDate = state?.oldestDate?.takeIf { it != Long.MAX_VALUE },
                checkpointProviderId = state?.oldestId?.takeIf { it != Long.MAX_VALUE },
                scanComplete = state?.historyBackfillComplete,
                // The REAL delivery rule, taken from the one place that defines it rather than
                // re-derived here. Re-deriving it is how the first version of this collector
                // dropped the dead-letter clause and would have reported CAUGHT_UP for a source
                // whose rows had failed permanently.
                delivered = runCatching { repository.isHistoryDeliveryComplete(source) }.getOrNull(),
                // §70: the numbers that say whether anything was dropped. Null when no scan has
                // been accounted for, which is an unmeasured state and not a healthy one.
                session = session,
            )
        }

        val activity = SyncActivity(
            uploadInFlight = (outbox?.inFlightCount ?: 0) > 0,
            backingOff = (outbox?.retryWaitCount ?: 0) > 0,
            historyScanComplete = historySources.isNotEmpty() &&
                historySources.all { it.scanComplete == true },
            // Scan complete is NOT the same as delivered (mission §26): every source must also be
            // exhausted, fully acknowledged AND free of dead letters. That last clause is the one
            // that is easy to drop, and dropping it turns a permanently-failed row into "caught up".
            historyResolvedAll = historySources.isNotEmpty() &&
                SOURCES.all { source ->
                    runCatching { repository.isHistoryCaughtUp(source) }.getOrNull() == true
                },
            historySessionExists = checkpoints.isNotEmpty()
        )

        return SyncDiagnostics(
            generatedAt = now,
            application = SyncDiagnostics.ApplicationSection(
                appVersion = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE,
                // Truncated again on export; see SyncDiagnostics.token.
                deviceIdToken = prefs.agentDeviceId(appContext)
            ),
            identity = SyncDiagnostics.IdentitySection(
                registered = prefs.identityRegistered,
                accountLinked = prefs.isRegistered,
                authorized = health.authentication.status ==
                    com.autonomousone.messages.gateway.health.AuthVerification.VERIFIED,
                // No local model of THIS device being revoked exists; see the audit's Blocker 23
                // note and ReplicationInputsReader. Reported false rather than guessed.
                revoked = false
            ),
            gateway = SyncDiagnostics.GatewaySection(
                desired = prefs.gatewayDesiredEnabled && prefs.hasGatewayConsent,
                controlConnected = GatewayService.supervisorState ==
                    ConnectionSupervisor.State.CONNECTED,
                lastHeartbeatAt = prefs.lastHeartbeatAt.takeIf { it > 0L },
                // Never recorded anywhere in the app. Null, not a fabricated timestamp.
                lastReconnectAt = null
            ),
            replication = SyncDiagnostics.ReplicationSection(
                state = SyncStateMachine.replication(prerequisites, activity),
                readyCount = outbox?.readyCount,
                inFlightCount = outbox?.inFlightCount,
                retryWaitCount = outbox?.retryWaitCount,
                deadLetterCount = outbox?.deadLetterCount,
                ackedCount = outbox?.ackedCount,
                oldestPendingAgeMs = outbox?.oldestPendingCreatedAt?.let { (now - it).coerceAtLeast(0L) },
                lastUploadAttemptAt = health.eventUpload.lastAttemptAt,
                lastSuccessfulUploadAt = health.eventUpload.lastSuccessAt,
                lastHttpStatus = health.eventUpload.lastHttpStatus,
                // Batch counters are computed per upload and only logged today; not stored.
                lastBatchSize = null,
                lastAccepted = null,
                lastDuplicates = null,
                lastRejected = null,
                keyRefs = keyRefs.orEmpty().map {
                    SyncDiagnostics.KeyRefSection(keyRef = it.keyRef, count = it.count)
                }
            ),
            history = SyncDiagnostics.HistorySection(
                state = SyncStateMachine.history(prerequisites, activity),
                grant = fullHistoryGrant(trustedDevices, now),
                keyVersion = null,
                // The newest session across sources, so the field names a real row instead of being
                // permanently null. Per-source detail lives on each source's `session`.
                sessionId = sessions.values
                    .filterNotNull()
                    .maxByOrNull { it.startedAt }
                    ?.sessionId,
                sources = historySources
            ),
            commands = SyncDiagnostics.CommandSection(
                state = SyncStateMachine.commands(prerequisites, activity),
                received = commandSummary?.received,
                claimed = commandSummary?.claimed,
                executing = commandSummary?.executing,
                completed = commandSummary?.completed,
                failed = commandSummary?.failed,
                lastCommandAt = commandSummary?.lastReceivedAt,
                lastCommandType = lastCommandType
            ),
            prerequisites = prerequisites,
            // §58: how many finished tasks GMweb has not been told about. Null when the durable queue
            // has not been loaded — "not looked at" is not "nothing to report".
            gatewayReportsAwaitingAck = runCatching {
                com.autonomousone.messages.eve.EveSmsQueue.unreportedGatewayBacklog()
            }.getOrNull(),
            telephonyRelayRunning =
                com.autonomousone.messages.observer.GatewayChangeRelay.runningProcessWide,
            // The measured answer to "did we drop anything?" (mission §70). Null when
            // reconciliation has not run, which is deliberately not reported as zero.
            reconciliation = MirrorReconcileStatus.last?.let { result ->
                SyncDiagnostics.ReconcileSection(
                    examined = result.examined,
                    recovered = result.recovered,
                    windowHours = (RECONCILE_WINDOW_MS / 60 / 60_000L).toInt(),
                    ranAt = MirrorReconcileStatus.lastAt
                )
            },
            // §35: the deeper, resumable walk over the WHOLE mirror. Empty until a sweep runs, and
            // an unfinished sweep is reported as unfinished rather than as a clean result — the window
            // check above cannot see a gap older than its own window, so this is the only thing that
            // can.
            mirrorVerify = runCatching { db.mirrorVerifyStateDao().all() }.getOrDefault(emptyList())
                .map { stored ->
                    val progress = stored.progress()
                    SyncDiagnostics.MirrorVerifySection(
                        source = progress.source,
                        complete = progress.complete,
                        examined = progress.examined,
                        alreadyReplicated = progress.alreadyReplicated,
                        recovered = progress.recovered,
                        skippedNoProviderId = progress.skippedNoProviderId,
                        skippedNoDirection = progress.skippedNoDirection,
                        balanced = progress.balances,
                        residual = progress.residual,
                        startedAt = progress.startedAt,
                        updatedAt = progress.updatedAt,
                        completedAt = progress.completedAt,
                    )
                },
            // §52: the MMS attachment gap, REPORTED rather than left invisible. Measured/null only —
            // never a zero standing for "fine" — and `replicationPathExists` is false because no
            // upload protocol exists yet (docs/gmweb-mms-attachment-handoff.md), which is a different
            // statement from "nothing to replicate".
            mmsAttachments = SyncDiagnostics.MmsAttachmentSection(
                localAssets = runCatching {
                    db.messageAssetDao().countForSource(MessageEntity.SOURCE_MMS)
                }.getOrNull(),
                replicationPathExists = false
            )
        )
    }

    /** The strongest grant any trusted device currently holds, for display only. */
    private fun fullHistoryGrant(
        devices: List<com.autonomousone.messages.data.TrustedDeviceEntity>,
        now: Long
    ): String? = when {
        devices.isEmpty() -> null
        TrustedDevicePolicy.hasFullHistoryConsumer(devices, now) -> TrustedDevicePolicy.GRANT_FULL_HISTORY
        devices.any { TrustedDevicePolicy.isTrusted(it, now) } -> TrustedDevicePolicy.GRANT_FROM_NOW_ON
        else -> null
    }

    private companion object {
        val SOURCES = listOf(MessageEntity.SOURCE_SMS, MessageEntity.SOURCE_MMS)

        /** Mirrors the coordinator's window; kept here only for display. */
        const val RECONCILE_WINDOW_MS = 48 * 60 * 60_000L
    }
}
