package com.autonomousone.messages.gateway

object GatewayAccessPolicy {
    fun canStart(hasConsent: Boolean): Boolean = hasConsent
    fun canTransmit(hasConsent: Boolean, isEnabled: Boolean): Boolean = hasConsent && isEnabled

    /** Keep reconnecting only while both consent and persisted user intent allow it. */
    fun shouldAutoReconnect(hasConsent: Boolean, desiredEnabled: Boolean): Boolean =
        hasConsent && desiredEnabled
}
