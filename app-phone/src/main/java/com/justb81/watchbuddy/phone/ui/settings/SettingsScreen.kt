package com.justb81.watchbuddy.phone.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.justb81.watchbuddy.BuildConfig
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import com.justb81.watchbuddy.core.model.AvatarSource
import com.justb81.watchbuddy.phone.ui.components.InitialsAvatar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onDisconnected: () -> Unit,
    onConnectClick: () -> Unit,
    onDiagnosticsClick: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    LaunchedEffect(Unit) {
        DiagnosticLog.event("SettingsScreen", "composable:entered")
    }

    val uiState by viewModel.uiState.collectAsState()
    // forceShowAdvanced is true when a bundled option is unavailable and the user must
    // configure it manually.  In that case advanced settings are always expanded.
    var showAdvanced by remember(uiState.forceShowAdvanced) { mutableStateOf(uiState.forceShowAdvanced) }
    var showDisconnectDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val savedMessage = stringResource(R.string.settings_saved)

    LaunchedEffect(uiState.saveSuccess) {
        if (uiState.saveSuccess) {
            snackbarHostState.showSnackbar(savedMessage)
            viewModel.clearSaveSuccess()
        }
    }

    val avatarImportErrorMessage = stringResource(R.string.settings_avatar_import_failed)
    LaunchedEffect(uiState.customAvatarImportError) {
        if (uiState.customAvatarImportError) {
            snackbarHostState.showSnackbar(avatarImportErrorMessage)
        }
    }

    val pickPhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> viewModel.onCustomAvatarPicked(uri) }

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
            // ── Diagnostics entry point ──────────────────────────────────────
            SettingsSectionHeader(stringResource(R.string.settings_diagnostics))

            SettingsCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onDiagnosticsClick)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        stringResource(R.string.settings_diagnostics_row),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "›",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }

            // ── Identity (display name + avatar source) ──────────────────────
            SettingsSectionHeader(stringResource(R.string.settings_identity_section))

            SettingsCard {
                IdentitySection(
                    state = uiState,
                    onDisplayNameChange = viewModel::setDisplayNameOverride,
                    onAvatarSourceChange = viewModel::setAvatarSource,
                    onChoosePhotoClick = {
                        pickPhoto.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                )
            }

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
                // Status row only — key management lives in Advanced settings.
                val tmdbStatusValue = when {
                    uiState.tmdbConnected -> stringResource(R.string.settings_tmdb_connected)
                    uiState.defaultTmdbApiKeyAvailable -> stringResource(R.string.settings_tmdb_default_key_active)
                    else -> stringResource(R.string.settings_not_connected)
                }
                SettingsRow(
                    label       = stringResource(R.string.settings_tmdb_account),
                    value       = tmdbStatusValue,
                    showDivider = true
                )

                // TMDB attribution (always visible)
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
                        // Snapshot to a local so a racing recomposition that nulls the
                        // value out between the guard and the render can't throw NPE.
                        val progress = uiState.llmDownloadProgress ?: 0
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(
                                stringResource(
                                    R.string.settings_llm_downloading,
                                    progress
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { progress / 100f },
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
                    // Hide the toggle when advanced settings must always be shown
                    // because a bundled option is not configured in this build.
                    if (!uiState.forceShowAdvanced) {
                        TextButton(onClick = { showAdvanced = !showAdvanced }) {
                            Text(
                                if (showAdvanced) stringResource(R.string.settings_advanced_hide)
                                else stringResource(R.string.settings_advanced_show)
                            )
                        }
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

            Text(
                text = stringResource(
                    R.string.settings_version_footer,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 16.dp)
            )
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

        // Auth mode selector — MANAGED is disabled when the backend is not configured in the build.
        AuthMode.entries.forEach { mode ->
            val isManagedUnavailable = mode == AuthMode.MANAGED && !uiState.managedTraktAvailable
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                RadioButton(
                    selected = uiState.authMode == mode,
                    onClick  = { if (!isManagedUnavailable) viewModel.setAuthMode(mode) },
                    enabled  = !isManagedUnavailable
                )
                Column {
                    Text(
                        text  = when (mode) {
                            AuthMode.MANAGED     -> stringResource(R.string.settings_auth_managed)
                            AuthMode.SELF_HOSTED -> stringResource(R.string.settings_auth_self_hosted)
                            AuthMode.DIRECT      -> stringResource(R.string.settings_auth_direct)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isManagedUnavailable)
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        else
                            MaterialTheme.colorScheme.onSurface
                    )
                    if (isManagedUnavailable) {
                        Text(
                            text  = stringResource(R.string.settings_auth_managed_unavailable),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    }
                }
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
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value         = uiState.directClientId,
                    onValueChange = viewModel::setDirectClientId,
                    label         = { Text(stringResource(R.string.settings_auth_client_id)) },
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

        // ── TMDB API key group ────────────────────────────────────────────────
        Text(
            text  = stringResource(R.string.settings_tmdb_key_section),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(4.dp))

        // "Bundled key" option — disabled when no built-in key was baked into this build.
        val bundledAvailable = uiState.buildHasBundledTmdbKey
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            RadioButton(
                selected = uiState.useBundledTmdbKey,
                onClick  = { viewModel.setUseBundledTmdbKey(true) },
                enabled  = bundledAvailable
            )
            Text(
                text  = stringResource(R.string.settings_tmdb_key_bundled),
                style = MaterialTheme.typography.bodyMedium,
                color = if (bundledAvailable)
                    MaterialTheme.colorScheme.onSurface
                else
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
        }

        // "Own key" option — always enabled.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            RadioButton(
                selected = !uiState.useBundledTmdbKey,
                onClick  = { viewModel.setUseBundledTmdbKey(false) }
            )
            Text(
                text  = stringResource(R.string.settings_tmdb_key_own),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        // Text field + helper shown only when "Own key" is selected.
        if (!uiState.useBundledTmdbKey) {
            Spacer(Modifier.height(4.dp))
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

// ── Identity section (display name + avatar source) ─────────────────────────

@Composable
private fun IdentitySection(
    state: SettingsUiState,
    onDisplayNameChange: (String) -> Unit,
    onAvatarSourceChange: (AvatarSource) -> Unit,
    onChoosePhotoClick: () -> Unit
) {
    val previewName = state.displayNameOverride.ifBlank {
        state.traktUsername ?: stringResource(R.string.settings_identity_fallback_name)
    }
    Column(modifier = Modifier.padding(16.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            IdentityPreview(state = state, previewName = previewName)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = previewName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(
                        when (state.avatarSource) {
                            AvatarSource.TRAKT -> R.string.settings_avatar_source_trakt
                            AvatarSource.GENERATED -> R.string.settings_avatar_source_generated
                            AvatarSource.CUSTOM -> R.string.settings_avatar_source_custom
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = state.displayNameOverride,
            onValueChange = onDisplayNameChange,
            label = { Text(stringResource(R.string.settings_display_name_label)) },
            placeholder = {
                Text(state.traktUsername ?: stringResource(R.string.settings_identity_fallback_name))
            },
            supportingText = { Text(stringResource(R.string.settings_display_name_helper)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        Text(
            text = stringResource(R.string.settings_avatar_source_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )

        AvatarSource.entries.forEach { source ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onAvatarSourceChange(source) }
                    .padding(vertical = 2.dp)
            ) {
                RadioButton(
                    selected = state.avatarSource == source,
                    onClick = { onAvatarSourceChange(source) }
                )
                Text(
                    text = stringResource(
                        when (source) {
                            AvatarSource.TRAKT -> R.string.settings_avatar_source_trakt
                            AvatarSource.GENERATED -> R.string.settings_avatar_source_generated
                            AvatarSource.CUSTOM -> R.string.settings_avatar_source_custom
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        if (state.avatarSource == AvatarSource.CUSTOM) {
            Spacer(Modifier.height(8.dp))
            Button(onClick = onChoosePhotoClick) {
                Text(stringResource(R.string.settings_avatar_choose_photo))
            }
        }
    }
}

@Composable
private fun IdentityPreview(state: SettingsUiState, previewName: String) {
    val androidContext = androidx.compose.ui.platform.LocalContext.current
    val size = 64.dp
    when (state.avatarSource) {
        AvatarSource.GENERATED -> {
            InitialsAvatar(name = previewName, size = size)
        }
        AvatarSource.CUSTOM -> {
            if (state.hasCustomAvatar) {
                val file = remember { java.io.File(androidContext.filesDir, "avatar.jpg") }
                AsyncImage(
                    model = ImageRequest.Builder(androidContext)
                        .data(file)
                        // Version-stamp the memory cache key so the preview
                        // refreshes the moment the user picks a new photo.
                        .memoryCacheKey("avatar-preview-${state.customAvatarVersion}")
                        .diskCacheKey("avatar-preview-${state.customAvatarVersion}")
                        .build(),
                    contentDescription = stringResource(R.string.settings_avatar_preview_cd),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(size)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
            } else {
                InitialsAvatar(name = previewName, size = size)
            }
        }
        AvatarSource.TRAKT -> {
            InitialsAvatar(name = previewName, size = size)
        }
    }
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
