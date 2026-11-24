package com.autonomousone.messages.sync

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reads the machine-readable naming contract shipped in `protocol/` and executes every example
 * through the producer. A future edit to either side fails here instead of at a replica.
 */
class SenderNamingContractTest {

    private fun contract(): JSONObject {
        val raw = checkNotNull(
            javaClass.classLoader?.getResourceAsStream("sender-naming-v1.json")
        ) { "protocol/sender-naming-v1.json missing from test resources" }
        return JSONObject(raw.bufferedReader(Charsets.UTF_8).use { it.readText() })
    }

    @Test
    fun `the protocol version and the producer version agree`() {
        assertEquals(SenderNaming.VERSION, contract().getInt("version"))
    }

    @Test
    fun `consumer sources are reserved and the phone only emits producer sources`() {
        val fields = contract().getJSONObject("fields")
        val source = fields.getJSONObject("displayNameSource")

        val producer = source.getJSONArray("producerEnum").strings().toSet()
        val reserved = source.getJSONArray("reservedForConsumers").strings().toSet()

        assertEquals(setOf("CONTACT", "ADDRESS", "UNKNOWN"), producer)
        assertEquals(setOf("USER", "DIRECTORY"), reserved)
        assertTrue("USER must outrank CONTACT", precedence().indexOf("USER") < precedence().indexOf("CONTACT"))
        assertTrue("DIRECTORY must outrank ADDRESS", precedence().indexOf("DIRECTORY") < precedence().indexOf("ADDRESS"))
    }

    @Test
    fun `the fallback rule is stated in the contract`() {
        val rules = contract().getJSONArray("rules").strings()
        assertTrue(
            rules.any { it.contains("must not overwrite", ignoreCase = true) }
        )
        assertTrue(
            rules.any { it.contains("never the literal label 'Unknown conversation'", ignoreCase = true) }
        )
    }

    @Test
    fun `every producer example round-trips through SenderNaming`() {
        val examples = contract().getJSONArray("examples")
        for (i in 0 until examples.length()) {
            val example = examples.getJSONObject(i)
            val address = example.getString("address")
            val expectedSource = example.getString("displayNameSource")
            val contactName = if (expectedSource == "CONTACT") example.getString("displayName") else null

            val naming = SenderNaming.resolve(address, null, contactName)

            assertEquals("address ${example}", address, naming.address)
            assertEquals("kind ${example}", example.getString("senderKind"), naming.senderKind)
            assertEquals("name ${example}", example.getString("displayName"), naming.displayName)
            assertEquals("source ${example}", expectedSource, naming.displayNameSource)
            assertTrue("displayName must never be blank: $example", naming.displayName.isNotBlank())
        }
    }

    private fun precedence(): List<String> =
        contract().getJSONArray("precedence").strings()

    private fun JSONArray.strings(): List<String> =
        (0 until length()).map { getString(it) }
}
