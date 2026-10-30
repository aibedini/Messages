package com.autonomousone.messages.gateway

/**
 * What the delivery-report uploader should do with one HTTP answer from GMweb.
 *
 * The queue is drained in-order and every failure used to stop the cycle. That is right for a
 * network blip or a revoked device key — the same report will be retried — but it is wrong for a
 * report GMweb will never accept: a single permanently-rejected verdict parked at the head of the
 * queue starved every later delivery report for ever, and the carrier truth for new messages simply
 * stopped leaving the device.
 *
 * Kept pure and separate from [OutboxPoller] so the boundary is a JVM test: network policy decides
 * nothing about transport here, only which class of answer this is.
 */
object DeliveryReportUploadPolicy {

    enum class Action {
        /** 2xx: the report is recorded; acknowledge and continue. */
        ACCEPT,

        /** This one report can never be accepted: keep the evidence for an operator, continue. */
        QUARANTINE,

        /** The answer describes the link or the whole sender, not this report: stop and retry later. */
        RETRY
    }

    /**
     * @param httpStatus the response code as received
     * @param errorCode GMweb's `error` field, when the body carried one
     */
    fun classify(httpStatus: Int, errorCode: String?): Action = when {
        httpStatus in 200..299 -> Action.ACCEPT

        // The ledger has no row for this request. GMweb will never accept it; retrying it in front
        // of newer reports only hides them.
        httpStatus == 404 && errorCode == "unknown_request_id" -> Action.QUARANTINE

        // A malformed or contradictory report is rejected deterministically (schema validation,
        // event-id conflict). The same bytes can never become acceptable.
        httpStatus == 400 || httpStatus == 409 || httpStatus == 422 -> Action.QUARANTINE

        // 401/403: the device key is rejected — every report will fail until it is fixed, so stop.
        // 408/429/5xx: transient. Anything else: treat as a link problem, not as poison.
        else -> Action.RETRY
    }
}
