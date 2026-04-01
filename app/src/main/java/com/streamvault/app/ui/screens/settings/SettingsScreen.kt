package com.streamvault.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.*
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.material3.Switch
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.HorizontalDivider
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

import com.streamvault.app.ui.components.dialogs.PinDialog
import com.streamvault.app.ui.components.dialogs.PremiumDialog
import com.streamvault.app.ui.components.dialogs.PremiumDialogFooterButton
import com.streamvault.app.ui.components.TvEmptyState
import com.streamvault.app.localization.localeForLanguageTag
import com.streamvault.app.localization.supportedAppLanguageTags
import com.streamvault.app.ui.components.shell.AppNavigationChrome
import com.streamvault.app.ui.components.shell.AppScreenScaffold
import com.streamvault.app.ui.model.LiveTvChannelMode
import com.streamvault.app.ui.model.VodViewMode
import com.streamvault.app.ui.theme.*
import com.streamvault.domain.manager.BackupConflictStrategy
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.CategorySortMode
import com.streamvault.domain.model.ChannelNumberingMode
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.ProviderStatus
import com.streamvault.domain.model.RecordingItem
import com.streamvault.domain.model.RecordingRecurrence
import com.streamvault.domain.model.RecordingStatus
import com.streamvault.app.ui.screens.settings.ProviderDiagnosticsUiModel
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.style.TextAlign
import com.streamvault.app.R
import java.util.Locale
import com.streamvault.app.BuildConfig
import com.streamvault.app.ui.interaction.mouseClickable


