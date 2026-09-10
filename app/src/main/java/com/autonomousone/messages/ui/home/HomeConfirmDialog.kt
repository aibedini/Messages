package com.autonomousone.messages.ui.home

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.autonomousone.messages.R
import com.autonomousone.messages.ui.theme.StatusError

/**
 * Two-stage confirm dialog for Home archive / unarchive / delete actions.
 *
 * Home parks the swiped row in a pending state and shows THIS dialog before
 * anything mutates (anti-fat-finger gate, v2.6.19). Stateless: title/text/
 * confirm label/action are supplied by the screen.
 */
@Composable
fun HomeConfirmDialog(
    title: String,
    body: String,
    destructive: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                val label = stringResource(
                    if (destructive) R.string.action_delete else R.string.list_archive
                )
                if (destructive) {
                    Text(label, color = StatusError)
                } else {
                    Text(label)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
