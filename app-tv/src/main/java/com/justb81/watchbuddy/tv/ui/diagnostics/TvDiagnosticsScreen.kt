package com.justb81.watchbuddy.tv.ui.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.*
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import com.justb81.watchbuddy.tv.data.CatalogSource
import com.justb81.watchbuddy.tv.data.JustWatchOutcomeEvent
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
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .diagnosticsFocusable(),
                        shape = RoundedCornerShape(12.dp),
                        colors = SurfaceDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
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
                ProviderCatalogSection(
                    version = uiState.catalogVersion,
                    fetchedAtMs = uiState.catalogFetchedAtMs,
                    source = uiState.catalogSource,
                )
            }

            item {
                StreamingDeepLinksSection(
                    cachedCount = uiState.cachedDeepLinkCount,
                    negativeCount = uiState.negativeDeepLinkCount,
                    lastFetchMs = uiState.lastDeepLinkFetchMs,
                    lastError = uiState.lastJustWatchError,
                    searchMisses24h = uiState.justWatchSearchMisses24h,
                    recentOutcomes = uiState.recentJustWatchOutcomes,
                    onClearCache = { viewModel.clearJustWatchCache() },
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
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .diagnosticsFocusable(),
                        shape = RoundedCornerShape(12.dp),
                        colors = SurfaceDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
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
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .diagnosticsFocusable(),
        shape = RoundedCornerShape(12.dp),
        colors = SurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = phone.capability?.userName
                    ?: phone.serviceName,
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
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .diagnosticsFocusable(),
        shape = RoundedCornerShape(12.dp),
        colors = SurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
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
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .diagnosticsFocusable(),
        shape = RoundedCornerShape(12.dp),
        colors = SurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
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
private fun Modifier.diagnosticsFocusable(): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val borderColor = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent
    return this
        .border(2.dp, borderColor, RoundedCornerShape(12.dp))
        .focusable(interactionSource = interaction)
}

private val ActionHintColor = Color(0xFF90CAF9)

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

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StreamingDeepLinksSection(
    cachedCount: Int,
    negativeCount: Int,
    lastFetchMs: Long,
    lastError: String?,
    searchMisses24h: Int,
    recentOutcomes: List<JustWatchOutcomeEvent>,
    onClearCache: () -> Unit,
) {
    Text(
        text = stringResource(R.string.tv_diagnostics_section_streaming_links).uppercase(),
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White.copy(alpha = 0.7f),
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp, start = 4.dp),
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
        scale = CardDefaults.scale(focusedScale = 1.02f),
        onClick = onClearCache,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            listOf(
                DiagRow(stringResource(R.string.tv_diagnostics_row_cached_urls), cachedCount.toString(), Status.NEUTRAL),
                DiagRow(stringResource(R.string.tv_diagnostics_row_negative_entries), negativeCount.toString(), Status.NEUTRAL),
                DiagRow(stringResource(R.string.tv_diagnostics_row_last_fetch), formatAge(lastFetchMs), Status.NEUTRAL),
                DiagRow(
                    label = stringResource(R.string.tv_diagnostics_row_jw_search_misses),
                    value = searchMisses24h.toString(),
                    status = if (searchMisses24h > 0) Status.WARN else Status.NEUTRAL,
                ),
                DiagRow(
                    label = stringResource(R.string.tv_diagnostics_row_jw_last_error),
                    value = lastError ?: "—",
                    status = if (lastError != null) Status.FAIL else Status.NEUTRAL,
                ),
            ).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f),
                    ) {
                        Box(Modifier.size(10.dp).background(statusColor(row.status), RoundedCornerShape(2.dp)))
                        Spacer(Modifier.width(12.dp))
                        Text(row.label, fontSize = 14.sp, color = Color.White)
                    }
                    Text(
                        row.value,
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (recentOutcomes.isNotEmpty()) {
                RecentOutcomesBlock(recentOutcomes)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.tv_diagnostics_action_clear_streaming_cache),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = ActionHintColor,
            )
        }
    }
}

@Composable
private fun RecentOutcomesBlock(recentOutcomes: List<JustWatchOutcomeEvent>) {
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.tv_diagnostics_row_jw_recent_outcomes),
        fontSize = 12.sp,
        color = Color.White.copy(alpha = 0.5f),
        modifier = Modifier.padding(top = 4.dp),
    )
    recentOutcomes.asReversed().forEach { event ->
        val outcomeStatus = when (event.outcome) {
            JustWatchOutcomeEvent.Outcome.EPISODE_CACHE_HIT,
            JustWatchOutcomeEvent.Outcome.EPISODE_API_HIT,
            JustWatchOutcomeEvent.Outcome.SHOW_CACHE_HIT,
            JustWatchOutcomeEvent.Outcome.SHOW_API_HIT -> Status.OK
            JustWatchOutcomeEvent.Outcome.SEARCH_MISS,
            JustWatchOutcomeEvent.Outcome.TECHNICAL_NAME_UNMAPPED,
            JustWatchOutcomeEvent.Outcome.EPISODE_NOT_IN_RESULTS -> Status.WARN
            JustWatchOutcomeEvent.Outcome.HTTP_ERROR,
            JustWatchOutcomeEvent.Outcome.GRAPHQL_ERROR -> Status.FAIL
        }
        val label = event.outcome.name.lowercase().replace('_', ' ')
        val value = buildString {
            append("tmdb=${event.tmdbShowId}")
            if (event.providerId >= 0) append(" p=${event.providerId}")
            if (event.detail.isNotBlank()) append(" (${event.detail})")
            append(" · ${formatAge(event.timestampMs)}")
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                Box(Modifier.size(8.dp).background(statusColor(outcomeStatus), RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(8.dp))
                Text(label, fontSize = 12.sp, color = Color.White)
            }
            Text(
                value,
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ProviderCatalogSection(
    version: Int,
    fetchedAtMs: Long,
    source: CatalogSource,
) {
    val sourceLabel = when (source) {
        CatalogSource.LIVE -> stringResource(R.string.tv_diagnostics_catalog_source_live)
        CatalogSource.BUNDLED -> stringResource(R.string.tv_diagnostics_catalog_source_bundled)
    }
    val sourceStatus = when (source) {
        CatalogSource.LIVE -> Status.OK
        CatalogSource.BUNDLED -> Status.WARN
    }
    DiagnosticsSection(
        title = stringResource(R.string.tv_diagnostics_section_provider_catalog),
        rows = listOf(
            DiagRow(
                label = stringResource(R.string.tv_diagnostics_row_catalog_version),
                value = if (version > 0) version.toString() else "—",
                status = if (version > 0) Status.OK else Status.NEUTRAL,
            ),
            DiagRow(
                label = stringResource(R.string.tv_diagnostics_row_catalog_last_fetched),
                value = formatAge(fetchedAtMs),
                status = Status.NEUTRAL,
            ),
            DiagRow(
                label = stringResource(R.string.tv_diagnostics_row_catalog_source),
                value = sourceLabel,
                status = sourceStatus,
            ),
        ),
    )
}

