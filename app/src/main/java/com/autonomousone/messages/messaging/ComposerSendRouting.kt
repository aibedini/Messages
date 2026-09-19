package com.autonomousone.messages.messaging

/**
 * P0 (v3.4.3) — the composer's send contract, as an explicit type.
 *
 * ── The bug this file exists to make impossible ──────────────────────────────
 * v3.4.3 routed a composer tap through `DelayedSendCoordinator.send` and then
 * folded EVERY non-delayed outcome into `null`:
 *
 *   DELAY OFF → sink.send(phone, body, subscriptionId)   // physical SMS #1
 *             → SendResult.SentNow(rowId)
 *             → routeComposerSend() returned null
 *             → caller read null as "not sent yet"
 *             → smsSender.send(...)                      // physical SMS #2
 *
 * One tap, two chargeable submissions. The defect was not the coordinator (it
 * reports `SentNow` correctly) and not the caller (it only knew about "held"
 * and "not held"): it was the missing outcome vocabulary. A nullable String can
 * encode "held" or "not held" but cannot encode "already submitted by the sink
 * you handed me", so the caller had to guess — and guessed wrong.
 *
 * [ComposerSendRouter.decide] is now the single mapping from coordinator result
 * to caller action, and `null` means exactly one thing: the bounded decision
 * expired or threw, so this process does not know. That is the ONLY case the
 * caller may fall back to a direct send, and [ComposerSendOrchestrator] allows
 * even that only once, and only when the durable ledger does not already own
 * the intent.
 *
 * Everything here is pure: no Android, no Room, no coroutines beyond the
 * caller-supplied suspend lambdas — so the exactly-once invariant is exercised
 * by `ComposerSendRoutingTest` on the JVM.
 */

/** One already-composed message on its way to the radio. */
data class ComposerSendRequest(
    val phone: String,
    val body: String,
    val threadId: Long,
    val subscriptionId: Int?,
    /**
     * Durable identity chosen BEFORE routing, so a caller that has to ask "did
     * the ledger accept this?" after a timeout can answer it.
     */
    val intentId: String
)

/** What the coordinator's outcome MEANS for the caller. */
sealed interface ComposerSendAction {

    /** Delayed: the durable ledger owns it. Never send directly. */
    data object HeldForDelay : ComposerSendAction

    /** The routing sink ALREADY submitted it. Never send again. */
    data class AlreadySent(val providerRowId: Long?) : ComposerSendAction

    /** Blank recipient/body: nothing was sent and nothing was queued. */
    data object Ignored : ComposerSendAction

    /** Timeout or throw: the only outcome that permits ONE fallback send. */
    data object DirectFallback : ComposerSendAction
}

object ComposerSendRouter {

    /**
     * The one place a [SendResult] becomes a caller action.
     *
     * `when (result)` is exhaustive over the sealed type: adding a result
     * without deciding its send policy is a compile error, not a silent second
     * submission.
     */
    fun decide(result: SendResult?): ComposerSendAction = when (result) {
        is SendResult.DelayedSend -> ComposerSendAction.HeldForDelay
        is SendResult.SentNow -> ComposerSendAction.AlreadySent(result.rowId)
        SendResult.Ignored -> ComposerSendAction.Ignored
        null -> ComposerSendAction.DirectFallback
    }
}

/** What ONE composer tap did, for the ViewModel and its diagnostics. */
sealed interface ComposerSendOutcome {

    /** Delay ON: the ledger bubble owns the UI; [delaySeconds] is the countdown. */
    data class HeldForDelay(val delaySeconds: Long) : ComposerSendOutcome

    /** Delay OFF: the sink already submitted it; [providerRowId] may be unknown. */
    data class AlreadySent(val providerRowId: Long?) : ComposerSendOutcome

    /** Blank: nothing happened. */
    data object Ignored : ComposerSendOutcome

    /** Timeout/throw with no durable row: exactly ONE direct submission. */
    data class FallbackSent(val providerRowId: Long?) : ComposerSendOutcome

    /**
     * Timeout/throw, but the ledger DOES own the intent (the insert landed after
     * the deadline). Falling back here would be the second physical send, so the
     * caller must re-arm the durable timer instead.
     */
    data object HeldAfterTimeout : ComposerSendOutcome
}

/**
 * Executes the decision table around caller-supplied effects.
 *
 * The effects are lambdas on purpose: the ViewModel supplies the real
 * coordinator/ledger/radio, the test supplies counting fakes, and BOTH run this
 * same class — so the exactly-once property is tested on the production logic,
 * not on a re-implementation of it.
 */
class ComposerSendOrchestrator(
    /** Routes through the delay gate; the sink inside it may physically send. */
    private val routeSend: suspend (ComposerSendRequest) -> SendResult?,
    /** True when the durable ledger already holds [request.intentId]. */
    private val ledgerHolds: suspend (String) -> Boolean,
    /** The ONE permitted fallback direct submission. */
    private val directSend: (ComposerSendRequest) -> Long?
) {

    suspend fun execute(request: ComposerSendRequest): ComposerSendOutcome {
        // A throw here is "unknown", the same as a timeout — never "not sent".
        val result = try {
            routeSend(request)
        } catch (_: Exception) {
            null
        }
        return when (val action = ComposerSendRouter.decide(result)) {
            ComposerSendAction.HeldForDelay ->
                ComposerSendOutcome.HeldForDelay(
                    (result as? SendResult.DelayedSend)?.delaySeconds ?: 0L
                )

            is ComposerSendAction.AlreadySent ->
                // The sink already submitted it. Deliberately no directSend.
                ComposerSendOutcome.AlreadySent(action.providerRowId)

            ComposerSendAction.Ignored -> ComposerSendOutcome.Ignored

            ComposerSendAction.DirectFallback ->
                if (ledgerHolds(request.intentId)) {
                    // The insert won the race with the deadline: the message IS
                    // held, so a fallback would submit it twice.
                    ComposerSendOutcome.HeldAfterTimeout
                } else {
                    ComposerSendOutcome.FallbackSent(directSend(request))
                }
        }
    }
}
