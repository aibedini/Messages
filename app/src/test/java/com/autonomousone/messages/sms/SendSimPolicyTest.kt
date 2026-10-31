package com.autonomousone.messages.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mission §50: "do not silently send using another SIM", and do not attribute a send to a line that
 * did not carry it.
 *
 * Two live defects these pin. `resolveSmsManager` ignored the user's chosen line on every supported
 * device below Android 12 — on the false grounds that the per-subscription API was gone — and the
 * send's segment ledger then recorded the *requested* subscription, so the per-SIM "SMS today"
 * counter attributed messages to a line that never carried them. A fabricated attribution is worse
 * than a missing one: it looks like evidence.
 */
class SendSimPolicyTest {

    private fun decide(requested: Int?, actual: Int, active: Boolean? = null) =
        SendSimPolicy.decide(requested = requested, actual = actual, requestedSimActive = active)

    // ── Honouring the request ────────────────────────────────────────────────

    @Test
    fun `aHonouredSelectionIsTheOnlyCaseWhereTheRequestIsAlsoAFact`() {
        assertEquals(
            SimDecision.Send(2),
            decide(requested = 2, actual = 2, active = true)
        )
    }

    @Test
    fun `noSelectionMeansThePlatformDefaultIsWhatTheUserAskedFor`() {
        // -1 is the preference sentinel for "the user has not chosen"; it is not a request for
        // subscription -1, so any line the platform picks is the one they asked for.
        assertEquals(SimDecision.Send(1), decide(requested = null, actual = 1))
        assertEquals(SimDecision.Send(SendSimPolicy.UNKNOWN_SUBSCRIPTION_ID), decide(requested = null, actual = -1))
    }

    // ── Refusing, and only on evidence ───────────────────────────────────────

    @Test
    fun `aChosenSimThatIsProvablyAbsentIsRefused`() {
        // The device's own active-subscription list says the line is gone. Sending would put the
        // message on the other line, from a number the user did not choose, and it cannot be recalled.
        assertEquals(
            SimDecision.Refuse(2),
            decide(requested = 2, actual = 1, active = false)
        )
    }

    @Test
    fun `aManagerThatReportsAnotherLineStillAttemptsAndRecordsWhatItReports`() {
        // The reported regression: on some OEM builds the manager created for the chosen line
        // reports another subscription (or none) even though the SIM is present. Refusing there
        // meant the chosen line could NEVER be used - the user had to change the system default SIM
        // to send at all. The manager is still the one created for the request, so the send is
        // attempted; the ledger keeps the manager's claim, never a fabricated request.
        assertEquals(
            SimDecision.Send(3),
            decide(requested = 2, actual = 3, active = true)
        )
    }

    @Test
    fun `anUnknownActiveListNeverRefuses`() {
        // The distinction that matters most in this file. Without READ_PHONE_STATE the app cannot
        // list SIMs at all; treating that as "the SIM is absent" would refuse every send on such a
        // device, which is a far worse failure than the one being fixed.
        val decision = decide(requested = 2, actual = 2, active = null)

        assertEquals(SimDecision.Send(2), decision)
        assertTrue(decision !is SimDecision.Refuse)
    }

    @Test
    fun `anUnknownManagerStillAttemptsAndRecordsUnknown`() {
        assertEquals(
            SimDecision.Send(SendSimPolicy.UNKNOWN_SUBSCRIPTION_ID),
            decide(requested = 2, actual = SendSimPolicy.UNKNOWN_SUBSCRIPTION_ID, active = null)
        )
    }

    @Test
    fun `anUnreadableSubscriptionNeverFabricatesTheRequest`() {
        // The send is attempted, but the ledger records ONLY what the manager reported: an unknown
        // binding stays unknown, so the per-SIM counter cannot claim the chosen line carried it.
        val decision = decide(requested = 2, actual = SendSimPolicy.UNKNOWN_SUBSCRIPTION_ID, active = true)

        assertEquals(SimDecision.Send(SendSimPolicy.UNKNOWN_SUBSCRIPTION_ID), decision)
    }

    @Test
    fun `noInputCombinationRecordsTheRequestWithoutTheManagerConfirmingIt`() {
        // The property over the whole space, rather than the examples above: whenever the ledger
        // records a subscription the user asked for, the manager must have reported that same id.
        val ids = listOf(-1, 0, 1, 2, 3)
        for (requested in listOf(null, 0, 1, 2, 3)) {
            for (actual in ids) {
                for (active in listOf(null, true, false)) {
                    val decision = SendSimPolicy.decide(requested, actual, active)
                    if (decision is SimDecision.Send && requested != null &&
                        decision.recordedSubscriptionId == requested
                    ) {
                        assertEquals(
                            "recorded the request for requested=$requested actual=$actual active=$active",
                            requested,
                            actual
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `the only refusal names the sim the user chose`() {
        // Absence is the one remaining refusal; a read-back disagreement attempts the send.
        val decision = decide(requested = 2, actual = 1, active = false)
        assertTrue(decision is SimDecision.Refuse)
        assertEquals(2, (decision as SimDecision.Refuse).requestedSubscriptionId)
    }
}
