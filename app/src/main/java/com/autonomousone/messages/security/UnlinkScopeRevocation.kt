package com.autonomousone.messages.security

import kotlinx.coroutines.CancellationException

/**
 * LDV-004 PR-E (slice 2c) — the primary-unlink half of the revocation lifecycle.
 *
 * Unlinking the primary identity tears down the local trust registry and the keyring, so it
 * must withdraw every AI device's conversation scope FIRST, while the device rows and the
 * scope state still exist. This class is that step, extracted from
 * [PrimaryDeviceController] so it can be unit-tested on the JVM against fake devices, a fake
 * store and a fake outbox instead of a real phone.
 *
 * ## What it guarantees
 *
 *  - **Every linked AI device is attempted.** A failure for one device (a throwing
 *    coordinator, an outbox write error, a `Failed` result) is recorded and the loop continues;
 *    one device's failure never suppresses another device's revocation.
 *  - **Nothing aborts the unlink.** [run] never throws except [CancellationException]: the
 *    device-level `DEVICE_REVOKED` flow in `PrimaryDeviceController` is the authoritative kill
 *    switch (design §4.5), and the unlink must complete even when the scope tombstones cannot
 *    be enqueued.
 *  - **Honest reporting.** [Outcome] separates what was withdrawn, what was already withdrawn,
 *    what was never granted and what failed, so the caller can log counts/device ids instead of
 *    pretending everything worked.
 *
 * ## What it deliberately does not do
 *
 * It does not clear the scope tables: `PrimaryDeviceController` does that inside the local
 * teardown transaction, AFTER this step, so a crash between the two cannot lose the local
 * record of a grant that was never withdrawn. It does not log anything itself: the caller
 * passes [onWarning], which receives a device id and a failure stage only — never a payload,
 * a key, a conversation id or a message body.
 *
 * ## Known limitation (documented, not fixable here)
 *
 * The tombstones are enqueued into `gateway_event_outbox`, which `EventUploader` drains. The
 * same unlink closes that uploader's identity gate, so in the common case these tombstones are
 * durable but not transmitted; the device-level `DEVICE_REVOKED` statement published by the
 * existing flow is what actually revokes the device at GMweb/god-ai (design §4.5, device
 * level). The tombstones remain correct and deterministic if the outbox is ever drained (for
 * example after a re-enrollment).
 */
class UnlinkScopeRevocation(
    private val aiDevices: suspend () -> List<GrantTarget>,
    private val pauseAll: suspend (GrantTarget, GrantRevokeReason) -> ScopeRevokeResult,
    private val onWarning: (message: String, error: Throwable?) -> Unit = { _, _ -> },
) {

    /** What happened to each AI device, by device id. */
    data class Outcome(
        val paused: List<String> = emptyList(),
        val alreadyRevoked: List<String> = emptyList(),
        val notGranted: List<String> = emptyList(),
        val failed: List<String> = emptyList(),
        /** True when the trust registry could not be read: no device was even attempted. */
        val enumerationFailed: Boolean = false,
    ) {
        /** How many AI devices were evaluated (0 when enumeration failed). */
        val attempted: Int
            get() = paused.size + alreadyRevoked.size + notGranted.size + failed.size

        /** True only when every linked AI device's scope is known to be withdrawn. */
        val allWithdrawn: Boolean
            get() = !enumerationFailed && failed.isEmpty()
    }

    /**
     * Withdraw the scope of every linked AI device.
     *
     * @param reason `unlinked` for the primary unlink (the default); the wire vocabulary is
     *   revoked|superseded|unlinked|expired (design §4.2).
     */
    suspend fun run(reason: GrantRevokeReason = GrantRevokeReason.UNLINKED): Outcome {
        val targets = try {
            aiDevices()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            onWarning("scope pause skipped: linked AI devices unreadable", t)
            return Outcome(enumerationFailed = true)
        }

        val paused = ArrayList<String>(targets.size)
        val alreadyRevoked = ArrayList<String>()
        val notGranted = ArrayList<String>()
        val failed = ArrayList<String>()
        for (target in targets.distinctBy { it.deviceId }) {
            try {
                when (val result = pauseAll(target, reason)) {
                    is ScopeRevokeResult.Revoked -> paused += target.deviceId
                    ScopeRevokeResult.AlreadyRevoked -> alreadyRevoked += target.deviceId
                    ScopeRevokeResult.NotGranted -> notGranted += target.deviceId
                    is ScopeRevokeResult.Failed -> {
                        failed += target.deviceId
                        onWarning(
                            "scope pause failed device=${target.deviceId} stage=${result.stage}",
                            result.cause,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // A coordinator/wiring exception must not abort the unlink.
                failed += target.deviceId
                onWarning("scope pause threw device=${target.deviceId}", t)
            }
        }
        return Outcome(
            paused = paused,
            alreadyRevoked = alreadyRevoked,
            notGranted = notGranted,
            failed = failed,
        )
    }
}
