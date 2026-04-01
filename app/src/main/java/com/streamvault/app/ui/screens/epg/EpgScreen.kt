package com.streamvault.app.ui.screens.epg

import android.view.inputmethod.InputMethodManager
import com.streamvault.app.ui.model.guideLookupKey
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.device.rememberIsTelevisionDevice
import com.streamvault.app.ui.components.ChannelLogoBadge
import com.streamvault.app.navigation.Routes
import com.streamvault.app.ui.components.SelectionChip
import com.streamvault.app.ui.components.SelectionChipRow
import com.streamvault.app.ui.components.dialogs.PinDialog
import com.streamvault.app.ui.components.shell.AppNavigationChrome
import com.streamvault.app.ui.components.shell.AppScreenScaffold
import com.streamvault.app.ui.theme.FocusBorder
import com.streamvault.app.ui.theme.OnBackground
import com.streamvault.app.ui.theme.OnSurface
import com.streamvault.app.ui.theme.OnSurfaceDim
import com.streamvault.app.ui.theme.Primary
import com.streamvault.app.ui.theme.SurfaceElevated
import com.streamvault.app.ui.theme.SurfaceHighlight
import com.streamvault.app.ui.theme.TextPrimary
import com.streamvault.app.ui.theme.TextSecondary
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Program
import com.streamvault.domain.repository.ChannelRepository
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max

private sealed interface LockedGuideAction {
    data class SelectCategory(val category: Category) : LockedGuideAction
    data class OpenProgram(val channel: Channel, val program: Program) : LockedGuideAction
    data class PlayChannel(val channel: Channel, val returnRoute: String) : LockedGuideAction
    data class PlayArchive(val channel: Channel, val program: Program, val returnRoute: String) : LockedGuideAction
}

@Composable
fun FullEpgScreen(
    currentRoute: String,
    initialCategoryId: Long? = null,
    initialAnchorTime: Long? = null,
    initialFavoritesOnly: Boolean = false,
    onPlayChannel: (Channel, String) -> Unit,
    onPlayArchive: (Channel, Program, String) -> Unit,
    onNavigate: (String) -> Unit,
    viewModel: EpgViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedProgram by remember { mutableStateOf<Pair<Channel, Program>?>(null) }
    var focusedChannel by remember { mutableStateOf<Channel?>(null) }
    var focusedProgram by remember { mutableStateOf<Program?>(null) }
    var topNavVisible by rememberSaveable { mutableStateOf(true) }
    var showCategoryPicker by rememberSaveable { mutableStateOf(false) }
    var showGuideOptions by rememberSaveable { mutableStateOf(false) }
    var showSearchOverlay by rememberSaveable { mutableStateOf(false) }
    var showPinDialog by rememberSaveable { mutableStateOf(false) }
    var pinError by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingLockedAction by remember { mutableStateOf<LockedGuideAction?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val now = rememberGuideNow()
    val returnRoute = remember(uiState.selectedCategoryId, uiState.guideAnchorTime, uiState.showFavoritesOnly) {
        Routes.epg(
            categoryId = uiState.selectedCategoryId.takeIf { it != ChannelRepository.ALL_CHANNELS_ID },
            anchorTime = uiState.guideAnchorTime,
            favoritesOnly = uiState.showFavoritesOnly
        )
    }
    val categoriesById = remember(uiState.categories) {
        uiState.categories.associateBy { it.id }
    }

    fun executeLockedGuideAction(action: LockedGuideAction) {
        when (action) {
            is LockedGuideAction.SelectCategory -> viewModel.selectCategory(action.category.id)
            is LockedGuideAction.OpenProgram -> selectedProgram = action.channel to action.program
            is LockedGuideAction.PlayChannel -> onPlayChannel(action.channel, action.returnRoute)
            is LockedGuideAction.PlayArchive -> onPlayArchive(action.channel, action.program, action.returnRoute)
        }
    }

    fun requestLockedGuideAction(action: LockedGuideAction) {
        pendingLockedAction = action
        pinError = null
        showPinDialog = true
    }

    LaunchedEffect(initialCategoryId, initialAnchorTime, initialFavoritesOnly) {
        viewModel.applyNavigationContext(
            categoryId = initialCategoryId,
            anchorTime = initialAnchorTime,
            favoritesOnly = initialFavoritesOnly
        )
    }

    LaunchedEffect(uiState.channels, uiState.programsByChannel, now) {
        if (uiState.channels.isEmpty()) {
            focusedChannel = null
            focusedProgram = null
            return@LaunchedEffect
        }
        val resolvedChannel = focusedChannel?.let { current ->
            uiState.channels.firstOrNull { it.id == current.id }
        } ?: uiState.channels.firstOrNull()
        focusedChannel = resolvedChannel
        val resolvedPrograms = resolvedChannel?.let { channel ->
            channel.guideLookupKey()?.let { lookupKey ->
                uiState.programsByChannel[lookupKey].orEmpty()
            }.orEmpty()
        }.orEmpty()
        focusedProgram = focusedProgram?.let { focused ->
            resolvedPrograms.firstOrNull {
                it.startTime == focused.startTime &&
                    it.endTime == focused.endTime &&
                    it.title == focused.title
            }
        } ?: resolvedPrograms.firstOrNull { now in it.startTime until it.endTime }
    }

    val heroSelection = remember(uiState, focusedChannel, focusedProgram, now) {
        resolveGuideHeroSelection(
            uiState = uiState,
            focusedChannel = focusedChannel,
            focusedProgram = focusedProgram,
            now = now
        )
    }

    AppScreenScaffold(
        currentRoute = currentRoute,
        onNavigate = onNavigate,
        title = stringResource(R.string.nav_epg),
        subtitle = stringResource(R.string.guide_shell_subtitle),
        navigationChrome = AppNavigationChrome.TopBar,
        topBarVisible = topNavVisible,
        compactHeader = true,
        showScreenHeader = false
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            when {
                uiState.isInitialLoading && uiState.channels.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(stringResource(R.string.epg_loading), color = OnBackground)
                    }
                }

                uiState.error != null -> {
                    GuideMessageState(
                        modifier = Modifier.weight(1f),
                        title = when (uiState.error) {
                            EpgViewModel.NO_ACTIVE_PROVIDER -> stringResource(R.string.epg_no_provider)
                            else -> stringResource(R.string.epg_error)
                        },
                        subtitle = when (uiState.error) {
                            EpgViewModel.NO_ACTIVE_PROVIDER -> null
                            else -> stringResource(R.string.epg_retry_hint)
                        },
                        actionLabel = if (uiState.error == EpgViewModel.NO_ACTIVE_PROVIDER) null else stringResource(R.string.epg_retry),
                        onAction = if (uiState.error == EpgViewModel.NO_ACTIVE_PROVIDER) null else viewModel::refresh
                    )
                }

                uiState.channels.isEmpty() -> {
                    GuideMessageState(
                        modifier = Modifier.weight(1f),
                        title = when {
                            uiState.programSearchQuery.isNotBlank() ->
                                stringResource(R.string.epg_no_search_results)
                            uiState.totalChannelCount == 0 && uiState.selectedCategoryId != ChannelRepository.ALL_CHANNELS_ID ->
                                stringResource(R.string.epg_no_channels_in_category)
                            uiState.totalChannelCount == 0 ->
                                stringResource(R.string.epg_no_data)
                            else ->
                                stringResource(R.string.epg_no_scheduled_channels)
                        },
                        subtitle = when {
                            uiState.programSearchQuery.isNotBlank() ->
                                stringResource(R.string.epg_search_empty_hint)
                            uiState.totalChannelCount == 0 ->
                                stringResource(R.string.epg_filter_hint)
                            uiState.showScheduledOnly ->
                                stringResource(R.string.epg_scheduled_only_hint)
                            else ->
                                stringResource(R.string.epg_stale_warning)
                        },
                        actionLabel = if (uiState.programSearchQuery.isNotBlank()) {
                            stringResource(R.string.epg_clear_search)
                        } else {
                            stringResource(R.string.epg_retry)
                        },
                        onAction = if (uiState.programSearchQuery.isNotBlank()) {
                            viewModel::clearProgramSearch
                        } else {
                            viewModel::refresh
                        }
                    )
                }

                else -> {
                    ImmersiveGuideHero(
                        selection = heroSelection,
                        providerLabel = uiState.providerSourceLabel,
                        selectedCategoryName = uiState.categories
                            .firstOrNull { it.id == uiState.selectedCategoryId }
                            ?.name
                            .orEmpty(),
                        isGuideStale = uiState.isGuideStale,
                        channelCount = uiState.totalChannelCount,
                        channelsWithSchedule = uiState.channelsWithSchedule,
                        lastUpdatedAt = uiState.lastUpdatedAt,
                        isRefreshing = uiState.isRefreshing,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 4.dp)
                    )
                    GuideToolbarRow(
                        selectedCategoryName = uiState.categories
                            .firstOrNull { it.id == uiState.selectedCategoryId }
                            ?.name
                            ?: stringResource(R.string.epg_filter_short),
                        onOpenCategoryPicker = {
                            showCategoryPicker = true
                        },
                        onJumpToNow = {
                            viewModel.jumpToNow()
                        },
                        onOpenSearch = {
                            showSearchOverlay = true
                        },
                        onOpenOptions = {
                            showGuideOptions = true
                        },
                        onGuideInteract = { topNavVisible = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp)
                    )
                    if (uiState.isRefreshing) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 4.dp)
                                .height(3.dp),
                            color = Primary,
                            trackColor = SurfaceHighlight
                        )
                    }
                    EpgGrid(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        channels = uiState.channels,
                        favoriteChannelIds = uiState.favoriteChannelIds,
                        programsByChannel = uiState.programsByChannel,
                        guideWindowStart = uiState.guideWindowStart,
                        guideWindowEnd = uiState.guideWindowEnd,
                        density = uiState.selectedDensity,
                        now = now,
                        onChannelClick = { channel ->
                            if (isGuideChannelLocked(channel, categoriesById, uiState.parentalControlLevel)) {
                                requestLockedGuideAction(LockedGuideAction.PlayChannel(channel, returnRoute))
                            } else {
                                onPlayChannel(channel, returnRoute)
                            }
                        },
                        onProgramClick = { channel, program ->
                            topNavVisible = false
                            if (isGuideChannelLocked(channel, categoriesById, uiState.parentalControlLevel)) {
                                requestLockedGuideAction(LockedGuideAction.OpenProgram(channel, program))
                            } else {
                                selectedProgram = channel to program
                            }
                        },
                        onChannelFocused = { channel, currentProgram, isFirstRow ->
                            topNavVisible = isFirstRow
                            focusedChannel = channel
                            focusedProgram = currentProgram
                        },
                        onProgramFocused = { channel, program, isFirstRow ->
                            topNavVisible = isFirstRow
                            focusedChannel = channel
                            focusedProgram = program
                        }
                    )
                }
            }
        }
    }

    if (showCategoryPicker) {
        GuideCategoryPickerDialog(
            categories = uiState.categories,
            selectedCategoryId = uiState.selectedCategoryId,
            parentalControlLevel = uiState.parentalControlLevel,
            onDismiss = { showCategoryPicker = false },
            onCategorySelected = { category ->
                showCategoryPicker = false
                if (isGuideCategoryLocked(category, uiState.parentalControlLevel)) {
                    requestLockedGuideAction(LockedGuideAction.SelectCategory(category))
                } else {
                    viewModel.selectCategory(category.id)
                }
            }
        )
    }

    if (showSearchOverlay) {
        GuideSearchOverlay(
            query = uiState.programSearchQuery,
            onQueryChange = viewModel::updateProgramSearchQuery,
            onClear = viewModel::clearProgramSearch,
            onDismiss = {
                showSearchOverlay = false
            }
        )
    }

    if (showGuideOptions) {
        GuideOptionsOverlay(
            uiState = uiState,
            onDismiss = { showGuideOptions = false },
            onShowAppNavigation = {
                topNavVisible = true
                showGuideOptions = false
            },
            onJumpToPreviousDay = viewModel::jumpToPreviousDay,
            onPageBackward = viewModel::pageBackward,
            onJumpBackwardHalfHour = viewModel::jumpBackwardHalfHour,
            onJumpBackward = viewModel::jumpBackward,
            onJumpToNow = viewModel::jumpToNow,
            onJumpForwardHalfHour = viewModel::jumpForwardHalfHour,
            onJumpForward = viewModel::jumpForward,
            onPageForward = viewModel::pageForward,
            onJumpToPrimeTime = viewModel::jumpToPrimeTime,
            onJumpToTomorrow = viewModel::jumpToTomorrow,
            onJumpToNextDay = viewModel::jumpToNextDay,
            onDaySelected = viewModel::jumpToDay,
            onModeSelected = viewModel::selectChannelMode,
            onDensitySelected = viewModel::selectDensity,
            onToggleScheduledOnly = viewModel::toggleScheduledOnly,
            onToggleFavoritesOnly = viewModel::toggleFavoritesOnly,
            onRefresh = viewModel::refresh
        )
    }

    if (showPinDialog) {
        PinDialog(
            onDismissRequest = {
                showPinDialog = false
                pinError = null
                pendingLockedAction = null
            },
            onPinEntered = { pin ->
                scope.launch {
                    if (viewModel.verifyPin(pin)) {
                        val action = pendingLockedAction
                        val lockedCategoryId = when (action) {
                            is LockedGuideAction.SelectCategory -> action.category.id
                            is LockedGuideAction.OpenProgram -> action.channel.categoryId
                            is LockedGuideAction.PlayChannel -> action.channel.categoryId
                            is LockedGuideAction.PlayArchive -> action.channel.categoryId
                            null -> null
                        }
                        lockedCategoryId?.let(viewModel::unlockCategory)
                        showPinDialog = false
                        pinError = null
                        pendingLockedAction = null
                        action?.let(::executeLockedGuideAction)
                    } else {
                        pinError = context.getString(R.string.home_incorrect_pin)
                    }
                }
            },
            error = pinError
        )
    }

    val dialogState = selectedProgram
    if (dialogState != null) {
        val (channel, program) = dialogState
        CompactGuideProgramDialog(
            channel = channel,
            program = program,
            providerLabel = uiState.providerSourceLabel,
            now = now,
            onDismiss = { selectedProgram = null },
            onWatchLive = {
                selectedProgram = null
                if (isGuideChannelLocked(channel, categoriesById, uiState.parentalControlLevel)) {
                    requestLockedGuideAction(LockedGuideAction.PlayChannel(channel, returnRoute))
                } else {
                    onPlayChannel(channel, returnRoute)
                }
            },
            onWatchArchive = if (program.hasArchive || channel.catchUpSupported) {
                {
                    selectedProgram = null
                    if (isGuideChannelLocked(channel, categoriesById, uiState.parentalControlLevel)) {
                        requestLockedGuideAction(LockedGuideAction.PlayArchive(channel, program, returnRoute))
                    } else {
                        onPlayArchive(channel, program, returnRoute)
                    }
                }
            } else {
                null
            }
        )
    }
}

