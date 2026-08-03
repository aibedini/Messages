package com.autonomousone.messages.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.autonomousone.messages.navigation.Screen
import com.autonomousone.messages.ui.components.ContactItem
import com.autonomousone.messages.viewmodel.NewConversationViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewConversationScreen(
    navController: NavController
) {

    val viewModel: NewConversationViewModel = viewModel()

    var search by remember {
        mutableStateOf("")
    }

    LaunchedEffect(Unit) {
        viewModel.loadContacts()
    }

    val contacts = viewModel.contacts

    val filtered = contacts.filter {

        it.name.contains(search, ignoreCase = true) ||
                it.phone.contains(search)

    }

    val canStartNewConversation =
        search.isNotBlank() &&
                search.any { it.isDigit() }

    Scaffold(

        topBar = {

            TopAppBar(

                title = {
                    Text("New Conversation")
                },

                navigationIcon = {

                    IconButton(
                        onClick = {
                            navController.popBackStack()
                        }
                    ) {

                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = null
                        )

                    }

                }

            )

        }

    ) { padding ->

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

                leadingIcon = {

                    Icon(
                        Icons.Default.Search,
                        contentDescription = null
                    )

                },

                placeholder = {

                    Text("Search contact or phone")

                },

                singleLine = true

            )

            if (canStartNewConversation) {

                Card(

                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clickable {

                            navController.navigate(

                                Screen.Conversation.createNewRoute(
                                    phone = search,
                                    name = search
                                )

                            )

                        },

                    elevation = CardDefaults.cardElevation(
                        defaultElevation = 2.dp
                    )

                ) {

                    Row(

                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)

                    ) {

                        Icon(
                            Icons.Default.PersonAdd,
                            contentDescription = null
                        )

                        Spacer(
                            modifier = Modifier.width(16.dp)
                        )

                        Column {

                            Text(
                                text = "Send to",
                                style = MaterialTheme.typography.bodyMedium
                            )

                            Spacer(
                                modifier = Modifier.height(4.dp)
                            )

                            Text(
                                text = search,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )

                        }

                    }

                }

                Spacer(
                    modifier = Modifier.height(12.dp)
                )

                Divider()

            }

            LazyColumn(
                modifier = Modifier.fillMaxSize()
            ) {

                items(

                    items = filtered,

                    key = {
                        "${it.id}_${it.phone}"
                    }

                ) { contact ->

                    ContactItem(

                        contact = contact,

                        onClick = {

                            navController.navigate(

                                Screen.Conversation.createNewRoute(
                                    phone = contact.phone,
                                    name = contact.name
                                )

                            )

                        }

                    )

                }

            }

        }

    }

}