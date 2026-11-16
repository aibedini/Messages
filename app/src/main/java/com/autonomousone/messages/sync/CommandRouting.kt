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

    /**
     * `SET_CONVERSATION_PREFERRED_SIM`: persist which line this conversation sends on.
     *
     * Its own route rather than reusing [READ_THREAD], because the two failures mean opposite things:
     * a read failing leaves the user's unread state alone, while a preference failing leaves the
     * conversation sending on a line the user did not choose.
     */
    CONVERSATION_SIM,

    /**
     * `SUGGESTIONS_READY`: store the AI's reply suggestions for one conversation (XR-GA-017).
     *
     * Its own route rather than reusing [READ_THREAD]: a suggestion delivery is neither a read nor a
     * send. It writes ONLY the suggestion-card table (never a message), and a failure means the cards
     * were not shown — not that the user's unread state or a conversation's SIM changed.
     */
    SUGGESTIONS,

    /** Not implemented by this build: must reach a durable terminal failure. */
    UNSUPPORTED
}

object CommandRouting {

    const val SEND_SMS = "SEND_SMS"
    const val MARK_THREAD_READ = "MARK_THREAD_READ"
    const val REFRESH_DEVICE_TELEMETRY = "REFRESH_DEVICE_TELEMETRY"
    const val FETCH_THREAD_HISTORY = ThreadHistoryCommand.TYPE

    /**
     * `SET_CONVERSATION_PREFERRED_SIM` — the sticky per-conversation SIM (Phase 5A).
     *
     * Named here as a constant before it is ADVERTISED: the executor and the advertisement are
     * deliberately separable, so the routing table can name the type while
     * [ADVERTISED_COMMAND_TYPES] still omits it. Advertising is a claim to another system, and this
     * build only makes that claim once the executor, the persistence and the canonical event all work.
     */
    const val SET_CONVERSATION_PREFERRED_SIM = "SET_CONVERSATION_PREFERRED_SIM"

    /**
     * `SUGGESTIONS_READY` — the AI's reply suggestions for one conversation (XR-GA-017).
     *
     * Advertised because all of its halves exist and are tested together: the executor validates the
     * payload against the local mirror, the cards are sealed into `suggestion_cards`, the conversation
     * UI renders them, and the feedback event has a durable outbox path. Advertising the type is how
     * GMweb learns the device can receive it at all.
     */
    const val SUGGESTIONS_READY = SuggestionsReadyCommand.TYPE

    /**
     * THE single source of truth for what this build can execute.
     *
     * It is used for two things that must never disagree: the `runtime.commandTypes` array advertised in
     * the command claim, and the set the router accepts. A second hand-maintained list is exactly how a
     * command came to be advertised but unroutable (or routable but never advertised).
     *
     * `SET_CONVERSATION_PREFERRED_SIM` is advertised NOW because all three halves are in place and
     * tested together: the executor persists the preference, it resolves the reference against the live
     * inventory with no slot fallback, and it queues the canonical encrypted conversation event before
     * ACKing. Advertising any earlier would have claimed a capability the device could not honour.
     */
    val ADVERTISED_COMMAND_TYPES: List<String> = listOf(
        SEND_SMS,
        MARK_THREAD_READ,
        REFRESH_DEVICE_TELEMETRY,
        FETCH_THREAD_HISTORY,
        SET_CONVERSATION_PREFERRED_SIM,
        SUGGESTIONS_READY
    )

    /** Types this build can actually route to an executor. Derived, never typed twice. */
    val EXECUTABLE_COMMAND_TYPES: Set<String> =
        ADVERTISED_COMMAND_TYPES.filter { routeOf(it) != CommandRoute.UNSUPPORTED }.toSet()

    fun routeOf(type: String): CommandRoute = when (type) {
        SEND_SMS -> CommandRoute.SMS_PIPELINE
        MARK_THREAD_READ -> CommandRoute.READ_THREAD
        REFRESH_DEVICE_TELEMETRY -> CommandRoute.TELEMETRY_REFRESH
        FETCH_THREAD_HISTORY -> CommandRoute.THREAD_HISTORY
        SET_CONVERSATION_PREFERRED_SIM -> CommandRoute.CONVERSATION_SIM
        SUGGESTIONS_READY -> CommandRoute.SUGGESTIONS
        else -> CommandRoute.UNSUPPORTED
    }
}
