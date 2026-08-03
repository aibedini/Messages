package com.autonomousone.messages.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.SentimentSatisfiedAlt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.ui.components.ChatBubble
import com.autonomousone.messages.ui.components.ConversationTopBar
import com.autonomousone.messages.ui.components.EmptyView
import com.autonomousone.messages.utils.formatDateHeader
import com.autonomousone.messages.viewmodel.ConversationViewModel
import kotlinx.coroutines.launch

sealed class ChatListItem {
    data class DateSeparator(val dateText: String) : ChatListItem()
    data class MessageItem(val sms: Sms) : ChatListItem()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ConversationScreen(
    threadId: Long,
    phone: String,
    name: String,
    navController: NavController
) {
    val viewModel: ConversationViewModel = viewModel()
    var message by remember { mutableStateOf("") }
    var showAttachmentSheet by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState()

    LaunchedEffect(threadId, phone) {
        if (threadId != 0L) {
            viewModel.loadConversation(threadId, phone)
        } else {
            viewModel.setPhone(phone)
        }
    }

    val messages = viewModel.messages

    // Group messages with date section headers using derivedStateOf for instant updates
    val chatItems by remember {
        derivedStateOf {
            val items = mutableListOf<ChatListItem>()
            var lastDateHeader = ""
            messages.forEach { sms ->
                val header = formatDateHeader(sms.date)
                if (header != lastDateHeader && header.isNotBlank()) {
                    items.add(ChatListItem.DateSeparator(header))
                    lastDateHeader = header
                }
                items.add(ChatListItem.MessageItem(sms))
            }
            items
        }
    }

    LaunchedEffect(chatItems.size) {
        if (chatItems.isNotEmpty()) {
            listState.animateScrollToItem(chatItems.lastIndex)
        }
    }

    val title = when {
        name.isNotBlank() -> name
        messages.isNotEmpty() -> messages.first().sender
        phone.isNotBlank() -> phone
        else -> "Conversation"
    }

    val recipientPhone = when {
        phone.isNotBlank() -> phone
        messages.isNotEmpty() -> messages.first().sender
        else -> ""
    }

    Scaffold(
        topBar = {
            ConversationTopBar(
                title = title,
                phone = recipientPhone,
                onBackClick = { navController.popBackStack() },
                onCallClick = {},
                onVideoClick = {}
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            if (chatItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    EmptyView(
                        title = "Start Conversation",
                        subtitle = "Send a message to $title to begin chatting.",
                        buttonText = null,
                        onButtonClick = null
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(
                        items = chatItems,
                        key = { item ->
                            when (item) {
                                is ChatListItem.DateSeparator -> "date_${item.dateText}"
                                is ChatListItem.MessageItem -> "sms_${item.sms.id}"
                            }
                        }
                    ) { item ->
                        when (item) {
                            is ChatListItem.DateSeparator -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                                        tonalElevation = 1.dp
                                    ) {
                                        Text(
                                            text = item.dateText,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                            is ChatListItem.MessageItem -> {
                                ChatBubble(sms = item.sms)
                            }
                        }
                    }
                }
            }

            // Flagship Compose Field Container
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 3.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { showAttachmentSheet = true }
                    ) {
                        Icon(
                            imageVector = Icons.Default.AddCircle,
                            contentDescription = "Add attachment",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp, vertical = 10.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (message.isEmpty()) {
                            Text(
                                text = "SMS message...",
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                fontSize = 16.sp
                            )
                        }

                        BasicTextField(
                            value = message,
                            onValueChange = { message = it },
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 16.sp
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    val canSend = message.isNotBlank()
                    val sendScale by animateFloatAsState(
                        targetValue = if (canSend) 1f else 0.85f,
                        animationSpec = spring(stiffness = 400f),
                        label = "sendScale"
                    )

                    IconButton(
                        onClick = {
                            if (message.isBlank()) return@IconButton

                            val destination = if (recipientPhone.isNotBlank()) {
                                recipientPhone
                            } else if (messages.isNotEmpty()) {
                                messages.first().sender
                            } else {
                                phone
                            }

                            val msgToSend = message
                            message = ""

                            viewModel.sendMessage(
                                threadId = threadId,
                                phone = destination,
                                message = msgToSend
                            )
                        },
                        enabled = canSend,
                        modifier = Modifier.scale(sendScale)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(
                                    if (canSend) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send SMS",
                                tint = if (canSend) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .size(20.dp)
                                    .padding(start = 2.dp)
                            )
                        }
                    }
                }
            }
        }

        // Bottom Attachment Sheet
        if (showAttachmentSheet) {
            ModalBottomSheet(
                onDismissRequest = { showAttachmentSheet = false },
                sheetState = sheetState,
                containerColor = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                        .navigationBarsPadding()
                ) {
                    Text(
                        text = "Share Content",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        AttachmentOptionItem(
                            icon = Icons.Default.Image,
                            label = "Gallery",
                            color = Color(0xFF3B82F6),
                            onClick = { showAttachmentSheet = false }
                        )
                        AttachmentOptionItem(
                            icon = Icons.Default.CameraAlt,
                            label = "Camera",
                            color = Color(0xFFEC4899),
                            onClick = { showAttachmentSheet = false }
                        )
                        AttachmentOptionItem(
                            icon = Icons.Default.AudioFile,
                            label = "Audio",
                            color = Color(0xFF8B5CF6),
                            onClick = { showAttachmentSheet = false }
                        )
                        AttachmentOptionItem(
                            icon = Icons.Default.LocationOn,
                            label = "Location",
                            color = Color(0xFF10B981),
                            onClick = { showAttachmentSheet = false }
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))
                }
            }
        }
    }
}

@Composable
fun AttachmentOptionItem(
    icon: ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = color,
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}