@Composable
fun SettingsScreen(
    onNavigate: (String) -> Unit,
    onAddProvider: () -> Unit = {},
    onEditProvider: (Provider) -> Unit = {},
    onNavigateToParentalControl: (Long) -> Unit = {},
    currentRoute: String,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val appLanguageLabel = remember(uiState.appLanguage, context) {
        displayLanguageLabel(uiState.appLanguage, context.getString(R.string.settings_system_default))
    }
    val preferredAudioLanguageLabel = remember(uiState.preferredAudioLanguage, context) {
        displayLanguageLabel(uiState.preferredAudioLanguage, context.getString(R.string.settings_audio_language_auto))
    }
    val playbackSpeedLabel = remember(uiState.playerPlaybackSpeed) {
        formatPlaybackSpeedLabel(uiState.playerPlaybackSpeed)
    }
    val subtitleSizeLabel = remember(uiState.subtitleTextScale, context) {
        formatSubtitleSizeLabel(uiState.subtitleTextScale, context)
    }
    val subtitleTextColorLabel = remember(uiState.subtitleTextColor, context) {
        formatSubtitleColorLabel(uiState.subtitleTextColor, subtitleTextColorOptions(context))
    }
    val subtitleBackgroundLabel = remember(uiState.subtitleBackgroundColor, context) {
        formatSubtitleColorLabel(uiState.subtitleBackgroundColor, subtitleBackgroundColorOptions(context))
    }
    val wifiQualityLabel = remember(uiState.wifiMaxVideoHeight, context) {
        formatQualityCapLabel(uiState.wifiMaxVideoHeight, context.getString(R.string.settings_quality_cap_auto))
    }
    val ethernetQualityLabel = remember(uiState.ethernetMaxVideoHeight, context) {
        formatQualityCapLabel(uiState.ethernetMaxVideoHeight, context.getString(R.string.settings_quality_cap_auto))
    }
    val lastSpeedTestLabel = remember(uiState.lastSpeedTest) {
        uiState.lastSpeedTest?.let(::formatSpeedTestValueLabel)
            ?: context.getString(R.string.settings_speed_test_not_run)
    }
    val lastSpeedTestSummary = remember(uiState.lastSpeedTest, context) {
        uiState.lastSpeedTest?.let { formatSpeedTestSummary(it, context) }
            ?: context.getString(R.string.settings_speed_test_summary_default)
    }
    val speedTestRecommendationLabel = remember(uiState.lastSpeedTest, context) {
        formatQualityCapLabel(
            uiState.lastSpeedTest?.recommendedMaxVideoHeight,
            context.getString(R.string.settings_quality_cap_auto)
        )
    }
    val protectionSummary = remember(uiState.parentalControlLevel, context) {
        when (uiState.parentalControlLevel) {
            0 -> context.getString(R.string.settings_level_off)
            1 -> context.getString(R.string.settings_level_locked)
            2 -> context.getString(R.string.settings_level_hidden)
            else -> context.getString(R.string.settings_level_unknown)
        }
    }

    // Parental Control State
    var showPinDialog by rememberSaveable { mutableStateOf(false) }
    var showLevelDialog by rememberSaveable { mutableStateOf(false) }
    var showLanguageDialog by rememberSaveable { mutableStateOf(false) }
    var showLiveTvModeDialog by rememberSaveable { mutableStateOf(false) }
    var showLiveChannelNumberingDialog by rememberSaveable { mutableStateOf(false) }
    var showVodViewModeDialog by rememberSaveable { mutableStateOf(false) }
    var showPlaybackSpeedDialog by rememberSaveable { mutableStateOf(false) }
    var showAudioLanguageDialog by rememberSaveable { mutableStateOf(false) }
    var showSubtitleSizeDialog by rememberSaveable { mutableStateOf(false) }
    var showSubtitleTextColorDialog by rememberSaveable { mutableStateOf(false) }
    var showSubtitleBackgroundDialog by rememberSaveable { mutableStateOf(false) }
    var showWifiQualityDialog by rememberSaveable { mutableStateOf(false) }
    var showProviderSyncDialog by rememberSaveable { mutableStateOf(false) }
    var showCustomProviderSyncDialog by rememberSaveable { mutableStateOf(false) }
    var pendingSyncProviderId by rememberSaveable { mutableStateOf<Long?>(null) }
    var customSyncSelections by rememberSaveable {
        mutableStateOf(
            setOf(
                ProviderSyncSelection.TV,
                ProviderSyncSelection.MOVIES,
                ProviderSyncSelection.EPG
            )
        )
    }
    var showEthernetQualityDialog by rememberSaveable { mutableStateOf(false) }
    var showClearHistoryDialog by rememberSaveable { mutableStateOf(false) }
    var categorySortDialogType by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedCategory by rememberSaveable { mutableStateOf(0) }
    var pinError by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingAction by remember { mutableStateOf<ParentalAction?>(null) }
    var pendingProtectionLevel by rememberSaveable { mutableStateOf<Int?>(null) }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { viewModel.exportConfig(it.toString()) }
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.inspectBackup(it.toString()) }
    }

    val uriHandler = LocalUriHandler.current

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.userMessageShown()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AppScreenScaffold(
            currentRoute = currentRoute,
            onNavigate = { if (!uiState.isSyncing) onNavigate(it) },
            title = stringResource(R.string.settings_title),
            subtitle = stringResource(R.string.settings_providers_subtitle),
            navigationChrome = AppNavigationChrome.TopBar,
            compactHeader = true,
            showScreenHeader = false
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                // ── Left navigation rail ──────────────────────────────────────
                LazyColumn(
                    modifier = Modifier
                        .width(236.dp)
                        .fillMaxHeight()
                        .background(Color.Black.copy(alpha = 0.25f)),
                    contentPadding = PaddingValues(top = 76.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    item { SettingsNavItem(stringResource(R.string.settings_providers), "P", Primary, selectedCategory == 0) { selectedCategory = 0 } }
                    item { SettingsNavItem(stringResource(R.string.settings_playback), ">", Color(0xFF9E8FFF), selectedCategory == 1) { selectedCategory = 1 } }
                    item { SettingsNavItem(stringResource(R.string.settings_privacy), "L", Color(0xFFFFB74D), selectedCategory == 2) { selectedCategory = 2 } }
                    item { SettingsNavItem(stringResource(R.string.settings_recording_title), "R", Color(0xFFEF5350), selectedCategory == 3) { selectedCategory = 3 } }
                    item { SettingsNavItem(stringResource(R.string.settings_backup_restore), "B", Color(0xFF42A5F5), selectedCategory == 4) { selectedCategory = 4 } }
                    item { SettingsNavItem(stringResource(R.string.settings_about), "i", Color(0xFF78909C), selectedCategory == 5) { selectedCategory = 5 } }
                }

                // Thin vertical separator
                Box(
                    Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(Color.White.copy(alpha = 0.07f))
                )

                // ── Right content pane ────────────────────────────────────────
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentPadding = PaddingValues(start = 20.dp, top = 76.dp, end = 20.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    userScrollEnabled = !uiState.isSyncing
                ) {
                    // ── 0: Providers ──────────────────────────────────────────
                    if (selectedCategory == 0) {
                        if (uiState.providers.isEmpty()) {
                            item {
                                TvEmptyState(
                                    title = stringResource(R.string.settings_no_providers),
                                    subtitle = stringResource(R.string.settings_no_providers_subtitle),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp, bottom = 8.dp)
                                )
                            }
                        } else {
                            item {
                                var selectedProviderId by rememberSaveable(uiState.providers, uiState.activeProviderId) {
                                    mutableStateOf(uiState.activeProviderId ?: uiState.providers.first().id)
                                }
                                LaunchedEffect(uiState.providers, uiState.activeProviderId) {
                                    val availableIds = uiState.providers.map { it.id }.toSet()
                                    if (selectedProviderId !in availableIds) {
                                        selectedProviderId = uiState.activeProviderId ?: uiState.providers.first().id
                                    }
                                }
                                val selectedProvider = uiState.providers.firstOrNull { it.id == selectedProviderId }
                                    ?: uiState.providers.first()

                                Text(
                                    text = stringResource(R.string.settings_provider_selector_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = OnSurfaceDim,
                                    modifier = Modifier.padding(bottom = 10.dp)
                                )
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    contentPadding = PaddingValues(bottom = 14.dp)
                                ) {
                                    items(uiState.providers, key = { it.id }) { provider ->
                                        ProviderSelectorTab(
                                            provider = provider,
                                            isSelected = provider.id == selectedProvider.id,
                                            isActive = provider.id == uiState.activeProviderId,
                                            onClick = { selectedProviderId = provider.id }
                                        )
                                    }
                                }
                                ProviderSettingsCard(
                                    provider = selectedProvider,
                                    isActive = selectedProvider.id == uiState.activeProviderId,
                                    isSyncing = uiState.isSyncing,
                                    diagnostics = uiState.diagnosticsByProvider[selectedProvider.id],
                                    syncWarnings = uiState.syncWarningsByProvider[selectedProvider.id].orEmpty(),
                                    onRetryWarningAction = { action -> viewModel.retryWarningAction(selectedProvider.id, action) },
                                    onConnect = { viewModel.setActiveProvider(selectedProvider.id) },
                                    onRefresh = {
                                        pendingSyncProviderId = selectedProvider.id
                                        customSyncSelections = buildSet {
                                            add(ProviderSyncSelection.TV)
                                            add(ProviderSyncSelection.MOVIES)
                                            add(ProviderSyncSelection.EPG)
                                            if (selectedProvider.type == ProviderType.XTREAM_CODES) {
                                                add(ProviderSyncSelection.SERIES)
                                            }
                                        }
                                        showProviderSyncDialog = true
                                    },
                                    onDelete = { viewModel.deleteProvider(selectedProvider.id) },
                                    onEdit = { onEditProvider(selectedProvider) },
                                    onParentalControl = { onNavigateToParentalControl(selectedProvider.id) }
                                )
                            }
                        }
                        item {
                            Surface(
                                onClick = onAddProvider,
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = Primary.copy(alpha = 0.15f),
                                    focusedContainerColor = Primary.copy(alpha = 0.3f)
                                ),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(R.string.settings_add_provider),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = Primary
                                    )
                                }
                            }
                        }
                    }

                    // ── 1: Playback ───────────────────────────────────────────
                    else if (selectedCategory == 1) {
                        item {
                            ClickableSettingsRow(
                                label = stringResource(R.string.settings_live_tv_channel_mode),
                                value = stringResource(uiState.liveTvChannelMode.labelResId()),
                                onClick = { showLiveTvModeDialog = true }
                            )
                            ClickableSettingsRow(
                                label = stringResource(R.string.settings_live_channel_numbering_mode),
                                value = stringResource(uiState.liveChannelNumberingMode.labelResId()),
                                onClick = { showLiveChannelNumberingDialog = true }
                            )
                            ClickableSettingsRow(
                                label = stringResource(R.string.settings_vod_view_mode),
                                value = stringResource(uiState.vodViewMode.labelResId()),
                                onClick = { showVodViewModeDialog = true }
                            )
                            HorizontalDivider(color = Color.White.copy(alpha = 0.07f), modifier = Modifier.padding(vertical = 4.dp))
                            ClickableSettingsRow(
                                label = stringResource(R.string.settings_category_sort_live),
                                value = formatCategorySortModeLabel(uiState.categorySortModes[ContentType.LIVE] ?: CategorySortMode.DEFAULT, context),
                                onClick = { categorySortDialogType = ContentType.LIVE.name }
                            )
                            ClickableSettingsRow(
                                label = stringResource(R.string.settings_category_sort_movies),
                                value = formatCategorySortModeLabel(uiState.categorySortModes[ContentType.MOVIE] ?: CategorySortMode.DEFAULT, context),
                                onClick = { categorySortDialogType = ContentType.MOVIE.name }
                            )
                            ClickableSettingsRow(
                                label = stringResource(R.string.settings_category_sort_series),
                                value = formatCategorySortModeLabel(uiState.categorySortModes[ContentType.SERIES] ?: CategorySortMode.DEFAULT, context),
                                onClick = { categorySortDialogType = ContentType.SERIES.name }
                            )
                            HorizontalDivider(color = Color.White.copy(alpha = 0.07f), modifier = Modifier.padding(vertical = 4.dp))
                            Surface(
                                onClick = { showLanguageDialog = true },
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = Color.Transparent,
                                    focusedContainerColor = Primary.copy(alpha = 0.15f)
                                ),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(text = stringResource(R.string.settings_app_language), style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                                    Text(text = appLanguageLabel, style = MaterialTheme.typography.bodyMedium, color = Primary)
                                }
                            }
                            HorizontalDivider(color = Color.White.copy(alpha = 0.07f), modifier = Modifier.padding(vertical = 4.dp))
                            ClickableSettingsRow(
                                label = stringResource(R.string.settings_default_playback_speed),
                                value = playbackSpeedLabel,
                                onClick = { showPlaybackSpeedDialog = true }
                            )
                            ClickableSettingsRow(
                                label = stringResource(R.string.settings_preferred_audio_language),
                                value = preferredAudioLanguageLabel,
                                onClick = { showAudioLanguageDialog = true }
                            )
                            ClickableSettingsRow(
                                label = stringResource(R.string.settings_subtitle_size),
                                value = subtitleSizeLabel,
                                onClick = { showSubtitleSizeDialog = true }
                            )
                            ClickableSettingsRow(
                                label = stringResource(R.string.settings_subtitle_text_color),
                                value = subtitleTextColorLabel,
                                onClick = { showSubtitleTextColorDialog = true }
                            )
                            ClickableSettingsRow(
                                label = stringResource(R.string.settings_subtitle_background),
                                value = subtitleBackgroundLabel,
                                onClick = { showSubtitleBackgroundDialog = true }
                            )
                            HorizontalDivider(color = Color.White.copy(alpha = 0.07f), modifier = Modifier.padding(vertical = 4.dp))
                            ClickableSettingsRow(
                                label = stringResource(R.string.settings_wifi_quality_cap),
                                value = wifiQualityLabel,
                                onClick = { showWifiQualityDialog = true }
                            )
                            ClickableSettingsRow(
                                label = stringResource(R.string.settings_ethernet_quality_cap),
                                value = ethernetQualityLabel,
                                onClick = { showEthernetQualityDialog = true }
                            )
                        }
                        item {
                            InternetSpeedTestCard(
                                valueLabel = lastSpeedTestLabel,
                                summary = lastSpeedTestSummary,
                                recommendationLabel = speedTestRecommendationLabel,
                                isRunning = uiState.isRunningInternetSpeedTest,
                                canApplyRecommendation = uiState.lastSpeedTest != null,
                                onRunTest = viewModel::runInternetSpeedTest,
                                onApplyWifi = viewModel::applySpeedTestRecommendationToWifi,
                                onApplyEthernet = viewModel::applySpeedTestRecommendationToEthernet
                            )
                        }
                    }

                    // ── 2: Privacy & Parental ─────────────────────────────────
                    else if (selectedCategory == 2) {
                        item {
                            ParentalControlCard(
                                level = uiState.parentalControlLevel,
                                hasParentalPin = uiState.hasParentalPin,
                                hasActiveProvider = uiState.activeProviderId != null,
                                onChangeLevel = {
                                    pendingProtectionLevel = null
                                    if (uiState.hasParentalPin) {
                                        pendingAction = ParentalAction.ChangeLevel
                                        showPinDialog = true
                                    } else {
                                        showLevelDialog = true
                                    }
                                },
                                onChangePin = {
                                    pendingProtectionLevel = null
                                    pendingAction = if (uiState.hasParentalPin) {
                                        ParentalAction.ChangePin
                                    } else {
                                        ParentalAction.SetNewPin
                                    }
                                    showPinDialog = true
                                }
                            )
                        }
                        item {
                            HorizontalDivider(color = Color.White.copy(alpha = 0.07f), modifier = Modifier.padding(vertical = 4.dp))
                            Surface(
                                onClick = { viewModel.toggleIncognitoMode() },
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = Color.Transparent,
                                    focusedContainerColor = Primary.copy(alpha = 0.15f)
                                ),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = stringResource(R.string.settings_incognito_mode), style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                                        Text(text = stringResource(R.string.settings_incognito_mode_subtitle), style = MaterialTheme.typography.bodySmall, color = OnBackground.copy(alpha = 0.6f))
                                    }
                                    Switch(checked = uiState.isIncognitoMode, onCheckedChange = { viewModel.toggleIncognitoMode() })
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            Surface(
                                onClick = { viewModel.toggleXtreamTextClassification() },
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = Color.Transparent,
                                    focusedContainerColor = Primary.copy(alpha = 0.15f)
                                ),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = stringResource(R.string.settings_xtream_text_classification), style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                                        Text(text = stringResource(R.string.settings_xtream_text_classification_subtitle), style = MaterialTheme.typography.bodySmall, color = OnBackground.copy(alpha = 0.6f))
                                    }
                                    Switch(checked = uiState.useXtreamTextClassification, onCheckedChange = { viewModel.toggleXtreamTextClassification() })
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            Surface(
                                onClick = { showClearHistoryDialog = true },
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = Color.Transparent,
                                    focusedContainerColor = Primary.copy(alpha = 0.15f)
                                ),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = stringResource(R.string.settings_clear_history), style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                                        Text(text = stringResource(R.string.settings_clear_history_subtitle), style = MaterialTheme.typography.bodySmall, color = OnBackground.copy(alpha = 0.6f))
                                    }
                                    Text(text = stringResource(R.string.settings_clear_history_confirm), style = MaterialTheme.typography.labelLarge, color = Primary)
                                }
                            }
                        }
                    }

                    // ── 3: Recordings ─────────────────────────────────────────
                    else if (selectedCategory == 3) {
                        item {
                            RecordingOverviewCard(
                                outputDirectory = uiState.recordingStorageState.outputDirectory,
                                availableBytes = uiState.recordingStorageState.availableBytes,
                                isWritable = uiState.recordingStorageState.isWritable,
                                activeCount = uiState.recordingItems.count { it.status == RecordingStatus.RECORDING },
                                scheduledCount = uiState.recordingItems.count { it.status == RecordingStatus.SCHEDULED }
                            )
                        }
                        if (uiState.recordingItems.isEmpty()) {
                            item {
                                Surface(
                                    onClick = {},
                                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                                    colors = ClickableSurfaceDefaults.colors(
                                        containerColor = Color.Transparent,
                                        focusedContainerColor = Primary.copy(alpha = 0.1f)
                                    ),
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp)
                                ) {
                                    TvEmptyState(
                                        title = stringResource(R.string.settings_recording_empty_title),
                                        subtitle = stringResource(R.string.settings_recording_empty_subtitle)
                                    )
                                }
                            }
                        } else {
                            items(uiState.recordingItems, key = { it.id }) { item ->
                                RecordingItemCard(
                                    item = item,
                                    onStop = { viewModel.stopRecording(item.id) },
                                    onCancel = { viewModel.cancelRecording(item.id) },
                                    onDelete = { viewModel.deleteRecording(item.id) }
                                )
                            }
                        }
                    }

                    // ── 4: Backup ─────────────────────────────────────────────
                    else if (selectedCategory == 4) {
                        item {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Surface(
                                    onClick = { createDocumentLauncher.launch("streamvault_backup.json") },
                                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                                    colors = ClickableSurfaceDefaults.colors(
                                        containerColor = Primary.copy(alpha = 0.12f),
                                        focusedContainerColor = Primary.copy(alpha = 0.28f)
                                    ),
                                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(24.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(text = "\u2191", style = MaterialTheme.typography.titleLarge, color = Primary, fontWeight = FontWeight.Bold)
                                        Text(text = stringResource(R.string.settings_backup_data), style = MaterialTheme.typography.titleSmall, color = Primary, textAlign = TextAlign.Center)
                                        Text(text = stringResource(R.string.settings_backup_subtitle), style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim, textAlign = TextAlign.Center)
                                    }
                                }
                                Surface(
                                    onClick = { openDocumentLauncher.launch(arrayOf("application/json")) },
                                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                                    colors = ClickableSurfaceDefaults.colors(
                                        containerColor = Secondary.copy(alpha = 0.12f),
                                        focusedContainerColor = Secondary.copy(alpha = 0.28f)
                                    ),
                                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(24.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(text = "\u2193", style = MaterialTheme.typography.titleLarge, color = Secondary, fontWeight = FontWeight.Bold)
                                        Text(text = stringResource(R.string.settings_restore_data), style = MaterialTheme.typography.titleSmall, color = Secondary, textAlign = TextAlign.Center)
                                        Text(text = stringResource(R.string.settings_backup_subtitle), style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim, textAlign = TextAlign.Center)
                                    }
                                }
                            }
                        }
                    }

                    // ── 5: About ──────────────────────────────────────────────
                    else {
                        item {
                            SettingsRow(label = stringResource(R.string.settings_app_version), value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                            SettingsRow(label = stringResource(R.string.settings_build), value = stringResource(R.string.settings_build_desc))
                            SettingsRow(label = stringResource(R.string.settings_developed_by), value = stringResource(R.string.settings_developer_name))
                            ClickableSettingsRow(
                                label = stringResource(R.string.settings_github),
                                value = stringResource(R.string.settings_github_url),
                                onClick = { uriHandler.openUri(context.getString(R.string.settings_github_url)) }
                            )
                            ClickableSettingsRow(
                                label = stringResource(R.string.settings_donate),
                                value = stringResource(R.string.settings_donate_url),
                                onClick = { uriHandler.openUri(context.getString(R.string.settings_donate_url)) }
                            )
                        }
                    }
                }
            }
        }

    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 16.dp)
    )

        // Blocking Loading Overlay
        if (uiState.isSyncing) {
            // Block Back Press
            androidx.activity.compose.BackHandler(enabled = true) {
                // Do nothing, effectively blocking back
            }
            
            // Overlay
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f))
                    .clickable(enabled = true, onClick = {}) // Consume clicks
                    .align(Alignment.Center),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(color = Primary)
                    Text(
                        text = stringResource(R.string.settings_syncing_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = OnSurface
                    )
                    Text(
                        text = stringResource(R.string.settings_syncing_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceDim
                    )
                }
            }
        }


        if (showLiveTvModeDialog) {
            LiveTvChannelModeDialog(
                selectedMode = uiState.liveTvChannelMode,
                onDismiss = { showLiveTvModeDialog = false },
                onModeSelected = { mode ->
                    viewModel.setLiveTvChannelMode(mode)
                    showLiveTvModeDialog = false
                }
            )
        }

        if (showLiveChannelNumberingDialog) {
            LiveChannelNumberingModeDialog(
                selectedMode = uiState.liveChannelNumberingMode,
                onDismiss = { showLiveChannelNumberingDialog = false },
                onModeSelected = { mode ->
                    viewModel.setLiveChannelNumberingMode(mode)
                    showLiveChannelNumberingDialog = false
                }
            )
        }

        if (showVodViewModeDialog) {
            VodViewModeDialog(
                selectedMode = uiState.vodViewMode,
                onDismiss = { showVodViewModeDialog = false },
                onModeSelected = { mode ->
                    viewModel.setVodViewMode(mode)
                    showVodViewModeDialog = false
                }
            )
        }

        if (showPlaybackSpeedDialog) {
            val speedOptions = remember { listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f) }
            PremiumSelectionDialog(
                title = stringResource(R.string.settings_select_playback_speed),
                onDismiss = { showPlaybackSpeedDialog = false }
            ) {
                speedOptions.forEachIndexed { index, speed ->
                    LevelOption(
                        level = index,
                        text = formatPlaybackSpeedLabel(speed),
                        currentLevel = if (speed == uiState.playerPlaybackSpeed) index else -1,
                        onSelect = {
                            viewModel.setDefaultPlaybackSpeed(speed)
                            showPlaybackSpeedDialog = false
                        }
                    )
                }
            }
        }

        if (showAudioLanguageDialog) {
            val autoLabel = stringResource(R.string.settings_audio_language_auto)
            val audioLanguageOptions = remember(autoLabel) { supportedAudioLanguages(autoLabel) }
            PremiumSelectionDialog(
                title = stringResource(R.string.settings_select_audio_language),
                onDismiss = { showAudioLanguageDialog = false }
            ) {
                audioLanguageOptions.forEachIndexed { index, option ->
                    LevelOption(
                        level = index,
                        text = option.label,
                        currentLevel = if (uiState.preferredAudioLanguage == option.tag) index else -1,
                        onSelect = {
                            viewModel.setPreferredAudioLanguage(option.tag)
                            showAudioLanguageDialog = false
                        }
                    )
                }
            }
        }

        if (showSubtitleSizeDialog) {
            val subtitleSizeOptions = remember { subtitleSizeOptions() }
            PremiumSelectionDialog(
                title = stringResource(R.string.settings_select_subtitle_size),
                onDismiss = { showSubtitleSizeDialog = false }
            ) {
                subtitleSizeOptions.forEachIndexed { index, option ->
                    LevelOption(
                        level = index,
                        text = option.label(context),
                        currentLevel = if (uiState.subtitleTextScale == option.scale) index else -1,
                        onSelect = {
                            viewModel.setSubtitleTextScale(option.scale)
                            showSubtitleSizeDialog = false
                        }
                    )
                }
            }
        }

        if (showSubtitleTextColorDialog) {
            val options = remember(context) { subtitleTextColorOptions(context) }
            PremiumSelectionDialog(
                title = stringResource(R.string.settings_select_subtitle_text_color),
                onDismiss = { showSubtitleTextColorDialog = false }
            ) {
                options.forEachIndexed { index, option ->
                    LevelOption(
                        level = index,
                        text = option.label,
                        currentLevel = if (uiState.subtitleTextColor == option.colorArgb) index else -1,
                        onSelect = {
                            viewModel.setSubtitleTextColor(option.colorArgb)
                            showSubtitleTextColorDialog = false
                        }
                    )
                }
            }
        }

        if (showSubtitleBackgroundDialog) {
            val options = remember(context) { subtitleBackgroundColorOptions(context) }
            PremiumSelectionDialog(
                title = stringResource(R.string.settings_select_subtitle_background),
                onDismiss = { showSubtitleBackgroundDialog = false }
            ) {
                options.forEachIndexed { index, option ->
                    LevelOption(
                        level = index,
                        text = option.label,
                        currentLevel = if (uiState.subtitleBackgroundColor == option.colorArgb) index else -1,
                        onSelect = {
                            viewModel.setSubtitleBackgroundColor(option.colorArgb)
                            showSubtitleBackgroundDialog = false
                        }
                    )
                }
            }
        }

        if (showWifiQualityDialog) {
            QualityCapSelectionDialog(
                title = stringResource(R.string.settings_select_wifi_quality_cap),
                currentValue = uiState.wifiMaxVideoHeight,
                onDismiss = { showWifiQualityDialog = false },
                onSelect = {
                    viewModel.setWifiQualityCap(it)
                    showWifiQualityDialog = false
                }
            )
        }

        if (showEthernetQualityDialog) {
            QualityCapSelectionDialog(
                title = stringResource(R.string.settings_select_ethernet_quality_cap),
                currentValue = uiState.ethernetMaxVideoHeight,
                onDismiss = { showEthernetQualityDialog = false },
                onSelect = {
                    viewModel.setEthernetQualityCap(it)
                    showEthernetQualityDialog = false
                }
            )
        }

        categorySortDialogType?.let { typeName ->
            val type = ContentType.entries.firstOrNull { it.name == typeName }
            if (type != null) {
                CategorySortModeDialog(
                    type = type,
                    currentMode = uiState.categorySortModes[type] ?: CategorySortMode.DEFAULT,
                    onDismiss = { categorySortDialogType = null },
                    onModeSelected = { mode ->
                        viewModel.setCategorySortMode(type, mode)
                        categorySortDialogType = null
                    }
                )
            }
        }

        if (showPinDialog) {
            PinDialog(
                onDismissRequest = { 
                    showPinDialog = false
                    pinError = null
                    if (pendingAction == ParentalAction.SetNewPin) {
                        pendingAction = null
                        pendingProtectionLevel = null
                    }
                },
                onPinEntered = { pin ->
                    scope.launch {
                        if (pendingAction == ParentalAction.SetNewPin) {
                            viewModel.changePin(pin)
                            pendingProtectionLevel?.let(viewModel::setParentalControlLevel)
                            pendingProtectionLevel = null
                            showPinDialog = false
                            pendingAction = null
                        } else {
                            if (viewModel.verifyPin(pin)) {
                                showPinDialog = false
                                pinError = null
                                when (pendingAction) {
                                    ParentalAction.ChangeLevel -> showLevelDialog = true
                                    ParentalAction.ChangePin -> {
                                        pendingAction = ParentalAction.SetNewPin
                                        showPinDialog = true 
                                    }
                                    else -> pendingAction = null
                                }
                            } else {
                                pinError = context.getString(R.string.home_incorrect_pin)
                            }
                        }
                    }
                },
                title = if (pendingAction == ParentalAction.SetNewPin) stringResource(R.string.settings_enter_new_pin) else stringResource(R.string.settings_enter_pin),
                error = pinError
            )
        }

        if (showLevelDialog) {
            PremiumSelectionDialog(
                title = stringResource(R.string.settings_select_level),
                onDismiss = { showLevelDialog = false }
            ) {
                LevelOption(0, stringResource(R.string.settings_level_off_desc), uiState.parentalControlLevel) {
                    viewModel.setParentalControlLevel(0)
                    showLevelDialog = false
                }
                LevelOption(1, stringResource(R.string.settings_level_locked_desc), uiState.parentalControlLevel) {
                    if (uiState.hasParentalPin) {
                        viewModel.setParentalControlLevel(1)
                    } else {
                        pendingProtectionLevel = 1
                        pendingAction = ParentalAction.SetNewPin
                        showPinDialog = true
                    }
                    showLevelDialog = false
                }
                LevelOption(2, stringResource(R.string.settings_level_hidden_desc), uiState.parentalControlLevel) {
                    if (uiState.hasParentalPin) {
                        viewModel.setParentalControlLevel(2)
                    } else {
                        pendingProtectionLevel = 2
                        pendingAction = ParentalAction.SetNewPin
                        showPinDialog = true
                    }
                    showLevelDialog = false
                }
            }
        }

        if (showLanguageDialog) {
            val systemDefaultLabel = stringResource(R.string.settings_system_default)
            val languageOptions = remember(systemDefaultLabel) { supportedAppLanguages(systemDefaultLabel) }
            PremiumSelectionDialog(
                title = stringResource(R.string.settings_select_language),
                onDismiss = { showLanguageDialog = false }
            ) {
                languageOptions.forEachIndexed { index, option ->
                    LevelOption(
                        level = index,
                        text = option.label,
                        currentLevel = if (uiState.appLanguage == option.tag) index else -1,
                        onSelect = {
                            viewModel.setAppLanguage(option.tag)
                            showLanguageDialog = false
                        }
                    )
                }
            }
        }

        val backupPreview = uiState.backupPreview
        if (backupPreview != null && uiState.pendingBackupUri != null) {
            BackupImportPreviewDialog(
                preview = backupPreview,
                plan = uiState.backupImportPlan,
                onDismiss = { viewModel.dismissBackupPreview() },
                onStrategySelected = { viewModel.setBackupConflictStrategy(it) },
                onImportPreferencesChanged = { viewModel.setImportPreferences(it) },
                onImportProvidersChanged = { viewModel.setImportProviders(it) },
                onImportSavedLibraryChanged = { viewModel.setImportSavedLibrary(it) },
                onImportPlaybackHistoryChanged = { viewModel.setImportPlaybackHistory(it) },
                onImportMultiViewChanged = { viewModel.setImportMultiViewPresets(it) },
                onImportRecordingSchedulesChanged = { viewModel.setImportRecordingSchedules(it) },
                onConfirm = { viewModel.confirmBackupImport() }
            )
        }

        if (showClearHistoryDialog) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showClearHistoryDialog = false },
                title = { Text(text = stringResource(R.string.settings_clear_history_dialog_title)) },
                text = {
                    Text(
                        text = stringResource(R.string.settings_clear_history_dialog_body),
                        color = OnSurface
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.clearHistory()
                            showClearHistoryDialog = false
                        }
                    ) {
                        Text(text = stringResource(R.string.settings_clear_history_confirm), color = Primary)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearHistoryDialog = false }) {
                        Text(text = stringResource(R.string.settings_cancel), color = OnSurface)
                    }
                },
                containerColor = SurfaceElevated,
                titleContentColor = OnSurface,
                textContentColor = TextSecondary
            )
        }
    }

    val pendingSyncProvider = pendingSyncProviderId?.let { providerId ->
        uiState.providers.firstOrNull { it.id == providerId }
    }
    if (showProviderSyncDialog && pendingSyncProvider != null) {
        ProviderSyncOptionsDialog(
            provider = pendingSyncProvider,
            onDismiss = {
                showProviderSyncDialog = false
                pendingSyncProviderId = null
            },
            onSelect = { selection ->
                showProviderSyncDialog = false
                if (selection == ProviderSyncSelection.ALL) {
                    viewModel.syncProviderSection(pendingSyncProvider.id, selection)
                    pendingSyncProviderId = null
                } else if (selection == null) {
                    showCustomProviderSyncDialog = true
                } else {
                    viewModel.syncProviderSection(pendingSyncProvider.id, selection)
                    pendingSyncProviderId = null
                }
            }
        )
    }
    if (showCustomProviderSyncDialog && pendingSyncProvider != null) {
        ProviderCustomSyncDialog(
            provider = pendingSyncProvider,
            selected = customSyncSelections,
            onToggle = { option ->
                customSyncSelections = if (option in customSyncSelections) {
                    customSyncSelections - option
                } else {
                    customSyncSelections + option
                }
            },
            onDismiss = {
                showCustomProviderSyncDialog = false
                pendingSyncProviderId = null
            },
            onConfirm = {
                showCustomProviderSyncDialog = false
                viewModel.syncProviderCustom(pendingSyncProvider.id, customSyncSelections)
                pendingSyncProviderId = null
            }
        )
    }
}

