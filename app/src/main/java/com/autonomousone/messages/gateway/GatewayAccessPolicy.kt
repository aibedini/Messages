package com.autonomousone.messages.gateway

object GatewayAccessPolicy {
    fun canStart(hasConsent: Boolean): Boolean = hasConsent
    fun canTransmit(hasConsent: Boolean, isEnabled: Boolean): Boolean = hasConsent && isEnabled
}
