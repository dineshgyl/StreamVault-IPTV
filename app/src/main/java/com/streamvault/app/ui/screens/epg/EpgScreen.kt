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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
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
import kotlinx.coroutines.launch
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
import com.streamvault.domain.model.EpgMatchType
import com.streamvault.domain.model.EpgOverrideCandidate
import com.streamvault.domain.model.EpgSourceType
import com.streamvault.domain.model.Program
import com.streamvault.domain.repository.ChannelRepository
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import com.streamvault.app.ui.interaction.TvClickableSurface
import com.streamvault.app.ui.interaction.TvButton
import com.streamvault.app.ui.interaction.TvIconButton

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
    val overrideUiState by viewModel.overrideUiState.collectAsStateWithLifecycle()
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
            },
            onManageEpgMatch = if (channel.providerId > 0L) {
                {
                    selectedProgram = null
                    viewModel.openEpgOverride(channel)
                }
            } else {
                null
            }
        )
    }

    if (overrideUiState.channel != null) {
        EpgOverrideDialog(
            state = overrideUiState,
            onDismiss = viewModel::dismissEpgOverride,
            onQueryChange = viewModel::updateEpgOverrideSearch,
            onCandidateSelected = viewModel::applyEpgOverride,
            onClearOverride = viewModel::clearEpgOverride
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

internal fun isGuideCategoryLocked(category: Category, parentalControlLevel: Int): Boolean =
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


