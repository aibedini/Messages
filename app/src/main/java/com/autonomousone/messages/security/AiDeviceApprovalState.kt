package com.autonomousone.messages.security

/**
 * XR-GA-008 / LDV-003 — approval state for linking an AI device.
 *
 * This is deliberately PURE (no Android, no Room, no Context) so the
 * confirmation rules are unit-tested instead of living only in a Composable:
 *
 *  - the operator must explicitly acknowledge the AI profile (a plain
 *    "Link device" tap is not enough for an AI device),
 *  - history is always [PairingCapabilityContract.HISTORY_FROM_NOW_ON],
 *  - the requested capabilities are exactly `READ_MESSAGES` + `AI_SUGGEST`,
 *  - `SEND_MESSAGES` is always displayed as NEVER granted.
 *
 * The existing WEB_PWA flow does not use this state and is unchanged.
 */
data class AiDeviceApprovalState(
    val acknowledged: Boolean = false,
    val historyGrant: String = PairingCapabilityContract.HISTORY_FROM_NOW_ON,
) {
    val requestedCapabilities: List<String> = PairingCapabilityContract.AI_AGENT_REQUIRED_CAPABILITIES
    val neverGrantedCapabilities: List<String> = listOf("SEND_MESSAGES")
    val historyLockedFromNowOn: Boolean
        get() = historyGrant == PairingCapabilityContract.HISTORY_FROM_NOW_ON

    /** True only after the operator explicitly acknowledged the AI profile. */
    val isReadyToConfirm: Boolean
        get() = acknowledged && historyLockedFromNowOn

    companion object {
        const val ACKNOWLEDGEMENT =
            "I understand: this AI device can read my messages and suggest replies, " +
                "but it can never send messages, mark them read, manage devices or " +
                "receive sensitive categories. History starts from now on."
    }
}
