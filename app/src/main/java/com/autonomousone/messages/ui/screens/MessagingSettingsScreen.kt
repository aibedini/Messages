package com.autonomousone.messages.ui.screens

import android.Manifest
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.autonomousone.messages.messaging.MessagingPreferences
import com.autonomousone.messages.viewmodel.MessagingSettingsViewModel

/**
 * Google Messages-style messaging options. Nothing is pre-enabled: every switch
 * starts OFF and every choice starts unset until the user decides.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagingSettingsScreen(navController: NavController) {
    val viewModel: MessagingSettingsViewModel = viewModel()
    var hasPhonePermission by remember { mutableStateOf(viewModel.hasPhonePermission()) }
    var tempSmsc by remember { mutableStateOf(viewModel.smscAddress) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPhonePermission = granted
        if (granted) viewModel.refreshSims()
    }

    LaunchedEffect(hasPhonePermission) {
        if (hasPhonePermission) viewModel.refreshSims()
    }
    // v2.6.14: once the SIM list is known, read each SIM's programmed SMSC.
    LaunchedEffect(viewModel.sims.size) {
        if (viewModel.sims.isNotEmpty()) viewModel.refreshSimSmsc()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Messaging", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SettingsCard(title = "SMS delivery reports") {
                SettingSwitch(
                    title = "Check delivery",
                    subtitle = "Request a delivery report for every SMS and show Sent / Delivered / Failed on outgoing messages.",
                    checked = viewModel.deliveryReportsEnabled,
                    onCheckedChange = { viewModel.setDeliveryReports(it) }
                )
            }

            SettingsCard(title = "SIM slots & sending line") {
                SimSlotContent(viewModel, hasPhonePermission) { granted ->
                    hasPhonePermission = granted
                }
            }

            SettingsCard(title = "SMSC") {
                Text(
                    "Each SIM stores its own service-centre address on the card. " +
                        "By default messages go out through the address programmed on the SIM " +
                        "that sends them. Only change this if you have to.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                if (!hasPhonePermission) {
                    Text(
                        "Grant phone permission on the SIM card above to read each SIM's SMSC.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (viewModel.sims.isEmpty()) {
                    Text(
                        "No active SIM detected.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    viewModel.sims.forEach { sim ->
                        val subId = sim.subscriptionId
                        val manual = viewModel.smscManual[subId]
                        val read = viewModel.simSmscRead[subId]
                        Spacer(modifier = Modifier.height(10.dp))
                        Column {
                            Text(
                                viewModel.labelFor(sim),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                when {
                                    manual != null -> "Manual override: $manual"
                                    read != null -> "On SIM card: $read"
                                    else -> "Reading… / not available on this device"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            if (viewModel.smscEditingSubId == subId) {
                                var temp by remember(subId) { mutableStateOf(manual ?: read ?: "") }
                                OutlinedTextField(
                                    value = temp,
                                    onValueChange = { temp = it },
                                    placeholder = { Text("+98…") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                TextButton(onClick = { viewModel.saveSmscForSim(subId, temp) }) {
                                    Text("Save")
                                }
                            } else {
                                TextButton(
                                    onClick = { viewModel.editSmscForSim(subId) }
                                ) {
                                    Text(if (manual == null) "Set manual override" else "Edit override")
                                }
                                if (manual != null) {
                                    Spacer(modifier = Modifier.weight(1f))
                                    TextButton(
                                        onClick = { viewModel.clearSmscForSim(subId) }
                                    ) { Text("Use SIM default") }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "Global override (applies to every SIM without its own)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = tempSmsc,
                    onValueChange = { tempSmsc = it },
                    label = { Text("Service center address") },
                    placeholder = { Text("+98… (empty = SIM's own address)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        tempSmsc = ""
                        viewModel.saveSmsc("")
                    }) { Text("Clear global") }
                    Spacer(modifier = Modifier.weight(1f))
                    Button(onClick = { viewModel.saveSmsc(tempSmsc) }) { Text("Save SMSC") }
                }
            }

            SettingsCard(title = "Conversations") {
                SettingSwitch(
                    title = "Show iPhone reactions as emoji",
                    subtitle = "iPhone tapbacks like Loved \"See you!\" appear as ❤️ instead of raw text.",
                    checked = viewModel.reactionsAsEmojiEnabled,
                    onCheckedChange = { viewModel.setReactionsAsEmoji(it) }
                )
                Spacer(modifier = Modifier.height(10.dp))
                SettingSwitch(
                    title = "Group messaging",
                    subtitle = "Send messages to multiple recipients together as one group conversation (MMS).",
                    checked = viewModel.groupMessagingEnabled,
                    onCheckedChange = { viewModel.setGroupMessaging(it) }
                )
            }
        }
    }
}

@Composable
private fun SimSlotContent(
    viewModel: MessagingSettingsViewModel,
    hasPhonePermission: Boolean,
    onPermissionResult: (Boolean) -> Unit
) {
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        onPermissionResult(granted)
        if (granted) viewModel.refreshSims()
    }

    if (!hasPhonePermission) {
        Text(
            "Permission is required to identify the SIM card slots.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(10.dp))
        Button(
            onClick = { permissionLauncher.launch(Manifest.permission.READ_PHONE_STATE) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Grant permission")
        }
        return
    }

    SimRow(
        title = "System default",
        subtitle = "Let Android pick the line",
        selected = viewModel.selectedSubscriptionId == MessagingPreferences.SUBSCRIPTION_UNSET,
        onClick = { viewModel.selectSim(MessagingPreferences.SUBSCRIPTION_UNSET) }
    )
    viewModel.sims.forEach { sim ->
        SimRow(
            title = viewModel.labelFor(sim),
            subtitle = sim.number.ifBlank { "Subscription ${sim.subscriptionId}" },
            selected = viewModel.selectedSubscriptionId == sim.subscriptionId,
            onClick = { viewModel.selectSim(sim.subscriptionId) }
        )
    }
    if (viewModel.sims.isEmpty()) {
        Text(
            "No active SIM detected.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SettingsCard(
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
                .padding(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SimRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(6.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

