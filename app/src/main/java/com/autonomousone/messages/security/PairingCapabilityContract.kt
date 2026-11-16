package com.autonomousone.messages.security

/**
 * ADR-007 capability contract — the Kotlin half of the SINGLE SOURCE OF TRUTH
 * in `protocol/pairing-protocol-v1.json::capability_definitions` +
 * `device_type_policy`.
 *
 * - [BASE_CAPABILITIES]: capabilities every linked browser ALWAYS receives.
 *   GMweb's server-side certificate validation requires exactly this set
 *   (certificate_capability_invalid if any is missing).
 * - [SENSITIVE_CAPABILITIES]: per-device, user-selectable grants (the rows in
 *   LinkedDevicesScreen + SensitiveGrantStore categories).
 * - [RESERVED_CAPABILITIES]: allowlisted by the server but not granted by
 *   today's Android UI.
 * - [AI_CAPABILITIES]: AI-device-only capabilities. They are NOT browser
 *   grants and never appear in [ALLOWLISTED_BY_ANDROID].
 *
 * XR-GA-008 / LDV-002 adds the `AI_AGENT` device type: exactly
 * `READ_MESSAGES` + `AI_SUGGEST`, history from-now-on only, and every send,
 * mark-read, manage-devices or sensitive capability explicitly forbidden.
 *
 * PairingCapabilityContractTest fails the build if these lists drift from the
 * protocol JSON. Do NOT edit this object without updating the JSON in the
 * same commit on BOTH repositories.
 */
object PairingCapabilityContract {
    const val DEVICE_TYPE_WEB_PWA = "WEB_PWA"
    const val DEVICE_TYPE_AI_AGENT = "AI_AGENT"

    const val HISTORY_FULL = "FULL_HISTORY"
    const val HISTORY_FROM_NOW_ON = "FROM_NOW_ON"

    val BASE_CAPABILITIES: List<String> = listOf(
        "READ_MESSAGES",
        "SEND_MESSAGES",
        "MARK_READ",
        "RECEIVE_NOTIFICATIONS",
    )

    val SENSITIVE_CAPABILITIES: List<String> = listOf(
        "CONTACTS_READ",
        "READ_OTP",
        "READ_BANK_SECURITY",
        "READ_PASSWORD_RESET",
        "READ_AUTH_CODES",
        "READ_FINANCIAL_NOTIFICATIONS",
    )

    val RESERVED_CAPABILITIES: List<String> = listOf("MANAGE_DEVICES")

    /** AI-device-only capability group (protocol `capability_definitions.ai`). */
    val AI_CAPABILITIES: List<String> = listOf("AI_SUGGEST")

    /** base + sensitive — the exact capability universe this APK can grant. */
    val ALLOWLISTED_BY_ANDROID: List<String> = BASE_CAPABILITIES + SENSITIVE_CAPABILITIES

    /** Mirror of `device_type_policy.AI_AGENT.required_capabilities`. */
    val AI_AGENT_REQUIRED_CAPABILITIES: List<String> = listOf("READ_MESSAGES", "AI_SUGGEST")

    /** Mirror of `device_type_policy.AI_AGENT.forbidden_capabilities`. */
    val AI_AGENT_FORBIDDEN_CAPABILITIES: List<String> = listOf(
        "SEND_MESSAGES",
        "MARK_READ",
        "RECEIVE_NOTIFICATIONS",
        "CONTACTS_READ",
        "READ_OTP",
        "READ_BANK_SECURITY",
        "READ_PASSWORD_RESET",
        "READ_AUTH_CODES",
        "READ_FINANCIAL_NOTIFICATIONS",
        "MANAGE_DEVICES",
    )

    /** Mirror of `device_type_policy.WEB_PWA.forbidden_capabilities`. */
    val WEB_PWA_FORBIDDEN_CAPABILITIES: List<String> = AI_CAPABILITIES

    private val ALL_KNOWN_CAPABILITIES: List<String> =
        BASE_CAPABILITIES + SENSITIVE_CAPABILITIES + RESERVED_CAPABILITIES + AI_CAPABILITIES

    fun isSupportedDeviceType(deviceType: String): Boolean =
        deviceType == DEVICE_TYPE_WEB_PWA || deviceType == DEVICE_TYPE_AI_AGENT

    /**
     * The ONLY way the app builds a certificate capability list.
     *
     * WEB_PWA keeps today's behaviour: the base set plus the sensitive grants
     * the operator enabled. AI_AGENT is always exactly
     * [AI_AGENT_REQUIRED_CAPABILITIES] — extra grants (including SEND_MESSAGES)
     * are structurally impossible. Unknown device types throw (fail closed).
     */
    fun capabilitiesFor(
        deviceType: String,
        sensitiveGrants: Collection<String> = emptyList(),
    ): List<String> = when (deviceType) {
        DEVICE_TYPE_WEB_PWA -> buildList {
            addAll(BASE_CAPABILITIES)
            SENSITIVE_CAPABILITIES.forEach { capability ->
                if (capability in sensitiveGrants) add(capability)
            }
        }
        DEVICE_TYPE_AI_AGENT -> AI_AGENT_REQUIRED_CAPABILITIES.toList()
        else -> throw IllegalArgumentException("unsupported_device_type")
    }

    /** AI_AGENT can never request full history. */
    fun historyGrantFor(deviceType: String, fullHistoryRequested: Boolean): String =
        when (deviceType) {
            DEVICE_TYPE_WEB_PWA -> if (fullHistoryRequested) HISTORY_FULL else HISTORY_FROM_NOW_ON
            DEVICE_TYPE_AI_AGENT -> HISTORY_FROM_NOW_ON
            else -> throw IllegalArgumentException("unsupported_device_type")
        }

    /**
     * Fail-closed predicate used immediately before signing a certificate and
     * before persisting a trusted-device row. Mirrors the GMweb certificate
     * policy so an invalid AI certificate can never be signed on the phone.
     */
    fun capabilitiesAreValidFor(deviceType: String, capabilities: Collection<String>): Boolean {
        val required: List<String>
        val optional: List<String>
        val forbidden: List<String>
        when (deviceType) {
            DEVICE_TYPE_WEB_PWA -> {
                required = BASE_CAPABILITIES
                optional = SENSITIVE_CAPABILITIES + RESERVED_CAPABILITIES
                forbidden = WEB_PWA_FORBIDDEN_CAPABILITIES
            }
            DEVICE_TYPE_AI_AGENT -> {
                required = AI_AGENT_REQUIRED_CAPABILITIES
                optional = emptyList()
                forbidden = AI_AGENT_FORBIDDEN_CAPABILITIES
            }
            else -> return false
        }
        val caps = capabilities.toSet()
        if (caps.any { it !in ALL_KNOWN_CAPABILITIES }) return false
        if (caps.any { it in forbidden }) return false
        if (!caps.containsAll(required)) return false
        return caps.all { it in required || it in optional }
    }
}
