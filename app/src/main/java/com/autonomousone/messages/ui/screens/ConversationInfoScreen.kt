package com.autonomousone.messages.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.autonomousone.messages.R
import com.autonomousone.messages.data.MessageCategory
import com.autonomousone.messages.navigation.Screen
import com.autonomousone.messages.repository.ConversationInfoController
import com.autonomousone.messages.repository.ConversationInfoLogic
import com.autonomousone.messages.repository.ConversationMute
import com.autonomousone.messages.repository.MuteStatus
import com.autonomousone.messages.repository.ParticipantContactAction
import com.autonomousone.messages.ui.components.Avatar
import com.autonomousone.messages.ui.components.AvatarSize
import com.autonomousone.messages.utils.DiagnosticLog
import com.autonomousone.messages.utils.formatFullTimestamp
import com.autonomousone.messages.viewmodel.ConversationInfoViewModel

/**
 * Conversation Info (v3.4.0 FEATURE 5).
 *
 * Everything shown here is REAL durable state, and everything tappable performs a
 * real action:
 *
 *  - Mute writes `conversation_preferences.mutedUntil` (expiry by comparison);
 *  - Custom notifications creates a real Android per-conversation channel and
 *    then opens the SYSTEM sheet, because the channel — not this app — is the
 *    authority for sound/vibration;
 *  - Category writes `categoryOverride` (`null` = Automatic) and NEVER sets spam;
 *  - Spam & blocking delegates to `SpamRepository`, which keeps the
 *    manual-block provenance rule intact;
 *  - Move to trash creates the durable, reversible `TrashedThreadRepository`
 *    tombstone behind an explicit confirmation.
 *
 * Route arguments are read with [Screen.cleanArg] (a leaked `{phone}` pattern is
 * never user data) and the layout is RTL-safe because every row uses
 * start/end-relative padding plus `Icons.AutoMirrored` chevrons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationInfoScreen(
    threadId: Long,
    phone: String,
    name: String,
    navController: NavController
) {
    val context = LocalContext.current
    val viewModel: ConversationInfoViewModel = viewModel(
        key = "conversation_info_$threadId",
        factory = ConversationInfoViewModel.factory(threadId)
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val event by viewModel.events.collectAsStateWithLifecycle()

    val safePhone = Screen.cleanArg(phone)
    val safeName = Screen.cleanArg(name)

    val snackbarHostState = remember { SnackbarHostState() }
    val copiedToast = stringResource(R.string.conv_number_copied)
    val numberCopiedLabel = stringResource(R.string.conv_copy_number)
    val contactActionFailed = stringResource(R.string.conv_info_contact_failed)

    var showMuteDialog by remember { mutableStateOf(false) }
    var showCategoryDialog by remember { mutableStateOf(false) }
    var showTrashDialog by remember { mutableStateOf(false) }
    var showPhoneActions by remember { mutableStateOf(false) }

    // Contact writes go through the platform activities; a device with no
    // contacts app must not crash the screen.
    val contactLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    LaunchedEffect(threadId, safePhone) { viewModel.load(safePhone) }

    LaunchedEffect(state.error) {
        val message = state.error
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.clearError()
        }
    }

    // One-shot navigation. Each event is consumed so a recomposition cannot
    // replay it (a replayed "trashed" would pop the screen twice).
    LaunchedEffect(event) {
        when (val pending = event) {
            null -> Unit
            ConversationInfoController.InfoEvent.OpenMedia -> {
                viewModel.consumeEvent(pending)
                navController.navigate(Screen.ConversationMedia.createRoute(threadId, safeName))
            }
            ConversationInfoController.InfoEvent.OpenStarred -> {
                viewModel.consumeEvent(pending)
                navController.navigate(Screen.ConversationStarred.createRoute(threadId))
            }
            ConversationInfoController.InfoEvent.OpenNotificationSettings -> {
                viewModel.consumeEvent(pending)
            }
            ConversationInfoController.InfoEvent.ConversationTrashed -> {
                viewModel.consumeEvent(pending)
                navController.popBackStack()
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.conv_info_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_close)
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
        when {
            state.loading && state.participant.phone.isBlank() && safeName.isBlank() ->
                LoadingState(Modifier.padding(padding))

            state.error != null && state.participant.phone.isBlank() && safeName.isBlank() ->
                // A genuinely empty screen (the load failed before anything was
                // known) offers a retry instead of a blank page.
                ErrorState(
                    message = state.error.orEmpty(),
                    onRetry = { viewModel.load(safePhone) },
                    modifier = Modifier.padding(padding)
                )

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HeaderCard(
                    displayName = state.participant.displayName.ifBlank { safeName.ifBlank { safePhone } },
                    phone = state.participant.phone.ifBlank { safePhone },
                    hasDialableNumber = state.participant.hasDialableNumber,
                    onCall = { dial(context, state.participant) },
                    onMessage = { showPhoneActions = true },
                    contactAction = state.contactAction,
                    onAddToContacts = {
                        launchContactInsert(context, contactLauncher, state.participant.phone, contactActionFailed)
                    },
                    onViewContact = {
                        launchContactView(context, contactLauncher, state.participant.contactLookupUri, contactActionFailed)
                    }
                )

                val sections = state.sections

                SectionCard(title = stringResource(R.string.conv_info_section_notifications)) {
                    InfoRow(
                        icon = Icons.Default.Notifications,
                        iconDescription = stringResource(R.string.conv_info_section_notifications),
                        title = stringResource(R.string.conv_info_mute),
                        subtitle = muteSubtitle(sections.notifications.status),
                        trailingText = stringResource(
                            when (sections.notifications.actionLabel) {
                                ConversationInfoLogic.NotificationAction.MUTE -> R.string.conv_info_mute
                                ConversationInfoLogic.NotificationAction.UNMUTE -> R.string.conv_info_unmute
                            }
                        ),
                        onClick = {
                            if (sections.notifications.isMuted) viewModel.unmute() else showMuteDialog = true
                        }
                    )
                    InfoRow(
                        icon = Icons.Default.Tune,
                        iconDescription = stringResource(R.string.conv_info_custom_notifications),
                        title = stringResource(R.string.conv_info_custom_notifications),
                        subtitle = stringResource(
                            if (sections.customNotifications.isCustom) {
                                R.string.conv_info_custom_active
                            } else {
                                R.string.conv_info_custom_default
                            }
                        ),
                        trailingText = null,
                        onClick = {
                            // Already custom → simply re-open the SYSTEM sheet; not
                            // yet custom → set the durable flag AND open it, because
                            // Android owns the actual sound/vibration choice.
                            viewModel.chooseCustomNotifications(
                                state.participant.displayName.ifBlank { safeName.ifBlank { safePhone } }
                            )
                        }
                    )
                }

                SectionCard(title = stringResource(R.string.conv_info_section_content)) {
                    InfoRow(
                        icon = Icons.Default.Image,
                        iconDescription = stringResource(R.string.conv_info_media),
                        title = stringResource(R.string.conv_info_media),
                        subtitle = mediaSubtitle(state.assetCount),
                        trailingText = null,
                        onClick = { viewModel.requestOpenMedia() }
                    )
                    InfoRow(
                        icon = Icons.Default.Star,
                        iconDescription = stringResource(R.string.conv_info_starred),
                        title = stringResource(R.string.conv_info_starred),
                        subtitle = null,
                        trailingText = sections.starredCount.badge(),
                        onClick = { viewModel.requestOpenStarred() }
                    )
                }

                SectionCard(title = stringResource(R.string.conv_info_section_category)) {
                    InfoRow(
                        icon = Icons.Default.ChevronRight,
                        iconDescription = stringResource(R.string.conv_info_category),
                        title = stringResource(R.string.conv_info_category),
                        subtitle = categorySubtitle(sections.categoryIsAutomatic),
                        trailingText = null,
                        onClick = { showCategoryDialog = true }
                    )
                }

                SectionCard(title = stringResource(R.string.conv_info_section_spam)) {
                    if (sections.canBlock) {
                        InfoRow(
                            icon = Icons.Default.Block,
                            iconDescription = stringResource(R.string.conv_info_block),
                            title = stringResource(
                                when (state.spamSection.blockAction) {
                                    com.autonomousone.messages.repository.SpamSectionState.BlockAction.BLOCK ->
                                        R.string.conv_info_block
                                    com.autonomousone.messages.repository.SpamSectionState.BlockAction.UNBLOCK ->
                                        R.string.conv_info_unblock
                                }
                            ),
                            subtitle = null,
                            trailingText = null,
                            onClick = {
                                viewModel.setBlocked(state.participant.phone, !state.blocked)
                            }
                        )
                    }
                    InfoRow(
                        icon = Icons.Default.Report,
                        iconDescription = stringResource(R.string.conv_info_report_spam),
                        title = stringResource(
                            when (state.spamSection.reportAction) {
                                com.autonomousone.messages.repository.SpamSectionState.ReportAction.REPORT_SPAM ->
                                    R.string.conv_info_report_spam
                                com.autonomousone.messages.repository.SpamSectionState.ReportAction.NOT_SPAM ->
                                    R.string.conv_info_not_spam
                            }
                        ),
                        subtitle = if (state.preference.spam) {
                            stringResource(R.string.conv_info_spam_reported)
                        } else {
                            null
                        },
                        trailingText = null,
                        onClick = {
                            if (state.preference.spam) {
                                viewModel.notSpam(
                                    state.participant.phone,
                                    state.preference.spam
                                )
                            } else {
                                viewModel.reportSpam(state.participant.phone)
                            }
                        }
                    )
                }

                SectionCard(title = stringResource(R.string.conv_info_section_danger)) {
                    InfoRow(
                        icon = Icons.Default.Delete,
                        iconDescription = stringResource(R.string.conv_info_move_to_trash),
                        title = stringResource(R.string.conv_info_move_to_trash),
                        subtitle = stringResource(R.string.conv_info_move_to_trash_hint),
                        trailingText = null,
                        destructive = true,
                        onClick = { showTrashDialog = true }
                    )
                }
            }
        }
    }

    if (showMuteDialog) {
        MuteDialog(
            onDismiss = { showMuteDialog = false },
            onPick = { preset ->
                showMuteDialog = false
                viewModel.setMuted(preset)
            }
        )
    }

    if (showCategoryDialog) {
        CategoryDialog(
            selected = state.sections.category.category,
            automaticLabel = automaticCategoryLabel(state.automaticCategory),
            onDismiss = { showCategoryDialog = false },
            onPick = { category ->
                showCategoryDialog = false
                viewModel.setCategoryOverride(category)
            }
        )
    }

    if (showTrashDialog) {
        AlertDialog(
            onDismissRequest = { showTrashDialog = false },
            title = { Text(stringResource(R.string.conv_info_trash_confirm_title)) },
            text = { Text(stringResource(R.string.conv_info_trash_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showTrashDialog = false
                    viewModel.trashConversation()
                }) {
                    Text(
                        text = stringResource(R.string.conv_info_move_to_trash),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showTrashDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showPhoneActions) {
        PhoneActionDialog(
            number = state.participant.phone.ifBlank { safePhone },
            onDismiss = { showPhoneActions = false },
            onSendSms = {
                showPhoneActions = false
                navController.navigate(
                    Screen.Conversation.createRoute(
                        threadId = threadId,
                        phone = state.participant.phone.ifBlank { safePhone },
                        name = state.participant.displayName.ifBlank { safeName }
                    )
                )
            },
            onCall = {
                showPhoneActions = false
                dial(context, state.participant)
            },
            onCopy = {
                showPhoneActions = false
                copyToClipboard(
                    context = context,
                    label = numberCopiedLabel,
                    value = state.participant.phone.ifBlank { safePhone },
                    toast = copiedToast
                )
            }
        )
    }
}

// ── Sections ────────────────────────────────────────────────────────────────

@Composable
private fun HeaderCard(
    displayName: String,
    phone: String,
    hasDialableNumber: Boolean,
    onCall: () -> Unit,
    onMessage: () -> Unit,
    contactAction: ParticipantContactAction,
    onAddToContacts: () -> Unit,
    onViewContact: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Avatar(name = displayName, size = AvatarSize.XLarge)
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (phone.isNotBlank() && phone != displayName) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = phone,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (hasDialableNumber) {
                    HeaderAction(
                        icon = Icons.Default.Call,
                        label = stringResource(R.string.conv_call),
                        onClick = onCall
                    )
                }
                HeaderAction(
                    icon = Icons.Default.Phone,
                    label = stringResource(R.string.conv_info_message),
                    onClick = onMessage
                )
                when (contactAction) {
                    ParticipantContactAction.ADD_TO_CONTACTS -> HeaderAction(
                        icon = Icons.Default.Person,
                        label = stringResource(R.string.conv_add_to_contacts),
                        onClick = onAddToContacts
                    )
                    ParticipantContactAction.VIEW_CONTACT -> HeaderAction(
                        icon = Icons.Default.Person,
                        label = stringResource(R.string.conv_view_contact),
                        onClick = onViewContact
                    )
                    ParticipantContactAction.NONE -> Unit
                }
            }
        }
    }
}

@Composable
private fun HeaderAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(26.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            content()
        }
    }
}

/**
 * One real, tappable Conversation Info row.
 *
 * The whole row is the touch target (minimum 48dp tall) and the trailing text is
 * a LABEL, not a second control, so there is no nested clickable. A null
 * [onClick] is not offered here by construction: every caller passes a real
 * action, which is what keeps "no clickable no-ops" true on this screen.
 */
