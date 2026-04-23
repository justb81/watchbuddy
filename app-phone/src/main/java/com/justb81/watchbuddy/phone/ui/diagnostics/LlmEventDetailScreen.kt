package com.justb81.watchbuddy.phone.ui.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.phone.llm.LlmEventLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LlmEventDetailScreen(
    onBack: () -> Unit,
    viewModel: LlmEventDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diagnostics_llm_detail_title)) },
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
            val event = uiState.event
            if (event == null) {
                Text(
                    text = stringResource(R.string.diagnostics_llm_detail_not_found),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    textAlign = TextAlign.Center,
                )
            } else {
                MetadataCard(event)
                SectionLabel(stringResource(R.string.diagnostics_llm_detail_prompt))
                TextBlockCard(event.prompt)
                when (event.status) {
                    LlmEventLog.Status.SUCCESS -> {
                        SectionLabel(stringResource(R.string.diagnostics_llm_detail_response))
                        TextBlockCard(event.response ?: "")
                    }
                    LlmEventLog.Status.EMPTY -> {
                        SectionLabel(stringResource(R.string.diagnostics_llm_detail_response))
                        TextBlockCard(
                            event.errorSummary
                                ?: stringResource(R.string.diagnostics_llm_detail_empty_response),
                        )
                    }
                    LlmEventLog.Status.ERROR -> {
                        SectionLabel(stringResource(R.string.diagnostics_llm_detail_error))
                        TextBlockCard(event.errorSummary ?: "")
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun MetadataCard(event: LlmEventLog.LlmEvent) {
    val callerLabel = when (event.caller) {
        "recap" -> stringResource(R.string.diagnostics_llm_caller_recap)
        "extract" -> stringResource(R.string.diagnostics_llm_caller_extract)
        else -> event.caller
    }
    val statusLabel = when (event.status) {
        LlmEventLog.Status.SUCCESS -> stringResource(R.string.diagnostics_llm_status_success)
        LlmEventLog.Status.EMPTY -> stringResource(R.string.diagnostics_llm_status_empty)
        LlmEventLog.Status.ERROR -> stringResource(R.string.diagnostics_llm_status_error)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MetadataLine(stringResource(R.string.diagnostics_llm_detail_caller_label), callerLabel)
            MetadataLine(stringResource(R.string.diagnostics_llm_detail_backend_label), event.backend)
            MetadataLine(
                stringResource(R.string.diagnostics_llm_detail_timestamp_label),
                formatInstant(event.startedAtMs),
            )
            MetadataLine(
                stringResource(R.string.diagnostics_llm_detail_duration_label),
                "${event.durationMs} ms",
            )
            MetadataLine(stringResource(R.string.diagnostics_llm_detail_status_label), statusLabel)
        }
    }
}

@Composable
private fun MetadataLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        )
    }
}

@Composable
private fun SectionLabel(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp, start = 4.dp),
    )
}

@Composable
private fun TextBlockCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        SelectionContainer {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

private fun formatInstant(ms: Long): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    return fmt.format(Date(ms))
}
