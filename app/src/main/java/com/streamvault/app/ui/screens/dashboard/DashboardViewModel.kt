package com.streamvault.app.ui.screens.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.sync.SyncManager
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.ProviderStatus
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Series
import com.streamvault.domain.model.SyncState
import com.streamvault.domain.model.VirtualCategoryIds
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.FavoriteRepository
import com.streamvault.domain.repository.MovieRepository
import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.repository.SeriesRepository
import com.streamvault.domain.usecase.ContinueWatchingScope
import com.streamvault.domain.usecase.GetContinueWatching
import com.streamvault.domain.usecase.GetCustomCategories
import android.content.Context
import com.streamvault.app.R
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.Year
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val providerRepository: ProviderRepository,
    private val favoriteRepository: FavoriteRepository,
    private val channelRepository: ChannelRepository,
    private val playbackHistoryRepository: PlaybackHistoryRepository,
    private val movieRepository: MovieRepository,
    private val seriesRepository: SeriesRepository,
    private val preferencesRepository: PreferencesRepository,
    private val getContinueWatching: GetContinueWatching,
    private val getCustomCategories: GetCustomCategories,
    private val syncManager: SyncManager
) : ViewModel() {
    private companion object {
        const val FAVORITE_CHANNEL_LIMIT = 12
        const val RECENT_CHANNEL_LIMIT = 12
        const val CONTINUE_WATCHING_LIMIT = 12
        const val MOVIE_SHELF_LIMIT = 12
        const val SERIES_SHELF_LIMIT = 12
        const val HOME_SHORTCUT_LIMIT = 4
    }

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            providerRepository.getActiveProvider()
                .flatMapLatest { provider ->
                    if (provider == null) {
                        flowOf(DashboardUiState(isLoading = false))
                    } else {
                        val contentShelves = combine(
                            observeFavoriteChannels(provider.id).onStart { emit(emptyList()) },
                            observeRecentChannels(provider.id).onStart { emit(emptyList()) },
                            observeContinueWatching(provider.id).onStart { emit(emptyList()) },
                            movieRepository.getMovies(provider.id).map { movies ->
                                movies
                                    .filterNot(::shouldHideVodFromHome)
                                    .sortedByDescending(::movieFreshnessScore)
                                    .take(MOVIE_SHELF_LIMIT)
                            }.onStart { emit(emptyList()) },
                            seriesRepository.getSeries(provider.id).map { series ->
                                series
                                    .filterNot(::shouldHideVodFromHome)
                                    .sortedByDescending(::seriesFreshnessScore)
                                    .take(SERIES_SHELF_LIMIT)
                            }.onStart { emit(emptyList()) }
                        ) { favoriteChannels, recentChannels, continueWatching, recentMovies, recentSeries ->
                            DashboardContentShelves(
                                favoriteChannels = favoriteChannels,
                                recentChannels = recentChannels,
                                continueWatching = continueWatching,
                                recentMovies = recentMovies,
                                recentSeries = recentSeries
                            )
                        }

                        combine(
                            contentShelves,
                            buildLiveContext(provider.id).onStart {
                                emit(DashboardLiveContext(lastVisitedCategory = null, shortcuts = emptyList()))
                            },
                            channelRepository.getChannels(provider.id).map { it.size }.onStart { emit(0) },
                            movieRepository.getLibraryCount(provider.id).onStart { emit(0) },
                            seriesRepository.getLibraryCount(provider.id).onStart { emit(0) }
                        ) { shelves, liveContext, liveChannelCount, movieCount, seriesCount ->
                            DashboardSnapshot(
                                shelves = shelves,
                                liveContext = liveContext,
                                liveChannelCount = liveChannelCount,
                                movieCount = movieCount,
                                seriesCount = seriesCount
                            )
                        }.combine(syncManager.syncStateForProvider(provider.id).onStart { emit(SyncState.Idle) }) { snapshot, syncState ->
                            DashboardUiState(
                                provider = provider,
                                favoriteChannels = snapshot.shelves.favoriteChannels,
                                recentChannels = snapshot.shelves.recentChannels,
                                continueWatching = snapshot.shelves.continueWatching,
                                recentMovies = snapshot.shelves.recentMovies,
                                recentSeries = snapshot.shelves.recentSeries,
                                lastLiveCategory = snapshot.liveContext.lastVisitedCategory,
                                liveShortcuts = snapshot.liveContext.shortcuts,
                                stats = DashboardStats(
                                    liveChannelCount = snapshot.liveChannelCount,
                                    favoriteChannelCount = snapshot.shelves.favoriteChannels.size,
                                    recentChannelCount = snapshot.shelves.recentChannels.size,
                                    continueWatchingCount = snapshot.shelves.continueWatching.size,
                                    movieLibraryCount = snapshot.movieCount,
                                    seriesLibraryCount = snapshot.seriesCount
                                ),
                                feature = buildFeature(
                                    providerName = provider.name,
                                    recentChannels = snapshot.shelves.recentChannels,
                                    continueWatching = snapshot.shelves.continueWatching,
                                    recentMovies = snapshot.shelves.recentMovies,
                                    recentSeries = snapshot.shelves.recentSeries
                                ),
                                providerHealth = DashboardProviderHealth(
                                    status = provider.status,
                                    type = provider.type,
                                    lastSyncedAt = provider.lastSyncedAt,
                                    expirationDate = provider.expirationDate,
                                    maxConnections = provider.maxConnections
                                ),
                                providerWarnings = when (syncState) {
                                    is SyncState.Partial -> syncState.warnings
                                    is SyncState.Error -> listOf(syncState.message)
                                    else -> emptyList()
                                },
                                isLoading = false
                            )
                        }
                    }
                }
                .collect { state ->
                    _uiState.value = state
                }
        }
    }

    private fun observeFavoriteChannels(providerId: Long): Flow<List<Channel>> =
        favoriteRepository.getFavorites(ContentType.LIVE)
            .map { favorites ->
                favorites
                    .filter { it.groupId == null }
                    .sortedBy { it.position }
                    .map { it.contentId }
                    .take(FAVORITE_CHANNEL_LIMIT)
            }
            .flatMapLatest(::loadChannelsByOrderedIds)

    private fun observeRecentChannels(providerId: Long): Flow<List<Channel>> =
        playbackHistoryRepository.getRecentlyWatchedByProvider(providerId, RECENT_CHANNEL_LIMIT)
            .map { history ->
                history
                    .filter { it.contentType == ContentType.LIVE }
                    .sortedByDescending { it.lastWatchedAt }
                    .distinctBy { it.contentId }
                    .map { it.contentId }
                    .take(RECENT_CHANNEL_LIMIT)
            }
            .flatMapLatest(::loadChannelsByOrderedIds)

    private fun observeContinueWatching(providerId: Long): Flow<List<PlaybackHistory>> =
        getContinueWatching(
            providerId = providerId,
            limit = CONTINUE_WATCHING_LIMIT,
            scope = ContinueWatchingScope.ALL_VOD
        )

    private fun buildLiveContext(providerId: Long): Flow<DashboardLiveContext> =
        combine(
            getCustomCategories(ContentType.LIVE),
            preferencesRepository.getLastLiveCategoryId(providerId),
            preferencesRepository.promotedLiveGroupIds
        ) { customCategories, lastVisitedCategoryId, promotedGroupIds ->
            val lastVisitedCategory = customCategories.firstOrNull { it.id == lastVisitedCategoryId }
            val shortcuts = buildList {
                lastVisitedCategory?.let {
                    add(
                        DashboardLiveShortcut(
                            label = appContext.getString(R.string.home_last_group),
                            detail = it.name,
                            categoryId = it.id,
                            type = DashboardShortcutType.LAST_GROUP
                        )
                    )
                }

                customCategories
                    .asSequence()
                    .filter { it.id != VirtualCategoryIds.FAVORITES }
                    .filter { it.count > 0 }
                    .sortedWith(
                        compareByDescending<Category> { category ->
                            val groupId = if (category.id < 0) -category.id else category.id
                            groupId in promotedGroupIds
                        }.thenByDescending { it.count }
                    )
                    .take(HOME_SHORTCUT_LIMIT)
                    .forEach { category ->
                        val groupId = if (category.id < 0) -category.id else category.id
                        add(
                            DashboardLiveShortcut(
                                label = category.name,
                                detail = if (groupId in promotedGroupIds) {
                                    appContext.getString(R.string.dashboard_pinned_channels_format, category.count)
                                } else {
                                    appContext.getString(R.string.dashboard_channels_format, category.count)
                                },
                                categoryId = category.id,
                                type = DashboardShortcutType.CUSTOM_GROUP
                            )
                        )
                    }
            }
                .distinctBy { it.categoryId ?: it.label }
                .take(HOME_SHORTCUT_LIMIT)

            DashboardLiveContext(
                lastVisitedCategory = lastVisitedCategory,
                shortcuts = shortcuts
            )
        }

    private fun loadChannelsByOrderedIds(ids: List<Long>): Flow<List<Channel>> {
        if (ids.isEmpty()) return flowOf(emptyList())

        return channelRepository.getChannelsByIds(ids).map { channels ->
            val channelMap = channels.associateBy { it.id }
            ids.mapNotNull { id ->
                channelMap[id]
            }
        }
    }

    private fun buildFeature(
        providerName: String,
        recentChannels: List<Channel>,
        continueWatching: List<PlaybackHistory>,
        recentMovies: List<Movie>,
        recentSeries: List<Series>
    ): DashboardFeature {
        val resumeItem = continueWatching.firstOrNull()
        if (resumeItem != null) {
            val detail = when (resumeItem.contentType) {
                ContentType.MOVIE -> appContext.getString(R.string.dashboard_resume_movie)
                ContentType.SERIES -> appContext.getString(R.string.dashboard_resume_series)
                ContentType.SERIES_EPISODE -> {
                    if (resumeItem.seasonNumber != null && resumeItem.episodeNumber != null) {
                        appContext.getString(R.string.dashboard_resume_episode_format, resumeItem.seasonNumber, resumeItem.episodeNumber)
                    } else {
                        appContext.getString(R.string.dashboard_resume_episode)
                    }
                }
                ContentType.LIVE -> appContext.getString(R.string.dashboard_resume_live)
            }
            return DashboardFeature(
                title = resumeItem.title,
                summary = detail,
                artworkUrl = resumeItem.posterUrl,
                actionLabel = appContext.getString(R.string.dashboard_continue_watching),
                actionType = DashboardFeatureAction.CONTINUE_WATCHING
            )
        }

        recentChannels.firstOrNull()?.let { channel ->
            return DashboardFeature(
                title = channel.name,
                summary = appContext.getString(R.string.dashboard_jump_back_live),
                artworkUrl = channel.logoUrl,
                actionLabel = appContext.getString(R.string.dashboard_watch_live),
                actionType = DashboardFeatureAction.LIVE
            )
        }

        recentMovies.firstOrNull()?.let { movie ->
            return DashboardFeature(
                title = movie.name,
                summary = movie.year ?: appContext.getString(R.string.dashboard_fresh_movie_pick),
                artworkUrl = movie.backdropUrl ?: movie.posterUrl,
                actionLabel = appContext.getString(R.string.dashboard_open_movies),
                actionType = DashboardFeatureAction.MOVIES
            )
        }

        recentSeries.firstOrNull()?.let { series ->
            return DashboardFeature(
                title = series.name,
                summary = appContext.getString(R.string.dashboard_updated_series),
                artworkUrl = series.backdropUrl ?: series.posterUrl,
                actionLabel = appContext.getString(R.string.dashboard_open_series),
                actionType = DashboardFeatureAction.SERIES
            )
        }

        return DashboardFeature(
            title = providerName,
            summary = appContext.getString(R.string.dashboard_library_ready),
            artworkUrl = null,
            actionLabel = appContext.getString(R.string.dashboard_open_live_tv),
            actionType = DashboardFeatureAction.LIVE
        )
    }

    private fun movieFreshnessScore(movie: Movie): Long {
        return parseDateScore(movie.releaseDate)
            ?: movie.year?.toIntOrNull()?.toLong()
            ?: movie.id
    }

    private fun seriesFreshnessScore(series: Series): Long {
        return series.lastModified
            .takeIf { it > 0L }
            ?: parseDateScore(series.releaseDate)
            ?: series.id
    }

    private fun shouldHideVodFromHome(movie: Movie): Boolean {
        if (movie.isAdult || movie.isUserProtected) return true
        return titleLooksExplicit(movie.name)
    }

    private fun shouldHideVodFromHome(series: Series): Boolean {
        if (series.isAdult || series.isUserProtected) return true
        return titleLooksExplicit(series.name)
    }

    private fun titleLooksExplicit(title: String): Boolean {
        val normalized = title.lowercase()
        val explicitTerms = listOf("porn", "porno", "xxx", "adult", "18+", "sex", "erotic", "hentai")
        return explicitTerms.any { term -> normalized.contains(term) }
    }

    private fun parseDateScore(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null

        return runCatching {
            LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE).toEpochDay()
        }.getOrNull()
            ?: runCatching {
                Year.parse(raw, DateTimeFormatter.ofPattern("yyyy")).atDay(1).toEpochDay()
            }.getOrNull()
    }
}

