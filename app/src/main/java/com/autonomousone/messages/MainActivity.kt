package com.autonomousone.messages

import android.Manifest
import android.content.pm.PackageManager
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
import com.autonomousone.messages.navigation.AppNavigation
import com.autonomousone.messages.ui.theme.MessagesTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge system bars
        enableEdgeToEdge()

        setContent {
            MessagesTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var hasPermission by remember {
                        mutableStateOf(
                            ContextCompat.checkSelfPermission(
                                this,
                                Manifest.permission.READ_SMS
                            ) == PackageManager.PERMISSION_GRANTED &&
                                    ContextCompat.checkSelfPermission(
                                        this,
                                        Manifest.permission.SEND_SMS
                                    ) == PackageManager.PERMISSION_GRANTED &&
                                    ContextCompat.checkSelfPermission(
                                        this,
                                        Manifest.permission.READ_CONTACTS
                                    ) == PackageManager.PERMISSION_GRANTED
                        )
                    }

                    val permissionLauncher =
                        rememberLauncherForActivityResult(
                            ActivityResultContracts.RequestMultiplePermissions()
                        ) { permissions ->
                            hasPermission =
                                permissions[Manifest.permission.READ_SMS] == true &&
                                        permissions[Manifest.permission.SEND_SMS] == true &&
                                        permissions[Manifest.permission.READ_CONTACTS] == true
                        }

                    LaunchedEffect(Unit) {
                        if (!hasPermission) {
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.READ_SMS,
                                    Manifest.permission.SEND_SMS,
                                    Manifest.permission.READ_CONTACTS
                                )
                            )
                        }
                    }

                    AppNavigation(
                        hasPermission = hasPermission
                    )
                }
            }
        }
    }
}