@Composable
private fun GuideProgramMetadataRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = OnSurfaceDim
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun GuideStatusCard(
    isArchiveReady: Boolean,
    providerCatchUpSupported: Boolean,
    isGuideStale: Boolean
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        colors = SurfaceDefaults.colors(containerColor = SurfaceHighlight)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.epg_program_status_title),
                style = MaterialTheme.typography.titleSmall,
                color = OnSurface
            )
            Text(
                text = when {
                    isArchiveReady -> stringResource(R.string.epg_archive_ready_hint)
                    providerCatchUpSupported -> stringResource(R.string.epg_archive_provider_hint)
                    else -> stringResource(R.string.epg_archive_unavailable_hint)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (isArchiveReady) Primary else OnSurfaceDim
            )
            if (isGuideStale) {
                Text(
                    text = stringResource(R.string.epg_archive_stale_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim
                )
            }
        }
    }
}

@Composable
private fun GuideProviderTroubleshootingCard(
    summary: String,
    channel: Channel,
    program: Program,
    isGuideStale: Boolean
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        colors = SurfaceDefaults.colors(containerColor = SurfaceHighlight.copy(alpha = 0.85f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.epg_provider_troubleshooting_title),
                style = MaterialTheme.typography.titleSmall,
                color = OnSurface
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurface
            )
            val channelReason = when {
                !channel.catchUpSupported && !program.hasArchive ->
                    stringResource(R.string.epg_provider_troubleshooting_no_archive)
                channel.catchUpSupported && channel.catchUpSource.isNullOrBlank() ->
                    stringResource(R.string.epg_provider_troubleshooting_missing_template)
                channel.streamId <= 0L ->
                    stringResource(R.string.epg_provider_troubleshooting_missing_stream_id)
                else ->
                    stringResource(R.string.epg_provider_troubleshooting_ready)
            }
            Text(
                text = channelReason,
                style = MaterialTheme.typography.bodySmall,
                color = if (program.hasArchive) Primary else OnSurfaceDim
            )
            if (isGuideStale) {
                Text(
                    text = stringResource(R.string.epg_provider_troubleshooting_stale),
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim
                )
            }
        }
    }
}

@Composable
private fun rememberGuideNow(): Long {
    val currentTime by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(30_000L)
        }
    }
    return currentTime
}

@Composable
private fun GuideDensityRow(
    selectedDensity: GuideDensity,
    onDensitySelected: (GuideDensity) -> Unit
) {
    SelectionChipRow(
        title = stringResource(R.string.epg_density_label),
        subtitle = stringResource(R.string.epg_density_subtitle),
        chips = listOf(
            SelectionChip(
                key = GuideDensity.COMPACT.name,
                label = stringResource(R.string.epg_density_compact),
                supportingText = stringResource(R.string.epg_density_compact_hint)
            ),
            SelectionChip(
                key = GuideDensity.COMFORTABLE.name,
                label = stringResource(R.string.epg_density_comfortable),
                supportingText = stringResource(R.string.epg_density_comfortable_hint)
            ),
            SelectionChip(
                key = GuideDensity.CINEMATIC.name,
                label = stringResource(R.string.epg_density_cinematic),
                supportingText = stringResource(R.string.epg_density_cinematic_hint)
            )
        ),
        selectedKey = selectedDensity.name,
        onChipSelected = { key ->
            GuideDensity.entries.firstOrNull { it.name == key }?.let(onDensitySelected)
        }
    )
}

