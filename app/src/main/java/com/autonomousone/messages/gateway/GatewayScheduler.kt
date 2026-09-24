package com.autonomousone.messages.gateway

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.autonomousone.messages.sms.SmsSender
import com.autonomousone.messages.utils.SecureStore
import java.util.concurrent.TimeUnit

/**
 * Scheduled sends exposed to external projects over the gateway REST API.
 *
 * Contract:
 *  POST /api/v1/sms/schedule   { phone, message, sendAt (epoch ms) | delaySeconds, [idempotencyKey] }
 *    → 202 { scheduleId, sendAt, status: "scheduled" }
 *  GET  /api/v1/sms/schedule/{scheduleId} → { scheduleId, status, sentAt?, failedReason? }
 *  DELETE /api/v1/sms/schedule/{scheduleId} → { ok } (only while pending)
 *  GET  /api/v1/sms/schedule   → { schedules: [...] }  (recent entries)
 *
 * Delivery uses WorkManager's persistent store, so a scheduled message fires
 * even after reboot / process death. The actual send goes through the same
 * [SmsSender] pipeline as everything else (SIM/SMSC prefs + delivery reports).
 */
object GatewayScheduler {

    private const val TAG = "GATEWAY_SCHED"
    private const val PREFS = "gateway_schedule_prefs"
    private const val KEY_INDEX = "schedule_index"

    // The recipient and body deliberately have NO WorkManager keys: they are not passed through
    // WorkManager's plainly-persisted input data. They live only in the encrypted registry.
    private const val KEY_SCHEDULE_ID = "schedule_id"

    /**
     * The pre-send decision (mission §47/§78), pure so it can be asserted without Android.
     *
     * Extracted because the at-most-once rule is the whole safety property here, and the code that
     * implements it needs a `Context`. Keeping the decision separate means the rule is tested
     * rather than reasoned about.
     */
    internal object ScheduledSendGate {

        sealed interface Decision {
            /** Cleared to submit. */
            data object Send : Decision

            /** Nothing to do: missing, cancelled, already sent, or already failed. */
            data object SkipNotScheduled : Decision

            /**
             * A previous attempt already started a submit, so the SMS may have left the device.
             * Never repeated — a duplicate SMS is irreversible (mission §78).
             */
            data object SkipPossiblyAlreadySent : Decision
        }

        fun decide(entry: Entry?): Decision = when {
            entry == null -> Decision.SkipNotScheduled
            entry.status != "scheduled" -> Decision.SkipNotScheduled
            entry.submittedOnce -> Decision.SkipPossiblyAlreadySent
            else -> Decision.Send
        }
    }

    /** Max in-flight scheduled jobs — protects the device from abuse. */
    const val MAX_PENDING = 200

    /** WorkManager attempts before a scheduled send is reported failed rather than retried. */
    const val MAX_SEND_ATTEMPTS = 3

    /**
     * The attempt started a native submit and never learned its outcome, so the SMS may or may not
     * have been sent. Never retried (mission §78) and named distinctly so the state is unmistakable
     * in diagnostics rather than looking like an ordinary dispatch failure.
     */
    const val REASON_INTERRUPTED_AFTER_SUBMIT = "interrupted_after_submit"

    data class Entry(
        val scheduleId: String,
        val phone: String,
        val message: String,
        val sendAt: Long,
        val createdAt: Long,
        var status: String = "scheduled", // scheduled | sent | failed | cancelled
        var sentAt: Long = 0L,
        var failedReason: String? = null,
        /**
         * True once a native submit has been started for this entry (mission §47/§78).
         *
         * Written and COMMITTED before `sendForResult` is called, so a retry knows the SMS may
         * already have left the device. Without it, a send that succeeded but returned null — or a
         * process death between the send and the status write — sent the message a second time on
         * the next WorkManager attempt (`docs/gateway-replication-audit.md`, audit §78 risk).
         */
        var submittedOnce: Boolean = false
    )

    // ── Registry (SharedPreferences — survives reboot) ──────────────────────
    //
    // The recipient and the message body are the two most sensitive things this feature holds, so
    // they are stored ENCRYPTED (AES-256-GCM, Keystore-backed) rather than in plaintext. The keys
    // `phoneC`/`messageC` hold ciphertext; the legacy plaintext keys are still READ so entries
    // written by an older build keep working, but they are never written again — the first save
    // after the upgrade replaces them.

    private fun loadIndex(context: Context): MutableMap<String, Entry> {
        val map = mutableMapOf<String, Entry>()
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val arr = org.json.JSONArray(prefs.getString(KEY_INDEX, "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                map[o.getString("scheduleId")] = Entry(
                    scheduleId = o.getString("scheduleId"),
                    phone = readSecret(o, "phoneC", "phone"),
                    message = readSecret(o, "messageC", "message"),
                    sendAt = o.getLong("sendAt"),
                    createdAt = o.getLong("createdAt"),
                    status = o.optString("status", "scheduled"),
                    sentAt = o.optLong("sentAt", 0L),
                    failedReason = o.optString("failedReason", "").ifBlank { null },
                    submittedOnce = o.optBoolean("submittedOnce", false)
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "loadIndex failed", e)
        }
        return map
    }

