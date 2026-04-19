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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.*
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.tv.discovery.PhoneDiscoveryManager

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvDiagnosticsScreen(
    onBack: () -> Unit,
    viewModel: TvDiagnosticsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

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
                    title = stringResource(R.string.tv_diagnostics_section_connectivity),
                    rows = listOf(
                        DiagRow(
                            stringResource(R.string.tv_diagnostics_row_multicast_lock),
                            yesNoStr(uiState.multicastLockHeld),
                            if (uiState.multicastLockHeld) Status.OK
                            else if (uiState.discoveryActive) Status.FAIL else Status.NEUTRAL,
                        ),
                    ),
                )
            }

            item {
                DiagnosticsSection(
                    title = stringResource(R.string.tv_diagnostics_section_nsd),
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
