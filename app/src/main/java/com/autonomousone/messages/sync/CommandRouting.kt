package com.autonomousone.messages.sync

/**
 * Which executor a claimed command belongs to — one decision, in one place, testable.
 *
 * **Why this is not an `if` inside the poller.** The poller grew a chain of type checks, and the last
 * one silently returned for anything unrecognised: a claimed command could sit non-terminal for ever,
 * with GMweb's optimistic entry spinning and nothing on the device working on it. The routing is
 * therefore a pure function with an explicit `UNSUPPORTED` outcome, so "we do not know this type" is
 * a value the poller must handle rather than a branch it can forget.
 *
 * It also pins the boundary that matters most: **only `SEND_SMS` may enter the SMS pipeline.** A
 * telemetry refresh that reached `GatewayOutgoingPipeline` would be treated as a message to send.
 */
enum class CommandRoute {
    /** The single SMS funnel (`GatewayOutgoingPipeline`). */
    SMS_PIPELINE,

    /** `MARK_THREAD_READ`: provider read + durable THREAD_READ. */
    READ_THREAD,

    /** `REFRESH_DEVICE_TELEMETRY`: one awaited telemetry report. Never the SMS pipeline. */
    TELEMETRY_REFRESH,

    /** Not implemented by this build: must reach a durable terminal failure. */
    UNSUPPORTED
}

object CommandRouting {

    const val SEND_SMS = "SEND_SMS"
    const val MARK_THREAD_READ = "MARK_THREAD_READ"
    const val REFRESH_DEVICE_TELEMETRY = "REFRESH_DEVICE_TELEMETRY"

    fun routeOf(type: String): CommandRoute = when (type) {
        SEND_SMS -> CommandRoute.SMS_PIPELINE
        MARK_THREAD_READ -> CommandRoute.READ_THREAD
        REFRESH_DEVICE_TELEMETRY -> CommandRoute.TELEMETRY_REFRESH
        else -> CommandRoute.UNSUPPORTED
    }
}
