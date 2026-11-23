package com.autonomousone.messages.repository

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the "Exported 500 messages in 1 conversation" failure:
 * a short read must never be accepted as a successful archive.
 */
class DataTransferCompletenessTest {

    @Test
    fun `complete read is accepted`() {
        requireCompleteArchive(read = 50_000, expected = 50_000, label = "export")
    }

    @Test
    fun `extra messages arriving during the run are accepted`() {
        requireCompleteArchive(read = 50_010, expected = 50_000, label = "backup")
    }

    @Test
    fun `one-page read of a large inbox is rejected with both counts`() {
        val error = try {
            requireCompleteArchive(read = 500, expected = 50_000, label = "export")
            null
        } catch (e: DataTransferException) {
            e
        }

        assertNotNull("A short read must throw instead of producing a partial archive", error)
        assertTrue(
            "The error must say how much was read and expected",
            error!!.message!!.contains("500 of 50000")
        )
        assertTrue(
            "The error must say the partial file was discarded",
            error.message!!.contains("discarded")
        )
    }
}
