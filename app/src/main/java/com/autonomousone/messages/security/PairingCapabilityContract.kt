package com.autonomousone.messages.security

/**
 * ADR-007 capability contract — the Kotlin half of the SINGLE SOURCE OF TRUTH
 * in `protocol/pairing-protocol-v1.json::capability_definitions`.
 *
 * - [BASE_CAPABILITIES]: capabilities every linked browser ALWAYS receives.
 *   GMweb's server-side certificate validation requires exactly this set
 *   (certificate_capability_invalid if any is missing).
 * - [SENSITIVE_CAPABILITIES]: per-device, user-selectable grants (the rows in
 *   LinkedDevicesScreen + SensitiveGrantStore categories).
 * - [RESERVED_CAPABILITIES]: allowlisted by the server but not granted by
 *   today's Android UI.
 *
 * PairingCapabilityContractTest fails the build if these lists drift from the
 * protocol JSON. Do NOT edit this object without updating the JSON in the
 * same commit on BOTH repositories.
 */
object PairingCapabilityContract {
    val BASE_CAPABILITIES: List<String> = listOf(
        "READ_MESSAGES",
        "SEND_MESSAGES",
        "MARK_READ",
        "RECEIVE_NOTIFICATIONS",
    )

    val SENSITIVE_CAPABILITIES: List<String> = listOf(
        "READ_OTP",
        "READ_BANK_SECURITY",
        "READ_PASSWORD_RESET",
        "READ_AUTH_CODES",
        "READ_FINANCIAL_NOTIFICATIONS",
    )

    val RESERVED_CAPABILITIES: List<String> = listOf("MANAGE_DEVICES")

    /** base + sensitive — the exact capability universe this APK can grant. */
    val ALLOWLISTED_BY_ANDROID: List<String> = BASE_CAPABILITIES + SENSITIVE_CAPABILITIES
}