private fun availableSyncSelections(provider: Provider): List<ProviderSyncSelection> = buildList {
    add(ProviderSyncSelection.TV)
    add(ProviderSyncSelection.MOVIES)
    if (provider.type == ProviderType.XTREAM_CODES) {
        add(ProviderSyncSelection.SERIES)
    }
    add(ProviderSyncSelection.EPG)
}

@Composable
private fun ProviderSyncOptionsDialog(
    provider: Provider,
    onDismiss: () -> Unit,
    onSelect: (ProviderSyncSelection?) -> Unit
) {
    PremiumDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_sync_dialog_title, provider.name),
        content = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = stringResource(R.string.settings_sync_dialog_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurface
            )
            SyncOptionButton(stringResource(R.string.settings_sync_option_all)) {
                onSelect(ProviderSyncSelection.ALL)
            }
            if (provider.type == ProviderType.XTREAM_CODES) {
                SyncOptionButton(stringResource(R.string.settings_sync_option_fast)) {
                    onSelect(ProviderSyncSelection.FAST)
                }
            }
            availableSyncSelections(provider).forEach { option ->
                SyncOptionButton(text = syncSelectionLabel(option)) {
                    onSelect(option)
                }
            }
            OutlinedButton(
                onClick = { onSelect(null) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.settings_sync_option_custom))
            }
        }
    }
    )
}

