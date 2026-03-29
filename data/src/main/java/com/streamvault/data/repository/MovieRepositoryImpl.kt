package com.streamvault.data.repository

import com.streamvault.data.local.dao.CategoryDao
import com.streamvault.data.local.dao.FavoriteDao
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.dao.PlaybackHistoryDao
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.mapper.toEntity
import com.streamvault.data.mapper.toDomain
import com.streamvault.data.remote.xtream.XtreamApiService
import com.streamvault.data.remote.xtream.XtreamProvider
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LibraryFilterType
import com.streamvault.domain.model.LibraryBrowseQuery
import com.streamvault.domain.model.LibrarySortBy
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.PagedResult
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.Result.Success
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.repository.MovieRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import com.streamvault.data.util.toFtsPrefixQuery
import com.streamvault.data.util.rankSearchResults
import com.streamvault.data.security.CredentialCrypto
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.remote.xtream.XtreamStreamUrlResolver
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Singleton

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class MovieRepositoryImpl @Inject constructor(
    private val movieDao: MovieDao,
    private val categoryDao: CategoryDao,
    private val providerDao: ProviderDao,
    private val xtreamApiService: XtreamApiService,
    private val preferencesRepository: PreferencesRepository,
    private val favoriteDao: FavoriteDao,
    private val playbackHistoryDao: PlaybackHistoryDao,
    private val xtreamStreamUrlResolver: XtreamStreamUrlResolver
) : MovieRepository {
    private companion object {
        const val SEARCH_RESULT_LIMIT = 200
        const val MIN_SEARCH_QUERY_LENGTH = 2
        const val BROWSE_WINDOW_BUFFER = 80
    }

    private data class CachedXtreamProvider(
        val signature: String,
        val provider: XtreamProvider
    )

    private val xtreamProviderCache = ConcurrentHashMap<Long, CachedXtreamProvider>()
    private val xtreamCategoryLoadLocks = ConcurrentHashMap<String, Mutex>()
    private val loadedXtreamCategories = ConcurrentHashMap.newKeySet<String>()

    override fun getMovies(providerId: Long): Flow<List<Movie>> =
        preferencesRepository.parentalControlLevel.flatMapLatest { level ->
            if (level == 2) movieDao.getByProviderUnprotected(providerId)
            else movieDao.getByProvider(providerId)
        }.map { list -> list.map { it.toDomain() } }

    override fun getMoviesByCategory(providerId: Long, categoryId: Long): Flow<List<Movie>> =
        flow {
            ensureXtreamCategoryLoaded(providerId, categoryId)
            emitAll(
                preferencesRepository.parentalControlLevel.flatMapLatest { level ->
                    if (level == 2) movieDao.getByCategoryUnprotected(providerId, categoryId)
                    else movieDao.getByCategory(providerId, categoryId)
                }.map { list -> list.map { it.toDomain() } }
            )
        }

    override fun getMoviesByCategoryPage(
        providerId: Long,
        categoryId: Long,
        limit: Int,
        offset: Int
    ): Flow<List<Movie>> = flow {
        ensureXtreamCategoryLoaded(providerId, categoryId)
        emitAll(
            combine(
                movieDao.getByCategoryPage(providerId, categoryId, limit, offset),
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

    override fun getMoviesByCategoryPreview(providerId: Long, categoryId: Long, limit: Int): Flow<List<Movie>> =
        flow {
            ensureXtreamCategoryLoaded(providerId, categoryId)
            emitAll(
                combine(
                    movieDao.getByCategoryPreview(providerId, categoryId, limit),
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

    override fun getCategoryPreviewRows(providerId: Long, limitPerCategory: Int): Flow<Map<Long?, List<Movie>>> =
        combine(
            categoryDao.getByProviderAndType(providerId, ContentType.MOVIE.name),
            preferencesRepository.parentalControlLevel
        ) { categories, level ->
            val filtered = if (level == 2) categories.filter { !it.isAdult && !it.isUserProtected } else categories
            filtered to level
        }.flatMapLatest { (filteredCategories, level) ->
            if (filteredCategories.isEmpty()) {
                flowOf(emptyMap())
            } else {
                // SQL LIMIT applied per-category — avoids loading the full catalog into memory
                val categoryGroupFlows: List<Flow<Pair<Long?, List<Movie>>>> = filteredCategories.map { cat ->
                    movieDao.getByCategoryPreview(providerId, cat.categoryId, limitPerCategory)
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

    override fun getTopRatedPreview(providerId: Long, limit: Int): Flow<List<Movie>> =
        combine(
            movieDao.getTopRatedPreview(providerId, limit),
            preferencesRepository.parentalControlLevel
        ) { entities, level: Int ->
            if (level == 2) {
                entities.filter { !it.isUserProtected }
            } else {
                entities
            }
        }.map { list -> list.map { it.toDomain() } }

    override fun getFreshPreview(providerId: Long, limit: Int): Flow<List<Movie>> =
        combine(
            movieDao.getFreshPreview(providerId, limit),
            preferencesRepository.parentalControlLevel
        ) { entities, level: Int ->
            if (level == 2) {
                entities.filter { !it.isUserProtected }
            } else {
                entities
            }
        }.map { list -> list.map { it.toDomain() } }

    override fun getRecommendations(providerId: Long, limit: Int): Flow<List<Movie>> =
        combine(
            getTopRatedPreview(providerId, limit = maxOf(limit * 6, 48)),
            getFreshPreview(providerId, limit = maxOf(limit * 6, 48)),
            favoriteDao.getAllByType(ContentType.MOVIE.name),
            playbackHistoryDao.getRecentlyWatchedByProvider(providerId, limit = maxOf(limit * 4, 24))
        ) { topRated, fresh, favorites, history ->
            buildRecommendations(
                movies = (topRated + fresh).distinctBy { it.id },
                favoriteIds = favorites.map { it.contentId }.toSet(),
                recentlyWatchedIds = history
                    .asSequence()
                    .filter { it.contentType == ContentType.MOVIE }
                    .map { it.contentId }
                    .toSet(),
                limit = limit
            )
        }

    override fun getRelatedContent(providerId: Long, movieId: Long, limit: Int): Flow<List<Movie>> =
        flow {
            val targetMovie = getMovie(movieId) ?: run {
                emit(emptyList())
                return@flow
            }
            val categoryFlow = targetMovie.categoryId?.let { categoryId ->
                getMoviesByCategoryPreview(providerId, categoryId, limit = maxOf(limit * 6, 48))
            } ?: flowOf(emptyList())
            emitAll(
                combine(
                    categoryFlow,
                    getTopRatedPreview(providerId, limit = maxOf(limit * 4, 32))
                ) { categoryItems, topRated ->
                    buildRelatedContent(
                        movies = (categoryItems + topRated).distinctBy { it.id },
                        movieId = movieId,
                        limit = limit
                    )
                }
            )
        }

    override fun getMoviesByIds(ids: List<Long>): Flow<List<Movie>> =
        movieDao.getByIds(ids).map { entities -> entities.map { it.toDomain() } }

    override fun getCategories(providerId: Long): Flow<List<Category>> =
        combine(
            categoryDao.getByProviderAndType(providerId, ContentType.MOVIE.name),
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
        movieDao.getCategoryCounts(providerId).map { counts ->
            counts.associate { it.categoryId to it.item_count }
        }

    override fun getLibraryCount(providerId: Long): Flow<Int> =
        movieDao.getCount(providerId)

    override fun browseMovies(query: LibraryBrowseQuery): Flow<PagedResult<Movie>> {
        return flow {
            query.categoryId?.let { ensureXtreamCategoryLoaded(query.providerId, it) }
            emitAll(
                combine(
                    movieBrowseSource(query),
                    movieBrowseTotalCount(query),
                    favoriteDao.getAllByType(ContentType.MOVIE.name),
                    playbackHistoryDao.getByProvider(query.providerId)
            ) { movies, totalCount, favorites, history ->
                val favoriteIds = favorites
                    .asSequence()
                        .filter { it.groupId == null }
                        .map { it.contentId }
                        .toSet()
                    val inProgressIds = history
                        .asSequence()
                        .filter { it.contentType == ContentType.MOVIE }
                        .filter { it.resumePositionMs > 0L && (it.totalDurationMs <= 0L || !moviePlaybackComplete(it.resumePositionMs, it.totalDurationMs)) }
                        .map { it.contentId }
                        .toSet()
                    val watchCounts = history
                        .asSequence()
                        .filter { it.contentType == ContentType.MOVIE }
                        .associate { it.contentId to it.watchCount }

                    val browsed = applyMovieBrowseQuery(
                        movies = movies,
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

    override fun searchMovies(providerId: Long, query: String): Flow<List<Movie>> =
        query.trim().takeIf { it.length >= MIN_SEARCH_QUERY_LENGTH }?.toFtsPrefixQuery().let { ftsQuery ->
            if (ftsQuery.isNullOrBlank()) {
            flowOf(emptyList())
            } else combine(
                movieDao.search(providerId, ftsQuery, SEARCH_RESULT_LIMIT),
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

    override suspend fun getMovie(movieId: Long): Movie? =
        movieDao.getById(movieId)?.toDomain()

    override suspend fun getMovieDetails(providerId: Long, movieId: Long): Result<Movie> {
        val movieEntity = movieDao.getById(movieId)
            ?: return Result.error("Movie not found")

        val provider = providerDao.getById(providerId)
            ?: return Result.error("Provider not found")

        if (provider.type != ProviderType.XTREAM_CODES) {
            return Result.success(movieEntity.toDomain())
        }

        val xtreamProvider = try {
            getOrCreateXtreamProvider(providerId, provider)
        } catch (e: Exception) {
            return Result.success(movieEntity.toDomain())
        }

        return when (val remoteResult = xtreamProvider.getVodInfo(movieEntity.streamId)) {
            is Result.Success -> {
                val remoteMovie = remoteResult.data
                val updatedMovie = movieEntity.copy(
                    name = remoteMovie.name.ifBlank { movieEntity.name },
                    posterUrl = remoteMovie.posterUrl ?: movieEntity.posterUrl,
                    backdropUrl = remoteMovie.backdropUrl ?: movieEntity.backdropUrl,
                    categoryId = remoteMovie.categoryId ?: movieEntity.categoryId,
                    categoryName = remoteMovie.categoryName ?: movieEntity.categoryName,
                    plot = remoteMovie.plot ?: movieEntity.plot,
                    cast = remoteMovie.cast ?: movieEntity.cast,
                    director = remoteMovie.director ?: movieEntity.director,
                    genre = remoteMovie.genre ?: movieEntity.genre,
                    releaseDate = remoteMovie.releaseDate ?: movieEntity.releaseDate,
                    duration = remoteMovie.duration ?: movieEntity.duration,
                    durationSeconds = remoteMovie.durationSeconds.takeIf { it > 0 } ?: movieEntity.durationSeconds,
                    rating = if (remoteMovie.rating > 0f) remoteMovie.rating else movieEntity.rating,
                    year = remoteMovie.year ?: movieEntity.year,
                    tmdbId = remoteMovie.tmdbId ?: movieEntity.tmdbId,
                    youtubeTrailer = remoteMovie.youtubeTrailer ?: movieEntity.youtubeTrailer
                )
                movieDao.update(updatedMovie)
                Result.success((movieDao.getById(movieEntity.id) ?: updatedMovie).toDomain())
            }
            is Result.Error -> Result.success(movieEntity.toDomain())
            is Result.Loading -> Result.error("Unexpected loading state")
        }
    }

    override suspend fun getStreamInfo(movie: Movie): Result<StreamInfo> = try {
        xtreamStreamUrlResolver.resolveWithMetadata(
            url = movie.streamUrl,
            fallbackProviderId = movie.providerId,
            fallbackStreamId = movie.streamId,
            fallbackContentType = ContentType.MOVIE,
            fallbackContainerExtension = movie.containerExtension
        )?.let { resolvedStream ->
            Result.success(
                StreamInfo(
                    url = resolvedStream.url,
                    title = movie.name,
                    expirationTime = resolvedStream.expirationTime
                )
            )
        } ?: Result.error("No stream URL available for movie: ${movie.name}")
    } catch (e: Exception) {
        Result.error(e.message ?: "Failed to resolve stream URL for movie: ${movie.name}", e)
    }

    override suspend fun refreshMovies(providerId: Long): Result<Unit> =
        Result.success(Unit) // Handled by ProviderRepository

    override suspend fun updateWatchProgress(movieId: Long, progress: Long): Result<Unit> = try {
        movieDao.updateWatchProgress(movieId, progress)
        Result.success(Unit)
    } catch (e: Exception) {
        Result.error("Failed to update movie watch progress", e)
    }

    private fun buildRecommendations(
        movies: List<Movie>,
        favoriteIds: Set<Long>,
        recentlyWatchedIds: Set<Long>,
        limit: Int
    ): List<Movie> {
        if (movies.isEmpty()) return emptyList()

        val seedMovies = movies.filter { movie -> movie.id in favoriteIds || movie.id in recentlyWatchedIds }
        val excludedIds = favoriteIds + recentlyWatchedIds

        if (seedMovies.isEmpty()) {
            return movies
                .sortedWith(compareByDescending<Movie> { it.rating }.thenByDescending(::movieReleaseScore).thenBy { it.name.lowercase() })
                .take(limit)
        }

        return movies
            .asSequence()
            .filterNot { movie -> movie.id in excludedIds }
            .map { movie -> movie to recommendationScore(movie, seedMovies, favoriteIds) }
            .filter { (_, score) -> score > 0f }
            .sortedWith(
                compareByDescending<Pair<Movie, Float>> { it.second }
                    .thenByDescending { it.first.rating }
                    .thenByDescending { movieReleaseScore(it.first) }
                    .thenBy { it.first.name.lowercase() }
            )
            .map { it.first }
            .take(limit)
            .toList()
            .ifEmpty {
                movies
                    .filterNot { movie -> movie.id in excludedIds }
                    .sortedWith(compareByDescending<Movie> { it.rating }.thenByDescending(::movieReleaseScore).thenBy { it.name.lowercase() })
                    .take(limit)
            }
    }

    private fun buildRelatedContent(
        movies: List<Movie>,
        movieId: Long,
        limit: Int
    ): List<Movie> {
        val target = movies.firstOrNull { it.id == movieId } ?: return emptyList()

        return movies
            .asSequence()
            .filterNot { it.id == movieId }
            .map { movie -> movie to relatedScore(target, movie) }
            .filter { (_, score) -> score > 0f }
            .sortedWith(
                compareByDescending<Pair<Movie, Float>> { it.second }
                    .thenByDescending { it.first.rating }
                    .thenByDescending { movieReleaseScore(it.first) }
                    .thenBy { it.first.name.lowercase() }
            )
            .map { it.first }
            .take(limit)
            .toList()
    }

    private fun recommendationScore(candidate: Movie, seedMovies: List<Movie>, favoriteIds: Set<Long>): Float {
        val candidateGenres = metadataTokens(candidate.genre)
        val candidateCast = metadataTokens(candidate.cast)
        val candidateDirector = metadataTokens(candidate.director)
        var score = if (candidate.id in favoriteIds) -2f else 0f

        seedMovies.forEach { seed ->
            score += tokenOverlap(candidateGenres, metadataTokens(seed.genre)) * 4f
            score += tokenOverlap(candidateCast, metadataTokens(seed.cast)) * 2.5f
            score += tokenOverlap(candidateDirector, metadataTokens(seed.director)) * 2f
            if (candidate.categoryId != null && candidate.categoryId == seed.categoryId) {
                score += 1.5f
            }
        }

        score += candidate.rating * 0.35f
        score += movieReleaseScore(candidate) * 0.0001f
        return score
    }

    private fun relatedScore(target: Movie, candidate: Movie): Float {
        val genreScore = tokenOverlap(metadataTokens(target.genre), metadataTokens(candidate.genre)) * 5f
        val castScore = tokenOverlap(metadataTokens(target.cast), metadataTokens(candidate.cast)) * 3f
        val directorScore = tokenOverlap(metadataTokens(target.director), metadataTokens(candidate.director)) * 2f
        val categoryScore = if (target.categoryId != null && target.categoryId == candidate.categoryId) 1.5f else 0f
        val yearScore = if (target.year != null && target.year == candidate.year) 0.75f else 0f

        return genreScore + castScore + directorScore + categoryScore + yearScore + (candidate.rating * 0.25f)
    }

    private fun metadataTokens(value: String?): Set<String> =
        value.orEmpty()
            .lowercase()
            .split(',', '/', '|')
            .flatMap { chunk -> chunk.split(' ') }
            .map { it.trim() }
            .filter { it.length >= 3 }
            .toSet()

    private fun tokenOverlap(left: Set<String>, right: Set<String>): Float {
        if (left.isEmpty() || right.isEmpty()) return 0f
        return left.intersect(right).size.toFloat()
    }

    private fun movieBrowseSource(query: LibraryBrowseQuery): Flow<List<Movie>> {
        val normalizedSearch = query.searchQuery.trim()
        val fetchLimit = browseFetchLimit(query)
        val fastFlow = when {
            normalizedSearch.length >= MIN_SEARCH_QUERY_LENGTH -> {
                val ftsQuery = normalizedSearch.toFtsPrefixQuery() ?: return flowOf(emptyList())
                query.categoryId?.let { categoryId ->
                    combine(
                        movieDao.searchByCategory(query.providerId, categoryId, ftsQuery, SEARCH_RESULT_LIMIT),
                        preferencesRepository.parentalControlLevel
                    ) { entities, level ->
                        if (level == 2) entities.filter { !it.isUserProtected } else entities
                    }.map { entities ->
                        entities.map { it.toDomain() }
                            .rankSearchResults(normalizedSearch) { it.name }
                    }
                } ?: searchMovies(query.providerId, normalizedSearch)
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
                                        movieDao.getTopRatedByCategoryPreview(query.providerId, categoryId, fetchLimit),
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
                                        movieDao.getFreshByCategoryPreview(query.providerId, categoryId, fetchLimit),
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
                                        movieDao.getByCategoryPage(query.providerId, categoryId, fetchLimit, 0),
                                        preferencesRepository.parentalControlLevel
                                    ) { entities, level ->
                                        if (level == 2) entities.filter { !it.isUserProtected } else entities
                                    }.map { entities -> entities.map { it.toDomain() } }
                                )
                            }
                        } ?: combine(
                            movieDao.getByProviderPage(query.providerId, fetchLimit, 0),
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
                                movieDao.getFavoritesByCategoryPage(query.providerId, categoryId, fetchLimit, 0),
                                preferencesRepository.parentalControlLevel
                            ) { entities, level ->
                                if (level == 2) entities.filter { !it.isUserProtected } else entities
                            }.map { entities -> entities.map { it.toDomain() } }
                        )
                    }
                } ?: combine(
                    movieDao.getFavoritesByProviderPage(query.providerId, fetchLimit, 0),
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
                                movieDao.getInProgressByCategoryPage(query.providerId, categoryId, fetchLimit, 0),
                                preferencesRepository.parentalControlLevel
                            ) { entities, level ->
                                if (level == 2) entities.filter { !it.isUserProtected } else entities
                            }.map { entities -> entities.map { it.toDomain() } }
                        )
                    }
                } ?: combine(
                    movieDao.getInProgressByProviderPage(query.providerId, fetchLimit, 0),
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
                                movieDao.getUnwatchedByCategoryPage(query.providerId, categoryId, fetchLimit, 0),
                                preferencesRepository.parentalControlLevel
                            ) { entities, level ->
                                if (level == 2) entities.filter { !it.isUserProtected } else entities
                            }.map { entities -> entities.map { it.toDomain() } }
                        )
                    }
                } ?: combine(
                    movieDao.getUnwatchedByProviderPage(query.providerId, fetchLimit, 0),
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
                                movieDao.getByWatchCountCategoryPage(query.providerId, categoryId, fetchLimit, 0),
                                preferencesRepository.parentalControlLevel
                            ) { entities, level ->
                                if (level == 2) entities.filter { !it.isUserProtected } else entities
                            }.map { entities -> entities.map { it.toDomain() } }
                        )
                    }
                } ?: combine(
                    movieDao.getByWatchCountProviderPage(query.providerId, fetchLimit, 0),
                    preferencesRepository.parentalControlLevel
                ) { entities, level ->
                    if (level == 2) entities.filter { !it.isUserProtected } else entities
                }.map { entities -> entities.map { it.toDomain() } }
            }
            else -> null
        }

        val categoryId = query.categoryId
        return fastFlow ?: if (categoryId == null) {
            getMovies(query.providerId)
        } else {
            getMoviesByCategory(query.providerId, categoryId)
        }
    }

    private fun movieBrowseTotalCount(query: LibraryBrowseQuery): Flow<Int> {
        val normalizedSearch = query.searchQuery.trim()
        return when {
            normalizedSearch.length >= MIN_SEARCH_QUERY_LENGTH -> movieBrowseSource(query).map { it.size }
            normalizedSearch.isBlank() &&
                query.filterBy.type == LibraryFilterType.FAVORITES &&
                query.sortBy in setOf(LibrarySortBy.LIBRARY, LibrarySortBy.TITLE) -> {
                query.categoryId?.let { categoryId ->
                    movieDao.getFavoriteCountByCategory(query.providerId, categoryId)
                } ?: movieDao.getFavoriteCountByProvider(query.providerId)
            }
            normalizedSearch.isBlank() &&
                query.filterBy.type == LibraryFilterType.IN_PROGRESS &&
                query.sortBy in setOf(LibrarySortBy.LIBRARY, LibrarySortBy.TITLE) -> {
                query.categoryId?.let { categoryId ->
                    movieDao.getInProgressCountByCategory(query.providerId, categoryId)
                } ?: movieDao.getInProgressCountByProvider(query.providerId)
            }
            normalizedSearch.isBlank() &&
                query.filterBy.type == LibraryFilterType.UNWATCHED &&
                query.sortBy in setOf(LibrarySortBy.LIBRARY, LibrarySortBy.TITLE) -> {
                query.categoryId?.let { categoryId ->
                    movieDao.getUnwatchedCountByCategory(query.providerId, categoryId)
                } ?: movieDao.getUnwatchedCountByProvider(query.providerId)
            }
            normalizedSearch.isBlank() &&
                query.filterBy.type == LibraryFilterType.ALL &&
                query.sortBy == LibrarySortBy.WATCH_COUNT -> {
                query.categoryId?.let { categoryId ->
                    movieDao.getCountByCategory(query.providerId, categoryId)
                } ?: movieDao.getCount(query.providerId)
            }
            query.filterBy.type in setOf(LibraryFilterType.ALL, LibraryFilterType.TOP_RATED, LibraryFilterType.RECENTLY_UPDATED) &&
                query.sortBy in setOf(LibrarySortBy.LIBRARY, LibrarySortBy.TITLE, LibrarySortBy.RELEASE, LibrarySortBy.UPDATED, LibrarySortBy.RATING) -> {
                query.categoryId?.let { categoryId ->
                    movieDao.getCountByCategory(query.providerId, categoryId)
                } ?: movieDao.getCount(query.providerId)
            }
            else -> movieBrowseSource(query).map { it.size }
        }
    }

    private fun browseFetchLimit(query: LibraryBrowseQuery): Int =
        (query.offset + query.limit + BROWSE_WINDOW_BUFFER).coerceAtMost(SEARCH_RESULT_LIMIT)

    private fun applyMovieBrowseQuery(
        movies: List<Movie>,
        query: LibraryBrowseQuery,
        favoriteIds: Set<Long>,
        inProgressIds: Set<Long>,
        watchCounts: Map<Long, Int>
    ): List<Movie> {
        val withFavoriteState = movies.map { movie ->
            movie.copy(isFavorite = movie.id in favoriteIds)
        }
        val filtered = withFavoriteState.filter { movie ->
            movieMatchesFilter(movie, query.filterBy.type, inProgressIds) && movieMatchesSearch(movie, query.searchQuery)
        }

        val sorted = when (query.sortBy) {
            LibrarySortBy.LIBRARY -> filtered
            LibrarySortBy.TITLE -> filtered.sortedBy { it.name.lowercase() }
            LibrarySortBy.RELEASE, LibrarySortBy.UPDATED -> filtered.sortedByDescending(::movieReleaseScore)
            LibrarySortBy.RATING -> filtered.sortedByDescending { it.rating }
            LibrarySortBy.WATCH_COUNT -> filtered.sortedByDescending { watchCounts[it.id] ?: 0 }
        }

        return if (query.searchQuery.isBlank() || query.sortBy != LibrarySortBy.LIBRARY) {
            sorted
        } else {
            sorted.rankSearchResults(query.searchQuery) { it.name }
        }
    }

    private fun movieMatchesFilter(movie: Movie, filterType: LibraryFilterType, inProgressIds: Set<Long>): Boolean = when (filterType) {
        LibraryFilterType.ALL -> true
        LibraryFilterType.FAVORITES -> movie.isFavorite
        LibraryFilterType.IN_PROGRESS -> movie.id in inProgressIds || movieIsInProgress(movie)
        LibraryFilterType.UNWATCHED -> movie.id !in inProgressIds && movie.watchProgress <= 0L
        LibraryFilterType.TOP_RATED -> movie.rating > 0f
        LibraryFilterType.RECENTLY_UPDATED -> movieReleaseScore(movie) > 0L
    }

    private fun movieMatchesSearch(movie: Movie, searchQuery: String): Boolean {
        val normalizedQuery = searchQuery.trim().lowercase()
        if (normalizedQuery.isBlank()) return true
        return sequenceOf(movie.name, movie.genre, movie.categoryName, movie.year)
            .filterNotNull()
            .any { value -> value.lowercase().contains(normalizedQuery) }
    }

    private suspend fun ensureXtreamCategoryLoaded(providerId: Long, categoryId: Long) {
        val key = "$providerId:$categoryId"
        if (loadedXtreamCategories.contains(key)) return
        if (movieDao.getCountByCategory(providerId, categoryId).first() > 0) {
            loadedXtreamCategories.add(key)
            return
        }

        val provider = providerDao.getById(providerId) ?: return
        if (provider.type != ProviderType.XTREAM_CODES) return

        val lock = xtreamCategoryLoadLocks.getOrPut(key) { Mutex() }
        lock.withLock {
            if (loadedXtreamCategories.contains(key)) return
            if (movieDao.getCountByCategory(providerId, categoryId).first() > 0) {
                loadedXtreamCategories.add(key)
                return
            }

            runCatching {
                val xtreamProvider = getOrCreateXtreamProvider(providerId, provider)
                when (val result = xtreamProvider.getVodStreams(categoryId)) {
                    is Success -> {
                        movieDao.replaceCategory(
                            providerId,
                            categoryId,
                            result.data.map { movie -> movie.toEntity() }
                        )
                        loadedXtreamCategories.add(key)
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun movieIsInProgress(movie: Movie): Boolean {
        if (movie.watchProgress <= 0L) return false
        val totalDurationMs = movie.durationSeconds.takeIf { it > 0 }?.times(1000L) ?: 0L
        return !moviePlaybackComplete(movie.watchProgress, totalDurationMs)
    }

    private fun movieReleaseScore(movie: Movie): Long =
        movie.releaseDate
            ?.filter { it.isDigit() }
            ?.toLongOrNull()
            ?: movie.year?.toLongOrNull()
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

    private fun moviePlaybackComplete(progressMs: Long, totalDurationMs: Long): Boolean {
        if (progressMs <= 0L || totalDurationMs <= 0L) return false
        return progressMs >= (totalDurationMs * 0.95f).toLong()
    }
}
