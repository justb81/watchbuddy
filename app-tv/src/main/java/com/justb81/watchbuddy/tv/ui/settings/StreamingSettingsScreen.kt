package com.justb81.watchbuddy.tv.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.*
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.core.model.StreamingService
import com.justb81.watchbuddy.tv.ui.theme.extendedColors

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun StreamingSettingsScreen(
    onBack: () -> Unit,
    onDiagnosticsClick: () -> Unit = {},
    viewModel: StreamingSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp, vertical = 32.dp)
        ) {
            // Header
            Text(
                text       = stringResource(R.string.tv_streaming_settings_title),
                fontSize   = 32.sp,
                fontWeight = FontWeight.Bold,
                color      = Color.White
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text     = stringResource(R.string.tv_streaming_settings_subtitle),
                fontSize = 14.sp,
                color    = Color.White.copy(alpha = 0.5f)
            )

            Spacer(Modifier.height(24.dp))

            // Service list
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                itemsIndexed(
                    uiState.services,
                    key = { _, service -> service.id }
                ) { _, service ->
                    val isSubscribed = service.id in uiState.subscribedIds
                    val priority = uiState.orderedIds.indexOf(service.id)

                    ServiceRow(
                        service      = service,
                        isSubscribed = isSubscribed,
                        priority     = if (isSubscribed) priority + 1 else null,
                        canMoveUp    = isSubscribed && priority > 0,
                        canMoveDown  = isSubscribed && priority < uiState.orderedIds.lastIndex,
                        onToggle     = { viewModel.toggleService(service.id) },
                        onMoveUp     = { viewModel.moveServiceUp(service.id) },
                        onMoveDown   = { viewModel.moveServiceDown(service.id) }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Footer actions
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedButton(onClick = onBack) {
                    Text(stringResource(R.string.tv_back))
                }
                OutlinedButton(onClick = onDiagnosticsClick) {
                    Text(stringResource(R.string.tv_diagnostics_title))
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ServiceRow(
    service: StreamingService,
    isSubscribed: Boolean,
    priority: Int?,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggle: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    val borderColor = if (isSubscribed) MaterialTheme.colorScheme.primary else Color.Transparent

    Card(
        onClick  = onToggle,
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, borderColor, RoundedCornerShape(12.dp)),
        shape    = CardDefaults.shape(RoundedCornerShape(12.dp)),
        colors   = CardDefaults.colors(
            containerColor        = if (isSubscribed) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.background,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        scale    = CardDefaults.scale(focusedScale = 1.02f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Priority number or checkbox indicator
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(
                            if (isSubscribed) MaterialTheme.colorScheme.primary else MaterialTheme.extendedColors.outline,
                            RoundedCornerShape(8.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text       = if (priority != null) priority.toString() else "",
                        fontSize   = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color      = Color.White
                    )
                }

                Column {
                    Text(
                        text       = service.name,
                        fontSize   = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color      = Color.White
                    )
                    Text(
                        text     = service.packageName,
                        fontSize = 11.sp,
                        color    = Color.White.copy(alpha = 0.3f)
                    )
                }
            }

            // Priority reorder buttons (only for subscribed services)
            if (isSubscribed) {
                val moveUpDesc   = stringResource(R.string.cd_service_move_up, service.name)
                val moveDownDesc = stringResource(R.string.cd_service_move_down, service.name)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onMoveUp,
                        enabled = canMoveUp,
                        modifier = Modifier
                            .size(40.dp)
                            .semantics { contentDescription = moveUpDesc },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("\u25B2", fontSize = 14.sp)
                    }
                    OutlinedButton(
                        onClick = onMoveDown,
                        enabled = canMoveDown,
                        modifier = Modifier
                            .size(40.dp)
                            .semantics { contentDescription = moveDownDesc },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("\u25BC", fontSize = 14.sp)
                    }
                }
            }
        }
    }
}