@Composable
private fun ProviderCustomSyncDialog(
    provider: Provider,
    selected: Set<ProviderSyncSelection>,
    onToggle: (ProviderSyncSelection) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    PremiumDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_sync_custom_title, provider.name),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.settings_sync_custom_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurface
                )
                availableSyncSelections(provider).forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggle(option) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Checkbox(
                            checked = option in selected,
                            onCheckedChange = { onToggle(option) }
                        )
                        Text(
                            text = syncSelectionLabel(option),
                            style = MaterialTheme.typography.bodyLarge,
                            color = OnBackground
                        )
                    }
                }
            }
        },
        footer = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
            ) {
                PremiumDialogFooterButton(
                    label = stringResource(R.string.settings_cancel),
                    onClick = onDismiss
                )
                PremiumDialogFooterButton(
                    label = stringResource(R.string.settings_sync_btn),
                    onClick = onConfirm,
                    enabled = selected.isNotEmpty()
                )
            }
        }
    )
}

@Composable
private fun SyncOptionButton(
    text: String,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text)
    }
}

@Composable
private fun syncSelectionLabel(selection: ProviderSyncSelection): String = when (selection) {
    ProviderSyncSelection.ALL -> stringResource(R.string.settings_sync_option_all)
    ProviderSyncSelection.FAST -> stringResource(R.string.settings_sync_option_fast)
    ProviderSyncSelection.TV -> stringResource(R.string.settings_sync_option_tv)
    ProviderSyncSelection.MOVIES -> stringResource(R.string.settings_sync_option_movies)
    ProviderSyncSelection.SERIES -> stringResource(R.string.settings_sync_option_series)
    ProviderSyncSelection.EPG -> stringResource(R.string.settings_sync_option_epg)
}

private enum class ParentalAction {
    ChangeLevel, ChangePin, SetNewPin
}

@Composable
private fun PremiumSelectionDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val isTelevisionDevice = com.streamvault.app.device.rememberIsTelevisionDevice()
    Dialog(onDismissRequest = onDismiss) {
        val dialogContent: @Composable (Modifier) -> Unit = { resolvedModifier ->
            androidx.compose.material3.Surface(
                shape = RoundedCornerShape(14.dp),
                color = SurfaceElevated,
                modifier = resolvedModifier
                    .border(1.dp, Primary.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Primary
                    )
                    HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 6.dp)
                    ) {
                        content()
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Surface(
                            onClick = onDismiss,
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = Primary.copy(alpha = 0.2f),
                                focusedContainerColor = Primary.copy(alpha = 0.4f)
                            )
                        ) {
                            Text(
                                text = stringResource(R.string.settings_cancel),
                                style = MaterialTheme.typography.labelMedium,
                                color = Primary,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
                            )
                        }
                    }
                }
            }
        }

        if (isTelevisionDevice) {
            dialogContent(Modifier.fillMaxWidth(0.62f))
        } else {
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                val dialogWidthFraction = when {
                    maxWidth < 700.dp -> 0.92f
                    maxWidth < 1000.dp -> 0.78f
                    else -> 0.62f
                }
                dialogContent(Modifier.fillMaxWidth(dialogWidthFraction))
            }
        }
    }
}

@Composable
private fun LevelOption(level: Int, text: String, currentLevel: Int, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = level == currentLevel,
            onClick = onSelect
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = OnBackground)
    }
}

