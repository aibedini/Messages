package com.autonomousone.messages.gateway

/**
 * Why a device-telemetry report was requested.
 *
 * Every reason is a moment GMweb's picture of this phone becomes WRONG if nothing is sent: the
 * process started, the network came back, the gateway reached the server, the SIM list changed, the
 * user granted Phone permission, the default SMS line changed, the app was updated. A 60-second timer
 * alone cannot express any of them — it can only be late for all of them — which is why the periodic
 * heartbeat stays but is no longer the only trigger.
 *
 * The value travels in the telemetry payload and in local diagnostics only. It never contains user
 * content.
 */
enum class TelemetryTrigger(val wireValue: String) {
    STARTUP("STARTUP"),
    NETWORK_RECONNECTED("NETWORK_RECONNECTED"),
    GATEWAY_CONNECTED("GATEWAY_CONNECTED"),
    SUBSCRIPTIONS_CHANGED("SUBSCRIPTIONS_CHANGED"),
    PHONE_PERMISSION_GRANTED("PHONE_PERMISSION_GRANTED"),
    DEFAULT_SMS_CHANGED("DEFAULT_SMS_CHANGED"),
    APP_UPDATED("APP_UPDATED"),
    MANUAL_DIAGNOSTIC_REFRESH("MANUAL_DIAGNOSTIC_REFRESH"),

    /** The 60-second heartbeat. Never the only trigger, never suppressed. */
    PERIODIC("PERIODIC"),
}
