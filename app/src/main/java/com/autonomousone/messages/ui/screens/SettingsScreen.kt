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
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.core.content.FileProvider
import com.autonomousone.messages.BuildConfig
import com.autonomousone.messages.R
import com.autonomousone.messages.navigation.Screen
import com.autonomousone.messages.viewmodel.DataToolsViewModel
import com.autonomousone.messages.utils.DiagnosticLog

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
            SectionCard(title = stringResource(R.string.settings_section_sms)) {
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

            SectionCard(title = stringResource(R.string.settings_section_messaging)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { navController.navigate(Screen.LinkedDevices.route) },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Linked devices", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Pair the web app by scanning its QR code",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text("›", style = MaterialTheme.typography.titleLarge)
                }
                Spacer(modifier = Modifier.height(12.dp))
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

            SectionCard(title = stringResource(R.string.settings_section_appearance)) {
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

            SectionCard(title = stringResource(R.string.settings_section_quick_replies)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { navController.navigate(Screen.QuickReplies.route) },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_quick_replies_subtitle),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.settings_quick_replies_hint),
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

                // Scheduled messages management
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { navController.navigate(Screen.ScheduledMessages.route) },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.sched_screen_title),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.sched_screen_hint),
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

            SectionCard(title = stringResource(R.string.settings_section_data_tools)) {
                val context = LocalContext.current
                val dataTools: DataToolsViewModel = viewModel()
                var showDeleteDialog by remember { mutableStateOf(false) }

                // Privacy-aware rotating diagnostic log. The export contains
                // state transitions/result codes, never SMS bodies or full
                // phone numbers.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val file = DiagnosticLog.createExportFile(context)
                            if (file == null) {
                                Toast.makeText(context, R.string.diagnostics_export_failed, Toast.LENGTH_SHORT).show()
                            } else {
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.provider",
                                    file
                                )
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(
                                    Intent.createChooser(share, context.getString(R.string.diagnostics_export))
                                )
                            }
                        },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.diagnostics_export),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.diagnostics_export_desc),
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
                                        Intent.createChooser(share, context.getString(R.string.data_export_all))
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
                            text = if (dataTools.busy) stringResource(R.string.settings_working) else stringResource(R.string.data_export_all),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.data_export_all_desc),
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

                // Backup to XML (SAF — no storage permission needed)
                val backupLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/xml")
                ) { uri ->
                    if (uri != null) dataTools.backupTo(uri) { count ->
                        Toast.makeText(
                            context,
                            if (count != null) context.getString(R.string.data_backup_ok_fmt, count) else context.getString(R.string.data_backup_failed),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !dataTools.busy) {
                            backupLauncher.launch("messages-backup.xml")
                        },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (dataTools.busy) stringResource(R.string.settings_working) else stringResource(R.string.data_backup),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.data_backup_desc),
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

                // Restore from a backup file (SAF)
                var confirmRestore by remember { mutableStateOf<android.net.Uri?>(null) }
                val restoreLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
                ) { uri ->
                    if (uri != null) confirmRestore = uri
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !dataTools.busy) {
                            restoreLauncher.launch(arrayOf("application/xml", "text/xml", "text/plain", "*/*"))
                        },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.data_restore),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.data_restore_desc),
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

                if (confirmRestore != null) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { confirmRestore = null },
                        title = { Text(stringResource(R.string.data_restore_confirm_title)) },
                        text = { Text(stringResource(R.string.data_restore_confirm_body)) },
                        confirmButton = {
                            TextButton(onClick = {
                                val uri = confirmRestore
                                confirmRestore = null
                                if (uri != null) {
                                    dataTools.restoreFrom(uri) { count ->
                                        Toast.makeText(
                                            context,
                                            if (count != null) context.getString(R.string.data_restore_ok_fmt, count) else context.getString(R.string.data_restore_failed),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }) { Text(stringResource(R.string.action_restore)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmRestore = null }) { Text(stringResource(R.string.action_cancel)) }
                        }
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
                            text = stringResource(R.string.data_delete_by_period),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.data_delete_by_period_desc),
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

            SectionCard(title = stringResource(R.string.settings_section_security)) {
                val context = LocalContext.current
                var lockEnabled by remember {
                    mutableStateOf(com.autonomousone.messages.utils.AppLockPreferences(context).isEnabled)
                }
                val biometricOk = remember {
                    com.autonomousone.messages.utils.isBiometricAvailable(context)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.security_app_lock),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (biometricOk) stringResource(R.string.security_app_lock_on)
                            else stringResource(R.string.security_app_lock_unavailable),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = lockEnabled && biometricOk,
                        enabled = biometricOk,
                        onCheckedChange = { checked ->
                            com.autonomousone.messages.utils.AppLockPreferences(context).isEnabled = checked
                            lockEnabled = checked
                        }
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Quiet hours: silence notifications during a daily window.
                val quietPrefs = remember {
                    com.autonomousone.messages.utils.QuietHoursPreferences(context)
                }
                var quietEnabled by remember { mutableStateOf(quietPrefs.enabled) }
                var quietStart by remember { mutableStateOf(quietPrefs.startHour) }
                var quietEnd by remember { mutableStateOf(quietPrefs.endHour) }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.quiet_hours_title),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (quietEnabled)
                                stringResource(R.string.quiet_hours_on_fmt, quietStart, quietEnd)
                            else
                                stringResource(R.string.quiet_hours_off),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = quietEnabled,
                        onCheckedChange = { checked ->
                            quietPrefs.enabled = checked
                            quietEnabled = checked
                        }
                    )
                }
                if (quietEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.quiet_hours_start),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        androidx.compose.material3.OutlinedTextField(
                            value = quietStart.toString(),
                            onValueChange = { v ->
                                v.toIntOrNull()?.let {
                                    quietStart = it
                                    quietPrefs.startHour = it
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.width(70.dp)
                        )
                        Text(text = stringResource(R.string.quiet_hours_end), style = MaterialTheme.typography.bodyMedium)
                        androidx.compose.material3.OutlinedTextField(
                            value = quietEnd.toString(),
                            onValueChange = { v ->
                                v.toIntOrNull()?.let {
                                    quietEnd = it
                                    quietPrefs.endHour = it
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.width(70.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Blocked numbers management
                var showBlocked by remember { mutableStateOf(false) }
                var blockedNumbers by remember {
                    mutableStateOf(setOf<String>())
                }
                val blocklistRepo = remember {
                    com.autonomousone.messages.repository.BlocklistRepository(context)
                }
                LaunchedEffect(showBlocked) {
                    if (showBlocked) blockedNumbers = blocklistRepo.getBlocked()
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.security_blocked_numbers),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.security_blocked_numbers_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { showBlocked = true }) {
                        Text("Manage")
                    }
                }

                if (showBlocked) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { showBlocked = false },
                        title = { Text(stringResource(R.string.blocked_title)) },
                        text = {
                            Column {
                                if (blockedNumbers.isEmpty()) {
                                    Text(stringResource(R.string.blocked_none_yet))
                                } else {
                                    blockedNumbers.sorted().forEach { number ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(number, style = MaterialTheme.typography.bodyMedium)
                                            TextButton(onClick = {
                                                blocklistRepo.unblock(number)
                                                blockedNumbers = blocklistRepo.getBlocked()
                                            }) { Text(stringResource(R.string.action_unblock)) }
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showBlocked = false }) { Text(stringResource(R.string.action_done)) }
                        }
                    )
                }
            }

            SectionCard(title = stringResource(R.string.settings_section_app)) {
                InfoRow(label = stringResource(R.string.settings_version), value = BuildConfig.VERSION_NAME)
                Spacer(modifier = Modifier.height(8.dp))
                InfoRow(label = stringResource(R.string.settings_version_code), value = BuildConfig.VERSION_CODE.toString())
                Spacer(modifier = Modifier.height(8.dp))
                InfoRow(label = stringResource(R.string.settings_package), value = BuildConfig.APPLICATION_ID)
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
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
