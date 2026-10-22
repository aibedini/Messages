package com.autonomousone.messages.evechat

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.autonomousone.messages.evechat.ui.EveChatInboxScreen

@Composable
fun EveChatScreen(navController: NavController) {
    val viewModel: EveChatViewModel = viewModel()
    EveChatInboxScreen(navController = navController, viewModel = viewModel)
}
