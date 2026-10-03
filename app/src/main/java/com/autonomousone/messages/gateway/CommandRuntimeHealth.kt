package com.autonomousone.messages.gateway

/**
 * What the RUNNING build last advertised to GMweb — liveness and identity, independent of telemetry.
 *
 * **Why this is separate from [TelemetryHealth].** They answer different questions. Telemetry asks
 * "did a device/SIM report reach the server?"; this asks "is this build talking to GMweb at all, and
 * which build is it advertising?". Telemetry is allowed to fail; the command channel is not, and
 * conflating the two is how a phone could look offline while it was polling every two seconds.
 *
 * Privacy: only timings, an HTTP status, the installed version NAME/CODE and a COUNT of advertised
 * command types. Never a response body, a ciphertext, a command plaintext or a credential.
 */
object CommandRuntimeHealth {

    data class Snapshot(
        val lastClaimAttemptAt: Long?,
        val lastClaimHttpStatus: Int?,
        val lastClaimSuccessAt: Long?,
        val lastAdvertisedVersionName: String?,
        val lastAdvertisedVersionCode: Long?,
        val advertisedCommandTypeCount: Int,
        val advertisedCommandTypes: List<String>
    ) {
        /** True when a claim carrying runtime metadata has been accepted at least once. */
        val runtimeEverAccepted: Boolean get() = lastClaimSuccessAt != null
    }

    private val lock = Any()
    private var lastClaimAttemptAt: Long? = null
    private var lastClaimHttpStatus: Int? = null
    private var lastClaimSuccessAt: Long? = null
    private var versionName: String? = null
    private var versionCode: Long? = null
    private var commandTypes: List<String> = emptyList()

    /**
     * Called immediately before a claim is sent, with the runtime metadata THAT request carries.
     *
     * Recorded before the request so a claim that never returns still shows what was advertised — the
     * case that used to be invisible.
     */
    fun onClaimAttempt(
        versionName: String,
        versionCode: Long,
        commandTypes: List<String>,
        at: Long = System.currentTimeMillis()
    ) = synchronized(lock) {
        lastClaimAttemptAt = at
        this.versionName = versionName
        this.versionCode = versionCode
        this.commandTypes = commandTypes.toList()
    }

    /** The claim's HTTP result. Success means a 2xx — anything else is a failed advertisement. */
    fun onClaimResult(
        httpStatus: Int?,
        at: Long = System.currentTimeMillis()
    ) = synchronized(lock) {
        lastClaimHttpStatus = httpStatus
        if (httpStatus != null && httpStatus in 200..299) lastClaimSuccessAt = at
    }

    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(
            lastClaimAttemptAt = lastClaimAttemptAt,
            lastClaimHttpStatus = lastClaimHttpStatus,
            lastClaimSuccessAt = lastClaimSuccessAt,
            lastAdvertisedVersionName = versionName,
            lastAdvertisedVersionCode = versionCode,
            advertisedCommandTypeCount = commandTypes.size,
            advertisedCommandTypes = commandTypes
        )
    }

    internal fun resetForTest() = synchronized(lock) {
        lastClaimAttemptAt = null
        lastClaimHttpStatus = null
        lastClaimSuccessAt = null
        versionName = null
        versionCode = null
        commandTypes = emptyList()
    }
}
