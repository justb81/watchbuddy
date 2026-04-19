package com.justb81.watchbuddy.phone.ui.diagnostics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.core.logging.DiagnosticShare
import com.justb81.watchbuddy.service.CompanionStateManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diagnostics_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_cd_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DiagnosticsSection(stringResource(R.string.diagnostics_section_connectivity)) {
                DiagnosticsRow(
                    label = stringResource(R.string.diagnostics_row_wifi),
                    value = yesNo(uiState.isOnWifi),
                    status = if (uiState.isOnWifi) Status.OK else Status.FAIL,
                )
                DiagnosticsRow(
                    label = stringResource(R.string.diagnostics_row_ipv4),
                    value = uiState.wifiIpv4 ?: stringResource(R.string.diagnostics_value_unknown),
                    status = if (uiState.wifiIpv4 != null) Status.OK else Status.WARN,
                )
                DiagnosticsRow(
                    label = stringResource(R.string.diagnostics_row_multicast_lock),
                    value = yesNo(uiState.multicastLockHeld),
                    status = if (uiState.multicastLockHeld) Status.OK
                             else if (uiState.serviceRunning) Status.FAIL else Status.NEUTRAL,
                )
            }

            DiagnosticsSection(stringResource(R.string.diagnostics_section_nsd)) {
                DiagnosticsRow(
                    label = stringResource(R.string.diagnostics_row_service_running),
                    value = yesNo(uiState.serviceRunning),
                    status = if (uiState.serviceRunning) Status.OK else Status.NEUTRAL,
                )
                DiagnosticsRow(
                    label = stringResource(R.string.diagnostics_row_nsd_state),
                    value = uiState.nsdState.name,
                    status = when (uiState.nsdState) {
                        CompanionStateManager.NsdRegistrationState.REGISTERED -> Status.OK
                        CompanionStateManager.NsdRegistrationState.FAILED -> Status.FAIL
                        CompanionStateManager.NsdRegistrationState.IDLE -> Status.NEUTRAL
                        else -> Status.WARN
                    },
                )
                uiState.nsdErrorCode?.let { code ->
                    DiagnosticsRow(
                        label = stringResource(R.string.diagnostics_row_nsd_error),
                        value = code.toString(),
                        status = Status.FAIL,
                    )
                }
            }

            DiagnosticsSection(stringResource(R.string.diagnostics_section_http)) {
                DiagnosticsRow(
                    label = stringResource(R.string.diagnostics_row_http_binding),
                    value = uiState.httpServerBinding ?: stringResource(R.string.diagnostics_value_stopped),
                    status = if (uiState.httpServerBinding != null) Status.OK else Status.NEUTRAL,
                )
                DiagnosticsRow(
                    label = stringResource(R.string.diagnostics_row_last_capability_poll),
                    value = formatAge(uiState.lastCapabilityCheckMs),
                    status = capabilityStatus(uiState.lastCapabilityCheckMs, uiState.serviceRunning),
                )
            }

            DiagnosticsSection(stringResource(R.string.diagnostics_section_ble)) {
                DiagnosticsRow(
                    label = stringResource(R.string.diagnostics_row_ble_state),
                    value = uiState.bleState.name,
                    status = when (uiState.bleState) {
                        CompanionStateManager.BleAdvertiseState.ADVERTISING -> Status.OK
                        CompanionStateManager.BleAdvertiseState.FAILED -> Status.FAIL
                        CompanionStateManager.BleAdvertiseState.IDLE -> Status.NEUTRAL
                    },
                )
                uiState.bleErrorCode?.let { code ->
                    DiagnosticsRow(
                        label = stringResource(R.string.diagnostics_row_ble_error),
                        value = code.toString(),
                        status = Status.FAIL,
                    )
                }
            }

            DiagnosticsSection(stringResource(R.string.diagnostics_section_scrobble)) {
                val scrobble = uiState.lastScrobble
                if (scrobble != null) {
                    DiagnosticsRow(
                        label = stringResource(R.string.diagnostics_row_scrobble_show),
                        value = "${scrobble.show.title} S${scrobble.episode.season}E${scrobble.episode.number}",
                        status = Status.OK,
                    )
                    DiagnosticsRow(
                        label = stringResource(R.string.diagnostics_row_scrobble_progress),
                        value = "%.0f%%".format(scrobble.progress),
                        status = Status.NEUTRAL,
                    )
                    DiagnosticsRow(
                        label = stringResource(R.string.diagnostics_row_scrobble_time),
                        value = formatAge(scrobble.timestamp),
                        status = Status.NEUTRAL,
                    )
                } else {
                    DiagnosticsRow(
                        label = stringResource(R.string.diagnostics_row_scrobble_show),
                        value = stringResource(R.string.diagnostics_value_none),
                        status = Status.NEUTRAL,
                    )
                }
            }

            DiagnosticsSection(stringResource(R.string.diagnostics_section_build)) {
                DiagnosticsRow(
                    label = stringResource(R.string.diagnostics_row_version_name),
                    value = uiState.versionName,
                    status = Status.NEUTRAL,
                )
                DiagnosticsRow(
                    label = stringResource(R.string.diagnostics_row_version_code),
                    value = uiState.versionCode.toString(),
                    status = Status.NEUTRAL,
                )
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { DiagnosticShare.launchShare(context) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.diagnostics_share_button))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun yesNo(value: Boolean): String =
    if (value) stringResource(R.string.diagnostics_value_yes)
    else stringResource(R.string.diagnostics_value_no)

private fun capabilityStatus(lastPollMs: Long, serviceRunning: Boolean): Status {
    if (!serviceRunning) return Status.NEUTRAL
    if (lastPollMs == 0L) return Status.WARN
    val ageMs = System.currentTimeMillis() - lastPollMs
    return when {
        ageMs < 2 * 60_000 -> Status.OK
        ageMs < 5 * 60_000 -> Status.WARN
        else -> Status.FAIL
    }
}

private fun formatAge(timestampMs: Long): String {
    if (timestampMs == 0L) return "—"
    val seconds = (System.currentTimeMillis() - timestampMs) / 1000
    return when {
        seconds < 0 -> "—"
        seconds < 60 -> "${seconds}s ago"
        seconds < 3_600 -> "${seconds / 60}m ago"
        else -> "${seconds / 3_600}h ago"
    }
}

private enum class Status { OK, WARN, FAIL, NEUTRAL }

@Composable
private fun DiagnosticsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp, start = 4.dp),
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(content = content)
    }
}

@Composable
private fun DiagnosticsRow(label: String, value: String, status: Status) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = statusColor(status),
                    modifier = Modifier.fillMaxSize(),
                ) {}
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun statusColor(status: Status): Color = when (status) {
    Status.OK -> Color(0xFF2E7D32)
    Status.WARN -> Color(0xFFF9A825)
    Status.FAIL -> MaterialTheme.colorScheme.error
    Status.NEUTRAL -> MaterialTheme.colorScheme.outline
}
