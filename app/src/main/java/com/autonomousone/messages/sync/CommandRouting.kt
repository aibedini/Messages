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

    /** `FETCH_THREAD_HISTORY`: one bounded, keyset-paged history publication. */
    THREAD_HISTORY,

    /** Not implemented by this build: must reach a durable terminal failure. */
    UNSUPPORTED
}

object CommandRouting {

    const val SEND_SMS = "SEND_SMS"
    const val MARK_THREAD_READ = "MARK_THREAD_READ"
    const val REFRESH_DEVICE_TELEMETRY = "REFRESH_DEVICE_TELEMETRY"
    const val FETCH_THREAD_HISTORY = ThreadHistoryCommand.TYPE

    /**
     * THE single source of truth for what this build can execute.
     *
     * It is used for two things that must never disagree: the `runtime.commandTypes` array advertised in
     * the command claim, and the set the router accepts. A second hand-maintained list is exactly how a
     * command came to be advertised but unroutable (or routable but never advertised).
     */
    val ADVERTISED_COMMAND_TYPES: List<String> = listOf(
        SEND_SMS,
        MARK_THREAD_READ,
        REFRESH_DEVICE_TELEMETRY,
        FETCH_THREAD_HISTORY
    )

    /** Types this build can actually route to an executor. Derived, never typed twice. */
    val EXECUTABLE_COMMAND_TYPES: Set<String> =
        ADVERTISED_COMMAND_TYPES.filter { routeOf(it) != CommandRoute.UNSUPPORTED }.toSet()

    fun routeOf(type: String): CommandRoute = when (type) {
        SEND_SMS -> CommandRoute.SMS_PIPELINE
        MARK_THREAD_READ -> CommandRoute.READ_THREAD
        REFRESH_DEVICE_TELEMETRY -> CommandRoute.TELEMETRY_REFRESH
        FETCH_THREAD_HISTORY -> CommandRoute.THREAD_HISTORY
        else -> CommandRoute.UNSUPPORTED
    }
}
