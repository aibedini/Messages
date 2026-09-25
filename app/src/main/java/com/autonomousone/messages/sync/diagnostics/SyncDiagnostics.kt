package com.autonomousone.messages.sync.diagnostics

import com.autonomousone.messages.sync.CommandSyncState
import com.autonomousone.messages.sync.HistorySyncState
import com.autonomousone.messages.sync.ReplicationPrerequisites
import com.autonomousone.messages.sync.ReplicationState
import com.autonomousone.messages.sync.SyncErrorCode
import org.json.JSONArray
import org.json.JSONObject

/**
 * The sync diagnostic model (mission §56), and its sanitized export (§58).
 *
 * WHY A SEPARATE MODEL FROM THE EXISTING REPORT: the shipped `GatewayDiagnosticReport` is a
 * rendered prose blob. It is good at what it does, but it cannot be asserted on, diffed,
 * counted, or attached to a bug report as data — and today it is missing the entire history and
 * command sections plus any notion of a blocker (audit: "WHAT THE MISSION ASKS FOR THAT IS
 * ABSENT"). This model is structured first; rendering is a separate, trivial step.
 *
 * THE SANITIZATION IS STRUCTURAL, NOT A FILTER. No field here can hold a message body, an
 * address, a phone number, a token or key material — there is simply nowhere to put one. A
 * redaction pass over free text can always be defeated by a new code path; a type that cannot
 * carry the secret cannot leak it. That is why [toSanitizedJson] builds its output from typed
 * fields rather than serialising a map.
 *
 * Unknown is `null`, never `0`. "We have not measured this" and "this is zero" are different
 * facts, and collapsing them is how a diagnostic starts asserting things it never observed.
 */