@Composable
private fun InfoRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconDescription: String,
    title: String,
    subtitle: String?,
    trailingText: String?,
    onClick: () -> Unit,
    destructive: Boolean = false
) {
    val contentColor = if (destructive) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = iconDescription,
            tint = contentColor,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (!trailingText.isNullOrBlank()) {
            Text(
                text = trailingText,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        if (!destructive) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ── Dialogs ─────────────────────────────────────────────────────────────────

@Composable
private fun MuteDialog(
    onDismiss: () -> Unit,
    onPick: (ConversationMute.Preset) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.conv_info_mute)) },
        text = {
            Column {
                ConversationInfoLogic.MUTE_PRESETS.forEach { preset ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(preset) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = mutePresetLabel(preset),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
private fun CategoryDialog(
    selected: MessageCategory?,
    automaticLabel: String?,
    onDismiss: () -> Unit,
    onPick: (MessageCategory?) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.conv_info_category)) },
        text = {
            Column {
                ConversationInfoLogic.CATEGORY_CHOICES.forEach { choice ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(choice.category) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // The ROW is the single click target, so the radio is not
                        // a second control competing for the same tap.
                        RadioButton(
                            selected = choice.category == selected,
                            onClick = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (choice.category == null) {
                                stringResource(R.string.conv_info_category_automatic)
                            } else {
                                categoryLabel(choice.category)
                            },
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
                if (automaticLabel != null) {
                    Text(
                        text = stringResource(R.string.conv_info_category_automatic_value, automaticLabel),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
private fun PhoneActionDialog(
    number: String,
    onDismiss: () -> Unit,
    onSendSms: () -> Unit,
    onCall: () -> Unit,
    onCopy: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(number) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onSendSms) { Text(stringResource(R.string.conv_send_sms_action)) }
                TextButton(onClick = onCall) { Text(stringResource(R.string.conv_call)) }
                TextButton(onClick = onCopy) { Text(stringResource(R.string.conv_copy_number)) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        }
    )
}

// ── Loading / Error ─────────────────────────────────────────────────────────

@Composable
private fun LoadingState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.conv_info_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.conv_info_error),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onRetry) { Text(stringResource(R.string.conv_info_retry)) }
    }
}

// ── Pure label helpers ──────────────────────────────────────────────────────

/** "Muted until <date>", "Muted", or null when not muted. */
@Composable
internal fun muteSubtitle(status: MuteStatus): String? = when (status) {
    MuteStatus.NotMuted -> null
    MuteStatus.MutedForever -> stringResource(R.string.conv_info_muted_forever)
    is MuteStatus.MutedUntil -> stringResource(
        R.string.conv_info_muted_until,
        formatFullTimestamp(status.until)
    )
}

@Composable
private fun mutePresetLabel(preset: ConversationMute.Preset): String = stringResource(
    when (preset) {
        ConversationMute.Preset.ONE_HOUR -> R.string.conv_info_mute_1h
        ConversationMute.Preset.EIGHT_HOURS -> R.string.conv_info_mute_8h
        ConversationMute.Preset.ONE_DAY -> R.string.conv_info_mute_24h
        ConversationMute.Preset.SEVEN_DAYS -> R.string.conv_info_mute_7d
        ConversationMute.Preset.FOREVER -> R.string.conv_info_mute_forever
    }
)

@Composable
internal fun categoryLabel(category: MessageCategory): String = stringResource(
    when (category) {
        MessageCategory.PERSONAL -> R.string.conv_info_category_personal
        MessageCategory.OTP -> R.string.conv_info_category_otp
        MessageCategory.TRANSACTION -> R.string.conv_info_category_transaction
        MessageCategory.PROMOTION -> R.string.conv_info_category_promotion
        MessageCategory.SPAM -> R.string.conv_info_category_spam
        MessageCategory.UNKNOWN -> R.string.conv_info_category_unknown
    }
)

@Composable
private fun categorySubtitle(
    state: ConversationInfoController.ConversationInfoUiState,
    isAutomatic: Boolean
): String {
    val effective = state.effectiveCategory
    val label = categoryLabel(effective)
    return if (isAutomatic) {
        stringResource(R.string.conv_info_category_automatic_value, label)
    } else {
        stringResource(R.string.conv_info_category_manual_value, label)
    }
}

@Composable
private fun mediaSubtitle(assetCount: Int?): String? =
    assetCount?.takeIf { it > 0 }?.let { stringResource(R.string.conv_info_media_count, it) }

@Composable
private fun automaticCategoryLabel(category: MessageCategory?): String? =
    category?.let { categoryLabel(it) }

// ── Platform actions ────────────────────────────────────────────────────────

private fun dial(context: Context, participant: com.autonomousone.messages.repository.ConversationParticipantState) {
    if (!participant.hasDialableNumber) return
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:${participant.normalizedPhone}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
    DiagnosticLog.event("CONTACT_ACTION", "known=${participant.isKnownContact} action=call")
}

private fun launchContactInsert(
    context: Context,
    launcher: androidx.activity.result.ActivityResultLauncher<Intent>,
    phone: String,
    failureMessage: String
) {
    if (phone.isBlank()) return
    val intent = Intent(Intent.ACTION_INSERT_OR_EDIT).apply {
        type = ContactsContract.Contacts.CONTENT_ITEM_TYPE
        putExtra(ContactsContract.Intents.Insert.PHONE, phone)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        launcher.launch(intent)
    } catch (_: Exception) {
        android.widget.Toast.makeText(context, failureMessage, android.widget.Toast.LENGTH_SHORT).show()
    }
    DiagnosticLog.event("CONTACT_ACTION", "known=false action=add")
}

private fun launchContactView(
    context: Context,
    launcher: androidx.activity.result.ActivityResultLauncher<Intent>,
    lookupUri: String?,
    failureMessage: String
) {
    val lookup = lookupUri ?: return
    try {
        launcher.launch(Intent(Intent.ACTION_VIEW, Uri.parse(lookup)))
    } catch (_: Exception) {
        android.widget.Toast.makeText(context, failureMessage, android.widget.Toast.LENGTH_SHORT).show()
    }
    DiagnosticLog.event("CONTACT_ACTION", "known=true action=view")
}

private fun copyToClipboard(context: Context, label: String, value: String, toast: String) {
    if (value.isBlank()) return
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
    android.widget.Toast.makeText(context, toast, android.widget.Toast.LENGTH_SHORT).show()
}
