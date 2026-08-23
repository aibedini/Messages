package com.autonomousone.messages.onboarding

enum class OnboardingStep {
    DISCLOSURE,
    DEFAULT_SMS_ROLE,
    SMS_PERMISSIONS,
    OPTIONAL_PERMISSIONS,
    COMPLETE,
}

data class OnboardingState(
    val step: OnboardingStep,
    val smsPermissionPermanentlyDenied: Boolean = false,
    val contactsPermissionPermanentlyDenied: Boolean = false,
    val notificationsPermissionPermanentlyDenied: Boolean = false,
)

object OnboardingPolicy {
    fun resolveStep(
        disclosureAccepted: Boolean,
        isDefaultSmsApp: Boolean,
        hasSmsPermissions: Boolean,
        optionalStepCompleted: Boolean,
    ): OnboardingStep = when {
        !disclosureAccepted -> OnboardingStep.DISCLOSURE
        !isDefaultSmsApp -> OnboardingStep.DEFAULT_SMS_ROLE
        !hasSmsPermissions -> OnboardingStep.SMS_PERMISSIONS
        !optionalStepCompleted -> OnboardingStep.OPTIONAL_PERMISSIONS
        else -> OnboardingStep.COMPLETE
    }
}
