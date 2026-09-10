package com.autonomousone.messages.ui.home

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Home "Start chat" floating action button. Stateless; expansion (showing the
 * label) is driven by the screen so scroll position stays outside this
 * component (RFP §8). No I/O here.
 */
@Composable
fun HomeFab(
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        expanded = expanded,
        icon = {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = "New Conversation"
            )
        },
        text = {
            Text(
                text = "Start chat",
                fontWeight = FontWeight.SemiBold
            )
        },
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
    )
}
