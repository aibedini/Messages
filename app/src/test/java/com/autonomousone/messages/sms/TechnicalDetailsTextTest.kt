package com.autonomousone.messages.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `Copy technical details` payload.
 *
 * ## Why the privacy assertions matter as much as the content ones
 *
 * This is the one place diagnostics leave the app, and the text lands in a clipboard that other apps
 * can read. Two failures are possible and both are silent: omitting the real evidence (a copy action
 * that looks like proof but says nothing), and including something it must not (a message body, an
 * OTP, a full number, a privileged SIM identifier).
 *
 * The builder takes a narrow `Evidence` value rather than a `MessageEntity`, so the body and the
 * address are not even reachable from it — and that is asserted below rather than trusted.
 */
class TechnicalDetailsTextTest {

    private fun evidence(
        transportState: String? = null,
        sendFailureCode: String? = null,
        sendResultCode: Int? = null,
        radioErrorCode: Int? = null,
        deliveryEvidence: String? = null,
        deliveryTpStatus: Int? = null,
        deliveryResultCode: Int? = null
    ) = TechnicalDetailsText.Evidence(
        source = "sms",
        providerId = 1415L,
        threadId = 100L,
        isOutgoing = true,
        sentAt = 1_700_000_000_000L,
        dateSent = 0L,
        transportState = transportState,
        sendFailureCode = sendFailureCode,
        sendResultCode = sendResultCode,
        radioErrorCode = radioErrorCode,
        sendStateUpdatedAt = 1_700_000_000_500L,
        deliveryEvidence = deliveryEvidence,
        deliveryTpStatus = deliveryTpStatus,
        deliveryResultCode = deliveryResultCode,
        deliveryCallbackAt = 0L
    )

    private fun build(
        uiState: String?,
        e: TechnicalDetailsText.Evidence,
        numberTail: String? = null,
        partCount: Int? = null
    ) = TechnicalDetailsText.build(
        uiState = uiState,
        evidence = e,
        appVersion = "3.5.2 (139)",
        simLabel = "SIM 1 · MCI",
        numberTail = numberTail,
        partCount = partCount
    )

    // ── real values, not placeholders ────────────────────────────────────────

    @Test
    fun `result 106 is copied with its symbolic name`() {
        // 106 is RESULT_RIL_REQUEST_RATE_LIMITED: the radio refused because requests were too frequent.
        // Reporting it as an unknown vendor value is how a rate limit stays invisible.
        val text = build(
            "NOT_SENT",
            evidence(transportState = "NOT_SENT", sendResultCode = 106, sendFailureCode = "RIL_RATE_LIMITED")
        )

        assertTrue(text, text.contains("sendResultCode: 106 (RESULT_RIL_REQUEST_RATE_LIMITED)"))
        assertTrue("the failure code must travel too", text.contains("sendFailureCode: RIL_RATE_LIMITED"))
    }

    @Test
    fun `tp-status is copied in decimal and in the hex the spec uses`() {
        val text = build("NOT_DELIVERED", evidence(deliveryEvidence = "FAILED", deliveryTpStatus = 0x40))

        assertTrue(text, text.contains("deliveryTpStatus: 64 (0x40)"))
        assertTrue(text, text.contains("deliveryEvidence: FAILED"))
    }

    @Test
    fun `a rate-limited message and a delivered one produce different text`() {
        // The requirement that the copy belongs to the SELECTED message: two messages with different
        // evidence must never share a payload.
        val rateLimited = build("NOT_SENT", evidence(sendResultCode = 106, sendFailureCode = "RIL_RATE_LIMITED"))
        val delivered = build("DELIVERED", evidence(deliveryEvidence = "DELIVERED", deliveryTpStatus = 0x00))

        assertFalse(rateLimited == delivered)
        assertTrue(rateLimited.contains("106"))
        assertFalse("the failure must not leak into the delivered report", delivered.contains("106"))
        assertTrue(delivered.contains("deliveryEvidence: DELIVERED"))
    }

    @Test
    fun `unknown evidence is omitted rather than invented`() {
        val text = build("SENDING", evidence(transportState = "SENT_PENDING"))

        assertFalse("no delivery report means no delivery line", text.contains("deliveryTpStatus"))
        assertFalse(text.contains("deliveryEvidence"))
        assertFalse("no SENT result means no result line", text.contains("sendResultCode"))
        assertTrue(text.contains("transport: SENT_PENDING"))
    }

    @Test
    fun `a part count is included only when the ledger knows it`() {
        val known = build("SENT", evidence(), partCount = 3)
        val unknown = build("SENT", evidence())

        assertTrue(known.contains("parts: 3"))
        // Absent, not defaulted to 1: a guessed count on a multipart message misreports the billing.
        assertFalse(unknown.contains("parts:"))
    }

    @Test
    fun `a row with no app-owned state says so instead of claiming a status`() {
        val text = build(null, evidence())

        assertTrue(text, text.contains("status: no-app-owned-state"))
    }

    // ── privacy ──────────────────────────────────────────────────────────────

    @Test
    fun `the recipient appears only as a masked tail`() {
        val text = build("SENT", evidence(), numberTail = TechnicalDetailsText.numberTail("+989123456789"))

        assertTrue(text, text.contains("recipient: ***6789"))
        assertFalse("the full number must never appear", text.contains("989123456789"))
    }

    @Test
    fun `a number tail is at most four digits`() {
        assertEquals("***6789", TechnicalDetailsText.numberTail("+98 912 345 6789"))
        assertEquals("***0001", TechnicalDetailsText.numberTail("+1 (555) 000-0001"))
        // Too short to mask meaningfully: no digits are exposed at all.
        assertEquals("***", TechnicalDetailsText.numberTail("123"))
        assertEquals("***", TechnicalDetailsText.numberTail(""))
    }

    @Test
    fun `the builder cannot reach private content because it is never given any`() {
        // The Evidence value carries no body and no address, so no future edit to the formatting can
        // leak one. Asserted structurally: the type has no such property.
        val names = TechnicalDetailsText.Evidence::class.java.declaredFields.map { it.name.lowercase() }

        for (forbidden in listOf("body", "message", "address", "number", "iccid", "imsi", "serial")) {
            assertFalse(
                "Evidence must not carry $forbidden",
                names.any { it.contains(forbidden) }
            )
        }
    }

    @Test
    fun `the copied text contains none of the forbidden identifiers`() {
        val text = build(
            "NOT_DELIVERED",
            evidence(
                transportState = "SENT_CONFIRMED",
                sendResultCode = 1,
                radioErrorCode = 7,
                deliveryEvidence = "FAILED",
                deliveryTpStatus = 0x40,
                deliveryResultCode = 2
            ),
            numberTail = "***6789",
            partCount = 2
        )

        // A short tail is allowed and expected; nothing longer may appear.
        for (forbidden in listOf("iccid", "imsi", "serial", "hmac", "keystore", "otp", "body")) {
            assertFalse("$forbidden must never be copied", text.lowercase().contains(forbidden))
        }
        // And no full phone number: the only digits present are the masked tail and the codes.
        assertFalse(text.contains("+98"))
        assertFalse(text.contains("989123456789"))
    }

    @Test
    fun `the copied text carries the identifiers a support report needs`() {
        val text = build("NOT_SENT", evidence(sendResultCode = 106), numberTail = "***6789")

        assertTrue(text.contains("Messages 3.5.2 (139)"))
        assertTrue(text.contains("providerId: 1415"))
        assertTrue(text.contains("threadId: 100"))
        assertTrue(text.contains("source: sms"))
        assertTrue(text.contains("outgoing: true"))
    }
}
