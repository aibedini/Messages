package com.autonomousone.messages.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.autonomousone.messages.R
import com.autonomousone.messages.event.SmsEventBus
import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.navigation.Screen
import com.autonomousone.messages.repository.ContactRepository
import com.autonomousone.messages.ui.components.ChatBubble
import com.autonomousone.messages.ui.components.ConversationTopBar
import com.autonomousone.messages.ui.components.EmptyView
import com.autonomousone.messages.utils.formatDateHeader
import com.autonomousone.messages.viewmodel.ConversationViewModel
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class ChatListItem {
    data class DateSeparator(val dateText: String) : ChatListItem()
    data class MessageItem(val sms: Sms) : ChatListItem()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun ConversationScreen(
    threadId: Long,
    phone: String,
    name: String,
    navController: NavController,
    forwardText: String = "",
    /** External share text: pre-fills the composer as a DRAFT. The user
     *  presses Send — nothing is auto-sent (unlike [forwardText]). */
    draftText: String = ""
) {
    val context = LocalContext.current
    val viewModel: ConversationViewModel = viewModel()
    // Process-wide reactive draft store (single-activity app: chat → home
    // never passes through Activity.onResume, so a shared StateFlow is the
    // only reliable way for the list to see drafts instantly).
    val draftRepo = remember { com.autonomousone.messages.repository.DraftRepository.get(context) }
    // Stable draft key: thread when known, otherwise the normalized recipient.
    val draftKey = remember(threadId, phone) {
        com.autonomousone.messages.repository.DraftRepository.keyFor(
            threadId, if (phone.isNotBlank()) phone else ""
        )
    }
    var message by remember { mutableStateOf(draftRepo.get(draftKey)) }
    var attachedImageUri by remember { mutableStateOf<Uri?>(null) }
    var attachedAudioUri by remember { mutableStateOf<Uri?>(null) }
    var showAttachmentSheet by remember { mutableStateOf(false) }
    var isFetchingLocation by remember { mutableStateOf(false) }
    var phoneActionNumber by remember { mutableStateOf<String?>(null) }
    var forwardSent by remember { mutableStateOf(false) }

    // ── External share draft: seed the composer ONCE, never auto-send ────────
    LaunchedEffect(draftText) {
        if (draftText.isNotBlank() && message.isBlank()) message = draftText
    }

    // ── Draft persistence ────────────────────────────────────────────────────
    // Saved LIVE on every keystroke (a tiny JSON write) rather than only on
    // dispose: the activity's onResume fires BEFORE the conversation's
    // composition is disposed, so a save-on-dispose alone would land after
    // Home already re-read its drafts. Saving eagerly makes every later read
    // correct regardless of ordering.
    LaunchedEffect(draftKey, message) {
        draftRepo.set(draftKey, message)
    }
    DisposableEffect(draftKey) {
        onDispose {
            // Safety net for the very last keystroke racing disposal.
            draftRepo.set(draftKey, message)
        }
    }

    val listState = rememberLazyListState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // ── Camera capture ──────────────────────────────────────────────────────
    val cameraImageUri = remember {
        val photoFile = File(
            context.cacheDir,
            "camera_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.jpg"
        )
        FileProvider.getUriForFile(context, "${context.packageName}.provider", photoFile)
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            attachedAudioUri = null
            attachedImageUri = cameraImageUri
        }
    }

    // ── Gallery / Image picker ───────────────────────────────────────────────
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            attachedAudioUri = null
            attachedImageUri = uri
        }
    }

    // ── Audio file picker ────────────────────────────────────────────────────
    val audioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            attachedImageUri = null
            attachedAudioUri = uri
        }
    }

    // ── Location permission ──────────────────────────────────────────────────
    val locationPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            isFetchingLocation = true
            val fusedClient = LocationServices.getFusedLocationProviderClient(context)
            val cts = CancellationTokenSource()
            fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                .addOnSuccessListener { location: Location? ->
                    isFetchingLocation = false
                    if (location != null) {
                        val lat = "%.6f".format(location.latitude)
                        val lon = "%.6f".format(location.longitude)
                        val mapsLink = "📍 My location: https://maps.google.com/?q=$lat,$lon"
                        message = if (message.isBlank()) mapsLink else "$message\n$mapsLink"
                    }
                }
                .addOnFailureListener {
                    isFetchingLocation = false
                }
        }
    }

    fun shareLocation() {
        val fineGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (fineGranted || coarseGranted) {
            isFetchingLocation = true
            val fusedClient = LocationServices.getFusedLocationProviderClient(context)
            val cts = CancellationTokenSource()
            val priority = if (fineGranted) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
            fusedClient.getCurrentLocation(priority, cts.token)
                .addOnSuccessListener { location: Location? ->
                    isFetchingLocation = false
                    if (location != null) {
                        val lat = "%.6f".format(location.latitude)
                        val lon = "%.6f".format(location.longitude)
                        val mapsLink = "📍 My location: https://maps.google.com/?q=$lat,$lon"
                        message = if (message.isBlank()) mapsLink else "$message\n$mapsLink"
                    }
                }
                .addOnFailureListener { isFetchingLocation = false }
        } else {
            locationPermLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    // ────────────────────────────────────────────────────────────────────────

    LaunchedEffect(threadId, phone) {
        if (threadId != 0L) {
            viewModel.loadConversation(threadId, phone)
        } else {
            viewModel.setPhone(phone)
        }
    }

    // Background jobs that crash-guarded into an error state surface it once
    // as a snackbar instead of killing the process; rows already on screen
    // stay painted either way.
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel.errorMessage) {
        viewModel.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeError()
        }
    }

    val messages = viewModel.messages

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

    val recipientPhone = remember(phone, messages.size) {
        if (phone.isNotBlank()) phone
        else if (messages.isNotEmpty()) messages.first().sender
        else ""
    }

    // ── Forwarded message: send once the recipient is known ─────────────────
    LaunchedEffect(forwardText, recipientPhone) {
        if (!forwardSent && forwardText.isNotBlank() && recipientPhone.isNotBlank()) {
            forwardSent = true
            viewModel.sendMessage(threadId, recipientPhone, forwardText)
        }
    }

    // ── Instant bottom position: jump BEFORE paint, no visible scrolling ────
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= info.totalItemsCount - 2
        }
    }
    // One bottom-anchor per OPENED conversation: remembering it by
    // (threadId, phone) means a second chat opened in the same composition
    // gets a fresh anchor instead of inheriting the previous thread's
    // "already anchored" state — which used to leave it stuck un-scrolled.
    var anchoredToBottom by remember(threadId, phone) { mutableStateOf(false) }
    // Set right after the user sends so the list ALWAYS jumps to their new
    // message — even if they had scrolled up to re-read something.
    var forceScrollToBottom by remember(threadId, phone) { mutableStateOf(false) }
    // Windowed history: pull older pages when the top is reached.
    var loadingOlder by remember { mutableStateOf(false) }

    // Identity of the newest row. The provider refresh can REPLACE the Room
    // window with an exactly-equal-sized list; keying the anchor on size alone
    // then never re-fires and the user sits on the oldest row of the window.
    // "id:date" of the tail guarantees the jump-to-newest happens once per
    // open — and never yanks the user back down after they scrolled up.
    val newestMessageKey = messages.lastOrNull()?.let { "${it.id}:${it.date}" }

    LaunchedEffect(threadId, phone, chatItems.size, newestMessageKey) {
        if (chatItems.isEmpty()) return@LaunchedEffect
        when {
            !anchoredToBottom -> {
                // First layout of this conversation: land on the newest message
                // immediately (requestScrollToItem applies before the next frame —
                // the user never sees a scroll animation from top to bottom).
                listState.requestScrollToItem(chatItems.lastIndex)
                anchoredToBottom = true
            }
            forceScrollToBottom || atBottom -> {
                // Own outgoing message, or following live updates at bottom.
                listState.requestScrollToItem(chatItems.lastIndex)
                forceScrollToBottom = false
            }
        }
    }
    // Older pages load ONLY after a REAL user upward scroll reaches the top.
    // canScrollBackward lies for small windows: a freshly opened 12-message
    // chat is not scrollable-back at startup, which looked exactly like
    // "top reached" and fetched history nobody asked for. Here the DRAG
    // itself is the precondition: firstVisibleItemIndex must decrease while a
    // scroll is in progress and arrive at index 0. Initial render ≠ request.
    LaunchedEffect(threadId, phone) {
        loadingOlder = false
        var prevIndex = -1
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.isScrollInProgress
        }
            .distinctUntilChanged()
            .collect { (index, scrolling) ->
                val movedUpWhileDragging = scrolling && prevIndex >= 0 && index < prevIndex
                prevIndex = index
                if (
                    movedUpWhileDragging && index == 0 &&
                    !loadingOlder && anchoredToBottom && viewModel.hasMoreOlder()
                ) {
                    loadingOlder = true
                    viewModel.loadOlderMessages()
                    kotlinx.coroutines.delay(400) // let composition settle before re-arming
                    loadingOlder = false
                }
            }
    }

    val title = remember(phone, name, recipientPhone) {
        if (name.isNotBlank()) name
        else {
            val cached = ContactRepository(context).getCachedDisplayName(recipientPhone)
            if (cached.isNotBlank()) cached else if (recipientPhone.isNotBlank()) recipientPhone else "Conversation"
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            ConversationTopBar(
                title = title,
                phone = recipientPhone,
                onBackClick = { navController.popBackStack() },
                onCallClick = {
                    // Group threads carry comma-joined recipients; dial the first only.
                    val dialTarget = recipientPhone.split(',', ';').firstOrNull()?.trim() ?: ""
                    if (dialTarget.isNotBlank()) {
                        val callIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$dialTarget"))
                        context.startActivity(callIntent)
                    }
                },
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
            if (viewModel.isLoading && chatItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    // Windowed load is a single small page — keep it minimal.
                    CircularProgressIndicator()
                }
            } else if (chatItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    EmptyView(
                        title = stringResource(R.string.conv_start_title),
                        subtitle = stringResource(R.string.conv_start_subtitle, title),
                        buttonText = null,
                        onButtonClick = null
                    )
                }
            } else {
                // ── Pull-to-refresh (Instagram-style): drag down at the top of
                // the thread to silently re-check the provider for updates.
                // Spinner is bound to the ViewModel's real refresh state.
                androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                    isRefreshing = viewModel.isRefreshing,
                    onRefresh = {
                        if (!viewModel.isRefreshing) viewModel.refresh()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (loadingOlder) {
                        item(key = "older_messages_sync") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.conv_syncing_older),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
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
                                ChatBubble(
                                    sms = item.sms,
                                    onForward = { text ->
                                        navController.navigate(Screen.NewConversation.createForwardRoute(text))
                                    },
                                    onPhoneClick = { number -> phoneActionNumber = number }
                                )
                            }
                        }
                    }
                }
                }
            }

            // ── Attachment Preview Card ─────────────────────────────────────
            AnimatedVisibility(
                visible = attachedImageUri != null || attachedAudioUri != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    tonalElevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (attachedImageUri != null) {
                            AsyncImage(
                                model = attachedImageUri,
                                contentDescription = "Preview",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(RoundedCornerShape(12.dp))
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.conv_photo_attached),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Text(
                                    text = stringResource(R.string.conv_photo_will_be_mms),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                                )
                            }
                            IconButton(onClick = { attachedImageUri = null }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.conv_remove_attachment),
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        } else if (attachedAudioUri != null) {
                            Box(
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AudioFile,
                                    contentDescription = "Audio",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.conv_audio_attached),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Text(
                                    text = stringResource(R.string.conv_photo_will_be_mms),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                                )
                            }
                            IconButton(onClick = { attachedAudioUri = null }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.conv_remove_attachment),
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }
                }
            }

            // ── Quick reply suggestions (WhatsApp-Business style) ────────────
            if (message.trimStart().startsWith("/")) {
                val quickPrefs = remember { com.autonomousone.messages.messaging.QuickRepliesPreferences(context) }
                val quickMatches = remember(message) {
                    quickPrefs.match(message.trim()).take(6)
                }
                if (quickMatches.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        quickMatches.forEach { qr ->
                            SuggestionChip(
                                onClick = { message = qr.text },
                                label = { Text("${qr.shortcut}  ·  ${qr.text.take(28)}") }
                            )
                        }
                    }
                }
            }

            // ── SIM selector chip (only when 2+ SIMs are active) ────────────
            val simManager = remember { com.autonomousone.messages.messaging.SimManager(context) }
            val activeSims = remember { simManager.getActiveSims() }
            val messagingPrefs = remember { com.autonomousone.messages.messaging.MessagingPreferences(context) }
            val simRules = remember { com.autonomousone.messages.repository.SimRulesRepository.get(context) }
            // Priority for the initial selection:
            //   1. Per-contact rule (if this conversation has one)
            //   2. Global default from Messaging settings
            var selectedSubId by remember {
                mutableStateOf(
                    simRules.ruleFor(if (phone.isNotBlank()) phone else "")
                        ?: messagingPrefs.sendSubscriptionId.takeIf {
                            it != com.autonomousone.messages.messaging.MessagingPreferences.SUBSCRIPTION_UNSET
                        }
                )
            }
            if (activeSims.size >= 2) {
                val current = activeSims.firstOrNull { it.subscriptionId == selectedSubId }
                var simMenuOpen by remember { mutableStateOf(false) }
                Box {
                    androidx.compose.material3.AssistChip(
                        onClick = { simMenuOpen = true },
                        label = {
                            Text(
                                text = current?.let { stringResource(R.string.sim_slot_fmt, it.slotIndex + 1) }
                                    ?: stringResource(R.string.sim_default_label),
                                style = MaterialTheme.typography.labelMedium
                            )
                        },
                        trailingIcon = {
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = stringResource(R.string.sim_switch),
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        modifier = Modifier.padding(start = 12.dp)
                    )
                    DropdownMenu(expanded = simMenuOpen, onDismissRequest = { simMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.sim_system_default)) },
                            onClick = {
                                selectedSubId = null
                                messagingPrefs.sendSubscriptionId =
                                    com.autonomousone.messages.messaging.MessagingPreferences.SUBSCRIPTION_UNSET
                                simRules.setRule(phone, null)
                                simMenuOpen = false
                            }
                        )
                        activeSims.forEach { sim ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "${simManager.labelFor(sim)}" +
                                                if (sim.number.isNotBlank()) " · ${sim.number}" else ""
                                    )
                                },
                                onClick = {
                                    selectedSubId = sim.subscriptionId
                                    messagingPrefs.sendSubscriptionId = sim.subscriptionId
                                    // Pin this line to this contact as well.
                                    simRules.setRule(phone, sim.subscriptionId)
                                    simMenuOpen = false
                                }
                            )
                        }
                    }
                }
            }

            // ── Message Input Bar ───────────────────────────────────────────
            // Standards-based segment counter. UX:
            //  1 segment  → "142 characters left"
            //  N segments → "2/3 SMS · 24 characters left" (remaining in the
            //               LAST part — that's the number the user actually needs).
            val segmentInfo = remember(message) {
                com.autonomousone.messages.utils.SmsSegmentCounter.count(message)
            }
            androidx.compose.animation.AnimatedVisibility(visible = message.isNotBlank()) {
                Text(
                    text = if (segmentInfo.segments <= 1) {
                        stringResource(R.string.conv_segment_one_left, segmentInfo.charsRemainingInLast)
                    } else {
                        val remaining = segmentInfo.charsRemainingInLast.coerceAtLeast(0)
                        stringResource(R.string.conv_segment_multi_fmt, segmentInfo.segments, remaining)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                )
            }

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
                    IconButton(onClick = { showAttachmentSheet = true }) {
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
                                text = when {
                                    isFetchingLocation -> stringResource(R.string.conv_fetching_location)
                                    attachedImageUri != null || attachedAudioUri != null ->
                                        stringResource(R.string.conv_caption_hint)
                                    else -> stringResource(R.string.conv_input_hint)
                                },
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

                    val canSend = message.isNotBlank() || attachedImageUri != null || attachedAudioUri != null
                    val sendScale by animateFloatAsState(
                        targetValue = if (canSend) 1f else 0.85f,
                        animationSpec = spring(stiffness = 400f),
                        label = "sendScale"
                    )

                    // ── Schedule send (long-press the send button) ────────────
                    var showScheduleDialog by remember { mutableStateOf(false) }
                    if (showScheduleDialog) {
                        val destination = if (recipientPhone.isNotBlank()) recipientPhone else phone
                        val msgToSend = message
                        ScheduleSendDialog(
                            onDismiss = { showScheduleDialog = false },
                            onConfirm = { triggerAt ->
                                showScheduleDialog = false
                                if (destination.isNotBlank() && msgToSend.isNotBlank()) {
                                    com.autonomousone.messages.sms.ScheduledSms.schedule(
                                        context, destination, msgToSend, triggerAt
                                    )
                                    message = ""
                                    // Scheduled is as good as queued — no draft.
                                    draftRepo.set(draftKey, "")
                                    android.widget.Toast.makeText(
                                        context,
                                        context.getString(R.string.sched_success_toast),
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        )
                    }

                    IconButton(
                        onClick = {
                            if (!canSend) return@IconButton
                            val destination = if (recipientPhone.isNotBlank()) recipientPhone else phone
                            val msgToSend = message
                            val currentImage = attachedImageUri
                            val currentAudio = attachedAudioUri

                            message = ""
                            attachedImageUri = null
                            attachedAudioUri = null
                            // Message is leaving as a real send — drop the draft.
                            draftRepo.set(draftKey, "")
                            // Always land the view on our new message.
                            forceScrollToBottom = true

                            if (currentImage != null) {
                                viewModel.sendImageMessage(
                                    threadId = threadId,
                                    phone = destination,
                                    imageUri = currentImage,
                                    caption = msgToSend
                                )
                            } else if (currentAudio != null) {
                                viewModel.sendAudioMessage(
                                    threadId = threadId,
                                    phone = destination,
                                    audioUri = currentAudio,
                                    caption = msgToSend
                                )
                            } else {
                                viewModel.sendMessage(
                                    threadId = threadId,
                                    phone = destination,
                                    message = msgToSend,
                                    subscriptionOverride = selectedSubId
                                )
                            }
                        },
                        enabled = canSend,
                        modifier = Modifier
                            .scale(sendScale)
                            .combinedClickable(
                                onClick = {},
                                onLongClick = {
                                    // Long-press send = schedule instead of send now.
                                    if (message.isNotBlank()) showScheduleDialog = true
                                }
                            )
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
                                contentDescription = "Send",
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

        // ── Attachment Bottom Sheet ──────────────────────────────────────────
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
                        text = stringResource(R.string.conv_share_content),
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
                            label = stringResource(R.string.conv_attach_gallery),
                            color = Color(0xFF3B82F6),
                            onClick = {
                                showAttachmentSheet = false
                                galleryLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            }
                        )
                        AttachmentOptionItem(
                            icon = Icons.Default.CameraAlt,
                            label = stringResource(R.string.conv_attach_camera),
                            color = Color(0xFFEC4899),
                            onClick = {
                                showAttachmentSheet = false
                                cameraLauncher.launch(cameraImageUri)
                            }
                        )
                        AttachmentOptionItem(
                            icon = Icons.Default.AudioFile,
                            label = stringResource(R.string.conv_attach_audio),
                            color = Color(0xFF8B5CF6),
                            onClick = {
                                showAttachmentSheet = false
                                audioLauncher.launch("audio/*")
                            }
                        )
                        AttachmentOptionItem(
                            icon = Icons.Default.LocationOn,
                            label = stringResource(R.string.conv_attach_location),
                            color = Color(0xFF10B981),
                            onClick = {
                                showAttachmentSheet = false
                                shareLocation()
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))
                }
            }
        }
    }

    // ── Phone-number action sheet (tap a number inside a message) ───────────
    phoneActionNumber?.let { number ->
        // Dialer/contacts/SM-RIL need ASCII digits; Persian digits break them.
        val asciiNumber = com.autonomousone.messages.utils.DigitNormalizer.toAsciiDigits(number)
        PhoneNumberActionDialog(
            number = number,
            onDismiss = { phoneActionNumber = null },
            onSendSms = {
                phoneActionNumber = null
                navController.navigate(
                    Screen.Conversation.createNewRoute(phone = asciiNumber, name = asciiNumber)
                )
            },
            onCall = {
                phoneActionNumber = null
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$asciiNumber")))
                }
            },
            onAddContact = {
                phoneActionNumber = null
                runCatching {
                    val intent = Intent(android.provider.ContactsContract.Intents.Insert.ACTION).apply {
                        type = android.provider.ContactsContract.RawContacts.CONTENT_TYPE
                        putExtra(android.provider.ContactsContract.Intents.Insert.PHONE, asciiNumber)
                    }
                    context.startActivity(intent)
                }
            },
            onCopy = {
                phoneActionNumber = null
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("number", number))
                android.widget.Toast.makeText(context, context.getString(R.string.conv_copied), android.widget.Toast.LENGTH_SHORT).show()
            }
        )
    }
}

