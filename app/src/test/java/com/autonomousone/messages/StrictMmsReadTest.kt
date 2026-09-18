package com.autonomousone.messages

import com.autonomousone.messages.data.ProviderExistence
import com.autonomousone.messages.data.ProviderRead
import com.autonomousone.messages.data.isSuccess
import com.autonomousone.messages.data.mmsSecondaryFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The strict-MMS contract, stated as the ONE invariant that keeps a failed
 * secondary provider read from destroying good local data:
 *
 *   a failed Addr or Part read must NEVER be materialized as a row.
 *
 * The old reader collapsed a failed Part query into an empty map, which the
 * mapper then rendered as the literal body "[MMS]" (and a failed Addr query as
 * "Unknown"). Sync-authoritative callers wrote that over the real Room row. The
 * message still existed; only our ability to read it had failed, and the
 * placeholder made the two indistinguishable.
 *
 * Existence is deliberately a SEPARATE question with its own type, because a
 * delete decision must not depend on the Addr/Part tables at all: SMS 123 and
 * MMS 123 are different rows, and a body lookup failure says nothing about
 * whether a message exists.
 *
 * WRITTEN BUT NOT EXECUTED.
 */
class StrictMmsReadTest {

    private fun ok(value: Map<Long, String>): ProviderRead<Map<Long, String>> =
        ProviderRead.Success(value)

    @Test
    fun `a failed address read propagates instead of becoming a placeholder`() {
        val failure = mmsSecondaryFailure(
            ProviderRead.Failure(ProviderRead.Reason.BINDER),
            ok(mapOf(1L to "hello"))
        )
        assertEquals(ProviderRead.Reason.BINDER, failure?.reason)
    }

    @Test
    fun `a failed body read propagates instead of becoming the MMS placeholder`() {
        val failure = mmsSecondaryFailure(
            ok(emptyMap()),
            ProviderRead.Failure(ProviderRead.Reason.QUERY_RETURNED_NULL)
        )
        assertEquals(ProviderRead.Reason.QUERY_RETURNED_NULL, failure?.reason)
    }

    @Test
    fun `two successful reads produce no failure even when both maps are empty`() {
        // An image-only MMS legitimately has no text/plain part. That is DATA,
        // not an error, and it is what separates this from a failed lookup.
        assertNull(mmsSecondaryFailure(ok(emptyMap()), ok(emptyMap())))
        assertNull(mmsSecondaryFailure(ok(mapOf(5L to "+9891")), ok(mapOf(5L to "body"))))
    }

    @Test
    fun `every failure reason survives verbatim`() {
        for (reason in ProviderRead.Reason.values()) {
            val failure = ProviderRead.Failure(reason)
            assertEquals(reason, mmsSecondaryFailure(failure, ok(emptyMap()))?.reason)
            assertEquals(reason, mmsSecondaryFailure(ok(emptyMap()), failure)?.reason)
        }
    }

    @Test
    fun `the guard can never turn a failure into a success`() {
        val success = ok(emptyMap())
        for (reason in ProviderRead.Reason.values()) {
            val failure: ProviderRead<Map<Long, String>> = ProviderRead.Failure(reason)
            for (other in listOf(success, failure)) {
                assertNotNull(
                    "address failure must propagate",
                    mmsSecondaryFailure(failure, other)
                )
                assertNotNull(
                    "body failure must propagate",
                    mmsSecondaryFailure(other, failure)
                )
            }
        }
    }

    @Test
    fun `a null cursor is a failure and not an empty result`() {
        val nullCursor = mmsSecondaryFailure(
            ok(emptyMap()),
            ProviderRead.Failure(ProviderRead.Reason.QUERY_RETURNED_NULL)
        )
        assertNotNull("a null cursor is UNKNOWN, never absence", nullCursor)
        assertEquals(ProviderRead.Reason.QUERY_RETURNED_NULL, nullCursor?.reason)
    }

    @Test
    fun `existence is a different question from readability`() {
        val absent: ProviderRead<ProviderExistence> = ProviderRead.Success(ProviderExistence.Absent)
        val exists: ProviderRead<ProviderExistence> = ProviderRead.Success(ProviderExistence.Exists)
        val failed: ProviderRead<ProviderExistence> =
            ProviderRead.Failure(ProviderRead.Reason.PROVIDER_UNAVAILABLE)

        assertTrue("the provider answered: the row is gone", absent.isSuccess)
        assertTrue("the provider answered: the row is there", exists.isSuccess)
        assertFalse("a failed existence probe proves NOTHING", failed.isSuccess)
        assertNotEquals(ProviderExistence.Absent, ProviderExistence.Exists)
    }
}