data class SyncDiagnostics(    val generatedAt: Long,
    val application: ApplicationSection,
    val identity: IdentitySection,
    val gateway: GatewaySection,
    val replication: ReplicationSection,
    val history: HistorySection,
    val commands: CommandSection,
    val prerequisites: ReplicationPrerequisites,
    /**
     * Whether provider-change detection is registered independently of the UI.
     *
     * Exists because the defect it reports was completely invisible: replication used to run only
     * while a screen was open, with nothing logged and a green gateway (audit Blocker 1). A user
     * can now confirm on-device that background detection is live.
     *
     * Null means "not measured", never "off".
     */
    val telephonyRelayRunning: Boolean? = null,
    /**
     * The most recent mirror → outbox reconciliation (mission §70).
     *
     * This is the only number that answers "is anything unexplained?" — a message the phone knows
     * about with no durable event. Null means it has not run, which is deliberately not the same as
     * zero.
     */
    val reconciliation: ReconcileSection? = null,
    /**
     * Finished gateway tasks whose outcome GMweb has not accepted yet (mission §58).
     *
     * `null` means the durable queue has not been loaded, which is deliberately NOT rendered as zero:
     * before the queue is read, an empty in-memory map means "we have not looked", and reporting that as
     * "everything is reported" is the false reassurance this project keeps having to remove.
     *
     * This number is durable, unlike the ACK counters in the health snapshot — which is the point: a
     * report that failed once used to be lost for good, and now it survives a restart until GMweb
     * accepts it.
     */
    val gatewayReportsAwaitingAck: Int? = null,
    /**
     * The full-mirror verification's progress, one entry per source (mission §35).
     *
     * Empty means no sweep has run — an unmeasured state, deliberately not rendered as "verified".
     */
    val mirrorVerify: List<MirrorVerifySection> = emptyList(),
    /**
     * The MMS attachment gap (mission §52).
     *
     * Null means not measured. This section exists because the gap was otherwise INVISIBLE: the
     * attachments are indexed for the local media tabs and none of them can be replicated, yet a
     * device holding twelve un-replicable photos reported exactly as healthy as one holding none.
     * Reporting it is the prerequisite that can be done without the server — see
     * `docs/gmweb-mms-attachment-handoff.md`.
     */
    val mmsAttachments: MmsAttachmentSection? = null
) {

    /** The one actionable blocker, or null when replication is clear (mission §57). */
    val currentBlocker: SyncErrorCode?
        get() = prerequisites.primaryCode

    /**
     * The single line a human should read first.
     *
     * Deliberately never the bare word `DEGRADED`: a state without a cause is the thing the
     * mission forbids.
     */
    fun blockerLine(): String = SyncDiagnosticsText.blockerLine(prerequisites)

    /** Sanitized JSON export (mission §58). Safe to attach to a bug report by construction. */
    fun toSanitizedJson(): String = JSONObject()
        .put("generatedAt", generatedAt)
        .put("application", application.toJson())
        .put("identity", identity.toJson())
        .put("gateway", gateway.toJson())
        .put("replication", replication.toJson())
        .put("history", history.toJson())
        .put("commands", commands.toJson())
        .put("telephonyRelayRunning", telephonyRelayRunning ?: JSONObject.NULL)
        .put("reconciliation", reconciliation?.toJson() ?: JSONObject.NULL)
        .put("gatewayReportsAwaitingAck", gatewayReportsAwaitingAck ?: JSONObject.NULL)
        .put("mirrorVerify", JSONArray(mirrorVerify.map { it.toJson() }))
        .put("blockers", JSONObject()
            .put("primary", prerequisites.primaryBlocker?.let { SyncDiagnosticsText.codeName(it) })
            .put("primaryCategory", prerequisites.primaryBlocker?.category?.name)
            .put("all", JSONArray(prerequisites.blockers
                .sortedBy { it.priority }
                .map { SyncDiagnosticsText.codeName(it) }))
            .put("canUploadRealtime", prerequisites.canUploadRealtime)
            .put("canUploadHistory", prerequisites.canUploadHistory)
            .put("canReceiveCommands", prerequisites.canReceiveCommands))
        .toString(2)

    data class ApplicationSection(
        val appVersion: String?,
        val versionCode: Int?,
        /** A non-reversible device token, already safe. Never a hardware identifier. */
        val deviceIdToken: String?
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("appVersion", appVersion ?: JSONObject.NULL)
            .put("versionCode", versionCode ?: JSONObject.NULL)
            .put("deviceId", deviceIdToken?.let { token(it) } ?: JSONObject.NULL)
    }

    data class IdentitySection(
        val registered: Boolean?,
        val accountLinked: Boolean?,
        val authorized: Boolean?,
        val revoked: Boolean?
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("registered", registered ?: JSONObject.NULL)
            .put("accountLinked", accountLinked ?: JSONObject.NULL)
            .put("authorized", authorized ?: JSONObject.NULL)
            .put("revoked", revoked ?: JSONObject.NULL)
    }

    data class GatewaySection(
        val desired: Boolean?,
        /** Whether the control channel is actually up, as opposed to merely wanted. */
        val controlConnected: Boolean?,
        val lastHeartbeatAt: Long?,
        val lastReconnectAt: Long?
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("desired", desired ?: JSONObject.NULL)
            .put("controlConnected", controlConnected ?: JSONObject.NULL)
            .put("lastHeartbeatAt", lastHeartbeatAt ?: JSONObject.NULL)
            .put("lastReconnectAt", lastReconnectAt ?: JSONObject.NULL)
    }

    data class ReplicationSection(
        val state: ReplicationState,
        val readyCount: Int?,
        val inFlightCount: Int?,
        val retryWaitCount: Int?,
        val deadLetterCount: Int?,
        val ackedCount: Int?,
        val oldestPendingAgeMs: Long?,
        val lastUploadAttemptAt: Long?,
        val lastSuccessfulUploadAt: Long?,
        val lastHttpStatus: Int?,
        val lastBatchSize: Int?,
        val lastAccepted: Int?,
        val lastDuplicates: Int?,
        val lastRejected: Int?,
        /**
         * Which encryption key(s) the not-yet-uploaded events are under (mission §42).
         *
         * One entry per `keyRef`, NULL-grouped entries included. More than one distinct key among
         * OUTSTANDING rows is what a rotation in progress looks like from this device, and it is
         * invisible in every other number here — the counts are identical whichever key is in use.
         */
        val keyRefs: List<KeyRefSection> = emptyList()
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("state", state.name)
            .put("readyCount", readyCount ?: JSONObject.NULL)
            .put("inFlightCount", inFlightCount ?: JSONObject.NULL)
            .put("retryWaitCount", retryWaitCount ?: JSONObject.NULL)
            .put("deadLetterCount", deadLetterCount ?: JSONObject.NULL)
            .put("ackedCount", ackedCount ?: JSONObject.NULL)
            .put("oldestPendingAgeMs", oldestPendingAgeMs ?: JSONObject.NULL)
            .put("lastUploadAttemptAt", lastUploadAttemptAt ?: JSONObject.NULL)
            .put("lastSuccessfulUploadAt", lastSuccessfulUploadAt ?: JSONObject.NULL)
            .put("lastHttpStatus", lastHttpStatus ?: JSONObject.NULL)
            .put("lastBatchSize", lastBatchSize ?: JSONObject.NULL)
            .put("lastAccepted", lastAccepted ?: JSONObject.NULL)
            .put("lastDuplicates", lastDuplicates ?: JSONObject.NULL)
            .put("lastRejected", lastRejected ?: JSONObject.NULL)
            .put("keyRefs", JSONArray(keyRefs.map { it.toJson() }))
    }

    /**
     * One encryption key present among the not-yet-uploaded events (mission §42).
     *
     * A key ID is a UUID, not key material, so it is safe in the export — but it is truncated in the
     * human-readable report for the same reason device ids are: the report is meant to be pasted into
     * a support conversation, and a full identifier invites being used as one.
     */
    data class KeyRefSection(val keyRef: String?, val count: Int) {
        fun toJson(): JSONObject = JSONObject()
            .put("keyRef", keyRef ?: JSONObject.NULL)
            .put("count", count)
    }

    data class HistorySection(
        val state: HistorySyncState,
        val grant: String?,
        val keyVersion: Int?,
        val sessionId: String?,
        val sources: List<HistorySourceSection>
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("state", state.name)
            .put("grant", grant ?: JSONObject.NULL)
            .put("keyVersion", keyVersion ?: JSONObject.NULL)
            .put("sessionId", sessionId ?: JSONObject.NULL)
            .put("sources", JSONArray(sources.map { it.toJson() }))
    }

    /**
     * Per-source history progress (mission §56).
     *
     * `checkpointDate` + `checkpointProviderId` are the compound keyset cursor, reported as a
     * pair because a timestamp alone cannot order messages that share one (mission §27).
     */
    data class HistorySourceSection(
        val source: String,
        val scanned: Int?,
        val enqueued: Int?,
        val acked: Int?,
        val pending: Int?,
        val skipped: Int?,
        val failed: Int?,
        val checkpointDate: Long?,
        val checkpointProviderId: Long?,
        /**
         * The PROVIDER has been read to the end (mission §26: SCAN_COMPLETE).
         *
         * Says nothing about whether the server has the data.
         */
        val scanComplete: Boolean?,
        /**
         * Every history event for this source is ACKed or confirmed duplicate, with no dead
         * letters (mission §26: the only real success).
         *
         * Kept separate from [scanComplete] because conflating them is exactly what the mission
         * forbids, and it was what the only visible "complete" indicator did
         * (`docs/gateway-replication-audit.md`, Blocker 9): a device could show "complete" while
         * nothing had reached GMweb.
         */
        val delivered: Boolean? = null,
        /**
         * The §70 arithmetic for this source's most recent history session, or null when no scan has
         * been accounted for yet.
         *
         * Deliberately its own block rather than an overload of the fields above: `enqueued` above is
         * checkpoint-derived ("history events produced"), while a session's `enqueued` counts ROWS it
         * replicated. Those are related but not equal, so one word cannot mean both — a reader who
         * added the two together would get a number that is not a fact about anything.
         */
        val session: HistorySessionSection? = null
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("source", source)
            .put("scanned", scanned ?: JSONObject.NULL)
            .put("enqueued", enqueued ?: JSONObject.NULL)
            .put("acked", acked ?: JSONObject.NULL)
            .put("pending", pending ?: JSONObject.NULL)
            .put("skipped", skipped ?: JSONObject.NULL)
            .put("failed", failed ?: JSONObject.NULL)
            .put("checkpointDate", checkpointDate ?: JSONObject.NULL)
            .put("checkpointProviderId", checkpointProviderId ?: JSONObject.NULL)
            .put("scanComplete", scanComplete ?: JSONObject.NULL)
            .put("delivered", delivered ?: JSONObject.NULL)
            .put("session", session?.toJson() ?: JSONObject.NULL)
    }

    /**
     * One history session's eligibility arithmetic (mission §25 + §70).
     *
     * `eligible = enqueued + skipped + failed` is the whole point, so the parts and the balance are
     * all exported: a report that showed only `failed` would leave a reader unable to tell a scan
     * that lost nothing from one that never looked.
     */
    data class HistorySessionSection(
        val sessionId: String,
        val eligible: Long,
        val enqueued: Long,
        val skipped: Long,
        val failed: Long,
        val skippedLocalOnly: Long,
        val skippedAskPending: Long,
        val skippedNoDirection: Long,
        val skippedSyncOff: Long,
        /** The PROVIDER was read to the end during this session. */
        val scanExhausted: Boolean,
        /** Still scanning: the session has no finish time yet. */
        val open: Boolean,
        val startedAt: Long,
        val finishedAt: Long,
    ) {
        /** True when every row the session read is explained. */
        val balanced: Boolean get() = eligible == enqueued + skipped + failed

        val residual: Long get() = eligible - (enqueued + skipped + failed)

        fun toJson(): JSONObject = JSONObject()
            .put("sessionId", sessionId)
            .put("eligible", eligible)
            .put("enqueued", enqueued)
            .put("skipped", skipped)
            .put("failed", failed)
            .put("skippedLocalOnly", skippedLocalOnly)
            .put("skippedAskPending", skippedAskPending)
            .put("skippedNoDirection", skippedNoDirection)
            .put("skippedSyncOff", skippedSyncOff)
            .put("scanExhausted", scanExhausted)
            .put("open", open)
            .put("balanced", balanced)
            .put("residual", residual)
            .put("startedAt", startedAt)
            .put("finishedAt", finishedAt)
    }

    data class CommandSection(
        val state: CommandSyncState,
        val received: Int?,
        val claimed: Int?,
        val executing: Int?,
        val completed: Int?,
        val failed: Int?,
        val lastCommandAt: Long?,
        /** A command TYPE (SEND_SMS / MARK_THREAD_READ), never a payload. */
        val lastCommandType: String?
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("state", state.name)
            .put("received", received ?: JSONObject.NULL)
            .put("claimed", claimed ?: JSONObject.NULL)
            .put("executing", executing ?: JSONObject.NULL)
            .put("completed", completed ?: JSONObject.NULL)
            .put("failed", failed ?: JSONObject.NULL)
            .put("lastCommandAt", lastCommandAt ?: JSONObject.NULL)
            .put("lastCommandType", lastCommandType ?: JSONObject.NULL)
    }

    /**
     * The full-mirror verification sweep's progress (mission §35).
     *
     * The recent-window check answers "did anything RECENT fail to replicate?". This answers the
     * question that check structurally cannot: "is there any message at all, however old, with no
     * durable event?" — and, until the sweep finishes, the honest answer is "not yet known", which is
     * why [complete] is reported alongside the counts rather than inferred from them.
     */
    /**
     * The MMS attachment gap, as measured facts rather than an absence (mission §52).
     *
     * WHY `replicationPathExists` IS A FIELD AND NOT AN INFERENCE: reporting "replicated = 0" would
     * read as "nothing to replicate" or as a healthy zero. The honest statement is that the device
     * holds [localAssets] attachments and there is **no protocol to upload any of them**, so the
     * count of replicated attachments is not zero — it is *not a thing that exists yet*. When the
     * endpoint in `docs/gmweb-mms-attachment-handoff.md` is agreed, this flips and the field becomes
     * a real count.
     */
    data class MmsAttachmentSection(
        /** Attachments known locally for MMS messages. Null = not measured. */
        val localAssets: Int?,
        /** Whether any attachment bytes can be replicated at all. False today, structurally. */
        val replicationPathExists: Boolean = false,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("localAssets", localAssets ?: JSONObject.NULL)
            .put("replicationPathExists", replicationPathExists)
    }

    data class MirrorVerifySection(
        val source: String,
        val complete: Boolean,
        val examined: Long,
        val alreadyReplicated: Long,
        val recovered: Long,
        /** Rows never looked up (no provider id) — named, never folded into the healthy count. */
        val skippedNoProviderId: Long,
        val skippedNoDirection: Long,
        val balanced: Boolean,
        val residual: Long,
        val startedAt: Long,
        val updatedAt: Long,
        val completedAt: Long,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("source", source)
            .put("complete", complete)
            .put("examined", examined)
            .put("alreadyReplicated", alreadyReplicated)
            .put("recovered", recovered)
            .put("skippedNoProviderId", skippedNoProviderId)
            .put("skippedNoDirection", skippedNoDirection)
            .put("balanced", balanced)
            .put("residual", residual)
            .put("startedAt", startedAt)
            .put("updatedAt", updatedAt)
            .put("completedAt", completedAt)
    }

    /**
     * The last mirror → outbox reconciliation (mission §70).
     *
     * [recovered] is the number of messages the phone already knew about that had NO durable event
     * — i.e. that would have been lost silently. It is the closest thing this system has to a
     * measured answer for "did we drop anything?", which is why it is reported rather than logged.
     */
    data class ReconcileSection(
        val examined: Int,
        val recovered: Int,
        val windowHours: Int,
        val ranAt: Long
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("examined", examined)
            .put("recovered", recovered)
            .put("windowHours", windowHours)
            .put("ranAt", ranAt)
    }

    companion object {
        /**
         * Defence in depth for the one free-form identifier in the model.
         *
         * Callers are already required to pass a token, but truncating here means that even a
         * mistake cannot turn the export into a bulk identifier leak.
         */
        internal fun token(value: String): String =
            if (value.length <= 8) value else value.take(8)
    }
}
