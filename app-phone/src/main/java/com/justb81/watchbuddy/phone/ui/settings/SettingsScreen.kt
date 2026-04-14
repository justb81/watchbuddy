package com.justb81.watchbuddy.phone.ui.settings

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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.justb81.watchbuddy.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onDisconnected: () -> Unit,
    onConnectClick: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showAdvanced by remember { mutableStateOf(false) }
    var showDisconnectDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val savedMessage = stringResource(R.string.settings_saved)

    LaunchedEffect(uiState.saveSuccess) {
        if (uiState.saveSuccess) {
            snackbarHostState.showSnackbar(savedMessage)
            viewModel.clearSaveSuccess()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_cd_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ── Account Section ───────────────────────────────────────────────
            SettingsSectionHeader(stringResource(R.string.settings_account))

            SettingsCard {
                SettingsRow(
                    label    = stringResource(R.string.settings_trakt_account),
                    value    = uiState.traktUsername ?: stringResource(R.string.settings_not_connected),
                    showDivider = true
                )
                if (uiState.traktUsername != null) {
                    TextButton(
                        onClick  = { showDisconnectDialog = true },
                        modifier = Modifier.padding(horizontal = 8.dp)
                    ) {
                        Text(
                            stringResource(R.string.settings_disconnect),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                } else {
                    TextButton(
                        onClick  = onConnectClick,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    ) {
                        Text(stringResource(R.string.settings_connect_to_trakt))
                    }
                }
            }

            // ── TMDB Section ─────────────────────────────────────────────────
            SettingsSectionHeader(stringResource(R.string.settings_tmdb))

            SettingsCard {
                when {
                    uiState.tmdbConnected -> {
                        // User has saved their own API key
                        SettingsRow(
                            label    = stringResource(R.string.settings_tmdb_account),
                            value    = stringResource(R.string.settings_tmdb_connected),
                            showDivider = true
                        )
                        TextButton(
                            onClick  = { viewModel.disconnectTmdb() },
                            modifier = Modifier.padding(horizontal = 8.dp)
                        ) {
                            Text(
                                stringResource(R.string.settings_disconnect),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    uiState.defaultTmdbApiKeyAvailable -> {
                        // No user key, but a built-in key is available
                        SettingsRow(
                            label    = stringResource(R.string.settings_tmdb_account),
                            value    = stringResource(R.string.settings_tmdb_default_key_active),
                            showDivider = true
                        )
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(
                                text  = stringResource(R.string.settings_tmdb_default_key_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value         = uiState.tmdbApiKey,
                                onValueChange = viewModel::setTmdbApiKey,
                                label         = { Text(stringResource(R.string.settings_tmdb_api_key)) },
                                modifier      = Modifier.fillMaxWidth(),
                                singleLine    = true,
                                visualTransformation = PasswordVisualTransformation()
                            )
                            Spacer(Modifier.height(4.dp))
                            TmdbHelperLink()
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick  = { viewModel.saveTmdbApiKey() },
                                modifier = Modifier.fillMaxWidth(),
                                enabled  = uiState.tmdbApiKey.isNotBlank()
                            ) {
                                Text(stringResource(R.string.settings_save))
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                    else -> {
                        // No key at all — user must enter one
                        SettingsRow(
                            label    = stringResource(R.string.settings_tmdb_account),
                            value    = stringResource(R.string.settings_not_connected),
                            showDivider = true
                        )
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            OutlinedTextField(
                                value         = uiState.tmdbApiKey,
                                onValueChange = viewModel::setTmdbApiKey,
                                label         = { Text(stringResource(R.string.settings_tmdb_api_key)) },
                                modifier      = Modifier.fillMaxWidth(),
                                singleLine    = true,
                                visualTransformation = PasswordVisualTransformation()
                            )
                            Spacer(Modifier.height(4.dp))
                            TmdbHelperLink()
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick  = { viewModel.saveTmdbApiKey() },
                                modifier = Modifier.fillMaxWidth(),
                                enabled  = uiState.tmdbApiKey.isNotBlank()
                            ) {
                                Text(stringResource(R.string.settings_save))
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }

                // TMDB attribution (always visible)
                HorizontalDivider(
                    color     = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    thickness = 0.5.dp,
                    modifier  = Modifier.padding(horizontal = 16.dp)
                )
                Text(
                    text     = stringResource(R.string.settings_tmdb_attribution),
                    style    = MaterialTheme.typography.labelSmall,
                    color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            // ── Companion Service ─────────────────────────────────────────────
            SettingsSectionHeader(stringResource(R.string.settings_companion))

            SettingsCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            stringResource(R.string.settings_companion_toggle),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text  = if (uiState.companionRunning)
                                        stringResource(R.string.settings_companion_running)
                                    else
                                        stringResource(R.string.settings_companion_stopped),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (uiState.companionRunning)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                    Switch(
                        checked         = uiState.companionRunning,
                        onCheckedChange = { viewModel.toggleCompanionService() }
                    )
                }
            }

            // ── LLM Section ───────────────────────────────────────────────────
            SettingsSectionHeader(stringResource(R.string.settings_llm))

            SettingsCard {
                SettingsRow(
                    label       = stringResource(R.string.settings_llm_backend),
                    value       = uiState.llmBackend,
                    showDivider = true
                )
                SettingsRow(
                    label       = stringResource(R.string.settings_llm_model),
                    value       = uiState.llmModelName ?: "—",
                    showDivider = uiState.llmDownloadProgress != null || !uiState.llmReady
                )

                when {
                    uiState.llmDownloadProgress != null -> {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(
                                stringResource(
                                    R.string.settings_llm_downloading,
                                    uiState.llmDownloadProgress!!
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { uiState.llmDownloadProgress!! / 100f },
                                modifier = Modifier.fillMaxWidth(),
                                color    = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    uiState.llmReady -> {
                        Text(
                            text     = stringResource(R.string.settings_llm_ready),
                            style    = MaterialTheme.typography.bodySmall,
                            color    = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    uiState.llmValidationFailed -> {
                        Text(
                            text     = stringResource(R.string.settings_llm_validation_failed),
                            style    = MaterialTheme.typography.bodySmall,
                            color    = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                        TextButton(
                            onClick  = { viewModel.downloadModel() },
                            modifier = Modifier.padding(horizontal = 8.dp)
                        ) {
                            Text(stringResource(R.string.settings_llm_download))
                        }
                    }
                    uiState.llmModelName != null -> {
                        TextButton(
                            onClick  = { viewModel.downloadModel() },
                            modifier = Modifier.padding(horizontal = 8.dp)
                        ) {
                            Text(stringResource(R.string.settings_llm_download))
                        }
                    }
                    else -> {
                        Text(
                            text     = stringResource(R.string.settings_llm_not_available),
                            style    = MaterialTheme.typography.bodySmall,
                            color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            // ── Advanced Settings ─────────────────────────────────────────────
            SettingsSectionHeader(stringResource(R.string.settings_advanced))

            SettingsCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        stringResource(R.string.settings_advanced),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    TextButton(onClick = { showAdvanced = !showAdvanced }) {
                        Text(if (showAdvanced) stringResource(R.string.settings_advanced_hide) else stringResource(R.string.settings_advanced_show))
                    }
                }

                if (showAdvanced) {
                    AdvancedAuthSettings(
                        uiState   = uiState,
                        viewModel = viewModel
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    // Disconnect confirmation dialog
    if (showDisconnectDialog) {
        AlertDialog(
            onDismissRequest = { showDisconnectDialog = false },
            title = { Text(stringResource(R.string.settings_disconnect_title)) },
            text  = { Text(stringResource(R.string.settings_disconnect_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.disconnectTrakt()
                    showDisconnectDialog = false
                    onDisconnected()
                }) {
                    Text(stringResource(R.string.settings_disconnect), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectDialog = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            }
        )
    }
}

@Composable
private fun AdvancedAuthSettings(uiState: SettingsUiState, viewModel: SettingsViewModel) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        // ── Authentication group ──
        Text(
            text  = stringResource(R.string.settings_auth_mode),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(4.dp))

        // Auth mode selector
        AuthMode.entries.forEach { mode ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                RadioButton(
                    selected = uiState.authMode == mode,
                    onClick  = { viewModel.setAuthMode(mode) }
                )
                Text(
                    text  = when (mode) {
                        AuthMode.MANAGED     -> stringResource(R.string.settings_auth_managed)
                        AuthMode.SELF_HOSTED -> stringResource(R.string.settings_auth_self_hosted)
                        AuthMode.DIRECT      -> stringResource(R.string.settings_auth_direct)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Mode-specific fields
        when (uiState.authMode) {
            AuthMode.SELF_HOSTED -> {
                OutlinedTextField(
                    value         = uiState.customBackendUrl,
                    onValueChange = viewModel::setCustomBackendUrl,
                    label         = { Text(stringResource(R.string.settings_auth_backend_url)) },
                    modifier      = Modifier.fillMaxWidth(),
                    singleLine    = true
                )
            }
            AuthMode.DIRECT -> {
                OutlinedTextField(
                    value         = uiState.directClientId,
                    onValueChange = viewModel::setDirectClientId,
                    label         = { Text(stringResource(R.string.settings_auth_client_id)) },
                    modifier      = Modifier.fillMaxWidth(),
                    singleLine    = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value               = uiState.directClientSecret,
                    onValueChange       = viewModel::setDirectClientSecret,
                    label               = { Text(stringResource(R.string.settings_auth_client_secret)) },
                    modifier            = Modifier.fillMaxWidth(),
                    singleLine          = true,
                    visualTransformation = PasswordVisualTransformation()
                )
            }
            else -> {}
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(
            color     = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
            thickness = 0.5.dp
        )
        Spacer(Modifier.height(12.dp))

        // ── Model download group ──
        Text(
            text  = stringResource(R.string.settings_model_url_section),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value         = uiState.modelDownloadUrl,
            onValueChange = viewModel::setModelDownloadUrl,
            label         = { Text(stringResource(R.string.settings_model_url)) },
            modifier      = Modifier.fillMaxWidth(),
            singleLine    = true,
            isError       = uiState.modelDownloadUrlError,
            supportingText = if (uiState.modelDownloadUrlError) {
                { Text(stringResource(R.string.settings_model_url_invalid)) }
            } else null
        )

        Spacer(Modifier.height(12.dp))
        Button(
            onClick  = { viewModel.saveAdvancedSettings() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.settings_save))
        }
        Spacer(Modifier.height(12.dp))
    }
}

// ── TMDB helper link ──────────────────────────────────────────────────────────

@Composable
private fun TmdbHelperLink() {
    val uriHandler = LocalUriHandler.current
    val url = stringResource(R.string.settings_tmdb_api_url)
    val linkText = stringResource(R.string.settings_tmdb_helper_link)
    val helperText = stringResource(R.string.settings_tmdb_helper)

    Text(
        text  = helperText,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    )
    Spacer(Modifier.height(2.dp))
    Text(
        text            = linkText,
        style           = MaterialTheme.typography.bodySmall,
        color           = MaterialTheme.colorScheme.primary,
        textDecoration  = TextDecoration.Underline,
        modifier        = Modifier.clickable { uriHandler.openUri(url) }
    )
}

// ── Reusable components ───────────────────────────────────────────────────────

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text       = title.uppercase(),
        style      = MaterialTheme.typography.labelSmall,
        color      = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier   = Modifier.padding(top = 16.dp, bottom = 4.dp, start = 4.dp)
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(content = content)
    }
}

@Composable
private fun SettingsRow(label: String, value: String, showDivider: Boolean = false) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text  = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text  = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        if (showDivider) {
            HorizontalDivider(
                color     = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                thickness = 0.5.dp,
                modifier  = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}
