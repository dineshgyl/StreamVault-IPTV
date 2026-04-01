package com.streamvault.data.sync

import android.util.Log
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.CatalogSyncDao
import com.streamvault.data.local.dao.CategoryDao
import com.streamvault.data.local.dao.ChannelDao
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.dao.ProgramDao
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.dao.SeriesDao
import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.local.entity.ChannelEntity
import com.streamvault.data.local.entity.MovieEntity
import com.streamvault.data.local.entity.SeriesEntity
import com.streamvault.data.mapper.toDomain
import com.streamvault.data.mapper.toEntity
import com.streamvault.data.parser.M3uParser
import com.streamvault.data.remote.dto.XtreamCategory
import com.streamvault.data.remote.dto.XtreamSeriesItem
import com.streamvault.data.remote.dto.XtreamStream
import com.streamvault.data.remote.xtream.OkHttpXtreamApiService
import com.streamvault.data.remote.xtream.XtreamAuthenticationException
import com.streamvault.data.remote.xtream.XtreamNetworkException
import com.streamvault.data.remote.xtream.XtreamParsingException
import com.streamvault.data.remote.xtream.XtreamRequestException
import com.streamvault.data.remote.xtream.XtreamResponseTooLargeException
import com.streamvault.data.remote.xtream.XtreamApiService
import com.streamvault.data.remote.xtream.XtreamProvider
import com.streamvault.data.remote.xtream.XtreamUrlFactory
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.data.util.AdultContentClassifier
import com.streamvault.data.util.UrlSecurityPolicy
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Series
import com.streamvault.domain.model.SyncMetadata
import com.streamvault.domain.model.SyncState
import com.streamvault.domain.model.VodSyncMode
import com.streamvault.domain.repository.EpgRepository
import com.streamvault.domain.repository.SyncMetadataRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlin.system.measureTimeMillis

private const val TAG = "SyncManager"
private const val XTREAM_CATALOG_PAGE_SIZE = 250
private const val XTREAM_CATALOG_MAX_PAGES = 200
private const val XTREAM_FALLBACK_STAGE_BATCH_SIZE = 500
private const val MOVIE_CATEGORY_SEQUENTIAL_MODE_WARNING =
    "Movies category-bulk sync downgraded to sequential mode after provider stress signals."
private const val LIVE_CATEGORY_SEQUENTIAL_MODE_WARNING =
    "Live category sync downgraded to sequential mode after provider stress signals."
private const val SERIES_CATEGORY_SEQUENTIAL_MODE_WARNING =
    "Series category sync downgraded to sequential mode after provider stress signals."
private const val MOVIE_PAGED_SEQUENTIAL_MODE_WARNING =
    "Movies paged sync downgraded to sequential mode after provider stress signals."
private const val SERIES_PAGED_SEQUENTIAL_MODE_WARNING =
    "Series paged sync downgraded to sequential mode after provider stress signals."
private const val XTREAM_RECOVERY_ABORT_WARNING_SUFFIX =
    "recovery stopped early after repeated provider stress signals; keeping recovered results."
private const val XTREAM_AVOID_FULL_CATALOG_COOLDOWN_MILLIS = 6 * 60 * 60 * 1000L
private const val XTREAM_MOVIE_REQUEST_TIMEOUT_MILLIS = 60_000L
private const val XTREAM_SERIES_REQUEST_TIMEOUT_MILLIS = 60_000L

enum class SyncRepairSection {
    LIVE,
    EPG,
    MOVIES,
    SERIES
}

