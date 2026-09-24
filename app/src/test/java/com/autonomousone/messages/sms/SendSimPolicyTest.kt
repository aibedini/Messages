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
    fun `aManagerBoundElsewhereIsRefused`() {
        assertEquals(
            SimDecision.Refuse(2),
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
    fun `anUnknownActiveListWithAManagerThatWillNotSayStillSends`() {
        assertEquals(
            SimDecision.Send(SendSimPolicy.UNKNOWN_SUBSCRIPTION_ID),
            decide(requested = 2, actual = SendSimPolicy.UNKNOWN_SUBSCRIPTION_ID, active = null)
        )
    }

    @Test
    fun `anUnreadableSubscriptionNeverFabricatesTheRequest`() {
        // The exact defect: the ledger said 2 because 2 was requested, while the message left on
        // whatever the platform default was. "Unknown" is the honest answer, and it is not 2.
        val decision = decide(requested = 2, actual = SendSimPolicy.UNKNOWN_SUBSCRIPTION_ID, active = true)

        assertEquals(SimDecision.Send(SendSimPolicy.UNKNOWN_SUBSCRIPTION_ID), decision)
        assertTrue(decision !is SimDecision.Send || decision.recordedSubscriptionId != 2)
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
    fun `aRefusalAlwaysNamesTheSimTheUserChose`() {
        // The message the user sees has to identify their own selection, not the line the app would
        // have used — otherwise it reads as "some SIM is unavailable" and they cannot act on it.
        listOf(
            decide(requested = 2, actual = 3, active = true),
            decide(requested = 2, actual = 1, active = false)
        ).forEach { decision ->
            assertTrue(decision is SimDecision.Refuse)
            assertEquals(2, (decision as SimDecision.Refuse).requestedSubscriptionId)
        }
    }
}
