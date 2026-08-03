package com.autonomousone.messages

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import com.autonomousone.messages.navigation.AppNavigation

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {

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