package com.justb81.watchbuddy.tv.ui.userselect

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.*
import coil.compose.SubcomposeAsyncImage
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.core.model.DeviceCapability
import com.justb81.watchbuddy.core.model.LlmBackend
import com.justb81.watchbuddy.tv.ui.theme.extendedColors

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun UserSelectScreen(
    onConfirm: () -> Unit,
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
                    onClick  = {
                        viewModel.persistSelection()
                        onConfirm()
                    },
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
    val borderColor   = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
    val initials      = user.userName.take(2).uppercase()
    val avatarDescription = stringResource(
        if (isSelected) R.string.cd_user_selected else R.string.cd_user_unselected,
        user.userName
    )

    Card(
        onClick  = onClick,
        modifier = Modifier
            .size(120.dp)
            .border(3.dp, borderColor, RoundedCornerShape(16.dp))
            .semantics { contentDescription = avatarDescription },
        shape    = CardDefaults.shape(RoundedCornerShape(16.dp)),
        colors   = CardDefaults.colors(
            containerColor        = MaterialTheme.colorScheme.surface,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        scale    = CardDefaults.scale(focusedScale = 1.08f)
    ) {
        Column(
            modifier            = Modifier.fillMaxSize().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Avatar: Trakt profile image with initials fallback
            val avatarBackground = if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.extendedColors.outline
            if (user.userAvatarUrl != null) {
                SubcomposeAsyncImage(
                    model = user.userAvatarUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape),
                    loading = { InitialsCircle(initials, avatarBackground) },
                    error = { InitialsCircle(initials, avatarBackground) }
                )
            } else {
                InitialsCircle(initials, avatarBackground)
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

@Composable
private fun InitialsCircle(initials: String, backgroundColor: Color) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initials,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}

private fun llmLabel(backend: LlmBackend, noneLlmLabel: String) = when (backend) {
    LlmBackend.AICORE -> "AICore"
    LlmBackend.LITERT -> "Gemma"
    LlmBackend.NONE   -> noneLlmLabel
}

@Composable
private fun llmColor(backend: LlmBackend) = when (backend) {
    LlmBackend.AICORE -> MaterialTheme.extendedColors.llmAiCore
    LlmBackend.LITERT -> MaterialTheme.extendedColors.llmGpu
    LlmBackend.NONE   -> MaterialTheme.extendedColors.llmNone
}
