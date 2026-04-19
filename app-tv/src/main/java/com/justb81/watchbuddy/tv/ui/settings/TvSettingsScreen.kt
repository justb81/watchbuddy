package com.justb81.watchbuddy.tv.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.justb81.watchbuddy.BuildConfig
import com.justb81.watchbuddy.R

private sealed interface SettingsRow {
    data class Toggle(
        val key: String,
        val title: String,
        val subtitle: String,
        val enabled: Boolean,
        val onChange: (Boolean) -> Unit,
    ) : SettingsRow

    data class Navigate(
        val key: String,
        val title: String,
        val subtitle: String,
        val onClick: () -> Unit,
    ) : SettingsRow
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvSettingsScreen(
    onBack: () -> Unit,
    onStreamingServicesClick: () -> Unit,
    onDiagnosticsClick: () -> Unit,
    viewModel: TvSettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    val rows: List<SettingsRow> = listOf(
        SettingsRow.Toggle(
            key = "phone_discovery",
            title = stringResource(R.string.tv_settings_phone_discovery_title),
            subtitle = stringResource(R.string.tv_settings_phone_discovery_subtitle),
            enabled = uiState.isPhoneDiscoveryEnabled,
            onChange = viewModel::setPhoneDiscoveryEnabled,
        ),
        SettingsRow.Toggle(
            key = "autostart",
            title = stringResource(R.string.tv_settings_autostart_title),
            subtitle = stringResource(R.string.tv_settings_autostart_subtitle),
            enabled = uiState.isAutostartEnabled,
            onChange = viewModel::setAutostartEnabled,
        ),
        SettingsRow.Navigate(
            key = "streaming_services",
            title = stringResource(R.string.tv_settings_streaming_services),
            subtitle = stringResource(R.string.tv_streaming_settings_subtitle),
            onClick = onStreamingServicesClick,
        ),
        SettingsRow.Navigate(
            key = "diagnostics",
            title = stringResource(R.string.tv_settings_diagnostics),
            subtitle = stringResource(R.string.tv_diagnostics_title),
            onClick = onDiagnosticsClick,
        ),
    )

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

            Spacer(Modifier.height(24.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(rows, key = { it.key() }) { row ->
                    when (row) {
                        is SettingsRow.Toggle -> ToggleRow(row)
                        is SettingsRow.Navigate -> NavigateRow(row)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = stringResource(
                    R.string.settings_version_footer,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE,
                ),
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.padding(bottom = 8.dp),
            )

            OutlinedButton(onClick = onBack) {
                Text(stringResource(R.string.tv_back))
            }
        }
    }
}

private fun SettingsRow.key(): String = when (this) {
    is SettingsRow.Toggle -> this.key
    is SettingsRow.Navigate -> this.key
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ToggleRow(row: SettingsRow.Toggle) {
    SettingsCard(
        onClick = { row.onChange(!row.enabled) },
        highlighted = row.enabled,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                )
                Text(
                    text = row.subtitle,
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.6f),
                )
            }

            TogglePill(enabled = row.enabled)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NavigateRow(row: SettingsRow.Navigate) {
    SettingsCard(
        onClick = row.onClick,
        highlighted = false,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                )
                Text(
                    text = row.subtitle,
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.6f),
                )
            }
            Text(
                text = "\u203A",
                fontSize = 22.sp,
                color = Color.White.copy(alpha = 0.6f),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingsCard(
    onClick: () -> Unit,
    highlighted: Boolean,
    content: @Composable () -> Unit,
) {
    val borderColor = if (highlighted) MaterialTheme.colorScheme.primary else Color.Transparent
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, borderColor, RoundedCornerShape(12.dp)),
        shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
        colors = CardDefaults.colors(
            containerColor = if (highlighted) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.background,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        scale = CardDefaults.scale(focusedScale = 1.02f),
    ) {
        content()
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TogglePill(enabled: Boolean) {
    val bg = if (enabled) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.15f)
    Box(
        modifier = Modifier
            .size(width = 56.dp, height = 28.dp)
            .background(bg, RoundedCornerShape(14.dp)),
        contentAlignment = if (enabled) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .size(20.dp)
                .background(Color.White, RoundedCornerShape(10.dp)),
        )
    }
}
