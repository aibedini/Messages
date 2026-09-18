package com.autonomousone.messages

import com.autonomousone.messages.data.ProviderRead
import com.autonomousone.messages.data.map
import com.autonomousone.messages.data.provesAbsence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sync-critical read contract: "the provider answered and there is no such
 * row" must never be confused with "I could not read the provider".
 *
 * WRITTEN BUT NOT EXECUTED.
 */
class ProviderReadTest {

    @Test
    fun `success carries a value and maps`() {
        val read: ProviderRead<List<Int>> = ProviderRead.Success(listOf(1, 2))
        val mapped = read.map { it.size }
        assertTrue("mapping a Success stays a Success", mapped is ProviderRead.Success)
        // map { it.size } yields Success(2) - NOT the original list.
        assertEquals(2, (mapped as ProviderRead.Success).value)
    }

    @Test
    fun `failure stays a failure through map`() {
        val read: ProviderRead<List<Int>> =
            ProviderRead.Failure(ProviderRead.Reason.BINDER)
        assertTrue(read.map { it.size } is ProviderRead.Failure)
    }

    @Test
    fun `only a successful null proves absence`() {
        val absent: ProviderRead<String?> = ProviderRead.Success(null)
        val present: ProviderRead<String?> = ProviderRead.Success("row")
        val failed: ProviderRead<String?> =
            ProviderRead.Failure(ProviderRead.Reason.QUERY_RETURNED_NULL)

        assertTrue(absent.provesAbsence)
        assertFalse("a present row is not absence", present.provesAbsence)
        assertFalse("a failed read is NEVER absence", failed.provesAbsence)
    }

    @Test
    fun `empty success and failure are different facts`() {
        val emptyButSuccessful: ProviderRead<List<Int>> = ProviderRead.Success(emptyList())
        val failed: ProviderRead<List<Int>> =
            ProviderRead.Failure(ProviderRead.Reason.PROVIDER_UNAVAILABLE)

        assertTrue(emptyButSuccessful is ProviderRead.Success)
        assertTrue(failed is ProviderRead.Failure)
    }

    @Test
    fun `null cursor has its own reason`() {
        assertEquals(
            ProviderRead.Reason.QUERY_RETURNED_NULL,
            (ProviderRead.Failure(ProviderRead.Reason.QUERY_RETURNED_NULL) as ProviderRead.Failure).reason
        )
    }
}
