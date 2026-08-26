package com.autonomousone.messages.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.autonomousone.messages.navigation.Screen
import com.autonomousone.messages.ui.components.AppSearchBar
import com.autonomousone.messages.ui.components.Avatar
import com.autonomousone.messages.ui.components.AvatarSize
import com.autonomousone.messages.ui.components.ContactItem
import com.autonomousone.messages.ui.components.EmptyView
import com.autonomousone.messages.model.Contact
import com.autonomousone.messages.viewmodel.NewConversationViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewConversationScreen(
    navController: NavController,
    forwardText: String = "",
    /** Text received from an external share — DRAFT only, never auto-sent. */
    draftText: String = "",
    /** Recipient phone from an sms: link — pre-fills the search field. */
    sharedPhone: String = ""
) {
    val viewModel: NewConversationViewModel = viewModel()
    var search by remember { mutableStateOf(sharedPhone) }
    var groupMode by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<Contact>() }

    LaunchedEffect(Unit) {
        viewModel.loadContacts()
    }

    val contacts = viewModel.contacts

    val filteredContacts by remember(search) {
        derivedStateOf {
            contacts.filter {
                it.name.contains(search, ignoreCase = true) ||
                        it.phone.contains(search)
            }
        }
    }

    val canStartNewConversation by remember(search) {
        derivedStateOf {
            search.isNotBlank() && search.any { it.isDigit() }
        }
    }

    // Frequent contacts top row (first 6 contacts)
    val frequentContacts by remember {
        derivedStateOf {
            contacts.take(6)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "New Conversation",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    // Group mode: pick several contacts, send as one conversation.
                    IconButton(onClick = {
                        groupMode = !groupMode
                        if (!groupMode) selected.clear()
                    }) {
                        Icon(
                            imageVector = Icons.Default.Group,
                            contentDescription = if (groupMode) "Exit group mode" else "Group mode",
                            tint = if (groupMode) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Flagship Pill SearchBar
            AppSearchBar(
                query = search,
                onQueryChange = { search = it },
                placeholderText = if (groupMode) "Add people to group..."
                else "Type a name or phone number..."
            )

            // Forwarding banner
            if (forwardText.isNotBlank()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Forward message to:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = forwardText.take(120),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Selected-people chips (group mode only)
            if (groupMode && selected.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    selected.forEach { person ->
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            onClick = { selected.removeAll { it.id == person.id && it.phone == person.phone } }
                        ) {
                            Text(
                                text = "${person.name} ✕",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }

            // Hero Card for Direct Phone Number Messaging
            AnimatedVisibility(
                visible = canStartNewConversation,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .clickable {
                            navController.navigate(
                                Screen.Conversation.createNewRoute(
                                    phone = search,
                                    name = search,
                                    forward = forwardText,
                                    draft = draftText
                                )
                            )
                        },
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "Send SMS to",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = search,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }

                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            // Favorites / Frequent Contacts Carousel (shown when search query is empty)
            if (search.isEmpty() && frequentContacts.isNotEmpty()) {
                Column(
                    modifier = Modifier.padding(vertical = 8.dp)
                ) {
                    Text(
                        text = "Frequent Contacts",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        frequentContacts.forEach { contact ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .width(64.dp)
                                     .clickable {
                                         navController.navigate(
                                             Screen.Conversation.createNewRoute(
                                                 phone = contact.phone,
                                                 name = contact.name,
                                                 forward = forwardText,
                                                 draft = draftText
                                             )
                                         )
                                     }
                            ) {
                                Avatar(
                                    name = contact.name,
                                    size = AvatarSize.Large
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = contact.name.split(" ").firstOrNull() ?: contact.name,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }

            // Contact List Header
            Text(
                text = "All Contacts (${filteredContacts.size})",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )

            if (viewModel.isLoading && filteredContacts.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator()
                        viewModel.loadStatus?.let { status ->
                            Text(
                                text = status,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else if (filteredContacts.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    EmptyView(
                        title = if (search.isNotBlank()) "No Contacts Found" else "No Contacts",
                        subtitle = if (search.isNotBlank()) {
                            "No contacts matching \"$search\"."
                        } else {
                            "No contacts were found on your device."
                        },
                        icon = Icons.Default.Contacts,
                        buttonText = null,
                        onButtonClick = null
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(
                        items = filteredContacts,
                        key = { "${it.id}_${it.phone}" }
                    ) { contact ->
                        ContactItem(
                            contact = contact,
                            onClick = {
                                if (groupMode) {
                                    val key = "${contact.id}_${contact.phone}"
                                    if (selected.any { "${it.id}_${it.phone}" == key }) {
                                        selected.removeAll { "${it.id}_${it.phone}" == key }
                                    } else {
                                        selected.add(contact)
                                    }
                                } else {
                                    navController.navigate(
                                        Screen.Conversation.createNewRoute(
                                            phone = contact.phone,
                                            name = contact.name,
                                            forward = forwardText,
                                            draft = draftText
                                        )
                                    )
                                }
                            }
                        )
                    }
                }
            }

            // Start group chat bar (group mode only)
            AnimatedVisibility(visible = groupMode && selected.size >= 2) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.primary,
                    onClick = {
                        val phones = selected.joinToString(",") { it.phone }
                        val names = selected.joinToString(", ") { it.name.split(" ").first() }
                        navController.navigate(
                            Screen.Conversation.createNewRoute(
                                phone = phones, name = names, forward = forwardText, draft = draftText
                            )
                        )
                    }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Start Group Chat (${selected.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }
    }
}