    /** Prefers the encrypted field; falls back to a legacy plaintext value from an older build. */
    private fun readSecret(o: org.json.JSONObject, encryptedKey: String, legacyKey: String): String {
        val encrypted = o.optString(encryptedKey, "")
        if (encrypted.isNotEmpty()) {
            SecureStore.decrypt(encrypted)?.let { return it }
            // A ciphertext we cannot decrypt is NOT silently replaced with a guessed value; the
            // entry keeps an empty recipient so it fails visibly rather than sending to nobody.
            Log.w(TAG, "could not decrypt $encryptedKey; the entry cannot be sent")
            return ""
        }
        return o.optString(legacyKey, "")
    }

    /**
     * Persists the registry, optionally waiting for the write to land.
     *
     * @return true when the registry was written. False means NOTHING was written — in particular
     *   this never falls back to storing the recipient or body in plaintext (mission §41: no
     *   plaintext fallback, ever), so a Keystore failure makes the caller refuse the operation
     *   rather than downgrade it.
     *
     * [synchronous] is REQUIRED for the pre-send marker: `apply()` returns before the write is on
     * disk, so a marker written that way can be lost in exactly the crash window it exists to cover
     * — the process dying between the send and the status update.
     */
    private fun saveIndex(context: Context, index: Map<String, Entry>, synchronous: Boolean = false): Boolean {
        return try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val arr = org.json.JSONArray()
            var encryptionFailed = false
            index.values.sortedByDescending { it.createdAt }.take(100).forEach { e ->
                val phoneC = SecureStore.encrypt(e.phone)
                val messageC = SecureStore.encrypt(e.message)
                if (phoneC == null || messageC == null) {
                    encryptionFailed = true
                    return@forEach
                }
                arr.put(
                    org.json.JSONObject()
                        .put("scheduleId", e.scheduleId)
                        // Encrypted at rest; the legacy `phone`/`message` keys are never written.
                        .put("phoneC", phoneC)
                        .put("messageC", messageC)
                        .put("sendAt", e.sendAt)
                        .put("createdAt", e.createdAt)
                        .put("status", e.status)
                        .put("sentAt", e.sentAt)
                        .put("failedReason", e.failedReason ?: "")
                        .put("submittedOnce", e.submittedOnce)
                )
            }
            if (encryptionFailed) {
                Log.e(TAG, "secure storage unavailable — refusing to persist in plaintext")
                return false
            }
            val editor = prefs.edit().putString(KEY_INDEX, arr.toString())
            if (synchronous) {
                // commit() is DELIBERATE here, against lint's ApplySharedPref advice. That rule
                // exists because a blocking write on the main thread can ANR — but this runs in a
                // WorkManager worker, and the write is the at-most-once marker. `apply()` returns
                // before the write reaches disk, so the marker could be lost in exactly the crash
                // window it exists to cover, and the SMS would be sent a second time.
                @Suppress("ApplySharedPref")
                editor.commit()
            } else {
                editor.apply()
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "saveIndex failed", e)
            false
        }
    }

    /**
     * Durably records "a submit is about to start" and reports whether it landed.
     *
     * FAIL CLOSED: if the marker cannot be committed, the caller must NOT send. An unrecorded
     * submit is indistinguishable from no submit at all on the next attempt, which is precisely the
     * duplicate this guards against — so declining to send is the safe direction (mission §78: a
     * duplicate SMS is irreversible).
     */
    private fun markSubmittedBeforeSend(context: Context, entry: Entry): Boolean {
        val index = loadIndex(context)
        val existing = index[entry.scheduleId] ?: return false
        if (existing.status != "scheduled") return false
        index[entry.scheduleId] = existing.copy(submittedOnce = true)
        saveIndex(context, index, synchronous = true)
        // Read back: `commit()` returning true is the evidence the marker is durable.
        return get(context, entry.scheduleId)?.submittedOnce == true
    }

    fun get(context: Context, scheduleId: String): Entry? =
        loadIndex(context)[scheduleId]

    fun list(context: Context): List<Entry> =
        loadIndex(context).values.sortedByDescending { it.createdAt }

    fun pendingCount(context: Context): Int =
        loadIndex(context).values.count { it.status == "scheduled" }

    private fun update(context: Context, entry: Entry) {
        val index = loadIndex(context)
        index[entry.scheduleId] = entry
        saveIndex(context, index)
    }

    // ── Scheduling ───────────────────────────────────────────────────────────

    /**
     * Queues an SMS for delivery at [sendAtMillis]. Returns the schedule id.
     * Idempotency: same (phone+message+sendAt) within the registry returns the
     * existing entry instead of double-scheduling.
     */
    @Synchronized
    fun schedule(context: Context, phone: String, message: String, sendAtMillis: Long): Pair<Entry, Boolean> {
        // Idempotency check on exact triple.
        loadIndex(context).values.firstOrNull {
            it.phone == phone && it.message == message && it.sendAt == sendAtMillis && it.status == "scheduled"
        }?.let { return it to false }

        if (pendingCount(context) >= MAX_PENDING) {
            throw IllegalStateException("too_many_pending")
        }

        val scheduleId = "sch_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val entry = Entry(
            scheduleId = scheduleId,
            phone = phone,
            message = message,
            sendAt = sendAtMillis,
            createdAt = System.currentTimeMillis()
        )
        val index = loadIndex(context)
        index[scheduleId] = entry
        if (!saveIndex(context, index)) {
            // Nothing was persisted — and in particular the recipient and body were NOT written in
            // plaintext (mission §41). Refuse the request rather than accept a schedule that cannot
            // be stored securely.
            throw IllegalStateException("secure_storage_unavailable")
        }

        val delay = (sendAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        val request = androidx.work.OneTimeWorkRequestBuilder<SendWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            // ONLY the schedule id goes into WorkManager. Its input data is persisted in
            // WorkManager's own database, so putting the recipient and the message body here would
            // store them in plaintext a second time — defeating the encryption above. The worker
            // reads them from the (encrypted) registry by id.
            .setInputData(workDataOf(KEY_SCHEDULE_ID to scheduleId))
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(scheduleId, ExistingWorkPolicy.REPLACE, request)

        return entry to true
    }

    /** Cancels a pending schedule. Returns true when it was still queued. */
    @Synchronized
    fun cancel(context: Context, scheduleId: String): Boolean {
        val entry = get(context, scheduleId) ?: return false
        if (entry.status != "scheduled") return false
        WorkManager.getInstance(context).cancelUniqueWork(scheduleId)
        update(context, entry.copy(status = "cancelled"))
        return true
    }

    /**
     * The worker that fires at send-time. Runs through [SmsSender.sendForResult]
     * so delivery reports + SIM preferences behave exactly like manual sends.
     */
    class SendWorker(appContext: Context, params: WorkerParameters) :
        CoroutineWorker(appContext, params) {

        override suspend fun doWork(): Result {
            val scheduleId = inputData.getString(KEY_SCHEDULE_ID) ?: return Result.failure()

            val current = GatewayScheduler.get(applicationContext, scheduleId)
            // The at-most-once rule lives in ScheduledSendGate so it is unit-tested rather than
            // reasoned about here.
            when (ScheduledSendGate.decide(current)) {
                ScheduledSendGate.Decision.SkipNotScheduled ->
                    return Result.success() // cancelled, already sent, or already failed

                ScheduledSendGate.Decision.SkipPossiblyAlreadySent -> {
                    // A previous attempt started a submit, so this SMS may have left the device.
                    // WorkManager retried because that attempt did not report success — exactly the
                    // state a successful send with a lost result leaves behind. Sending again would
                    // be a duplicate real-world action, so decline and report it as unresolved.
                    Log.w(TAG, "Scheduled SMS $scheduleId already submitted — NOT resending")
                    GatewayScheduler.update(
                        applicationContext,
                        requireNotNull(current).copy(
                            status = "failed",
                            failedReason = REASON_INTERRUPTED_AFTER_SUBMIT
                        )
                    )
                    return Result.success()
                }

                ScheduledSendGate.Decision.Send -> Unit
            }
            val entry = requireNotNull(current)
            // Read from the registry (encrypted at rest), never from WorkManager's input data —
            // which is persisted in plaintext in WorkManager's own database.
            val phone = entry.phone
            val message = entry.message
            if (phone.isBlank() || message.isBlank()) {
                // A ciphertext we could not decrypt, so the recipient is unknown. Sending is
                // impossible and guessing would be worse: report it as unresolved.
                Log.e(TAG, "Scheduled SMS $scheduleId has unreadable content; not sending")
                GatewayScheduler.update(
                    applicationContext,
                    entry.copy(status = "failed", failedReason = "content_unreadable")
                )
                return Result.success()
            }

            // Record the marker BEFORE the side effect, and refuse to send if it cannot be made
            // durable: an unrecorded submit is indistinguishable from no submit on the next attempt.
            if (!GatewayScheduler.markSubmittedBeforeSend(applicationContext, entry)) {
                Log.w(TAG, "Scheduled SMS $scheduleId: could not record the submit marker; not sending")
                return Result.retry()
            }

            val sentId = SmsSender(applicationContext).sendForResult(phone, message)
            return if (sentId != null) {
                GatewayScheduler.update(
                    applicationContext,
                    GatewayScheduler.get(applicationContext, scheduleId)
                        ?.copy(status = "sent", sentAt = System.currentTimeMillis())
                        ?: entry.copy(status = "sent", sentAt = System.currentTimeMillis())
                )
                // The phone number is deliberately NOT logged (mission §43): a log line is a
                // durable copy of a recipient's number.
                Log.i(TAG, "Scheduled SMS $scheduleId sent")
                Result.success()
            } else {
                if (runAttemptCount < MAX_SEND_ATTEMPTS) {
                    Log.w(TAG, "Scheduled SMS $scheduleId failed, retry #$runAttemptCount")
                    Result.retry()
                } else {
                    GatewayScheduler.update(
                        applicationContext,
                        entry.copy(status = "failed", failedReason = "dispatch_failed")
                    )
                    Result.failure()
                }
            }
        }
    }
}
