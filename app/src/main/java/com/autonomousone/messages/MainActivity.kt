package com.autonomousone.messages

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.provider.Telephony
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.autonomousone.messages.event.SmsEventBus
import com.autonomousone.messages.navigation.AppNavigation
import com.autonomousone.messages.onboarding.OnboardingPolicy
import com.autonomousone.messages.onboarding.OnboardingPreferences
import com.autonomousone.messages.onboarding.OnboardingState
import com.autonomousone.messages.onboarding.OnboardingStep
import com.autonomousone.messages.repository.ContactRepository
import com.autonomousone.messages.ui.screens.OnboardingScreen
import com.autonomousone.messages.ui.theme.MessagesTheme
import com.autonomousone.messages.utils.NotificationHelper

class MainActivity : ComponentActivity() {
    private val isDefaultAppState = mutableStateOf(false)
    private val refreshState = mutableIntStateOf(0)
    private lateinit var onboardingPreferences: OnboardingPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        NotificationHelper.createNotificationChannel(this)
        onboardingPreferences = OnboardingPreferences(this)
        refreshSystemState()

        setContent {
            MessagesTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    @Suppress("UNUSED_VARIABLE") val refresh by refreshState
                    val isDefaultApp by isDefaultAppState
                    val hasSmsPermissions = hasSmsPermissions()
                    val hasContactsPermission = hasPermission(Manifest.permission.READ_CONTACTS)
                    val hasNotificationsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                        hasPermission(Manifest.permission.POST_NOTIFICATIONS)

                    val defaultAppLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.StartActivityForResult()
                    ) { refreshSystemState() }
                    val smsPermissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                    ) { refreshSystemState() }
                    val contactsPermissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission()
                    ) {
                        ContactRepository.clearCache()
                        refreshSystemState()
                    }
                    val notificationsPermissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission()
                    ) { refreshSystemState() }

                    val step = OnboardingPolicy.resolveStep(
                        onboardingPreferences.disclosureAccepted,
                        isDefaultApp,
                        hasSmsPermissions,
                        onboardingPreferences.optionalStepCompleted,
                    )

                    if (step != OnboardingStep.COMPLETE) {
                        OnboardingScreen(
                            state = OnboardingState(
                                step = step,
                                smsPermissionPermanentlyDenied = onboardingPreferences.smsPermissionRequested &&
                                    SMS_PERMISSIONS.any { !hasPermission(it) && !ActivityCompat.shouldShowRequestPermissionRationale(this, it) },
                                contactsPermissionPermanentlyDenied = onboardingPreferences.contactsPermissionRequested &&
                                    !hasContactsPermission &&
                                    !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.READ_CONTACTS),
                                notificationsPermissionPermanentlyDenied = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    onboardingPreferences.notificationsPermissionRequested && !hasNotificationsPermission &&
                                    !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS),
                            ),
                            hasContactsPermission = hasContactsPermission,
                            hasNotificationsPermission = hasNotificationsPermission,
                            onAcceptDisclosure = {
                                onboardingPreferences.disclosureAccepted = true
                                refreshState.intValue++
                            },
                            onRequestDefaultRole = { requestDefaultSmsApp(defaultAppLauncher) },
                            onRequestSmsPermissions = {
                                if (isDefaultSmsApp()) {
                                    onboardingPreferences.smsPermissionRequested = true
                                    smsPermissionLauncher.launch(SMS_PERMISSIONS)
                                }
                            },
                            onRequestContactsPermission = {
                                onboardingPreferences.contactsPermissionRequested = true
                                contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                            },
                            onRequestNotificationsPermission = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    onboardingPreferences.notificationsPermissionRequested = true
                                    notificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            },
                            onCompleteOptionalStep = {
                                onboardingPreferences.optionalStepCompleted = true
                                refreshState.intValue++
                            },
                            onOpenSettings = ::openAppSettings,
                            onOpenPrivacyPolicy = ::openPrivacyPolicy,
                        )
                    } else {
                        AppNavigation(
                            hasPermission = hasSmsPermissions,
                            isDefaultSmsApp = isDefaultApp,
                            onRequestDefaultApp = { requestDefaultSmsApp(defaultAppLauncher) },
                            onRequestPermissions = {
                                when {
                                    !isDefaultApp -> requestDefaultSmsApp(defaultAppLauncher)
                                    !hasSmsPermissions -> {
                                        onboardingPreferences.smsPermissionRequested = true
                                        smsPermissionLauncher.launch(SMS_PERMISSIONS)
                                    }
                                    !hasContactsPermission -> {
                                        onboardingPreferences.contactsPermissionRequested = true
                                        contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                                    }
                                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationsPermission -> {
                                        onboardingPreferences.notificationsPermissionRequested = true
                                        notificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        SmsEventBus.isAppInForeground = true
        if (::onboardingPreferences.isInitialized) refreshSystemState()
        SmsEventBus.notifyResume()
    }

    override fun onPause() {
        super.onPause()
        SmsEventBus.isAppInForeground = false
    }

    private fun refreshSystemState() {
        isDefaultAppState.value = isDefaultSmsApp()
        refreshState.intValue++
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasSmsPermissions() = SMS_PERMISSIONS.all(::hasPermission)

    private fun isDefaultSmsApp(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_SMS)
        } else Telephony.Sms.getDefaultSmsPackage(this) == packageName
    } catch (_: Exception) { false }

    private fun requestDefaultSmsApp(launcher: androidx.activity.result.ActivityResultLauncher<Intent>) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = getSystemService(RoleManager::class.java)
                if (roleManager.isRoleAvailable(RoleManager.ROLE_SMS)) {
                    launcher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS))
                }
            } else {
                @Suppress("DEPRECATION")
                launcher.launch(Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
                    putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
                })
            }
        } catch (_: Exception) { openAppSettings() }
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun openPrivacyPolicy() {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL)))
    }

    companion object {
        private val SMS_PERMISSIONS = arrayOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.SEND_SMS,
        )
        private const val PRIVACY_POLICY_URL = "https://github.com/aibedini/Messages/blob/main/PRIVACY.md"
    }
}
