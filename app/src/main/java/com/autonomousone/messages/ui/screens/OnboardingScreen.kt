package com.autonomousone.messages.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.autonomousone.messages.onboarding.OnboardingState
import com.autonomousone.messages.onboarding.OnboardingStep

@Composable
fun OnboardingScreen(
    state: OnboardingState,
    hasContactsPermission: Boolean,
    hasNotificationsPermission: Boolean,
    onAcceptDisclosure: () -> Unit,
    onRequestDefaultRole: () -> Unit,
    onRequestSmsPermissions: () -> Unit,
    onRequestContactsPermission: () -> Unit,
    onRequestNotificationsPermission: () -> Unit,
    onCompleteOptionalStep: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        StepProgress(state.step)
        Spacer(Modifier.height(32.dp))
        Icon(
            imageVector = when (state.step) {
                OnboardingStep.DISCLOSURE -> Icons.Default.Security
                OnboardingStep.DEFAULT_SMS_ROLE, OnboardingStep.SMS_PERMISSIONS -> Icons.AutoMirrored.Filled.Message
                else -> Icons.Default.Lock
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            titleFor(state.step),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(12.dp))
        Text(bodyFor(state.step), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))

        if (state.step == OnboardingStep.DISCLOSURE) {
            DisclosureCard()
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onOpenPrivacyPolicy, modifier = Modifier.fillMaxWidth()) {
                Text("Read privacy policy")
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onAcceptDisclosure, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("I understand and continue")
            }
        }

        if (state.step == OnboardingStep.DEFAULT_SMS_ROLE) {
            Button(onClick = onRequestDefaultRole, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Set as default SMS app")
            }
        }

        if (state.step == OnboardingStep.SMS_PERMISSIONS) {
            if (state.smsPermissionPermanentlyDenied) {
                Text("SMS access was permanently denied. Enable it in Android app settings to continue.", color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Open app settings") }
            } else {
                Button(onClick = onRequestSmsPermissions, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("Allow SMS access")
                }
            }
        }

        if (state.step == OnboardingStep.OPTIONAL_PERMISSIONS) {
            PermissionAction(
                label = if (hasContactsPermission) "Contacts allowed" else "Allow contacts (optional)",
                permanentlyDenied = state.contactsPermissionPermanentlyDenied,
                onRequest = onRequestContactsPermission,
                onOpenSettings = onOpenSettings,
            )
            Spacer(Modifier.height(8.dp))
            PermissionAction(
                label = if (hasNotificationsPermission) "Notifications allowed" else "Allow notifications (optional)",
                permanentlyDenied = state.notificationsPermissionPermanentlyDenied,
                onRequest = onRequestNotificationsPermission,
                onOpenSettings = onOpenSettings,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onCompleteOptionalStep, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Continue to Messages")
            }
        }
    }
}

@Composable
private fun DisclosureCard() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Messages reads, receives and sends SMS only to provide its core messaging features.")
            Text("Contacts access is optional and is used only to show names for phone numbers.")
            Text("The SMS Gateway is off by default. If you enable it later, a separate consent screen explains what message data can be sent and where.")
        }
    }
}

@Composable
private fun StepProgress(step: OnboardingStep) {
    val activeIndex = when (step) {
        OnboardingStep.DISCLOSURE -> 0
        OnboardingStep.DEFAULT_SMS_ROLE -> 1
        OnboardingStep.SMS_PERMISSIONS -> 2
        OnboardingStep.OPTIONAL_PERMISSIONS, OnboardingStep.COMPLETE -> 3
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(4) { index ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(if (index == activeIndex) 5.dp else 3.dp)
                    .background(
                        color = if (index <= activeIndex) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(99.dp),
                    )
            )
        }
    }
}

@Composable
private fun PermissionAction(
    label: String,
    permanentlyDenied: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = if (permanentlyDenied) onOpenSettings else onRequest, modifier = Modifier.fillMaxWidth()) {
            Text(if (permanentlyDenied) "$label — open settings" else label)
        }
    }
}

private fun titleFor(step: OnboardingStep) = when (step) {
    OnboardingStep.DISCLOSURE -> "Your messages stay under your control"
    OnboardingStep.DEFAULT_SMS_ROLE -> "Make Messages your SMS app"
    OnboardingStep.SMS_PERMISSIONS -> "Allow core SMS access"
    OnboardingStep.OPTIONAL_PERMISSIONS -> "Finish setup"
    OnboardingStep.COMPLETE -> "Ready"
}

private fun bodyFor(step: OnboardingStep) = when (step) {
    OnboardingStep.DISCLOSURE -> "Review how sensitive data is used before Android asks for any permission."
    OnboardingStep.DEFAULT_SMS_ROLE -> "Android must register this app as the default SMS handler before restricted SMS permissions are requested."
    OnboardingStep.SMS_PERMISSIONS -> "These permissions are required to display conversations and send or receive messages."
    OnboardingStep.OPTIONAL_PERMISSIONS -> "Contacts improve sender names and notifications alert you about new messages. You can skip both."
    OnboardingStep.COMPLETE -> "Setup is complete."
}