@Composable
private fun QualityCapSelectionDialog(
    title: String,
    currentValue: Int?,
    onDismiss: () -> Unit,
    onSelect: (Int?) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val options = remember(context) {
        listOf<Int?>(null, 2160, 1080, 720, 480)
    }
    PremiumSelectionDialog(
        title = title,
        onDismiss = onDismiss
    ) {
        options.forEachIndexed { index, option ->
            LevelOption(
                level = index,
                text = formatQualityCapLabel(option, context.getString(R.string.settings_quality_cap_auto)),
                currentLevel = if (option == currentValue) index else -1,
                onSelect = { onSelect(option) }
            )
        }
    }
}

@Composable
private fun CategorySortModeDialog(
    type: ContentType,
    currentMode: CategorySortMode,
    onDismiss: () -> Unit,
    onModeSelected: (CategorySortMode) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    PremiumDialog(
        title = categoryTypeLabel(type, context),
        subtitle = categoryTypeDescription(type, context),
        onDismissRequest = onDismiss,
        widthFraction = 0.52f,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CategorySortMode.entries.forEach { mode ->
                    Surface(
                        onClick = { onModeSelected(mode) },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (mode == currentMode) Primary.copy(alpha = 0.18f) else SurfaceElevated,
                            focusedContainerColor = Primary.copy(alpha = 0.28f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = formatCategorySortModeLabel(mode, context),
                                style = MaterialTheme.typography.titleSmall,
                                color = if (mode == currentMode) Primary else OnBackground
                            )
                            Text(
                                text = sortModeLabel(mode, context),
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceDim
                            )
                        }
                    }
                }
            }
        },
        footer = {
            PremiumDialogFooterButton(
                label = stringResource(R.string.settings_cancel),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun ParentalControlCard(
    level: Int,
    hasParentalPin: Boolean,
    hasActiveProvider: Boolean,
    onChangeLevel: () -> Unit,
    onChangePin: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceElevated, RoundedCornerShape(8.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(stringResource(R.string.settings_protection_level), style = MaterialTheme.typography.bodyLarge, color = OnBackground)
                Text(
                    text = when(level) {
                        0 -> stringResource(R.string.settings_level_off)
                        1 -> stringResource(R.string.settings_level_locked)
                        2 -> stringResource(R.string.settings_level_hidden)
                        else -> stringResource(R.string.settings_level_unknown)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (level == 0) ErrorColor else Primary
                )
            }
            
            // Custom Focusable Button for "Change"
            Surface(
                onClick = onChangeLevel,
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Secondary.copy(alpha = 0.2f),
                    focusedContainerColor = Secondary.copy(alpha = 0.5f),
                    contentColor = Secondary,
                    focusedContentColor = Secondary
                )
            ) {
                Text(
                    text = stringResource(R.string.settings_change),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
        
        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.settings_parental_pin), style = MaterialTheme.typography.bodyLarge, color = OnBackground)
            
            // Custom Focusable Button for "Change PIN"
            Surface(
                onClick = onChangePin,
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Primary.copy(alpha = 0.2f),
                    focusedContainerColor = Primary.copy(alpha = 0.5f),
                    contentColor = Primary,
                    focusedContentColor = Primary
                )
            ) {
                Text(
                    text = stringResource(if (hasParentalPin) R.string.settings_change_pin else R.string.settings_set_pin),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun ProviderSettingsCard(
    provider: Provider,
    isActive: Boolean,
    isSyncing: Boolean,
    diagnostics: ProviderDiagnosticsUiModel?,
    syncWarnings: List<String>,
    onRetryWarningAction: (ProviderWarningAction) -> Unit,
    onConnect: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onParentalControl: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Use Column layout - provider info + buttons below as separate focusable items
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (isActive) SurfaceHighlight else SurfaceElevated,
                shape = RoundedCornerShape(8.dp)
            )
            .border(
                width = if (isActive) 2.dp else 0.dp,
                color = if (isActive) Primary else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Provider info
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = provider.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = OnBackground
                )
                Text(
                    text = provider.type.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurface
                )
            }
            ProviderStatusBadge(status = provider.status)
            if (isActive) {
                Text(
                    text = stringResource(R.string.settings_active),
                    style = MaterialTheme.typography.labelSmall,
                    color = Primary,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    modifier = Modifier
                        .background(Primary.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        // Expiration Date
        val expDate = provider.expirationDate
        val expirationText = remember(expDate) {
            when (expDate) {
                null -> context.getString(R.string.settings_expiration_unknown)
                Long.MAX_VALUE -> context.getString(R.string.settings_expiration_never)
                else -> context.getString(R.string.settings_expires, java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(expDate)))
            }
        }
        Text(
            text = expirationText,
            style = MaterialTheme.typography.bodySmall,
            color = if (expDate != null && expDate < System.currentTimeMillis() && expDate != Long.MAX_VALUE) ErrorColor else OnSurfaceDim
        )

        diagnostics?.let { model ->
            Text(
                text = listOf(model.sourceLabel, model.connectionSummary, model.expirySummary)
                    .filter { it.isNotBlank() }
                    .joinToString(" • "),
                style = MaterialTheme.typography.bodySmall,
                color = OnSurface
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProviderCompactStat(title = stringResource(R.string.settings_diagnostic_live), count = model.liveCount)
                ProviderCompactStat(title = stringResource(R.string.settings_diagnostic_movies), count = model.movieCount)
                if (provider.type == ProviderType.XTREAM_CODES) {
                    ProviderCompactStat(title = stringResource(R.string.settings_diagnostic_series), count = model.seriesCount)
                }
                ProviderCompactStat(title = stringResource(R.string.settings_diagnostic_epg), count = model.epgCount)
            }

            ProviderDiagnosticsPanel(
                provider = provider,
                diagnostics = model
            )
        }

        if (syncWarnings.isNotEmpty()) {
            val hasEpgWarning = syncWarnings.any { it.contains("EPG", ignoreCase = true) }
            val hasMoviesWarning = syncWarnings.any { it.contains("Movies", ignoreCase = true) }
            val hasSeriesWarning = syncWarnings.any { it.contains("Series", ignoreCase = true) }

            Text(
                text = stringResource(R.string.settings_provider_warnings, syncWarnings.take(3).joinToString(", ")),
                style = MaterialTheme.typography.bodySmall,
                color = Secondary
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hasEpgWarning) {
                    Surface(
                        onClick = { onRetryWarningAction(ProviderWarningAction.EPG) },
                        enabled = !isSyncing,
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Secondary.copy(alpha = 0.16f),
                            focusedContainerColor = Secondary.copy(alpha = 0.35f)
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.settings_retry_epg),
                            style = MaterialTheme.typography.labelSmall,
                            color = Secondary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                }
                if (hasMoviesWarning) {
                    Surface(
                        onClick = { onRetryWarningAction(ProviderWarningAction.MOVIES) },
                        enabled = !isSyncing,
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Secondary.copy(alpha = 0.16f),
                            focusedContainerColor = Secondary.copy(alpha = 0.35f)
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.settings_retry_movies),
                            style = MaterialTheme.typography.labelSmall,
                            color = Secondary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                }
                if (hasSeriesWarning && provider.type == ProviderType.XTREAM_CODES) {
                    Surface(
                        onClick = { onRetryWarningAction(ProviderWarningAction.SERIES) },
                        enabled = !isSyncing,
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Secondary.copy(alpha = 0.16f),
                            focusedContainerColor = Secondary.copy(alpha = 0.35f)
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.settings_retry_series),
                            style = MaterialTheme.typography.labelSmall,
                            color = Secondary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }

        // Action buttons - each independently focusable for d-pad
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!isActive) {
                Surface(
                    onClick = onConnect,
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Primary,
                        focusedContainerColor = Primary.copy(alpha = 0.8f),
                        contentColor = Color.White
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                ) {
                    Text(
                        text = stringResource(R.string.settings_connect),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            } else {
                Surface(
                    onClick = onRefresh,
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Primary.copy(alpha = 0.2f),
                        focusedContainerColor = Primary.copy(alpha = 0.5f)
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                ) {
                    Text(
                        text = if (isSyncing) stringResource(R.string.settings_syncing_btn) else stringResource(R.string.settings_sync_btn),
                        style = MaterialTheme.typography.labelMedium,
                        color = Primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            }

            Surface(
                onClick = onEdit,
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Secondary.copy(alpha = 0.2f),
                    focusedContainerColor = Secondary.copy(alpha = 0.5f)
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
            ) {
                Text(
                    text = stringResource(R.string.settings_edit),
                    style = MaterialTheme.typography.labelMedium,
                    color = Secondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }

            Surface(
                onClick = onDelete,
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = ErrorColor.copy(alpha = 0.2f),
                    focusedContainerColor = ErrorColor.copy(alpha = 0.5f)
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
            ) {
                Text(
                    text = stringResource(R.string.settings_delete),
                    style = MaterialTheme.typography.labelMedium,
                    color = ErrorColor,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }

            if (isActive) {
                Surface(
                    onClick = onParentalControl,
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Primary.copy(alpha = 0.15f),
                        focusedContainerColor = Primary.copy(alpha = 0.3f)
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                ) {
                    Text(
                        text = stringResource(R.string.settings_provider_category_controls_action),
                        style = MaterialTheme.typography.labelMedium,
                        color = Primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            }
        }

    }
}

@Composable
private fun ProviderSelectorTab(
    provider: Provider,
    isSelected: Boolean,
    isActive: Boolean,
    onClick: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    Surface(
        onClick = onClick,
        modifier = Modifier
            .focusRequester(focusRequester)
            .mouseClickable(
                focusRequester = focusRequester,
                onClick = onClick
            ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(999.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) Primary.copy(alpha = 0.18f) else SurfaceElevated,
            focusedContainerColor = Primary.copy(alpha = 0.34f)
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = androidx.compose.foundation.BorderStroke(
                    width = 1.dp,
                    color = if (isSelected) Primary.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.08f)
                )
            ),
            focusedBorder = Border(
                border = androidx.compose.foundation.BorderStroke(
                    width = 2.dp,
                    color = Primary
                )
            )
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = provider.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = OnBackground,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    text = provider.type.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = OnSurfaceDim
                )
            }
            if (isActive) {
                Text(
                    text = stringResource(R.string.settings_active),
                    style = MaterialTheme.typography.labelSmall,
                    color = Primary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun ProviderCompactStat(
    title: String,
    count: Int
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        colors = SurfaceDefaults.colors(containerColor = Color.White.copy(alpha = 0.06f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = OnSurfaceDim
            )
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = OnBackground
            )
        }
    }
}

@Composable
private fun ProviderStatusBadge(status: ProviderStatus) {
    val (label, color) = when (status) {
        ProviderStatus.ACTIVE -> stringResource(R.string.settings_status_active) to Primary
        ProviderStatus.PARTIAL -> stringResource(R.string.settings_status_partial) to Secondary
        ProviderStatus.ERROR -> stringResource(R.string.settings_status_error) to ErrorColor
        ProviderStatus.EXPIRED -> stringResource(R.string.settings_status_expired) to ErrorColor
        ProviderStatus.DISABLED -> stringResource(R.string.settings_status_disabled) to OnSurfaceDim
        ProviderStatus.UNKNOWN -> stringResource(R.string.settings_status_unknown) to OnSurfaceDim
    }

    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        modifier = Modifier
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Composable
private fun SettingsOverviewCard(
    activeProviderName: String,
    providerCount: Int,
    protectionSummary: String,
    languageLabel: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        colors = SurfaceDefaults.colors(containerColor = SurfaceElevated),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = stringResource(R.string.settings_overview_title),
                style = MaterialTheme.typography.titleLarge,
                color = OnBackground
            )
            Text(
                text = stringResource(R.string.settings_overview_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceDim
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SettingsOverviewStat(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.settings_overview_provider_label),
                    value = activeProviderName
                )
                SettingsOverviewStat(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.settings_overview_count_label),
                    value = providerCount.toString()
                )
                SettingsOverviewStat(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.settings_overview_protection_label),
                    value = protectionSummary
                )
                SettingsOverviewStat(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.settings_overview_language_label),
                    value = languageLabel
                )
            }
        }

    }
}

@Composable
private fun ProviderDiagnosticsPanel(
    provider: Provider,
    diagnostics: ProviderDiagnosticsUiModel
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.settings_provider_diagnostics_title),
            style = MaterialTheme.typography.titleSmall,
            color = Primary
        )
        Text(
            text = diagnostics.capabilitySummary,
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceDim
        )
        Text(
            text = stringResource(R.string.diagnostics_summary_format, diagnostics.sourceLabel, diagnostics.connectionSummary),
            style = MaterialTheme.typography.bodySmall,
            color = OnSurface
        )
        Text(
            text = diagnostics.expirySummary,
            style = MaterialTheme.typography.bodySmall,
            color = OnSurface
        )
        Text(
            text = diagnostics.archiveSummary,
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceDim
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ProviderDiagnosticPill(
                title = stringResource(R.string.settings_diagnostic_live),
                count = diagnostics.liveCount,
                timestamp = diagnostics.lastLiveSync
            )
            ProviderDiagnosticPill(
                title = stringResource(R.string.settings_diagnostic_movies),
                count = diagnostics.movieCount,
                timestamp = diagnostics.lastMovieSync
            )
            if (provider.type == ProviderType.XTREAM_CODES) {
                ProviderDiagnosticPill(
                    title = stringResource(R.string.settings_diagnostic_series),
                    count = diagnostics.seriesCount,
                    timestamp = diagnostics.lastSeriesSync
                )
            }
            ProviderDiagnosticPill(
                title = stringResource(R.string.settings_diagnostic_epg),
                count = diagnostics.epgCount,
                timestamp = diagnostics.lastEpgSync
            )
        }
        Text(
            text = stringResource(R.string.settings_diagnostic_status, diagnostics.lastSyncStatus),
            style = MaterialTheme.typography.labelSmall,
            color = OnSurface
        )
    }
}

@Composable
private fun ProviderDiagnosticPill(
    title: String,
    count: Int,
    timestamp: Long
) {
    val syncLabel = remember(timestamp) {
        if (timestamp <= 0L) {
            null
        } else {
            java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(timestamp))
        }
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        colors = SurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.06f)
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = OnSurfaceDim
            )
            Text(
                text = stringResource(R.string.settings_diagnostic_items, count),
                style = MaterialTheme.typography.labelLarge,
                color = OnBackground
            )
            Text(
                text = syncLabel ?: stringResource(R.string.settings_diagnostic_never),
                style = MaterialTheme.typography.labelSmall,
                color = OnSurface
            )
        }
    }
}

