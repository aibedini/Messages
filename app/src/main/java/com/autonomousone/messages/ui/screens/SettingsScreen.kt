package com.autonomousone.messages.ui.screens

import android.content.Intent
import android.widget.Toast
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.autonomousone.messages.BuildConfig
import com.autonomousone.messages.navigation.Screen
import com.autonomousone.messages.viewmodel.DataToolsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    hasPermission: Boolean,
    isDefaultSmsApp: Boolean,
    onRequestPermissions: () -> Unit,
    onRequestDefaultApp: () -> Unit,
    navController: NavController
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
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
            SectionCard(title = "SMS") {
                StatusRow(
                    label = "Default SMS app",
                    isOk = isDefaultSmsApp,
                    okText = "Yes",
                    notOkText = "Not set"
                )
                if (!isDefaultSmsApp) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onRequestDefaultApp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Set as Default SMS App")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                StatusRow(
                    label = "SMS & contacts permissions",
                    isOk = hasPermission,
                    okText = "Granted",
                    notOkText = "Missing"
                )
                if (!hasPermission) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onRequestPermissions,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Grant Permissions")
                    }
                }
            }

            SectionCard(title = "Messaging") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { navController.navigate(Screen.MessagingSettings.route) },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Delivery reports, SIM line, SMSC…",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Delivery check · SIM slots · SMSC · iPhone reactions · Group messaging",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = ">",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            SectionCard(title = "Appearance") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { navController.navigate(Screen.AppearanceSettings.route) },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Theme color, dark mode, calendar",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Color presets · Light/Dark/System · Gregorian/Persian",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = ">",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            SectionCard(title = "Data tools") {
                val context = LocalContext.current
                val dataTools: DataToolsViewModel = viewModel()
                var showDeleteDialog by remember { mutableStateOf(false) }

                // Export row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !dataTools.busy) {
                            dataTools.exportAll { uri ->
                                if (uri != null) {
                                    val share = Intent(Intent.ACTION_SEND).apply {
                                        type = "application/json"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(
                                        Intent.createChooser(share, "Export chats")
                                    )
                                } else {
                                    Toast.makeText(context, "Export failed", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (dataTools.busy) "Working…" else "Export all chats",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Share every conversation as a JSON archive",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = ">",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Delete-by-period row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !dataTools.busy) { showDeleteDialog = true },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Delete messages by period",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Bulk-delete everything before/after a date & time",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = ">",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                dataTools.lastStatus?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                if (showDeleteDialog) {
                    DeleteByPeriodDialog(
                        onDismiss = { showDeleteDialog = false },
                        onConfirm = { cutoffMillis, before ->
                            showDeleteDialog = false
                            dataTools.deleteByRange(cutoffMillis, before) { _, _ -> }
                        }
                    )
                }
            }

            SectionCard(title = "App") {
                InfoRow(label = "Version", value = BuildConfig.VERSION_NAME)
                Spacer(modifier = Modifier.height(8.dp))
                InfoRow(label = "Version code", value = BuildConfig.VERSION_CODE.toString())
                Spacer(modifier = Modifier.height(8.dp))
                InfoRow(label = "Package", value = BuildConfig.APPLICATION_ID)
            }

            SectionCard(title = "Gateway") {
                InfoRow(label = "Backend URL", value = BuildConfig.GATEWAY_BACKEND_URL)
            }
        }
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
private fun StatusRow(
    label: String,
    isOk: Boolean,
    okText: String,
    notOkText: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Surface(
            shape = MaterialTheme.shapes.small,
            color = if (isOk) Color(0xFF2E7D32) else Color(0xFFC62828)
        ) {
            Text(
                text = if (isOk) okText else notOkText,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Bulk-delete picker: choose a date + time and whether to delete everything
 * BEFORE or AFTER that instant. Deletion covers SMS and MMS.
 */
@Composable
private fun DeleteByPeriodDialog(
    onDismiss: () -> Unit,
    onConfirm: (cutoffMillis: Long, before: Boolean) -> Unit
) {
    val context = LocalContext.current
    val calendar = remember { java.util.Calendar.getInstance() }

    var before by remember { mutableStateOf(true) }
    var pickedDate by remember { mutableStateOf<Long?>(null) }
    var pickedHour by remember { mutableIntStateOf(0) }
    var pickedMinute by remember { mutableIntStateOf(0) }

    val cutoffMillis: Long? = pickedDate?.let { date ->
        java.util.Calendar.getInstance().apply {
            timeInMillis = date
            set(java.util.Calendar.HOUR_OF_DAY, pickedHour)
            set(java.util.Calendar.MINUTE, pickedMinute)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    val dateLabel = pickedDate?.let {
        com.autonomousone.messages.utils.formatDate(it, "yyyy/MM/dd")
    } ?: "Pick date"

    val timeLabel = "%02d:%02d".format(pickedHour, pickedMinute)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete messages by period") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Direction toggle
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { before = true },
                        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                            containerColor = if (before) MaterialTheme.colorScheme.errorContainer
                            else Color.Transparent
                        )
                    ) { Text("Before", color = MaterialTheme.colorScheme.error) }

                    OutlinedButton(
                        onClick = { before = false },
                        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                            containerColor = if (!before) MaterialTheme.colorScheme.errorContainer
                            else Color.Transparent
                        )
                    ) { Text("After", color = MaterialTheme.colorScheme.error) }
                }

                Text(
                    text = if (before) "Deletes messages sent up to and including the chosen moment."
                    else "Deletes messages sent from the chosen moment onwards.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Date & time pickers (platform dialogs — locale aware)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = {
                        android.app.DatePickerDialog(
                            context,
                            { _, y, m, d ->
                                calendar.set(y, m, d)
                                pickedDate = calendar.timeInMillis
                            },
                            calendar.get(java.util.Calendar.YEAR),
                            calendar.get(java.util.Calendar.MONTH),
                            calendar.get(java.util.Calendar.DAY_OF_MONTH)
                        ).show()
                    }) { Text(dateLabel) }

                    OutlinedButton(onClick = {
                        android.app.TimePickerDialog(
                            context,
                            { _, h, min ->
                                pickedHour = h
                                pickedMinute = min
                            },
                            pickedHour, pickedMinute, true
                        ).show()
                    }) { Text(timeLabel) }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = cutoffMillis != null,
                onClick = { cutoffMillis?.let { onConfirm(it, before) } }
            ) { Text("Delete", color = MaterialTheme.colorScheme.onError) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