@Composable
private fun GuideProgramSearchRow(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onSearch: ((String) -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    autoRequestFocus: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
    showLabel: Boolean = true,
    onSearchFieldActivated: (() -> Unit)? = null
) {
    val resolvedFocusRequester = focusRequester ?: remember { FocusRequester() }
    var localQuery by rememberSaveable { mutableStateOf(query) }
    var refocusToken by rememberSaveable { mutableStateOf(0) }

    LaunchedEffect(query) {
        if (query != localQuery) {
            localQuery = query
        }
    }

    LaunchedEffect(localQuery) {
        if (localQuery == query) return@LaunchedEffect
        if (localQuery.isBlank()) {
            onQueryChange("")
            return@LaunchedEffect
        }
        delay(250)
        if (localQuery != query) {
            onQueryChange(localQuery)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(contentPadding)
    ) {
        if (showLabel) {
            Text(
                text = stringResource(R.string.epg_search_label),
                style = MaterialTheme.typography.labelMedium,
                color = OnSurfaceDim
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GuideSearchField(
                value = localQuery,
                onValueChange = { localQuery = it },
                placeholder = stringResource(R.string.epg_search_placeholder),
                modifier = Modifier.weight(1f),
                focusRequester = resolvedFocusRequester,
                autoRequestFocus = autoRequestFocus,
                refocusToken = refocusToken,
                onSearch = { onSearch?.invoke(localQuery.trim()) },
                onActivated = onSearchFieldActivated
            )
            Box(modifier = Modifier.widthIn(min = 104.dp), contentAlignment = Alignment.CenterEnd) {
                if (localQuery.isNotBlank()) {
                    GuideShortcutChip(
                        label = stringResource(R.string.epg_clear_search),
                        onClick = {
                            localQuery = ""
                            onClear()
                            refocusToken += 1
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun GuideSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester = remember { FocusRequester() },
    autoRequestFocus: Boolean = false,
    refocusToken: Int = 0,
    onSearch: ((String) -> Unit)? = null,
    onActivated: (() -> Unit)? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val context = LocalContext.current
    var pendingKeyboardRequest by remember { mutableStateOf(0) }
    val inputMethodManager = remember(context) {
        context.getSystemService(InputMethodManager::class.java)
    }

    fun requestKeyboard() {
        pendingKeyboardRequest += 1
        onActivated?.invoke()
    }

    LaunchedEffect(autoRequestFocus) {
        if (autoRequestFocus) {
            requestKeyboard()
        }
    }

    LaunchedEffect(refocusToken) {
        if (refocusToken > 0) {
            requestKeyboard()
        }
    }

    LaunchedEffect(pendingKeyboardRequest) {
        if (pendingKeyboardRequest <= 0) return@LaunchedEffect
        focusRequester.requestFocus()
        kotlinx.coroutines.delay(80)
        view.post {
            val focusedView = view.findFocus() ?: view
            focusedView.requestFocus()
            keyboardController?.show()
            inputMethodManager?.showSoftInput(focusedView, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    LaunchedEffect(isFocused) {
        if (!isFocused) return@LaunchedEffect
        kotlinx.coroutines.delay(50)
        view.post {
            val focusedView = view.findFocus() ?: view
            focusedView.requestFocus()
            keyboardController?.show()
            inputMethodManager?.showSoftInput(focusedView, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    Surface(
        onClick = {
            requestKeyboard()
        },
        modifier = modifier.height(40.dp),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isFocused) SurfaceHighlight else SurfaceElevated,
            focusedContainerColor = SurfaceHighlight,
            contentColor = OnSurface,
            focusedContentColor = OnSurface
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, FocusBorder),
                shape = RoundedCornerShape(10.dp)
            )
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.tv.material3.Icon(
                imageVector = androidx.compose.material.icons.Icons.Default.Search,
                contentDescription = null,
                tint = if (isFocused) Primary else OnSurfaceDim
            )
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart
            ) {
                if (value.isBlank()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged {
                            isFocused = it.isFocused
                            if (it.isFocused) {
                                onActivated?.invoke()
                            }
                        },
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = OnSurface),
                    singleLine = true,
                    cursorBrush = SolidColor(Primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                        onSearch?.invoke(value.trim())
                    })
                )
            }
        }
    }
}

@Composable
private fun GuideModeRow(
    selectedMode: GuideChannelMode,
    onModeSelected: (GuideChannelMode) -> Unit
) {
    SelectionChipRow(
        title = stringResource(R.string.epg_mode_label),
        subtitle = stringResource(R.string.epg_mode_subtitle),
        chips = listOf(
            SelectionChip(
                key = GuideChannelMode.ALL.name,
                label = stringResource(R.string.epg_mode_all),
                supportingText = stringResource(R.string.epg_mode_all_hint)
            ),
            SelectionChip(
                key = GuideChannelMode.ANCHORED.name,
                label = stringResource(R.string.epg_mode_anchored),
                supportingText = stringResource(R.string.epg_mode_anchored_hint)
            ),
            SelectionChip(
                key = GuideChannelMode.ARCHIVE_READY.name,
                label = stringResource(R.string.epg_mode_archive),
                supportingText = stringResource(R.string.epg_mode_archive_hint)
            )
        ),
        selectedKey = selectedMode.name,
        onChipSelected = { key ->
            GuideChannelMode.entries
                .firstOrNull { it.name == key }
                ?.let(onModeSelected)
        }
    )
}

@Composable
private fun GuideFilterRow(
    categories: List<Category>,
    selectedCategoryId: Long,
    onCategorySelected: (Long) -> Unit,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp),
    showLabel: Boolean = true
) {
    if (categories.isEmpty()) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(contentPadding)
    ) {
        if (showLabel) {
            Text(
                text = stringResource(R.string.epg_filter_label),
                style = MaterialTheme.typography.labelMedium,
                color = OnSurfaceDim
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(
                items = categories,
                key = { index, category -> epgCategoryKey(category, index) }
            ) { _, category ->
                Surface(
                    onClick = { onCategorySelected(category.id) },
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (category.id == selectedCategoryId) Primary.copy(alpha = 0.18f) else SurfaceElevated,
                        focusedContainerColor = SurfaceHighlight,
                        contentColor = if (category.id == selectedCategoryId) Primary else OnSurface,
                        focusedContentColor = OnSurface
                    ),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(999.dp)),
                    border = ClickableSurfaceDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(2.dp, FocusBorder),
                            shape = RoundedCornerShape(999.dp)
                        )
                    )
                ) {
                    Text(
                        text = category.name,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun GuideTimeControlsRow(
    onJumpToPreviousDay: () -> Unit,
    onPageBackward: () -> Unit,
    onJumpBackwardHalfHour: () -> Unit,
    onJumpBackward: () -> Unit,
    onJumpToNow: () -> Unit,
    onJumpForwardHalfHour: () -> Unit,
    onJumpForward: () -> Unit,
    onPageForward: () -> Unit,
    onJumpToPrimeTime: () -> Unit,
    onJumpToTomorrow: () -> Unit,
    onJumpToNextDay: () -> Unit,
    firstChipFocusRequester: FocusRequester? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
    showLabel: Boolean = true
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(contentPadding)
    ) {
        if (showLabel) {
            Text(
                text = stringResource(R.string.epg_time_controls),
                style = MaterialTheme.typography.labelMedium,
                color = OnSurfaceDim
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                GuideShortcutChip(
                    modifier = firstChipFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier,
                    label = stringResource(R.string.epg_previous_day),
                    onClick = onJumpToPreviousDay
                )
            }
            item {
                GuideShortcutChip(
                    label = stringResource(R.string.epg_page_back),
                    onClick = onPageBackward
                )
            }
            item {
                GuideShortcutChip(
                    label = stringResource(R.string.epg_jump_back_half_hour),
                    onClick = onJumpBackwardHalfHour
                )
            }
            item {
                GuideShortcutChip(
                    label = stringResource(R.string.epg_jump_back),
                    onClick = onJumpBackward
                )
            }
            item {
                GuideShortcutChip(
                    label = stringResource(R.string.epg_jump_now),
                    onClick = onJumpToNow
                )
            }
            item {
                GuideShortcutChip(
                    label = stringResource(R.string.epg_jump_forward_half_hour),
                    onClick = onJumpForwardHalfHour
                )
            }
            item {
                GuideShortcutChip(
                    label = stringResource(R.string.epg_jump_forward),
                    onClick = onJumpForward
                )
            }
            item {
                GuideShortcutChip(
                    label = stringResource(R.string.epg_page_forward),
                    onClick = onPageForward
                )
            }
            item {
                GuideShortcutChip(
                    label = stringResource(R.string.epg_jump_prime_time),
                    onClick = onJumpToPrimeTime
                )
            }
            item {
                GuideShortcutChip(
                    label = stringResource(R.string.epg_jump_tomorrow),
                    onClick = onJumpToTomorrow
                )
            }
            item {
                GuideShortcutChip(
                    label = stringResource(R.string.epg_next_day),
                    onClick = onJumpToNextDay
                )
            }
        }
    }
}

@Composable
private fun GuideDayRow(
    selectedDayStart: Long,
    onDaySelected: (Long) -> Unit,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 4.dp),
    showLabel: Boolean = true
) {
    val dayFormat = remember { SimpleDateFormat("EEE d MMM", Locale.getDefault()) }
    val dayAnchors = remember(selectedDayStart) {
        (-1L..3L).map { offset ->
            selectedDayStart + (offset * EpgViewModel.DAY_SHIFT_MS)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(contentPadding)
    ) {
        if (showLabel) {
            Text(
                text = stringResource(R.string.epg_day_selector_label),
                style = MaterialTheme.typography.labelMedium,
                color = OnSurfaceDim
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(dayAnchors, key = { it }) { dayStart ->
                val isSelected = dayStart == selectedDayStart
                Surface(
                    onClick = { onDaySelected(dayStart) },
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (isSelected) Primary.copy(alpha = 0.18f) else SurfaceElevated,
                        focusedContainerColor = SurfaceHighlight,
                        contentColor = if (isSelected) Primary else OnSurface,
                        focusedContentColor = OnSurface
                    ),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(999.dp)),
                    border = ClickableSurfaceDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(2.dp, FocusBorder),
                            shape = RoundedCornerShape(999.dp)
                        )
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = dayRelativeLabel(dayStart),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            text = dayFormat.format(Date(dayStart)),
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceDim
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideViewOptionsRow(
    showScheduledOnly: Boolean,
    onToggleScheduledOnly: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp)
    ) {
        Text(
            text = stringResource(R.string.epg_view_options_label),
            style = MaterialTheme.typography.labelMedium,
            color = OnSurfaceDim
        )
        Spacer(modifier = Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Surface(
                    onClick = onToggleScheduledOnly,
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (showScheduledOnly) Primary.copy(alpha = 0.18f) else SurfaceElevated,
                        focusedContainerColor = SurfaceHighlight,
                        contentColor = if (showScheduledOnly) Primary else OnSurface,
                        focusedContentColor = OnSurface
                    ),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(999.dp)),
                    border = ClickableSurfaceDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(2.dp, FocusBorder),
                            shape = RoundedCornerShape(999.dp)
                        )
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = stringResource(
                                if (showScheduledOnly) {
                                    R.string.epg_view_scheduled_only_on
                                } else {
                                    R.string.epg_view_scheduled_only_off
                                }
                            ),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            text = stringResource(R.string.epg_view_scheduled_only_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceDim
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideFavoritesRow(
    showFavoritesOnly: Boolean,
    onToggleFavoritesOnly: () -> Unit
) {
    SelectionChipRow(
        title = stringResource(R.string.epg_favorites_filter_title),
        subtitle = stringResource(R.string.epg_favorites_filter_subtitle),
        chips = listOf(
            SelectionChip(
                key = "all",
                label = stringResource(R.string.epg_favorites_filter_all),
                supportingText = stringResource(R.string.epg_favorites_filter_all_hint)
            ),
            SelectionChip(
                key = "favorites",
                label = stringResource(R.string.epg_favorites_filter_favorites),
                supportingText = stringResource(R.string.epg_favorites_filter_favorites_hint)
            )
        ),
        selectedKey = if (showFavoritesOnly) "favorites" else "all",
        onChipSelected = { key ->
            val shouldShowFavorites = key == "favorites"
            if (shouldShowFavorites != showFavoritesOnly) {
                onToggleFavoritesOnly()
            }
        },
        contentPadding = PaddingValues(horizontal = 24.dp)
    )
}

@Composable
private fun GuideShortcutChip(
    modifier: Modifier = Modifier,
    label: String,
    onClick: () -> Unit,
    isSelected: Boolean = false
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) Primary.copy(alpha = 0.18f) else SurfaceElevated,
            focusedContainerColor = SurfaceHighlight,
            contentColor = if (isSelected) Primary else OnSurface,
            focusedContentColor = OnSurface
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(999.dp)),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, FocusBorder),
                shape = RoundedCornerShape(999.dp)
            )
        )
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun GuideOptionsToggleRow(
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp)
    ) {
        Surface(
            onClick = onToggle,
            colors = ClickableSurfaceDefaults.colors(
                containerColor = SurfaceElevated,
                focusedContainerColor = SurfaceHighlight,
                contentColor = OnSurface,
                focusedContentColor = OnSurface
            ),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(999.dp)),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(
                    border = BorderStroke(2.dp, FocusBorder),
                    shape = RoundedCornerShape(999.dp)
                )
            )
        ) {
            Text(
                text = stringResource(if (expanded) R.string.epg_hide_options else R.string.epg_show_options),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
private fun GuideSummaryCard(uiState: EpgUiState) {
    val lastUpdatedLabel = uiState.lastUpdatedAt?.let { timestamp ->
        val minutes = ((System.currentTimeMillis() - timestamp) / 60_000L).coerceAtLeast(0L)
        if (minutes <= 1) {
            stringResource(R.string.epg_updated_now)
        } else {
            stringResource(R.string.epg_updated_minutes_ago, minutes)
        }
    }
    val windowFormat = remember { SimpleDateFormat("EEE HH:mm", Locale.getDefault()) }
    val windowLabel = stringResource(
        R.string.epg_window_label,
        windowFormat.format(Date(uiState.guideWindowStart)),
        windowFormat.format(Date(uiState.guideWindowEnd))
    )

    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            GuideSummaryPill(
                title = stringResource(
                    R.string.epg_showing_channels,
                    uiState.channels.size,
                    uiState.totalChannelCount
                ),
                subtitle = stringResource(
                    R.string.epg_schedule_summary_short,
                    uiState.channelsWithSchedule,
                    uiState.channels.size
                )
            )
        }
        item {
            GuideSummaryPill(
                title = windowLabel,
                subtitle = lastUpdatedLabel ?: stringResource(R.string.epg_updated_now)
            )
        }
        if (uiState.isGuideStale) {
            item {
                GuideSummaryPill(
                    title = stringResource(R.string.epg_stale_short),
                    subtitle = uiState.providerSourceLabel.ifBlank { stringResource(R.string.nav_epg) },
                    accent = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

private data class GuideHeroSelection(
    val channel: Channel,
    val program: Program?,
    val isFallbackToChannel: Boolean
)

private fun resolveGuideHeroSelection(
    uiState: EpgUiState,
    focusedChannel: Channel?,
    focusedProgram: Program?,
    now: Long
): GuideHeroSelection? {
    val resolvedChannel = focusedChannel
        ?.let { current -> uiState.channels.firstOrNull { it.id == current.id } }
        ?: uiState.channels.firstOrNull()
        ?: return null
    val programs = resolvedChannel.guideLookupKey()?.let { lookupKey ->
        uiState.programsByChannel[lookupKey].orEmpty()
    }.orEmpty()
    val resolvedProgram = focusedProgram?.let { focused ->
        programs.firstOrNull {
            it.startTime == focused.startTime &&
                it.endTime == focused.endTime &&
                it.title == focused.title
        }
    } ?: programs.firstOrNull { now in it.startTime until it.endTime } ?: programs.firstOrNull()
    return GuideHeroSelection(
        channel = resolvedChannel,
        program = resolvedProgram,
        isFallbackToChannel = resolvedProgram == null
    )
}

@Composable
private fun ImmersiveGuideHero(
    selection: GuideHeroSelection?,
    providerLabel: String,
    selectedCategoryName: String,
    isGuideStale: Boolean,
    channelCount: Int,
    channelsWithSchedule: Int,
    lastUpdatedAt: Long?,
    isRefreshing: Boolean,
    modifier: Modifier = Modifier
) {
    val format = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val currentTime = System.currentTimeMillis()
    val lastUpdatedLabel = remember(lastUpdatedAt, currentTime) {
        lastUpdatedAt?.let { updatedAt ->
            val minutes = ((currentTime - updatedAt).coerceAtLeast(0L) / 60_000L).toInt()
            if (minutes <= 0) {
                "Updated just now"
            } else {
                "$minutes min ago"
            }
        }
    }

    Surface(
        modifier = modifier.height(96.dp),
        colors = SurfaceDefaults.colors(containerColor = SurfaceElevated),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val channel = selection?.channel
            ChannelLogoBadge(
                channelName = channel?.name ?: stringResource(R.string.epg_title),
                logoUrl = channel?.logoUrl,
                modifier = Modifier
                    .width(54.dp)
                    .height(54.dp),
                shape = RoundedCornerShape(12.dp)
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                val program = selection?.program
                Text(
                    text = program?.title ?: channel?.name ?: stringResource(R.string.epg_title),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 15.sp,
                        lineHeight = 18.sp
                    ),
                    color = OnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        channel?.let { append("${it.number}. ${it.name}") }
                        if (providerLabel.isNotBlank()) {
                            if (isNotEmpty()) append("  |  ")
                            append(providerLabel)
                        }
                        if (selectedCategoryName.isNotBlank()) {
                            if (isNotEmpty()) append("  |  ")
                            append(selectedCategoryName)
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (program != null) {
                    Text(
                        text = "${format.format(Date(program.startTime))} - ${format.format(Date(program.endTime))}",
                        style = MaterialTheme.typography.labelSmall.copy(lineHeight = 11.sp),
                        color = OnSurface
                    )
                    if (currentTime in program.startTime until program.endTime) {
                        LinearProgressIndicator(
                            progress = { ((currentTime - program.startTime).toFloat() / (program.endTime - program.startTime).toFloat()).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp),
                            color = Primary,
                            trackColor = SurfaceHighlight
                        )
                    }
                    Text(
                        text = program.description.ifBlank { stringResource(R.string.epg_hero_no_program_description) },
                        style = MaterialTheme.typography.labelSmall,
                        color = OnSurfaceDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Text(
                        text = stringResource(R.string.epg_no_schedule),
                        style = MaterialTheme.typography.labelLarge,
                        color = OnSurface
                    )
                    Text(
                        text = stringResource(R.string.epg_hero_no_schedule_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = OnSurfaceDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Column(
                modifier = Modifier.widthIn(min = 176.dp, max = 220.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                GuideHeroBadge(text = stringResource(R.string.epg_schedule_summary_short, channelsWithSchedule, channelCount))
                if (isRefreshing) {
                    GuideHeroBadge(text = stringResource(R.string.epg_loading))
                }
                if (selection?.program?.hasArchive == true || selection?.channel?.catchUpSupported == true) {
                    GuideHeroBadge(
                        text = if (selection.program?.hasArchive == true) {
                            stringResource(R.string.epg_program_replay_ready)
                        } else {
                            stringResource(R.string.epg_program_replay_partial)
                        },
                        highlight = true
                    )
                }
                if (isGuideStale) {
                    GuideHeroBadge(
                        text = stringResource(R.string.epg_stale_short),
                        accentColor = Color(0xFFFF6B6B)
                    )
                }
                if (!lastUpdatedLabel.isNullOrBlank()) {
                    Text(
                        text = lastUpdatedLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = OnSurfaceDim
                    )
                }
            }
        }
    }
}

@Composable
private fun GuideHeroBadge(
    text: String,
    highlight: Boolean = false,
    accentColor: Color = Primary
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        colors = SurfaceDefaults.colors(
            containerColor = if (highlight) accentColor.copy(alpha = 0.18f) else SurfaceHighlight
        )
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (highlight) accentColor else OnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun GuideToolbarRow(
    selectedCategoryName: String,
    onOpenCategoryPicker: () -> Unit,
    onJumpToNow: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenOptions: () -> Unit,
    onGuideInteract: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GuideToolbarButton(
            label = selectedCategoryName,
            modifier = Modifier.widthIn(min = 200.dp, max = 280.dp),
            onClick = onOpenCategoryPicker,
            onFocused = onGuideInteract
        )
        GuideToolbarButton(
            label = stringResource(R.string.epg_jump_now),
            onClick = onJumpToNow,
            onFocused = onGuideInteract
        )
        GuideToolbarButton(
            label = stringResource(R.string.epg_search_label),
            onClick = onOpenSearch,
            onFocused = onGuideInteract
        )
        GuideToolbarButton(
            label = stringResource(R.string.epg_options_short),
            onClick = onOpenOptions,
            onFocused = onGuideInteract
        )
    }
}

@Composable
private fun GuideToolbarButton(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onFocused: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier.onFocusChanged {
            if (it.isFocused && !focused) onFocused()
            focused = it.isFocused
        },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = SurfaceElevated,
            focusedContainerColor = SurfaceHighlight
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, FocusBorder),
                shape = RoundedCornerShape(14.dp)
            )
        )
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium,
            color = OnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun GuideModalDialog(
    onDismiss: () -> Unit,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = contentAlignment
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.68f))
                    .clickable(
                        onClick = onDismiss,
                        indication = null,
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    )
            )
            content()
        }
    }
}

@Composable
private fun GuideSearchOverlay(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    val searchFocusRequester = remember { FocusRequester() }
    val applySearchAndClose = remember(onQueryChange, onDismiss) {
        { submittedQuery: String ->
            onQueryChange(submittedQuery)
            onDismiss()
        }
    }
    val clearAndClose = remember(onClear, onDismiss) {
        {
            onClear()
            onDismiss()
        }
    }

    LaunchedEffect(Unit) {
        searchFocusRequester.requestFocus()
    }

    GuideModalDialog(
        onDismiss = onDismiss,
        contentAlignment = Alignment.TopCenter
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .padding(top = 32.dp)
                .focusGroup(),
            colors = SurfaceDefaults.colors(containerColor = SurfaceElevated),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.epg_search_label),
                        style = MaterialTheme.typography.titleLarge,
                        color = OnSurface
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GuideShortcutChip(
                            label = stringResource(R.string.epg_search_apply),
                            onClick = { applySearchAndClose(query.trim()) }
                        )
                        GuideShortcutChip(
                            label = stringResource(R.string.epg_clear_search_close),
                            onClick = clearAndClose
                        )
                    }
                }
                GuideProgramSearchRow(
                    query = query,
                    onQueryChange = onQueryChange,
                    onClear = onClear,
                    onSearch = applySearchAndClose,
                    focusRequester = searchFocusRequester,
                    autoRequestFocus = true,
                    contentPadding = PaddingValues(0.dp),
                    showLabel = false
                )
            }
        }
    }
}

@Composable
private fun GuideOptionsOverlay(
    uiState: EpgUiState,
    onDismiss: () -> Unit,
    onShowAppNavigation: () -> Unit,
    onJumpToPreviousDay: () -> Unit,
    onPageBackward: () -> Unit,
    onJumpBackwardHalfHour: () -> Unit,
    onJumpBackward: () -> Unit,
    onJumpToNow: () -> Unit,
    onJumpForwardHalfHour: () -> Unit,
    onJumpForward: () -> Unit,
    onPageForward: () -> Unit,
    onJumpToPrimeTime: () -> Unit,
    onJumpToTomorrow: () -> Unit,
    onJumpToNextDay: () -> Unit,
    onDaySelected: (Long) -> Unit,
    onModeSelected: (GuideChannelMode) -> Unit,
    onDensitySelected: (GuideDensity) -> Unit,
    onToggleScheduledOnly: () -> Unit,
    onToggleFavoritesOnly: () -> Unit,
    onRefresh: () -> Unit
) {
    val optionsFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        optionsFocusRequester.requestFocus()
    }
    GuideModalDialog(onDismiss = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.68f)
                .fillMaxHeight(0.78f)
                .focusGroup(),
            colors = SurfaceDefaults.colors(containerColor = SurfaceElevated),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.epg_options_short),
                        style = MaterialTheme.typography.headlineSmall,
                        color = OnSurface
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GuideShortcutChip(
                            label = stringResource(R.string.epg_show_app_navigation),
                            onClick = onShowAppNavigation
                        )
                        GuideShortcutChip(
                            label = stringResource(R.string.settings_cancel),
                            onClick = onDismiss
                        )
                    }
                }
                GuideTimeControlsRow(
                    onJumpToPreviousDay = onJumpToPreviousDay,
                    onPageBackward = onPageBackward,
                    onJumpBackwardHalfHour = onJumpBackwardHalfHour,
                    onJumpBackward = onJumpBackward,
                    onJumpToNow = onJumpToNow,
                    onJumpForwardHalfHour = onJumpForwardHalfHour,
                    onJumpForward = onJumpForward,
                    onPageForward = onPageForward,
                    onJumpToPrimeTime = onJumpToPrimeTime,
                    onJumpToTomorrow = onJumpToTomorrow,
                    onJumpToNextDay = onJumpToNextDay,
                    firstChipFocusRequester = optionsFocusRequester
                )
                GuideDayRow(
                    selectedDayStart = startOfDay(uiState.guideWindowStart + EpgViewModel.LOOKBACK_MS),
                    onDaySelected = onDaySelected
                )
                GuideModeRow(
                    selectedMode = uiState.selectedChannelMode,
                    onModeSelected = onModeSelected
                )
                GuideDensityRow(
                    selectedDensity = uiState.selectedDensity,
                    onDensitySelected = onDensitySelected
                )
                GuideViewOptionsRow(
                    showScheduledOnly = uiState.showScheduledOnly,
                    onToggleScheduledOnly = onToggleScheduledOnly
                )
                GuideFavoritesRow(
                    showFavoritesOnly = uiState.showFavoritesOnly,
                    onToggleFavoritesOnly = onToggleFavoritesOnly
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    GuideShortcutChip(
                        label = stringResource(R.string.epg_refresh_guide),
                        onClick = onRefresh
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactGuideProgramDialog(
    channel: Channel,
    program: Program,
    providerLabel: String,
    now: Long,
    onDismiss: () -> Unit,
    onWatchLive: () -> Unit,
    onWatchArchive: (() -> Unit)?
) {
    var showDetails by rememberSaveable(program.startTime, program.endTime, program.title) { mutableStateOf(false) }
    val format = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    GuideModalDialog(onDismiss = onDismiss) {
        Surface(
            modifier = Modifier.widthIn(min = 420.dp, max = 640.dp),
            colors = SurfaceDefaults.colors(containerColor = SurfaceElevated),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = program.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = OnSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${channel.number}. ${channel.name}  |  ${format.format(Date(program.startTime))} - ${format.format(Date(program.endTime))}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfaceDim,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (providerLabel.isNotBlank()) {
                    Text(
                        text = providerLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = Primary
                    )
                }
                if (now in program.startTime until program.endTime) {
                    LinearProgressIndicator(
                        progress = { ((now - program.startTime).toFloat() / (program.endTime - program.startTime).toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                        color = Primary,
                        trackColor = SurfaceHighlight
                    )
                }
                if (showDetails) {
                    Text(
                        text = program.description.ifBlank { stringResource(R.string.epg_no_info) },
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurface,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onWatchLive,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.epg_watch_live))
                    }
                    if (onWatchArchive != null) {
                        Button(
                            onClick = onWatchArchive,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.colors(
                                containerColor = Primary,
                                contentColor = Color.White
                            )
                        ) {
                            Text(stringResource(R.string.epg_watch_archive))
                        }
                    }
                    Button(
                        onClick = { showDetails = !showDetails },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.colors(
                            containerColor = SurfaceHighlight,
                            contentColor = OnSurface
                        )
                    ) {
                        Text(
                            if (showDetails) stringResource(R.string.epg_program_details_hide)
                            else stringResource(R.string.epg_program_details_show)
                        )
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.settings_cancel))
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideSummaryPill(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    accent: Color = Primary
) {
    Surface(
        modifier = modifier,
        colors = SurfaceDefaults.colors(containerColor = SurfaceHighlight),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = OnSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = accent
            )
        }
    }
}

@Composable
private fun GuideMessageState(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String?,
    actionLabel: String?,
    onAction: (() -> Unit)?
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = OnSurface
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfaceDim
                )
            }
            if (actionLabel != null && onAction != null) {
                Button(
                    onClick = onAction,
                    colors = ButtonDefaults.colors(
                        containerColor = Primary,
                        contentColor = Color.White
                    )
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
fun EpgGrid(
    modifier: Modifier = Modifier,
    channels: List<Channel>,
    favoriteChannelIds: Set<Long>,
    programsByChannel: Map<String, List<Program>>,
    guideWindowStart: Long,
    guideWindowEnd: Long,
    density: GuideDensity,
    now: Long,
    onChannelClick: (Channel) -> Unit,
    onProgramClick: (Channel, Program) -> Unit,
    onChannelFocused: (Channel, Program?, Boolean) -> Unit,
    onProgramFocused: (Channel, Program, Boolean) -> Unit
) {
    val channelRailWidth = 180.dp
    val timelineGap = 4.dp
    val rowHeight = when (density) {
        GuideDensity.COMPACT -> 38.dp
        GuideDensity.COMFORTABLE -> 44.dp
        GuideDensity.CINEMATIC -> 52.dp
    }
    val horizontalScrollState = rememberScrollState()

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 2.dp)
    ) {
        val timelineViewportWidth = (maxWidth - channelRailWidth - timelineGap).coerceAtLeast(640.dp)
        val totalDuration = (guideWindowEnd - guideWindowStart).coerceAtLeast(1L)
        val visibleDurationMs = 3 * 60 * 60 * 1000L
        val calculatedTimelineWidth = timelineViewportWidth * (totalDuration.toFloat() / visibleDurationMs.toFloat())
        val totalTimelineWidth = if (calculatedTimelineWidth > timelineViewportWidth) {
            calculatedTimelineWidth
        } else {
            timelineViewportWidth
        }
        val markerStepMs = EpgViewModel.HALF_HOUR_SHIFT_MS

        Column(modifier = Modifier.fillMaxSize()) {
            GuideTimelineHeader(
                windowStart = guideWindowStart,
                windowEnd = guideWindowEnd,
                now = now,
                channelRailWidth = channelRailWidth,
                timelineGap = timelineGap,
                timelineViewportWidth = timelineViewportWidth,
                totalTimelineWidth = totalTimelineWidth,
                markerStepMs = markerStepMs,
                scrollState = horizontalScrollState
            )
            Spacer(modifier = Modifier.height(4.dp))
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                itemsIndexed(
                    items = channels,
                    key = { index, channel -> epgChannelKey(channel, index) }
                ) { index, channel ->
                    val programs = channel.guideLookupKey()?.let { lookupKey ->
                        programsByChannel[lookupKey].orEmpty()
                    }.orEmpty()
                    val isFirstRow = index == 0
                    EpgRow(
                        channel = channel,
                        isFavorite = channel.id in favoriteChannelIds,
                        programs = programs,
                        now = now,
                        windowStart = guideWindowStart,
                        windowEnd = guideWindowEnd,
                        channelRailWidth = channelRailWidth,
                        timelineGap = timelineGap,
                        timelineViewportWidth = timelineViewportWidth,
                        totalTimelineWidth = totalTimelineWidth,
                        density = density,
                        rowHeight = rowHeight,
                        markerStepMs = markerStepMs,
                        scrollState = horizontalScrollState,
                        onChannelClick = { onChannelClick(channel) },
                        onChannelFocused = { onChannelFocused(channel, it, isFirstRow) },
                        onProgramClick = { program -> onProgramClick(channel, program) },
                        onProgramFocused = { program -> onProgramFocused(channel, program, isFirstRow) }
                    )
                }
            }
        }
    }
}

@Composable
private fun GuideTimelineHeader(
    windowStart: Long,
    windowEnd: Long,
    now: Long,
    channelRailWidth: Dp,
    timelineGap: Dp,
    timelineViewportWidth: Dp,
    totalTimelineWidth: Dp,
    markerStepMs: Long,
    scrollState: androidx.compose.foundation.ScrollState
) {
    val hourFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val totalDuration = (windowEnd - windowStart).coerceAtLeast(1L)
    val clampedNow = now.coerceIn(windowStart, windowEnd)
    val elapsedRatio = ((clampedNow - windowStart).toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)
    val markerLabel = if (now in windowStart..windowEnd) {
        stringResource(R.string.epg_now_marker, hourFormat.format(Date(now)))
    } else {
        stringResource(R.string.epg_outside_window)
    }
    val hourMarkers = buildList {
        var marker = windowStart
        while (marker <= windowEnd) {
            add(marker)
            marker += markerStepMs
        }
        if (lastOrNull() != windowEnd) {
            add(windowEnd)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Spacer(modifier = Modifier.width(channelRailWidth + timelineGap))
        Column(
            modifier = Modifier.width(timelineViewportWidth),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.epg_timeline_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = OnSurfaceDim
                )
                Text(
                    text = stringResource(
                        R.string.epg_timeline_range_label,
                        formatWindowDuration(totalDuration)
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = OnSurfaceDim
                )
            }
            Box(
                modifier = Modifier
                    .width(timelineViewportWidth)
                    .height(20.dp)
            ) {
                Row(
                    modifier = Modifier
                        .width(totalTimelineWidth)
                        .horizontalScroll(scrollState)
                ) {
                    Box(
                        modifier = Modifier
                            .width(totalTimelineWidth)
                            .height(20.dp)
                    ) {
                        hourMarkers.forEach { marker ->
                            val markerRatio = ((marker - windowStart).toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)
                            val markerOffset = totalTimelineWidth * markerRatio
                            Column(
                                modifier = Modifier.padding(start = markerOffset),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = hourFormat.format(Date(marker)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = OnSurfaceDim
                                )
                                Box(
                                    modifier = Modifier
                                        .width(1.dp)
                                        .height(8.dp)
                                        .background(Color.White.copy(alpha = 0.16f))
                                )
                            }
                        }
                        if (now in windowStart..windowEnd) {
                            Box(
                                modifier = Modifier
                                    .padding(start = totalTimelineWidth * elapsedRatio)
                                    .width(2.dp)
                                    .fillMaxHeight()
                                    .background(Primary)
                            )
                        }
                    }
                }
            }
            Text(
                text = markerLabel,
                style = MaterialTheme.typography.labelSmall,
                color = if (now in windowStart..windowEnd) Primary else OnSurfaceDim
            )
        }
    }
}

@Composable
fun EpgRow(
    channel: Channel,
    isFavorite: Boolean,
    programs: List<Program>,
    now: Long,
    windowStart: Long,
    windowEnd: Long,
    channelRailWidth: Dp,
    timelineGap: Dp,
    timelineViewportWidth: Dp,
    totalTimelineWidth: Dp,
    density: GuideDensity,
    rowHeight: Dp,
    markerStepMs: Long,
    scrollState: androidx.compose.foundation.ScrollState,
    onChannelClick: () -> Unit,
    onChannelFocused: (Program?) -> Unit,
    onProgramClick: (Program) -> Unit,
    onProgramFocused: (Program) -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val totalDuration = (windowEnd - windowStart).coerceAtLeast(1L)
    val currentProgram = programs.firstOrNull { now in it.startTime..it.endTime }
    val channelPaddingVertical = when (density) {
        GuideDensity.COMPACT -> 3.dp
        GuideDensity.COMFORTABLE -> 4.dp
        GuideDensity.CINEMATIC -> 5.dp
    }
    val channelLogoSize = when (density) {
        GuideDensity.COMPACT -> 22.dp
        GuideDensity.COMFORTABLE -> 24.dp
        GuideDensity.CINEMATIC -> 26.dp
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            onClick = onChannelClick,
            modifier = Modifier
                .width(channelRailWidth)
                .fillMaxHeight()
                .onFocusChanged {
                    if (it.isFocused && !isFocused) {
                        onChannelFocused(currentProgram)
                    }
                    isFocused = it.isFocused
                },
            colors = ClickableSurfaceDefaults.colors(
                containerColor = SurfaceElevated,
                focusedContainerColor = SurfaceHighlight
            ),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(
                    border = BorderStroke(2.dp, FocusBorder),
                    shape = RoundedCornerShape(8.dp)
                )
            )
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 7.dp, vertical = channelPaddingVertical)
                    .fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ChannelLogoBadge(
                    channelName = channel.name,
                    logoUrl = channel.logoUrl,
                    modifier = Modifier
                        .width(channelLogoSize)
                        .height(channelLogoSize),
                    shape = RoundedCornerShape(6.dp)
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    Text(
                        text = "${channel.number}. ${channel.name}",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isFocused) TextPrimary else OnSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = currentProgram?.title ?: stringResource(R.string.epg_no_schedule_short),
                        style = MaterialTheme.typography.labelSmall,
                        color = OnSurfaceDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (isFavorite || channel.catchUpSupported) {
                    Box(
                        modifier = Modifier
                            .background(
                                color = Primary.copy(alpha = 0.16f),
                                shape = CircleShape
                            )
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = if (channel.catchUpSupported) stringResource(R.string.player_archive_badge) else stringResource(R.string.epg_favorite_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = Primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.width(timelineGap))

        Box(
            modifier = Modifier
                .width(timelineViewportWidth)
                .fillMaxHeight()
                .background(SurfaceElevated, RoundedCornerShape(8.dp))
                .clip(RoundedCornerShape(8.dp))
        ) {
            Row(
                modifier = Modifier
                    .width(totalTimelineWidth)
                    .horizontalScroll(scrollState)
            ) {
                Box(
                    modifier = Modifier
                        .width(totalTimelineWidth)
                        .fillMaxHeight()
                ) {
                val markers = remember(windowStart, windowEnd, markerStepMs) {
                    buildList {
                        var marker = windowStart
                        while (marker <= windowEnd) {
                            add(marker)
                            marker += markerStepMs
                        }
                    }
                }
                markers.forEach { marker ->
                    val markerRatio = ((marker - windowStart).toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .padding(start = totalTimelineWidth * markerRatio)
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(Color.White.copy(alpha = 0.08f))
                    )
                }
                if (now in windowStart..windowEnd) {
                    val nowRatio = ((now - windowStart).toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .padding(start = totalTimelineWidth * nowRatio)
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(Primary)
                    )
                }
                if (programs.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.epg_no_schedule_short), color = OnSurfaceDim)
                    }
                } else {
                    programs.forEach { program ->
                        ProgramItem(
                            program = program,
                            density = density,
                            isCurrent = now in program.startTime until program.endTime,
                            now = now,
                            windowStart = windowStart,
                            windowEnd = windowEnd,
                            totalTimelineWidth = totalTimelineWidth,
                            onClick = { onProgramClick(program) },
                            onFocused = { onProgramFocused(program) }
                        )
                    }
                }
                }
            }
        }
    }
}

