package com.justb81.watchbuddy.tv.ui.userselect

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.*
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.core.model.DeviceCapability
import com.justb81.watchbuddy.core.model.LlmBackend

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun UserSelectScreen(
    onConfirm: (Set<String>) -> Unit,
    onBack: () -> Unit,
    viewModel: UserSelectViewModel = hiltViewModel()
) {
    val uiState    by viewModel.uiState.collectAsState()
    val selectedIds by viewModel.selectedIds.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(32.dp),
            modifier = Modifier.padding(48.dp)
        ) {
            Text(
                text       = stringResource(R.string.tv_select_user),
                fontSize   = 36.sp,
                fontWeight = FontWeight.Bold,
                color      = Color.White
            )

            Text(
                text     = stringResource(R.string.tv_select_user_subtitle),
                fontSize = 16.sp,
                color    = Color.White.copy(alpha = 0.6f)
            )

            if (uiState.availableUsers.isEmpty()) {
                Text(
                    text     = stringResource(R.string.tv_no_phone),
                    fontSize = 16.sp,
                    color    = Color.White.copy(alpha = 0.4f)
                )
            } else {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    items(uiState.availableUsers) { user ->
                        UserAvatar(
                            user       = user,
                            isSelected = selectedIds.contains(user.deviceId),
                            onClick    = { viewModel.toggleUser(user.deviceId) }
                        )
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedButton(onClick = onBack) {
                    Text(stringResource(R.string.tv_cancel))
                }

                Button(
                    onClick  = { onConfirm(selectedIds) },
                    enabled  = selectedIds.isNotEmpty()
                ) {
                    Text(
                        if (selectedIds.size > 1)
                            stringResource(R.string.tv_watching_together)
                        else
                            stringResource(R.string.tv_confirm_selection)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun UserAvatar(
    user: DeviceCapability,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) Color(0xFFE53935) else Color.Transparent
    val initials    = user.userName.take(2).uppercase()

    Card(
        onClick  = onClick,
        modifier = Modifier
            .size(120.dp)
            .border(3.dp, borderColor, RoundedCornerShape(16.dp)),
        shape    = CardDefaults.shape(RoundedCornerShape(16.dp)),
        colors   = CardDefaults.colors(
            containerColor        = Color(0xFF1C1C1E),
            focusedContainerColor = Color(0xFF2C2C2E),
        ),
        scale    = CardDefaults.scale(focusedScale = 1.08f)
    ) {
        Column(
            modifier            = Modifier.fillMaxSize().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Avatar circle with initials
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(
                        if (isSelected) Color(0xFFE53935)
                        else Color(0xFF3A3A3C)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text       = initials,
                    fontSize   = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color      = Color.White
                )
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text      = user.userName,
                fontSize  = 12.sp,
                color     = Color.White,
                maxLines  = 1
            )

            // LLM quality indicator
            Text(
                text     = llmLabel(user.llmBackend, stringResource(R.string.tv_llm_none)),
                fontSize = 10.sp,
                color    = llmColor(user.llmBackend)
            )
        }
    }
}

private fun llmLabel(backend: LlmBackend, noneLlmLabel: String) = when (backend) {
    LlmBackend.AICORE        -> "AICore"
    LlmBackend.MEDIAPIPE_GPU -> "Gemma GPU"
    LlmBackend.MEDIAPIPE_CPU -> "Gemma CPU"
    LlmBackend.NONE          -> noneLlmLabel
}

private fun llmColor(backend: LlmBackend) = when (backend) {
    LlmBackend.AICORE        -> Color(0xFF4CAF50)
    LlmBackend.MEDIAPIPE_GPU -> Color(0xFF2196F3)
    LlmBackend.MEDIAPIPE_CPU -> Color(0xFFFF9800)
    LlmBackend.NONE          -> Color(0xFF757575)
}
