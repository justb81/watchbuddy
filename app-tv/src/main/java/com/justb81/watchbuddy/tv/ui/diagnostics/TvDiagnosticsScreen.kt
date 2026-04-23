package com.justb81.watchbuddy.tv.ui.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.tv.material3.*
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import com.justb81.watchbuddy.core.scrobbler.MediaSessionScrobbler
import com.justb81.watchbuddy.tv.discovery.PhoneDiscoveryManager

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvDiagnosticsScreen(
    onBack: () -> Unit,
    viewModel: TvDiagnosticsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshNotificationAccess()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.tv_diagnostics_title),
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Spacer(Modifier.height(16.dp))
            }

            item {
                DiagnosticsSection(
                    title = stringResource(R.string.tv_diagnostics_section_discovery),
                    rows = listOfNotNull(
                        DiagRow(
                            stringResource(R.string.tv_diagnostics_row_discovery_active),
                            yesNoStr(uiState.discoveryActive),
                            if (uiState.discoveryActive) Status.OK else Status.NEUTRAL,
                        ),
                        DiagRow(
                            stringResource(R.string.tv_diagnostics_row_last_heartbeat),
                            formatAge(uiState.lastHeartbeatMs),
                            heartbeatStatus(uiState.lastHeartbeatMs, uiState.discoveryActive),
                        ),
                    ),
                )
            }

            item {
                DiagnosticsSection(
                    title = stringResource(R.string.tv_diagnostics_section_ble),
                    rows = listOfNotNull(
                        DiagRow(
                            stringResource(R.string.tv_diagnostics_row_ble_state),
                            uiState.bleScanState.name,
                            when (uiState.bleScanState) {
                                PhoneDiscoveryManager.BleScanState.SCANNING -> Status.OK
                                PhoneDiscoveryManager.BleScanState.FAILED -> Status.FAIL
                                PhoneDiscoveryManager.BleScanState.IDLE -> Status.NEUTRAL
                            },
                        ),
                        uiState.bleScanErrorCode?.let {
                            DiagRow(
                                stringResource(R.string.tv_diagnostics_row_ble_error),
                                it.toString(),
                                Status.FAIL,
                            )
                        },
                    ),
                )
            }

            item {
                DiagnosticsSection(
                    title = stringResource(R.string.tv_diagnostics_section_scrobble),
                    rows = listOf(
                        DiagRow(
                            stringResource(R.string.tv_diagnostics_row_notification_access),
                            yesNoStr(uiState.notificationAccessGranted),
                            if (uiState.notificationAccessGranted) Status.OK else Status.FAIL,
                        ),
                        DiagRow(
                            stringResource(R.string.tv_diagnostics_row_scrobbler_listening),
                            yesNoStr(uiState.scrobblerListening),
                            when {
                                uiState.scrobblerListening -> Status.OK
                                uiState.notificationAccessGranted -> Status.WARN
                                else -> Status.NEUTRAL
                            },
                        ),
                        DiagRow(
                            stringResource(R.string.tv_diagnostics_row_last_candidate),
                            formatLastCandidate(uiState.lastCandidate),
                            if (uiState.lastCandidate != null) Status.OK else Status.NEUTRAL,
                        ),
                    ),
                )
            }

            item {
                Text(
                    text = stringResource(R.string.tv_diagnostics_section_phones).uppercase(),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp, start = 4.dp),
                )
            }

            if (uiState.phones.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
                        onClick = {},
                    ) {
                        Text(
                            text = stringResource(R.string.tv_diagnostics_value_no_phones),
                            color = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            } else {
                items(uiState.phones, key = { it.baseUrl }) { phone ->
                    PhoneDiagnosticsCard(phone)
                }
            }

            item {
                DiagnosticsSection(
                    title = stringResource(R.string.tv_diagnostics_section_build),
                    rows = listOf(
                        DiagRow(
                            stringResource(R.string.tv_diagnostics_row_version_name),
                            uiState.versionName,
                            Status.NEUTRAL,
                        ),
                        DiagRow(
                            stringResource(R.string.tv_diagnostics_row_version_code),
                            uiState.versionCode.toString(),
                            Status.NEUTRAL,
                        ),
                    ),
                )
            }

            item {
                Text(
                    text = stringResource(R.string.tv_diagnostics_section_recent_events).uppercase(),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp, start = 4.dp),
                )
            }

            if (uiState.recentEvents.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
                        onClick = {},
                    ) {
                        Text(
                            text = stringResource(R.string.tv_diagnostics_value_no_events),
                            color = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            } else {
                items(uiState.recentEvents) { entry ->
                    RecentEventRow(entry)
                }
            }

            item {
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = onBack) {
                    Text(stringResource(R.string.tv_back))
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PhoneDiagnosticsCard(phone: PhoneDiscoveryManager.DiscoveredPhone) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
        onClick = {},
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = phone.capability?.userName
                    ?: phone.serviceInfo.serviceName
                    ?: phone.baseUrl,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = phone.baseUrl,
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.5f),
            )
            Spacer(Modifier.height(8.dp))
            PhoneRow(stringResource(R.string.tv_diagnostics_row_phone_score), phone.score.toString())
            phone.txtRecord?.let { txt ->
                PhoneRow(
                    stringResource(R.string.tv_diagnostics_row_phone_quality),
                    txt.modelQuality.toString(),
                )
                PhoneRow(
                    stringResource(R.string.tv_diagnostics_row_phone_backend),
                    txt.llmBackend.name,
                )
            }
            PhoneRow(
                stringResource(R.string.tv_diagnostics_row_phone_fail_count),
                phone.failCount.toString(),
            )
            PhoneRow(
                stringResource(R.string.tv_diagnostics_row_rssi),
                phone.rssi?.let { "$it dBm" } ?: "—",
            )
            PhoneRow(
                stringResource(R.string.tv_diagnostics_row_phone_last_success),
                formatAge(phone.lastSuccessfulCheck),
            )
        }
    }
}

