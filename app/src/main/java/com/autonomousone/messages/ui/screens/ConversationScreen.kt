package com.autonomousone.messages.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.autonomousone.messages.ui.components.ChatBubble
import com.autonomousone.messages.viewmodel.ConversationViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    threadId: Long,
    phone: String,
    name: String,
    navController: NavController
) {

    val viewModel: ConversationViewModel = viewModel()

    var message by remember {
        mutableStateOf("")
    }

    val listState = rememberLazyListState()

    LaunchedEffect(threadId, phone) {

        if (threadId != 0L) {

            viewModel.loadConversation(threadId)

        } else {

            viewModel.setPhone(phone)

        }

    }

    val messages = viewModel.messages

    LaunchedEffect(messages.size) {

        if (messages.isNotEmpty()) {

            listState.animateScrollToItem(
                messages.lastIndex
            )

        }

    }

    val title =
        when {

            messages.isNotEmpty() ->
                messages.first().sender

            name.isNotBlank() ->
                name

            phone.isNotBlank() ->
                phone

            else ->
                "Conversation"

        }

    Scaffold(

        topBar = {

            TopAppBar(

                title = {
                    Text(title)
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

            if (messages.isEmpty()) {

                Box(

                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),

                    contentAlignment = Alignment.Center

                ) {

                    Text(
                        "Start a conversation"
                    )

                }

            } else {

                LazyColumn(

                    modifier = Modifier.weight(1f),

                    state = listState,

                    contentPadding = PaddingValues(12.dp),

                    verticalArrangement = Arrangement.spacedBy(8.dp)

                ) {

                    items(
                        items = messages,
                        key = { it.id }
                    ) { sms ->

                        ChatBubble(sms)

                    }

                }

            }

            Row(

                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),

                verticalAlignment = Alignment.CenterVertically

            ) {

                OutlinedTextField(

                    value = message,

                    onValueChange = {
                        message = it
                    },

                    modifier = Modifier.weight(1f),

                    placeholder = {
                        Text("Type message")
                    },

                    singleLine = true

                )

                Spacer(
                    modifier = Modifier.width(8.dp)
                )

                FloatingActionButton(

                    onClick = {

                        if (message.isBlank()) return@FloatingActionButton

                        val destination =

                            if (messages.isNotEmpty()) {
                                messages.first().sender
                            } else {
                                phone
                            }

                        viewModel.sendMessage(
                            threadId = threadId,
                            phone = destination,
                            message = message
                        )

                        message = ""

                    }

                ) {

                    Icon(
                        Icons.Default.Send,
                        contentDescription = null
                    )

                }

            }

        }

    }

}