@Singleton
class SyncManager @Inject constructor(
    private val providerDao: ProviderDao,
    private val channelDao: ChannelDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val programDao: ProgramDao,
    private val categoryDao: CategoryDao,
    private val catalogSyncDao: CatalogSyncDao,
    private val xtreamJson: Json,
    private val m3uParser: M3uParser,
    private val epgRepository: EpgRepository,
    private val okHttpClient: OkHttpClient,
    private val syncMetadataRepository: SyncMetadataRepository,
    private val transactionRunner: DatabaseTransactionRunner,
    private val preferencesRepository: com.streamvault.data.preferences.PreferencesRepository
) {
    private data class SyncOutcome(
        val partial: Boolean = false,
        val warnings: List<String> = emptyList()
    )

    private sealed interface CatalogStrategyResult<out T> {
        val strategyName: String

        data class Success<T>(
            override val strategyName: String,
            val items: List<T>,
            val warnings: List<String> = emptyList()
        ) : CatalogStrategyResult<T>

        data class Partial<T>(
            override val strategyName: String,
            val items: List<T>,
            val warnings: List<String>
        ) : CatalogStrategyResult<T>

        data class Failure(
            override val strategyName: String,
            val error: Throwable,
            val warnings: List<String> = emptyList()
        ) : CatalogStrategyResult<Nothing>

        data class EmptyValid(
            override val strategyName: String,
            val warnings: List<String> = emptyList()
        ) : CatalogStrategyResult<Nothing>
    }

    private data class XtreamStrategyFeedback(
        val preferredSegmentedFirst: Boolean = false,
        val attemptedFullCatalog: Boolean = false,
        val fullCatalogUnsafe: Boolean = false,
        val segmentedStressDetected: Boolean = false
    )

    private data class MovieCatalogSyncResult(
        val catalogResult: CatalogStrategyResult<Movie>,
        val categories: List<CategoryEntity>?,
        val syncMode: VodSyncMode,
        val warnings: List<String> = emptyList(),
        val strategyFeedback: XtreamStrategyFeedback = XtreamStrategyFeedback(),
        val stagedSessionId: Long? = null,
        val stagedAcceptedCount: Int = 0
    )

    private data class CatalogSyncPayload<T>(
        val catalogResult: CatalogStrategyResult<T>,
        val categories: List<CategoryEntity>?,
        val warnings: List<String> = emptyList(),
        val strategyFeedback: XtreamStrategyFeedback = XtreamStrategyFeedback(),
        val stagedSessionId: Long? = null,
        val stagedAcceptedCount: Int = 0
    )

    private data class SequentialProviderAdaptation(
        val rememberSequential: Boolean,
        val healthyStreak: Int
    )

    private sealed interface Attempt<out T> {
        data class Success<T>(val value: T) : Attempt<T>
        data class Failure(val error: Exception) : Attempt<Nothing>
    }

    private sealed interface CategoryFetchOutcome<out T> {
        data class Success<T>(val categoryName: String, val items: List<T>) : CategoryFetchOutcome<T>
        data class Empty(val categoryName: String) : CategoryFetchOutcome<Nothing>
        data class Failure(val categoryName: String, val error: Throwable) : CategoryFetchOutcome<Nothing>
    }

    private data class TimedCategoryOutcome<T>(
        val category: XtreamCategory,
        val outcome: CategoryFetchOutcome<T>,
        val elapsedMs: Long
    )

    private sealed interface PageFetchOutcome<out T> {
        data class Success<T>(val items: List<T>, val rawCount: Int) : PageFetchOutcome<T>
        data class Empty(val page: Int) : PageFetchOutcome<Nothing>
        data class Failure(val page: Int, val error: Throwable) : PageFetchOutcome<Nothing>
    }

    private data class TimedPageOutcome<T>(
        val page: Int,
        val outcome: PageFetchOutcome<T>,
        val elapsedMs: Long
    )

    private data class CategoryExecutionPlan<T>(
        val outcomes: List<TimedCategoryOutcome<T>>,
        val warnings: List<String> = emptyList()
    )

    private data class PageExecutionPlan<T>(
        val outcomes: List<TimedPageOutcome<T>>,
        val warnings: List<String> = emptyList(),
        val stoppedEarly: Boolean = false
    )

    private data class M3uImportStats(
        val header: M3uParser.M3uHeader,
        val liveCount: Int,
        val movieCount: Int,
        val warnings: List<String> = emptyList()
    )

    private data class StreamedPlaylist(
        val inputStream: InputStream,
        val contentEncoding: String? = null,
        val sourceName: String? = null
    )

    private class StableLongHasher {
        private val digest = MessageDigest.getInstance("SHA-256")

        fun hash(input: String): Long {
            val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
            var result = 0L
            for (i in 0 until 8) {
                result = (result shl 8) or (bytes[i].toLong() and 0xFF)
            }
            return result and Long.MAX_VALUE
        }
    }

    private class CategoryAccumulator(
        private val providerId: Long,
        private val type: ContentType,
        private val hasher: StableLongHasher
    ) {
        private val categoryIds = LinkedHashMap<String, Long>()
        val count: Int
            get() = categoryIds.size

        fun idFor(name: String): Long {
            return categoryIds.getOrPut(name) { stableId(providerId, type, name, hasher) }
        }

        fun entities(): List<CategoryEntity> {
            return categoryIds.map { (name, id) ->
                CategoryEntity(
                    categoryId = id,
                    name = name,
                    parentId = 0,
                    type = type,
                    providerId = providerId,
                    isAdult = AdultContentClassifier.isAdultCategoryName(name)
                )
            }
        }

        /** Generates a stable, collision-resistant ID from the provider+type+name triple. */
        private fun stableId(
            providerId: Long,
            type: ContentType,
            name: String,
            hasher: StableLongHasher
        ): Long {
            return hasher.hash("$providerId/${type.name}/$name").coerceAtLeast(1L)
        }
    }

    private val syncStateTracker = SyncStateTracker()
    private val syncErrorSanitizer = SyncErrorSanitizer()
    private val xtreamAdaptiveSyncPolicy = XtreamAdaptiveSyncPolicy()
    private val syncCatalogStore = SyncCatalogStore(
        movieDao = movieDao,
        catalogSyncDao = catalogSyncDao,
        transactionRunner = transactionRunner
    )
    val syncState: StateFlow<SyncState> = syncStateTracker.aggregateState
    val syncStatesByProvider: StateFlow<Map<Long, SyncState>> = syncStateTracker.statesByProvider
    private val providerSyncMutexes = ConcurrentHashMap<Long, Mutex>()
    private val xtreamCatalogHttpService: OkHttpXtreamApiService by lazy {
        OkHttpXtreamApiService(
            client = okHttpClient,
            json = xtreamJson
        )
    }
    private val xtreamCatalogApiService: XtreamApiService by lazy { xtreamCatalogHttpService }

    fun syncStateForProvider(providerId: Long): Flow<SyncState> =
        syncStatesByProvider.map { states -> states[providerId] ?: SyncState.Idle }

    fun currentSyncState(providerId: Long): SyncState =
        syncStateTracker.current(providerId)

    private suspend fun <T> withProviderLock(providerId: Long, block: suspend () -> T): T {
        val mutex = providerSyncMutexes.computeIfAbsent(providerId) { Mutex() }
        return mutex.withLock { block() }
    }

    suspend fun onProviderDeleted(providerId: Long) {
        syncStateTracker.reset(providerId)
        providerSyncMutexes.remove(providerId)
        xtreamAdaptiveSyncPolicy.forgetProvider(providerId)
        syncCatalogStore.clearProviderStaging(providerId)
    }

    suspend fun sync(
        providerId: Long,
        force: Boolean = false,
        movieFastSyncOverride: Boolean? = null,
        onProgress: ((String) -> Unit)? = null
    ): com.streamvault.domain.model.Result<Unit> = withProviderLock(providerId) lock@{
        val providerEntity = providerDao.getById(providerId)
            ?: return@lock com.streamvault.domain.model.Result.error("Provider $providerId not found")

        val provider = providerEntity
            .copy(password = CredentialCrypto.decryptIfNeeded(providerEntity.password))
            .toDomain()
            .let { resolvedProvider ->
                movieFastSyncOverride?.let { override ->
                    resolvedProvider.copy(xtreamFastSyncEnabled = override)
                } ?: resolvedProvider
            }
        publishSyncState(providerId, SyncState.Syncing("Starting..."))

        try {
            val outcome = withContext(Dispatchers.IO) {
                when (provider.type) {
                    ProviderType.XTREAM_CODES -> syncXtream(provider, force, onProgress)
                    ProviderType.M3U -> syncM3u(provider, force, onProgress)
                }
            }
            providerDao.updateSyncTime(providerId, System.currentTimeMillis())
            updateSyncStatusMetadata(
                providerId = providerId,
                status = if (outcome.partial) "PARTIAL" else "SUCCESS"
            )
            publishSyncState(providerId, if (outcome.partial) {
                SyncState.Partial("Sync completed with warnings", outcome.warnings)
            } else {
                SyncState.Success()
            })
            com.streamvault.domain.model.Result.success(Unit)
        } catch (e: CancellationException) {
            resetState(providerId)
            throw e
        } catch (e: Exception) {
            val safeMessage = syncErrorSanitizer.userMessage(e, "Sync failed")
            Log.e(TAG, "Sync failed for provider $providerId: ${syncErrorSanitizer.throwableMessage(e)}")
            updateSyncStatusMetadata(providerId = providerId, status = "ERROR")
            publishSyncState(providerId, SyncState.Error(safeMessage, e))
            com.streamvault.domain.model.Result.error(safeMessage, e)
        }
    }

    private class FallbackCategoryCollector(
        private val providerId: Long,
        private val type: ContentType
    ) {
        private val categories = LinkedHashMap<Long, CategoryEntity>()

        fun record(categoryId: Long?, categoryName: String?, isAdult: Boolean) {
            val resolvedCategoryId = categoryId ?: return
            val resolvedCategoryName = categoryName?.trim().takeUnless { it.isNullOrEmpty() }
                ?: "Category $resolvedCategoryId"
            categories.putIfAbsent(
                resolvedCategoryId,
                CategoryEntity(
                    categoryId = resolvedCategoryId,
                    name = resolvedCategoryName,
                    parentId = 0,
                    type = type,
                    providerId = providerId,
                    isAdult = isAdult || AdultContentClassifier.isAdultCategoryName(resolvedCategoryName)
                )
            )
        }

        fun entities(): List<CategoryEntity> = categories.values.toList()
    }

    private data class StagedCatalogSnapshot(
        val sessionId: Long?,
        val acceptedCount: Int,
        val fallbackCategories: List<CategoryEntity>?
    )

    fun resetState(providerId: Long? = null) {
        syncStateTracker.reset(providerId)
    }

    suspend fun retrySection(
        providerId: Long,
        section: SyncRepairSection,
        movieFastSyncOverride: Boolean? = null,
        onProgress: ((String) -> Unit)? = null
    ): com.streamvault.domain.model.Result<Unit> = withProviderLock(providerId) lock@{
        val providerEntity = providerDao.getById(providerId)
            ?: return@lock com.streamvault.domain.model.Result.error("Provider $providerId not found")

        val provider = providerEntity
            .copy(password = CredentialCrypto.decryptIfNeeded(providerEntity.password))
            .toDomain()
            .let { resolvedProvider ->
                movieFastSyncOverride?.let { override ->
                    resolvedProvider.copy(xtreamFastSyncEnabled = override)
                } ?: resolvedProvider
            }

        try {
            withContext(Dispatchers.IO) {
                when (section) {
                    SyncRepairSection.LIVE -> syncLiveOnly(provider, onProgress)
                    SyncRepairSection.EPG -> syncEpgOnly(provider, onProgress)
                    SyncRepairSection.MOVIES -> syncMoviesOnly(provider, onProgress)
                    SyncRepairSection.SERIES -> syncSeriesOnly(provider, onProgress)
                }
            }
            updateSyncStatusMetadata(providerId = providerId, status = "SUCCESS")
            publishSyncState(providerId, SyncState.Success())
            com.streamvault.domain.model.Result.success(Unit)
        } catch (e: CancellationException) {
            resetState(providerId)
            throw e
        } catch (e: Exception) {
            val safeMessage = syncErrorSanitizer.userMessage(e, "Retry failed")
            Log.e(TAG, "Section retry failed for provider $providerId [$section]: ${syncErrorSanitizer.throwableMessage(e)}")
            updateSyncStatusMetadata(providerId = providerId, status = "ERROR")
            publishSyncState(providerId, SyncState.Error(safeMessage, e))
            com.streamvault.domain.model.Result.error(safeMessage, e)
        }
    }

    private suspend fun syncXtream(
        provider: Provider,
        force: Boolean,
        onProgress: ((String) -> Unit)?
    ): SyncOutcome {
        val warnings = mutableListOf<String>()
        UrlSecurityPolicy.validateXtreamServerUrl(provider.serverUrl)?.let { message ->
            throw IllegalStateException(message)
        }
        progress(provider.id, onProgress, "Connecting to server...")
        val useTextClassification = preferencesRepository.useXtreamTextClassification.first()
        val api = createXtreamSyncProvider(provider, useTextClassification)

        var metadata = syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id)
        val now = System.currentTimeMillis()

        if (force || ContentCachePolicy.shouldRefresh(metadata.lastLiveSync, ContentCachePolicy.CATALOG_TTL_MILLIS, now)) {
            progress(provider.id, onProgress, "Downloading Live TV...")
            val liveSyncResult = syncXtreamLiveCatalog(
                provider = provider,
                api = api,
                existingMetadata = metadata,
                onProgress = onProgress
            )
            val liveSequentialStress = liveSyncResult.strategyFeedback.segmentedStressDetected
            val liveProviderAdaptation = updateSequentialProviderAdaptation(
                previousRemembered = metadata.liveSequentialFailuresRemembered,
                previousHealthyStreak = metadata.liveHealthySyncStreak,
                sawSequentialStress = liveSequentialStress
            )
            val liveAvoidFullUntil = updateAvoidFullUntil(
                previousAvoidFullUntil = metadata.liveAvoidFullUntil,
                now = now,
                feedback = liveSyncResult.strategyFeedback
            )

            when (val liveResult = liveSyncResult.catalogResult) {
                is CatalogStrategyResult.Success -> {
                    val acceptedCount = syncCatalogStore.replaceLiveCatalog(
                        providerId = provider.id,
                        categories = liveSyncResult.categories,
                        channels = liveResult.items.map { it.toEntity() }
                    )
                    metadata = metadata.copy(
                        lastLiveSync = now,
                        liveCount = acceptedCount,
                        liveAvoidFullUntil = liveAvoidFullUntil,
                        liveSequentialFailuresRemembered = liveProviderAdaptation.rememberSequential,
                        liveHealthySyncStreak = liveProviderAdaptation.healthyStreak
                    )
                    warnings += liveSyncResult.warnings + liveResult.warnings
                }
                is CatalogStrategyResult.Partial -> {
                    val acceptedCount = syncCatalogStore.replaceLiveCatalog(
                        providerId = provider.id,
                        categories = liveSyncResult.categories,
                        channels = liveResult.items.map { it.toEntity() }
                    )
                    metadata = metadata.copy(
                        lastLiveSync = now,
                        liveCount = acceptedCount,
                        liveAvoidFullUntil = liveAvoidFullUntil,
                        liveSequentialFailuresRemembered = metadata.liveSequentialFailuresRemembered || liveSequentialStress,
                        liveHealthySyncStreak = 0
                    )
                    warnings += liveSyncResult.warnings + liveResult.warnings + listOf("Live TV sync completed partially.")
                }
                is CatalogStrategyResult.EmptyValid -> {
                    val existingChannelCount = channelDao.getCount(provider.id).first()
                    Log.w(TAG, "Live TV sync kept existing catalog for provider ${provider.id}: empty valid result, existingCount=$existingChannelCount")
                    metadata = metadata.copy(
                        liveAvoidFullUntil = liveAvoidFullUntil,
                        liveSequentialFailuresRemembered = metadata.liveSequentialFailuresRemembered || liveSequentialStress,
                        liveHealthySyncStreak = 0
                    )
                    warnings += liveSyncResult.warnings + liveResult.warnings +
                        if (existingChannelCount > 0) {
                            listOf("Live TV refresh returned an empty valid catalog; keeping previous channel library.")
                        } else {
                            listOf("Live TV refresh returned an empty valid catalog with no previous channel library.")
                        }
                    if (existingChannelCount == 0) {
                        throw IllegalStateException("Live TV catalog was empty.")
                    }
                }
                is CatalogStrategyResult.Failure -> {
                    val existingChannelCount = channelDao.getCount(provider.id).first()
                    Log.w(TAG, "Live TV sync preserved previous catalog for provider ${provider.id}: strategy=${liveResult.strategyName}, existingCount=$existingChannelCount, reason=${sanitizeThrowableMessage(liveResult.error)}")
                    metadata = metadata.copy(
                        liveAvoidFullUntil = liveAvoidFullUntil,
                        liveSequentialFailuresRemembered = metadata.liveSequentialFailuresRemembered || liveSequentialStress || shouldRememberSequentialPreference(liveResult.error),
                        liveHealthySyncStreak = 0
                    )
                    warnings += liveSyncResult.warnings + liveResult.warnings +
                        if (existingChannelCount > 0) {
                            listOf("Live TV sync degraded; keeping previous channel library.")
                        } else {
                            listOf("Live TV sync failed before any usable channel catalog was available.")
                        }
                    if (existingChannelCount == 0) {
                        throw IllegalStateException(
                            "Failed to fetch live streams: ${syncErrorSanitizer.throwableMessage(liveResult.error)}"
                        )
                    }
                }
            }
            syncMetadataRepository.updateMetadata(metadata)
        }

        if (force || ContentCachePolicy.shouldRefresh(metadata.lastMovieSync, ContentCachePolicy.CATALOG_TTL_MILLIS, now)) {
            progress(
                provider.id,
                onProgress,
                if (provider.xtreamFastSyncEnabled) "Loading movie categories..." else "Downloading Movies..."
            )
            val movieSyncResult = syncXtreamMoviesCatalog(
                provider = provider,
                api = api,
                existingMetadata = metadata,
                onProgress = onProgress
            )
            val sawSequentialStress = movieSyncResult.strategyFeedback.segmentedStressDetected
            val healthyMovieProviderAdaptation = updateSequentialProviderAdaptation(
                previousRemembered = metadata.movieParallelFailuresRemembered,
                previousHealthyStreak = metadata.movieHealthySyncStreak,
                sawSequentialStress = sawSequentialStress
            )
            val movieAvoidFullUntil = updateAvoidFullUntil(
                previousAvoidFullUntil = metadata.movieAvoidFullUntil,
                now = now,
                feedback = movieSyncResult.strategyFeedback
            )

            when (val catalogResult = movieSyncResult.catalogResult) {
                is CatalogStrategyResult.Success -> {
                    val acceptedCount = if (movieSyncResult.syncMode == VodSyncMode.FAST_SYNC) {
                        movieSyncResult.categories?.let {
                            syncCatalogStore.replaceCategories(provider.id, "MOVIE", it)
                        }
                        movieDao.getCount(provider.id).first()
                    } else {
                        movieSyncResult.stagedSessionId?.let { sessionId ->
                            syncCatalogStore.applyStagedMovieCatalog(provider.id, sessionId, movieSyncResult.categories)
                            movieSyncResult.stagedAcceptedCount
                        } ?: syncCatalogStore.replaceMovieCatalog(
                            providerId = provider.id,
                            categories = movieSyncResult.categories,
                            movies = catalogResult.items.asSequence().map { movie -> movie.toEntity() }
                        )
                    }
                    metadata = metadata.copy(
                        lastMovieSync = now,
                        lastMovieAttempt = now,
                        lastMovieSuccess = now,
                        movieCount = acceptedCount,
                        movieSyncMode = movieSyncResult.syncMode,
                        movieWarningsCount = (movieSyncResult.warnings + catalogResult.warnings).size,
                        movieCatalogStale = false,
                        movieAvoidFullUntil = movieAvoidFullUntil,
                        movieParallelFailuresRemembered = healthyMovieProviderAdaptation.rememberSequential,
                        movieHealthySyncStreak = healthyMovieProviderAdaptation.healthyStreak
                    )
                }
                is CatalogStrategyResult.Partial -> {
                    val acceptedCount = movieSyncResult.stagedSessionId?.let { sessionId ->
                        syncCatalogStore.applyStagedMovieCatalog(provider.id, sessionId, movieSyncResult.categories)
                        movieSyncResult.stagedAcceptedCount
                    } ?: syncCatalogStore.replaceMovieCatalog(
                        providerId = provider.id,
                        categories = movieSyncResult.categories,
                        movies = catalogResult.items.asSequence().map { movie -> movie.toEntity() }
                    )
                    metadata = metadata.copy(
                        lastMovieSync = now,
                        lastMovieAttempt = now,
                        lastMoviePartial = now,
                        movieCount = acceptedCount,
                        movieSyncMode = movieSyncResult.syncMode,
                        movieWarningsCount = (movieSyncResult.warnings + catalogResult.warnings).size,
                        movieCatalogStale = true,
                        movieAvoidFullUntil = movieAvoidFullUntil,
                        movieParallelFailuresRemembered = metadata.movieParallelFailuresRemembered || sawSequentialStress,
                        movieHealthySyncStreak = 0
                    )
                    warnings += movieSyncResult.warnings + catalogResult.warnings
                }
                is CatalogStrategyResult.EmptyValid -> {
                    val existingMovieCount = movieDao.getCount(provider.id).first()
                    Log.w(TAG, "Movies sync kept existing catalog for provider ${provider.id}: empty valid result, existingCount=$existingMovieCount")
                    metadata = metadata.copy(
                        lastMovieAttempt = now,
                        movieSyncMode = movieSyncResult.syncMode,
                        movieWarningsCount = (movieSyncResult.warnings + catalogResult.warnings).size,
                        movieCatalogStale = existingMovieCount > 0 || movieSyncResult.syncMode == VodSyncMode.LAZY_BY_CATEGORY,
                        movieAvoidFullUntil = movieAvoidFullUntil,
                        movieParallelFailuresRemembered = metadata.movieParallelFailuresRemembered || sawSequentialStress,
                        movieHealthySyncStreak = 0
                    )
                    warnings += movieSyncResult.warnings + catalogResult.warnings +
                        if (existingMovieCount > 0) {
                            listOf("Movies refresh returned an empty valid catalog; keeping previous movie library.")
                        } else {
                            listOf("Movies refresh returned an empty valid catalog with no previous movie library.")
                        }
                }
                is CatalogStrategyResult.Failure -> {
                    val existingMovieCount = movieDao.getCount(provider.id).first()
                    val finalMode = if (movieSyncResult.syncMode == VodSyncMode.LAZY_BY_CATEGORY) {
                        VodSyncMode.LAZY_BY_CATEGORY
                    } else {
                        metadata.movieSyncMode
                    }
                    if (movieSyncResult.syncMode == VodSyncMode.LAZY_BY_CATEGORY) {
                        movieSyncResult.categories?.let { syncCatalogStore.replaceCategories(provider.id, "MOVIE", it) }
                    }
                    Log.w(TAG, "Movies sync preserved previous catalog for provider ${provider.id}: strategy=${catalogResult.strategyName}, existingCount=$existingMovieCount, mode=${movieSyncResult.syncMode}, reason=${sanitizeThrowableMessage(catalogResult.error)}")
                    metadata = metadata.copy(
                        lastMovieAttempt = now,
                        movieSyncMode = finalMode,
                        movieWarningsCount = (movieSyncResult.warnings + catalogResult.warnings).size,
                        movieCatalogStale = existingMovieCount > 0 || movieSyncResult.syncMode == VodSyncMode.LAZY_BY_CATEGORY,
                        movieAvoidFullUntil = movieAvoidFullUntil,
                        movieParallelFailuresRemembered = metadata.movieParallelFailuresRemembered || sawSequentialStress || shouldRememberSequentialPreference(catalogResult.error),
                        movieHealthySyncStreak = 0
                    )
                    warnings += movieSyncResult.warnings + catalogResult.warnings +
                        if (existingMovieCount > 0) {
                            listOf("Movies sync degraded; keeping previous movie library.")
                        } else {
                            listOf("Movies sync failed; continuing with the rest of the provider sync.")
                        }
                }
            }
            syncMetadataRepository.updateMetadata(metadata)
        }

        if (force || ContentCachePolicy.shouldRefresh(metadata.lastSeriesSync, ContentCachePolicy.CATALOG_TTL_MILLIS, now)) {
            progress(provider.id, onProgress, "Downloading Series...")
            val seriesSyncResult = syncXtreamSeriesCatalog(
                provider = provider,
                api = api,
                existingMetadata = metadata,
                onProgress = onProgress
            )
            val seriesSequentialStress = seriesSyncResult.strategyFeedback.segmentedStressDetected
            val seriesProviderAdaptation = updateSequentialProviderAdaptation(
                previousRemembered = metadata.seriesSequentialFailuresRemembered,
                previousHealthyStreak = metadata.seriesHealthySyncStreak,
                sawSequentialStress = seriesSequentialStress
            )
            val seriesAvoidFullUntil = updateAvoidFullUntil(
                previousAvoidFullUntil = metadata.seriesAvoidFullUntil,
                now = now,
                feedback = seriesSyncResult.strategyFeedback
            )

            when (val seriesResult = seriesSyncResult.catalogResult) {
                is CatalogStrategyResult.Success -> {
                    val acceptedCount = seriesSyncResult.stagedSessionId?.let { sessionId ->
                        syncCatalogStore.applyStagedSeriesCatalog(provider.id, sessionId, seriesSyncResult.categories)
                        seriesSyncResult.stagedAcceptedCount
                    } ?: syncCatalogStore.replaceSeriesCatalog(
                        providerId = provider.id,
                        categories = seriesSyncResult.categories,
                        series = seriesResult.items.asSequence().map { it.toEntity() }
                    )
                    metadata = metadata.copy(
                        lastSeriesSync = now,
                        seriesCount = acceptedCount,
                        seriesAvoidFullUntil = seriesAvoidFullUntil,
                        seriesSequentialFailuresRemembered = seriesProviderAdaptation.rememberSequential,
                        seriesHealthySyncStreak = seriesProviderAdaptation.healthyStreak
                    )
                    warnings += seriesSyncResult.warnings + seriesResult.warnings
                }
                is CatalogStrategyResult.Partial -> {
                    val acceptedCount = seriesSyncResult.stagedSessionId?.let { sessionId ->
                        syncCatalogStore.applyStagedSeriesCatalog(provider.id, sessionId, seriesSyncResult.categories)
                        seriesSyncResult.stagedAcceptedCount
                    } ?: syncCatalogStore.replaceSeriesCatalog(
                        providerId = provider.id,
                        categories = seriesSyncResult.categories,
                        series = seriesResult.items.asSequence().map { it.toEntity() }
                    )
                    metadata = metadata.copy(
                        lastSeriesSync = now,
                        seriesCount = acceptedCount,
                        seriesAvoidFullUntil = seriesAvoidFullUntil,
                        seriesSequentialFailuresRemembered = metadata.seriesSequentialFailuresRemembered || seriesSequentialStress,
                        seriesHealthySyncStreak = 0
                    )
                    warnings += seriesSyncResult.warnings + seriesResult.warnings + listOf("Series sync completed partially.")
                }
                is CatalogStrategyResult.EmptyValid -> {
                    val existingSeriesCount = seriesDao.getCount(provider.id).first()
                    Log.w(TAG, "Series sync kept existing catalog for provider ${provider.id}: empty valid result, existingCount=$existingSeriesCount")
                    metadata = metadata.copy(
                        seriesAvoidFullUntil = seriesAvoidFullUntil,
                        seriesSequentialFailuresRemembered = metadata.seriesSequentialFailuresRemembered || seriesSequentialStress,
                        seriesHealthySyncStreak = 0
                    )
                    warnings += seriesSyncResult.warnings + seriesResult.warnings +
                        if (existingSeriesCount > 0) {
                            listOf("Series refresh returned an empty valid catalog; keeping previous series library.")
                        } else {
                            listOf("Series refresh returned an empty valid catalog with no previous series library.")
                        }
                    if (existingSeriesCount == 0) {
                        throw IllegalStateException("Series catalog was empty.")
                    }
                }
                is CatalogStrategyResult.Failure -> {
                    val existingSeriesCount = seriesDao.getCount(provider.id).first()
                    val enteringLazyMode = seriesResult.strategyName == "lazy_by_category" && !seriesSyncResult.categories.isNullOrEmpty()
                    if (enteringLazyMode) {
                        seriesSyncResult.categories?.let { syncCatalogStore.replaceCategories(provider.id, "SERIES", it) }
                    }
                    Log.w(TAG, "Series sync preserved previous catalog for provider ${provider.id}: strategy=${seriesResult.strategyName}, existingCount=$existingSeriesCount, reason=${sanitizeThrowableMessage(seriesResult.error)}")
                    metadata = metadata.copy(
                        lastSeriesSync = if (enteringLazyMode) now else metadata.lastSeriesSync,
                        seriesAvoidFullUntil = seriesAvoidFullUntil,
                        seriesSequentialFailuresRemembered = metadata.seriesSequentialFailuresRemembered || seriesSequentialStress || shouldRememberSequentialPreference(seriesResult.error),
                        seriesHealthySyncStreak = 0
                    )
                    warnings += seriesSyncResult.warnings + seriesResult.warnings +
                        if (enteringLazyMode) {
                            listOf("Series entered lazy category-only mode.")
                        } else if (existingSeriesCount > 0) {
                            listOf("Series sync degraded; keeping previous series library.")
                        } else {
                            listOf("Series sync failed before any usable series catalog was available.")
                        }
                    if (existingSeriesCount == 0 && !enteringLazyMode) {
                        throw IllegalStateException(
                            "Failed to fetch series catalog: ${syncErrorSanitizer.throwableMessage(seriesResult.error)}"
                        )
                    }
                }
            }
            syncMetadataRepository.updateMetadata(metadata)
        }

        if (force || ContentCachePolicy.shouldRefresh(metadata.lastEpgSync, ContentCachePolicy.EPG_TTL_MILLIS, now)) {
            try {
                progress(provider.id, onProgress, "Downloading EPG...")
                val base = provider.serverUrl.trimEnd('/')
                val xmltvUrl = provider.epgUrl.ifBlank {
                    XtreamUrlFactory.buildXmltvUrl(base, provider.username, provider.password)
                }
                UrlSecurityPolicy.validateXtreamEpgUrl(xmltvUrl)?.let { message ->
                    throw IllegalStateException(message)
                }
                retryTransient { epgRepository.refreshEpg(provider.id, xmltvUrl) }
                val epgCount = programDao.countByProvider(provider.id)
                metadata = metadata.copy(
                    lastEpgSync = now,
                    epgCount = epgCount
                )
                syncMetadataRepository.updateMetadata(metadata)
                if (epgCount == 0) {
                    warnings.add("EPG imported zero programs; live guide will fall back to on-demand Xtream data.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "EPG sync failed (non-fatal): ${sanitizeThrowableMessage(e)}")
                warnings.add("EPG XMLTV sync failed; live guide will fall back to on-demand Xtream data.")
            }
        }

        return if (warnings.isEmpty()) SyncOutcome() else SyncOutcome(partial = true, warnings = warnings)
    }

    private suspend fun syncM3u(
        provider: Provider,
        force: Boolean,
        onProgress: ((String) -> Unit)?
    ): SyncOutcome {
        val warnings = mutableListOf<String>()
        var metadata = syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id)
        val now = System.currentTimeMillis()

        if (force || ContentCachePolicy.shouldRefresh(metadata.lastLiveSync, ContentCachePolicy.CATALOG_TTL_MILLIS, now)) {
            val stats = withContext(Dispatchers.IO) { importM3uPlaylist(provider, onProgress) }
            if (stats.liveCount == 0 && stats.movieCount == 0) {
                throw IllegalStateException("Playlist is empty or contains no supported entries")
            }
            warnings += stats.warnings
            if (provider.epgUrl.isBlank() && !stats.header.tvgUrl.isNullOrBlank()) {
                providerDao.updateEpgUrl(provider.id, stats.header.tvgUrl)
            }
            metadata = metadata.copy(
                lastLiveSync = now,
                lastMovieSync = now,
                lastSeriesSync = now,
                lastMovieAttempt = now,
                lastMovieSuccess = now,
                liveCount = stats.liveCount,
                movieCount = stats.movieCount,
                movieSyncMode = VodSyncMode.FULL,
                movieWarningsCount = stats.warnings.size,
                movieCatalogStale = false,
                movieHealthySyncStreak = 0
            )
            syncMetadataRepository.updateMetadata(metadata)
        }

        val currentEpgUrl = providerDao.getById(provider.id)?.epgUrl ?: provider.epgUrl
        if (!currentEpgUrl.isNullOrBlank() && (force || ContentCachePolicy.shouldRefresh(metadata.lastEpgSync, ContentCachePolicy.EPG_TTL_MILLIS, now))) {
            val epgValidationError = UrlSecurityPolicy.validateOptionalEpgUrl(currentEpgUrl)
            if (epgValidationError != null) {
                warnings.add(epgValidationError)
            } else {
                try {
                    progress(provider.id, onProgress, "Downloading EPG...")
                    retryTransient { epgRepository.refreshEpg(provider.id, currentEpgUrl) }
                    metadata = metadata.copy(
                        lastEpgSync = now,
                        epgCount = programDao.countByProvider(provider.id)
                    )
                    syncMetadataRepository.updateMetadata(metadata)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "EPG sync failed (non-fatal): ${sanitizeThrowableMessage(e)}")
                    warnings.add("EPG sync failed")
                }
            }
        }

        return if (warnings.isEmpty()) SyncOutcome() else SyncOutcome(partial = true, warnings = warnings)
    }

    private suspend fun syncEpgOnly(
        provider: Provider,
        onProgress: ((String) -> Unit)?
    ) {
        progress(provider.id, onProgress, "Retrying EPG...")
        val epgUrl = when (provider.type) {
            ProviderType.XTREAM_CODES -> {
                val base = provider.serverUrl.trimEnd('/')
                provider.epgUrl.ifBlank {
                    XtreamUrlFactory.buildXmltvUrl(base, provider.username, provider.password)
                }
            }
            ProviderType.M3U -> providerDao.getById(provider.id)?.epgUrl ?: provider.epgUrl
        }
        if (epgUrl.isBlank()) {
            throw IllegalStateException("No EPG URL configured for this provider")
        }
        val validationError = when (provider.type) {
            ProviderType.XTREAM_CODES -> UrlSecurityPolicy.validateXtreamEpgUrl(epgUrl)
            ProviderType.M3U -> UrlSecurityPolicy.validateOptionalEpgUrl(epgUrl)
        }
        validationError?.let { message ->
            throw IllegalStateException(message)
        }
        retryTransient { epgRepository.refreshEpg(provider.id, epgUrl) }
        val now = System.currentTimeMillis()
        val metadata = (syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id))
            .copy(
                lastEpgSync = now,
                epgCount = programDao.countByProvider(provider.id)
            )
        syncMetadataRepository.updateMetadata(metadata)
    }

    private suspend fun syncLiveOnly(
        provider: Provider,
        onProgress: ((String) -> Unit)?
    ) {
        val now = System.currentTimeMillis()
        when (provider.type) {
            ProviderType.XTREAM_CODES -> {
                progress(provider.id, onProgress, "Retrying Live TV...")
                val useTextClassification = preferencesRepository.useXtreamTextClassification.first()
                val api = createXtreamSyncProvider(provider, useTextClassification)
                val currentMetadata = syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id)
                val liveSyncResult = syncXtreamLiveCatalog(
                    provider = provider,
                    api = api,
                    existingMetadata = currentMetadata,
                    onProgress = onProgress
                )
                val liveSequentialStress = liveSyncResult.strategyFeedback.segmentedStressDetected
                val liveProviderAdaptation = updateSequentialProviderAdaptation(
                    previousRemembered = currentMetadata.liveSequentialFailuresRemembered,
                    previousHealthyStreak = currentMetadata.liveHealthySyncStreak,
                    sawSequentialStress = liveSequentialStress
                )
                val liveAvoidFullUntil = updateAvoidFullUntil(
                    previousAvoidFullUntil = currentMetadata.liveAvoidFullUntil,
                    now = now,
                    feedback = liveSyncResult.strategyFeedback
                )

                when (val liveResult = liveSyncResult.catalogResult) {
                    is CatalogStrategyResult.Success -> {
                        val acceptedCount = syncCatalogStore.replaceLiveCatalog(
                            providerId = provider.id,
                            categories = liveSyncResult.categories,
                            channels = liveResult.items.map { it.toEntity() }
                        )
                        syncMetadataRepository.updateMetadata(
                            currentMetadata.copy(
                                lastLiveSync = now,
                                liveCount = acceptedCount,
                                liveAvoidFullUntil = liveAvoidFullUntil,
                                liveSequentialFailuresRemembered = liveProviderAdaptation.rememberSequential,
                                liveHealthySyncStreak = liveProviderAdaptation.healthyStreak
                            )
                        )
                    }
                    is CatalogStrategyResult.Partial -> {
                        val acceptedCount = syncCatalogStore.replaceLiveCatalog(
                            providerId = provider.id,
                            categories = liveSyncResult.categories,
                            channels = liveResult.items.map { it.toEntity() }
                        )
                        syncMetadataRepository.updateMetadata(
                            currentMetadata.copy(
                                lastLiveSync = now,
                                liveCount = acceptedCount,
                                liveAvoidFullUntil = liveAvoidFullUntil,
                                liveSequentialFailuresRemembered = currentMetadata.liveSequentialFailuresRemembered || liveSequentialStress,
                                liveHealthySyncStreak = 0
                            )
                        )
                    }
                    is CatalogStrategyResult.EmptyValid -> {
                        val existingLiveCount = channelDao.getCount(provider.id).first()
                        syncMetadataRepository.updateMetadata(
                            currentMetadata.copy(
                                liveAvoidFullUntil = liveAvoidFullUntil,
                                liveSequentialFailuresRemembered = currentMetadata.liveSequentialFailuresRemembered || liveSequentialStress,
                                liveHealthySyncStreak = 0
                            )
                        )
                        throw IllegalStateException(
                            if (existingLiveCount > 0) {
                                "Live TV refresh returned an empty catalog; existing library was preserved."
                            } else {
                                "Live TV catalog was empty."
                            }
                        )
                    }
                    is CatalogStrategyResult.Failure -> {
                        syncMetadataRepository.updateMetadata(
                            currentMetadata.copy(
                                liveAvoidFullUntil = liveAvoidFullUntil,
                                liveSequentialFailuresRemembered = currentMetadata.liveSequentialFailuresRemembered || liveSequentialStress || shouldRememberSequentialPreference(liveResult.error),
                                liveHealthySyncStreak = 0
                            )
                        )
                        throw IllegalStateException(
                            syncErrorSanitizer.userMessage(liveResult.error, "Failed to fetch live channels"),
                            liveResult.error
                        )
                    }
                }
            }
            ProviderType.M3U -> {
                progress(provider.id, onProgress, "Retrying Live TV...")
                val stats = withContext(Dispatchers.IO) {
                    importM3uPlaylist(provider, onProgress, includeLive = true, includeMovies = false)
                }
                if (stats.liveCount == 0) {
                    throw IllegalStateException("Playlist contains no live TV entries")
                }
                val metadata = (syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id))
                    .copy(
                        lastLiveSync = now,
                        liveCount = stats.liveCount
                    )
                syncMetadataRepository.updateMetadata(metadata)
            }
        }
    }

    private suspend fun syncMoviesOnly(
        provider: Provider,
        onProgress: ((String) -> Unit)?
    ) {
        val now = System.currentTimeMillis()
        when (provider.type) {
            ProviderType.XTREAM_CODES -> {
                progress(
                    provider.id,
                    onProgress,
                    if (provider.xtreamFastSyncEnabled) "Refreshing movie categories..." else "Retrying Movies..."
                )
                val useTextClassification = preferencesRepository.useXtreamTextClassification.first()
                val api = createXtreamSyncProvider(provider, useTextClassification)
                val currentMetadata = syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id)
                val movieSyncResult = syncXtreamMoviesCatalog(
                    provider = provider,
                    api = api,
                    existingMetadata = currentMetadata,
                    onProgress = onProgress
                )
                val sawSequentialStress = movieSyncResult.strategyFeedback.segmentedStressDetected
                val healthyMovieProviderAdaptation = updateSequentialProviderAdaptation(
                    previousRemembered = currentMetadata.movieParallelFailuresRemembered,
                    previousHealthyStreak = currentMetadata.movieHealthySyncStreak,
                    sawSequentialStress = sawSequentialStress
                )
                val movieWarnings = movieSyncResult.warnings + strategyWarnings(movieSyncResult.catalogResult)
                val movieAvoidFullUntil = updateAvoidFullUntil(
                    previousAvoidFullUntil = currentMetadata.movieAvoidFullUntil,
                    now = now,
                    feedback = movieSyncResult.strategyFeedback
                )

                val metadata = currentMetadata.copy(
                    lastMovieAttempt = now,
                    movieSyncMode = movieSyncResult.syncMode,
                    movieWarningsCount = movieWarnings.size,
                    movieCatalogStale = movieSyncResult.syncMode == VodSyncMode.LAZY_BY_CATEGORY,
                    movieAvoidFullUntil = movieAvoidFullUntil,
                    movieParallelFailuresRemembered = currentMetadata.movieParallelFailuresRemembered || sawSequentialStress
                )

                when (val catalogResult = movieSyncResult.catalogResult) {
                    is CatalogStrategyResult.Success -> {
                        val acceptedCount = if (movieSyncResult.syncMode == VodSyncMode.FAST_SYNC) {
                            movieSyncResult.categories?.let {
                                syncCatalogStore.replaceCategories(provider.id, "MOVIE", it)
                            }
                            movieDao.getCount(provider.id).first()
                        } else {
                            movieSyncResult.stagedSessionId?.let { sessionId ->
                                syncCatalogStore.applyStagedMovieCatalog(provider.id, sessionId, movieSyncResult.categories)
                                movieSyncResult.stagedAcceptedCount
                            } ?: syncCatalogStore.replaceMovieCatalog(
                                providerId = provider.id,
                                categories = movieSyncResult.categories,
                                movies = catalogResult.items.asSequence().map { movie -> movie.toEntity() }
                            )
                        }
                        syncMetadataRepository.updateMetadata(
                            metadata.copy(
                                lastMovieSync = now,
                                lastMovieSuccess = now,
                                movieCount = acceptedCount,
                                movieCatalogStale = false,
                                movieAvoidFullUntil = movieAvoidFullUntil,
                                movieParallelFailuresRemembered = healthyMovieProviderAdaptation.rememberSequential,
                                movieHealthySyncStreak = healthyMovieProviderAdaptation.healthyStreak
                            )
                        )
                    }
                    is CatalogStrategyResult.Partial -> {
                        val acceptedCount = movieSyncResult.stagedSessionId?.let { sessionId ->
                            syncCatalogStore.applyStagedMovieCatalog(provider.id, sessionId, movieSyncResult.categories)
                            movieSyncResult.stagedAcceptedCount
                        } ?: syncCatalogStore.replaceMovieCatalog(
                            providerId = provider.id,
                            categories = movieSyncResult.categories,
                            movies = catalogResult.items.asSequence().map { movie -> movie.toEntity() }
                        )
                        syncMetadataRepository.updateMetadata(
                            metadata.copy(
                                lastMovieSync = now,
                                lastMoviePartial = now,
                                movieCount = acceptedCount,
                                movieCatalogStale = true,
                                movieAvoidFullUntil = movieAvoidFullUntil,
                                movieParallelFailuresRemembered = currentMetadata.movieParallelFailuresRemembered || sawSequentialStress,
                                movieHealthySyncStreak = 0
                            )
                        )
                    }
                    is CatalogStrategyResult.EmptyValid -> {
                        val existingMovieCount = movieDao.getCount(provider.id).first()
                        syncMetadataRepository.updateMetadata(
                            metadata.copy(
                                movieCatalogStale = existingMovieCount > 0 || movieSyncResult.syncMode == VodSyncMode.LAZY_BY_CATEGORY,
                                movieAvoidFullUntil = movieAvoidFullUntil,
                                movieParallelFailuresRemembered = currentMetadata.movieParallelFailuresRemembered || sawSequentialStress,
                                movieHealthySyncStreak = 0
                            )
                        )
                        throw IllegalStateException(
                            if (existingMovieCount > 0) {
                                "Movies refresh returned an empty catalog; existing library was preserved."
                            } else {
                                "Movies refresh returned an empty catalog."
                            }
                        )
                    }
                    is CatalogStrategyResult.Failure -> {
                        val existingMovieCount = movieDao.getCount(provider.id).first()
                        if (movieSyncResult.syncMode == VodSyncMode.LAZY_BY_CATEGORY) {
                            movieSyncResult.categories?.let { syncCatalogStore.replaceCategories(provider.id, "MOVIE", it) }
                        }
                        syncMetadataRepository.updateMetadata(
                            metadata.copy(
                                movieCatalogStale = existingMovieCount > 0 || movieSyncResult.syncMode == VodSyncMode.LAZY_BY_CATEGORY,
                                movieAvoidFullUntil = movieAvoidFullUntil,
                                movieParallelFailuresRemembered = currentMetadata.movieParallelFailuresRemembered || sawSequentialStress || shouldRememberSequentialPreference(catalogResult.error),
                                movieHealthySyncStreak = 0
                            )
                        )
                        throw IllegalStateException(
                            syncErrorSanitizer.userMessage(catalogResult.error, "Failed to fetch VOD streams"),
                            catalogResult.error
                        )
                    }
                }
            }
            ProviderType.M3U -> {
                progress(provider.id, onProgress, "Retrying Movies...")
                val stats = withContext(Dispatchers.IO) {
                    importM3uPlaylist(provider, onProgress, includeLive = false, includeMovies = true)
                }
                if (stats.movieCount == 0) {
                    throw IllegalStateException("Playlist contains no movie entries")
                }
                val metadata = (syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id))
                    .copy(
                        lastMovieSync = now,
                        lastMovieAttempt = now,
                        lastMovieSuccess = now,
                        movieCount = stats.movieCount,
                        movieSyncMode = VodSyncMode.FULL,
                        movieWarningsCount = stats.warnings.size,
                        movieCatalogStale = false
                    )
                syncMetadataRepository.updateMetadata(metadata)
            }
        }
    }

    private suspend fun syncSeriesOnly(
        provider: Provider,
        onProgress: ((String) -> Unit)?
    ) {
        if (provider.type != ProviderType.XTREAM_CODES) {
            throw IllegalStateException("Series retry is available only for Xtream providers")
        }
        progress(provider.id, onProgress, "Retrying Series...")
        val useTextClassification = preferencesRepository.useXtreamTextClassification.first()
        val api = createXtreamSyncProvider(provider, useTextClassification)
        val currentMetadata = syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id)
        val seriesSyncResult = syncXtreamSeriesCatalog(
            provider = provider,
            api = api,
            existingMetadata = currentMetadata,
            onProgress = onProgress
        )

        val now = System.currentTimeMillis()
        val seriesSequentialStress = seriesSyncResult.strategyFeedback.segmentedStressDetected
        val seriesProviderAdaptation = updateSequentialProviderAdaptation(
            previousRemembered = currentMetadata.seriesSequentialFailuresRemembered,
            previousHealthyStreak = currentMetadata.seriesHealthySyncStreak,
            sawSequentialStress = seriesSequentialStress
        )
        val seriesAvoidFullUntil = updateAvoidFullUntil(
            previousAvoidFullUntil = currentMetadata.seriesAvoidFullUntil,
            now = now,
            feedback = seriesSyncResult.strategyFeedback
        )
        when (val seriesResult = seriesSyncResult.catalogResult) {
            is CatalogStrategyResult.Success -> {
                val acceptedCount = seriesSyncResult.stagedSessionId?.let { sessionId ->
                    syncCatalogStore.applyStagedSeriesCatalog(provider.id, sessionId, seriesSyncResult.categories)
                    seriesSyncResult.stagedAcceptedCount
                } ?: syncCatalogStore.replaceSeriesCatalog(
                    providerId = provider.id,
                    categories = seriesSyncResult.categories,
                    series = seriesResult.items.asSequence().map { it.toEntity() }
                )
                syncMetadataRepository.updateMetadata(
                    currentMetadata.copy(
                        lastSeriesSync = now,
                        seriesCount = acceptedCount,
                        seriesAvoidFullUntil = seriesAvoidFullUntil,
                        seriesSequentialFailuresRemembered = seriesProviderAdaptation.rememberSequential,
                        seriesHealthySyncStreak = seriesProviderAdaptation.healthyStreak
                    )
                )
            }
            is CatalogStrategyResult.Partial -> {
                val acceptedCount = seriesSyncResult.stagedSessionId?.let { sessionId ->
                    syncCatalogStore.applyStagedSeriesCatalog(provider.id, sessionId, seriesSyncResult.categories)
                    seriesSyncResult.stagedAcceptedCount
                } ?: syncCatalogStore.replaceSeriesCatalog(
                    providerId = provider.id,
                    categories = seriesSyncResult.categories,
                    series = seriesResult.items.asSequence().map { it.toEntity() }
                )
                syncMetadataRepository.updateMetadata(
                    currentMetadata.copy(
                        lastSeriesSync = now,
                        seriesCount = acceptedCount,
                        seriesAvoidFullUntil = seriesAvoidFullUntil,
                        seriesSequentialFailuresRemembered = currentMetadata.seriesSequentialFailuresRemembered || seriesSequentialStress,
                        seriesHealthySyncStreak = 0
                    )
                )
            }
            is CatalogStrategyResult.EmptyValid -> {
                val existingSeriesCount = seriesDao.getCount(provider.id).first()
                syncMetadataRepository.updateMetadata(
                    currentMetadata.copy(
                        seriesAvoidFullUntil = seriesAvoidFullUntil,
                        seriesSequentialFailuresRemembered = currentMetadata.seriesSequentialFailuresRemembered || seriesSequentialStress,
                        seriesHealthySyncStreak = 0
                    )
                )
                throw IllegalStateException(
                    if (existingSeriesCount > 0) {
                        "Series refresh returned an empty catalog; existing library was preserved."
                    } else {
                        "Series catalog was empty."
                    }
                )
            }
            is CatalogStrategyResult.Failure -> {
                val enteringLazyMode = seriesResult.strategyName == "lazy_by_category" && !seriesSyncResult.categories.isNullOrEmpty()
                if (enteringLazyMode) {
                    seriesSyncResult.categories?.let { syncCatalogStore.replaceCategories(provider.id, "SERIES", it) }
                }
                syncMetadataRepository.updateMetadata(
                    currentMetadata.copy(
                        lastSeriesSync = if (enteringLazyMode) now else currentMetadata.lastSeriesSync,
                        seriesAvoidFullUntil = seriesAvoidFullUntil,
                        seriesSequentialFailuresRemembered = currentMetadata.seriesSequentialFailuresRemembered || seriesSequentialStress || shouldRememberSequentialPreference(seriesResult.error),
                        seriesHealthySyncStreak = 0
                    )
                )
                if (!enteringLazyMode) {
                    throw IllegalStateException(
                        syncErrorSanitizer.userMessage(seriesResult.error, "Failed to fetch series list"),
                        seriesResult.error
                    )
                }
            }
        }
    }

    private suspend fun syncXtreamMoviesCatalog(
        provider: Provider,
        api: XtreamProvider,
        existingMetadata: SyncMetadata,
        onProgress: ((String) -> Unit)?
    ): MovieCatalogSyncResult {
        Log.i(
            TAG,
            "Xtream movies strategy start for provider ${provider.id}. previousMode=${existingMetadata.movieSyncMode} rememberSequential=${existingMetadata.movieParallelFailuresRemembered}"
        )
        if (provider.xtreamFastSyncEnabled) {
            progress(provider.id, onProgress, "Loading movie categories...")
        }
        val rawVodCategories = fetchXtreamVodCategories(provider)
        val resolvedCategories = rawVodCategories
            ?.let { categories -> api.mapCategories(ContentType.MOVIE, categories) }
            ?.map { category -> category.toEntity(provider.id) }
            ?.takeIf { it.isNotEmpty() }

        if (provider.xtreamFastSyncEnabled) {
            return if (!resolvedCategories.isNullOrEmpty()) {
                MovieCatalogSyncResult(
                    catalogResult = CatalogStrategyResult.Success(
                        strategyName = "fast_sync",
                        items = emptyList()
                    ),
                    categories = resolvedCategories,
                    syncMode = VodSyncMode.FAST_SYNC
                )
            } else {
                MovieCatalogSyncResult(
                    catalogResult = CatalogStrategyResult.Failure(
                        strategyName = "fast_sync",
                        error = IllegalStateException("Fast Sync could not load VOD categories")
                    ),
                    categories = null,
                    syncMode = VodSyncMode.FAST_SYNC
                )
            }
        }

        var fullPayload = CatalogSyncPayload<Movie>(
            catalogResult = CatalogStrategyResult.EmptyValid("full"),
            categories = null
        )
        var pagedPayload = CatalogSyncPayload<Movie>(
            catalogResult = CatalogStrategyResult.EmptyValid("paged"),
            categories = null
        )
        var categoryPayload = CatalogSyncPayload<Movie>(
            catalogResult = CatalogStrategyResult.EmptyValid("category_bulk"),
            categories = null
        )

        progress(provider.id, onProgress, "Preparing Movies category sync...")
        categoryPayload = loadXtreamMoviesByCategory(
            provider = provider,
            api = api,
            rawCategories = rawVodCategories.orEmpty(),
            onProgress = onProgress,
            preferSequential = existingMetadata.movieParallelFailuresRemembered
        )
        when (val categoryResult = categoryPayload.catalogResult) {
            is CatalogStrategyResult.Success -> return MovieCatalogSyncResult(
                catalogResult = categoryResult,
                categories = resolvedCategories ?: categoryPayload.categories,
                syncMode = VodSyncMode.CATEGORY_BULK,
                warnings = strategyWarnings(fullPayload.catalogResult) + strategyWarnings(pagedPayload.catalogResult),
                strategyFeedback = XtreamStrategyFeedback(
                    preferredSegmentedFirst = true,
                    segmentedStressDetected = sawSegmentedStress(
                        warnings = strategyWarnings(fullPayload.catalogResult) + strategyWarnings(pagedPayload.catalogResult),
                        result = categoryResult,
                        sequentialWarnings = setOf(MOVIE_CATEGORY_SEQUENTIAL_MODE_WARNING, MOVIE_PAGED_SEQUENTIAL_MODE_WARNING)
                    )
                ),
                stagedSessionId = categoryPayload.stagedSessionId,
                stagedAcceptedCount = categoryPayload.stagedAcceptedCount
            ).also {
                Log.i(TAG, "Xtream movies strategy selected CATEGORY_BULK for provider ${provider.id} with ${categoryPayload.stagedAcceptedCount} items.")
            }
            is CatalogStrategyResult.Partial -> return MovieCatalogSyncResult(
                catalogResult = categoryResult,
                categories = resolvedCategories ?: categoryPayload.categories,
                syncMode = VodSyncMode.CATEGORY_BULK,
                warnings = strategyWarnings(fullPayload.catalogResult) + strategyWarnings(pagedPayload.catalogResult),
                strategyFeedback = XtreamStrategyFeedback(
                    preferredSegmentedFirst = true,
                    segmentedStressDetected = sawSegmentedStress(
                        warnings = strategyWarnings(fullPayload.catalogResult) + strategyWarnings(pagedPayload.catalogResult),
                        result = categoryResult,
                        sequentialWarnings = setOf(MOVIE_CATEGORY_SEQUENTIAL_MODE_WARNING, MOVIE_PAGED_SEQUENTIAL_MODE_WARNING)
                    )
                ),
                stagedSessionId = categoryPayload.stagedSessionId,
                stagedAcceptedCount = categoryPayload.stagedAcceptedCount
            ).also {
                Log.w(TAG, "Xtream movies strategy selected CATEGORY_BULK(partial) for provider ${provider.id} with ${categoryPayload.stagedAcceptedCount} items.")
            }
            else -> Unit
        }

        if (resolvedCategories.isNullOrEmpty()) {
            progress(provider.id, onProgress, "Checking Movies paged catalog...")
            pagedPayload = loadXtreamMoviesByPage(provider, api, onProgress)
            when (val pagedResult = pagedPayload.catalogResult) {
                is CatalogStrategyResult.Success -> return MovieCatalogSyncResult(
                    catalogResult = pagedResult,
                    categories = pagedPayload.categories,
                    syncMode = VodSyncMode.PAGED,
                    warnings = strategyWarnings(fullPayload.catalogResult) + strategyWarnings(categoryPayload.catalogResult),
                    strategyFeedback = XtreamStrategyFeedback(
                        preferredSegmentedFirst = true,
                        segmentedStressDetected = sawSegmentedStress(
                            warnings = strategyWarnings(fullPayload.catalogResult) + strategyWarnings(categoryPayload.catalogResult),
                            result = pagedResult,
                            sequentialWarnings = setOf(MOVIE_PAGED_SEQUENTIAL_MODE_WARNING, MOVIE_CATEGORY_SEQUENTIAL_MODE_WARNING)
                        )
                    ),
                    stagedSessionId = pagedPayload.stagedSessionId,
                    stagedAcceptedCount = pagedPayload.stagedAcceptedCount
                ).also {
                    Log.i(TAG, "Xtream movies strategy selected PAGED for provider ${provider.id} with ${pagedPayload.stagedAcceptedCount} items.")
                }
                is CatalogStrategyResult.Partial -> return MovieCatalogSyncResult(
                    catalogResult = pagedResult,
                    categories = pagedPayload.categories,
                    syncMode = VodSyncMode.PAGED,
                    warnings = strategyWarnings(fullPayload.catalogResult) + strategyWarnings(categoryPayload.catalogResult),
                    strategyFeedback = XtreamStrategyFeedback(
                        preferredSegmentedFirst = true,
                        segmentedStressDetected = sawSegmentedStress(
                            warnings = strategyWarnings(fullPayload.catalogResult) + strategyWarnings(categoryPayload.catalogResult),
                            result = pagedResult,
                            sequentialWarnings = setOf(MOVIE_PAGED_SEQUENTIAL_MODE_WARNING, MOVIE_CATEGORY_SEQUENTIAL_MODE_WARNING)
                        )
                    ),
                    stagedSessionId = pagedPayload.stagedSessionId,
                    stagedAcceptedCount = pagedPayload.stagedAcceptedCount
                ).also {
                    Log.w(TAG, "Xtream movies strategy selected PAGED(partial) for provider ${provider.id} with ${pagedPayload.stagedAcceptedCount} items.")
                }
                else -> Unit
            }
        }

        progress(provider.id, onProgress, "Checking Movies full catalog...")
        fullPayload = loadXtreamMoviesFull(provider, api)
        when (val fullResult = fullPayload.catalogResult) {
            is CatalogStrategyResult.Success -> return MovieCatalogSyncResult(
                catalogResult = fullResult,
                categories = resolvedCategories ?: fullPayload.categories,
                syncMode = VodSyncMode.FULL,
                warnings = strategyWarnings(categoryPayload.catalogResult) + strategyWarnings(pagedPayload.catalogResult),
                strategyFeedback = XtreamStrategyFeedback(
                    preferredSegmentedFirst = true,
                    attemptedFullCatalog = true,
                    fullCatalogUnsafe = false
                ),
                stagedSessionId = fullPayload.stagedSessionId,
                stagedAcceptedCount = fullPayload.stagedAcceptedCount
            ).also {
                val itemCount = fullPayload.stagedAcceptedCount.takeIf { it > 0 } ?: fullResult.items.size
                Log.i(TAG, "Xtream movies strategy selected FULL for provider ${provider.id} with $itemCount items.")
            }
            else -> Unit
        }

        val lazyWarnings = buildList {
            addAll(strategyWarnings(categoryPayload.catalogResult))
            addAll(strategyWarnings(fullPayload.catalogResult))
            if (!resolvedCategories.isNullOrEmpty()) {
                add("Movies entered lazy category-only mode after category-bulk and full strategies failed.")
            } else {
                addAll(strategyWarnings(pagedPayload.catalogResult))
                add("Movies entered lazy category-only mode after category-bulk, paged, and full strategies failed.")
            }
        }
        Log.w(
            TAG,
            "Xtream movies strategy exhausted for provider ${provider.id}. categoriesAvailable=${!resolvedCategories.isNullOrEmpty()} finalMode=${if (!resolvedCategories.isNullOrEmpty()) VodSyncMode.LAZY_BY_CATEGORY else VodSyncMode.UNKNOWN}"
        )

        return if (!resolvedCategories.isNullOrEmpty()) {
            MovieCatalogSyncResult(
                catalogResult = CatalogStrategyResult.Failure(
                    strategyName = "lazy_by_category",
                    error = IllegalStateException("Movie catalog strategies failed; exposing categories only"),
                    warnings = lazyWarnings
                ),
                categories = resolvedCategories,
                syncMode = VodSyncMode.LAZY_BY_CATEGORY,
                warnings = lazyWarnings,
                strategyFeedback = XtreamStrategyFeedback(
                    preferredSegmentedFirst = true,
                    attemptedFullCatalog = true,
                    segmentedStressDetected = true
                )
            )
        } else {
            MovieCatalogSyncResult(
                catalogResult = CatalogStrategyResult.Failure(
                    strategyName = "movies",
                    error = IllegalStateException("Movie catalog strategies failed and no categories were available"),
                    warnings = lazyWarnings
                ),
                categories = null,
                syncMode = VodSyncMode.UNKNOWN,
                warnings = lazyWarnings,
                strategyFeedback = XtreamStrategyFeedback(
                    preferredSegmentedFirst = true,
                    attemptedFullCatalog = true,
                    segmentedStressDetected = true
                )
            )
        }
    }

    private suspend fun loadXtreamMoviesFull(
        provider: Provider,
        api: XtreamProvider
    ): CatalogSyncPayload<Movie> {
        val fallbackCollector = FallbackCategoryCollector(provider.id, ContentType.MOVIE)
        val seenStreamIds = HashSet<Long>()
        val rawBatch = ArrayList<XtreamStream>(XTREAM_FALLBACK_STAGE_BATCH_SIZE)
        var stagedSessionId: Long? = null
        var acceptedCount = 0
        var streamedRawCount = 0
        var fullMoviesFailure: Throwable? = null

        suspend fun flushRawBatch() {
            if (rawBatch.isEmpty()) return
            val staged = stageMovieSequence(
                providerId = provider.id,
                items = api.mapVodStreamsSequence(rawBatch.asSequence()),
                seenStreamIds = seenStreamIds,
                fallbackCollector = fallbackCollector,
                sessionId = stagedSessionId
            )
            stagedSessionId = staged.sessionId
            acceptedCount += staged.acceptedCount
            rawBatch.clear()
        }

        val fullMoviesElapsedMs = measureTimeMillis {
            when (val attempt = attemptNonCancellation {
                withMovieRequestTimeout("full movie catalog") {
                    retryXtreamCatalogTransient(provider.id) {
                        executeXtreamRequest(provider.id, XtreamAdaptiveSyncPolicy.Stage.HEAVY) {
                            xtreamCatalogHttpService.streamVodStreams(
                                XtreamUrlFactory.buildPlayerApiUrl(
                                    serverUrl = provider.serverUrl,
                                    username = provider.username,
                                    password = provider.password,
                                    action = "get_vod_streams"
                                )
                            ) { stream ->
                                rawBatch += stream
                                streamedRawCount++
                                if (rawBatch.size >= XTREAM_FALLBACK_STAGE_BATCH_SIZE) {
                                    flushRawBatch()
                                }
                            }.also {
                                flushRawBatch()
                            }
                        }
                    }
                }
            }) {
                is Attempt.Success -> Unit
                is Attempt.Failure -> {
                    fullMoviesFailure = attempt.error
                    stagedSessionId?.let { sessionId ->
                        syncCatalogStore.discardStagedImport(provider.id, sessionId)
                        stagedSessionId = null
                    }
                }
            }
        }
        if (streamedRawCount > 0) {
            Log.i(
                TAG,
                "Xtream movies full catalog succeeded for provider ${provider.id} in ${fullMoviesElapsedMs}ms with $acceptedCount accepted items from $streamedRawCount raw items."
            )
            if (acceptedCount == 0) {
                return CatalogSyncPayload(
                    catalogResult = CatalogStrategyResult.EmptyValid(
                        strategyName = "full",
                        warnings = listOf("Movies full catalog returned raw items but none were usable after mapping.")
                    ),
                    categories = null
                )
            }
            return CatalogSyncPayload(
                catalogResult = CatalogStrategyResult.Success(
                    strategyName = "full",
                    items = emptyList()
                ),
                categories = fallbackCollector.entities().takeIf { it.isNotEmpty() },
                stagedSessionId = stagedSessionId,
                stagedAcceptedCount = acceptedCount
            )
        }
        return if (fullMoviesFailure != null) {
            logXtreamCatalogFallback(
                provider = provider,
                section = "movies",
                stage = "full catalog",
                elapsedMs = fullMoviesElapsedMs,
                itemCount = streamedRawCount,
                error = fullMoviesFailure,
                nextStep = "category-bulk"
            )
            CatalogSyncPayload(
                catalogResult = CatalogStrategyResult.Failure(
                    strategyName = "full",
                    error = fullMoviesFailure!!,
                    warnings = listOf(fullCatalogFallbackWarning("Movies", fullMoviesFailure))
                ),
                categories = null
            )
        } else {
            logXtreamCatalogFallback(
                provider = provider,
                section = "movies",
                stage = "full catalog",
                elapsedMs = fullMoviesElapsedMs,
                itemCount = streamedRawCount,
                error = null,
                nextStep = "category-bulk"
            )
            CatalogSyncPayload(
                catalogResult = CatalogStrategyResult.EmptyValid(
                    strategyName = "full",
                    warnings = listOf("Movies full catalog returned an empty valid result.")
                ),
                categories = null
            )
        }
    }

    private suspend fun syncXtreamLiveCatalog(
        provider: Provider,
        api: XtreamProvider,
        existingMetadata: SyncMetadata,
        onProgress: ((String) -> Unit)?
    ): CatalogSyncPayload<Channel> {
        Log.i(TAG, "Xtream live strategy start for provider ${provider.id}.")
        val rawLiveCategories = when (val attempt = attemptNonCancellation {
            retryXtreamCatalogTransient(provider.id) {
                executeXtreamRequest(provider.id, XtreamAdaptiveSyncPolicy.Stage.LIGHTWEIGHT) {
                    xtreamCatalogApiService.getLiveCategories(
                        XtreamUrlFactory.buildPlayerApiUrl(
                            serverUrl = provider.serverUrl,
                            username = provider.username,
                            password = provider.password,
                            action = "get_live_categories"
                        )
                    )
                }
            }
        }) {
            is Attempt.Success -> attempt.value
            is Attempt.Failure -> {
                Log.w(TAG, "Xtream live categories request failed for provider ${provider.id}: ${sanitizeThrowableMessage(attempt.error)}")
                null
            }
        }
        val resolvedCategories = rawLiveCategories
            ?.let { categories -> api.mapCategories(ContentType.LIVE, categories) }
            ?.map { category -> category.toEntity(provider.id) }
            ?.takeIf { it.isNotEmpty() }
        var fullResult: CatalogStrategyResult<Channel> = CatalogStrategyResult.EmptyValid("full")
        var categoryResult: CatalogStrategyResult<Channel> = CatalogStrategyResult.EmptyValid("category_bulk")
        progress(provider.id, onProgress, "Downloading Live TV...")
        fullResult = loadXtreamLiveFull(provider, api)
        when (fullResult) {
            is CatalogStrategyResult.Success -> return CatalogSyncPayload(
                catalogResult = fullResult,
                categories = resolvedCategories ?: buildFallbackLiveCategories(provider.id, fullResult.items),
                warnings = emptyList(),
                strategyFeedback = XtreamStrategyFeedback(
                    attemptedFullCatalog = true,
                    fullCatalogUnsafe = false
                )
            )
            is CatalogStrategyResult.Partial -> return CatalogSyncPayload(
                catalogResult = fullResult,
                categories = resolvedCategories ?: buildFallbackLiveCategories(provider.id, fullResult.items),
                warnings = emptyList(),
                strategyFeedback = XtreamStrategyFeedback(
                    attemptedFullCatalog = true,
                    fullCatalogUnsafe = false
                )
            )
            else -> Unit
        }

        progress(provider.id, onProgress, "Downloading Live TV by category...")
        categoryResult = loadXtreamLiveByCategory(
            provider,
            api,
            rawLiveCategories.orEmpty(),
            onProgress,
            preferSequential = existingMetadata.liveSequentialFailuresRemembered
        )
        return CatalogSyncPayload(
            catalogResult = categoryResult,
            categories = when (categoryResult) {
                is CatalogStrategyResult.Success -> resolvedCategories ?: buildFallbackLiveCategories(provider.id, categoryResult.items)
                is CatalogStrategyResult.Partial -> resolvedCategories ?: buildFallbackLiveCategories(provider.id, categoryResult.items)
                else -> null
            },
            warnings = strategyWarnings(fullResult),
            strategyFeedback = XtreamStrategyFeedback(
                attemptedFullCatalog = true,
                fullCatalogUnsafe = (fullResult as? CatalogStrategyResult.Failure)?.error?.let(::shouldAvoidFullCatalogStrategy) == true,
                segmentedStressDetected = sawSegmentedStress(
                    warnings = strategyWarnings(fullResult),
                    result = categoryResult,
                    sequentialWarnings = setOf(LIVE_CATEGORY_SEQUENTIAL_MODE_WARNING)
                )
            )
        )
    }

    private suspend fun loadXtreamLiveFull(
        provider: Provider,
        api: XtreamProvider
    ): CatalogStrategyResult<Channel> {
        var fullChannels: List<Channel>? = null
        var fullChannelsFailure: Throwable? = null
        val fullChannelsElapsedMs = measureTimeMillis {
            when (val attempt = attemptNonCancellation {
                retryXtreamCatalogTransient(provider.id) {
                    executeXtreamRequest(provider.id, XtreamAdaptiveSyncPolicy.Stage.HEAVY) {
                        api.getLiveStreams().getOrThrow("Live streams")
                    }
                }
            }) {
                is Attempt.Success -> fullChannels = attempt.value
                is Attempt.Failure -> fullChannelsFailure = attempt.error
            }
        }
        if (!fullChannels.isNullOrEmpty()) {
            Log.i(TAG, "Xtream live full catalog succeeded for provider ${provider.id} in ${fullChannelsElapsedMs}ms with ${fullChannels!!.size} items.")
            return CatalogStrategyResult.Success("full", fullChannels!!)
        }
        return if (fullChannelsFailure != null) {
            logXtreamCatalogFallback(
                provider = provider,
                section = "live",
                stage = "full catalog",
                elapsedMs = fullChannelsElapsedMs,
                itemCount = fullChannels?.size,
                error = fullChannelsFailure,
                nextStep = "category-bulk"
            )
            CatalogStrategyResult.Failure(
                strategyName = "full",
                error = fullChannelsFailure!!,
                warnings = listOf(fullCatalogFallbackWarning("Live TV", fullChannelsFailure))
            )
        } else {
            logXtreamCatalogFallback(
                provider = provider,
                section = "live",
                stage = "full catalog",
                elapsedMs = fullChannelsElapsedMs,
                itemCount = fullChannels?.size,
                error = null,
                nextStep = "category-bulk"
            )
            CatalogStrategyResult.EmptyValid(
                strategyName = "full",
                warnings = listOf("Live full catalog returned an empty valid result.")
            )
        }
    }

    private suspend fun loadXtreamLiveByCategory(
        provider: Provider,
        api: XtreamProvider,
        rawCategories: List<XtreamCategory>,
        onProgress: ((String) -> Unit)?,
        preferSequential: Boolean
    ): CatalogStrategyResult<Channel> {
        val categories = rawCategories.filter { it.categoryId.isNotBlank() }
        if (categories.isEmpty()) {
            return CatalogStrategyResult.Failure(
                strategyName = "category_bulk",
                error = IllegalStateException("No live categories available"),
                warnings = listOf("Live category-bulk strategy was unavailable because no categories were returned.")
            )
        }

        val concurrency = xtreamAdaptiveSyncPolicy.concurrencyFor(
            providerId = provider.id,
            workloadSize = categories.size,
            preferSequential = preferSequential,
            stage = XtreamAdaptiveSyncPolicy.Stage.CATEGORY
        )
        progress(provider.id, onProgress, "Downloading Live TV by category 0/${categories.size}...")

        val executionPlan = executeCategoryRecoveryPlan(
            provider = provider,
            categories = categories,
            initialConcurrency = concurrency,
            sectionLabel = "Live TV",
            sequentialModeWarning = LIVE_CATEGORY_SEQUENTIAL_MODE_WARNING,
            onProgress = onProgress,
            fetch = { category -> fetchLiveCategoryOutcome(provider, api, category) }
        )
        var timedOutcomes = executionPlan.outcomes

        val categoryOutcomes = timedOutcomes.map { it.outcome }
        val failureCount = timedOutcomes.count { it.outcome is CategoryFetchOutcome.Failure }
        val fastFailureCount = timedOutcomes.count {
            it.elapsedMs <= 5_000L && it.outcome is CategoryFetchOutcome.Failure
        }

        val downgradeRecommended = shouldDowngradeCategorySync(categories.size, failureCount, fastFailureCount, categoryOutcomes)
        var fallbackWarnings = executionPlan.warnings
        if (concurrency > 1 && shouldRetryFailedCategories(categories.size, failureCount, downgradeRecommended, categoryOutcomes)) {
            Log.w(TAG, "Xtream live category sync is continuing in sequential mode for failed categories on provider ${provider.id}.")
            timedOutcomes = continueFailedCategoryOutcomes(
                provider = provider,
                timedOutcomes = timedOutcomes,
                fetchSequentially = { category -> fetchLiveCategoryOutcome(provider, api, category) }
            )
            fallbackWarnings = (fallbackWarnings + if (downgradeRecommended) listOf(LIVE_CATEGORY_SEQUENTIAL_MODE_WARNING) else emptyList()).distinct()
        }

        val finalOutcomes = timedOutcomes.map { it.outcome }
        val warnings = finalOutcomes
            .filterIsInstance<CategoryFetchOutcome.Failure>()
            .map { failure -> categoryFailureWarning("Live TV", failure.categoryName, failure.error) } +
            fallbackWarnings

        val channels = finalOutcomes
            .asSequence()
            .filterIsInstance<CategoryFetchOutcome.Success<Channel>>()
            .flatMap { it.items.asSequence() }
            .filter { it.streamId > 0L }
            .associateBy { it.streamId }
            .values
            .toList()
        val failedCategories = finalOutcomes.count { it is CategoryFetchOutcome.Failure }
        val emptyCategories = finalOutcomes.count { it is CategoryFetchOutcome.Empty }
        val successfulCategories = finalOutcomes.count { it is CategoryFetchOutcome.Success }
        Log.i(TAG, "Xtream live category strategy summary for provider ${provider.id}: successful=$successfulCategories empty=$emptyCategories failed=$failedCategories dedupedChannels=${channels.size} concurrency=$concurrency")

        return when {
            channels.isNotEmpty() && failedCategories == 0 -> CatalogStrategyResult.Success("category_bulk", channels, warnings.toList())
            channels.isNotEmpty() -> CatalogStrategyResult.Partial("category_bulk", channels, warnings.toList())
            failedCategories > 0 -> CatalogStrategyResult.Failure(
                strategyName = "category_bulk",
                error = IllegalStateException("Live category-bulk sync failed for all usable categories"),
                warnings = warnings.toList()
            )
            else -> CatalogStrategyResult.EmptyValid(
                strategyName = "category_bulk",
                warnings = listOf("All live categories returned valid empty results.")
            )
        }
    }

    private suspend fun loadXtreamMoviesByCategory(
        provider: Provider,
        api: XtreamProvider,
        rawCategories: List<XtreamCategory>,
        onProgress: ((String) -> Unit)?,
        preferSequential: Boolean
    ): CatalogSyncPayload<Movie> {
        val categories = rawCategories.filter { it.categoryId.isNotBlank() }
        if (categories.isEmpty()) {
            return CatalogSyncPayload(
                catalogResult = CatalogStrategyResult.Failure(
                    strategyName = "category_bulk",
                    error = IllegalStateException("No VOD categories available"),
                    warnings = listOf("Movies category-bulk strategy was unavailable because no categories were returned.")
                ),
                categories = null
            )
        }

        val concurrency = xtreamAdaptiveSyncPolicy.concurrencyFor(
            providerId = provider.id,
            workloadSize = categories.size,
            preferSequential = preferSequential,
            stage = XtreamAdaptiveSyncPolicy.Stage.CATEGORY
        )
        progress(provider.id, onProgress, "Downloading Movies by category 0/${categories.size}...")

        val executionPlan = executeCategoryRecoveryPlan(
            provider = provider,
            categories = categories,
            initialConcurrency = concurrency,
            sectionLabel = "Movies",
            sequentialModeWarning = MOVIE_CATEGORY_SEQUENTIAL_MODE_WARNING,
            onProgress = onProgress,
            fetch = { category -> fetchMovieCategoryOutcome(provider, api, category) }
        )
        var timedOutcomes = executionPlan.outcomes

        val categoryOutcomes = timedOutcomes.map { it.outcome }
        val failureCount = timedOutcomes.count { it.outcome is CategoryFetchOutcome.Failure }
        val fastFailureCount = timedOutcomes.count {
            it.elapsedMs <= 5_000L && it.outcome is CategoryFetchOutcome.Failure
        }

        val downgradeRecommended = shouldDowngradeCategorySync(
            totalCategories = categories.size,
            failures = failureCount,
            fastFailures = fastFailureCount,
            outcomes = categoryOutcomes
        )
        var fallbackWarnings = executionPlan.warnings
        if (concurrency > 1 && shouldRetryFailedCategories(categories.size, failureCount, downgradeRecommended, categoryOutcomes)) {
            val downgradeReasons = buildList {
                add("failures=$failureCount/${categories.size}")
                if (fastFailureCount > 0) add("fastFailures=$fastFailureCount")
                val providerStressSignals = categoryOutcomes.count { outcome ->
                    outcome is CategoryFetchOutcome.Failure && shouldRememberSequentialPreference(outcome.error)
                }
                if (providerStressSignals > 0) add("providerStressSignals=$providerStressSignals")
            }.joinToString(", ")
            Log.w(
                TAG,
                "Xtream movie category sync is continuing in sequential mode for failed categories on provider ${provider.id}: $downgradeReasons"
            )
            timedOutcomes = continueFailedCategoryOutcomes(
                provider = provider,
                timedOutcomes = timedOutcomes,
                fetchSequentially = { category -> fetchMovieCategoryOutcome(provider, api, category) }
            )
            fallbackWarnings = (fallbackWarnings + if (downgradeRecommended) listOf(MOVIE_CATEGORY_SEQUENTIAL_MODE_WARNING) else emptyList()).distinct()
        }

        val finalOutcomes = timedOutcomes.map { it.outcome }
        val warnings = finalOutcomes
            .filterIsInstance<CategoryFetchOutcome.Failure>()
            .map { failure -> categoryFailureWarning("Movies", failure.categoryName, failure.error) } +
            fallbackWarnings

        val seenStreamIds = HashSet<Long>()
        val fallbackCollector = FallbackCategoryCollector(provider.id, ContentType.MOVIE)
        var sessionId: Long? = null
        var acceptedCount = 0
        finalOutcomes
            .filterIsInstance<CategoryFetchOutcome.Success<Movie>>()
            .forEach { success ->
                val staged = stageMovieItems(
                    providerId = provider.id,
                    items = success.items,
                    seenStreamIds = seenStreamIds,
                    fallbackCollector = fallbackCollector,
                    sessionId = sessionId
                )
                sessionId = staged.sessionId
                acceptedCount += staged.acceptedCount
            }

        val failedCategories = finalOutcomes.count { it is CategoryFetchOutcome.Failure }
        val successfulCategories = finalOutcomes.count { it is CategoryFetchOutcome.Success }
        val emptyCategories = finalOutcomes.count { it is CategoryFetchOutcome.Empty }
        Log.i(
            TAG,
            "Xtream movie category strategy summary for provider ${provider.id}: successful=$successfulCategories empty=$emptyCategories failed=$failedCategories dedupedMovies=$acceptedCount concurrency=$concurrency"
        )
        return when {
            acceptedCount > 0 && failedCategories == 0 -> CatalogSyncPayload(
                catalogResult = CatalogStrategyResult.Success(
                    strategyName = "category_bulk",
                    items = emptyList(),
                    warnings = warnings.toList()
                ),
                categories = fallbackCollector.entities().takeIf { it.isNotEmpty() },
                stagedSessionId = sessionId,
                stagedAcceptedCount = acceptedCount
            )
            acceptedCount > 0 -> CatalogSyncPayload(
                catalogResult = CatalogStrategyResult.Partial(
                    strategyName = "category_bulk",
                    items = emptyList(),
                    warnings = warnings.toList()
                ),
                categories = fallbackCollector.entities().takeIf { it.isNotEmpty() },
                stagedSessionId = sessionId,
                stagedAcceptedCount = acceptedCount
            )
            failedCategories > 0 -> CatalogSyncPayload(
                catalogResult = CatalogStrategyResult.Failure(
                    strategyName = "category_bulk",
                    error = IllegalStateException("Movie category-bulk sync failed for all usable categories"),
                    warnings = warnings.toList()
                ),
                categories = null
            )
            else -> CatalogSyncPayload(
                catalogResult = CatalogStrategyResult.EmptyValid(
                    strategyName = "category_bulk",
                    warnings = listOf("All movie categories returned valid empty results.")
                ),
                categories = null
            )
        }
    }

    private suspend fun loadXtreamMoviesByPage(
        provider: Provider,
        api: XtreamProvider,
        onProgress: ((String) -> Unit)?
    ): CatalogSyncPayload<Movie> {
        val seenStreamIds = HashSet<Long>()
        val fallbackCollector = FallbackCategoryCollector(provider.id, ContentType.MOVIE)
        val warnings = mutableListOf<String>()
        var sessionId: Long? = null
        var acceptedCount = 0
        var nextPage = 1
        var stopPaging = false
        var forceSequential = false

        while (nextPage <= XTREAM_CATALOG_MAX_PAGES && !stopPaging) {
            val remainingPages = XTREAM_CATALOG_MAX_PAGES - nextPage + 1
            val concurrency = xtreamAdaptiveSyncPolicy.concurrencyFor(
                providerId = provider.id,
                workloadSize = remainingPages,
                preferSequential = forceSequential,
                stage = XtreamAdaptiveSyncPolicy.Stage.PAGED
            )
            val pageWindow = (nextPage until (nextPage + concurrency))
                .takeWhile { it <= XTREAM_CATALOG_MAX_PAGES }
            progress(
                provider.id,
                onProgress,
                if (pageWindow.size == 1) {
                    "Downloading Movies by page ${pageWindow.first()}..."
                } else {
                    "Downloading Movies by page ${pageWindow.first()}-${pageWindow.last()}..."
                }
            )

            var timedOutcomes = coroutineScope {
                pageWindow.map { page ->
                    async { fetchMoviePageOutcome(provider, api, page) }
                }.awaitAll()
            }
            val failures = timedOutcomes.count { it.outcome is PageFetchOutcome.Failure }
            val recoveryPlan = evaluatePageRecoveryPlan(
                provider = provider,
                sectionLabel = "Movies",
                pageWindow = pageWindow,
                outcomes = timedOutcomes,
                sequentialModeWarning = MOVIE_PAGED_SEQUENTIAL_MODE_WARNING
            )
            forceSequential = forceSequential || recoveryPlan.warnings.any { it == MOVIE_PAGED_SEQUENTIAL_MODE_WARNING }
            warnings += recoveryPlan.warnings
            if (recoveryPlan.stoppedEarly) {
                stopPaging = true
            }
            if (!recoveryPlan.stoppedEarly && concurrency > 1 && shouldRetryFailedPages(pageWindow.size, failures, timedOutcomes.map { it.outcome })) {
                timedOutcomes = continueFailedPageOutcomes(
                    provider = provider,
                    timedOutcomes = timedOutcomes,
                    fetchSequentially = { page -> fetchMoviePageOutcome(provider, api, page) }
                )
            }

            var terminalFailure: Throwable? = null
            var terminalFailurePage: Int? = null
            timedOutcomes.sortedBy { it.page }.forEach { timedOutcome ->
                when (val outcome = timedOutcome.outcome) {
                    is PageFetchOutcome.Success -> {
                        val staged = stageMovieItems(
                            providerId = provider.id,
                            items = outcome.items,
                            seenStreamIds = seenStreamIds,
                            fallbackCollector = fallbackCollector,
                            sessionId = sessionId
                        )
                        sessionId = staged.sessionId
                        acceptedCount += staged.acceptedCount
                        val newItems = staged.acceptedCount
                        if (outcome.rawCount < XTREAM_CATALOG_PAGE_SIZE || newItems == 0) {
                            stopPaging = true
                        }
                    }
                    is PageFetchOutcome.Empty -> {
                        stopPaging = true
                    }
                    is PageFetchOutcome.Failure -> {
                        warnings += pagingFailureWarning("Movies", outcome.page, outcome.error)
                        if (terminalFailure == null) {
                            terminalFailure = outcome.error
                            terminalFailurePage = outcome.page
                        }
                    }
                }
            }

            if (terminalFailure != null) {
                return if (acceptedCount == 0) {
                    CatalogSyncPayload(
                        catalogResult = CatalogStrategyResult.Failure(
                            strategyName = "paged",
                            error = terminalFailure!!,
                            warnings = listOf(
                                pagingFailureWarning("Movies", terminalFailurePage ?: nextPage, terminalFailure!!)
                            )
                        )
                    ,
                        categories = null
                    )
                } else {
                    CatalogSyncPayload(
                        catalogResult = CatalogStrategyResult.Partial(
                            strategyName = "paged",
                            items = emptyList(),
                            warnings = warnings.toList()
                        ),
                        categories = fallbackCollector.entities().takeIf { it.isNotEmpty() },
                        stagedSessionId = sessionId,
                        stagedAcceptedCount = acceptedCount
                    )
                }
            }

            nextPage = pageWindow.last() + 1
        }

        return if (acceptedCount > 0) {
            Log.i(TAG, "Xtream paged movie strategy completed for provider ${provider.id} with $acceptedCount deduped items.")
            CatalogSyncPayload(
                catalogResult = CatalogStrategyResult.Success("paged", emptyList(), warnings.toList()),
                categories = fallbackCollector.entities().takeIf { it.isNotEmpty() },
                stagedSessionId = sessionId,
                stagedAcceptedCount = acceptedCount
            )
        } else {
            CatalogSyncPayload(
                catalogResult = CatalogStrategyResult.EmptyValid(
                    strategyName = "paged",
                    warnings = listOf("Paged movie catalog completed without items.")
                ),
                categories = null
            )
        }
    }

    private suspend fun syncXtreamSeriesCatalog(
        provider: Provider,
        api: XtreamProvider,
        existingMetadata: SyncMetadata,
        onProgress: ((String) -> Unit)?
    ): CatalogSyncPayload<Series> {
        Log.i(TAG, "Xtream series strategy start for provider ${provider.id}.")
        val rawSeriesCategories = when (val attempt = attemptNonCancellation {
            withSeriesRequestTimeout("series categories") {
                retryXtreamCatalogTransient(provider.id) {
                    executeXtreamRequest(provider.id, XtreamAdaptiveSyncPolicy.Stage.LIGHTWEIGHT) {
                        xtreamCatalogApiService.getSeriesCategories(
                            XtreamUrlFactory.buildPlayerApiUrl(
                                serverUrl = provider.serverUrl,
                                username = provider.username,
                                password = provider.password,
                                action = "get_series_categories"
                            )
                        )
                    }
                }
            }
        }) {
            is Attempt.Success -> attempt.value
            is Attempt.Failure -> {
                Log.w(TAG, "Xtream series categories request failed for provider ${provider.id}: ${sanitizeThrowableMessage(attempt.error)}")
                null
            }
        }
        val resolvedCategories = rawSeriesCategories
            ?.let { categories -> api.mapCategories(ContentType.SERIES, categories) }
            ?.map { category -> category.toEntity(provider.id) }
            ?.takeIf { it.isNotEmpty() }
        var fullPayload = CatalogSyncPayload<Series>(
            catalogResult = CatalogStrategyResult.EmptyValid("full"),
            categories = null
        )
        var pagedPayload = CatalogSyncPayload<Series>(
            catalogResult = CatalogStrategyResult.EmptyValid("paged"),
            categories = null
        )
        var categoryPayload = CatalogSyncPayload<Series>(
            catalogResult = CatalogStrategyResult.EmptyValid("category_bulk"),
            categories = null
        )

        if (!resolvedCategories.isNullOrEmpty()) {
            progress(provider.id, onProgress, "Preparing Series category sync...")
            categoryPayload = loadXtreamSeriesByCategory(
                provider,
                api,
                rawSeriesCategories.orEmpty(),
                onProgress,
                preferSequential = existingMetadata.seriesSequentialFailuresRemembered
            )
            when (val categoryResult = categoryPayload.catalogResult) {
                is CatalogStrategyResult.Success,
                is CatalogStrategyResult.Partial -> return CatalogSyncPayload(
                    catalogResult = categoryResult,
                    categories = resolvedCategories ?: categoryPayload.categories,
                warnings = strategyWarnings(fullPayload.catalogResult),
                strategyFeedback = XtreamStrategyFeedback(
                    preferredSegmentedFirst = true,
                    segmentedStressDetected = sawSegmentedStress(
                        warnings = strategyWarnings(fullPayload.catalogResult),
                        result = categoryResult,
                        sequentialWarnings = setOf(SERIES_CATEGORY_SEQUENTIAL_MODE_WARNING)
                    )
                    ),
                    stagedSessionId = categoryPayload.stagedSessionId,
                    stagedAcceptedCount = categoryPayload.stagedAcceptedCount
                )
                else -> Unit
            }
        }

        if (resolvedCategories.isNullOrEmpty()) {
            progress(provider.id, onProgress, "Checking Series paged catalog...")
            pagedPayload = loadXtreamSeriesByPage(provider, api, onProgress)
            when (val pagedResult = pagedPayload.catalogResult) {
                is CatalogStrategyResult.Success,
                is CatalogStrategyResult.Partial -> return CatalogSyncPayload(
                    catalogResult = pagedResult,
                    categories = pagedPayload.categories,
                    warnings = strategyWarnings(fullPayload.catalogResult) + strategyWarnings(categoryPayload.catalogResult),
                    strategyFeedback = XtreamStrategyFeedback(
                        preferredSegmentedFirst = true,
                        segmentedStressDetected = sawSegmentedStress(
                            warnings = strategyWarnings(fullPayload.catalogResult) + strategyWarnings(categoryPayload.catalogResult),
                            result = pagedResult,
                            sequentialWarnings = setOf(SERIES_PAGED_SEQUENTIAL_MODE_WARNING, SERIES_CATEGORY_SEQUENTIAL_MODE_WARNING)
                        )
                    ),
                    stagedSessionId = pagedPayload.stagedSessionId,
                    stagedAcceptedCount = pagedPayload.stagedAcceptedCount
                )
                else -> Unit
            }

            progress(provider.id, onProgress, "Preparing Series category sync...")
            categoryPayload = loadXtreamSeriesByCategory(
                provider,
                api,
                rawSeriesCategories.orEmpty(),
                onProgress,
                preferSequential = existingMetadata.seriesSequentialFailuresRemembered
            )
            when (val categoryResult = categoryPayload.catalogResult) {
                is CatalogStrategyResult.Success,
                is CatalogStrategyResult.Partial -> return CatalogSyncPayload(
                    catalogResult = categoryResult,
                    categories = categoryPayload.categories,
                    warnings = strategyWarnings(fullPayload.catalogResult) + strategyWarnings(pagedPayload.catalogResult),
                    strategyFeedback = XtreamStrategyFeedback(
                        preferredSegmentedFirst = true,
                        segmentedStressDetected = sawSegmentedStress(
                            warnings = strategyWarnings(fullPayload.catalogResult) + strategyWarnings(pagedPayload.catalogResult),
                            result = categoryResult,
                            sequentialWarnings = setOf(SERIES_CATEGORY_SEQUENTIAL_MODE_WARNING, SERIES_PAGED_SEQUENTIAL_MODE_WARNING)
                        )
                    ),
                    stagedSessionId = categoryPayload.stagedSessionId,
                    stagedAcceptedCount = categoryPayload.stagedAcceptedCount
                )
                else -> Unit
            }
        }

        progress(provider.id, onProgress, "Checking Series full catalog...")
        fullPayload = loadXtreamSeriesFull(provider, api)
        when (val fullResult = fullPayload.catalogResult) {
            is CatalogStrategyResult.Success -> return CatalogSyncPayload(
                catalogResult = fullResult,
                categories = resolvedCategories ?: fullPayload.categories,
                warnings = strategyWarnings(categoryPayload.catalogResult) + strategyWarnings(pagedPayload.catalogResult),
                strategyFeedback = XtreamStrategyFeedback(
                    preferredSegmentedFirst = true,
                    attemptedFullCatalog = true,
                    fullCatalogUnsafe = false
                ),
                stagedSessionId = fullPayload.stagedSessionId,
                stagedAcceptedCount = fullPayload.stagedAcceptedCount
            )
            else -> Unit
        }

        val lazyWarnings = buildList {
            addAll(strategyWarnings(categoryPayload.catalogResult))
            addAll(strategyWarnings(fullPayload.catalogResult))
            if (!resolvedCategories.isNullOrEmpty()) {
                add("Series entered lazy category-only mode after category-bulk and full strategies failed.")
            } else {
                addAll(strategyWarnings(pagedPayload.catalogResult))
                add("Series entered lazy category-only mode after category-bulk, paged, and full strategies failed.")
            }
        }
        Log.w(
            TAG,
            "Xtream series strategy exhausted for provider ${provider.id}. categoriesAvailable=${!resolvedCategories.isNullOrEmpty()} finalMode=${if (!resolvedCategories.isNullOrEmpty()) "lazy_by_category" else "unavailable"}"
        )

        return if (!resolvedCategories.isNullOrEmpty()) {
            CatalogSyncPayload(
                catalogResult = CatalogStrategyResult.Failure(
                    strategyName = "lazy_by_category",
                    error = IllegalStateException("Series catalog strategies failed; exposing categories only"),
                    warnings = lazyWarnings
                ),
                categories = resolvedCategories,
                warnings = lazyWarnings,
                strategyFeedback = XtreamStrategyFeedback(
                    preferredSegmentedFirst = true,
                    attemptedFullCatalog = true,
                    segmentedStressDetected = true
                )
            )
        } else {
            CatalogSyncPayload(
                catalogResult = CatalogStrategyResult.Failure(
                    strategyName = "series",
                    error = IllegalStateException("Series catalog strategies failed and no categories were available"),
                    warnings = lazyWarnings
                ),
                categories = null,
                warnings = lazyWarnings,
                strategyFeedback = XtreamStrategyFeedback(
                    preferredSegmentedFirst = true,
                    attemptedFullCatalog = true,
                    segmentedStressDetected = true
                )
            )
        }
    }

    private suspend fun loadXtreamSeriesFull(
        provider: Provider,
        api: XtreamProvider
    ): CatalogSyncPayload<Series> {
        val fallbackCollector = FallbackCategoryCollector(provider.id, ContentType.SERIES)
        val seenSeriesIds = HashSet<Long>()
        val rawBatch = ArrayList<XtreamSeriesItem>(XTREAM_FALLBACK_STAGE_BATCH_SIZE)
        var stagedSessionId: Long? = null
        var acceptedCount = 0
        var streamedRawCount = 0
        var fullSeriesFailure: Throwable? = null

        suspend fun flushRawBatch() {
            if (rawBatch.isEmpty()) return
            val staged = stageSeriesSequence(
                providerId = provider.id,
                items = api.mapSeriesListSequence(rawBatch.asSequence()),
                seenSeriesIds = seenSeriesIds,
                fallbackCollector = fallbackCollector,
                sessionId = stagedSessionId
            )
            stagedSessionId = staged.sessionId
            acceptedCount += staged.acceptedCount
            rawBatch.clear()
        }

        val fullSeriesElapsedMs = measureTimeMillis {
            when (val attempt = attemptNonCancellation {
                withSeriesRequestTimeout("full series catalog") {
                    retryXtreamCatalogTransient(provider.id) {
                        executeXtreamRequest(provider.id, XtreamAdaptiveSyncPolicy.Stage.HEAVY) {
                            xtreamCatalogHttpService.streamSeriesList(
                                XtreamUrlFactory.buildPlayerApiUrl(
                                    serverUrl = provider.serverUrl,
                                    username = provider.username,
                                    password = provider.password,
                                    action = "get_series"
                                )
                            ) { item ->
                                rawBatch += item
                                streamedRawCount++
                                if (rawBatch.size >= XTREAM_FALLBACK_STAGE_BATCH_SIZE) {
                                    flushRawBatch()
                                }
                            }.also {
                                flushRawBatch()
                            }
                        }
                    }
                }
            }) {
                is Attempt.Success -> Unit
                is Attempt.Failure -> {
                    fullSeriesFailure = attempt.error
                    stagedSessionId?.let { sessionId ->
                        syncCatalogStore.discardStagedImport(provider.id, sessionId)
                        stagedSessionId = null
                    }
                }
            }
        }
        if (streamedRawCount > 0) {
            Log.i(
                TAG,
                "Xtream series full catalog succeeded for provider ${provider.id} in ${fullSeriesElapsedMs}ms with $acceptedCount accepted items from $streamedRawCount raw items."
            )
            if (acceptedCount == 0) {
                return CatalogSyncPayload(
                    catalogResult = CatalogStrategyResult.EmptyValid(
                        strategyName = "full",
                        warnings = listOf("Series full catalog returned raw items but none were usable after mapping.")
                    ),
                    categories = null
                )
            }
            return CatalogSyncPayload(
                catalogResult = CatalogStrategyResult.Success("full", emptyList()),
                categories = fallbackCollector.entities().takeIf { it.isNotEmpty() },
                stagedSessionId = stagedSessionId,
                stagedAcceptedCount = acceptedCount
            )
        }
        return if (fullSeriesFailure != null) {
            logXtreamCatalogFallback(
                provider = provider,
                section = "series",
                stage = "full catalog",
                elapsedMs = fullSeriesElapsedMs,
                itemCount = streamedRawCount,
                error = fullSeriesFailure,
                nextStep = "category-bulk"
            )
            CatalogSyncPayload(
                catalogResult = CatalogStrategyResult.Failure(
                    strategyName = "full",
                    error = fullSeriesFailure!!,
                    warnings = listOf(fullCatalogFallbackWarning("Series", fullSeriesFailure))
                ),
                categories = null
            )
        } else {
            logXtreamCatalogFallback(
                provider = provider,
                section = "series",
                stage = "full catalog",
                elapsedMs = fullSeriesElapsedMs,
                itemCount = streamedRawCount,
                error = null,
                nextStep = "category-bulk"
            )
            CatalogSyncPayload(
                catalogResult = CatalogStrategyResult.EmptyValid(
                    strategyName = "full",
                    warnings = listOf("Series full catalog returned an empty valid result.")
                ),
                categories = null
            )
        }
    }

    private suspend fun loadXtreamSeriesByCategory(
        provider: Provider,
        api: XtreamProvider,
        rawCategories: List<XtreamCategory>,
        onProgress: ((String) -> Unit)?,
        preferSequential: Boolean
    ): CatalogSyncPayload<Series> {
        val categories = rawCategories.filter { it.categoryId.isNotBlank() }
        if (categories.isEmpty()) {
            return CatalogSyncPayload(
                catalogResult = CatalogStrategyResult.Failure(
                    strategyName = "category_bulk",
                    error = IllegalStateException("No series categories available"),
                    warnings = listOf("Series category-bulk strategy was unavailable because no categories were returned.")
                ),
                categories = null
            )
        }

        val concurrency = xtreamAdaptiveSyncPolicy.concurrencyFor(
            providerId = provider.id,
            workloadSize = categories.size,
            preferSequential = preferSequential,
            stage = XtreamAdaptiveSyncPolicy.Stage.CATEGORY
        )
        progress(provider.id, onProgress, "Downloading Series by category 0/${categories.size}...")

        val executionPlan = executeCategoryRecoveryPlan(
            provider = provider,
            categories = categories,
            initialConcurrency = concurrency,
            sectionLabel = "Series",
            sequentialModeWarning = SERIES_CATEGORY_SEQUENTIAL_MODE_WARNING,
            onProgress = onProgress,
            fetch = { category -> fetchSeriesCategoryOutcome(provider, api, category) }
        )
        var timedOutcomes = executionPlan.outcomes

        val categoryOutcomes = timedOutcomes.map { it.outcome }
        val failureCount = timedOutcomes.count { it.outcome is CategoryFetchOutcome.Failure }
        val fastFailureCount = timedOutcomes.count {
            it.elapsedMs <= 5_000L && it.outcome is CategoryFetchOutcome.Failure
        }

        val downgradeRecommended = shouldDowngradeCategorySync(categories.size, failureCount, fastFailureCount, categoryOutcomes)
        var fallbackWarnings = executionPlan.warnings
        if (concurrency > 1 && shouldRetryFailedCategories(categories.size, failureCount, downgradeRecommended, categoryOutcomes)) {
            Log.w(TAG, "Xtream series category sync is continuing in sequential mode for failed categories on provider ${provider.id}.")
            timedOutcomes = continueFailedCategoryOutcomes(
                provider = provider,
                timedOutcomes = timedOutcomes,
                fetchSequentially = { category -> fetchSeriesCategoryOutcome(provider, api, category) }
            )
            fallbackWarnings = (fallbackWarnings + if (downgradeRecommended) listOf(SERIES_CATEGORY_SEQUENTIAL_MODE_WARNING) else emptyList()).distinct()
        }

        val finalOutcomes = timedOutcomes.map { it.outcome }
        val warnings = finalOutcomes
            .filterIsInstance<CategoryFetchOutcome.Failure>()
            .map { failure -> categoryFailureWarning("Series", failure.categoryName, failure.error) } +
            fallbackWarnings

        val seenSeriesIds = HashSet<Long>()
        val fallbackCollector = FallbackCategoryCollector(provider.id, ContentType.SERIES)
        var sessionId: Long? = null
        var acceptedCount = 0
        finalOutcomes
            .filterIsInstance<CategoryFetchOutcome.Success<Series>>()
            .forEach { success ->
                val staged = stageSeriesItems(
                    providerId = provider.id,
                    items = success.items,
                    seenSeriesIds = seenSeriesIds,
                    fallbackCollector = fallbackCollector,
                    sessionId = sessionId
                )
                sessionId = staged.sessionId
                acceptedCount += staged.acceptedCount
            }
        val failedCategories = finalOutcomes.count { it is CategoryFetchOutcome.Failure }
        val emptyCategories = finalOutcomes.count { it is CategoryFetchOutcome.Empty }
        val successfulCategories = finalOutcomes.count { it is CategoryFetchOutcome.Success }
        Log.i(TAG, "Xtream series category strategy summary for provider ${provider.id}: successful=$successfulCategories empty=$emptyCategories failed=$failedCategories dedupedSeries=$acceptedCount concurrency=$concurrency")

        return when {
            acceptedCount > 0 && failedCategories == 0 -> CatalogSyncPayload(
                catalogResult = CatalogStrategyResult.Success("category_bulk", emptyList(), warnings.toList()),
                categories = fallbackCollector.entities().takeIf { it.isNotEmpty() },
                stagedSessionId = sessionId,
                stagedAcceptedCount = acceptedCount
            )
            acceptedCount > 0 -> CatalogSyncPayload(
                catalogResult = CatalogStrategyResult.Partial("category_bulk", emptyList(), warnings.toList()),
                categories = fallbackCollector.entities().takeIf { it.isNotEmpty() },
                stagedSessionId = sessionId,
                stagedAcceptedCount = acceptedCount
            )
            failedCategories > 0 -> CatalogSyncPayload(
                catalogResult = CatalogStrategyResult.Failure(
                    strategyName = "category_bulk",
                    error = IllegalStateException("Series category-bulk sync failed for all usable categories"),
                    warnings = warnings.toList()
                ),
                categories = null
            )
            else -> CatalogSyncPayload(
                catalogResult = CatalogStrategyResult.EmptyValid(
                    strategyName = "category_bulk",
                    warnings = listOf("All series categories returned valid empty results.")
                ),
                categories = null
            )
        }
    }

    private suspend fun loadXtreamSeriesByPage(
        provider: Provider,
        api: XtreamProvider,
        onProgress: ((String) -> Unit)?
    ): CatalogSyncPayload<Series> {
        val seenSeriesIds = HashSet<Long>()
        val fallbackCollector = FallbackCategoryCollector(provider.id, ContentType.SERIES)
        val warnings = mutableListOf<String>()
        var sessionId: Long? = null
        var acceptedCount = 0
        var nextPage = 1
        var stopPaging = false
        var forceSequential = false

        while (nextPage <= XTREAM_CATALOG_MAX_PAGES && !stopPaging) {
            val remainingPages = XTREAM_CATALOG_MAX_PAGES - nextPage + 1
            val concurrency = xtreamAdaptiveSyncPolicy.concurrencyFor(
                providerId = provider.id,
                workloadSize = remainingPages,
                preferSequential = forceSequential,
                stage = XtreamAdaptiveSyncPolicy.Stage.PAGED
            )
            val pageWindow = (nextPage until (nextPage + concurrency))
                .takeWhile { it <= XTREAM_CATALOG_MAX_PAGES }
            progress(
                provider.id,
                onProgress,
                if (pageWindow.size == 1) {
                    "Downloading Series by page ${pageWindow.first()}..."
                } else {
                    "Downloading Series by page ${pageWindow.first()}-${pageWindow.last()}..."
                }
            )

            var timedOutcomes = coroutineScope {
                pageWindow.map { page ->
                    async { fetchSeriesPageOutcome(provider, api, page) }
                }.awaitAll()
            }
            val failures = timedOutcomes.count { it.outcome is PageFetchOutcome.Failure }
            val recoveryPlan = evaluatePageRecoveryPlan(
                provider = provider,
                sectionLabel = "Series",
                pageWindow = pageWindow,
                outcomes = timedOutcomes,
                sequentialModeWarning = SERIES_PAGED_SEQUENTIAL_MODE_WARNING
            )
            forceSequential = forceSequential || recoveryPlan.warnings.any { it == SERIES_PAGED_SEQUENTIAL_MODE_WARNING }
            warnings += recoveryPlan.warnings
            if (recoveryPlan.stoppedEarly) {
                stopPaging = true
            }
            if (!recoveryPlan.stoppedEarly && concurrency > 1 && shouldRetryFailedPages(pageWindow.size, failures, timedOutcomes.map { it.outcome })) {
                timedOutcomes = continueFailedPageOutcomes(
                    provider = provider,
                    timedOutcomes = timedOutcomes,
                    fetchSequentially = { page -> fetchSeriesPageOutcome(provider, api, page) }
                )
            }

            var terminalFailure: Throwable? = null
            var terminalFailurePage: Int? = null
            timedOutcomes.sortedBy { it.page }.forEach { timedOutcome ->
                when (val outcome = timedOutcome.outcome) {
                    is PageFetchOutcome.Success -> {
                        val staged = stageSeriesItems(
                            providerId = provider.id,
                            items = outcome.items,
                            seenSeriesIds = seenSeriesIds,
                            fallbackCollector = fallbackCollector,
                            sessionId = sessionId
                        )
                        sessionId = staged.sessionId
                        acceptedCount += staged.acceptedCount
                        val newItems = staged.acceptedCount
                        if (outcome.rawCount < XTREAM_CATALOG_PAGE_SIZE || newItems == 0) {
                            stopPaging = true
                        }
                    }
                    is PageFetchOutcome.Empty -> {
                        stopPaging = true
                    }
                    is PageFetchOutcome.Failure -> {
                        warnings += pagingFailureWarning("Series", outcome.page, outcome.error)
                        if (terminalFailure == null) {
                            terminalFailure = outcome.error
                            terminalFailurePage = outcome.page
                        }
                    }
                }
            }

            if (terminalFailure != null) {
                return if (acceptedCount == 0) {
                    CatalogSyncPayload(
                        catalogResult = CatalogStrategyResult.Failure(
                            strategyName = "paged",
                            error = terminalFailure!!,
                            warnings = listOf(
                                pagingFailureWarning("Series", terminalFailurePage ?: nextPage, terminalFailure!!)
                            )
                        )
                    ,
                        categories = null
                    )
                } else {
                    CatalogSyncPayload(
                        catalogResult = CatalogStrategyResult.Partial(
                            strategyName = "paged",
                            items = emptyList(),
                            warnings = warnings.toList()
                        ),
                        categories = fallbackCollector.entities().takeIf { it.isNotEmpty() },
                        stagedSessionId = sessionId,
                        stagedAcceptedCount = acceptedCount
                    )
                }
            }

            nextPage = pageWindow.last() + 1
        }

        return if (acceptedCount > 0) {
            CatalogSyncPayload(
                catalogResult = CatalogStrategyResult.Success("paged", emptyList(), warnings.toList()),
                categories = fallbackCollector.entities().takeIf { it.isNotEmpty() },
                stagedSessionId = sessionId,
                stagedAcceptedCount = acceptedCount
            )
        } else {
            CatalogSyncPayload(
                catalogResult = CatalogStrategyResult.EmptyValid(
                    strategyName = "paged",
                    warnings = listOf("Paged series catalog completed without items.")
                ),
                categories = null
            )
        }
    }

    private suspend fun updateSyncStatusMetadata(providerId: Long, status: String) {
        val metadata = (syncMetadataRepository.getMetadata(providerId) ?: SyncMetadata(providerId))
            .copy(lastSyncStatus = status)
        syncMetadataRepository.updateMetadata(metadata)
    }

    private suspend fun importM3uPlaylist(
        provider: Provider,
        onProgress: ((String) -> Unit)?,
        includeLive: Boolean = true,
        includeMovies: Boolean = true,
        batchSize: Int = 1000
    ): M3uImportStats {
        UrlSecurityPolicy.validatePlaylistSourceUrl(provider.m3uUrl.ifBlank { provider.serverUrl })?.let { message ->
            throw IllegalStateException(message)
        }
        progress(provider.id, onProgress, "Downloading Playlist...")
        syncCatalogStore.clearProviderStaging(provider.id)
        val sessionId = syncCatalogStore.newSessionId()
        val stableLongHasher = StableLongHasher()
        val liveCategories = CategoryAccumulator(provider.id, ContentType.LIVE, stableLongHasher)
        val movieCategories = CategoryAccumulator(provider.id, ContentType.MOVIE, stableLongHasher)
        val channelBatch = ArrayList<ChannelEntity>(batchSize)
        val movieBatch = ArrayList<MovieEntity>(batchSize)
        val seenLiveStreamIds = if (includeLive) mutableSetOf<Long>() else null
        val seenMovieStreamIds = if (includeMovies) mutableSetOf<Long>() else null
        var header = M3uParser.M3uHeader()
        var liveCount = 0
        var movieCount = 0
        var parsedCount = 0
        var nextMilestone = PROGRESS_INTERVAL
        val warnings = mutableListOf<String>()
        var insecureStreamCount = 0

        try {
            openPlaylistStream(provider) { streamed ->
                progress(provider.id, onProgress, "Parsing Playlist...")
                maybeDecompressPlaylist(streamed).use { input ->
                    m3uParser.parseStreaming(
                        inputStream = input,
                        onHeader = { parsedHeader ->
                            val secureEpgUrl = parsedHeader.tvgUrl?.takeIf { UrlSecurityPolicy.isSecureRemoteUrl(it) }
                            if (parsedHeader.tvgUrl != null && secureEpgUrl == null) {
                                warnings += "Ignored insecure EPG URL from playlist header."
                            }
                            header = parsedHeader.copy(tvgUrl = secureEpgUrl)
                        }
                    ) { entry ->
                        parsedCount++
                        if (parsedCount >= nextMilestone) {
                            progress(provider.id, onProgress, "Imported $parsedCount playlist entries...")
                            nextMilestone += PROGRESS_INTERVAL
                        }
                        if (!UrlSecurityPolicy.isAllowedStreamEntryUrl(entry.url)) {
                            insecureStreamCount++
                            return@parseStreaming
                        }

                        val safeLogoUrl = UrlSecurityPolicy.sanitizeImportedAssetUrl(entry.tvgLogo)
                        val safeCatchUpSource = UrlSecurityPolicy.sanitizeImportedAssetUrl(entry.catchUpSource)

                        if (isVodEntry(entry)) {
                            if (!includeMovies) {
                                return@parseStreaming
                            }
                            val groupTitle = entry.groupTitle.ifBlank { "Uncategorized" }
                            val stableStreamId = stableId(
                                providerId = provider.id,
                                contentType = ContentType.MOVIE,
                                tvgId = entry.tvgId,
                                url = entry.url,
                                title = entry.name,
                                groupTitle = groupTitle,
                                hasher = stableLongHasher
                            )
                            if (seenMovieStreamIds?.add(stableStreamId) != true) {
                                return@parseStreaming
                            }
                            val categoryId = movieCategories.idFor(groupTitle)
                            val isAdult = AdultContentClassifier.isAdultCategoryName(groupTitle)
                            movieBatch.add(
                                MovieEntity(
                                    streamId = stableStreamId,
                                    name = entry.name,
                                    posterUrl = safeLogoUrl,
                                    categoryId = categoryId,
                                    categoryName = groupTitle,
                                    streamUrl = entry.url,
                                    providerId = provider.id,
                                    rating = entry.rating?.toFloatOrNull() ?: 0f,
                                    year = entry.year,
                                    genre = entry.genre,
                                    isAdult = isAdult
                                )
                            )
                            movieCount++
                            if (movieBatch.size >= batchSize) {
                                flushMovieBatch(provider.id, sessionId, movieBatch)
                            }
                        } else {
                            if (!includeLive) {
                                return@parseStreaming
                            }
                            val groupTitle = entry.groupTitle.ifBlank { "Uncategorized" }
                            val stableStreamId = stableId(
                                providerId = provider.id,
                                contentType = ContentType.LIVE,
                                tvgId = entry.tvgId,
                                url = entry.url,
                                title = entry.name,
                                groupTitle = groupTitle,
                                hasher = stableLongHasher
                            )
                            if (seenLiveStreamIds?.add(stableStreamId) != true) {
                                return@parseStreaming
                            }
                            val categoryId = liveCategories.idFor(groupTitle)
                            val isAdult = AdultContentClassifier.isAdultCategoryName(groupTitle)
                            channelBatch.add(
                                ChannelEntity(
                                    streamId = stableStreamId,
                                    name = entry.name,
                                    logoUrl = safeLogoUrl,
                                    groupTitle = groupTitle,
                                    categoryId = categoryId,
                                    categoryName = groupTitle,
                                    epgChannelId = entry.tvgId ?: entry.tvgName,
                                    number = entry.tvgChno ?: 0,
                                    streamUrl = entry.url,
                                    catchUpSupported = entry.catchUp != null,
                                    catchUpDays = entry.catchUpDays ?: 0,
                                    catchUpSource = safeCatchUpSource,
                                    providerId = provider.id,
                                    isAdult = isAdult
                                )
                            )
                            liveCount++
                            if (channelBatch.size >= batchSize) {
                                flushChannelBatch(provider.id, sessionId, channelBatch)
                            }
                        }
                    }
                }
            }

            flushChannelBatch(provider.id, sessionId, channelBatch)
            flushMovieBatch(provider.id, sessionId, movieBatch)
            syncCatalogStore.finalizeStagedImport(
                providerId = provider.id,
                sessionId = sessionId,
                liveCategories = if (includeLive) liveCategories.entities() else null,
                movieCategories = if (includeMovies) movieCategories.entities() else null,
                includeLive = includeLive,
                includeMovies = includeMovies
            )
        } finally {
            syncCatalogStore.discardStagedImport(provider.id, sessionId)
        }

        if (insecureStreamCount > 0) {
            warnings += "Ignored $insecureStreamCount insecure playlist stream URL(s)."
        }

        return M3uImportStats(
            header = header,
            liveCount = liveCount,
            movieCount = movieCount,
            warnings = warnings
        )
    }

    private suspend fun openPlaylistStream(
        provider: Provider,
        block: suspend (StreamedPlaylist) -> Unit
    ) {
        val urlStr = provider.m3uUrl.ifBlank { provider.serverUrl }
        if (urlStr.startsWith("file:")) {
            java.io.File(java.net.URI(urlStr)).inputStream().use { input ->
                block(StreamedPlaylist(inputStream = input, sourceName = urlStr))
            }
            return
        }

        retryTransient {
            okHttpClient.newCall(Request.Builder().url(urlStr).build()).execute().use { response ->
                ensureSuccessfulPlaylistResponse(response)
                val body = response.body ?: throw IllegalStateException("Empty M3U response")
                body.byteStream().use { input ->
                    block(
                        StreamedPlaylist(
                            inputStream = input,
                            contentEncoding = response.header("Content-Encoding"),
                            sourceName = urlStr
                        )
                    )
                }
            }
        }
    }

    private fun ensureSuccessfulPlaylistResponse(response: Response) {
        if (response.isSuccessful) {
            return
        }
        if (response.code in 500..599 || response.code == 429) {
            throw IOException("Transient HTTP ${response.code}")
        }
        throw IllegalStateException("Failed to download M3U: HTTP ${response.code}")
    }

    private fun maybeDecompressPlaylist(streamed: StreamedPlaylist): InputStream {
        val buffered = if (streamed.inputStream is BufferedInputStream) {
            streamed.inputStream
        } else {
            BufferedInputStream(streamed.inputStream, 64 * 1024)
        }
        buffered.mark(2)
        val first = buffered.read()
        val second = buffered.read()
        buffered.reset()
        val gzipMagic = first == 0x1f && second == 0x8b
        val encodedGzip = streamed.contentEncoding?.contains("gzip", ignoreCase = true) == true
        val namedGzip = streamed.sourceName?.lowercase()?.endsWith(".gz") == true
        return if (gzipMagic || encodedGzip || namedGzip) {
            GZIPInputStream(buffered, 64 * 1024)
        } else {
            buffered
        }
    }

    private suspend fun flushChannelBatch(providerId: Long, sessionId: Long, batch: MutableList<ChannelEntity>) {
        if (batch.isEmpty()) {
            return
        }
        syncCatalogStore.stageChannelBatch(providerId, sessionId, batch)
        batch.clear()
    }

    private suspend fun flushMovieBatch(providerId: Long, sessionId: Long, batch: MutableList<MovieEntity>) {
        if (batch.isEmpty()) {
            return
        }
        syncCatalogStore.stageMovieBatch(providerId, sessionId, batch)
        batch.clear()
    }

    private suspend fun fetchXtreamVodCategories(provider: Provider): List<XtreamCategory>? {
        return when (val attempt = attemptNonCancellation {
            withMovieRequestTimeout("movie categories") {
                retryXtreamCatalogTransient(provider.id) {
                    executeXtreamRequest(provider.id, XtreamAdaptiveSyncPolicy.Stage.LIGHTWEIGHT) {
                        xtreamCatalogApiService.getVodCategories(
                            XtreamUrlFactory.buildPlayerApiUrl(
                                serverUrl = provider.serverUrl,
                                username = provider.username,
                                password = provider.password,
                                action = "get_vod_categories"
                            )
                        )
                    }
                }
            }
        }) {
            is Attempt.Success -> attempt.value
            is Attempt.Failure -> {
                Log.w(TAG, "Xtream VOD categories request failed for provider ${provider.id}: ${sanitizeThrowableMessage(attempt.error)}")
                null
            }
        }
    }

    private fun strategyWarnings(result: CatalogStrategyResult<*>): List<String> = when (result) {
        is CatalogStrategyResult.Success -> result.warnings
        is CatalogStrategyResult.Partial -> result.warnings
        is CatalogStrategyResult.Failure -> result.warnings
        is CatalogStrategyResult.EmptyValid -> result.warnings
    }

    private fun <T> shouldDowngradeCategorySync(
        totalCategories: Int,
        failures: Int,
        fastFailures: Int,
        outcomes: List<CategoryFetchOutcome<T>>
    ): Boolean {
        if (totalCategories <= 1) return false
        val failureRatio = failures.toFloat() / totalCategories.toFloat()
        val firstWindow = outcomes.take(minOf(4, outcomes.size))
        val firstWindowFailures = firstWindow.count { outcome ->
            outcome is CategoryFetchOutcome.Failure && shouldRememberSequentialPreference(outcome.error)
        }
        return failureRatio >= 0.75f ||
            fastFailures >= minOf(3, totalCategories) ||
            firstWindowFailures >= minOf(3, firstWindow.size)
    }

    private fun <T> shouldRetryFailedCategories(
        totalCategories: Int,
        failures: Int,
        downgradeRecommended: Boolean,
        outcomes: List<CategoryFetchOutcome<T>>
    ): Boolean {
        if (totalCategories <= 1 || failures == 0) {
            return false
        }
        val stressFailures = outcomes.count { outcome ->
            outcome is CategoryFetchOutcome.Failure && shouldRememberSequentialPreference(outcome.error)
        }
        return downgradeRecommended || stressFailures > 0 || failures <= minOf(2, totalCategories)
    }

    private fun <T> shouldRetryFailedPages(
        totalPages: Int,
        failures: Int,
        outcomes: List<PageFetchOutcome<T>>
    ): Boolean {
        if (totalPages <= 1 || failures == 0) {
            return false
        }
        val stressFailures = outcomes.count { outcome ->
            outcome is PageFetchOutcome.Failure && shouldRememberSequentialPreference(outcome.error)
        }
        return stressFailures > 0 || failures <= minOf(2, totalPages)
    }

    private fun shouldRememberSequentialPreference(error: Throwable): Boolean {
        return xtreamAdaptiveSyncPolicy.isProviderStress(error) ||
            error is XtreamAuthenticationException ||
            error is XtreamParsingException ||
            (error is XtreamRequestException && error.statusCode in setOf(403, 429)) ||
            (error is XtreamNetworkException && error.message.orEmpty().contains("reset", ignoreCase = true))
    }

    private fun paginationParamsForPage(page: Int): Map<String, String> {
        return mapOf(
            "page" to page.toString(),
            "limit" to XTREAM_CATALOG_PAGE_SIZE.toString(),
            "offset" to ((page - 1) * XTREAM_CATALOG_PAGE_SIZE).toString(),
            "items_per_page" to XTREAM_CATALOG_PAGE_SIZE.toString()
        )
    }

    private fun updateSequentialProviderAdaptation(
        previousRemembered: Boolean,
        previousHealthyStreak: Int,
        sawSequentialStress: Boolean
    ): SequentialProviderAdaptation {
        if (sawSequentialStress) {
            return SequentialProviderAdaptation(rememberSequential = true, healthyStreak = 0)
        }
        if (!previousRemembered) {
            return SequentialProviderAdaptation(rememberSequential = false, healthyStreak = 0)
        }
        val nextHealthyStreak = (previousHealthyStreak + 1).coerceAtMost(2)
        return if (nextHealthyStreak >= 2) {
            SequentialProviderAdaptation(rememberSequential = false, healthyStreak = 0)
        } else {
            SequentialProviderAdaptation(rememberSequential = true, healthyStreak = nextHealthyStreak)
        }
    }

    private fun updateAvoidFullUntil(
        previousAvoidFullUntil: Long,
        now: Long,
        feedback: XtreamStrategyFeedback
    ): Long {
        return when {
            feedback.attemptedFullCatalog && feedback.fullCatalogUnsafe -> now + XTREAM_AVOID_FULL_CATALOG_COOLDOWN_MILLIS
            feedback.attemptedFullCatalog -> 0L
            feedback.preferredSegmentedFirst && feedback.segmentedStressDetected -> 0L
            previousAvoidFullUntil <= now -> 0L
            else -> previousAvoidFullUntil
        }
    }

    private fun shouldPreferSegmentedLiveSync(
        metadata: SyncMetadata,
        now: Long
    ): Boolean {
        return metadata.liveAvoidFullUntil > now
    }

    private fun shouldPreferSegmentedMovieSync(
        metadata: SyncMetadata,
        now: Long
    ): Boolean {
        return metadata.movieAvoidFullUntil > now
    }

    private fun shouldPreferSegmentedSeriesSync(
        metadata: SyncMetadata,
        now: Long
    ): Boolean {
        return metadata.seriesAvoidFullUntil > now
    }

    private fun shouldAvoidFullCatalogStrategy(error: Throwable): Boolean {
        return when (error) {
            is XtreamResponseTooLargeException,
            is XtreamParsingException -> true
            is java.net.SocketTimeoutException,
            is java.io.InterruptedIOException -> true
            is XtreamNetworkException ->
                error.message.orEmpty().contains("timed out", ignoreCase = true)
            is IllegalStateException -> {
                val normalized = error.message.orEmpty().lowercase()
                normalized.contains("oversized") ||
                    normalized.contains("safe in-memory budget") ||
                    normalized.contains("unreadable response") ||
                    normalized.contains("invalid catalog data")
            }
            else -> false
        }
    }

    private fun sawSegmentedStress(
        warnings: List<String>,
        result: CatalogStrategyResult<*>,
        sequentialWarnings: Set<String>
    ): Boolean {
        val warningMatched = warnings.any { warning ->
            sequentialWarnings.any { marker -> warning.contains(marker, ignoreCase = true) }
        }
        val resultWarningMatched = strategyWarnings(result).any { warning ->
            sequentialWarnings.any { marker -> warning.contains(marker, ignoreCase = true) }
        }
        val failureMatched = (
            (result as? CatalogStrategyResult.Failure)
                ?.error
                ?.let(::shouldRememberSequentialPreference)
            ) == true
        return warningMatched || resultWarningMatched || failureMatched
    }

    private fun stableId(
        providerId: Long,
        contentType: ContentType,
        tvgId: String?,
        url: String,
        title: String,
        groupTitle: String?,
        hasher: StableLongHasher
    ): Long {
        val normalizedUrl = normalizeUrlForIdentity(url)
        val normalizedTvgId = tvgId?.trim()?.lowercase().orEmpty()
        val normalizedTitle = normalizeTextForIdentity(title)
        val normalizedGroup = normalizeTextForIdentity(groupTitle)
        val identity = if (normalizedTvgId.isNotBlank()) {
            "$providerId|${contentType.name}|tvg=$normalizedTvgId|url=$normalizedUrl"
        } else {
            "$providerId|${contentType.name}|url=$normalizedUrl|title=$normalizedTitle|group=$normalizedGroup"
        }
        return hasher.hash(identity)
    }

    private fun normalizeUrlForIdentity(url: String): String {
        val parsed = runCatching { URI(url) }.getOrNull()
        val scheme = parsed?.scheme?.lowercase().orEmpty()
        val host = parsed?.host?.lowercase().orEmpty()
        val path = parsed?.path.orEmpty().trimEnd('/')
        val query = parsed?.query
            ?.split('&')
            ?.mapNotNull { pair ->
                val key = pair.substringBefore('=').lowercase()
                val value = pair.substringAfter('=', "")
                when (key) {
                    "token", "auth", "password", "username" -> null
                    else -> "$key=$value"
                }
            }
            ?.sorted()
            ?.joinToString("&")
            .orEmpty()
        return listOf(scheme, host, path, query)
            .joinToString("|")
            .ifBlank { url.trim().lowercase() }
    }

    private fun normalizeTextForIdentity(value: String?): String {
        return value
            .orEmpty()
            .lowercase()
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun buildFallbackMovieCategories(providerId: Long, movies: List<Movie>): List<CategoryEntity> {
        return buildFallbackCategories(
            providerId = providerId,
            type = ContentType.MOVIE,
            items = movies,
            categoryId = { movie -> movie.categoryId },
            categoryName = { movie -> movie.categoryName },
            isAdult = { movie -> movie.isAdult }
        )
    }

    private fun buildFallbackLiveCategories(providerId: Long, channels: List<Channel>): List<CategoryEntity> {
        return buildFallbackCategories(
            providerId = providerId,
            type = ContentType.LIVE,
            items = channels,
            categoryId = { channel -> channel.categoryId },
            categoryName = { channel -> channel.categoryName ?: channel.groupTitle },
            isAdult = { channel -> channel.isAdult }
        )
    }

    private fun buildFallbackSeriesCategories(providerId: Long, series: List<Series>): List<CategoryEntity> {
        return buildFallbackCategories(
            providerId = providerId,
            type = ContentType.SERIES,
            items = series,
            categoryId = { item -> item.categoryId },
            categoryName = { item -> item.categoryName },
            isAdult = { item -> item.isAdult }
        )
    }

    private fun <T> buildFallbackCategories(
        providerId: Long,
        type: ContentType,
        items: List<T>,
        categoryId: (T) -> Long?,
        categoryName: (T) -> String?,
        isAdult: (T) -> Boolean
    ): List<CategoryEntity> {
        return items
            .asSequence()
            .mapNotNull { item ->
                val resolvedCategoryId = categoryId(item) ?: return@mapNotNull null
                val resolvedCategoryName = categoryName(item)?.trim().takeUnless { it.isNullOrEmpty() }
                    ?: "Category $resolvedCategoryId"
                CategoryEntity(
                    categoryId = resolvedCategoryId,
                    name = resolvedCategoryName,
                    parentId = 0,
                    type = type,
                    providerId = providerId,
                    isAdult = isAdult(item) || AdultContentClassifier.isAdultCategoryName(resolvedCategoryName)
                )
            }
            .distinctBy { it.categoryId }
            .toList()
    }

    private suspend fun stageMovieItems(
        providerId: Long,
        items: List<Movie>,
        seenStreamIds: MutableSet<Long>,
        fallbackCollector: FallbackCategoryCollector,
        sessionId: Long?
    ): StagedCatalogSnapshot {
        var resolvedSessionId = sessionId
        val acceptedEntities = ArrayList<MovieEntity>(items.size)
        items.forEach { movie ->
            val streamId = movieKey(movie)
            if (streamId <= 0L || !seenStreamIds.add(streamId)) {
                return@forEach
            }
            fallbackCollector.record(movie.categoryId, movie.categoryName, movie.isAdult)
            acceptedEntities += movie.toEntity()
        }
        if (acceptedEntities.isNotEmpty()) {
            if (resolvedSessionId == null) {
                syncCatalogStore.clearProviderStaging(providerId)
                resolvedSessionId = syncCatalogStore.newSessionId()
            }
            syncCatalogStore.stageMovieBatch(providerId, requireNotNull(resolvedSessionId), acceptedEntities)
        }
        return StagedCatalogSnapshot(
            sessionId = resolvedSessionId,
            acceptedCount = acceptedEntities.size,
            fallbackCategories = fallbackCollector.entities().takeIf { it.isNotEmpty() }
        )
    }

    private suspend fun stageSeriesItems(
        providerId: Long,
        items: List<Series>,
        seenSeriesIds: MutableSet<Long>,
        fallbackCollector: FallbackCategoryCollector,
        sessionId: Long?
    ): StagedCatalogSnapshot {
        var resolvedSessionId = sessionId
        val acceptedEntities = ArrayList<SeriesEntity>(items.size)
        items.forEach { item ->
            val seriesId = seriesKey(item)
            if (seriesId <= 0L || !seenSeriesIds.add(seriesId)) {
                return@forEach
            }
            fallbackCollector.record(item.categoryId, item.categoryName, item.isAdult)
            acceptedEntities += item.toEntity()
        }
        if (acceptedEntities.isNotEmpty()) {
            if (resolvedSessionId == null) {
                syncCatalogStore.clearProviderStaging(providerId)
                resolvedSessionId = syncCatalogStore.newSessionId()
            }
            syncCatalogStore.stageSeriesBatch(providerId, requireNotNull(resolvedSessionId), acceptedEntities)
        }
        return StagedCatalogSnapshot(
            sessionId = resolvedSessionId,
            acceptedCount = acceptedEntities.size,
            fallbackCategories = fallbackCollector.entities().takeIf { it.isNotEmpty() }
        )
    }

    private suspend fun stageMovieSequence(
        providerId: Long,
        items: Sequence<Movie>,
        seenStreamIds: MutableSet<Long>,
        fallbackCollector: FallbackCategoryCollector,
        sessionId: Long?
    ): StagedCatalogSnapshot {
        val batch = ArrayList<Movie>(XTREAM_FALLBACK_STAGE_BATCH_SIZE)
        var currentSessionId = sessionId
        var acceptedCount = 0

        suspend fun flushBatch() {
            if (batch.isEmpty()) return
            val staged = stageMovieItems(
                providerId = providerId,
                items = batch,
                seenStreamIds = seenStreamIds,
                fallbackCollector = fallbackCollector,
                sessionId = currentSessionId
            )
            currentSessionId = staged.sessionId
            acceptedCount += staged.acceptedCount
            batch.clear()
        }

        items.forEach { movie ->
            batch += movie
            if (batch.size >= XTREAM_FALLBACK_STAGE_BATCH_SIZE) {
                flushBatch()
            }
        }
        flushBatch()

        return StagedCatalogSnapshot(
            sessionId = currentSessionId,
            acceptedCount = acceptedCount,
            fallbackCategories = fallbackCollector.entities().takeIf { it.isNotEmpty() }
        )
    }

    private suspend fun stageSeriesSequence(
        providerId: Long,
        items: Sequence<Series>,
        seenSeriesIds: MutableSet<Long>,
        fallbackCollector: FallbackCategoryCollector,
        sessionId: Long?
    ): StagedCatalogSnapshot {
        val batch = ArrayList<Series>(XTREAM_FALLBACK_STAGE_BATCH_SIZE)
        var currentSessionId = sessionId
        var acceptedCount = 0

        suspend fun flushBatch() {
            if (batch.isEmpty()) return
            val staged = stageSeriesItems(
                providerId = providerId,
                items = batch,
                seenSeriesIds = seenSeriesIds,
                fallbackCollector = fallbackCollector,
                sessionId = currentSessionId
            )
            currentSessionId = staged.sessionId
            acceptedCount += staged.acceptedCount
            batch.clear()
        }

        items.forEach { series ->
            batch += series
            if (batch.size >= XTREAM_FALLBACK_STAGE_BATCH_SIZE) {
                flushBatch()
            }
        }
        flushBatch()

        return StagedCatalogSnapshot(
            sessionId = currentSessionId,
            acceptedCount = acceptedCount,
            fallbackCategories = fallbackCollector.entities().takeIf { it.isNotEmpty() }
        )
    }

    private fun movieKey(movie: Movie): Long = movie.streamId.takeIf { it > 0L } ?: movie.id

    private fun seriesKey(item: Series): Long = item.seriesId.takeIf { it > 0L } ?: item.id

    /** Delegates to M3uParser to avoid duplicate logic. */
    internal fun isVodEntry(entry: M3uParser.M3uEntry): Boolean = M3uParser.isVodEntry(entry)

    private fun progress(providerId: Long, callback: ((String) -> Unit)?, message: String) {
        publishSyncState(providerId, SyncState.Syncing(message))
        callback?.invoke(message)
    }

    private fun publishSyncState(providerId: Long, state: SyncState) {
        syncStateTracker.publish(providerId, state)
    }

    private fun redactUrlForLogs(url: String?): String {
        if (url.isNullOrBlank()) return "<empty>"
        return runCatching {
            val parsed = URI(url)
            val scheme = parsed.scheme ?: "http"
            val host = parsed.host ?: return@runCatching "<redacted>"
            val path = parsed.path.orEmpty()
            "$scheme://$host$path"
        }.getOrDefault("<redacted>")
    }

    private fun sanitizeThrowableMessage(error: Throwable?): String {
        return syncErrorSanitizer.throwableMessage(error)
    }

    private fun sanitizeLogMessage(message: String?): String {
        return syncErrorSanitizer.sanitize(message)
    }

    private fun fullCatalogFallbackWarning(sectionLabel: String, error: Throwable?): String {
        return when (error) {
            is XtreamResponseTooLargeException ->
                "$sectionLabel full catalog was too large for one request, so sync continued with a safer segmented mode."
            else ->
                "$sectionLabel full catalog request failed, so sync continued with a safer fallback mode."
        }
    }

    private fun categoryFailureWarning(sectionLabel: String, categoryName: String, error: Throwable): String {
        val safeCategoryName = sanitizeLogMessage(categoryName).takeIf { it.isNotBlank() } ?: "Unknown"
        return when (error) {
            is XtreamResponseTooLargeException ->
                "$sectionLabel category '$safeCategoryName' was too large to load safely."
            else ->
                "$sectionLabel category '$safeCategoryName' failed: " +
                    syncErrorSanitizer.userMessage(error, "Provider request failed.")
        }
    }

    private fun pagingFailureWarning(sectionLabel: String, page: Int, error: Throwable): String {
        return when (error) {
            is XtreamResponseTooLargeException ->
                "$sectionLabel paging response on page $page was too large to load safely."
            else ->
                "$sectionLabel paging failed on page $page: " +
                    syncErrorSanitizer.userMessage(error, "Provider request failed.")
        }
    }

    private suspend fun <T> executeCategoryRecoveryPlan(
        provider: Provider,
        categories: List<XtreamCategory>,
        initialConcurrency: Int,
        sectionLabel: String,
        sequentialModeWarning: String,
        onProgress: ((String) -> Unit)?,
        fetch: suspend (XtreamCategory) -> TimedCategoryOutcome<T>
    ): CategoryExecutionPlan<T> {
        if (categories.isEmpty()) {
            return CategoryExecutionPlan(emptyList())
        }

        val outcomes = mutableListOf<TimedCategoryOutcome<T>>()
        val warnings = mutableListOf<String>()
        var nextIndex = 0
        var forceSequential = initialConcurrency <= 1
        var consecutiveSequentialStressFailures = 0
        var stoppedEarly = false

        while (nextIndex < categories.size && !stoppedEarly) {
            val windowConcurrency = if (forceSequential) 1 else initialConcurrency
            val window = categories.subList(nextIndex, minOf(nextIndex + windowConcurrency, categories.size))
            val windowOutcomes = coroutineScope {
                window.map { category ->
                    async { fetch(category) }
                }.awaitAll()
            }
            outcomes += windowOutcomes
            nextIndex += window.size

            val completed = outcomes.size
            progress(provider.id, onProgress, "Downloading $sectionLabel by category $completed/${categories.size}...")

            if (!forceSequential && shouldRecoverRemainingCategoryRequests(categories.size, completed, outcomes.map { it.outcome })) {
                forceSequential = true
                warnings += sequentialModeWarning
                Log.w(
                    TAG,
                    "Xtream $sectionLabel category sync is switching remaining categories to sequential mode for provider ${provider.id} after $completed/${categories.size} categories."
                )
            }

            if (forceSequential) {
                windowOutcomes.forEach { timedOutcome ->
                    val isStressFailure = (timedOutcome.outcome as? CategoryFetchOutcome.Failure)
                        ?.error
                        ?.let(::shouldRememberSequentialPreference)
                        ?: false
                    consecutiveSequentialStressFailures = if (isStressFailure) {
                        consecutiveSequentialStressFailures + 1
                    } else {
                        0
                    }
                }
                if (consecutiveSequentialStressFailures >= 3 && nextIndex < categories.size) {
                    warnings += "$sectionLabel $XTREAM_RECOVERY_ABORT_WARNING_SUFFIX"
                    Log.w(
                        TAG,
                        "Xtream $sectionLabel category sync stopped early for provider ${provider.id} after repeated sequential stress failures. completed=$completed total=${categories.size}"
                    )
                    stoppedEarly = true
                }
            }
        }

        return CategoryExecutionPlan(outcomes = outcomes, warnings = warnings.distinct())
    }

    private fun <T> shouldRecoverRemainingCategoryRequests(
        totalCategories: Int,
        processedCategories: Int,
        outcomes: List<CategoryFetchOutcome<T>>
    ): Boolean {
        if (processedCategories >= totalCategories || processedCategories <= 1) {
            return false
        }
        val processedOutcomes = outcomes.take(processedCategories)
        val failures = processedOutcomes.count { it is CategoryFetchOutcome.Failure }
        val stressFailures = processedOutcomes.count { outcome ->
            outcome is CategoryFetchOutcome.Failure && shouldRememberSequentialPreference(outcome.error)
        }
        val recentWindow = processedOutcomes.takeLast(minOf(4, processedOutcomes.size))
        val recentStressFailures = recentWindow.count { outcome ->
            outcome is CategoryFetchOutcome.Failure && shouldRememberSequentialPreference(outcome.error)
        }
        val failureRatio = failures.toFloat() / processedCategories.toFloat()
        return recentStressFailures >= minOf(2, recentWindow.size) ||
            stressFailures >= minOf(3, processedCategories) ||
            (processedCategories >= 6 && failureRatio >= 0.34f)
    }

    private fun <T> evaluatePageRecoveryPlan(
        provider: Provider,
        sectionLabel: String,
        pageWindow: List<Int>,
        outcomes: List<TimedPageOutcome<T>>,
        sequentialModeWarning: String
    ): PageExecutionPlan<T> {
        if (pageWindow.isEmpty()) {
            return PageExecutionPlan(emptyList())
        }
        val warnings = mutableListOf<String>()
        val stressFailures = outcomes.count { outcome ->
            (outcome.outcome as? PageFetchOutcome.Failure)
                ?.error
                ?.let(::shouldRememberSequentialPreference)
                ?: false
        }
        val stoppedEarly = stressFailures >= minOf(3, pageWindow.size)
        val shouldDegrade = !stoppedEarly && pageWindow.size > 1 && stressFailures >= minOf(2, pageWindow.size)
        if (shouldDegrade) {
            warnings += sequentialModeWarning
            Log.w(
                TAG,
                "Xtream $sectionLabel paged sync is switching remaining pages to sequential mode for provider ${provider.id} after window ${pageWindow.first()}-${pageWindow.last()}."
            )
        }
        if (stoppedEarly) {
            warnings += "$sectionLabel $XTREAM_RECOVERY_ABORT_WARNING_SUFFIX"
            Log.w(
                TAG,
                "Xtream $sectionLabel paged sync stopped early for provider ${provider.id} after repeated stress failures in window ${pageWindow.first()}-${pageWindow.last()}."
            )
        }
        return PageExecutionPlan(outcomes = outcomes, warnings = warnings.distinct(), stoppedEarly = stoppedEarly)
    }

    private suspend fun <T> retryTransient(
        maxAttempts: Int = 3,
        initialDelayMs: Long = 700L,
        block: suspend () -> T
    ): T {
        var attempt = 0
        var delayMs = initialDelayMs
        var lastError: Exception? = null

        while (attempt < maxAttempts) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Exception) {
                lastError = t
                attempt++
                if (attempt >= maxAttempts || !isRetryable(t)) {
                    throw t
                }
                delay(delayMs)
                delayMs *= 2
            }
        }

        throw lastError ?: IllegalStateException("Unknown sync retry failure")
    }

    private suspend fun <T> attemptNonCancellation(block: suspend () -> T): Attempt<T> {
        return try {
            Attempt.Success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Attempt.Failure(e)
        }
    }

    private suspend fun <T> executeXtreamRequest(
        providerId: Long,
        stage: XtreamAdaptiveSyncPolicy.Stage,
        block: suspend () -> T
    ): T {
        xtreamAdaptiveSyncPolicy.awaitTurn(providerId, stage)
        return try {
            val timeoutMs = xtreamAdaptiveSyncPolicy.timeoutFor(providerId, stage)
            val result = if (timeoutMs != null) {
                withTimeout(timeoutMs) {
                    block()
                }
            } else {
                block()
            }
            xtreamAdaptiveSyncPolicy.recordSuccess(providerId)
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            xtreamAdaptiveSyncPolicy.recordFailure(providerId, e)
            throw e
        }
    }

    private suspend fun <T> withMovieRequestTimeout(
        requestLabel: String,
        block: suspend () -> T
    ): T {
        return try {
            withTimeout(XTREAM_MOVIE_REQUEST_TIMEOUT_MILLIS) {
                block()
            }
        } catch (e: TimeoutCancellationException) {
            throw IOException("Timed out after 60 seconds while loading $requestLabel", e)
        }
    }

    private suspend fun <T> withSeriesRequestTimeout(
        requestLabel: String,
        block: suspend () -> T
    ): T {
        return try {
            withTimeout(XTREAM_SERIES_REQUEST_TIMEOUT_MILLIS) {
                block()
            }
        } catch (e: TimeoutCancellationException) {
            throw IOException("Timed out after 60 seconds while loading $requestLabel", e)
        }
    }

    private suspend fun <T> retryXtreamCatalogTransient(providerId: Long, block: suspend () -> T): T {
        var attempt = 0
        var lastError: Exception? = null

        while (attempt < 3) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Exception) {
                lastError = t
                attempt++
                if (attempt >= 3 || !isXtreamCatalogRetryable(t, attempt)) {
                    throw t
                }
                val delayMs = xtreamAdaptiveSyncPolicy.retryDelayFor(providerId, attempt)
                val jitterMs = Random.nextLong(0L, (delayMs / 3L).coerceAtLeast(1L) + 1L)
                delay(delayMs + jitterMs)
            }
        }

        throw lastError ?: IllegalStateException("Unknown Xtream catalog retry failure")
    }

    private fun isRetryable(error: Throwable): Boolean {
        if (error is XtreamAuthenticationException) return false
        if (error is XtreamParsingException) return false
        if (error is XtreamRequestException) return false
        if (error is XtreamNetworkException) return true
        if (error is IOException) return true

        val message = error.message.orEmpty().lowercase()
        return message.contains("timeout") ||
            message.contains("timed out") ||
            message.contains("unable to resolve host") ||
            message.contains("connection reset") ||
            message.contains("connect") ||
            message.contains("network")
    }

    private fun isXtreamCatalogRetryable(error: Throwable, attempt: Int): Boolean {
        return when (error) {
            is XtreamAuthenticationException -> false
            is XtreamResponseTooLargeException -> false
            is XtreamParsingException -> attempt == 1
            is XtreamRequestException ->
                error.statusCode in setOf(403, 408, 409, 429) || error.statusCode in 500..599
            is XtreamNetworkException -> true
            is IOException -> true
            else -> isRetryable(error)
        }
    }

    companion object {
        private const val PROGRESS_INTERVAL = 5_000
    }

    private fun createXtreamSyncProvider(provider: Provider, useTextClassification: Boolean = true): XtreamProvider {
        return XtreamProvider(
            providerId = provider.id,
            api = xtreamCatalogApiService,
            serverUrl = provider.serverUrl,
            username = provider.username,
            password = provider.password,
            allowedOutputFormats = provider.allowedOutputFormats,
            useTextClassification = useTextClassification
        )
    }

    private fun logXtreamCatalogFallback(
        provider: Provider,
        section: String,
        stage: String,
        elapsedMs: Long,
        itemCount: Int?,
        error: Throwable?,
        nextStep: String
    ) {
        val reason = when {
            error != null -> "${error::class.java.simpleName}: ${sanitizeThrowableMessage(error)}"
            itemCount == 0 -> "empty result"
            else -> "no usable data"
        }
        Log.w(
            TAG,
            "Xtream $section $stage failed for provider ${provider.id} after ${elapsedMs}ms ($reason). Switching to $nextStep."
        )
    }

    private fun <T> com.streamvault.domain.model.Result<T>.getOrThrow(resourceName: String): T {
        return when (this) {
            is com.streamvault.domain.model.Result.Success -> data
            is com.streamvault.domain.model.Result.Error ->
                throw exception ?: IllegalStateException("Failed to fetch $resourceName: $message")
            is com.streamvault.domain.model.Result.Loading ->
                throw Exception("Unexpected loading state for $resourceName")
        }
    }

    private suspend fun fetchLiveCategoryOutcome(
        provider: Provider,
        api: XtreamProvider,
        category: XtreamCategory
    ): TimedCategoryOutcome<Channel> {
        var rawStreams: List<XtreamStream> = emptyList()
        var categoryFailure: Throwable? = null
        val elapsedMs = measureTimeMillis {
            when (val attempt = attemptNonCancellation {
                retryXtreamCatalogTransient(provider.id) {
                    executeXtreamRequest(provider.id, XtreamAdaptiveSyncPolicy.Stage.CATEGORY) {
                        xtreamCatalogApiService.getLiveStreams(
                            XtreamUrlFactory.buildPlayerApiUrl(
                                serverUrl = provider.serverUrl,
                                username = provider.username,
                                password = provider.password,
                                action = "get_live_streams",
                                extraQueryParams = mapOf("category_id" to category.categoryId)
                            )
                        )
                    }
                }
            }) {
                is Attempt.Success -> rawStreams = attempt.value
                is Attempt.Failure -> categoryFailure = attempt.error
            }
        }
        val outcome = when {
            categoryFailure != null -> {
                Log.w(TAG, "Xtream live category '${category.categoryName}' failed after ${elapsedMs}ms: ${sanitizeThrowableMessage(categoryFailure)}")
                CategoryFetchOutcome.Failure(category.categoryName, categoryFailure!!)
            }
            rawStreams.isEmpty() -> {
                Log.i(TAG, "Xtream live category '${category.categoryName}' completed in ${elapsedMs}ms with a valid empty result.")
                CategoryFetchOutcome.Empty(category.categoryName)
            }
            else -> {
                Log.i(TAG, "Xtream live category '${category.categoryName}' completed in ${elapsedMs}ms with ${rawStreams.size} raw items.")
                CategoryFetchOutcome.Success(category.categoryName, api.mapLiveStreamsResponse(rawStreams))
            }
        }
        return TimedCategoryOutcome(category, outcome, elapsedMs)
    }

    private suspend fun fetchMovieCategoryOutcome(
        provider: Provider,
        api: XtreamProvider,
        category: XtreamCategory
    ): TimedCategoryOutcome<Movie> {
        var rawStreams: List<XtreamStream> = emptyList()
        var categoryFailure: Throwable? = null
        val elapsedMs = measureTimeMillis {
            when (val attempt = attemptNonCancellation {
                withMovieRequestTimeout("movie category '${category.categoryName}'") {
                    retryXtreamCatalogTransient(provider.id) {
                        executeXtreamRequest(provider.id, XtreamAdaptiveSyncPolicy.Stage.CATEGORY) {
                            xtreamCatalogApiService.getVodStreams(
                                XtreamUrlFactory.buildPlayerApiUrl(
                                    serverUrl = provider.serverUrl,
                                    username = provider.username,
                                    password = provider.password,
                                    action = "get_vod_streams",
                                    extraQueryParams = mapOf("category_id" to category.categoryId)
                                )
                            )
                        }
                    }
                }
            }) {
                is Attempt.Success -> rawStreams = attempt.value
                is Attempt.Failure -> categoryFailure = attempt.error
            }
        }
        val outcome = when {
            categoryFailure != null -> {
                Log.w(TAG, "Xtream movie category '${category.categoryName}' failed after ${elapsedMs}ms: ${sanitizeThrowableMessage(categoryFailure)}")
                CategoryFetchOutcome.Failure(category.categoryName, categoryFailure!!)
            }
            rawStreams.isEmpty() -> {
                Log.i(TAG, "Xtream movie category '${category.categoryName}' completed in ${elapsedMs}ms with a valid empty result.")
                CategoryFetchOutcome.Empty(category.categoryName)
            }
            else -> {
                Log.i(TAG, "Xtream movie category '${category.categoryName}' completed in ${elapsedMs}ms with ${rawStreams.size} raw items.")
                CategoryFetchOutcome.Success(category.categoryName, api.mapVodStreamsResponse(rawStreams))
            }
        }
        return TimedCategoryOutcome(category, outcome, elapsedMs)
    }

    private suspend fun fetchSeriesCategoryOutcome(
        provider: Provider,
        api: XtreamProvider,
        category: XtreamCategory
    ): TimedCategoryOutcome<Series> {
        var rawSeries: List<XtreamSeriesItem> = emptyList()
        var categoryFailure: Throwable? = null
        val elapsedMs = measureTimeMillis {
            when (val attempt = attemptNonCancellation {
                withSeriesRequestTimeout("series category '${category.categoryName}'") {
                    retryXtreamCatalogTransient(provider.id) {
                        executeXtreamRequest(provider.id, XtreamAdaptiveSyncPolicy.Stage.CATEGORY) {
                            xtreamCatalogApiService.getSeriesList(
                                XtreamUrlFactory.buildPlayerApiUrl(
                                    serverUrl = provider.serverUrl,
                                    username = provider.username,
                                    password = provider.password,
                                    action = "get_series",
                                    extraQueryParams = mapOf("category_id" to category.categoryId)
                                )
                            )
                        }
                    }
                }
            }) {
                is Attempt.Success -> rawSeries = attempt.value
                is Attempt.Failure -> categoryFailure = attempt.error
            }
        }
        val outcome = when {
            categoryFailure != null -> {
                Log.w(TAG, "Xtream series category '${category.categoryName}' failed after ${elapsedMs}ms: ${sanitizeThrowableMessage(categoryFailure)}")
                CategoryFetchOutcome.Failure(category.categoryName, categoryFailure!!)
            }
            rawSeries.isEmpty() -> {
                Log.i(TAG, "Xtream series category '${category.categoryName}' completed in ${elapsedMs}ms with a valid empty result.")
                CategoryFetchOutcome.Empty(category.categoryName)
            }
            else -> {
                Log.i(TAG, "Xtream series category '${category.categoryName}' completed in ${elapsedMs}ms with ${rawSeries.size} raw items.")
                CategoryFetchOutcome.Success(category.categoryName, api.mapSeriesListResponse(rawSeries))
            }
        }
        return TimedCategoryOutcome(category, outcome, elapsedMs)
    }

    private suspend fun fetchMoviePageOutcome(
        provider: Provider,
        api: XtreamProvider,
        page: Int
    ): TimedPageOutcome<Movie> {
        var rawStreams: List<XtreamStream> = emptyList()
        var pageFailure: Throwable? = null
        val elapsedMs = measureTimeMillis {
            when (val attempt = attemptNonCancellation {
                withMovieRequestTimeout("movie page $page") {
                    retryXtreamCatalogTransient(provider.id) {
                        executeXtreamRequest(provider.id, XtreamAdaptiveSyncPolicy.Stage.PAGED) {
                            xtreamCatalogApiService.getVodStreams(
                                XtreamUrlFactory.buildPlayerApiUrl(
                                    serverUrl = provider.serverUrl,
                                    username = provider.username,
                                    password = provider.password,
                                    action = "get_vod_streams",
                                    extraQueryParams = paginationParamsForPage(page)
                                )
                            )
                        }
                    }
                }
            }) {
                is Attempt.Success -> rawStreams = attempt.value
                is Attempt.Failure -> pageFailure = attempt.error
            }
        }
        val outcome = when {
            pageFailure != null -> {
                Log.w(
                    TAG,
                    "Xtream paged movie request failed for provider ${provider.id} on page $page after ${elapsedMs}ms: ${sanitizeThrowableMessage(pageFailure)}"
                )
                PageFetchOutcome.Failure(page, pageFailure!!)
            }
            rawStreams.isEmpty() -> {
                Log.i(
                    TAG,
                    "Xtream paged movie request for provider ${provider.id} page $page completed in ${elapsedMs}ms with a valid empty result."
                )
                PageFetchOutcome.Empty(page)
            }
            else -> {
                Log.i(
                    TAG,
                    "Xtream paged movie request for provider ${provider.id} page $page completed in ${elapsedMs}ms with ${rawStreams.size} raw items."
                )
                PageFetchOutcome.Success(api.mapVodStreamsResponse(rawStreams), rawStreams.size)
            }
        }
        return TimedPageOutcome(page = page, outcome = outcome, elapsedMs = elapsedMs)
    }

    private suspend fun fetchSeriesPageOutcome(
        provider: Provider,
        api: XtreamProvider,
        page: Int
    ): TimedPageOutcome<Series> {
        var rawSeries: List<XtreamSeriesItem> = emptyList()
        var pageFailure: Throwable? = null
        val elapsedMs = measureTimeMillis {
            when (val attempt = attemptNonCancellation {
                withSeriesRequestTimeout("series page $page") {
                    retryXtreamCatalogTransient(provider.id) {
                        executeXtreamRequest(provider.id, XtreamAdaptiveSyncPolicy.Stage.PAGED) {
                            xtreamCatalogApiService.getSeriesList(
                                XtreamUrlFactory.buildPlayerApiUrl(
                                    serverUrl = provider.serverUrl,
                                    username = provider.username,
                                    password = provider.password,
                                    action = "get_series",
                                    extraQueryParams = paginationParamsForPage(page)
                                )
                            )
                        }
                    }
                }
            }) {
                is Attempt.Success -> rawSeries = attempt.value
                is Attempt.Failure -> pageFailure = attempt.error
            }
        }
        val outcome = when {
            pageFailure != null -> {
                Log.w(
                    TAG,
                    "Xtream paged series request failed for provider ${provider.id} on page $page after ${elapsedMs}ms: ${sanitizeThrowableMessage(pageFailure)}"
                )
                PageFetchOutcome.Failure(page, pageFailure!!)
            }
            rawSeries.isEmpty() -> {
                Log.i(
                    TAG,
                    "Xtream paged series request for provider ${provider.id} page $page completed in ${elapsedMs}ms with a valid empty result."
                )
                PageFetchOutcome.Empty(page)
            }
            else -> {
                Log.i(
                    TAG,
                    "Xtream paged series request for provider ${provider.id} page $page completed in ${elapsedMs}ms with ${rawSeries.size} raw items."
                )
                PageFetchOutcome.Success(api.mapSeriesListResponse(rawSeries), rawSeries.size)
            }
        }
        return TimedPageOutcome(page = page, outcome = outcome, elapsedMs = elapsedMs)
    }

    private suspend fun <T> continueFailedPageOutcomes(
        provider: Provider,
        timedOutcomes: List<TimedPageOutcome<T>>,
        fetchSequentially: suspend (Int) -> TimedPageOutcome<T>
    ): List<TimedPageOutcome<T>> {
        val failedPages = timedOutcomes
            .filter { it.outcome is PageFetchOutcome.Failure }
            .map { it.page }
        if (failedPages.isEmpty()) {
            return timedOutcomes
        }
        val replacements = LinkedHashMap<Int, TimedPageOutcome<T>>()
        failedPages.forEach { page ->
            replacements[page] = fetchSequentially(page)
        }
        return timedOutcomes.map { existing ->
            replacements[existing.page] ?: existing
        }.also {
            Log.i(
                TAG,
                "Xtream continuation fallback kept ${timedOutcomes.size - failedPages.size} successful page results for provider ${provider.id} and retried only ${failedPages.size} failed pages."
            )
        }
    }

    private suspend fun <T> continueFailedCategoryOutcomes(
        provider: Provider,
        timedOutcomes: List<TimedCategoryOutcome<T>>,
        fetchSequentially: suspend (XtreamCategory) -> TimedCategoryOutcome<T>
    ): List<TimedCategoryOutcome<T>> {
        val failedCategories = timedOutcomes
            .filter { it.outcome is CategoryFetchOutcome.Failure }
            .map { it.category }
        if (failedCategories.isEmpty()) {
            return timedOutcomes
        }
        val replacements = LinkedHashMap<String, TimedCategoryOutcome<T>>()
        failedCategories.forEach { category ->
            replacements[category.categoryId] = fetchSequentially(category)
        }
        return timedOutcomes.map { existing ->
            replacements[existing.category.categoryId] ?: existing
        }.also {
            Log.i(
                TAG,
                "Xtream continuation fallback kept ${timedOutcomes.size - failedCategories.size} successful category results for provider ${provider.id} and retried only ${failedCategories.size} failed categories."
            )
        }
    }
}
