package com.autonomousone.messages.gateway.health

/**
 * How a health value should LOOK, without knowing anything about Compose.
 *
 * The card is a colour-coded list, and "which colour, and is that alarming?" is a decision
 * with user consequences: a red row for a gateway the user deliberately switched off is a
 * bug report waiting to happen. Keeping the mapping here means it is unit-tested instead of
 * being an inline `when` in a composable that nobody can assert on.
 */
enum class HealthTone {
    /** Working. */
    GOOD,

    /** Working, but something is stale or a retry is in flight. */
    WARN,

    /** A failure retrying cannot fix, or a persistent one. */
    BAD,

    /** Not known yet, or a state the app cannot judge. */
    NEUTRAL,

    /** Deliberately not running. Never alarming. */
    IDLE
}

object GatewayHealthPresentation {

    /**
     * The overall verdict's tone.
     *
     * OFFLINE is IDLE rather than BAD on purpose: the most common reason for it is that the
     * user turned the gateway off, and shouting at someone about their own decision trains
     * them to ignore the colour that does matter.
     */
    fun tone(overall: GatewayOverallHealth): HealthTone = when (overall) {
        GatewayOverallHealth.HEALTHY -> HealthTone.GOOD
        GatewayOverallHealth.DEGRADED -> HealthTone.WARN
        GatewayOverallHealth.ERROR -> HealthTone.BAD
        GatewayOverallHealth.STARTING -> HealthTone.NEUTRAL
        GatewayOverallHealth.OFFLINE -> HealthTone.IDLE
    }

    /** The headline on the card. */
    fun headline(overall: GatewayOverallHealth): String = when (overall) {
        GatewayOverallHealth.HEALTHY -> "Healthy"
        GatewayOverallHealth.DEGRADED -> "Degraded"
        GatewayOverallHealth.ERROR -> "Not working"
        GatewayOverallHealth.STARTING -> "Starting"
        GatewayOverallHealth.OFFLINE -> "Off"
    }

    /**
     * One sentence the user can act on.
     *
     * Deliberately the SAME vocabulary as the diagnostic report: a card that says one thing
     * and a report that says another is how two people end up debugging different problems.
     */
    fun summary(conclusion: GatewayConclusion): String = when (conclusion) {
        GatewayConclusion.NONE -> "Everything the gateway needs is up to date."
        GatewayConclusion.DISABLED -> "The gateway is off."
        GatewayConclusion.NO_NETWORK -> "No validated internet connection right now."
        GatewayConclusion.SERVER_UNREACHABLE -> "The server address resolved, but nothing accepted the connection."
        GatewayConclusion.DNS_PROBLEM -> "The GMweb host name did not resolve."
        GatewayConclusion.TLS_PROBLEM -> "The server's certificate was rejected for this address."
        GatewayConclusion.AUTH_REJECTED -> "GMweb rejected this device's key."
        GatewayConclusion.AUTH_UNVERIFIED ->
            "Could not confirm this device's key — the check itself gave no clear answer, " +
                "which is not the same as a rejection."
        GatewayConclusion.REQUEST_CONTRACT_MISMATCH ->
            "The server refused the request: this app and your GMweb version disagree about " +
                "the API. This is not a key problem."
        GatewayConclusion.WRONG_URL -> "GMweb answered, but the gateway route is not there."
        GatewayConclusion.RATE_LIMITED -> "GMweb is rate limiting this device; it will retry."
        GatewayConclusion.SERVER_ERROR -> "GMweb returned a server error."
        GatewayConclusion.BRIDGE_NOT_POLLING ->
            "GMweb is reachable, but send requests are not reaching this phone."
        GatewayConclusion.BRIDGE_FAILING -> "The delivery poll is failing; see the last error below."
        GatewayConclusion.UPLOAD_STALLED ->
            "Delivery is fine, but this phone's own sync to GMweb is failing."
        GatewayConclusion.ACK_FAILING ->
            "Tasks are arriving, but the latest result could not be acknowledged."
        GatewayConclusion.HISTORICAL_FAILURES ->
            "Gateway is connected. Historical sync failures need review."
        GatewayConclusion.UNKNOWN_FAILURE -> "A failure was recorded that does not match a known cause."
    }

    /** A dimension that is either satisfied or not. "Unknown" stays neutral, never green. */
    fun tone(passed: Boolean?): HealthTone = when (passed) {
        true -> HealthTone.GOOD
        false -> HealthTone.BAD
        null -> HealthTone.NEUTRAL
    }

