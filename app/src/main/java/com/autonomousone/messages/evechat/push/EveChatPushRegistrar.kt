package com.autonomousone.messages.evechat.push

import android.content.Context
import android.os.Build
import com.autonomousone.messages.BuildConfig
import com.autonomousone.messages.evechat.ApiResult
import com.autonomousone.messages.evechat.EveChatApiClient
import com.autonomousone.messages.evechat.EveChatAuth
import com.autonomousone.messages.evechat.EveChatLog
import com.autonomousone.messages.evechat.EveChatPreferences
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.TimeZone

/**
 * Keeps this device's FCM token registered with the EveChat server for the signed-in agent.
 *
 * Firebase auto-init is off in the manifest and only switched on while an EveChat session
 * exists, so people who use the app purely for SMS never get an FCM token. Registration runs in
 * [EveChatPushRegistrationWorker] (network-constrained, retried with backoff); a server that has
 * no push endpoint yet is left alone for a day and delivery falls back to socket + polling.
 */
class EveChatPushRegistrar internal constructor(
    private val context: Context,
    private val prefs: EveChatPreferences,
    private val api: EveChatApiClient,
    private val auth: EveChatAuth,
    private val scope: CoroutineScope,
    private val signedIn: () -> Boolean,
    private val agentId: () -> String?
) {
    enum class Status { DISABLED, NOT_CONFIGURED, NO_PLAY_SERVICES, SERVER_UNSUPPORTED, REGISTERING, ACTIVE, FAILED }

    enum class Outcome { SUCCESS, RETRY, STOP }

    private val _status = MutableStateFlow(Status.DISABLED)
    val status: StateFlow<Status> = _status.asStateFlow()

    val lastPushAt: Long get() = prefs.lastPushAt

    /** null when FCM can be used on this build and device. */
    fun unavailableReason(): Status? = when {
        FirebaseApp.getApps(context).isEmpty() -> Status.NOT_CONFIGURED
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) != ConnectionResult.SUCCESS ->
            Status.NO_PLAY_SERVICES
        else -> null
    }

    /** App start / foreground: cheap check, enqueues work only when the server's copy is stale. */
    fun ensureRegistered() {
        scope.launch {
            if (!signedIn()) {
                _status.value = Status.DISABLED
                return@launch
            }
            unavailableReason()?.let {
                _status.value = it
                return@launch
            }
            setAutoInit(true)
            if (serverMarkedUnsupported()) {
                _status.value = Status.SERVER_UNSUPPORTED
                return@launch
            }
            val record = PushRegistrationRecord.fromJson(prefs.pushRecord)
            val needed = PushRegistrationPolicy.needsRegistration(
                record, prefs.fcmToken, prefs.serverUrl, agentId(), BuildConfig.VERSION_CODE, System.currentTimeMillis()
            )
            if (needed) {
                _status.value = Status.REGISTERING
                EveChatPushRegistrationWorker.enqueue(context, replace = false)
            } else {
                _status.value = Status.ACTIVE
            }
        }
    }

    fun onSignedIn() {
        prefs.pushUnsupported = null
        scope.launch {
            unavailableReason()?.let {
                _status.value = it
                EveChatLog.i("push", "push unavailable", "reason" to it)
                return@launch
            }
            setAutoInit(true)
            _status.value = Status.REGISTERING
            EveChatPushRegistrationWorker.enqueue(context, replace = true)
        }
    }

    /** FirebaseMessagingService callback: tokens rotate (reinstall, data clear, FCM-side refresh). */
    fun onNewToken(token: String) {
        prefs.fcmToken = token
        EveChatLog.i("push", "new FCM token issued")
        if (signedIn()) {
            _status.value = Status.REGISTERING
            EveChatPushRegistrationWorker.enqueue(context, replace = true)
        }
    }

    suspend fun registerNow(): Outcome {
        if (!signedIn()) return Outcome.STOP
        unavailableReason()?.let {
            _status.value = it
            return Outcome.STOP
        }
        val token = try {
            withTimeout(TOKEN_TIMEOUT_MS) { FirebaseMessaging.getInstance().token.await() }
        } catch (e: Exception) {
            EveChatLog.w("push", "FCM token unavailable", e)
            _status.value = Status.FAILED
            return Outcome.RETRY
        }
        prefs.fcmToken = token
        val server = prefs.serverUrl
        val body = PushDeviceRegistration(
            deviceId = prefs.deviceId,
            token = token,
            appVersion = BuildConfig.VERSION_NAME,
            appBuild = BuildConfig.VERSION_CODE,
            osVersion = "Android ${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT})",
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            locale = Locale.getDefault().toLanguageTag(),
            timezone = TimeZone.getDefault().id
        ).toJson()

        return when (val r = auth.authorized { api.registerPushDevice(it, body) }) {
            is ApiResult.Ok -> {
                prefs.pushRecord = PushRegistrationRecord(
                    token, server, agentId().orEmpty(), BuildConfig.VERSION_CODE, System.currentTimeMillis()
                ).toJson()
                prefs.pushUnsupported = null
                _status.value = Status.ACTIVE
                EveChatLog.i("push", "device registered for push")
                Outcome.SUCCESS
            }
            is ApiResult.Err -> when {
                PushRegistrationPolicy.isUnsupported(r.error.httpStatus) -> {
                    prefs.pushUnsupported = "$server|${System.currentTimeMillis() + PushRegistrationPolicy.UNSUPPORTED_RETRY_MS}"
                    _status.value = Status.SERVER_UNSUPPORTED
                    EveChatLog.i("push", "server has no push endpoint; using socket and polling", "status" to r.error.httpStatus)
                    Outcome.STOP
                }
                r.error.retryable -> {
                    _status.value = Status.FAILED
                    Outcome.RETRY
                }
                else -> {
                    _status.value = Status.FAILED
                    EveChatLog.w("push", "registration rejected", null, "kind" to r.error.kind, "status" to r.error.httpStatus)
                    Outcome.STOP
                }
            }
        }
    }

    /**
     * Best effort, bounded: tell the server to forget this device, then rotate the FCM token so
     * a server that missed the call can only reach a dead token. Uses the raw access token on
     * purpose — a refresh here could re-enter session expiry during sign-out.
     */
    suspend fun onSignedOut(accessToken: String?) {
        EveChatPushRegistrationWorker.cancel(context)
        if (accessToken != null && prefs.pushRecord != null) {
            val r = withTimeoutOrNull(SIGN_OUT_TIMEOUT_MS) { api.unregisterPushDevice(accessToken, prefs.deviceId) }
            EveChatLog.i("push", "device unregistered", "ok" to (r is ApiResult.Ok))
        }
        if (unavailableReason() == null) {
            setAutoInit(false)
            withTimeoutOrNull(SIGN_OUT_TIMEOUT_MS) {
                runCatching { FirebaseMessaging.getInstance().deleteToken().await() }
                    .onFailure { EveChatLog.w("push", "FCM token deletion failed", it) }
            }
        }
        _status.value = Status.DISABLED
    }

    fun recordDelivery() {
        prefs.lastPushAt = System.currentTimeMillis()
    }

    private fun serverMarkedUnsupported(): Boolean {
        val raw = prefs.pushUnsupported ?: return false
        val server = raw.substringBeforeLast('|')
        val until = raw.substringAfterLast('|').toLongOrNull() ?: 0L
        if (server == prefs.serverUrl && System.currentTimeMillis() < until) return true
        prefs.pushUnsupported = null
        return false
    }

    private fun setAutoInit(enabled: Boolean) {
        runCatching { FirebaseMessaging.getInstance().isAutoInitEnabled = enabled }
            .onFailure { EveChatLog.w("push", "could not change FCM auto-init", it) }
    }

    private companion object {
        const val TOKEN_TIMEOUT_MS = 30_000L
        const val SIGN_OUT_TIMEOUT_MS = 5_000L
    }
}