@Composable
private fun SettingsOverviewStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(SurfaceHighlight.copy(alpha = 0.28f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label.uppercase(Locale.getDefault()),
            style = MaterialTheme.typography.labelSmall,
            color = OnSurfaceDim
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = OnBackground,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun LiveTvChannelModeDialog(
    selectedMode: LiveTvChannelMode,
    onDismiss: () -> Unit,
    onModeSelected: (LiveTvChannelMode) -> Unit
) {
    PremiumDialog(
        title = stringResource(R.string.settings_live_tv_channel_mode),
        subtitle = stringResource(R.string.settings_live_tv_channel_mode_subtitle),
        onDismissRequest = onDismiss,
        widthFraction = 0.52f,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LiveTvChannelMode.entries.forEach { mode ->
                    Surface(
                        onClick = { onModeSelected(mode) },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (mode == selectedMode) Primary.copy(alpha = 0.18f) else SurfaceElevated,
                            focusedContainerColor = Primary.copy(alpha = 0.28f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = stringResource(mode.labelResId()),
                                style = MaterialTheme.typography.titleSmall,
                                color = if (mode == selectedMode) Primary else OnBackground
                            )
                            Text(
                                text = stringResource(mode.descriptionResId()),
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceDim
                            )
                        }
                    }
                }
            }
        },
        footer = {
            PremiumDialogFooterButton(
                label = stringResource(R.string.settings_cancel),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun SettingsSectionHeader(
    title: String,
    subtitle: String
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = Primary
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceDim
        )
    }
}

@Composable
private fun VodViewModeDialog(
    selectedMode: VodViewMode,
    onDismiss: () -> Unit,
    onModeSelected: (VodViewMode) -> Unit
) {
    PremiumDialog(
        title = stringResource(R.string.settings_vod_view_mode),
        subtitle = stringResource(R.string.settings_vod_view_mode_subtitle),
        onDismissRequest = onDismiss,
        widthFraction = 0.52f,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                VodViewMode.entries.forEach { mode ->
                    Surface(
                        onClick = { onModeSelected(mode) },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (mode == selectedMode) Primary.copy(alpha = 0.18f) else SurfaceElevated,
                            focusedContainerColor = Primary.copy(alpha = 0.28f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = stringResource(mode.labelResId()),
                                style = MaterialTheme.typography.titleSmall,
                                color = if (mode == selectedMode) Primary else OnBackground
                            )
                            Text(
                                text = stringResource(mode.descriptionResId()),
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceDim
                            )
                        }
                    }
                }
            }
        },
        footer = {
            PremiumDialogFooterButton(
                label = stringResource(R.string.settings_cancel),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun LiveChannelNumberingModeDialog(
    selectedMode: ChannelNumberingMode,
    onDismiss: () -> Unit,
    onModeSelected: (ChannelNumberingMode) -> Unit
) {
    PremiumDialog(
        title = stringResource(R.string.settings_live_channel_numbering_mode),
        subtitle = stringResource(R.string.settings_live_channel_numbering_mode_subtitle),
        onDismissRequest = onDismiss,
        widthFraction = 0.52f,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ChannelNumberingMode.entries.forEach { mode ->
                    Surface(
                        onClick = { onModeSelected(mode) },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (mode == selectedMode) Primary.copy(alpha = 0.18f) else SurfaceElevated,
                            focusedContainerColor = Primary.copy(alpha = 0.28f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = stringResource(mode.labelResId()),
                                style = MaterialTheme.typography.titleSmall,
                                color = if (mode == selectedMode) Primary else OnBackground
                            )
                            Text(
                                text = stringResource(mode.descriptionResId()),
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceDim
                            )
                        }
                    }
                }
            }
        },
        footer = {
            PremiumDialogFooterButton(
                label = stringResource(R.string.settings_cancel),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun SettingsRow(label: String, value: String) {
    val focusRequester = remember { FocusRequester() }
    Surface(
        onClick = {},
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Primary.copy(alpha = 0.15f)
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .mouseClickable(
                focusRequester = focusRequester,
                onClick = {}
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium, color = OnSurface)
            Text(text = value, style = MaterialTheme.typography.bodyMedium, color = OnBackground)
        }
    }
}

@Composable
private fun ClickableSettingsRow(label: String, value: String, onClick: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Primary.copy(alpha = 0.15f)
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .mouseClickable(
                focusRequester = focusRequester,
                onClick = onClick
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium, color = OnSurface)
            Text(text = value, style = MaterialTheme.typography.bodyMedium, color = Primary)
        }
    }
}

