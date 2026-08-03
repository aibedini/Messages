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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        NotificationHelper.createNotificationChannel(this)

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

                    fun checkPermissions(): Boolean {
                        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED &&
                                ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED &&
                                ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED &&
                                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
                    }

                    fun isDefaultSmsApp(): Boolean {
                        return try {
                            Telephony.Sms.getDefaultSmsPackage(this) == packageName
                        } catch (e: Exception) {
                            false
                        }
                    }

                    var hasPermission by remember { mutableStateOf(checkPermissions()) }
                    var isDefaultApp by remember { mutableStateOf(isDefaultSmsApp()) }

                    // Launcher to request default SMS app role (Android Q+)
                    val defaultAppLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.StartActivityForResult()
                    ) {
                        isDefaultApp = isDefaultSmsApp()
                    }

                    val permissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                    ) {
                        hasPermission = checkPermissions()
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
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                val roleManager = getSystemService(RoleManager::class.java)
                                if (roleManager.isRoleAvailable(RoleManager.ROLE_SMS) &&
                                    !roleManager.isRoleHeld(RoleManager.ROLE_SMS)) {
                                    val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
                                    defaultAppLauncher.launch(intent)
                                }
                            } else {
                                val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
                                    putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
                                }
                                defaultAppLauncher.launch(intent)
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        SmsEventBus.isAppInForeground = true
    }

    override fun onPause() {
        super.onPause()
        SmsEventBus.isAppInForeground = false
    }
}