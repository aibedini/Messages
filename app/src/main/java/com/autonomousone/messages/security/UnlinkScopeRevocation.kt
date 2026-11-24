package com.autonomousone.messages.security

import kotlinx.coroutines.CancellationException

/**
 * LDV-004 PR-E (slice 2c + 2d) — the unlink half of the revocation lifecycle.
 *
 * Unlinking the primary identity tears down the local trust registry and the keyring, so it
 * must withdraw every AI device's conversation scope FIRST, while the device rows and the
 * scope state still exist. [UnlinkScopeRevocation] is that step, extracted from
 * [PrimaryDeviceController] so it can be unit-tested on the JVM against fake devices, a fake
 * store and a fake outbox instead of a real phone.
 *
 * Slice 2d adds [SingleDeviceUnlinkScope]: the same guarantee for the "Unlink device" button
 * on ONE linked device. It pauses that device's scope (reason `unlinked`) before the
 * revocation is recorded and deletes its scope rows afterwards, so the local state matches the
 * device that is leaving — while the device-level `DEVICE_REVOKED` statement remains the
 * authoritative kill switch when the pause cannot be published.
 *
 * ## What it guarantees
 *
 *  - **Every linked AI device is attempted.** A failure for one device (a throwing
 *    coordinator, an outbox write error, a `Failed` result) is recorded and the loop continues;
 *    one device's failure never suppresses another device's revocation.
 *  - **Nothing aborts the unlink.** [UnlinkScopeRevocation.run] never throws except
 *    [CancellationException]; [SingleDeviceUnlinkScope.run] additionally lets the caller's own
 *    `revoke` failure propagate unchanged (that is the primary action the caller already
 *    reports). In both, a scope failure never stops the unlink: the device-level
 *    `DEVICE_REVOKED` flow in `PrimaryDeviceController` is the authoritative kill switch
 *    (design §4.5), and the unlink must complete even when the scope tombstones cannot be
 *    enqueued.
 *  - **Honest reporting.** [UnlinkScopeRevocation.Outcome] and
 *    [SingleDeviceUnlinkScope.Outcome] separate what was withdrawn, what was already
 *    withdrawn, what was never granted and what failed, so the caller can log counts/device ids
 *    instead of pretending everything worked.
 *
 * ## What it deliberately does not do
 *
 * It does not clear the scope tables for the primary unlink: `PrimaryDeviceController` does
 * that inside the local teardown transaction, AFTER this step, so a crash between the two
 * cannot lose the local record of a grant that was never withdrawn. (The single-device helper
 * DOES clear one device's rows, after its revocation succeeded.) It does not log anything
 * itself: the caller passes `onWarning`, which receives a device id and a failure stage only —
 * never a payload, a key, a conversation id or a message body.
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

/**
 * LDV-004 PR-E (slice 2d) — the SINGLE-device unlink hook behind the "Unlink device" button on
 * [com.autonomousone.messages.ui.screens.LinkedDevicesScreen].
 *
 * The unlink flow is `pause ▸ revoke ▸ forget`, and the order is the security property:
 *
 *  1. **Pause first**, while the device row and its scope still exist. A pause publishes the
 *     key-less `unlinked` tombstones and persists the revoked scope, so the AI loses the
 *     conversations even if the trust-registry write fails afterwards. A pause that fails for
 *     any reason is logged and never aborts the unlink.
 *  2. **Revoke** through the caller's own [TrustedDeviceRegistry.recordRevocation] (injected as
 *     `revoke` so this file stays Android-free). Its failure PROPAGATES: the caller already
 *     reports that failure, and the device is still trusted, so nothing must be forgotten.
 *  3. **Forget the scope rows** ([com.autonomousone.messages.data.LinkedDeviceScopeDao.deleteAll])
 *     only after the revocation succeeded. A failure here is logged and swallowed: the device
 *     is already revoked and the leftovers are inert, so the unlink must not be reported as
 *     failed.
 *
 * A `WEB_PWA` device can never hold a conversation grant (design §4.2/§5.8), so for it this
 * helper performs no scope operation at all — the existing browser flow is untouched.
 *
 * ## What is (and is not) logged
 *
 * [onWarning] receives a fixed message with the device id and, at most, the typed
 * [ScopeApplyStage]; never a conversation id, a payload, a ciphertext or a key. Callers are
 * expected to route it through the app's diagnostic log.
 */