@Composable
fun ProgramItem(
    program: Program,
    density: GuideDensity,
    isCurrent: Boolean,
    now: Long,
    windowStart: Long,
    windowEnd: Long,
    totalTimelineWidth: Dp,
    onClick: () -> Unit,
    onFocused: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    val format = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val startStr = format.format(Date(program.startTime))
    val endStr = format.format(Date(program.endTime))
    val totalDuration = (windowEnd - windowStart).coerceAtLeast(1L)
    val visibleStart = max(program.startTime, windowStart)
    val visibleEnd = max(visibleStart + 1, minOf(program.endTime, windowEnd))
    val startRatio = ((visibleStart - windowStart).toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)
    val widthRatio = ((visibleEnd - visibleStart).toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)
    val itemStart = totalTimelineWidth * startRatio
    val minimumItemWidth = when (density) {
        GuideDensity.COMPACT -> 40.dp
        GuideDensity.COMFORTABLE -> 48.dp
        GuideDensity.CINEMATIC -> 56.dp
    }
    val itemWidth = (totalTimelineWidth * widthRatio).coerceAtLeast(minimumItemWidth)
    val isCompactCell = itemWidth < 148.dp
    val isVeryCompactCell = itemWidth < 116.dp
    val outerVerticalPadding = when (density) {
        GuideDensity.COMPACT -> 2.dp
        GuideDensity.COMFORTABLE -> 2.dp
        GuideDensity.CINEMATIC -> 3.dp
    }
    val innerHorizontalPadding = when {
        isVeryCompactCell -> 5.dp
        isCompactCell -> 6.dp
        else -> 8.dp
    }
    val innerVerticalPadding = when (density) {
        GuideDensity.COMPACT -> 2.dp
        GuideDensity.COMFORTABLE -> 3.dp
        GuideDensity.CINEMATIC -> 4.dp
    }
    val titleStyle = when {
        isVeryCompactCell -> MaterialTheme.typography.labelSmall.copy(
            fontSize = 10.sp,
            lineHeight = 11.sp
        )
        isCompactCell || density == GuideDensity.COMPACT -> MaterialTheme.typography.labelMedium.copy(
            fontSize = 11.sp,
            lineHeight = 12.sp
        )
        else -> MaterialTheme.typography.labelLarge.copy(
            fontSize = 12.sp,
            lineHeight = 14.sp
        )
    }
    val timeStyle = when {
        isCompactCell || density == GuideDensity.COMPACT -> MaterialTheme.typography.labelSmall.copy(
            fontSize = 9.sp,
            lineHeight = 10.sp
        )
        else -> MaterialTheme.typography.labelSmall.copy(
            fontSize = 10.sp,
            lineHeight = 11.sp
        )
    }

    Surface(
        onClick = onClick,
        modifier = Modifier
            .padding(start = itemStart, top = outerVerticalPadding, bottom = outerVerticalPadding)
            .width(itemWidth)
            .fillMaxHeight()
            .clip(RoundedCornerShape(8.dp))
            .onFocusChanged {
                if (it.isFocused && !isFocused) {
                    onFocused()
                }
                isFocused = it.isFocused
            },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isCurrent) Primary.copy(alpha = 0.2f) else SurfaceElevated,
            focusedContainerColor = SurfaceHighlight
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, FocusBorder),
                shape = RoundedCornerShape(8.dp)
            )
        )
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = innerHorizontalPadding, vertical = innerVerticalPadding)
                .fillMaxSize(),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = program.title,
                style = titleStyle,
                color = if (isFocused) TextPrimary else if (isCurrent) Primary else OnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!isVeryCompactCell) {
                Text(
                    text = "$startStr - $endStr",
                    style = timeStyle,
                    color = if (isFocused) TextSecondary else OnSurfaceDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun formatWindowDuration(durationMs: Long): String {
    val hours = durationMs / (60 * 60 * 1000L)
    val minutes = (durationMs / (60 * 1000L)) % 60
    return if (minutes == 0L) {
        "${hours}h"
    } else {
        "${hours}h ${minutes}m"
    }
}

private fun startOfDay(timestamp: Long): Long {
    val calendar = Calendar.getInstance().apply {
        timeInMillis = timestamp
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return calendar.timeInMillis
}

@Composable
private fun GuideHeaderDeck(
    uiState: EpgUiState,
    showSearchBar: Boolean,
    showAdvancedOptions: Boolean,
    onToggleSearch: () -> Unit,
    onOpenCategoryPicker: () -> Unit,
    onToggleAdvancedOptions: () -> Unit,
    onProgramSearchQueryChange: (String) -> Unit,
    onClearProgramSearch: () -> Unit,
    onJumpToPreviousDay: () -> Unit,
    onJumpBackward: () -> Unit,
    onJumpToNow: () -> Unit,
    onJumpForward: () -> Unit,
    onJumpToPrimeTime: () -> Unit,
    onJumpToNextDay: () -> Unit,
    onPageBackward: () -> Unit,
    onJumpBackwardHalfHour: () -> Unit,
    onJumpForwardHalfHour: () -> Unit,
    onPageForward: () -> Unit,
    onJumpToTomorrow: () -> Unit,
    onDaySelected: (Long) -> Unit,
    onModeSelected: (GuideChannelMode) -> Unit,
    onDensitySelected: (GuideDensity) -> Unit,
    onToggleScheduledOnly: () -> Unit,
    onToggleFavoritesOnly: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        colors = SurfaceDefaults.colors(containerColor = SurfaceElevated),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            GuideSummaryCard(uiState = uiState)

            GuidePrimaryControlsRow(
                categories = uiState.categories,
                selectedCategoryId = uiState.selectedCategoryId,
                showSearchBar = showSearchBar,
                showAdvancedOptions = showAdvancedOptions,
                onJumpToPreviousDay = onJumpToPreviousDay,
                onJumpBackward = onJumpBackward,
                onJumpToNow = onJumpToNow,
                onJumpForward = onJumpForward,
                onJumpToPrimeTime = onJumpToPrimeTime,
                onJumpToNextDay = onJumpToNextDay,
                onOpenCategoryPicker = onOpenCategoryPicker,
                onToggleSearch = onToggleSearch,
                onToggleAdvancedOptions = onToggleAdvancedOptions
            )

            if (showSearchBar) {
                GuideProgramSearchRow(
                    query = uiState.programSearchQuery,
                    onQueryChange = onProgramSearchQueryChange,
                    onClear = onClearProgramSearch,
                    contentPadding = PaddingValues(0.dp),
                    showLabel = false
                )
            }

            if (showAdvancedOptions) {
                GuideTimeControlsRow(
                    onJumpToPreviousDay = onJumpToPreviousDay,
                    onPageBackward = onPageBackward,
                    onJumpBackwardHalfHour = onJumpBackwardHalfHour,
                    onJumpBackward = onJumpBackward,
                    onJumpToNow = onJumpToNow,
                    onJumpForwardHalfHour = onJumpForwardHalfHour,
                    onJumpForward = onJumpForward,
                    onPageForward = onPageForward,
                    onJumpToPrimeTime = onJumpToPrimeTime,
                    onJumpToTomorrow = onJumpToTomorrow,
                    onJumpToNextDay = onJumpToNextDay,
                    contentPadding = PaddingValues(0.dp),
                    showLabel = false
                )

                GuideDayRow(
                    selectedDayStart = startOfDay(uiState.guideWindowStart + EpgViewModel.LOOKBACK_MS),
                    onDaySelected = onDaySelected,
                    contentPadding = PaddingValues(0.dp),
                    showLabel = false
                )

                GuideModeRow(
                    selectedMode = uiState.selectedChannelMode,
                    onModeSelected = onModeSelected
                )

                GuideDensityRow(
                    selectedDensity = uiState.selectedDensity,
                    onDensitySelected = onDensitySelected
                )

                GuideViewOptionsRow(
                    showScheduledOnly = uiState.showScheduledOnly,
                    onToggleScheduledOnly = onToggleScheduledOnly
                )

                GuideFavoritesRow(
                    showFavoritesOnly = uiState.showFavoritesOnly,
                    onToggleFavoritesOnly = onToggleFavoritesOnly
                )
            }
        }
    }
}

@Composable
private fun GuidePrimaryControlsRow(
    categories: List<Category>,
    selectedCategoryId: Long,
    showSearchBar: Boolean,
    showAdvancedOptions: Boolean,
    onJumpToPreviousDay: () -> Unit,
    onJumpBackward: () -> Unit,
    onJumpToNow: () -> Unit,
    onJumpForward: () -> Unit,
    onJumpToPrimeTime: () -> Unit,
    onJumpToNextDay: () -> Unit,
    onOpenCategoryPicker: () -> Unit,
    onToggleSearch: () -> Unit,
    onToggleAdvancedOptions: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GuideCategoryLauncherRow(
            categories = categories,
            selectedCategoryId = selectedCategoryId,
            onOpenCategoryPicker = onOpenCategoryPicker,
            modifier = Modifier.widthIn(min = 220.dp, max = 360.dp)
        )

        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { GuideShortcutChip(label = stringResource(R.string.epg_previous_day), onClick = onJumpToPreviousDay) }
            item { GuideShortcutChip(label = stringResource(R.string.epg_jump_now), onClick = onJumpToNow) }
            item { GuideShortcutChip(label = stringResource(R.string.epg_next_day), onClick = onJumpToNextDay) }
            item { GuideShortcutChip(label = stringResource(R.string.epg_jump_back), onClick = onJumpBackward) }
            item { GuideShortcutChip(label = stringResource(R.string.epg_jump_forward), onClick = onJumpForward) }
            item { GuideShortcutChip(label = stringResource(R.string.epg_jump_prime_time), onClick = onJumpToPrimeTime) }
            item {
                GuideShortcutChip(
                    label = if (showSearchBar) stringResource(R.string.epg_clear_search) else stringResource(R.string.epg_search_short),
                    onClick = onToggleSearch
                )
            }
            item {
                GuideShortcutChip(
                    label = stringResource(R.string.epg_options_short),
                    onClick = onToggleAdvancedOptions,
                    isSelected = showAdvancedOptions
                )
            }
        }
    }
}

