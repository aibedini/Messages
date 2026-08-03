package com.autonomousone.messages

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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

        // Enable edge-to-edge system bars
        enableEdgeToEdge()

        // Create notification channel on launch
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
                        val readSmsGranted = ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.READ_SMS
                        ) == PackageManager.PERMISSION_GRANTED
                        val receiveSmsGranted = ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.RECEIVE_SMS
                        ) == PackageManager.PERMISSION_GRANTED
                        val sendSmsGranted = ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.SEND_SMS
                        ) == PackageManager.PERMISSION_GRANTED
                        val readContactsGranted = ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.READ_CONTACTS
                        ) == PackageManager.PERMISSION_GRANTED

                        return readSmsGranted && receiveSmsGranted && sendSmsGranted && readContactsGranted
                    }

                    var hasPermission by remember { mutableStateOf(checkPermissions()) }

                    val permissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                    ) { _ ->
                        hasPermission = checkPermissions()
                    }

                    LaunchedEffect(Unit) {
                        if (!hasPermission) {
                            permissionLauncher.launch(permissionsToRequest)
                        }
                    }

                    AppNavigation(
                        hasPermission = hasPermission
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