package com.autonomousone.messages

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.autonomousone.messages.event.SmsEventBus
import com.autonomousone.messages.navigation.AppNavigation
import com.autonomousone.messages.ui.theme.MessagesTheme
import com.autonomousone.messages.utils.NotificationHelper

class MainActivity : ComponentActivity() {

    // Exposed as Compose-observable state so setters in onResume recompose the UI
    private var _hasPermission = mutableStateOf(false)
    private var _isDefaultApp = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        NotificationHelper.createNotificationChannel(this)

        // Initial check before Compose is set up
        _hasPermission.value = checkPermissions()
        _isDefaultApp.value = isDefaultSmsApp()

        setContent {
            MessagesTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val permissionsToRequest = remember {
                        mutableListOf(
                            Manifest.permission.READ_SMS,
                            Manifest.permission.RECEIVE_SMS,
                            Manifest.permission.SEND_SMS,
                            Manifest.permission.READ_CONTACTS
                        ).apply {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }.toTypedArray()
                    }

                    // Read from the class-level mutableStateOf so onResume updates recompose
                    val hasPermission by _hasPermission
                    val isDefaultApp by _isDefaultApp

                    val defaultAppLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.StartActivityForResult()
                    ) {
                        // Recheck immediately when user returns from system picker
                        _isDefaultApp.value = isDefaultSmsApp()
                        _hasPermission.value = checkPermissions()
                    }

                    val permissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                    ) {
                        _hasPermission.value = checkPermissions()
                    }

                    LaunchedEffect(Unit) {
                        if (!hasPermission) {
                            permissionLauncher.launch(permissionsToRequest)
                        }
                    }

                    AppNavigation(
                        hasPermission = hasPermission,
                        isDefaultSmsApp = isDefaultApp,
                        onRequestDefaultApp = {
                            requestDefaultSmsApp(defaultAppLauncher)
                        },
                        onRequestPermissions = {
                            permissionLauncher.launch(permissionsToRequest)
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        SmsEventBus.isAppInForeground = true
        // Update observable states — Compose will recompose automatically
        _hasPermission.value = checkPermissions()
        _isDefaultApp.value = isDefaultSmsApp()
        // Signal all ViewModels to reload fresh data
        SmsEventBus.notifyResume()
    }

    override fun onPause() {
        super.onPause()
        SmsEventBus.isAppInForeground = false
    }

    private fun checkPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    }

    private fun isDefaultSmsApp(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // RoleManager.isRoleHeld() is the authoritative check on Android 10+
                // Telephony.Sms.getDefaultSmsPackage() can lag after role is granted
                val roleManager = getSystemService(RoleManager::class.java)
                roleManager.isRoleHeld(RoleManager.ROLE_SMS)
            } else {
                // Pre-Q: use the traditional default SMS package check
                Telephony.Sms.getDefaultSmsPackage(this) == packageName
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun requestDefaultSmsApp(
        launcher: androidx.activity.result.ActivityResultLauncher<Intent>
    ) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = getSystemService(RoleManager::class.java)
                if (roleManager.isRoleAvailable(RoleManager.ROLE_SMS)) {
                    val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
                    launcher.launch(intent)
                }
            } else {
                @Suppress("DEPRECATION")
                val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
                    putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
                }
                launcher.launch(intent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}