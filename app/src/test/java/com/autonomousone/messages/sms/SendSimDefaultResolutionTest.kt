package com.autonomousone.messages.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MODE A — PHONE_DEFAULT (mission §25/§64).
 *
 * A send that names no line must be decided by the phone, at EXECUTION time, from the CURRENT system
 * default SMS subscription. Two failure modes are forbidden and both are pinned here:
 *
 *  - picking an active subscription because one happens to exist ("random SIM 1"), which is how a web
 *    request that named no line would leave on a line the user never designated; and
 *  - treating "the app could not read the platform's answer" as "there is no default", which would
 *    refuse every default-line send on a device that withholds the list.
 */
class SendSimDefaultResolutionTest {

    @Test
    fun `an explicit system default is used as the line`() {
        val result = SendSimPolicy.resolveDefault(platformDefaultSmsSubscriptionId = 7)

        assertEquals(SendSimPolicy.DefaultSimResolution.Use(7), result)
    }

    @Test
    fun `the platform saying there is no default fails closed`() {
        val result = SendSimPolicy.resolveDefault(
            platformDefaultSmsSubscriptionId = SendSimPolicy.UNKNOWN_SUBSCRIPTION_ID
        )

        assertEquals(SendSimPolicy.DefaultSimResolution.NoDefault, result)
    }

    @Test
    fun `any other negative id is also treated as no default`() {
        for (id in listOf(-1, -2, Int.MIN_VALUE)) {
            assertEquals(
                "subscription $id is not a subscription",
                SendSimPolicy.DefaultSimResolution.NoDefault,
                SendSimPolicy.resolveDefault(id)
            )
        }
    }

    @Test
    fun `a platform that could not be asked is NOT the same as one with no default`() {
        val result = SendSimPolicy.resolveDefault(platformDefaultSmsSubscriptionId = null)

        assertEquals(SendSimPolicy.DefaultSimResolution.Unknown, result)
        assertTrue(
            "an unreadable answer must not be reported as a missing default",
            result != SendSimPolicy.DefaultSimResolution.NoDefault
        )
    }

    @Test
    fun `subscription zero is a real subscription, not a sentinel`() {
        assertEquals(
            SendSimPolicy.DefaultSimResolution.Use(0),
            SendSimPolicy.resolveDefault(0)
        )
    }

    @Test
    fun `resolving the default never invents a line from the active list`() {
        // The policy has no access to the active list at all: its only input is the platform's own
        // default answer. That is what makes "random SIM 1" structurally impossible rather than a
        // rule someone has to remember.
        val parameters = SendSimPolicy::class.java.declaredMethods
            .first { it.name == "resolveDefault" }
            .parameterTypes
            .map { it.name }

        assertEquals(listOf("java.lang.Integer"), parameters)
    }
}