    /** The bridge's own state, as one tone. */
    fun bridgeTone(bridge: PullBridgeHealth, now: Long): HealthTone = when {
        bridge.lastFailure != null && bridge.lastFailure!!.needsConfigurationChange -> HealthTone.BAD
        bridge.lastFailure != null -> HealthTone.WARN
        bridge.freshWithin(GatewayHealthRules.PULL_FRESH_MS, now) -> HealthTone.GOOD
        bridge.lastSuccessfulPollAt == null -> HealthTone.NEUTRAL
        else -> HealthTone.WARN
    }

    /**
     * The uploader's tone.
     *
     * A quiet device is HEALTHY, not unknown: no events to send and no failure is the normal
     * resting state, and colouring it amber would make every idle phone look broken.
     */
    fun uploadTone(upload: EventUploadHealth, now: Long): HealthTone = when {
        upload.lastFailure != null -> HealthTone.BAD
        !upload.running -> HealthTone.NEUTRAL
        upload.hasActiveFailure(now) -> HealthTone.WARN
        else -> HealthTone.GOOD
    }

    fun tone(severity: GatewayLogSeverity): HealthTone = when (severity) {
        GatewayLogSeverity.SUCCESS -> HealthTone.GOOD
        GatewayLogSeverity.WARNING -> HealthTone.WARN
        GatewayLogSeverity.ERROR -> HealthTone.BAD
        GatewayLogSeverity.INFO -> HealthTone.NEUTRAL
        GatewayLogSeverity.DEBUG -> HealthTone.NEUTRAL
    }

    fun tone(status: GatewayProbeStatus): HealthTone = when (status) {
        GatewayProbeStatus.PASSED -> HealthTone.GOOD
        GatewayProbeStatus.FAILED -> HealthTone.BAD
        GatewayProbeStatus.SKIPPED -> HealthTone.NEUTRAL
        GatewayProbeStatus.NOT_REQUIRED -> HealthTone.IDLE
    }

    /** "just now" / "42s ago" / "4m ago" — short enough for a status row. */
    fun ago(at: Long?, now: Long): String {
        if (at == null) return "never"
        val delta = (now - at).coerceAtLeast(0L)
        return when {
            delta < 2_000L -> "just now"
            delta < 60_000L -> "${delta / 1000}s ago"
            delta < 3_600_000L -> "${delta / 60_000}m ago"
            else -> "${delta / 3_600_000}h ago"
        }
    }

    /**
     * The one-line state of the delivery bridge, for the compact row.
     *
     * An in-flight long-poll is called out by how long it has been waiting: the bridge holds
     * a request open for ~25 seconds by design, so "waiting 18s" is normal and "not polling
     * yet" is not, and the user needs to be able to tell those apart at a glance.
     */
    fun bridgeSummary(bridge: PullBridgeHealth, now: Long): String = when {
        bridge.lastFailure != null -> bridge.lastFailure!!.name
        bridge.freshWithin(GatewayHealthRules.PULL_FRESH_MS, now) ->
            "online · ${ago(bridge.lastSuccessfulPollAt, now)}"
        bridge.currentRequestStartedAt != null ->
            "waiting ${ago(bridge.currentRequestStartedAt, now)}"
        bridge.lastSuccessfulPollAt == null -> "not polling yet"
        else -> "stalled · last success ${ago(bridge.lastSuccessfulPollAt, now)}"
    }

    /** How long the in-flight long-poll has been open, or null when nothing is in flight. */
    fun inFlightWait(bridge: PullBridgeHealth, now: Long): String? =
        bridge.currentRequestStartedAt?.let { ago(it, now) }

    /** The one-line state of the outbound sync. */
    fun uploadSummary(upload: EventUploadHealth, now: Long): String = when {
        upload.lastFailure != null -> upload.lastFailure!!.name
        !upload.running -> "not running"
        upload.pending > 0 || upload.sending > 0 -> "${upload.pending + upload.sending} pending"
        upload.lastSuccessAt != null -> "ok · ${ago(upload.lastSuccessAt, now)}"
        else -> "idle · nothing to send"
    }

    /** The EVE queue's one-line state. */
    fun queueSummary(queue: EveQueueHealth): String = when {
        queue.active > 0 -> "sending ${queue.active}"
        queue.queued > 0 -> "${queue.queued} waiting"
        queue.deferred > 0 -> "${queue.deferred} deferred"
        queue.failedRecent > 0 -> "${queue.failedRecent} failed"
        else -> "idle"
    }
}