@Composable
private fun InternetSpeedTestCard(
    valueLabel: String,
    summary: String,
    recommendationLabel: String,
    isRunning: Boolean,
    canApplyRecommendation: Boolean,
    onRunTest: () -> Unit,
    onApplyWifi: () -> Unit,
    onApplyEthernet: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    Surface(
        onClick = onRunTest,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = SurfaceElevated,
            focusedContainerColor = Primary.copy(alpha = 0.18f)
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .focusRequester(focusRequester)
            .mouseClickable(
                focusRequester = focusRequester,
                onClick = onRunTest
            )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.settings_speed_test_title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = OnBackground
                    )
                    Text(
                        text = valueLabel,
                        style = MaterialTheme.typography.titleSmall,
                        color = Primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (isRunning) {
                    CircularProgressIndicator(
                        color = Primary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Text(
                        text = stringResource(R.string.settings_speed_test_run_action),
                        style = MaterialTheme.typography.labelLarge,
                        color = Primary
                    )
                }
            }

            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceDim
            )

            Text(
                text = stringResource(R.string.settings_speed_test_recommendation, recommendationLabel),
                style = MaterialTheme.typography.bodySmall,
                color = OnSurface
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    onClick = onRunTest,
                    enabled = !isRunning,
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Primary.copy(alpha = 0.18f),
                        focusedContainerColor = Primary.copy(alpha = 0.32f)
                    )
                ) {
                    Text(
                        text = stringResource(if (isRunning) R.string.settings_speed_test_running_action else R.string.settings_speed_test_run_action),
                        style = MaterialTheme.typography.labelMedium,
                        color = Primary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
                Surface(
                    onClick = onApplyWifi,
                    enabled = canApplyRecommendation && !isRunning,
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Secondary.copy(alpha = 0.16f),
                        focusedContainerColor = Secondary.copy(alpha = 0.28f)
                    )
                ) {
                    Text(
                        text = stringResource(R.string.settings_speed_test_apply_wifi),
                        style = MaterialTheme.typography.labelMedium,
                        color = Secondary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
                Surface(
                    onClick = onApplyEthernet,
                    enabled = canApplyRecommendation && !isRunning,
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Secondary.copy(alpha = 0.16f),
                        focusedContainerColor = Secondary.copy(alpha = 0.28f)
                    )
                ) {
                    Text(
                        text = stringResource(R.string.settings_speed_test_apply_ethernet),
                        style = MaterialTheme.typography.labelMedium,
                        color = Secondary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun BackupImportPreviewDialog(
    preview: com.streamvault.domain.manager.BackupPreview,
    plan: com.streamvault.domain.manager.BackupImportPlan,
    onDismiss: () -> Unit,
    onStrategySelected: (BackupConflictStrategy) -> Unit,
    onImportPreferencesChanged: (Boolean) -> Unit,
    onImportProvidersChanged: (Boolean) -> Unit,
    onImportSavedLibraryChanged: (Boolean) -> Unit,
    onImportPlaybackHistoryChanged: (Boolean) -> Unit,
    onImportMultiViewChanged: (Boolean) -> Unit,
    onImportRecordingSchedulesChanged: (Boolean) -> Unit,
    onConfirm: () -> Unit
) {
    PremiumDialog(
        title = stringResource(R.string.settings_backup_preview_title),
        subtitle = stringResource(R.string.settings_backup_preview_subtitle, preview.version),
        onDismissRequest = onDismiss,
        widthFraction = 0.58f,
        content = {
            BackupPreviewRow(stringResource(R.string.settings_backup_section_preferences), preview.preferenceCount, 0)
            BackupPreviewRow(stringResource(R.string.settings_backup_section_providers), preview.providerCount, preview.providerConflicts)
            BackupPreviewRow(stringResource(R.string.settings_backup_section_saved), preview.favoriteCount + preview.groupCount + preview.protectedCategoryCount, preview.favoriteConflicts + preview.groupConflicts + preview.protectedCategoryConflicts)
            BackupPreviewRow(stringResource(R.string.settings_backup_section_history), preview.playbackHistoryCount, preview.historyConflicts)
            BackupPreviewRow(stringResource(R.string.settings_backup_section_multiview), preview.multiViewPresetCount, 0)
            BackupPreviewRow(stringResource(R.string.settings_backup_section_recordings), preview.scheduledRecordingCount, preview.recordingConflicts)
            Text(
                text = stringResource(R.string.settings_backup_conflict_strategy),
                style = MaterialTheme.typography.titleSmall,
                color = Primary
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BackupStrategyChip(
                    title = stringResource(R.string.settings_backup_keep_existing),
                    selected = plan.conflictStrategy == BackupConflictStrategy.KEEP_EXISTING,
                    onClick = { onStrategySelected(BackupConflictStrategy.KEEP_EXISTING) }
                )
                BackupStrategyChip(
                    title = stringResource(R.string.settings_backup_replace_existing),
                    selected = plan.conflictStrategy == BackupConflictStrategy.REPLACE_EXISTING,
                    onClick = { onStrategySelected(BackupConflictStrategy.REPLACE_EXISTING) }
                )
            }
            Text(
                text = stringResource(R.string.settings_backup_import_sections),
                style = MaterialTheme.typography.titleSmall,
                color = Primary
            )
            BackupToggleRow(stringResource(R.string.settings_backup_section_preferences), plan.importPreferences, onImportPreferencesChanged)
            BackupToggleRow(stringResource(R.string.settings_backup_section_providers), plan.importProviders, onImportProvidersChanged)
            BackupToggleRow(stringResource(R.string.settings_backup_section_saved), plan.importSavedLibrary, onImportSavedLibraryChanged)
            BackupToggleRow(stringResource(R.string.settings_backup_section_history), plan.importPlaybackHistory, onImportPlaybackHistoryChanged)
            BackupToggleRow(stringResource(R.string.settings_backup_section_multiview), plan.importMultiViewPresets, onImportMultiViewChanged)
            BackupToggleRow(stringResource(R.string.settings_backup_section_recordings), plan.importRecordingSchedules, onImportRecordingSchedulesChanged)
        },
        footer = {
            PremiumDialogFooterButton(
                label = stringResource(R.string.settings_cancel),
                onClick = onDismiss
            )
            PremiumDialogFooterButton(
                label = stringResource(R.string.settings_backup_import_confirm),
                onClick = onConfirm,
                emphasized = true
            )
        }
    )
}

@Composable
private fun BackupPreviewRow(
    title: String,
    itemCount: Int,
    conflictCount: Int
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = OnBackground)
            Text(
                text = if (conflictCount > 0) {
                    stringResource(R.string.settings_backup_conflict_count, conflictCount)
                } else {
                    stringResource(R.string.settings_backup_no_conflicts)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (conflictCount > 0) Secondary else OnSurfaceDim
            )
        }
        Text(
            text = stringResource(R.string.settings_backup_item_count, itemCount),
            style = MaterialTheme.typography.labelLarge,
            color = OnBackground
        )
    }
}

@Composable
private fun BackupStrategyChip(
    title: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(24.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Primary.copy(alpha = 0.2f) else SurfaceElevated,
            focusedContainerColor = Primary.copy(alpha = 0.35f)
        )
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Primary else OnBackground,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun BackupToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = OnBackground)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun RecordingOverviewCard(
    outputDirectory: String?,
    availableBytes: Long?,
    isWritable: Boolean,
    activeCount: Int,
    scheduledCount: Int
) {
    Surface(
        onClick = {},
        modifier = Modifier.fillMaxWidth(),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = SurfaceElevated,
            focusedContainerColor = SurfaceElevated,
            focusedContentColor = OnBackground
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.settings_recording_storage_title),
                style = MaterialTheme.typography.titleSmall,
                color = Primary
            )
            Text(
                text = outputDirectory ?: stringResource(R.string.settings_recording_storage_unknown),
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceDim
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsOverviewStat(
                    label = stringResource(R.string.settings_recording_active_label),
                    value = activeCount.toString(),
                    modifier = Modifier.weight(1f)
                )
                SettingsOverviewStat(
                    label = stringResource(R.string.settings_recording_scheduled_label),
                    value = scheduledCount.toString(),
                    modifier = Modifier.weight(1f)
                )
                SettingsOverviewStat(
                    label = stringResource(R.string.settings_recording_space_label),
                    value = availableBytes?.let(::formatBytes) ?: stringResource(R.string.settings_recording_storage_unknown),
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                text = if (isWritable) stringResource(R.string.settings_recording_storage_ready) else stringResource(R.string.settings_recording_storage_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = if (isWritable) Primary else ErrorColor
            )
        }
    }
}

@Composable
private fun RecordingItemCard(
    item: RecordingItem,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        colors = SurfaceDefaults.colors(containerColor = SurfaceElevated),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.programTitle ?: item.channelName, style = MaterialTheme.typography.titleSmall, color = OnBackground)
                    Text(item.channelName, style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
                }
                Text(
                    text = when (item.status) {
                        RecordingStatus.SCHEDULED -> stringResource(R.string.settings_recording_status_scheduled)
                        RecordingStatus.RECORDING -> stringResource(R.string.settings_recording_status_recording)
                        RecordingStatus.COMPLETED -> stringResource(R.string.settings_recording_status_completed)
                        RecordingStatus.FAILED -> stringResource(R.string.settings_recording_status_failed)
                        RecordingStatus.CANCELLED -> stringResource(R.string.settings_recording_status_cancelled)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = when (item.status) {
                        RecordingStatus.RECORDING -> Primary
                        RecordingStatus.COMPLETED -> OnBackground
                        RecordingStatus.FAILED -> ErrorColor
                        RecordingStatus.CANCELLED -> OnSurfaceDim
                        RecordingStatus.SCHEDULED -> Secondary
                    }
                )
            }
            Text(
                text = stringResource(
                    R.string.settings_recording_time_window,
                    formatTimestamp(item.scheduledStartMs),
                    formatTimestamp(item.scheduledEndMs)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = OnSurface
            )
            if (item.recurrence != RecordingRecurrence.NONE) {
                Text(
                    text = when (item.recurrence) {
                        RecordingRecurrence.DAILY -> stringResource(R.string.settings_recording_recurrence_daily)
                        RecordingRecurrence.WEEKLY -> stringResource(R.string.settings_recording_recurrence_weekly)
                        RecordingRecurrence.NONE -> stringResource(R.string.settings_recording_recurrence_none)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Secondary
                )
            }
            item.failureReason?.takeIf { it.isNotBlank() }?.let { reason ->
                Text(reason, style = MaterialTheme.typography.bodySmall, color = ErrorColor)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (item.status == RecordingStatus.RECORDING) {
                    Surface(
                        onClick = onStop,
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = ErrorColor.copy(alpha = 0.2f),
                            focusedContainerColor = ErrorColor.copy(alpha = 0.35f)
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.settings_recording_stop),
                            color = ErrorColor,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                        )
                    }
                }
                if (item.status == RecordingStatus.SCHEDULED) {
                    Surface(
                        onClick = onCancel,
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = SurfaceHighlight,
                            focusedContainerColor = Primary.copy(alpha = 0.2f)
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.settings_recording_cancel),
                            color = OnBackground,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                        )
                    }
                }
                if (item.status == RecordingStatus.COMPLETED || item.status == RecordingStatus.FAILED || item.status == RecordingStatus.CANCELLED) {
                    Surface(
                        onClick = onDelete,
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = SurfaceHighlight,
                            focusedContainerColor = Primary.copy(alpha = 0.2f)
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.settings_recording_delete),
                            color = OnBackground,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsNavItem(
    label: String,
    badgeChar: String,
    accentColor: Color,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(0.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) Primary.copy(alpha = 0.11f) else Color.Transparent,
            focusedContainerColor = Primary.copy(alpha = 0.22f)
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .mouseClickable(focusRequester = focusRequester, onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(22.dp)
                    .background(
                        color = if (isSelected) Primary else Color.Transparent,
                        shape = RoundedCornerShape(2.dp)
                    )
            )
            Box(
                Modifier
                    .size(28.dp)
                    .background(accentColor.copy(alpha = 0.18f), RoundedCornerShape(7.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = badgeChar,
                    style = MaterialTheme.typography.labelSmall,
                    color = accentColor,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (isSelected) Primary else OnBackground,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
            )
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        bytes >= gb -> String.format(Locale.getDefault(), "%.1f GB", bytes / gb)
        bytes >= mb -> String.format(Locale.getDefault(), "%.1f MB", bytes / mb)
        bytes >= kb -> String.format(Locale.getDefault(), "%.1f KB", bytes / kb)
        else -> "$bytes B"
    }
}

private fun formatTimestamp(timestampMs: Long): String {
    if (timestampMs <= 0L) return "--:--"
    return java.text.SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(java.util.Date(timestampMs))
}

private data class AppLanguageOption(
    val tag: String,
    val label: String
)

private fun supportedAppLanguages(systemDefaultLabel: String): List<AppLanguageOption> {
    val localeTags = listOf("system") + supportedAppLanguageTags()

    return localeTags.map { tag ->
        AppLanguageOption(
            tag = tag,
            label = if (tag == "system") {
                systemDefaultLabel
            } else {
                val locale = localeForLanguageTag(tag)
                locale.getDisplayLanguage(locale)
                    .replaceFirstChar { character ->
                        if (character.isLowerCase()) {
                            character.titlecase(locale)
                        } else {
                            character.toString()
                        }
                    }
            }
        )
    }
}

private fun supportedAudioLanguages(autoLabel: String): List<AppLanguageOption> {
    return buildList {
        add(AppLanguageOption(tag = "auto", label = autoLabel))
        addAll(supportedAppLanguages(autoLabel).filterNot { it.tag == "system" })
    }
}

private data class SubtitleScaleOption(
    val scale: Float,
    val label: (android.content.Context) -> String
)

private data class SubtitleColorOption(
    val colorArgb: Int,
    val label: String
)

private fun subtitleSizeOptions(): List<SubtitleScaleOption> {
    return listOf(
        SubtitleScaleOption(0.85f) { it.getString(R.string.settings_subtitle_size_small) },
        SubtitleScaleOption(1f) { it.getString(R.string.settings_subtitle_size_default) },
        SubtitleScaleOption(1.15f) { it.getString(R.string.settings_subtitle_size_large) },
        SubtitleScaleOption(1.3f) { it.getString(R.string.settings_subtitle_size_extra_large) }
    )
}

private fun subtitleTextColorOptions(context: android.content.Context): List<SubtitleColorOption> {
    return listOf(
        SubtitleColorOption(0xFFFFFFFF.toInt(), context.getString(R.string.settings_subtitle_color_white)),
        SubtitleColorOption(0xFFFFEB3B.toInt(), context.getString(R.string.settings_subtitle_color_yellow)),
        SubtitleColorOption(0xFF80DEEA.toInt(), context.getString(R.string.settings_subtitle_color_cyan)),
        SubtitleColorOption(0xFFA5D6A7.toInt(), context.getString(R.string.settings_subtitle_color_green))
    )
}

private fun subtitleBackgroundColorOptions(context: android.content.Context): List<SubtitleColorOption> {
    return listOf(
        SubtitleColorOption(0x00000000, context.getString(R.string.settings_subtitle_background_transparent)),
        SubtitleColorOption(0x80000000.toInt(), context.getString(R.string.settings_subtitle_background_dim)),
        SubtitleColorOption(0xCC000000.toInt(), context.getString(R.string.settings_subtitle_background_black)),
        SubtitleColorOption(0xCC102A43.toInt(), context.getString(R.string.settings_subtitle_background_blue))
    )
}

private fun displayLanguageLabel(languageTag: String, defaultLabel: String): String {
    if (languageTag.isBlank() || languageTag == "system" || languageTag == "auto") return defaultLabel
    val locale = localeForLanguageTag(languageTag)
    if (locale.language.isBlank()) return defaultLabel
    return locale.getDisplayLanguage(Locale.getDefault())
        .replaceFirstChar { character ->
            if (character.isLowerCase()) {
                character.titlecase(Locale.getDefault())
            } else {
                character.toString()
            }
        }
}

private fun formatPlaybackSpeedLabel(speed: Float): String {
    return if (speed % 1f == 0f) {
        "${speed.toInt()}x"
    } else {
        "${("%.2f".format(Locale.US, speed)).trimEnd('0').trimEnd('.')}x"
    }
}

private fun formatSubtitleSizeLabel(scale: Float, context: android.content.Context): String {
    return subtitleSizeOptions().firstOrNull { it.scale == scale }?.label?.invoke(context)
        ?: context.getString(R.string.settings_subtitle_size_default)
}

private fun formatSubtitleColorLabel(colorArgb: Int, options: List<SubtitleColorOption>): String {
    return options.firstOrNull { it.colorArgb == colorArgb }?.label ?: options.first().label
}

private fun formatQualityCapLabel(maxHeight: Int?, autoLabel: String): String {
    return maxHeight?.let { "${it}p" } ?: autoLabel
}

private fun formatSpeedTestValueLabel(speedTest: InternetSpeedTestUiModel): String {
    return String.format(Locale.getDefault(), "%.1f Mbps", speedTest.megabitsPerSecond)
}

private fun formatSpeedTestSummary(
    speedTest: InternetSpeedTestUiModel,
    context: android.content.Context
): String {
    val transportLabel = when (speedTest.transportLabel) {
        InternetSpeedTestTransport.WIFI.name -> context.getString(R.string.settings_speed_test_transport_wifi)
        InternetSpeedTestTransport.ETHERNET.name -> context.getString(R.string.settings_speed_test_transport_ethernet)
        InternetSpeedTestTransport.CELLULAR.name -> context.getString(R.string.settings_speed_test_transport_cellular)
        InternetSpeedTestTransport.OTHER.name -> context.getString(R.string.settings_speed_test_transport_other)
        else -> context.getString(R.string.settings_speed_test_transport_unknown)
    }
    val measuredAtLabel = formatTimestamp(speedTest.measuredAtMs)
    return if (speedTest.isEstimated) {
        context.getString(R.string.settings_speed_test_summary_estimated, transportLabel, measuredAtLabel)
    } else {
        context.getString(R.string.settings_speed_test_summary_measured, transportLabel, measuredAtLabel)
    }
}

private fun sortModeLabel(mode: CategorySortMode, context: android.content.Context): String {
    return when (mode) {
        CategorySortMode.DEFAULT -> context.getString(R.string.settings_category_sort_default)
        CategorySortMode.TITLE_ASC -> context.getString(R.string.settings_category_sort_az)
        CategorySortMode.TITLE_DESC -> context.getString(R.string.settings_category_sort_za)
        CategorySortMode.COUNT_DESC -> context.getString(R.string.settings_category_sort_most_items)
        CategorySortMode.COUNT_ASC -> context.getString(R.string.settings_category_sort_least_items)
    }
}

private fun formatCategorySortModeLabel(mode: CategorySortMode, context: android.content.Context): String {
    return sortModeLabel(mode, context)
}

private fun categoryTypeLabel(type: ContentType, context: android.content.Context): String {
    return when (type) {
        ContentType.LIVE -> context.getString(R.string.settings_category_sort_live)
        ContentType.MOVIE -> context.getString(R.string.settings_category_sort_movies)
        ContentType.SERIES -> context.getString(R.string.settings_category_sort_series)
        ContentType.SERIES_EPISODE -> context.getString(R.string.settings_category_sort_series)
    }
}

private fun categoryTypeDescription(type: ContentType, context: android.content.Context): String {
    return when (type) {
        ContentType.LIVE -> context.getString(R.string.settings_category_type_live_description)
        ContentType.MOVIE -> context.getString(R.string.settings_category_type_movies_description)
        ContentType.SERIES -> context.getString(R.string.settings_category_type_series_description)
        ContentType.SERIES_EPISODE -> context.getString(R.string.settings_category_type_series_description)
    }
}

private fun LiveTvChannelMode.labelResId(): Int = when (this) {
    LiveTvChannelMode.COMFORTABLE -> R.string.settings_live_tv_mode_comfortable
    LiveTvChannelMode.COMPACT -> R.string.settings_live_tv_mode_compact
    LiveTvChannelMode.PRO -> R.string.settings_live_tv_mode_pro
}

private fun LiveTvChannelMode.descriptionResId(): Int = when (this) {
    LiveTvChannelMode.COMFORTABLE -> R.string.settings_live_tv_mode_comfortable_desc
    LiveTvChannelMode.COMPACT -> R.string.settings_live_tv_mode_compact_desc
    LiveTvChannelMode.PRO -> R.string.settings_live_tv_mode_pro_desc
}

private fun VodViewMode.labelResId(): Int = when (this) {
    VodViewMode.MODERN -> R.string.settings_vod_view_mode_modern
    VodViewMode.CLASSIC -> R.string.settings_vod_view_mode_classic
}

private fun VodViewMode.descriptionResId(): Int = when (this) {
    VodViewMode.MODERN -> R.string.settings_vod_view_mode_modern_desc
    VodViewMode.CLASSIC -> R.string.settings_vod_view_mode_classic_desc
}

private fun ChannelNumberingMode.labelResId(): Int = when (this) {
    ChannelNumberingMode.GROUP -> R.string.settings_live_channel_numbering_group
    ChannelNumberingMode.PROVIDER -> R.string.settings_live_channel_numbering_provider
}

private fun ChannelNumberingMode.descriptionResId(): Int = when (this) {
    ChannelNumberingMode.GROUP -> R.string.settings_live_channel_numbering_group_desc
    ChannelNumberingMode.PROVIDER -> R.string.settings_live_channel_numbering_provider_desc
}

