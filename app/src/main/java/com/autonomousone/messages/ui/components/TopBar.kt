package com.autonomousone.messages.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.res.stringResource
import com.autonomousone.messages.R
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autonomousone.messages.repository.ConversationParticipantActions
import com.autonomousone.messages.repository.ConversationParticipantState
import com.autonomousone.messages.repository.ParticipantContactAction

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTopBar(
    title: String = "Messages",
    onProfileClick: () -> Unit = {},
    onSearchClick: (() -> Unit)? = null,
    onMarkAllReadClick: () -> Unit = {},
    onGatewayClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    titleBadge: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

    TopAppBar(
        modifier = modifier,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Avatar(
                    name = "User",
                    size = AvatarSize.Small,
                    onClick = onProfileClick
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                if (titleBadge != null) {
                    Spacer(modifier = Modifier.width(10.dp))
                    titleBadge()
                }
            }
        },
        actions = {
            if (onSearchClick != null) {
                IconButton(onClick = onSearchClick) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search"
                    )
                }
            }

            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Menu"
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("SMS Gateway") },
                        onClick = {
                            showMenu = false
                            onGatewayClick()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Mark all as read") },
                        onClick = {
                            showMenu = false
                            onMarkAllReadClick()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Settings") },
                        onClick = {
                            showMenu = false
                            onSettingsClick()
                        }
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationTopBar(
    title: String,
    phone: String,
    participant: ConversationParticipantState,
    onBackClick: () -> Unit,
    onCallClick: () -> Unit = {},
    onParticipantClick: () -> Unit = {},
    onCopyNumber: () -> Unit = {},
    onAddToContacts: () -> Unit = {},
    onViewContact: () -> Unit = {},
    /** "Go to first message" — jumps the window to the true start of the
     *  thread via a direct keyset query (v2.6.7, never a full scan). */
    onGoToFirstMessage: () -> Unit = {},
    /** v3.4.0 FEATURE 1 — enter in-conversation search. */
    onSearchClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

    TopAppBar(
        modifier = modifier,
        navigationIcon = {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back"
                )
            }
        },
        title = {
            Row(
                modifier = Modifier.clickable(onClick = onParticipantClick),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Avatar(
                    name = title,
                    size = AvatarSize.Small,
                    isOnline = true,
                    onClick = onParticipantClick
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (phone.isNotBlank() && phone != title) {
                        Text(
                            text = phone,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        },
        actions = {
            if (participant.hasDialableNumber) {
                IconButton(onClick = onCallClick) {
                    Icon(
                        imageVector = Icons.Default.Call,
                        contentDescription = stringResource(R.string.conv_call),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More options"
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    // Search first: it is the most-used item in this menu.
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.conv_search_in_chat)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null
                            )
                        },
                        onClick = {
                            showMenu = false
                            onSearchClick()
                        }
                    )
                    when (ConversationParticipantActions.primaryContactAction(participant)) {
                        ParticipantContactAction.ADD_TO_CONTACTS -> DropdownMenuItem(
                            text = { Text(stringResource(R.string.conv_add_to_contacts)) },
                            onClick = {
                                showMenu = false
                                onAddToContacts()
                            }
                        )
                        ParticipantContactAction.VIEW_CONTACT -> DropdownMenuItem(
                            text = { Text(stringResource(R.string.conv_view_contact)) },
                            onClick = {
                                showMenu = false
                                onViewContact()
                            }
                        )
                        ParticipantContactAction.NONE -> Unit
                    }
                    if (participant.hasDialableNumber) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.conv_copy_number)) },
                            onClick = {
                                showMenu = false
                                onCopyNumber()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.conv_call)) },
                            onClick = {
                                showMenu = false
                                onCallClick()
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.conv_go_to_first_message)) },
                        onClick = {
                            showMenu = false
                            onGoToFirstMessage()
                        }
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground
        )
    )
}
