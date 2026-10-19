package com.autonomousone.messages.gateway.wake

import kotlinx.coroutines.flow.StateFlow

/**
 * Which mechanism tells this device that downstream work is waiting.
 *
 * POLLING is the permanent floor: it is the only source that needs neither platform support nor
 * server support, and the AUTO ladder always ends there.
 */
enum class WakeSourceId { POLLING, SOCKET, FCM }

/** Which mechanism the USER selected. [AUTO] lets the ladder decide. */
enum class WakeMode {
    AUTO,
    POLLING,
    SOCKET,
    FCM;

    companion object {
        /**
         * Tolerant read of the persisted value.
         *
         * Anything unrecognised — a value written by a newer build, an empty string, null — degrades
         * to [AUTO], because the alternative is a device that cannot choose a wake source at all.
         */
        fun parse(raw: String?): WakeMode = when (raw?.trim()?.uppercase()) {
            POLLING.name -> POLLING
            SOCKET.name -> SOCKET
            FCM.name -> FCM
            else -> AUTO
        }
    }
}

/** Live state of the WebSocket transport. Counts and enums only. */
enum class WakeSocketState { CLOSED, CONNECTING, OPEN }

/** Whether this client is pinning the server certificate, and whether a pin ever mismatched. */
enum class WakePinning { DISABLED, ENABLED, MISMATCH }

/** Why a [WakeSource] is not usable right now. Reported, never inferred. */
enum class WakeUnavailable {
    /** No server origin, no token. */
    NOT_CONFIGURED,

    /** e.g. FCM selected on a device without Play Services. */
    NO_PLATFORM_SUPPORT,

    /** The server refused our credentials. */
    AUTH_REJECTED,

    /** Connect/upgrade failed. */
    TRANSPORT_FAILED,

    /** The user turned this mechanism off. */
    DISABLED_BY_USER
}

/**
 * What the running wake mechanism is doing. Counts, timestamps and enums only — never a task id, a
 * recipient or a message body.
 */
data class WakeHealth(
    val id: WakeSourceId,
    val active: Boolean,
    val unavailable: WakeUnavailable? = null,
    val lastWakeAt: Long = 0L,
    val lastAttemptAt: Long = 0L,
    val consecutiveFailures: Int = 0,
    /** Wake-ups delivered since start. Counts only — no ids, no recipients. */
    val wakeCount: Long = 0L,
    // ── WebSocket detail (STAGE-4 §5). Enums, counts and timestamps only. ──
    val socketState: WakeSocketState = WakeSocketState.CLOSED,
    val lastSocketChangeAt: Long = 0L,
    val reconnectCount: Int = 0,
    /** Frames the one-second wake floor suppressed. */
    val suppressedWakes: Long = 0L,
    /** Frames that were not `{"t":"wake"}`. Counted, never parsed for other fields. */
    val unknownFrames: Long = 0L,
    val activity: WakeActivity = WakeActivity.IDLE,
    val activityReason: WakeActivityReason = WakeActivityReason.NONE,
    val pinning: WakePinning = WakePinning.DISABLED,
    val pingIntervalMs: Long = 0L
)

/**
 * A mechanism that says "there may be downstream work", nothing more (ADR-008).
 *
 * **A WakeSource never delivers the work.** Everything that fetches, validates, sends, ACKs or
 * persists stays in the existing authenticated `GET /gateway/pull` + `POST /gateway/ack` path. That
 * one-bit rule is what keeps the security model unchanged when the transport changes: a socket or an
 * FCM ping that carries no content cannot leak content. Implementations MUST NOT carry task payloads,
 * recipients or message bodies.
 */
interface WakeSource {

    val id: WakeSourceId

    /** True when this source can run on this device, with this configuration, now. */
    fun isAvailable(): Boolean

    /**
     * Begin delivering wake-ups. Idempotent against an already-started source.
     *
     * [onWake] may be called from any thread and must be cheap: it is expected to poke a conflated
     * channel (`OutboxPoller.retryNow()`), not to do work.
     */
    fun start(onWake: () -> Unit)

    fun stop()

    val health: StateFlow<WakeHealth>
}
