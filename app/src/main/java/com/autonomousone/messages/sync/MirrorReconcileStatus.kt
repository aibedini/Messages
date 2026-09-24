package com.autonomousone.messages.sync

/**
 * The most recent mirror → outbox reconciliation outcome, process-wide.
 *
 * WHY THIS EXISTS: reconciliation detects the one failure that is otherwise invisible — a message
 * the phone knows about that has NO durable event, because its realtime notification was missed. It
 * repairs what it finds, but until now the finding only reached a log line, so the diagnostic could
 * not answer mission §70's question: *are any messages unexplained?*
 *
 * Process-wide rather than instance-held, for the same reason as
 * [com.autonomousone.messages.observer.GatewayChangeRelay.runningProcessWide]: the diagnostic
 * collector is not the object that ran the reconciliation, and threading a reference between them
 * would couple two unrelated lifetimes.
 *
 * `null` means "not run yet", which is NOT the same as "found nothing" — reporting those as equal
 * would turn an unmeasured diagnostic into a reassuring one.
 */
object MirrorReconcileStatus {

    @Volatile
    var last: ReconcileResult? = null
        private set

    @Volatile
    var lastAt: Long = 0L
        private set

    /** Records a completed run. Called by the coordinator after each reconciliation. */
    fun record(result: ReconcileResult, at: Long) {
        last = result
        lastAt = at
    }

    /** Test seam: forget the previous run. */
    fun reset() {
        last = null
        lastAt = 0L
    }
}