@Composable
private fun PhoneRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f))
        Text(value, fontSize = 12.sp, color = Color.White)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RecentEventRow(entry: DiagnosticLog.Entry) {
    val status = when (entry.level) {
        DiagnosticLog.Level.DEBUG -> Status.NEUTRAL
        DiagnosticLog.Level.INFO -> Status.OK
        DiagnosticLog.Level.WARN -> Status.WARN
        DiagnosticLog.Level.ERROR -> Status.FAIL
    }
    val message = entry.throwableSummary?.let { "${entry.message} → $it" } ?: entry.message
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
        onClick = {},
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(statusColor(status), RoundedCornerShape(2.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "${formatAge(entry.timestampMs)} · ${entry.tag}",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.6f),
                )
                Text(
                    text = message,
                    fontSize = 13.sp,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private data class DiagRow(val label: String, val value: String, val status: Status)

private enum class Status { OK, WARN, FAIL, NEUTRAL }

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DiagnosticsSection(title: String, rows: List<DiagRow>) {
    Text(
        text = title.uppercase(),
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White.copy(alpha = 0.7f),
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp, start = 4.dp),
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
        onClick = {},
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            rows.forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(statusColor(row.status), RoundedCornerShape(2.dp)),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(row.label, fontSize = 14.sp, color = Color.White)
                    }
                    Text(
                        row.value,
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

@Composable
private fun statusColor(status: Status): Color = when (status) {
    Status.OK -> Color(0xFF2E7D32)
    Status.WARN -> Color(0xFFF9A825)
    Status.FAIL -> Color(0xFFC62828)
    Status.NEUTRAL -> Color.White.copy(alpha = 0.3f)
}

@Composable
private fun yesNoStr(value: Boolean): String =
    if (value) stringResource(R.string.tv_diagnostics_value_yes)
    else stringResource(R.string.tv_diagnostics_value_no)

private fun heartbeatStatus(tickMs: Long, discoveryActive: Boolean): Status {
    if (!discoveryActive) return Status.NEUTRAL
    if (tickMs == 0L) return Status.WARN
    val ageMs = System.currentTimeMillis() - tickMs
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

private fun formatLastCandidate(last: MediaSessionScrobbler.LastCandidate?): String {
    if (last == null) return "—"
    val title = last.candidate.matchedShow?.title ?: last.candidate.mediaTitle
    val pct = (last.candidate.confidence * 100).toInt()
    val marker = if (last.autoScrobbled) "auto" else "overlay"
    return "$title @ ${pct}% · $marker · ${formatAge(last.observedAtMs)}"
}
