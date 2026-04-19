package com.justb81.watchbuddy.tv.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.*
import com.justb81.watchbuddy.R

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvSettingsScreen(
    onBack: () -> Unit,
    onStreamingServicesClick: () -> Unit,
    onDiagnosticsClick: () -> Unit,
    viewModel: TvSettingsViewModel = hiltViewModel()
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
            Text(
                text = stringResource(R.string.tv_settings_title),
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )

            Spacer(Modifier.height(32.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsToggleRow(
                    title = stringResource(R.string.tv_settings_phone_discovery_title),
                    subtitle = stringResource(R.string.tv_settings_phone_discovery_subtitle),
                    checked = uiState.isPhoneDiscoveryEnabled,
                    onCheckedChange = viewModel::setPhoneDiscoveryEnabled,
                )
                SettingsToggleRow(
                    title = stringResource(R.string.tv_settings_autostart_title),
                    subtitle = stringResource(R.string.tv_settings_autostart_subtitle),
                    checked = uiState.isAutostartEnabled,
                    onCheckedChange = viewModel::setAutostartEnabled,
                )
                SettingsNavigationRow(
                    title = stringResource(R.string.tv_settings_streaming_services),
                    onClick = onStreamingServicesClick,
                )
                SettingsNavigationRow(
                    title = stringResource(R.string.tv_settings_diagnostics),
                    onClick = onDiagnosticsClick,
                )
            }

            Spacer(Modifier.weight(1f))

            OutlinedButton(onClick = onBack) {
                Text(stringResource(R.string.tv_back))
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        onClick = { onCheckedChange(!checked) },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.3f),
            focusedContainerColor = MaterialTheme.colorScheme.surface,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                )
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.5f),
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = null,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingsNavigationRow(
    title: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.3f),
            focusedContainerColor = MaterialTheme.colorScheme.surface,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
            )
            Text(
                text = "›",
                fontSize = 20.sp,
                color = Color.White.copy(alpha = 0.5f),
            )
        }
    }
}