@Composable
private fun GuideCategoryLauncherRow(
    categories: List<Category>,
    selectedCategoryId: Long,
    onOpenCategoryPicker: () -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedCategory = remember(categories, selectedCategoryId) {
        categories.firstOrNull { it.id == selectedCategoryId }
    }
    Surface(
        onClick = onOpenCategoryPicker,
        modifier = modifier.widthIn(max = 320.dp),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = SurfaceHighlight,
            focusedContainerColor = SurfaceElevated,
            contentColor = OnSurface,
            focusedContentColor = OnSurface
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, FocusBorder),
                shape = RoundedCornerShape(16.dp)
            )
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                Text(
                    text = selectedCategory?.name ?: stringResource(R.string.epg_filter_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = OnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${categories.size} categories",
                    style = MaterialTheme.typography.labelSmall,
                    color = OnSurfaceDim
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.epg_filter_label),
                style = MaterialTheme.typography.labelMedium,
                color = Primary
            )
        }
    }
}

@Composable
private fun GuideCategoryPickerDialog(
    categories: List<Category>,
    selectedCategoryId: Long,
    parentalControlLevel: Int,
    onDismiss: () -> Unit,
    onCategorySelected: (Category) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var shouldFocusFirstCategory by rememberSaveable { mutableStateOf(true) }
    val searchFocusRequester = remember { FocusRequester() }
    val firstCategoryFocusRequester = remember { FocusRequester() }
    val filteredCategories = remember(categories, query) {
        val trimmed = query.trim()
        val baseCategories = if (trimmed.isBlank()) {
            categories
        } else {
            categories.filter { it.name.contains(trimmed, ignoreCase = true) }
        }
        val selectedCategory = baseCategories.firstOrNull { it.id == selectedCategoryId }
        buildList {
            if (selectedCategory != null) add(selectedCategory)
            addAll(baseCategories.filterNot { it.id == selectedCategoryId })
        }
    }
    GuideModalDialog(onDismiss = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.52f)
                .fillMaxHeight(0.78f)
                .focusGroup(),
            colors = SurfaceDefaults.colors(containerColor = SurfaceElevated),
            shape = RoundedCornerShape(22.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.epg_filter_label),
                            style = MaterialTheme.typography.headlineSmall,
                            color = OnSurface
                        )
                        Text(
                            text = "${filteredCategories.size} matches",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceDim
                        )
                    }
                    GuideShortcutChip(
                        label = stringResource(R.string.settings_cancel),
                        onClick = onDismiss
                    )
                }

                GuideProgramSearchRow(
                    query = query,
                    onQueryChange = { query = it },
                    onClear = { query = "" },
                    focusRequester = searchFocusRequester,
                    contentPadding = PaddingValues(0.dp),
                    showLabel = false,
                    onSearchFieldActivated = {
                        shouldFocusFirstCategory = false
                    }
                )

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(
                        items = filteredCategories,
                        key = { index, category -> epgCategoryKey(category, index) }
                    ) { _, category ->
                        val isSelected = category.id == selectedCategoryId
                        val isLocked = isGuideCategoryLocked(category, parentalControlLevel)
                        Surface(
                            onClick = { onCategorySelected(category) },
                            modifier = if (category.id == filteredCategories.firstOrNull()?.id) {
                                Modifier.focusRequester(firstCategoryFocusRequester)
                            } else {
                                Modifier
                            },
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = if (isSelected) Primary.copy(alpha = 0.18f) else SurfaceHighlight,
                                focusedContainerColor = if (isSelected) {
                                    Primary.copy(alpha = 0.22f)
                                } else {
                                    SurfaceHighlight
                                },
                                contentColor = if (isSelected) Primary else OnSurface,
                                focusedContentColor = if (isSelected) Primary else OnSurface
                            ),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
                            border = ClickableSurfaceDefaults.border(
                                focusedBorder = Border(
                                    border = BorderStroke(2.dp, FocusBorder),
                                    shape = RoundedCornerShape(14.dp)
                                )
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = category.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = if (isSelected) Primary else OnSurface
                                    )
                                    if (isLocked) {
                                        Text(
                                            text = stringResource(R.string.settings_parental_control),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = OnSurfaceDim
                                        )
                                    } else if (category.count > 0) {
                                        Text(
                                            text = "${category.count} channels",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = OnSurfaceDim
                                        )
                                    }
                                }
                                if (isSelected) {
                                    Text(
                                        text = stringResource(R.string.epg_jump_now),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = Primary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(filteredCategories, shouldFocusFirstCategory) {
        if (shouldFocusFirstCategory && filteredCategories.isNotEmpty()) {
            firstCategoryFocusRequester.requestFocus()
            shouldFocusFirstCategory = false
        }
    }
}

private fun epgCategoryKey(category: Category, index: Int): String {
    return "category:${category.id}:${category.name.trim()}:$index"
}

private fun epgChannelKey(channel: Channel, index: Int): String {
    val epgId = channel.guideLookupKey().orEmpty()
    return "channel:${channel.id}:${channel.streamId}:${epgId}:${channel.name.trim()}:$index"
}

private fun isGuideCategoryLocked(category: Category, parentalControlLevel: Int): Boolean =
    parentalControlLevel == 1 && (category.isAdult || category.isUserProtected)

private fun isGuideChannelLocked(
    channel: Channel,
    categoriesById: Map<Long, Category>,
    parentalControlLevel: Int
): Boolean {
    if (parentalControlLevel != 1) {
        return false
    }
    val categoryLocked = channel.categoryId?.let(categoriesById::get)?.let { category ->
        category.isAdult || category.isUserProtected
    } ?: false
    return channel.isAdult || channel.isUserProtected || categoryLocked
}

@Composable
private fun dayRelativeLabel(dayStart: Long): String {
    val todayStart = remember { startOfDay(System.currentTimeMillis()) }
    return when (dayStart) {
        todayStart - EpgViewModel.DAY_SHIFT_MS -> stringResource(R.string.epg_day_yesterday)
        todayStart -> stringResource(R.string.epg_day_today)
        todayStart + EpgViewModel.DAY_SHIFT_MS -> stringResource(R.string.epg_day_tomorrow)
        else -> {
            val format = remember { SimpleDateFormat("EEE", Locale.getDefault()) }
            format.format(Date(dayStart))
        }
    }
}