/**
 * Actions for a phone number detected inside a message body:
 * send SMS, call, add to contacts, copy.
 */
@Composable
private fun PhoneNumberActionDialog(
    number: String,
    onDismiss: () -> Unit,
    onSendSms: () -> Unit,
    onCall: () -> Unit,
    onAddContact: () -> Unit,
    onCopy: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(number) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                androidx.compose.material3.TextButton(onClick = onSendSms) { Text(stringResource(R.string.conv_send_sms_action)) }
                androidx.compose.material3.TextButton(onClick = onCall) { Text(stringResource(R.string.conv_call)) }
                androidx.compose.material3.TextButton(onClick = onAddContact) { Text(stringResource(R.string.conv_add_to_contacts)) }
                androidx.compose.material3.TextButton(onClick = onCopy) { Text(stringResource(R.string.conv_copy_number)) }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
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
/**
 * Schedule-send dialog: quick presets + a custom date/time picker.
 * Confirms with the exact epoch-millis trigger time for [ScheduledSms].
 */
@Composable
private fun ScheduleSendDialog(
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit
) {
    val context = LocalContext.current
    var pickedMillis by remember { mutableStateOf<Long?>(null) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sched_dialog_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.sched_dialog_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                            context.getString(R.string.sched_in_1_hour) to 1L,
                            context.getString(R.string.sched_in_3_hours) to 3L,
                            context.getString(R.string.sched_tomorrow_9am) to -1L
                        ).forEach { (label, hours) ->
                        androidx.compose.material3.FilterChip(
                            selected = false,
                            onClick = {
                                pickedMillis = when {
                                    hours == -1L -> {
                                        val cal = java.util.Calendar.getInstance().apply {
                                            add(java.util.Calendar.DAY_OF_YEAR, 1)
                                            set(java.util.Calendar.HOUR_OF_DAY, 9)
                                            set(java.util.Calendar.MINUTE, 0)
                                            set(java.util.Calendar.SECOND, 0)
                                        }
                                        cal.timeInMillis
                                    }
                                    else -> System.currentTimeMillis() + hours * 3_600_000L
                                }
                            },
                            label = { Text(label) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = {
                    val cal = java.util.Calendar.getInstance()
                    android.app.DatePickerDialog(
                        context,
                        { _, y, m, d ->
                            android.app.TimePickerDialog(
                                context,
                                { _, hh, mm ->
                                    cal.set(y, m, d, hh, mm, 0)
                                    pickedMillis = cal.timeInMillis
                                },
                                cal.get(java.util.Calendar.HOUR_OF_DAY),
                                cal.get(java.util.Calendar.MINUTE),
                                true
                            ).show()
                        },
                        cal.get(java.util.Calendar.YEAR),
                        cal.get(java.util.Calendar.MONTH),
                        cal.get(java.util.Calendar.DAY_OF_MONTH)
                    ).show()
                }) {
                    Text(if (pickedMillis == null) context.getString(R.string.sched_pick_date_time) else context.getString(R.string.sched_change_date_time))
                }
                pickedMillis?.let {
                    Text(
                        text = stringResource(R.string.sched_will_send_fmt, com.autonomousone.messages.utils.formatFullTimestamp(it)),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = pickedMillis != null,
                onClick = { pickedMillis?.let(onConfirm) }
            ) { Text(stringResource(R.string.sched_confirm_button)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
