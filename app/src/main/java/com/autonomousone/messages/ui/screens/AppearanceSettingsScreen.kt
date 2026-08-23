package com.autonomousone.messages.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.autonomousone.messages.ui.theme.ThemeController
import com.autonomousone.messages.ui.theme.ThemePresets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettingsScreen(
    navController: NavController
) {
    val state by ThemeController.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Appearance",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Theme color presets ──────────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Theme color", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        ThemePresets.all.forEach { preset ->
                            val selected = state.presetId == preset.id
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(preset.swatch)
                                    .then(
                                        if (selected) {
                                            Modifier.border(
                                                width = 3.dp,
                                                color = MaterialTheme.colorScheme.primary,
                                                shape = CircleShape
                                            )
                                        } else {
                                            Modifier.border(
                                                width = 1.dp,
                                                color = MaterialTheme.colorScheme.outlineVariant,
                                                shape = CircleShape
                                            )
                                        }
                                    )
                                    .clickable { ThemeController.setPreset(preset.id) }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = ThemePresets.byId(state.presetId).label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ── Dark / Light mode ────────────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Mode", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        ThemeController.Mode.entries.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = state.mode == mode,
                                onClick = { ThemeController.setMode(mode) },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = ThemeController.Mode.entries.size
                                )
                            ) {
                                Text(
                                    when (mode) {
                                        ThemeController.Mode.SYSTEM -> "System"
                                        ThemeController.Mode.LIGHT -> "Light"
                                        ThemeController.Mode.DARK -> "Dark"
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // ── Calendar system ──────────────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                    Text(
                        text = "Calendar",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                    CalendarOption(
                        title = "Gregorian",
                        subtitle = "Oct 24 · 2026",
                        selected = !state.calendarPersian,
                        onClick = { ThemeController.setCalendar(false) }
                    )
                    CalendarOption(
                        title = "Persian (شمسی)",
                        subtitle = "۲۴ مهر ۱۴۰۵",
                        selected = state.calendarPersian,
                        onClick = { ThemeController.setCalendar(true) }
                    )
                }
            }

            Text(
                text = "Applies instantly across chats, lists and message details.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

@Composable
private fun CalendarOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.size(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
    }
}
