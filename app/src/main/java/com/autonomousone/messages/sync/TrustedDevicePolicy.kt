package com.autonomousone.messages.sync

import com.autonomousone.messages.data.TrustedDeviceEntity
import com.autonomousone.messages.security.ConversationKeyRepository
import org.json.JSONArray

/**
 * Which linked devices count as authorized recipients of this phone's data.
 *
 * **This is now the ONLY copy, and that closed a live fragility rather than just a duplication.**
 * `ConversationKeyRepository` had its own `trusted` / `eligibleForHistoryKey` / `capabilities`, and the
 * two copies were identical in the trust expression but **not** in the capability parse:
 *
 * | | this policy | the repository's old copy |
 * |---|---|---|
 * | unreadable `capabilitiesJson` | empty set — fails closed | `JSONArray("not json")` **threw** |
 *
 * A throw there is not a local failure: `capabilities` is called from `eligibleForAccountKey` inside
 * `encrypt`, so ONE linked device with an unparseable capability list aborted the encryption of the
 * message being enqueued — which is to say a message that then could not replicate at all. The
 * fail-closed side of that table is the behaviour worth keeping, so the repository now calls this one.
 *
 * The rule was previously mirrored here on purpose: the original was covered only by *instrumented*
 * tests, and extracting it blind would have been a change to key publication that nothing fast could
 * verify. The condition for unifying was "add a JVM test for the original first", and the answer turned
 * out to be simpler — the original is now this, so the JVM tests that cover it cover the crypto path too.
 *
 * This is the "no consumer ⇒ no history destination" question behind
 * [ReplicationBlocker.MissingHistoryGrant]. It is NOT about Android permissions — that is
 * [ReplicationBlocker.TelephonyPermissionMissing].
 */
object TrustedDevicePolicy {

    const val GRANT_FULL_HISTORY = "FULL_HISTORY"
    const val GRANT_FROM_NOW_ON = "FROM_NOW_ON"

    /** Mirrors the statuses that count as trusted: publication-pending still does. */
    private val TRUSTED_STATUSES = setOf(
        TrustedDeviceEntity.STATUS_ACTIVE,
        TrustedDeviceEntity.STATUS_PENDING_PUBLICATION
    )

    fun isTrusted(device: TrustedDeviceEntity, now: Long): Boolean =
        device.status in TRUSTED_STATUSES &&
            device.certificateJson.isNotBlank() &&
            device.expiresAt > now &&
            device.revokedAt == null

    /**
     * The device's declared capability set.
     *
     * An unparseable list yields the empty set, which fails closed: a device whose capabilities
     * cannot be read is not treated as authorized for anything.
     */
    fun capabilities(device: TrustedDeviceEntity): Set<String> = runCatching {
        val values = JSONArray(device.capabilitiesJson)
        (0 until values.length()).mapTo(mutableSetOf()) { values.getString(it) }
    }.getOrDefault(emptySet())

    /** Mirrors `ConversationKeyRepository.eligibleForHistoryKey`. */
    fun isEligibleForHistory(device: TrustedDeviceEntity, now: Long): Boolean =
        isTrusted(device, now) &&
            device.historyGrant == GRANT_FULL_HISTORY &&
            ConversationKeyRepository.MESSAGE_DOMAIN in capabilities(device)

    /**
     * True when at least one linked device would actually receive full history.
     *
     * A linked device that asked for [GRANT_FROM_NOW_ON] does not make history backfill
     * worthwhile: it has explicitly declined the past, so scanning 360k rows for it would be
     * work with no destination.
     */
    fun hasFullHistoryConsumer(devices: List<TrustedDeviceEntity>, now: Long): Boolean =
        devices.any { isEligibleForHistory(it, now) }
}
