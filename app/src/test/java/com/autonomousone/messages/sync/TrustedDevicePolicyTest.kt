package com.autonomousone.messages.sync

import com.autonomousone.messages.data.TrustedDeviceEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "does history backfill have a destination?" rule.
 *
 * This is the JVM coverage the original predicate in `ConversationKeyRepository` never had, and
 * it is the reason the rule is mirrored here rather than extracted blind — see the KDoc on
 * [TrustedDevicePolicy].
 */
class TrustedDevicePolicyTest {

    private val now = 1_700_000_000_000L

    private fun device(
        deviceId: String = "web-1",
        status: String = TrustedDeviceEntity.STATUS_ACTIVE,
        historyGrant: String = TrustedDevicePolicy.GRANT_FULL_HISTORY,
        capabilitiesJson: String = """["READ_MESSAGES"]""",
        certificateJson: String = """{"kind":"DeviceCertificate"}""",
        expiresAt: Long = now + 60_000L,
        revokedAt: Long? = null
    ) = TrustedDeviceEntity(
        deviceId = deviceId,
        accountId = "account-1",
        displayName = "Chrome on Windows",
        deviceType = "WEB_PWA",
        origin = "https://gmweb.example.com",
        signingPublicKey = "spki-sign",
        encryptionPublicKey = "spki-enc",
        capabilitiesJson = capabilitiesJson,
        historyGrant = historyGrant,
        certificateJson = certificateJson,
        certificateSignature = "signature",
        trustSequence = 1,
        status = status,
        approvedAt = now - 120_000L,
        expiresAt = expiresAt,
        revokedAt = revokedAt,
        createdAt = now - 180_000L,
        updatedAt = now - 60_000L
    )

    @Test
    fun `anActiveFullHistoryDeviceWithReadMessagesIsAConsumer`() {
        assertTrue(TrustedDevicePolicy.isEligibleForHistory(device(), now))
        assertTrue(TrustedDevicePolicy.hasFullHistoryConsumer(listOf(device()), now))
    }

    @Test
    fun `fromNowOnHasNoInterestInThePastSoItIsNotADestination`() {
        // Scanning 360k rows for a device that declined history would be work with no consumer.
        val passive = device(historyGrant = TrustedDevicePolicy.GRANT_FROM_NOW_ON)

        assertTrue(TrustedDevicePolicy.isTrusted(passive, now))
        assertFalse(TrustedDevicePolicy.isEligibleForHistory(passive, now))
        assertFalse(TrustedDevicePolicy.hasFullHistoryConsumer(listOf(passive), now))
    }

    @Test
    fun `publicationPendingCountsAsTrusted`() {
        // Mirrors the key publisher: a device approved but not yet published still receives keys.
        val pending = device(status = TrustedDeviceEntity.STATUS_PENDING_PUBLICATION)

        assertTrue(TrustedDevicePolicy.isEligibleForHistory(pending, now))
    }

    @Test
    fun `aRevokedDeviceIsNotADestination`() {
        val revoked = device(status = TrustedDeviceEntity.STATUS_REVOKED)

        assertFalse(TrustedDevicePolicy.isEligibleForHistory(revoked, now))
        // A live status with a revocation stamp must also be refused.
        assertFalse(
            TrustedDevicePolicy.isEligibleForHistory(
                device(revokedAt = now - 1_000L),
                now
            )
        )
    }

    @Test
    fun `anExpiredDeviceIsNotADestination`() {
        assertFalse(TrustedDevicePolicy.isEligibleForHistory(device(expiresAt = now - 1L), now))
        // Exactly at expiry is already expired.
        assertFalse(TrustedDevicePolicy.isEligibleForHistory(device(expiresAt = now), now))
    }

    @Test
    fun `anUncertifiedDeviceIsNotADestination`() {
        assertFalse(TrustedDevicePolicy.isEligibleForHistory(device(certificateJson = ""), now))
    }

    @Test
    fun `aDeviceWithoutTheMessageCapabilityIsNotADestination`() {
        val wrongDomain = device(capabilitiesJson = """["CONTACTS_READ"]""")

        assertTrue(TrustedDevicePolicy.isTrusted(wrongDomain, now))
        assertFalse(TrustedDevicePolicy.isEligibleForHistory(wrongDomain, now))
    }

    @Test
    fun `unreadableCapabilitiesFailClosed`() {
        // A device whose capabilities cannot be parsed is authorized for nothing.
        assertTrue(TrustedDevicePolicy.capabilities(device(capabilitiesJson = "not json")).isEmpty())
        assertFalse(
            TrustedDevicePolicy.isEligibleForHistory(device(capabilitiesJson = "not json"), now)
        )
    }

    @Test
    fun `theUnguardedParseThisReplacedWouldHaveThrown`() {
        // The evidence for unifying the two copies, and for the behaviour change it carried. The
        // repository's old `capabilities` called `JSONArray(...)` with no guard, and that call THROWS on a
        // malformed list. It sits inside `eligibleForAccountKey`, which is called from `encrypt` — so one
        // unparseable device row aborted the encryption of the message being enqueued, and a message that
        // cannot be encrypted cannot be replicated.
        //
        // `org.json` is the real implementation in this test JVM (only `android.*` is stubbed), so this is
        // the actual library behaviour the old code depended on.
        assertThrows(org.json.JSONException::class.java) {
            org.json.JSONArray("not json")
        }
    }

    @Test
    fun `onlyOneDeviceNeedsToAskForFullHistory`() {        val devices = listOf(
            device(deviceId = "web-1", historyGrant = TrustedDevicePolicy.GRANT_FROM_NOW_ON),
            device(deviceId = "web-2", status = TrustedDeviceEntity.STATUS_REVOKED),
            device(deviceId = "web-3")
        )

        assertTrue(TrustedDevicePolicy.hasFullHistoryConsumer(devices, now))
        assertFalse(TrustedDevicePolicy.hasFullHistoryConsumer(emptyList(), now))
    }

    @Test
    fun `multipleCapabilitiesAreReadAsASet`() {
        val multi = device(capabilitiesJson = """["CONTACTS_READ","READ_MESSAGES"]""")

        assertTrue(TrustedDevicePolicy.capabilities(multi).containsAll(
            setOf("CONTACTS_READ", "READ_MESSAGES")
        ))
        assertTrue(TrustedDevicePolicy.isEligibleForHistory(multi, now))
    }
}
