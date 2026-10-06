package com.autonomousone.messages.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Physical send ownership: exactly ONE place may submit to the radio.
 *
 * The bug this prevents is a regression by ADDITION. Today every caller (composer, GMweb, EVE,
 * delayed, resend, headless) reaches the radio through `SmsSender.dispatch()` → `SmsTransportGate`,
 * which is what makes per-SIM serialization and the throttle cooldown authoritative. A new caller with
 * its own `sendTextMessage(...)` would silently bypass both, and no behavioural test would notice
 * because no test exercises the new path.
 *
 * NOTE: written and executed as part of this change (source scan, no Android runtime).
 */
class SmsPhysicalSendOwnershipTest {

    private val mainRoots = listOf("src/main", "app/src/main")

    private fun mainSources(): List<File> =
        mainRoots.map { File(it) }.firstOrNull { it.isDirectory }
            ?.walkTopDown()
            ?.filter { it.isFile && it.extension == "kt" }
            ?.toList()
            ?: error("cannot locate main sources; looked in ${mainRoots.joinToString()}")

    /** Call sites, ignoring the definition itself and any comment mentioning it. */
    private fun callSitesOf(marker: String): List<String> =
        mainSources()
            .filter { file ->
                file.readLines().any { line ->
                    val trimmed = line.trim()
                    !trimmed.startsWith("*") && !trimmed.startsWith("//") && trimmed.contains(marker)
                }
            }
            .map { it.name }
            .sorted()

    @Test
    fun `only the send owner calls sendTextMessage`() {
        assertEquals(
            "every physical submit must go through SmsSender's gate",
            listOf("SmsSender.kt"),
            callSitesOf("sendTextMessage(")
        )
    }

    @Test
    fun `only the send owner calls sendMultipartTextMessage`() {
        assertEquals(
            listOf("SmsSender.kt"),
            callSitesOf("sendMultipartTextMessage(")
        )
    }

    @Test
    fun `the physical submit is wrapped by the gate`() {
        val sender = mainRoots.map { File(it, "java/com/autonomousone/messages/sms/SmsSender.kt") }
            .first { it.isFile }
            .readText()

        val gateIndex = sender.indexOf("SmsTransportGate.submit(")
        // The QUALIFIED call, so a mention in a comment cannot satisfy or defeat this check.
        val submitIndex = sender.indexOf("manager.sendTextMessage(")

        assertTrue("the submit must be gated", gateIndex > 0)
        assertTrue("the physical single-part submit must exist", submitIndex > 0)
        assertTrue("the gate call must precede the physical submit", gateIndex < submitIndex)
        assertTrue(
            "the multipart submit must be inside the same gated block",
            sender.indexOf("manager.sendMultipartTextMessage(") > gateIndex
        )
    }

    @Test
    fun `the bypassing rate limiter is gone`() {
        val hits = mainSources().filter { it.readText().contains("SendRateLimiter.acquireSlot") }

        assertTrue("SendRateLimiter.acquireSlot must not exist anywhere", hits.isEmpty())
        assertTrue(
            "Thread.sleep is banned in the send path",
            hits.none { it.readText().contains("Thread.sleep") }
        )
    }
}
