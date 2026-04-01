package com.streamvault.data.repository

import com.streamvault.data.local.dao.*
import com.streamvault.data.local.entity.*
import com.streamvault.data.mapper.*
import com.streamvault.data.remote.xtream.XtreamApiService
import com.streamvault.data.remote.xtream.XtreamProvider
import com.streamvault.data.remote.xtream.XtreamStreamUrlResolver
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.domain.model.*
import com.streamvault.domain.model.Result.Success
import com.streamvault.domain.repository.SeriesRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeout
import com.streamvault.data.util.toFtsPrefixQuery
import com.streamvault.data.util.rankSearchResults
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import com.streamvault.data.preferences.PreferencesRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Singleton

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class SeriesRepositoryImpl @Inject constructor(
    private val seriesDao: SeriesDao,
    private val episodeDao: EpisodeDao,
    private val categoryDao: CategoryDao,
    private val favoriteDao: FavoriteDao,
    private val playbackHistoryDao: PlaybackHistoryDao,
    private val providerDao: ProviderDao,
    private val xtreamApiService: XtreamApiService,
    private val preferencesRepository: PreferencesRepository,
    private val xtreamStreamUrlResolver: XtreamStreamUrlResolver
) : SeriesRepository {
    private companion object {
        const val SEARCH_RESULT_LIMIT = 200
        const val MIN_SEARCH_QUERY_LENGTH = 2
        const val BROWSE_WINDOW_BUFFER = 80
        const val XTREAM_SERIES_CATEGORY_TIMEOUT_MILLIS = 60_000L
    }

    private data class CachedXtreamProvider(
        val signature: String,
        val provider: XtreamProvider
    )

    private val xtreamProviderCache = ConcurrentHashMap<Long, CachedXtreamProvider>()
    private val xtreamCategoryLoadLocks = ConcurrentHashMap<String, Mutex>()
    private val loadedXtreamCategories = ConcurrentHashMap.newKeySet<String>()

    override fun getSeries(providerId: Long): Flow<List<Series>> =
        combine(
            seriesDao.getByProvider(providerId),
            preferencesRepository.parentalControlLevel
        ) { entities, level: Int ->
            if (level == 2) {
                entities.filter { !it.isUserProtected }
            } else {
                entities
            }
        }.map { list -> list.map { it.toDomain() } }

    override fun getSeriesByCategory(providerId: Long, categoryId: Long): Flow<List<Series>> =
        flow {
            ensureXtreamCategoryLoaded(providerId, categoryId)
            emitAll(
                combine(
                    seriesDao.getByCategory(providerId, categoryId),
                    preferencesRepository.parentalControlLevel
                ) { entities, level: Int ->
                    if (level == 2) {
                        entities.filter { !it.isUserProtected }
                    } else {
                        entities
                    }
                }.map { list -> list.map { it.toDomain() } }
            )
        }

    override fun getSeriesByCategoryPage(
        providerId: Long,
        categoryId: Long,
        limit: Int,
        offset: Int
    ): Flow<List<Series>> = flow {
        ensureXtreamCategoryLoaded(providerId, categoryId)
        emitAll(
            combine(
                seriesDao.getByCategoryPage(providerId, categoryId, limit, offset),
                preferencesRepository.parentalControlLevel
            ) { entities, level: Int ->
                if (level == 2) {
                    entities.filter { !it.isUserProtected }
                } else {
                    entities
                }
            }.map { list -> list.map { it.toDomain() } }
        )
    }

    override fun getSeriesByCategoryPreview(providerId: Long, categoryId: Long, limit: Int): Flow<List<Series>> =
        flow {
            ensureXtreamCategoryLoaded(providerId, categoryId)
            emitAll(
                combine(
                    seriesDao.getByCategoryPreview(providerId, categoryId, limit),
                    preferencesRepository.parentalControlLevel
                ) { entities, level: Int ->
                    if (level == 2) {
                        entities.filter { !it.isUserProtected }
                    } else {
                        entities
                    }
                }.map { list -> list.map { it.toDomain() } }
            )
        }

    override fun getCategoryPreviewRows(providerId: Long, limitPerCategory: Int): Flow<Map<Long?, List<Series>>> =
        combine(
            categoryDao.getByProviderAndType(providerId, ContentType.SERIES.name),
            preferencesRepository.parentalControlLevel
        ) { categories, level ->
            val filtered = if (level == 2) categories.filter { !it.isAdult && !it.isUserProtected } else categories
            filtered to level
        }.flatMapLatest { (filteredCategories, level) ->
            if (filteredCategories.isEmpty()) {
                flowOf(emptyMap())
            } else {
                // SQL LIMIT applied per-category — avoids loading the full catalog into memory
                val categoryGroupFlows: List<Flow<Pair<Long?, List<Series>>>> = filteredCategories.map { cat ->
                    seriesDao.getByCategoryPreview(providerId, cat.categoryId, limitPerCategory)
                        .map { entities ->
                            val items = if (level == 2) entities.filter { !it.isUserProtected } else entities
                            (cat.categoryId as Long?) to items.map { it.toDomain() }
                        }
                }
                combine(categoryGroupFlows) { pairs ->
                    pairs.associate { it.first to it.second }
                }
            }
        }

    override fun getTopRatedPreview(providerId: Long, limit: Int): Flow<List<Series>> =
        combine(
            seriesDao.getTopRatedPreview(providerId, limit),
            preferencesRepository.parentalControlLevel
        ) { entities, level: Int ->
            if (level == 2) {
                entities.filter { !it.isUserProtected }
            } else {
                entities
            }
        }.map { list -> list.map { it.toDomain() } }

    override fun getFreshPreview(providerId: Long, limit: Int): Flow<List<Series>> =
        combine(
            seriesDao.getFreshPreview(providerId, limit),
            preferencesRepository.parentalControlLevel
        ) { entities, level: Int ->
            if (level == 2) {
                entities.filter { !it.isUserProtected }
            } else {
                entities
            }
        }.map { list -> list.map { it.toDomain() } }

    override fun getSeriesByIds(ids: List<Long>): Flow<List<Series>> =
        seriesDao.getByIds(ids).map { entities -> entities.map { it.toDomain() } }

    override fun getCategories(providerId: Long): Flow<List<Category>> =
        combine(
            categoryDao.getByProviderAndType(providerId, ContentType.SERIES.name),
            preferencesRepository.parentalControlLevel
        ) { entities: List<CategoryEntity>, level: Int ->
            val mapped = entities.map { it.toDomain() }
            if (level == 2) {
                mapped.filter { !it.isAdult && !it.isUserProtected }
            } else {
                mapped
            }
        }

    override fun getCategoryItemCounts(providerId: Long): Flow<Map<Long, Int>> =
        seriesDao.getCategoryCounts(providerId).map { counts ->
            counts.associate { it.categoryId to it.item_count }
        }

    override fun getLibraryCount(providerId: Long): Flow<Int> =
        seriesDao.getCount(providerId)

    override fun browseSeries(query: LibraryBrowseQuery): Flow<PagedResult<Series>> {
        return flow {
            query.categoryId?.let { ensureXtreamCategoryLoaded(query.providerId, it) }
            emitAll(
                combine(
                    seriesBrowseSource(query),
                    seriesBrowseTotalCount(query),
                    favoriteDao.getAllByType(ContentType.SERIES.name),
                    playbackHistoryDao.getByProvider(query.providerId)
                ) { series, totalCount, favorites, history ->
                    val favoriteIds = favorites
                        .asSequence()
                        .filter { it.groupId == null }
                        .map { it.contentId }
                        .toSet()
                    val inProgressIds = history
                        .asSequence()
                        .filter { it.contentType == ContentType.SERIES || it.contentType == ContentType.SERIES_EPISODE }
                        .filter {
                            it.resumePositionMs > 0L && (
                                it.totalDurationMs <= 0L ||
                                    it.resumePositionMs < (it.totalDurationMs * 0.95f).toLong()
                                )
                        }
                        .mapNotNull { it.seriesId ?: it.contentId }
                        .toSet()
                    val watchCounts = history
                        .asSequence()
                        .filter { it.contentType == ContentType.SERIES || it.contentType == ContentType.SERIES_EPISODE }
                        .groupBy { it.seriesId ?: it.contentId }
                        .mapValues { (_, entries) -> entries.maxOf { it.watchCount } }

                    val browsed = applySeriesBrowseQuery(
                        series = series,
                        query = query,
                        favoriteIds = favoriteIds,
                        inProgressIds = inProgressIds,
                        watchCounts = watchCounts
                    )

                    PagedResult(
                        items = browsed.drop(query.offset).take(query.limit),
                        totalCount = totalCount,
                        offset = query.offset,
                        limit = query.limit
                    )
                }
            )
        }
    }

    override fun searchSeries(providerId: Long, query: String): Flow<List<Series>> =
        query.trim().takeIf { it.length >= MIN_SEARCH_QUERY_LENGTH }?.toFtsPrefixQuery().let { ftsQuery ->
            if (ftsQuery.isNullOrBlank()) {
            flowOf(emptyList())
            } else combine(
                seriesDao.search(providerId, ftsQuery, SEARCH_RESULT_LIMIT),
                preferencesRepository.parentalControlLevel
            ) { entities, level: Int ->
                if (level == 2) {
                    entities.filter { !it.isUserProtected }
                } else {
                    entities
                }
            }.map { list ->
                list.map { it.toDomain() }
                    .rankSearchResults(query) { it.name }
            }
        }

    override suspend fun getSeriesById(seriesId: Long): Series? =
        seriesDao.getById(seriesId)?.toDomain()

    override suspend fun getSeriesDetails(providerId: Long, seriesId: Long): Result<Series> {
        val seriesEntity = seriesDao.getById(seriesId)
            ?: seriesDao.getBySeriesId(providerId, seriesId)
            ?: return Result.error("Series not found")

        val provider = providerDao.getById(providerId)
            ?: return Result.error("Provider not found")

        // M3U and other non-Xtream providers have no standardized series-detail endpoint.
        if (provider.type != ProviderType.XTREAM_CODES || seriesEntity.seriesId <= 0L) {
            return Result.success(buildSeriesWithPersistedEpisodes(seriesEntity))
        }

        val xtreamProvider = try {
            getOrCreateXtreamProvider(providerId, provider)
        } catch (e: Exception) {
            return Result.error(e.message ?: "Failed to access provider credentials", e)
        }

        return when (val remoteResult = xtreamProvider.getSeriesInfo(seriesEntity.seriesId)) {
            is Result.Success -> {
                val remoteSeries = remoteResult.data

                val updatedSeries = seriesEntity.copy(
                    name = remoteSeries.name.ifBlank { seriesEntity.name },
                    posterUrl = remoteSeries.posterUrl ?: seriesEntity.posterUrl,
                    backdropUrl = remoteSeries.backdropUrl ?: seriesEntity.backdropUrl,
                    categoryId = remoteSeries.categoryId ?: seriesEntity.categoryId,
                    categoryName = remoteSeries.categoryName ?: seriesEntity.categoryName,
                    plot = remoteSeries.plot ?: seriesEntity.plot,
                    cast = remoteSeries.cast ?: seriesEntity.cast,
                    director = remoteSeries.director ?: seriesEntity.director,
                    genre = remoteSeries.genre ?: seriesEntity.genre,
                    releaseDate = remoteSeries.releaseDate ?: seriesEntity.releaseDate,
                    rating = if (remoteSeries.rating > 0f) remoteSeries.rating else seriesEntity.rating,
                    tmdbId = remoteSeries.tmdbId ?: seriesEntity.tmdbId,
                    youtubeTrailer = remoteSeries.youtubeTrailer ?: seriesEntity.youtubeTrailer,
                    episodeRunTime = remoteSeries.episodeRunTime ?: seriesEntity.episodeRunTime,
                    lastModified = if (remoteSeries.lastModified > 0) remoteSeries.lastModified else seriesEntity.lastModified
                )
                seriesDao.update(updatedSeries)

                val episodesToPersist = remoteSeries.seasons
                    .flatMap { season ->
                        season.episodes.map { episode ->
                            val remoteEpisodeId = episode.episodeId.takeIf { it > 0 } ?: episode.id
                            episode.copy(
                                id = 0,
                                episodeId = remoteEpisodeId,
                                seasonNumber = if (episode.seasonNumber > 0) episode.seasonNumber else season.seasonNumber,
                                seriesId = seriesEntity.id,
                                providerId = providerId
                            ).toEntity().copy(
                                id = 0,
                                episodeId = remoteEpisodeId,
                                seriesId = seriesEntity.id,
                                providerId = providerId
                            )
                        }
                    }

                if (episodesToPersist.isNotEmpty()) {
                    episodeDao.replaceAll(seriesEntity.id, providerId, episodesToPersist)
                }

                val persistedSeries = seriesDao.getById(seriesEntity.id) ?: updatedSeries
                val persistedEpisodes = episodeDao.getBySeriesSync(seriesEntity.id).map { it.toDomain() }
                val persistedByRemoteEpisodeId = persistedEpisodes.associateBy {
                    it.episodeId.takeIf { remoteId -> remoteId > 0 } ?: it.id
                }

                val mergedSeasons = if (remoteSeries.seasons.isNotEmpty()) {
                    remoteSeries.seasons
                        .sortedBy { it.seasonNumber }
                        .map { remoteSeason ->
                            val mergedEpisodes = remoteSeason.episodes.map { remoteEpisode ->
                                val remoteEpisodeId = remoteEpisode.episodeId.takeIf { it > 0 } ?: remoteEpisode.id
                                persistedByRemoteEpisodeId[remoteEpisodeId] ?: remoteEpisode.copy(
                                    episodeId = remoteEpisodeId,
                                    seriesId = seriesEntity.id,
                                    providerId = providerId
                                )
                            }
                            remoteSeason.copy(
                                episodes = mergedEpisodes,
                                episodeCount = mergedEpisodes.size
                            )
                        }
                } else {
                    persistedEpisodes.groupBy { it.seasonNumber }
                        .entries
                        .sortedBy { it.key }
                        .map { (seasonNumber, episodes) ->
                            Season(
                                seasonNumber = seasonNumber,
                                name = "Season $seasonNumber",
                                episodes = episodes,
                                episodeCount = episodes.size
                            )
                        }
                }

                Result.success(
                    persistedSeries.toDomain().copy(seasons = mergedSeasons)
                )
            }
            is Result.Error -> {
                val localSeries = buildSeriesWithPersistedEpisodes(seriesEntity)
                Result.success(localSeries)
            }
            is Result.Loading -> Result.error("Unexpected loading state")
        }
    }

    override suspend fun getEpisodeStreamInfo(episode: Episode): Result<StreamInfo> = try {
        xtreamStreamUrlResolver.resolveWithMetadata(
            url = episode.streamUrl,
            fallbackProviderId = episode.providerId,
            fallbackStreamId = episode.episodeId.takeIf { it > 0 } ?: episode.id,
            fallbackContentType = ContentType.SERIES_EPISODE,
            fallbackContainerExtension = episode.containerExtension
        )?.let { resolvedStream ->
            Result.success(
                StreamInfo(
                    url = resolvedStream.url,
                    title = episode.title,
                    expirationTime = resolvedStream.expirationTime
                )
            )
        } ?: Result.error("No stream URL available for episode: ${episode.title}")
    } catch (e: Exception) {
        Result.error(e.message ?: "Failed to resolve stream URL for episode: ${episode.title}", e)
    }

    override suspend fun refreshSeries(providerId: Long): Result<Unit> =
        Result.success(Unit) // Handled by ProviderRepository

    override suspend fun updateEpisodeWatchProgress(episodeId: Long, progress: Long): Result<Unit> = try {
        episodeDao.updateWatchProgress(episodeId, progress)
        Result.success(Unit)
    } catch (e: Exception) {
        Result.error("Failed to update episode watch progress", e)
    }

    private suspend fun buildSeriesWithPersistedEpisodes(seriesEntity: SeriesEntity): Series {
        val episodes = episodeDao.getBySeriesSync(seriesEntity.id).map { it.toDomain() }
        val seasons = episodes.groupBy { it.seasonNumber }
            .entries
            .sortedBy { it.key }
            .map { (seasonNumber, seasonEpisodes) ->
                Season(
                    seasonNumber = seasonNumber,
                    name = "Season $seasonNumber",
                    episodes = seasonEpisodes,
                    episodeCount = seasonEpisodes.size
                )
            }
        return seriesEntity.toDomain().copy(seasons = seasons)
    }

    private fun seriesBrowseSource(query: LibraryBrowseQuery): Flow<List<Series>> {
        val normalizedSearch = query.searchQuery.trim()
        val fetchLimit = browseFetchLimit(query)
        val fastFlow = when {
            normalizedSearch.length >= MIN_SEARCH_QUERY_LENGTH -> {
                val ftsQuery = normalizedSearch.toFtsPrefixQuery() ?: return flowOf(emptyList())
                query.categoryId?.let { categoryId ->
                    combine(
                        seriesDao.searchByCategory(query.providerId, categoryId, ftsQuery, SEARCH_RESULT_LIMIT),
                        preferencesRepository.parentalControlLevel
                    ) { entities, level ->
                        if (level == 2) entities.filter { !it.isUserProtected } else entities
                    }.map { entities ->
                        entities.map { it.toDomain() }
                            .rankSearchResults(normalizedSearch) { it.name }
                    }
                } ?: searchSeries(query.providerId, normalizedSearch)
            }
            query.filterBy.type in setOf(LibraryFilterType.ALL, LibraryFilterType.TOP_RATED, LibraryFilterType.RECENTLY_UPDATED) &&
                query.sortBy in setOf(LibrarySortBy.LIBRARY, LibrarySortBy.TITLE, LibrarySortBy.RELEASE, LibrarySortBy.UPDATED, LibrarySortBy.RATING) -> {
                when {
                    query.sortBy == LibrarySortBy.RATING || query.filterBy.type == LibraryFilterType.TOP_RATED -> {
                        query.categoryId?.let { categoryId ->
                            flow {
                                ensureXtreamCategoryLoaded(query.providerId, categoryId)
                                emitAll(
                                    combine(
                                        seriesDao.getTopRatedByCategoryPreview(query.providerId, categoryId, fetchLimit),
                                        preferencesRepository.parentalControlLevel
                                    ) { entities, level ->
                                        if (level == 2) entities.filter { !it.isUserProtected } else entities
                                    }.map { entities -> entities.map { it.toDomain() } }
                                )
                            }
                        } ?: getTopRatedPreview(query.providerId, fetchLimit)
                    }
                    query.sortBy == LibrarySortBy.RELEASE || query.sortBy == LibrarySortBy.UPDATED || query.filterBy.type == LibraryFilterType.RECENTLY_UPDATED -> {
                        query.categoryId?.let { categoryId ->
                            flow {
                                ensureXtreamCategoryLoaded(query.providerId, categoryId)
                                emitAll(
                                    combine(
                                        seriesDao.getFreshByCategoryPreview(query.providerId, categoryId, fetchLimit),
                                        preferencesRepository.parentalControlLevel
                                    ) { entities, level ->
                                        if (level == 2) entities.filter { !it.isUserProtected } else entities
                                    }.map { entities -> entities.map { it.toDomain() } }
                                )
                            }
                        } ?: getFreshPreview(query.providerId, fetchLimit)
                    }
                    else -> {
                        query.categoryId?.let { categoryId ->
                            flow {
                                ensureXtreamCategoryLoaded(query.providerId, categoryId)
                                emitAll(
                                    combine(
                                        seriesDao.getByCategoryPage(query.providerId, categoryId, fetchLimit, 0),
                                        preferencesRepository.parentalControlLevel
                                    ) { entities, level ->
                                        if (level == 2) entities.filter { !it.isUserProtected } else entities
                                    }.map { entities -> entities.map { it.toDomain() } }
                                )
                            }
                        } ?: combine(
                            seriesDao.getByProviderPage(query.providerId, fetchLimit, 0),
                            preferencesRepository.parentalControlLevel
                        ) { entities, level ->
                            if (level == 2) entities.filter { !it.isUserProtected } else entities
                        }.map { entities -> entities.map { it.toDomain() } }
                    }
                }
            }
            normalizedSearch.isBlank() &&
                query.filterBy.type == LibraryFilterType.FAVORITES &&
                query.sortBy in setOf(LibrarySortBy.LIBRARY, LibrarySortBy.TITLE) -> {
                query.categoryId?.let { categoryId ->
                    flow {
                        ensureXtreamCategoryLoaded(query.providerId, categoryId)
                        emitAll(
                            combine(
                                seriesDao.getFavoritesByCategoryPage(query.providerId, categoryId, fetchLimit, 0),
                                preferencesRepository.parentalControlLevel
                            ) { entities, level ->
                                if (level == 2) entities.filter { !it.isUserProtected } else entities
                            }.map { entities -> entities.map { it.toDomain() } }
                        )
                    }
                } ?: combine(
                    seriesDao.getFavoritesByProviderPage(query.providerId, fetchLimit, 0),
                    preferencesRepository.parentalControlLevel
                ) { entities, level ->
                    if (level == 2) entities.filter { !it.isUserProtected } else entities
                }.map { entities -> entities.map { it.toDomain() } }
            }
            normalizedSearch.isBlank() &&
                query.filterBy.type == LibraryFilterType.IN_PROGRESS &&
                query.sortBy in setOf(LibrarySortBy.LIBRARY, LibrarySortBy.TITLE) -> {
                query.categoryId?.let { categoryId ->
                    flow {
                        ensureXtreamCategoryLoaded(query.providerId, categoryId)
                        emitAll(
                            combine(
                                seriesDao.getInProgressByCategoryPage(query.providerId, categoryId, fetchLimit, 0),
                                preferencesRepository.parentalControlLevel
                            ) { entities, level ->
                                if (level == 2) entities.filter { !it.isUserProtected } else entities
                            }.map { entities -> entities.map { it.toDomain() } }
                        )
                    }
                } ?: combine(
                    seriesDao.getInProgressByProviderPage(query.providerId, fetchLimit, 0),
                    preferencesRepository.parentalControlLevel
                ) { entities, level ->
                    if (level == 2) entities.filter { !it.isUserProtected } else entities
                }.map { entities -> entities.map { it.toDomain() } }
            }
            normalizedSearch.isBlank() &&
                query.filterBy.type == LibraryFilterType.UNWATCHED &&
                query.sortBy in setOf(LibrarySortBy.LIBRARY, LibrarySortBy.TITLE) -> {
                query.categoryId?.let { categoryId ->
                    flow {
                        ensureXtreamCategoryLoaded(query.providerId, categoryId)
                        emitAll(
                            combine(
                                seriesDao.getUnwatchedByCategoryPage(query.providerId, categoryId, fetchLimit, 0),
                                preferencesRepository.parentalControlLevel
                            ) { entities, level ->
                                if (level == 2) entities.filter { !it.isUserProtected } else entities
                            }.map { entities -> entities.map { it.toDomain() } }
                        )
                    }
                } ?: combine(
                    seriesDao.getUnwatchedByProviderPage(query.providerId, fetchLimit, 0),
                    preferencesRepository.parentalControlLevel
                ) { entities, level ->
                    if (level == 2) entities.filter { !it.isUserProtected } else entities
                }.map { entities -> entities.map { it.toDomain() } }
            }
            normalizedSearch.isBlank() &&
                query.filterBy.type == LibraryFilterType.ALL &&
                query.sortBy == LibrarySortBy.WATCH_COUNT -> {
                query.categoryId?.let { categoryId ->
                    flow {
                        ensureXtreamCategoryLoaded(query.providerId, categoryId)
                        emitAll(
                            combine(
                                seriesDao.getByWatchCountCategoryPage(query.providerId, categoryId, fetchLimit, 0),
                                preferencesRepository.parentalControlLevel
                            ) { entities, level ->
                                if (level == 2) entities.filter { !it.isUserProtected } else entities
                            }.map { entities -> entities.map { it.toDomain() } }
                        )
                    }
                } ?: combine(
                    seriesDao.getByWatchCountProviderPage(query.providerId, fetchLimit, 0),
                    preferencesRepository.parentalControlLevel
                ) { entities, level ->
                    if (level == 2) entities.filter { !it.isUserProtected } else entities
                }.map { entities -> entities.map { it.toDomain() } }
            }
            else -> null
        }

        val categoryId = query.categoryId
        return fastFlow ?: if (categoryId == null) {
            getSeries(query.providerId)
        } else {
            getSeriesByCategory(query.providerId, categoryId)
        }
    }

    private fun seriesBrowseTotalCount(query: LibraryBrowseQuery): Flow<Int> {
        val normalizedSearch = query.searchQuery.trim()
        return when {
            normalizedSearch.length >= MIN_SEARCH_QUERY_LENGTH -> seriesBrowseSource(query).map { it.size }
            normalizedSearch.isBlank() &&
                query.filterBy.type == LibraryFilterType.FAVORITES &&
                query.sortBy in setOf(LibrarySortBy.LIBRARY, LibrarySortBy.TITLE) -> {
                query.categoryId?.let { categoryId ->
                    seriesDao.getFavoriteCountByCategory(query.providerId, categoryId)
                } ?: seriesDao.getFavoriteCountByProvider(query.providerId)
            }
            normalizedSearch.isBlank() &&
                query.filterBy.type == LibraryFilterType.IN_PROGRESS &&
                query.sortBy in setOf(LibrarySortBy.LIBRARY, LibrarySortBy.TITLE) -> {
                query.categoryId?.let { categoryId ->
                    seriesDao.getInProgressCountByCategory(query.providerId, categoryId)
                } ?: seriesDao.getInProgressCountByProvider(query.providerId)
            }
            normalizedSearch.isBlank() &&
                query.filterBy.type == LibraryFilterType.UNWATCHED &&
                query.sortBy in setOf(LibrarySortBy.LIBRARY, LibrarySortBy.TITLE) -> {
                query.categoryId?.let { categoryId ->
                    seriesDao.getUnwatchedCountByCategory(query.providerId, categoryId)
                } ?: seriesDao.getUnwatchedCountByProvider(query.providerId)
            }
            normalizedSearch.isBlank() &&
                query.filterBy.type == LibraryFilterType.ALL &&
                query.sortBy == LibrarySortBy.WATCH_COUNT -> {
                query.categoryId?.let { categoryId ->
                    seriesDao.getCountByCategory(query.providerId, categoryId)
                } ?: seriesDao.getCount(query.providerId)
            }
            query.filterBy.type in setOf(LibraryFilterType.ALL, LibraryFilterType.TOP_RATED, LibraryFilterType.RECENTLY_UPDATED) &&
                query.sortBy in setOf(LibrarySortBy.LIBRARY, LibrarySortBy.TITLE, LibrarySortBy.RELEASE, LibrarySortBy.UPDATED, LibrarySortBy.RATING) -> {
                query.categoryId?.let { categoryId ->
                    seriesDao.getCountByCategory(query.providerId, categoryId)
                } ?: seriesDao.getCount(query.providerId)
            }
            else -> seriesBrowseSource(query).map { it.size }
        }
    }

    private fun browseFetchLimit(query: LibraryBrowseQuery): Int =
        (query.offset + query.limit + BROWSE_WINDOW_BUFFER).coerceAtMost(SEARCH_RESULT_LIMIT)

    private fun applySeriesBrowseQuery(
        series: List<Series>,
        query: LibraryBrowseQuery,
        favoriteIds: Set<Long>,
        inProgressIds: Set<Long>,
        watchCounts: Map<Long, Int>
    ): List<Series> {
        val withFavoriteState = series.map { item -> item.copy(isFavorite = item.id in favoriteIds) }
        val filtered = withFavoriteState.filter { item ->
            seriesMatchesFilter(item, query.filterBy.type, inProgressIds) && seriesMatchesSearch(item, query.searchQuery)
        }

        val sorted = when (query.sortBy) {
            LibrarySortBy.LIBRARY -> filtered
            LibrarySortBy.TITLE -> filtered.sortedBy { it.name.lowercase() }
            LibrarySortBy.RELEASE, LibrarySortBy.UPDATED -> filtered.sortedByDescending(::seriesFreshnessScore)
            LibrarySortBy.RATING -> filtered.sortedByDescending { it.rating }
            LibrarySortBy.WATCH_COUNT -> filtered.sortedByDescending { watchCounts[it.id] ?: 0 }
        }

        return if (query.searchQuery.isBlank() || query.sortBy != LibrarySortBy.LIBRARY) {
            sorted
        } else {
            sorted.rankSearchResults(query.searchQuery) { it.name }
        }
    }

    private fun seriesMatchesFilter(series: Series, filterType: LibraryFilterType, inProgressIds: Set<Long>): Boolean = when (filterType) {
        LibraryFilterType.ALL -> true
        LibraryFilterType.FAVORITES -> series.isFavorite
        LibraryFilterType.IN_PROGRESS -> series.id in inProgressIds
        LibraryFilterType.UNWATCHED -> series.id !in inProgressIds
        LibraryFilterType.TOP_RATED -> series.rating > 0f
        LibraryFilterType.RECENTLY_UPDATED -> seriesFreshnessScore(series) > 0L
    }

    private fun seriesMatchesSearch(series: Series, searchQuery: String): Boolean {
        val normalizedQuery = searchQuery.trim().lowercase()
        if (normalizedQuery.isBlank()) return true
        return sequenceOf(series.name, series.genre, series.categoryName)
            .filterNotNull()
            .any { value -> value.lowercase().contains(normalizedQuery) }
    }

    private suspend fun ensureXtreamCategoryLoaded(providerId: Long, categoryId: Long) {
        val key = "$providerId:$categoryId"
        if (loadedXtreamCategories.contains(key)) return
        if (seriesDao.getCountByCategory(providerId, categoryId).first() > 0) {
            loadedXtreamCategories.add(key)
            return
        }

        val provider = providerDao.getById(providerId) ?: return
        if (provider.type != ProviderType.XTREAM_CODES) return

        val lock = xtreamCategoryLoadLocks.getOrPut(key) { Mutex() }
        lock.withLock {
            if (loadedXtreamCategories.contains(key)) return
            if (seriesDao.getCountByCategory(providerId, categoryId).first() > 0) {
                loadedXtreamCategories.add(key)
                return
            }

            runCatching {
                val xtreamProvider = getOrCreateXtreamProvider(providerId, provider)
                when (val result = withXtreamSeriesCategoryTimeout(categoryId) {
                    xtreamProvider.getSeriesList(categoryId)
                }) {
                    is Success -> {
                        seriesDao.replaceCategory(
                            providerId,
                            categoryId,
                            result.data.map { item -> item.toEntity() }
                        )
                        episodeDao.deleteOrphans()
                        loadedXtreamCategories.add(key)
                    }
                    else -> Unit
                }
            }
        }
    }

    private suspend fun <T> withXtreamSeriesCategoryTimeout(
        categoryId: Long,
        block: suspend () -> T
    ): T {
        return try {
            withTimeout(XTREAM_SERIES_CATEGORY_TIMEOUT_MILLIS) {
                block()
            }
        } catch (e: TimeoutCancellationException) {
            throw IOException("Timed out after 60 seconds while loading series category $categoryId", e)
        }
    }

    private fun seriesFreshnessScore(series: Series): Long =
        series.lastModified
            .takeIf { it > 0L }
            ?: series.releaseDate
                ?.filter { it.isDigit() }
                ?.take(8)
                ?.toLongOrNull()
            ?: 0L

    private fun getOrCreateXtreamProvider(providerId: Long, provider: ProviderEntity): XtreamProvider {
        val signature = listOf(provider.serverUrl, provider.username, provider.password).joinToString("\u0000")
        return xtreamProviderCache.compute(providerId) { _, cached ->
            if (cached != null && cached.signature == signature) {
                cached
            } else {
                val decryptedPassword = CredentialCrypto.decryptIfNeeded(provider.password)
                CachedXtreamProvider(
                    signature = signature,
                    provider = XtreamProvider(
                        providerId = providerId,
                        api = xtreamApiService,
                        serverUrl = provider.serverUrl,
                        username = provider.username,
                        password = decryptedPassword,
                        allowedOutputFormats = provider.toDomain().allowedOutputFormats
                    )
                )
            }
        }!!.provider
    }
}
