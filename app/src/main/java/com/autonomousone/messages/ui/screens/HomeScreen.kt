package com.autonomousone.messages.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.autonomousone.messages.navigation.Screen
import com.autonomousone.messages.ui.components.SmsItem
import com.autonomousone.messages.viewmodel.HomeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    hasPermission: Boolean,
    navController: NavController
) {

    val viewModel: HomeViewModel = viewModel()

    var search by remember {
        mutableStateOf("")
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            viewModel.loadSms()
        }
    }

    val smsList = viewModel.conversations

    val filteredList = remember(search, smsList) {
        smsList.filter {
            it.sender.contains(search, true) ||
                    it.message.contains(search, true)
        }
    }

    Scaffold(

        topBar = {

            CenterAlignedTopAppBar(

                title = {
                    Text("Messages")
                },

                actions = {

                    IconButton(onClick = {}) {

                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = null
                        )

                    }

                }

            )

        },

        floatingActionButton = {

            FloatingActionButton(
                onClick = {
                    // New Conversation
                }
            ) {

                Icon(
                    Icons.Default.Add,
                    contentDescription = null
                )

            }

        }

    ) { padding ->

        if (!hasPermission) {

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {

                Text("Please allow SMS permission.")

            }

            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {

            OutlinedTextField(

                value = search,

                onValueChange = {
                    search = it
                },

                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),

                placeholder = {

                    Text("Search messages")

                },

                leadingIcon = {

                    Icon(
                        Icons.Default.Search,
                        contentDescription = null
                    )

                },

                singleLine = true

            )

            if (filteredList.isEmpty()) {

                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {

                    Text("No conversations found")

                }

            } else {

                LazyColumn(

                    modifier = Modifier.fillMaxSize(),

                    contentPadding = PaddingValues(
                        bottom = 90.dp
                    )

                ) {

                    items(filteredList) { sms ->

                        SmsItem(

                            sms = sms,

                            onClick = {

                                navController.navigate(
                                    Screen.Conversation.createRoute(
                                        sms.sender
                                    )
                                )

                            }

                        )

                    }

                }

            }

        }

    }

}