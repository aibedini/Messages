package com.autonomousone.messages.gateway

import com.autonomousone.messages.messaging.SimDiscovery
import com.autonomousone.messages.messaging.SimDiscoveryResult
import com.autonomousone.messages.messaging.SimInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SIM discovery must answer three DIFFERENT questions, and telemetry must not flatten them.
 *
 * `SimManager.getActiveSims()` caught every exception and returned `emptyList()`, so "the user has
 * not granted Phone permission", "this phone has no active subscription" and "the platform refused
 * to answer" all reached GMweb as `available=false, items=[]`. The first is a ten-second fix for the
 * user, the second is a phone with no service, the third is a bug.
 */
class SimDiscoveryTest {

    private val sim1 = SimInfo(1, 0, "Carrier A", "SIM 1", "", true)

    @Test
    fun `a granted permission with no active subscription is AVAILABLE and empty`() {
        val result = SimDiscovery.classify(permissionGranted = true) { emptyList() }

        assertTrue(result is SimDiscoveryResult.Available)
        assertEquals(0, (result as SimDiscoveryResult.Available).sims.size)
        assertNull("an answered question has no reason code", SimDiscovery.reasonOf(result))
    }

    @Test
    fun `a missing permission is not an empty list and does not call the platform`() {
        var queried = false
        val result = SimDiscovery.classify(permissionGranted = false) {
            queried = true
            emptyList()
        }

        assertTrue(result is SimDiscoveryResult.PermissionMissing)
        assertFalse("a question that cannot be asked must not be asked", queried)
        assertEquals(SimDiscovery.REASON_PERMISSION_MISSING, SimDiscovery.reasonOf(result))
    }

    @Test
    fun `a platform failure is its own state, never an empty list`() {
        val result = SimDiscovery.classify(permissionGranted = true) {
            throw IllegalStateException("SubscriptionManager unavailable")
        }

        assertTrue(result is SimDiscoveryResult.Failed)
        assertEquals(SimDiscovery.REASON_PLATFORM_FAILURE, SimDiscovery.reasonOf(result))
    }

    @Test
    fun `a platform failure carries no exception text to the wire`() {
        val result = SimDiscovery.classify(permissionGranted = true) {
            throw IllegalStateException("android.os.DeadObjectException: internal component name")
        }

        val reason = SimDiscovery.reasonOf(result)!!
        assertEquals(SimDiscovery.REASON_PLATFORM_FAILURE, reason)
        assertFalse(reason.contains("DeadObject"))
        assertFalse(reason.contains("internal"))
    }

    @Test
    fun `the three states are distinguishable from each other`() {
        val available = SimDiscovery.classify(true) { listOf(sim1) }
        val empty = SimDiscovery.classify(true) { emptyList() }
        val missing = SimDiscovery.classify(false) { emptyList() }
        val failed = SimDiscovery.classify(true) { throw RuntimeException("x") }

        assertEquals(1, (available as SimDiscoveryResult.Available).sims.size)
        assertEquals(0, (empty as SimDiscoveryResult.Available).sims.size)
        assertFalse(empty == missing)
        assertFalse(missing == failed)
        assertFalse(empty == failed)
    }

    @Test
    fun `dual sim is reported in slot order`() {
        val second = SimInfo(2, 1, "Carrier B", "SIM 2", "", false)
        val result = SimDiscovery.classify(true) { listOf(second, sim1) }

        assertEquals(listOf(2, 1), (result as SimDiscoveryResult.Available).sims.map { it.subscriptionId })
    }
}
