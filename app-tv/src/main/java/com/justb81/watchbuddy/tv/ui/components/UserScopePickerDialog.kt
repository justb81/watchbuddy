package com.justb81.watchbuddy.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.justb81.watchbuddy.R

/**
 * TV-friendly dialog that lets the user pick which connected phones/accounts
 * should receive a manual mark-watched (or unmark) action.
 *
 * Decoupled from any ViewModel — all state is passed in and decisions are
 * returned via [onConfirm].
 *
 * @param connectedUsers     List of all currently discovered phones.
 * @param initialSelection   Pre-selected user IDs (typically all of them).
 * @param onConfirm          Called with the confirmed set of IDs and whether the
 *                           user wants to skip the dialog in future.
 * @param onDismiss          Called when the user cancels without confirming.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun UserScopePickerDialog(
    connectedUsers: List<ConnectedUser>,
    initialSelection: Set<String>,
    onConfirm: (selectedIds: Set<String>, dontAskAgain: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember(initialSelection) { mutableStateOf(initialSelection) }
    var dontAskAgain by remember { mutableStateOf(false) }
    val allSelected = connectedUsers.isNotEmpty() && selected.containsAll(connectedUsers.map { it.id })

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                .padding(24.dp)
                .width(360.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.tv_scope_title),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                ScopePickerSelectAllRow(allSelected) { checked ->
                    selected = if (checked) connectedUsers.map { it.id }.toSet() else emptySet()
                }
                ScopePickerUserList(connectedUsers, selected) { id, checked ->
                    selected = if (checked) selected + id else selected - id
                }
                Spacer(Modifier.height(4.dp))
                ScopePickerDontAskRow(dontAskAgain) { dontAskAgain = it }
                ScopePickerActionRow(
                    isConfirmEnabled = selected.isNotEmpty(),
                    onDismiss = onDismiss,
                    onConfirm = { onConfirm(selected, dontAskAgain) },
                )
            }
        }
    }
}

@Composable
private fun ScopePickerSelectAllRow(allSelected: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Checkbox(
            checked = allSelected,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(8.dp))
        Text(text = stringResource(R.string.tv_scope_all_users), fontSize = 16.sp, color = Color.White)
    }
}

@Composable
private fun ScopePickerUserList(
    connectedUsers: List<ConnectedUser>,
    selected: Set<String>,
    onToggle: (id: String, checked: Boolean) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        items(connectedUsers, key = { it.id }) { user ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Checkbox(
                    checked = selected.contains(user.id),
                    onCheckedChange = { checked -> onToggle(user.id, checked) },
                    colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary),
                )
                Spacer(Modifier.width(8.dp))
                Text(text = user.displayName, fontSize = 14.sp, color = Color.White.copy(alpha = 0.87f))
            }
        }
    }
}

@Composable
private fun ScopePickerDontAskRow(dontAskAgain: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Checkbox(
            checked = dontAskAgain,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.secondary),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.tv_scope_dont_ask_again),
            fontSize = 13.sp,
            color = Color.White.copy(alpha = 0.6f),
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ScopePickerActionRow(
    isConfirmEnabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
            androidx.compose.material3.Text(stringResource(R.string.tv_scope_cancel))
        }
        Button(
            onClick = onConfirm,
            enabled = isConfirmEnabled,
            modifier = Modifier.weight(1f),
        ) {
            Text(stringResource(R.string.tv_scope_apply))
        }
    }
}
