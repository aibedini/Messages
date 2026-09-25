package com.autonomousone.messages.gateway

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.Constraints
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters

/**
 * What to do when the platform refuses to let the gateway start in the foreground (Workstream F).
 *
 * A refused start is a normal platform answer, not a fault: Android 15+ forbids a boot receiver
 * from launching a `dataSync` foreground service (and Android 12+ restricts background starts
 * generally). Before this existed, `BootGatewayReceiver` called `startForegroundService`
 * unconditionally, so the refusal took the form of a thrown
 * `ForegroundServiceStartNotAllowedException` — which the policy in
 * [GatewayForegroundStartPolicy] avoids for the documented cases and this object handles for the
 * undocumented ones.
 *
 * WHY WORKMANAGER IS THE RIGHT DEFERRAL, GIVEN THIS APP'S DECISION
 *
 * The runtime decision for history backfill is bounded, checkpointed units whose correctness never
 * depends on one process surviving (docs/production-validation-plan.md §7). So deferring is not a
 * degraded mode: WorkManager already owns the durable retry story, it obeys the platform's
 * execution windows, and it survives reboots. The alternative — catching the exception and doing
 * nothing — is what would actually break the product, because this app has no periodic scheduler
 * for the gateway: the foreground service is the only thing that revives it, so a silently dropped
 * boot start leaves the gateway dead until the user opens the app.
 */
object GatewayStartDeferral {

    private const val TAG = "GW_START"

    /** Unique, so a boot storm or repeated refusals cannot pile up duplicate attempts. */
    const val WORK_NAME = "gateway-start-rearm"

    fun defer(context: Context) {
        val request = OneTimeWorkRequestBuilder<GatewayReArmWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        Log.i(TAG, "gateway start deferred to WorkManager as '$WORK_NAME'")
    }
}

/**
 * Retries the foreground start inside WorkManager's execution window.
 *
 * `RETRY` rather than `BOOT` is passed as the reason: a worker is not a boot receiver, so it is not
 * subject to the boot-specific type restriction, and it should not claim to be. If the start is
 * still refused (for example the app is in a restricted background state), returning `Result.retry()`
 * lets WorkManager back off and try again later instead of dropping the intent.
 */
class GatewayReArmWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // deferOnFailure = false: this worker's own Result.retry() is the deferral. Letting
        // startGateway defer as well would have each enqueueing work for the other.
        val started = GatewayService.startGateway(
            applicationContext,
            GatewayForegroundStartPolicy.StartReason.RETRY,
            deferOnFailure = false
        )
        return if (started) Result.success() else Result.retry()
    }
}