private data class DashboardLiveContext(
    val lastVisitedCategory: Category?,
    val shortcuts: List<DashboardLiveShortcut>
)

private data class DashboardContentShelves(
    val favoriteChannels: List<Channel>,
    val recentChannels: List<Channel>,
    val continueWatching: List<PlaybackHistory>,
    val recentMovies: List<Movie>,
    val recentSeries: List<Series>
)

private data class DashboardSnapshot(
    val shelves: DashboardContentShelves,
    val liveContext: DashboardLiveContext,
    val liveChannelCount: Int,
    val movieCount: Int,
    val seriesCount: Int
)

data class DashboardUiState(
    val provider: Provider? = null,
    val favoriteChannels: List<Channel> = emptyList(),
    val recentChannels: List<Channel> = emptyList(),
    val continueWatching: List<PlaybackHistory> = emptyList(),
    val recentMovies: List<Movie> = emptyList(),
    val recentSeries: List<Series> = emptyList(),
    val lastLiveCategory: Category? = null,
    val liveShortcuts: List<DashboardLiveShortcut> = emptyList(),
    val feature: DashboardFeature = DashboardFeature(),
    val providerHealth: DashboardProviderHealth = DashboardProviderHealth(),
    val providerWarnings: List<String> = emptyList(),
    val stats: DashboardStats = DashboardStats(),
    val isLoading: Boolean = true
)

data class DashboardProviderHealth(
    val status: ProviderStatus = ProviderStatus.UNKNOWN,
    val type: ProviderType = ProviderType.M3U,
    val lastSyncedAt: Long = 0L,
    val expirationDate: Long? = null,
    val maxConnections: Int = 1
)

data class DashboardStats(
    val liveChannelCount: Int = 0,
    val favoriteChannelCount: Int = 0,
    val recentChannelCount: Int = 0,
    val continueWatchingCount: Int = 0,
    val movieLibraryCount: Int = 0,
    val seriesLibraryCount: Int = 0
)

data class DashboardFeature(
    val title: String = "",
    val summary: String = "",
    val artworkUrl: String? = null,
    val actionLabel: String = "",
    val actionType: DashboardFeatureAction = DashboardFeatureAction.LIVE
)

enum class DashboardFeatureAction {
    LIVE,
    CONTINUE_WATCHING,
    MOVIES,
    SERIES
}

data class DashboardLiveShortcut(
    val label: String,
    val detail: String,
    val categoryId: Long?,
    val type: DashboardShortcutType
)

enum class DashboardShortcutType {
    FAVORITES,
    RECENT,
    LAST_GROUP,
    CUSTOM_GROUP
}
