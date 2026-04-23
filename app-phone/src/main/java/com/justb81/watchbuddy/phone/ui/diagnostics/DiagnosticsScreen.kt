package com.justb81.watchbuddy.phone.ui.diagnostics

import android.content.Context
import androidx.compose.foundation.clickable
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import com.justb81.watchbuddy.core.logging.DiagnosticShare
import com.justb81.watchbuddy.phone.llm.LlmEventLog
import com.justb81.watchbuddy.service.CompanionStateManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    onLlmEventClick: (Long) -> Unit = {},
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
            ConnectivitySection(uiState)
            HttpSection(uiState)
            BleSection(uiState)
            ScrobbleSection(uiState)
            LlmActivitySection(uiState, onLlmEventClick)
            BuildInfoSection(uiState)
            Spacer(Modifier.height(8.dp))
            ShareDiagnosticsButton(context)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ConnectivitySection(uiState: DiagnosticsUiState) {
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
            label = stringResource(R.string.diagnostics_row_service_running),
            value = yesNo(uiState.serviceRunning),
            status = if (uiState.serviceRunning) Status.OK else Status.NEUTRAL,
        )
    }
}

@Composable
private fun HttpSection(uiState: DiagnosticsUiState) {
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
}

@Composable
private fun BleSection(uiState: DiagnosticsUiState) {
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
}

@Composable
private fun ScrobbleSection(uiState: DiagnosticsUiState) {
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
}

@Composable
private fun LlmActivitySection(
    uiState: DiagnosticsUiState,
    onLlmEventClick: (Long) -> Unit,
) {
    DiagnosticsSection(stringResource(R.string.diagnostics_section_llm_activity)) {
        when {
            !uiState.llmActivityLoggingEnabled -> {
                DiagnosticsRow(
                    label = stringResource(R.string.diagnostics_row_llm_activity_disabled),
                    value = "",
                    status = Status.NEUTRAL,
                )
            }
            uiState.llmEvents.isEmpty() -> {
                DiagnosticsRow(
                    label = stringResource(R.string.diagnostics_row_llm_activity_empty),
                    value = "",
                    status = Status.NEUTRAL,
                )
            }
            else -> {
                uiState.llmEvents.forEach { event ->
                    LlmEventRow(event = event, onClick = { onLlmEventClick(event.id) })
                }
            }
        }
    }
}

@Composable
private fun BuildInfoSection(uiState: DiagnosticsUiState) {
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
}

@Composable
private fun ShareDiagnosticsButton(context: Context) {
    Button(
        onClick = {
            // End-marker in the breadcrumb ring so the snapshot always
            // has a clear "user hit share" line and the share-sheet
            // round-trip is visible in the exported log.
            DiagnosticLog.event("DiagnosticsScreen", "Share diagnostics clicked")
            DiagnosticShare.launchShare(context)
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.diagnostics_share_button))
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

@Composable
private fun LlmEventRow(event: LlmEventSummary, onClick: () -> Unit) {
    val statusDot = when (event.status) {
        LlmEventLog.Status.SUCCESS -> Status.OK
        LlmEventLog.Status.EMPTY -> Status.WARN
        LlmEventLog.Status.ERROR -> Status.FAIL
    }
    val callerLabel = when (event.caller) {
        "recap" -> stringResource(R.string.diagnostics_llm_caller_recap)
        "extract" -> stringResource(R.string.diagnostics_llm_caller_extract)
        else -> event.caller
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(10.dp), contentAlignment = Alignment.Center) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = statusColor(statusDot),
                modifier = Modifier.fillMaxSize(),
            ) {}
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "$callerLabel · ${event.backend}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "${formatAge(event.startedAtMs)} · ${event.durationMs} ms",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        Text(
            text = "›",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
        )
    }
}
