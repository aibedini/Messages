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

    private const val KEY_PHONE = "phone"
    private const val KEY_MESSAGE = "message"
    private const val KEY_SCHEDULE_ID = "schedule_id"

    /** Max in-flight scheduled jobs — protects the device from abuse. */
    const val MAX_PENDING = 200

    data class Entry(
        val scheduleId: String,
        val phone: String,
        val message: String,
        val sendAt: Long,
        val createdAt: Long,
        var status: String = "scheduled", // scheduled | sent | failed | cancelled
        var sentAt: Long = 0L,
        var failedReason: String? = null
    )

    // ── Registry (SharedPreferences — survives reboot) ──────────────────────

    private fun loadIndex(context: Context): MutableMap<String, Entry> {
        val map = mutableMapOf<String, Entry>()
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val arr = org.json.JSONArray(prefs.getString(KEY_INDEX, "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                map[o.getString("scheduleId")] = Entry(
                    scheduleId = o.getString("scheduleId"),
                    phone = o.getString("phone"),
                    message = o.getString("message"),
                    sendAt = o.getLong("sendAt"),
                    createdAt = o.getLong("createdAt"),
                    status = o.optString("status", "scheduled"),
                    sentAt = o.optLong("sentAt", 0L),
                    failedReason = o.optString("failedReason", "").ifBlank { null }
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "loadIndex failed", e)
        }
        return map
    }

    private fun saveIndex(context: Context, index: Map<String, Entry>) {
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val arr = org.json.JSONArray()
            index.values.sortedByDescending { it.createdAt }.take(100).forEach { e ->
                arr.put(
                    org.json.JSONObject()
                        .put("scheduleId", e.scheduleId)
                        .put("phone", e.phone)
                        .put("message", e.message)
                        .put("sendAt", e.sendAt)
                        .put("createdAt", e.createdAt)
                        .put("status", e.status)
                        .put("sentAt", e.sentAt)
                        .put("failedReason", e.failedReason ?: "")
                )
            }
            prefs.edit().putString(KEY_INDEX, arr.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "saveIndex failed", e)
        }
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
        saveIndex(context, index)

        val delay = (sendAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        val request = androidx.work.OneTimeWorkRequestBuilder<SendWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(
                workDataOf(
                    KEY_SCHEDULE_ID to scheduleId,
                    KEY_PHONE to phone,
                    KEY_MESSAGE to message
                )
            )
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
            val phone = inputData.getString(KEY_PHONE) ?: return Result.failure()
            val message = inputData.getString(KEY_MESSAGE) ?: return Result.failure()

            val current = GatewayScheduler.get(applicationContext, scheduleId)
                ?: return Result.success() // cancelled while waiting
            if (current.status != "scheduled") return Result.success()

            val sentId = SmsSender(applicationContext).sendForResult(phone, message)
            return if (sentId != null) {
                GatewayScheduler.update(
                    applicationContext,
                    current.copy(status = "sent", sentAt = System.currentTimeMillis())
                )
                Log.i(TAG, "Scheduled SMS $scheduleId -> $phone sent")
                Result.success()
            } else {
                if (runAttemptCount < 3) {
                    Log.w(TAG, "Scheduled SMS $scheduleId failed, retry #$runAttemptCount")
                    Result.retry()
                } else {
                    GatewayScheduler.update(
                        applicationContext,
                        current.copy(status = "failed", failedReason = "dispatch_failed")
                    )
                    Result.failure()
                }
            }
        }
    }
}
