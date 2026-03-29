package com.streamvault.app.ui.screens.search

import androidx.annotation.StringRes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextButton
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.*
import com.streamvault.app.R
import com.streamvault.app.ui.components.SearchInput
import com.streamvault.app.ui.components.ChannelCard
import com.streamvault.app.ui.components.MovieCard
import com.streamvault.app.ui.components.SeriesCard
import com.streamvault.app.ui.components.TvEmptyState
import com.streamvault.app.ui.components.shell.AppNavigationChrome
import com.streamvault.app.ui.components.shell.AppScreenScaffold
import com.streamvault.app.ui.theme.*
import com.streamvault.domain.manager.ParentalControlManager
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.Series
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.usecase.SearchContent
import com.streamvault.domain.usecase.SearchContentScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModel @Inject constructor(
    private val providerRepository: ProviderRepository,
    private val searchContent: SearchContent,
    private val preferencesRepository: com.streamvault.data.preferences.PreferencesRepository,
    private val parentalControlManager: ParentalControlManager
) : ViewModel() {
    private companion object {
        const val MAX_RESULTS_PER_SECTION = 120
        const val MAX_RECENT_QUERIES = 6
    }

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _selectedTab = MutableStateFlow(SearchTab.ALL)
    val selectedTab: StateFlow<SearchTab> = _selectedTab.asStateFlow()
    private val _recentQueries = MutableStateFlow<List<String>>(emptyList())
    val recentQueries: StateFlow<List<String>> = _recentQueries.asStateFlow()

    private val _parentalControlLevel = MutableStateFlow(0)
    private val _activeProviderId = MutableStateFlow<Long?>(null)
    private val unlockedCategoryIds = providerRepository.getActiveProvider()
        .onEach { provider -> _activeProviderId.value = provider?.id }
        .flatMapLatest { provider ->
            provider?.let { parentalControlManager.unlockedCategoriesForProvider(it.id) } ?: flowOf(emptySet())
        }

    init {
        viewModelScope.launch {
            preferencesRepository.parentalControlLevel.collect { level ->
                _parentalControlLevel.value = level
            }
        }
        viewModelScope.launch {
            preferencesRepository.recentSearchQueries.collect { queries ->
                _recentQueries.value = queries
            }
        }
    }

    @OptIn(FlowPreview::class)
    val uiState: StateFlow<SearchUiState> = combine(
        providerRepository.getActiveProvider(),
        _query.debounce(300),
        _selectedTab,
        _parentalControlLevel,
        unlockedCategoryIds
    ) { provider, query, tab, level, unlockedIds ->
        SearchFilterParams(provider, query, tab, level, unlockedIds)
    }.flatMapLatest { params ->
        val provider = params.provider
        val query = params.query
        val tab = params.tab
        val level = params.level
        val unlockedIds = params.unlockedCategoryIds

        if (provider == null || query.length < 2) {
            flowOf(
                SearchUiState(
                    parentalControlLevel = level,
                    hasActiveProvider = provider != null,
                    queryLength = query.length,
                    unlockedCategoryIds = unlockedIds
                )
            )
        } else {
            searchContent(
                providerId = provider.id,
                query = query,
                scope = tab.toSearchScope(),
                maxResultsPerSection = MAX_RESULTS_PER_SECTION
            ).map { results ->
                SearchUiState(
                    channels = results.channels,
                    movies = results.movies,
                    series = results.series,
                    isLoading = false,
                    hasSearched = true,
                    parentalControlLevel = level,
                    hasActiveProvider = true,
                    queryLength = query.length,
                    unlockedCategoryIds = unlockedIds
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SearchUiState())

    fun onQueryChange(newQuery: String) {
        _query.value = newQuery
    }

    fun onSearchSubmitted() {
        val normalizedQuery = _query.value.trim()
        if (normalizedQuery.length < 2) return

        _query.value = normalizedQuery
        val updatedQueries = _recentQueries.updateAndGet { existing ->
            (listOf(normalizedQuery) + existing.filterNot { it.equals(normalizedQuery, ignoreCase = true) })
                .take(MAX_RECENT_QUERIES)
        }
        viewModelScope.launch {
            preferencesRepository.setRecentSearchQueries(updatedQueries)
        }
    }

    fun onRecentQuerySelected(query: String) {
        _query.value = query
        onSearchSubmitted()
    }

    fun submitExternalQuery(query: String) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.length < 2) return

        _query.value = normalizedQuery
        onSearchSubmitted()
    }

    fun clearRecentQueries() {
        _recentQueries.value = emptyList()
        viewModelScope.launch {
            preferencesRepository.setRecentSearchQueries(emptyList())
        }
    }

    fun onTabSelected(tab: SearchTab) {
        _selectedTab.value = tab
    }

    suspend fun verifyPin(pin: String): Boolean {
        return preferencesRepository.verifyParentalPin(pin)
    }

    fun unlockCategory(categoryId: Long?) {
        val providerId = _activeProviderId.value ?: return
        val resolvedCategoryId = categoryId ?: return
        parentalControlManager.unlockCategory(providerId, resolvedCategoryId)
    }
}

private data class SearchFilterParams(
    val provider: com.streamvault.domain.model.Provider?,
    val query: String,
    val tab: SearchTab,
    val level: Int,
    val unlockedCategoryIds: Set<Long>
)

enum class SearchTab(@get:StringRes val titleRes: Int) {
    ALL(R.string.search_all),
    LIVE(R.string.search_live_tv),
    MOVIES(R.string.search_movies),
    SERIES(R.string.search_series)
}

private fun SearchTab.toSearchScope(): SearchContentScope = when (this) {
    SearchTab.ALL -> SearchContentScope.ALL
    SearchTab.LIVE -> SearchContentScope.LIVE
    SearchTab.MOVIES -> SearchContentScope.MOVIES
    SearchTab.SERIES -> SearchContentScope.SERIES
}

data class SearchUiState(
    val channels: List<Channel> = emptyList(),
    val movies: List<Movie> = emptyList(),
    val series: List<Series> = emptyList(),
    val isLoading: Boolean = false,
    val hasSearched: Boolean = false,
    val parentalControlLevel: Int = 0,
    val hasActiveProvider: Boolean = false,
    val queryLength: Int = 0,
    val unlockedCategoryIds: Set<Long> = emptySet()
) {
    val isEmpty: Boolean get() = hasSearched && channels.isEmpty() && movies.isEmpty() && series.isEmpty()
    val totalResults: Int get() = channels.size + movies.size + series.size
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SearchScreen(
    initialQuery: String = "",
    onChannelClick: (Channel) -> Unit,
    onMovieClick: (Movie) -> Unit,
    onSeriesClick: (Series) -> Unit,
    onNavigate: (String) -> Unit,
    currentRoute: String,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val recentQueries by viewModel.recentQueries.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val searchFocusRequester = remember { FocusRequester() }
    val context = androidx.compose.ui.platform.LocalContext.current
    var showPinDialog by remember { mutableStateOf(false) }
    var pinError by remember { mutableStateOf<String?>(null) }
    var pendingChannel by remember { mutableStateOf<Channel?>(null) }
    var pendingMovie by remember { mutableStateOf<Movie?>(null) }
    var pendingSeries by remember { mutableStateOf<Series?>(null) }
    val scope = rememberCoroutineScope()
    val selectedStateLabel = stringResource(R.string.a11y_selected)

    fun isLocked(categoryId: Long?, isAdult: Boolean, isUserProtected: Boolean): Boolean {
        if (uiState.parentalControlLevel != 1) {
            return false
        }
        if (!isAdult && !isUserProtected) {
            return false
        }
        return categoryId == null || categoryId !in uiState.unlockedCategoryIds
    }

    LaunchedEffect(Unit) {
        runCatching { searchFocusRequester.requestFocus() }
    }

    LaunchedEffect(initialQuery) {
        if (initialQuery.isNotBlank()) {
            viewModel.submitExternalQuery(initialQuery)
            focusManager.clearFocus()
        }
    }

    LaunchedEffect(showPinDialog) {
        if (!showPinDialog) {
            runCatching { searchFocusRequester.requestFocus() }
        }
    }

    if (showPinDialog) {
        com.streamvault.app.ui.components.dialogs.PinDialog(
            onDismissRequest = {
                showPinDialog = false
                pinError = null
                pendingChannel = null
                pendingMovie = null
                pendingSeries = null
            },
            onPinEntered = { pin ->
                scope.launch {
                    if (viewModel.verifyPin(pin)) {
                        pendingChannel?.categoryId?.let(viewModel::unlockCategory)
                        pendingMovie?.categoryId?.let(viewModel::unlockCategory)
                        pendingSeries?.categoryId?.let(viewModel::unlockCategory)
                        showPinDialog = false
                        pinError = null
                        pendingChannel?.let { onChannelClick(it) }
                        pendingMovie?.let { onMovieClick(it) }
                        pendingSeries?.let { onSeriesClick(it) }
                        pendingChannel = null
                        pendingMovie = null
                        pendingSeries = null
                    } else {
                        pinError = context.getString(R.string.search_incorrect_pin)
                    }
                }
            },
            error = pinError
        )
    }

    AppScreenScaffold(
        currentRoute = currentRoute,
        onNavigate = onNavigate,
        title = stringResource(R.string.search_title),
        subtitle = stringResource(R.string.search_screen_subtitle),
        navigationChrome = AppNavigationChrome.TopBar,
        compactHeader = true,
        showScreenHeader = false
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.search_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = TextPrimary,
                    modifier = Modifier.semantics { heading() }
                )
            }

            item {
                Text(
                    text = stringResource(R.string.search_command_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.widthIn(max = 640.dp)
                )
            }

            item {
            SearchInput(
                value = query,
                onValueChange = { viewModel.onQueryChange(it) },
                placeholder = stringResource(R.string.search_hint),
                focusRequester = searchFocusRequester,
                onSearch = {
                    viewModel.onSearchSubmitted()
                    focusManager.clearFocus()
                },
                modifier = Modifier.widthIn(max = 620.dp)
            )
            }

            item {
                androidx.compose.foundation.lazy.LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(SearchTab.values().toList(), key = { it.name }) { tab ->
                        FilterChip(
                            selected = tab == selectedTab,
                            onClick = { viewModel.onTabSelected(tab) },
                            modifier = Modifier.semantics {
                                selected = tab == selectedTab
                                if (tab == selectedTab) {
                                    stateDescription = selectedStateLabel
                                }
                            },
                            colors = FilterChipDefaults.colors(
                                selectedContainerColor = Primary,
                                selectedContentColor = Color.White
                            )
                        ) {
                            Text(stringResource(tab.titleRes))
                        }
                    }
                }
            }

            if (recentQueries.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.search_recent_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = OnSurface,
                            modifier = Modifier.semantics { heading() }
                        )
                        TextButton(onClick = { viewModel.clearRecentQueries() }) {
                            Text(stringResource(R.string.search_clear_history))
                        }
                    }
                }

                item {
                    androidx.compose.foundation.lazy.LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(recentQueries, key = { it }) { recentQuery ->
                            val recentQueryDescription = stringResource(R.string.a11y_recent_search, recentQuery)
                            FilterChip(
                                selected = recentQuery.equals(query, ignoreCase = true),
                                onClick = {
                                    viewModel.onRecentQuerySelected(recentQuery)
                                    focusManager.clearFocus()
                                },
                                modifier = Modifier.semantics {
                                    contentDescription = recentQueryDescription
                                    if (recentQuery.equals(query, ignoreCase = true)) {
                                        selected = true
                                        stateDescription = selectedStateLabel
                                    }
                                },
                                colors = FilterChipDefaults.colors(
                                    selectedContainerColor = Primary,
                                    selectedContentColor = Color.White
                                )
                            ) {
                                Text(recentQuery)
                            }
                        }
                    }
                }
            }

            item {
                when {
                    !uiState.hasActiveProvider -> {
                        SearchMessageState(
                            title = stringResource(R.string.search_no_provider_title),
                            subtitle = stringResource(R.string.search_no_provider_subtitle)
                        )
                    }

                    uiState.queryLength < 2 -> {
                        SearchMessageState(
                            title = stringResource(R.string.search_ready_title),
                            subtitle = stringResource(R.string.search_type_to_search)
                        )
                    }

                    uiState.isEmpty -> {
                        SearchMessageState(
                            title = stringResource(R.string.search_no_results_title),
                            subtitle = stringResource(R.string.search_no_results, query)
                        )
                    }

                    else -> {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(132.dp),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 280.dp, max = 1600.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            userScrollEnabled = false
                        ) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                SearchResultsSummaryRow(
                                    uiState = uiState,
                                    selectedTab = selectedTab,
                                    onTabSelected = viewModel::onTabSelected
                                )
                            }

                            if (uiState.channels.isNotEmpty()) {
                                if (selectedTab == SearchTab.ALL) {
                                    item(span = { GridItemSpan(maxLineSpan) }) {
                                        SectionHeader(stringResource(R.string.search_live_tv))
                                    }
                                }
                                items(uiState.channels, key = { it.id }) { channel ->
                                    val isLocked = isLocked(
                                        categoryId = channel.categoryId,
                                        isAdult = channel.isAdult,
                                        isUserProtected = channel.isUserProtected
                                    )
                                    ChannelCard(
                                        channel = channel,
                                        isLocked = isLocked,
                                        onClick = {
                                            if (isLocked) {
                                                pendingChannel = channel
                                                showPinDialog = true
                                            } else {
                                                onChannelClick(channel)
                                            }
                                        },
                                        modifier = Modifier.aspectRatio(16f / 9f)
                                    )
                                }
                            }

                            if (uiState.movies.isNotEmpty()) {
                                if (selectedTab == SearchTab.ALL) {
                                    item(span = { GridItemSpan(maxLineSpan) }) {
                                        SectionHeader(stringResource(R.string.search_movies))
                                    }
                                }
                                items(uiState.movies, key = { it.id }) { movie ->
                                    val isLocked = isLocked(
                                        categoryId = movie.categoryId,
                                        isAdult = movie.isAdult,
                                        isUserProtected = movie.isUserProtected
                                    )
                                    MovieCard(
                                        movie = movie,
                                        isLocked = isLocked,
                                        onClick = {
                                            if (isLocked) {
                                                pendingMovie = movie
                                                showPinDialog = true
                                            } else {
                                                onMovieClick(movie)
                                            }
                                        },
                                        modifier = Modifier.aspectRatio(2f / 3f)
                                    )
                                }
                            }

                            if (uiState.series.isNotEmpty()) {
                                if (selectedTab == SearchTab.ALL) {
                                    item(span = { GridItemSpan(maxLineSpan) }) {
                                        SectionHeader(stringResource(R.string.search_series))
                                    }
                                }
                                items(uiState.series, key = { it.id }) { series ->
                                    val isLocked = isLocked(
                                        categoryId = series.categoryId,
                                        isAdult = series.isAdult,
                                        isUserProtected = series.isUserProtected
                                    )
                                    SeriesCard(
                                        series = series,
                                        isLocked = isLocked,
                                        onClick = {
                                            if (isLocked) {
                                                pendingSeries = series
                                                showPinDialog = true
                                            } else {
                                                onSeriesClick(series)
                                            }
                                        },
                                        modifier = Modifier.aspectRatio(2f / 3f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = Primary,
        modifier = Modifier
            .padding(vertical = 8.dp)
            .semantics { heading() }
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SearchResultsSummaryRow(
    uiState: SearchUiState,
    selectedTab: SearchTab,
    onTabSelected: (SearchTab) -> Unit
) {
    val selectedStateLabel = stringResource(R.string.a11y_selected)
    val summaryChips = listOf(
        SearchSummaryChip(SearchTab.ALL, stringResource(R.string.search_all), uiState.totalResults),
        SearchSummaryChip(SearchTab.LIVE, stringResource(R.string.search_live_tv), uiState.channels.size),
        SearchSummaryChip(SearchTab.MOVIES, stringResource(R.string.search_movies), uiState.movies.size),
        SearchSummaryChip(SearchTab.SERIES, stringResource(R.string.search_series), uiState.series.size)
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.search_results_title, uiState.totalResults),
            style = MaterialTheme.typography.titleMedium,
            color = OnSurface,
            modifier = Modifier.semantics { heading() }
        )
        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(summaryChips, key = { it.tab.name }) { chip ->
                FilterChip(
                    selected = chip.tab == selectedTab,
                    onClick = { onTabSelected(chip.tab) },
                    modifier = Modifier.semantics {
                        selected = chip.tab == selectedTab
                        if (chip.tab == selectedTab) {
                            stateDescription = selectedStateLabel
                        }
                    },
                    colors = FilterChipDefaults.colors(
                        selectedContainerColor = Primary,
                        selectedContentColor = Color.White
                    )
                ) {
                    Text(stringResource(R.string.search_results_count, chip.label, chip.count))
                }
            }
        }
    }
}

@Composable
private fun SearchMessageState(
    title: String,
    subtitle: String
) {
    TvEmptyState(
        title = title,
        subtitle = subtitle,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    )
}

private data class SearchSummaryChip(
    val tab: SearchTab,
    val label: String,
    val count: Int
)
