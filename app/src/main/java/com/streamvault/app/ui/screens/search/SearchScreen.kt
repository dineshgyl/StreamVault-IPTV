package com.streamvault.app.ui.screens.search

import androidx.annotation.StringRes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.*
import com.streamvault.app.R
import com.streamvault.app.ui.components.CategoryRow
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
    val channelRows = remember(uiState.channels) { uiState.channels.chunked(4) }
    val movieRows = remember(uiState.movies) { uiState.movies.chunked(6) }
    val seriesRows = remember(uiState.series) { uiState.series.chunked(6) }

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

    val selectedTabDescription = selectedStateLabel

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
            verticalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item {
                SearchHeroPanel(
                    query = query,
                    selectedTab = selectedTab,
                    recentQueries = recentQueries,
                    totalResults = uiState.totalResults,
                    onQueryChange = viewModel::onQueryChange,
                    onSearch = {
                        viewModel.onSearchSubmitted()
                    },
                    onTabSelected = viewModel::onTabSelected,
                    onRecentQuerySelected = {
                        viewModel.onRecentQuerySelected(it)
                    },
                    onClearRecentQueries = viewModel::clearRecentQueries,
                    focusRequester = searchFocusRequester,
                    selectedStateLabel = selectedTabDescription
                )
            }

            when {
                !uiState.hasActiveProvider -> {
                    item {
                        SearchMessageState(
                            title = stringResource(R.string.search_no_provider_title),
                            subtitle = stringResource(R.string.search_no_provider_subtitle)
                        )
                    }
                }

                uiState.queryLength < 2 -> {
                    item {
                        SearchMessageState(
                            title = stringResource(R.string.search_ready_title),
                            subtitle = stringResource(R.string.search_type_to_search)
                        )
                    }
                }

                uiState.isEmpty -> {
                    item {
                        SearchMessageState(
                            title = stringResource(R.string.search_no_results_title),
                            subtitle = stringResource(R.string.search_no_results, query)
                        )
                    }
                }

                else -> {
                    item {
                        SearchResultsSummaryRow(
                            uiState = uiState
                        )
                    }

                    if (selectedTab == SearchTab.ALL) {
                        if (uiState.channels.isNotEmpty()) {
                            item {
                                SearchResultRail(
                                    title = stringResource(R.string.search_live_tv),
                                    items = uiState.channels.take(18),
                                    keySelector = { it.id }
                                ) { channel ->
                                    val channelLocked = isLocked(
                                        categoryId = channel.categoryId,
                                        isAdult = channel.isAdult,
                                        isUserProtected = channel.isUserProtected
                                    )
                                    ChannelCard(
                                        channel = channel,
                                        isLocked = channelLocked,
                                        onClick = {
                                            if (channelLocked) {
                                                pendingChannel = channel
                                                showPinDialog = true
                                            } else {
                                                onChannelClick(channel)
                                            }
                                        }
                                    )
                                }
                            }
                        }

                        if (uiState.movies.isNotEmpty()) {
                            item {
                                SearchResultRail(
                                    title = stringResource(R.string.search_movies),
                                    items = uiState.movies.take(18),
                                    keySelector = { it.id }
                                ) { movie ->
                                    val movieLocked = isLocked(
                                        categoryId = movie.categoryId,
                                        isAdult = movie.isAdult,
                                        isUserProtected = movie.isUserProtected
                                    )
                                    MovieCard(
                                        movie = movie,
                                        isLocked = movieLocked,
                                        onClick = {
                                            if (movieLocked) {
                                                pendingMovie = movie
                                                showPinDialog = true
                                            } else {
                                                onMovieClick(movie)
                                            }
                                        }
                                    )
                                }
                            }
                        }

                        if (uiState.series.isNotEmpty()) {
                            item {
                                SearchResultRail(
                                    title = stringResource(R.string.search_series),
                                    items = uiState.series.take(18),
                                    keySelector = { it.id }
                                ) { seriesItem ->
                                    val seriesLocked = isLocked(
                                        categoryId = seriesItem.categoryId,
                                        isAdult = seriesItem.isAdult,
                                        isUserProtected = seriesItem.isUserProtected
                                    )
                                    SeriesCard(
                                        series = seriesItem,
                                        isLocked = seriesLocked,
                                        onClick = {
                                            if (seriesLocked) {
                                                pendingSeries = seriesItem
                                                showPinDialog = true
                                            } else {
                                                onSeriesClick(seriesItem)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    } else {
                        item {
                            SectionHeader(
                                title = when (selectedTab) {
                                    SearchTab.ALL -> stringResource(R.string.search_all)
                                    SearchTab.LIVE -> stringResource(R.string.search_live_tv)
                                    SearchTab.MOVIES -> stringResource(R.string.search_movies)
                                    SearchTab.SERIES -> stringResource(R.string.search_series)
                                }
                            )
                        }

                        when (selectedTab) {
                            SearchTab.ALL -> Unit
                            SearchTab.LIVE -> items(channelRows, key = { row ->
                                row.joinToString("-") { it.id.toString() }
                            }) { row ->
                                SearchChannelGridRow(
                                    channels = row,
                                    isLocked = { channel ->
                                        isLocked(
                                            categoryId = channel.categoryId,
                                            isAdult = channel.isAdult,
                                            isUserProtected = channel.isUserProtected
                                        )
                                    },
                                    onChannelClick = { channel, locked ->
                                        if (locked) {
                                            pendingChannel = channel
                                            showPinDialog = true
                                        } else {
                                            onChannelClick(channel)
                                        }
                                    }
                                )
                            }

                            SearchTab.MOVIES -> items(movieRows, key = { row ->
                                row.joinToString("-") { it.id.toString() }
                            }) { row ->
                                SearchMovieGridRow(
                                    movies = row,
                                    isLocked = { movie ->
                                        isLocked(
                                            categoryId = movie.categoryId,
                                            isAdult = movie.isAdult,
                                            isUserProtected = movie.isUserProtected
                                        )
                                    },
                                    onMovieClick = { movie, locked ->
                                        if (locked) {
                                            pendingMovie = movie
                                            showPinDialog = true
                                        } else {
                                            onMovieClick(movie)
                                        }
                                    }
                                )
                            }

                            SearchTab.SERIES -> items(seriesRows, key = { row ->
                                row.joinToString("-") { it.id.toString() }
                            }) { row ->
                                SearchSeriesGridRow(
                                    seriesItems = row,
                                    isLocked = { seriesItem ->
                                        isLocked(
                                            categoryId = seriesItem.categoryId,
                                            isAdult = seriesItem.isAdult,
                                            isUserProtected = seriesItem.isUserProtected
                                        )
                                    },
                                    onSeriesClick = { seriesItem, locked ->
                                        if (locked) {
                                            pendingSeries = seriesItem
                                            showPinDialog = true
                                        } else {
                                            onSeriesClick(seriesItem)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchHeroPanel(
    query: String,
    selectedTab: SearchTab,
    recentQueries: List<String>,
    totalResults: Int,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onTabSelected: (SearchTab) -> Unit,
    onRecentQuerySelected: (String) -> Unit,
    onClearRecentQueries: () -> Unit,
    focusRequester: FocusRequester,
    selectedStateLabel: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = SurfaceDefaults.colors(containerColor = SurfaceElevated.copy(alpha = 0.92f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.search_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = TextPrimary,
                        modifier = Modifier.semantics { heading() }
                    )
                    Text(
                        text = stringResource(R.string.search_command_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 640.dp)
                    )
                }

                SearchStatusCard(
                    title = if (query.length >= 2) {
                        stringResource(R.string.search_results_title, totalResults)
                    } else {
                        stringResource(R.string.search_ready_title)
                    },
                    body = if (query.length >= 2) {
                        stringResource(R.string.search_screen_subtitle)
                    } else {
                        stringResource(R.string.search_type_to_search)
                    },
                    modifier = Modifier.widthIn(min = 220.dp, max = 360.dp)
                )
            }

            SearchInput(
                value = query,
                onValueChange = onQueryChange,
                placeholder = stringResource(R.string.search_hint),
                focusRequester = focusRequester,
                onSearch = onSearch,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            )

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(SearchTab.values().toList(), key = { it.name }) { tab ->
                    SearchPill(
                        text = stringResource(tab.titleRes),
                        selected = tab == selectedTab,
                        onClick = { onTabSelected(tab) },
                        modifier = Modifier.semantics {
                            selected = tab == selectedTab
                            if (tab == selectedTab) {
                                stateDescription = selectedStateLabel
                            }
                        }
                    )
                }
            }

            if (recentQueries.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.search_recent_title),
                        style = MaterialTheme.typography.labelLarge,
                        color = TextSecondary
                    )
                    LazyRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(recentQueries, key = { it }) { recentQuery ->
                            val recentQueryDescription = stringResource(R.string.a11y_recent_search, recentQuery)
                            SearchPill(
                                text = recentQuery,
                                selected = recentQuery.equals(query, ignoreCase = true),
                                onClick = { onRecentQuerySelected(recentQuery) },
                                modifier = Modifier.semantics {
                                    contentDescription = recentQueryDescription
                                    if (recentQuery.equals(query, ignoreCase = true)) {
                                        selected = true
                                        stateDescription = selectedStateLabel
                                    }
                                }
                            )
                        }
                    }
                    SearchPill(
                        text = stringResource(R.string.search_clear_history),
                        selected = false,
                        compact = true,
                        onClick = onClearRecentQueries
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SearchPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    Surface(
        modifier = modifier,
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Primary.copy(alpha = 0.22f) else Surface.copy(alpha = 0.72f),
            focusedContainerColor = if (selected) Primary.copy(alpha = 0.30f) else SurfaceHighlight,
            contentColor = if (selected) Color.White else TextSecondary,
            focusedContentColor = TextPrimary
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(
                    1.dp,
                    if (selected) Primary.copy(alpha = 0.65f) else FocusBorder.copy(alpha = 0.28f)
                ),
                shape = CircleShape
            ),
            focusedBorder = Border(
                border = BorderStroke(2.dp, FocusBorder),
                shape = CircleShape
            )
        )
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(
                horizontal = if (compact) 12.dp else 16.dp,
                vertical = if (compact) 8.dp else 10.dp
            ),
            style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SearchStatusCard(
    title: String,
    body: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = SurfaceDefaults.colors(containerColor = Surface.copy(alpha = 0.78f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = TextPrimary
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun <T : Any> SearchResultRail(
    title: String,
    items: List<T>,
    keySelector: (T) -> Any,
    itemContent: @Composable (T) -> Unit
) {
    CategoryRow(
        title = title,
        items = items,
        keySelector = keySelector
    ) { item ->
        itemContent(item)
    }
}

@Composable
private fun SearchChannelGridRow(
    channels: List<Channel>,
    isLocked: (Channel) -> Boolean,
    onChannelClick: (Channel, Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        channels.forEach { channel ->
            val locked = isLocked(channel)
            ChannelCard(
                channel = channel,
                isLocked = locked,
                onClick = { onChannelClick(channel, locked) }
            )
        }
    }
}

@Composable
private fun SearchMovieGridRow(
    movies: List<Movie>,
    isLocked: (Movie) -> Boolean,
    onMovieClick: (Movie, Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        movies.forEach { movie ->
            val locked = isLocked(movie)
            MovieCard(
                movie = movie,
                isLocked = locked,
                onClick = { onMovieClick(movie, locked) }
            )
        }
    }
}

@Composable
private fun SearchSeriesGridRow(
    seriesItems: List<Series>,
    isLocked: (Series) -> Boolean,
    onSeriesClick: (Series, Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        seriesItems.forEach { seriesItem ->
            val locked = isLocked(seriesItem)
            SeriesCard(
                series = seriesItem,
                isLocked = locked,
                onClick = { onSeriesClick(seriesItem, locked) }
            )
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
    uiState: SearchUiState
) {
    val countsSummary = listOf(
        stringResource(R.string.search_results_count, stringResource(R.string.search_live_tv), uiState.channels.size),
        stringResource(R.string.search_results_count, stringResource(R.string.search_movies), uiState.movies.size),
        stringResource(R.string.search_results_count, stringResource(R.string.search_series), uiState.series.size)
    ).joinToString("  •  ")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = stringResource(R.string.search_results_title, uiState.totalResults),
            style = MaterialTheme.typography.titleMedium,
            color = OnSurface,
            modifier = Modifier.semantics { heading() }
        )
        Text(
            text = countsSummary,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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

