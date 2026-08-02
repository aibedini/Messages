package com.autonomousone.messages

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.autonomousone.messages.navigation.AppNavigation
import com.autonomousone.messages.ui.screens.HomeScreen
import com.autonomousone.messages.ui.screens.SplashScreen
import com.autonomousone.messages.ui.theme.MessagesTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var showSplash by remember {
                mutableStateOf(true)
            }

            if (showSplash) {

                SplashScreen {
                    showSplash = false
                }

            } else {
                AppNavigation()
            }
        }
    }
}

