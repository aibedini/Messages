package com.autonomousone.messages

import com.autonomousone.messages.security.PrimaryTrustRoot
import java.security.MessageDigest
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class TrustStatementInteropFixtureTest {
    @Test
    fun `JVM canonical trust statement matches shared Node fixture`() {
        val raw = checkNotNull(javaClass.classLoader?.getResourceAsStream("trust-statement-jvm-fixture.json"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val fixture = JSONObject(raw)
        val canonical = PrimaryTrustRoot.canonicalTrustStatement(fixture.getJSONObject("statement"))
        val bytes = canonical.toByteArray(Charsets.UTF_8)
        assertEquals(fixture.getString("canonicalBase64"), Base64.getEncoder().encodeToString(bytes))
        assertEquals(
            fixture.getString("sha256"),
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
        )
    }
}
