package com.autonomousone.messages.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Structural guards for the carrier-delivery pipeline (source scans, not behaviour tests).
 *
 * The failures they prevent are regressions by ADDITION, which no behavioural test sees:
 *
 *  - someone adds a network call to the status receiver, making a carrier verdict depend on
 *    connectivity at the exact moment the broadcast window closes;
 *  - someone adds a second uploader for delivery reports, so the same verdict is sent twice or a
 *    report is acknowledged by the wrong path;
 *  - someone drops the DELIVERY PendingIntent for one of the two send branches (single vs multipart),
 *    silently reducing "carrier delivered" to "submitted" again;
 *  - someone exports the status receiver.
 *
 * NOTE: written, deliberately NOT executed (this task forbids running Gradle).
 */
class CarrierDeliveryPipelineGuardTest {

    private val mainRoots = listOf("src/main", "app/src/main")

    private fun mainSource(relative: String): File =
        mainRoots.map { File(it, relative) }.firstOrNull { it.isFile }
            ?: error("cannot locate $relative under ${mainRoots.joinToString()}")

    private fun allMainSources(): List<File> =
        mainRoots.map { File(it) }.firstOrNull { it.isDirectory }
            ?.walkTopDown()
            ?.filter { it.isFile && it.extension == "kt" }
            ?.toList()
            ?: error("cannot locate main sources under ${mainRoots.joinToString()}")

    private val receiver by lazy {
        mainSource("java/com/autonomousone/messages/sms/SmsStatusReceiver.kt").readText()
    }
    private val sender by lazy {
        mainSource("java/com/autonomousone/messages/sms/SmsSender.kt").readText()
    }

    // ── the receiver persists; it does not upload ────────────────────────────

    @Test
    fun `a carrier callback is persisted durably from the receiver`() {
        assertTrue(
            "the receiver must persist the verdict before anything else can see it",
            receiver.contains("GatewayDeliveryReports.recordFinal")
        )
    }

    @Test
    fun `the receiver performs no network work`() {
        for (forbidden in listOf("HttpURLConnection", "URL(", "ControlPlaneClient", "OkHttp", "openConnection")) {
            assertFalse(
                "a carrier verdict must not depend on the network in the receiver ($forbidden)",
                receiver.contains(forbidden)
            )
        }
    }

    @Test
    fun `the persisted verdict carries the part and send-time subscription metadata`() {
        assertTrue(receiver.contains("segmentIndex = partIndex"))
        assertTrue(receiver.contains("segmentCount = partCount"))
        assertTrue(
            "the subscription recorded must be the one carried by the callback, not a fresh lookup",
            receiver.contains("subscriptionId = subscriptionId")
        )
    }

    // ── both callbacks are requested for both send branches ──────────────────

    @Test
    fun `the sender requests a sent AND a delivery callback`() {
        assertTrue(sender.contains("SmsStatusReceiver.ACTION_SMS_SENT"))
        assertTrue(sender.contains("SmsStatusReceiver.ACTION_SMS_DELIVERED"))
    }

    @Test
    fun `both the single-part and the multipart send receive the delivery callbacks`() {
        assertTrue("multipart send must pass deliveredIntents", sender.contains("deliveredIntents"))
        // The single-part branch pairs the sent and delivered intents explicitly.
        assertTrue(
            sender.contains("sentIntents.single(), deliveredIntents?.single()")
        )
    }

    @Test
    fun `a gateway-originated send always asks for a carrier delivery report`() {
        assertTrue(
            "the local preference must not be able to remove GMweb's carrier evidence",
            sender.contains("GatewayDeliveryReports.isGatewayOriginated(context, sentId)") &&
                sender.contains("prefs.deliveryReportsEnabled || gatewayOriginated")
        )
    }

    // ── exactly one durable uploader for delivery reports ────────────────────

    @Test
    fun `only one main source uploads delivery reports`() {
        val uploaders = allMainSources().filter { it.readText().contains("/gateway/delivery-report") }

        assertEquals(
            "a second uploader would send the same verdict twice from two places",
            listOf("OutboxPoller.kt"),
            uploaders.map { it.name }
        )
    }

    @Test
    fun `the uploader acknowledges only through the durable acknowledgement`() {
        val uploader = mainRoots.map { File(it, "java/com/autonomousone/messages/gateway/OutboxPoller.kt") }
            .first { it.isFile }
            .readText()

        assertTrue(uploader.contains("GatewayDeliveryReports.pending("))
        assertTrue(uploader.contains("GatewayDeliveryReports.acknowledge("))
        // The ACK must be gated on the response code, not on having attempted the call.
        assertTrue(
            "acknowledgement must follow a 2xx check",
            uploader.contains("if (responseCode !in 200..299)")
        )
    }

    // ── the receiver is not a public surface ────────────────────────────────

    @Test
    fun `the status receiver is not exported`() {
        val manifest = mainSource("AndroidManifest.xml").readText()
        val block = manifest.substringAfter("SmsStatusReceiver", "")
            .substringBefore("/>")
        val receiverBlock = manifest.substringAfter("android:name=\".sms.SmsStatusReceiver\"").substringBefore("/>")

        assertTrue(
            "a callback receiver that only the system fires must not be exported",
            block.contains("android:exported=\"false\"") ||
                receiverBlock.contains("android:exported=\"false\"")
        )
    }
}