class SingleDeviceUnlinkScope(
    private val pauseAll: suspend (GrantTarget, GrantRevokeReason) -> ScopeRevokeResult,
    private val deleteScopeRows: suspend (deviceId: String) -> Unit,
    private val onWarning: (message: String, error: Throwable?) -> Unit = { _, _ -> },
) {

    /** What the pause step did, if it ran at all. */
    enum class PauseOutcome {
        /** The device is not an AI device: there is no scope to pause. */
        NOT_APPLICABLE,

        /** Tombstones were enqueued and the revoked scope is durable. */
        PAUSED,

        /** The scope was already revoked; nothing was published. */
        ALREADY_REVOKED,

        /** The device never had a scope; nothing was published. */
        NOT_GRANTED,

        /** The pause failed; the revocation still proceeds (see the class KDoc). */
        FAILED,
    }

    /**
     * What happened during one unlink.
     *
     * [revocationSequence] is the trust sequence the caller's revocation returned (`null` when
     * the registry had nothing to revoke); [scopeRowsCleared] is false for a WEB_PWA device by
     * design and false when the delete failed.
     */
    data class Outcome(
        val isAiDevice: Boolean,
        val pauseOutcome: PauseOutcome,
        val tombstonesEnqueued: Int,
        val revocationSequence: Long?,
        val scopeRowsCleared: Boolean,
    )

    /**
     * Run the ordered unlink hook.
     *
     * @param device the leaving device. Only [GrantTarget.deviceId] is used by the pause (a
     *   revoke is key-less), so a rotated/unreadable key must never skip the pause.
     * @param isAiDevice `deviceType == AI_AGENT`. For any other type no scope call is made.
     * @param revoke the caller's revocation write, executed BETWEEN the pause and the row
     *   delete. A throwable from it propagates unchanged.
     */
    suspend fun run(
        device: GrantTarget,
        isAiDevice: Boolean,
        revoke: suspend () -> Long?,
    ): Outcome {
        if (!isAiDevice) {
            return Outcome(
                isAiDevice = false,
                pauseOutcome = PauseOutcome.NOT_APPLICABLE,
                tombstonesEnqueued = 0,
                revocationSequence = revoke(),
                scopeRowsCleared = false,
            )
        }

        var tombstones = 0
        val pauseOutcome = try {
            when (val result = pauseAll(device, GrantRevokeReason.UNLINKED)) {
                is ScopeRevokeResult.Revoked -> {
                    tombstones = result.published.size
                    PauseOutcome.PAUSED
                }

                ScopeRevokeResult.AlreadyRevoked -> PauseOutcome.ALREADY_REVOKED
                ScopeRevokeResult.NotGranted -> PauseOutcome.NOT_GRANTED
                is ScopeRevokeResult.Failed -> {
                    onWarning(
                        "single-device scope pause failed device=${device.deviceId} stage=${result.stage}",
                        result.cause,
                    )
                    PauseOutcome.FAILED
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // A coordinator/wiring exception must not abort the unlink.
            onWarning("single-device scope pause threw device=${device.deviceId}", t)
            PauseOutcome.FAILED
        }

        // The primary action. Its failure is the caller's to report, so it propagates and the
        // local scope rows are kept for the still-trusted device.
        val sequence = revoke()

        val cleared = try {
            deleteScopeRows(device.deviceId)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            onWarning("single-device scope rows not cleared device=${device.deviceId}", t)
            false
        }

        return Outcome(
            isAiDevice = true,
            pauseOutcome = pauseOutcome,
            tombstonesEnqueued = tombstones,
            revocationSequence = sequence,
            scopeRowsCleared = cleared,
        )
    }
}
