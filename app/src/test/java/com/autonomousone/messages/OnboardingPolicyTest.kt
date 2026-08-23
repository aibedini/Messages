package com.autonomousone.messages

import com.autonomousone.messages.onboarding.OnboardingPolicy
import com.autonomousone.messages.onboarding.OnboardingStep
import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingPolicyTest {
    @Test fun `disclosure is always first`() {
        assertEquals(OnboardingStep.DISCLOSURE, OnboardingPolicy.resolveStep(false, false, false, false))
    }

    @Test fun `default role precedes restricted permissions`() {
        assertEquals(OnboardingStep.DEFAULT_SMS_ROLE, OnboardingPolicy.resolveStep(true, false, false, false))
        assertEquals(OnboardingStep.SMS_PERMISSIONS, OnboardingPolicy.resolveStep(true, true, false, false))
    }

    @Test fun `optional permissions can be skipped explicitly`() {
        assertEquals(OnboardingStep.OPTIONAL_PERMISSIONS, OnboardingPolicy.resolveStep(true, true, true, false))
        assertEquals(OnboardingStep.COMPLETE, OnboardingPolicy.resolveStep(true, true, true, true))
    }

    @Test fun `loss of default role returns user to role step`() {
        assertEquals(OnboardingStep.DEFAULT_SMS_ROLE, OnboardingPolicy.resolveStep(true, false, true, true))
    }
}
