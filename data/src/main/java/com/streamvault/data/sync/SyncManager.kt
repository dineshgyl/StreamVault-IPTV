package com.streamvault.data.sync

import android.util.Log
import com.streamvault.data.local.dao.CategoryDao
import com.streamvault.data.local.dao.ChannelDao
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.dao.ProgramDao
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.dao.SeriesDao
import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.local.entity.ChannelEntity
import com.streamvault.data.local.entity.MovieEntity
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit.MINUTES
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlin.system.measureTimeMillis

private const val TAG = "SyncManager"
private const val XTREAM_LIVE_CATEGORY_SYNC_MAX_CONCURRENCY = 4
private const val XTREAM_CATALOG_TIMEOUT_MINUTES = 5L
private const val XTREAM_CATALOG_PAGE_SIZE = 1000
private const val XTREAM_CATALOG_MAX_PAGES = 200
private const val MOVIE_CATEGORY_SEQUENTIAL_MODE_WARNING =
    "Movies category-bulk sync downgraded to sequential mode after provider stress signals."
private const val LIVE_CATEGORY_SEQUENTIAL_MODE_WARNING =
    "Live category sync downgraded to sequential mode after provider stress signals."
private const val SERIES_CATEGORY_SEQUENTIAL_MODE_WARNING =
    "Series category sync downgraded to sequential mode after provider stress signals."

enum class SyncRepairSection {
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
    private val xtreamJson: Json,
    private val m3uParser: M3uParser,
    private val epgRepository: EpgRepository,
    private val okHttpClient: OkHttpClient,
    private val syncMetadataRepository: SyncMetadataRepository
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

    private data class MovieCatalogSyncResult(
        val catalogResult: CatalogStrategyResult<Movie>,
        val categories: List<CategoryEntity>?,
        val syncMode: VodSyncMode,
        val warnings: List<String> = emptyList()
    )

    private data class CatalogSyncPayload<T>(
        val catalogResult: CatalogStrategyResult<T>,
        val categories: List<CategoryEntity>?,
        val warnings: List<String> = emptyList()
    )

    private data class MovieProviderAdaptation(
        val rememberSequential: Boolean,
        val healthyStreak: Int
    )

    private sealed interface CategoryFetchOutcome<out T> {
        data class Success<T>(val categoryName: String, val items: List<T>) : CategoryFetchOutcome<T>
        data class Empty(val categoryName: String) : CategoryFetchOutcome<Nothing>
        data class Failure(val categoryName: String, val error: Throwable) : CategoryFetchOutcome<Nothing>
    }

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

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()
    private val _syncStatesByProvider = MutableStateFlow<Map<Long, SyncState>>(emptyMap())
    val syncStatesByProvider: StateFlow<Map<Long, SyncState>> = _syncStatesByProvider.asStateFlow()
    private val providerSyncMutexes = ConcurrentHashMap<Long, Mutex>()
    private val xtreamCatalogApiService: XtreamApiService by lazy {
        OkHttpXtreamApiService(
            client = okHttpClient.newBuilder()
                .connectTimeout(XTREAM_CATALOG_TIMEOUT_MINUTES, MINUTES)
                .readTimeout(XTREAM_CATALOG_TIMEOUT_MINUTES, MINUTES)
                .writeTimeout(XTREAM_CATALOG_TIMEOUT_MINUTES, MINUTES)
                .build(),
            json = xtreamJson
        )
    }

    fun syncStateForProvider(providerId: Long): Flow<SyncState> =
        syncStatesByProvider.map { states -> states[providerId] ?: SyncState.Idle }

    fun currentSyncState(providerId: Long): SyncState =
        _syncStatesByProvider.value[providerId] ?: SyncState.Idle

    private suspend fun <T> withProviderLock(providerId: Long, block: suspend () -> T): T {
        val mutex = providerSyncMutexes.computeIfAbsent(providerId) { Mutex() }
        return mutex.withLock { block() }
    }

    suspend fun sync(
        providerId: Long,
        force: Boolean = false,
        onProgress: ((String) -> Unit)? = null
    ): com.streamvault.domain.model.Result<Unit> = withProviderLock(providerId) lock@{
        val providerEntity = providerDao.getById(providerId)
            ?: return@lock com.streamvault.domain.model.Result.error("Provider $providerId not found")

        val provider = providerEntity
            .copy(password = CredentialCrypto.decryptIfNeeded(providerEntity.password))
            .toDomain()
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
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed for provider $providerId: ${redactUrlForLogs(e.message)}")
            updateSyncStatusMetadata(providerId = providerId, status = "ERROR")
            publishSyncState(providerId, SyncState.Error(e.message ?: "Unknown error", e))
            com.streamvault.domain.model.Result.error(e.message ?: "Sync failed", e)
        }
    }

    fun resetState(providerId: Long? = null) {
        if (providerId == null) {
            _syncStatesByProvider.value = emptyMap()
        } else {
            _syncStatesByProvider.update { states -> states - providerId }
        }
        _syncState.value = SyncState.Idle
    }

    suspend fun retrySection(
        providerId: Long,
        section: SyncRepairSection,
        onProgress: ((String) -> Unit)? = null
    ): com.streamvault.domain.model.Result<Unit> = withProviderLock(providerId) lock@{
        val providerEntity = providerDao.getById(providerId)
            ?: return@lock com.streamvault.domain.model.Result.error("Provider $providerId not found")

        val provider = providerEntity
            .copy(password = CredentialCrypto.decryptIfNeeded(providerEntity.password))
            .toDomain()

        try {
            withContext(Dispatchers.IO) {
                when (section) {
                    SyncRepairSection.EPG -> syncEpgOnly(provider, onProgress)
                    SyncRepairSection.MOVIES -> syncMoviesOnly(provider, onProgress)
                    SyncRepairSection.SERIES -> syncSeriesOnly(provider, onProgress)
                }
            }
            updateSyncStatusMetadata(providerId = providerId, status = "SUCCESS")
            publishSyncState(providerId, SyncState.Success())
            com.streamvault.domain.model.Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Section retry failed for provider $providerId [$section]: ${redactUrlForLogs(e.message)}")
            updateSyncStatusMetadata(providerId = providerId, status = "ERROR")
            publishSyncState(providerId, SyncState.Error(e.message ?: "Retry failed", e))
            com.streamvault.domain.model.Result.error(e.message ?: "Retry failed", e)
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
        val api = createXtreamSyncProvider(provider)

        var metadata = syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id)
        val now = System.currentTimeMillis()

        if (force || ContentCachePolicy.shouldRefresh(metadata.lastLiveSync, ContentCachePolicy.CATALOG_TTL_MILLIS, now)) {
            progress(provider.id, onProgress, "Downloading Live TV...")
            val liveSyncResult = syncXtreamLiveCatalog(
                provider = provider,
                api = api,
                onProgress = onProgress
            )

            when (val liveResult = liveSyncResult.catalogResult) {
                is CatalogStrategyResult.Success -> {
                    liveSyncResult.categories?.let { categoryDao.replaceAll(provider.id, "LIVE", it) }
                    channelDao.replaceAll(provider.id, liveResult.items.map { it.toEntity() })
                    metadata = metadata.copy(lastLiveSync = now, liveCount = liveResult.items.size)
                    warnings += liveSyncResult.warnings + liveResult.warnings
                }
                is CatalogStrategyResult.Partial -> {
                    liveSyncResult.categories?.let { categoryDao.replaceAll(provider.id, "LIVE", it) }
                    channelDao.replaceAll(provider.id, liveResult.items.map { it.toEntity() })
                    metadata = metadata.copy(lastLiveSync = now, liveCount = liveResult.items.size)
                    warnings += liveSyncResult.warnings + liveResult.warnings + listOf("Live TV sync completed partially.")
                }
                is CatalogStrategyResult.EmptyValid -> {
                    val existingChannelCount = channelDao.getCount(provider.id).first()
                    Log.w(TAG, "Live TV sync kept existing catalog for provider ${provider.id}: empty valid result, existingCount=$existingChannelCount")
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
                    warnings += liveSyncResult.warnings + liveResult.warnings +
                        if (existingChannelCount > 0) {
                            listOf("Live TV sync degraded; keeping previous channel library.")
                        } else {
                            listOf("Live TV sync failed before any usable channel catalog was available.")
                        }
                    if (existingChannelCount == 0) {
                        throw IllegalStateException("Failed to fetch live streams: ${liveResult.error.message}")
                    }
                }
            }
            syncMetadataRepository.updateMetadata(metadata)
        }

        if (force || ContentCachePolicy.shouldRefresh(metadata.lastMovieSync, ContentCachePolicy.CATALOG_TTL_MILLIS, now)) {
            progress(provider.id, onProgress, "Downloading Movies...")
            val movieSyncResult = syncXtreamMoviesCatalog(
                provider = provider,
                api = api,
                existingMetadata = metadata,
                onProgress = onProgress
            )
            val sawSequentialStress = (movieSyncResult.warnings + strategyWarnings(movieSyncResult.catalogResult))
                .any { warning -> warning.contains(MOVIE_CATEGORY_SEQUENTIAL_MODE_WARNING, ignoreCase = true) }
            val healthyMovieProviderAdaptation = updateMovieProviderAdaptation(
                previousRemembered = metadata.movieParallelFailuresRemembered,
                previousHealthyStreak = metadata.movieHealthySyncStreak,
                sawSequentialStress = sawSequentialStress
            )

            when (val catalogResult = movieSyncResult.catalogResult) {
                is CatalogStrategyResult.Success -> {
                    movieSyncResult.categories?.let { categoryDao.replaceAll(provider.id, "MOVIE", it) }
                    movieDao.replaceAll(provider.id, catalogResult.items.map { movie -> movie.toEntity() })
                    metadata = metadata.copy(
                        lastMovieSync = now,
                        lastMovieAttempt = now,
                        lastMovieSuccess = now,
                        movieCount = catalogResult.items.size,
                        movieSyncMode = movieSyncResult.syncMode,
                        movieWarningsCount = (movieSyncResult.warnings + catalogResult.warnings).size,
                        movieCatalogStale = false,
                        movieParallelFailuresRemembered = healthyMovieProviderAdaptation.rememberSequential,
                        movieHealthySyncStreak = healthyMovieProviderAdaptation.healthyStreak
                    )
                }
                is CatalogStrategyResult.Partial -> {
                    movieSyncResult.categories?.let { categoryDao.replaceAll(provider.id, "MOVIE", it) }
                    movieDao.replaceAll(provider.id, catalogResult.items.map { movie -> movie.toEntity() })
                    metadata = metadata.copy(
                        lastMovieSync = now,
                        lastMovieAttempt = now,
                        lastMoviePartial = now,
                        movieCount = catalogResult.items.size,
                        movieSyncMode = movieSyncResult.syncMode,
                        movieWarningsCount = (movieSyncResult.warnings + catalogResult.warnings).size,
                        movieCatalogStale = true,
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
                        movieSyncResult.categories?.let { categoryDao.replaceAll(provider.id, "MOVIE", it) }
                    }
                    Log.w(TAG, "Movies sync preserved previous catalog for provider ${provider.id}: strategy=${catalogResult.strategyName}, existingCount=$existingMovieCount, mode=${movieSyncResult.syncMode}, reason=${sanitizeThrowableMessage(catalogResult.error)}")
                    metadata = metadata.copy(
                        lastMovieAttempt = now,
                        movieSyncMode = finalMode,
                        movieWarningsCount = (movieSyncResult.warnings + catalogResult.warnings).size,
                        movieCatalogStale = existingMovieCount > 0 || movieSyncResult.syncMode == VodSyncMode.LAZY_BY_CATEGORY,
                        movieParallelFailuresRemembered = metadata.movieParallelFailuresRemembered || sawSequentialStress || shouldRememberSequentialPreference(catalogResult.error),
                        movieHealthySyncStreak = 0
                    )
                    warnings += movieSyncResult.warnings + catalogResult.warnings +
                        if (existingMovieCount > 0) {
                            listOf("Movies sync degraded; keeping previous movie library.")
                        } else {
                            listOf("Movies sync failed before any usable movie catalog was available.")
                        }
                    if (existingMovieCount == 0 && movieSyncResult.syncMode != VodSyncMode.LAZY_BY_CATEGORY) {
                        throw IllegalStateException("Failed to fetch movie catalog: ${catalogResult.error.message}")
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
                onProgress = onProgress
            )

            when (val seriesResult = seriesSyncResult.catalogResult) {
                is CatalogStrategyResult.Success -> {
                    seriesSyncResult.categories?.let { categoryDao.replaceAll(provider.id, "SERIES", it) }
                    seriesDao.replaceAll(provider.id, seriesResult.items.map { it.toEntity() })
                    metadata = metadata.copy(lastSeriesSync = now, seriesCount = seriesResult.items.size)
                    warnings += seriesSyncResult.warnings + seriesResult.warnings
                }
                is CatalogStrategyResult.Partial -> {
                    seriesSyncResult.categories?.let { categoryDao.replaceAll(provider.id, "SERIES", it) }
                    seriesDao.replaceAll(provider.id, seriesResult.items.map { it.toEntity() })
                    metadata = metadata.copy(lastSeriesSync = now, seriesCount = seriesResult.items.size)
                    warnings += seriesSyncResult.warnings + seriesResult.warnings + listOf("Series sync completed partially.")
                }
                is CatalogStrategyResult.EmptyValid -> {
                    val existingSeriesCount = seriesDao.getCount(provider.id).first()
                    Log.w(TAG, "Series sync kept existing catalog for provider ${provider.id}: empty valid result, existingCount=$existingSeriesCount")
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
                    Log.w(TAG, "Series sync preserved previous catalog for provider ${provider.id}: strategy=${seriesResult.strategyName}, existingCount=$existingSeriesCount, reason=${sanitizeThrowableMessage(seriesResult.error)}")
                    warnings += seriesSyncResult.warnings + seriesResult.warnings +
                        if (existingSeriesCount > 0) {
                            listOf("Series sync degraded; keeping previous series library.")
                        } else {
                            listOf("Series sync failed before any usable series catalog was available.")
                        }
                    if (existingSeriesCount == 0) {
                        throw IllegalStateException("Failed to fetch series catalog: ${seriesResult.error.message}")
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

    private suspend fun syncMoviesOnly(
        provider: Provider,
        onProgress: ((String) -> Unit)?
    ) {
        val now = System.currentTimeMillis()
        when (provider.type) {
            ProviderType.XTREAM_CODES -> {
                progress(provider.id, onProgress, "Retrying Movies...")
                val api = createXtreamSyncProvider(provider)
                val currentMetadata = syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id)
                val movieSyncResult = syncXtreamMoviesCatalog(
                    provider = provider,
                    api = api,
                    existingMetadata = currentMetadata,
                    onProgress = onProgress
                )
                val sawSequentialStress = (movieSyncResult.warnings + strategyWarnings(movieSyncResult.catalogResult))
                    .any { warning -> warning.contains(MOVIE_CATEGORY_SEQUENTIAL_MODE_WARNING, ignoreCase = true) }
                val healthyMovieProviderAdaptation = updateMovieProviderAdaptation(
                    previousRemembered = currentMetadata.movieParallelFailuresRemembered,
                    previousHealthyStreak = currentMetadata.movieHealthySyncStreak,
                    sawSequentialStress = sawSequentialStress
                )
                val movieWarnings = movieSyncResult.warnings + strategyWarnings(movieSyncResult.catalogResult)

                val metadata = currentMetadata.copy(
                    lastMovieAttempt = now,
                    movieSyncMode = movieSyncResult.syncMode,
                    movieWarningsCount = movieWarnings.size,
                    movieCatalogStale = movieSyncResult.syncMode == VodSyncMode.LAZY_BY_CATEGORY,
                    movieParallelFailuresRemembered = currentMetadata.movieParallelFailuresRemembered || sawSequentialStress
                )

                when (val catalogResult = movieSyncResult.catalogResult) {
                    is CatalogStrategyResult.Success -> {
                        movieSyncResult.categories?.let { categoryDao.replaceAll(provider.id, "MOVIE", it) }
                        movieDao.replaceAll(provider.id, catalogResult.items.map { movie -> movie.toEntity() })
                        syncMetadataRepository.updateMetadata(
                            metadata.copy(
                                lastMovieSync = now,
                                lastMovieSuccess = now,
                                movieCount = catalogResult.items.size,
                                movieCatalogStale = false,
                                movieParallelFailuresRemembered = healthyMovieProviderAdaptation.rememberSequential,
                                movieHealthySyncStreak = healthyMovieProviderAdaptation.healthyStreak
                            )
                        )
                    }
                    is CatalogStrategyResult.Partial -> {
                        movieSyncResult.categories?.let { categoryDao.replaceAll(provider.id, "MOVIE", it) }
                        movieDao.replaceAll(provider.id, catalogResult.items.map { movie -> movie.toEntity() })
                        syncMetadataRepository.updateMetadata(
                            metadata.copy(
                                lastMovieSync = now,
                                lastMoviePartial = now,
                                movieCount = catalogResult.items.size,
                                movieCatalogStale = true,
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
                            movieSyncResult.categories?.let { categoryDao.replaceAll(provider.id, "MOVIE", it) }
                        }
                        syncMetadataRepository.updateMetadata(
                            metadata.copy(
                                movieCatalogStale = existingMovieCount > 0 || movieSyncResult.syncMode == VodSyncMode.LAZY_BY_CATEGORY,
                                movieParallelFailuresRemembered = currentMetadata.movieParallelFailuresRemembered || sawSequentialStress || shouldRememberSequentialPreference(catalogResult.error),
                                movieHealthySyncStreak = 0
                            )
                        )
                        throw IllegalStateException(catalogResult.error.message ?: "Failed to fetch VOD streams", catalogResult.error)
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
        val api = createXtreamSyncProvider(provider)
        val seriesSyncResult = syncXtreamSeriesCatalog(
            provider = provider,
            api = api,
            onProgress = onProgress
        )

        val now = System.currentTimeMillis()
        val currentMetadata = syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id)
        when (val seriesResult = seriesSyncResult.catalogResult) {
            is CatalogStrategyResult.Success -> {
                seriesSyncResult.categories?.let { categoryDao.replaceAll(provider.id, "SERIES", it) }
                seriesDao.replaceAll(provider.id, seriesResult.items.map { it.toEntity() })
                syncMetadataRepository.updateMetadata(
                    currentMetadata.copy(lastSeriesSync = now, seriesCount = seriesResult.items.size)
                )
            }
            is CatalogStrategyResult.Partial -> {
                seriesSyncResult.categories?.let { categoryDao.replaceAll(provider.id, "SERIES", it) }
                seriesDao.replaceAll(provider.id, seriesResult.items.map { it.toEntity() })
                syncMetadataRepository.updateMetadata(
                    currentMetadata.copy(lastSeriesSync = now, seriesCount = seriesResult.items.size)
                )
            }
            is CatalogStrategyResult.EmptyValid -> {
                val existingSeriesCount = seriesDao.getCount(provider.id).first()
                throw IllegalStateException(
                    if (existingSeriesCount > 0) {
                        "Series refresh returned an empty catalog; existing library was preserved."
                    } else {
                        "Series catalog was empty."
                    }
                )
            }
            is CatalogStrategyResult.Failure -> {
                throw IllegalStateException(seriesResult.error.message ?: "Failed to fetch series list", seriesResult.error)
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
        val rawVodCategories = fetchXtreamVodCategories(provider)
        val resolvedCategories = rawVodCategories
            ?.let { categories -> api.mapCategories(ContentType.MOVIE, categories) }
            ?.map { category -> category.toEntity(provider.id) }
            ?.takeIf { it.isNotEmpty() }

        val fullResult = loadXtreamMoviesFull(provider, api)
        when (fullResult) {
            is CatalogStrategyResult.Success -> return MovieCatalogSyncResult(
                catalogResult = fullResult,
                categories = resolvedCategories ?: buildFallbackMovieCategories(provider.id, fullResult.items),
                syncMode = VodSyncMode.FULL
            ).also {
                Log.i(TAG, "Xtream movies strategy selected FULL for provider ${provider.id} with ${fullResult.items.size} items.")
            }
            is CatalogStrategyResult.Partial -> return MovieCatalogSyncResult(
                catalogResult = fullResult,
                categories = resolvedCategories ?: buildFallbackMovieCategories(provider.id, fullResult.items),
                syncMode = VodSyncMode.FULL,
                warnings = emptyList()
            ).also {
                Log.w(TAG, "Xtream movies strategy selected FULL(partial) for provider ${provider.id} with ${fullResult.items.size} items.")
            }
            else -> Unit
        }

        val categoryResult = loadXtreamMoviesByCategory(
            provider = provider,
            api = api,
            rawCategories = rawVodCategories.orEmpty(),
            onProgress = onProgress,
            preferSequential = existingMetadata.movieParallelFailuresRemembered
        )
        when (categoryResult) {
            is CatalogStrategyResult.Success -> return MovieCatalogSyncResult(
                catalogResult = categoryResult,
                categories = resolvedCategories ?: buildFallbackMovieCategories(provider.id, categoryResult.items),
                syncMode = VodSyncMode.CATEGORY_BULK,
                warnings = strategyWarnings(fullResult)
            ).also {
                Log.i(TAG, "Xtream movies strategy selected CATEGORY_BULK for provider ${provider.id} with ${categoryResult.items.size} items.")
            }
            is CatalogStrategyResult.Partial -> return MovieCatalogSyncResult(
                catalogResult = categoryResult,
                categories = resolvedCategories ?: buildFallbackMovieCategories(provider.id, categoryResult.items),
                syncMode = VodSyncMode.CATEGORY_BULK,
                warnings = strategyWarnings(fullResult)
            ).also {
                Log.w(TAG, "Xtream movies strategy selected CATEGORY_BULK(partial) for provider ${provider.id} with ${categoryResult.items.size} items.")
            }
            else -> Unit
        }

        val pagedResult = loadXtreamMoviesByPage(provider, api, onProgress)
        when (pagedResult) {
            is CatalogStrategyResult.Success -> return MovieCatalogSyncResult(
                catalogResult = pagedResult,
                categories = resolvedCategories ?: buildFallbackMovieCategories(provider.id, pagedResult.items),
                syncMode = VodSyncMode.PAGED,
                warnings = strategyWarnings(fullResult) + strategyWarnings(categoryResult)
            ).also {
                Log.i(TAG, "Xtream movies strategy selected PAGED for provider ${provider.id} with ${pagedResult.items.size} items.")
            }
            is CatalogStrategyResult.Partial -> return MovieCatalogSyncResult(
                catalogResult = pagedResult,
                categories = resolvedCategories ?: buildFallbackMovieCategories(provider.id, pagedResult.items),
                syncMode = VodSyncMode.PAGED,
                warnings = strategyWarnings(fullResult) + strategyWarnings(categoryResult)
            ).also {
                Log.w(TAG, "Xtream movies strategy selected PAGED(partial) for provider ${provider.id} with ${pagedResult.items.size} items.")
            }
            else -> Unit
        }

        val lazyWarnings = buildList {
            addAll(strategyWarnings(fullResult))
            addAll(strategyWarnings(categoryResult))
            addAll(strategyWarnings(pagedResult))
            add("Movies entered lazy category-only mode after full, category-bulk, and paged strategies failed.")
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
                warnings = lazyWarnings
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
                warnings = lazyWarnings
            )
        }
    }

    private suspend fun loadXtreamMoviesFull(
        provider: Provider,
        api: XtreamProvider
    ): CatalogStrategyResult<Movie> {
        var fullMovies: List<Movie>? = null
        var fullMoviesFailure: Throwable? = null
        val fullMoviesElapsedMs = measureTimeMillis {
            runCatching {
                retryXtreamCatalogTransient { api.getVodStreams().getOrThrow("VOD streams") }
            }.onSuccess { movies ->
                fullMovies = movies
            }.onFailure { error ->
                fullMoviesFailure = error
            }
        }
        if (!fullMovies.isNullOrEmpty()) {
            Log.i(
                TAG,
                "Xtream movies full catalog succeeded for provider ${provider.id} in ${fullMoviesElapsedMs}ms with ${fullMovies!!.size} items."
            )
            return CatalogStrategyResult.Success(
                strategyName = "full",
                items = fullMovies!!
            )
        }
        return if (fullMoviesFailure != null) {
            logXtreamCatalogFallback(
                provider = provider,
                section = "movies",
                stage = "full catalog",
                elapsedMs = fullMoviesElapsedMs,
                itemCount = fullMovies?.size,
                error = fullMoviesFailure,
                nextStep = "category-bulk"
            )
            CatalogStrategyResult.Failure(
                strategyName = "full",
                error = fullMoviesFailure!!,
                warnings = listOf("Movies full catalog request failed: ${sanitizeThrowableMessage(fullMoviesFailure)}")
            )
        } else {
            logXtreamCatalogFallback(
                provider = provider,
                section = "movies",
                stage = "full catalog",
                elapsedMs = fullMoviesElapsedMs,
                itemCount = fullMovies?.size,
                error = null,
                nextStep = "category-bulk"
            )
            CatalogStrategyResult.EmptyValid(
                strategyName = "full",
                warnings = listOf("Movies full catalog returned an empty valid result.")
            )
        }
    }

    private suspend fun syncXtreamLiveCatalog(
        provider: Provider,
        api: XtreamProvider,
        onProgress: ((String) -> Unit)?
    ): CatalogSyncPayload<Channel> {
        Log.i(TAG, "Xtream live strategy start for provider ${provider.id}.")
        val rawLiveCategories = runCatching {
            retryTransient {
                xtreamCatalogApiService.getLiveCategories(
                    XtreamUrlFactory.buildPlayerApiUrl(
                        serverUrl = provider.serverUrl,
                        username = provider.username,
                        password = provider.password,
                        action = "get_live_categories"
                    )
                )
            }
        }.onFailure { error ->
            Log.w(TAG, "Xtream live categories request failed for provider ${provider.id}: ${sanitizeThrowableMessage(error)}")
        }.getOrNull()
        val resolvedCategories = rawLiveCategories
            ?.let { categories -> api.mapCategories(ContentType.LIVE, categories) }
            ?.map { category -> category.toEntity(provider.id) }
            ?.takeIf { it.isNotEmpty() }

        val fullResult = loadXtreamLiveFull(provider, api)
        when (fullResult) {
            is CatalogStrategyResult.Success -> return CatalogSyncPayload(
                catalogResult = fullResult,
                categories = resolvedCategories ?: buildFallbackLiveCategories(provider.id, fullResult.items)
            )
            is CatalogStrategyResult.Partial -> return CatalogSyncPayload(
                catalogResult = fullResult,
                categories = resolvedCategories ?: buildFallbackLiveCategories(provider.id, fullResult.items),
                warnings = emptyList()
            )
            else -> Unit
        }

        val categoryResult = loadXtreamLiveByCategory(provider, api, rawLiveCategories.orEmpty(), onProgress, preferSequential = false)
        return CatalogSyncPayload(
            catalogResult = categoryResult,
            categories = when (categoryResult) {
                is CatalogStrategyResult.Success -> resolvedCategories ?: buildFallbackLiveCategories(provider.id, categoryResult.items)
                is CatalogStrategyResult.Partial -> resolvedCategories ?: buildFallbackLiveCategories(provider.id, categoryResult.items)
                else -> null
            },
            warnings = strategyWarnings(fullResult)
        )
    }

    private suspend fun loadXtreamLiveFull(
        provider: Provider,
        api: XtreamProvider
    ): CatalogStrategyResult<Channel> {
        var fullChannels: List<Channel>? = null
        var fullChannelsFailure: Throwable? = null
        val fullChannelsElapsedMs = measureTimeMillis {
            runCatching {
                retryXtreamCatalogTransient { api.getLiveStreams().getOrThrow("Live streams") }
            }.onSuccess { channels ->
                fullChannels = channels
            }.onFailure { error ->
                fullChannelsFailure = error
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
                warnings = listOf("Live full catalog request failed: ${sanitizeThrowableMessage(fullChannelsFailure)}")
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

        val concurrency = if (preferSequential) 1 else XTREAM_LIVE_CATEGORY_SYNC_MAX_CONCURRENCY
        val semaphore = Semaphore(concurrency)
        val progressCounter = java.util.concurrent.atomic.AtomicInteger(0)
        val failureCount = java.util.concurrent.atomic.AtomicInteger(0)
        val fastFailureCount = java.util.concurrent.atomic.AtomicInteger(0)
        val warnings = mutableListOf<String>()
        val warningsMutex = Mutex()
        progress(provider.id, onProgress, "Downloading Live TV 0/${categories.size}...")

        val categoryOutcomes = coroutineScope {
            categories.map { category ->
                async {
                    semaphore.withPermit {
                        rateLimitXtreamCatalogRequest()
                        var rawStreams: List<XtreamStream> = emptyList()
                        var categoryFailure: Throwable? = null
                        val elapsedMs = measureTimeMillis {
                            runCatching {
                                retryXtreamCatalogTransient {
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
                            }.onSuccess { streams ->
                                rawStreams = streams
                            }.onFailure { error ->
                                categoryFailure = error
                            }
                        }

                        val completed = progressCounter.incrementAndGet()
                        progress(provider.id, onProgress, "Downloading Live TV $completed/${categories.size}...")

                        when {
                            categoryFailure != null -> {
                                failureCount.incrementAndGet()
                                if (elapsedMs <= 5_000L) fastFailureCount.incrementAndGet()
                                val failure = categoryFailure!!
                                warningsMutex.withLock {
                                    warnings += "Live category '${category.categoryName}' failed: ${sanitizeThrowableMessage(failure)}"
                                }
                                Log.w(TAG, "Xtream live category '${category.categoryName}' failed after ${elapsedMs}ms: ${sanitizeThrowableMessage(failure)}")
                                CategoryFetchOutcome.Failure(category.categoryName, failure)
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
                    }
                }
            }.awaitAll()
        }

        if (concurrency > 1 && shouldDowngradeCategorySync(categories.size, failureCount.get(), fastFailureCount.get(), categoryOutcomes)) {
            Log.w(TAG, "Xtream live category sync is downgrading to sequential mode for provider ${provider.id}.")
            return when (val sequentialResult = loadXtreamLiveByCategory(provider, api, rawCategories, onProgress, preferSequential = true)) {
                is CatalogStrategyResult.Success -> sequentialResult.copy(warnings = sequentialResult.warnings + LIVE_CATEGORY_SEQUENTIAL_MODE_WARNING)
                is CatalogStrategyResult.Partial -> sequentialResult.copy(warnings = sequentialResult.warnings + LIVE_CATEGORY_SEQUENTIAL_MODE_WARNING)
                is CatalogStrategyResult.Failure -> sequentialResult.copy(warnings = sequentialResult.warnings + LIVE_CATEGORY_SEQUENTIAL_MODE_WARNING)
                is CatalogStrategyResult.EmptyValid -> sequentialResult.copy(warnings = sequentialResult.warnings + LIVE_CATEGORY_SEQUENTIAL_MODE_WARNING)
            }
        }

        val channels = categoryOutcomes
            .asSequence()
            .filterIsInstance<CategoryFetchOutcome.Success<Channel>>()
            .flatMap { it.items.asSequence() }
            .filter { it.streamId > 0L }
            .associateBy { it.streamId }
            .values
            .toList()
        val failedCategories = categoryOutcomes.count { it is CategoryFetchOutcome.Failure }
        val emptyCategories = categoryOutcomes.count { it is CategoryFetchOutcome.Empty }
        val successfulCategories = categoryOutcomes.count { it is CategoryFetchOutcome.Success }
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
    ): CatalogStrategyResult<Movie> {
        val categories = rawCategories.filter { it.categoryId.isNotBlank() }
        if (categories.isEmpty()) {
            return CatalogStrategyResult.Failure(
                strategyName = "category_bulk",
                error = IllegalStateException("No VOD categories available"),
                warnings = listOf("Movies category-bulk strategy was unavailable because no categories were returned.")
            )
        }

        val concurrency = if (preferSequential) 1 else xtreamCategorySyncConcurrency(categories.size)
        val semaphore = Semaphore(concurrency)
        val progressCounter = java.util.concurrent.atomic.AtomicInteger(0)
        val failureCount = java.util.concurrent.atomic.AtomicInteger(0)
        val fastFailureCount = java.util.concurrent.atomic.AtomicInteger(0)
        val warnings = mutableListOf<String>()
        val warningsMutex = Mutex()
        progress(provider.id, onProgress, "Downloading Movies 0/${categories.size}...")

        val categoryOutcomes = coroutineScope {
            categories.map { category ->
                async {
                    semaphore.withPermit {
                        rateLimitXtreamCatalogRequest()
                        var rawStreams: List<XtreamStream> = emptyList()
                        var categoryFailure: Throwable? = null
                        val elapsedMs = measureTimeMillis {
                            runCatching {
                                retryXtreamCatalogTransient {
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
                            }.onSuccess { streams ->
                                rawStreams = streams
                            }.onFailure { error ->
                                categoryFailure = error
                            }
                        }

                        val completed = progressCounter.incrementAndGet()
                        progress(provider.id, onProgress, "Downloading Movies $completed/${categories.size}...")

                        when {
                            categoryFailure != null -> {
                                failureCount.incrementAndGet()
                                if (elapsedMs <= 5_000L) fastFailureCount.incrementAndGet()
                                val failure = categoryFailure!!
                                warningsMutex.withLock {
                                    warnings += "Category '${category.categoryName}' failed: ${failure.message}"
                                }
                                Log.w(
                                    TAG,
                                    "Xtream movie category '${category.categoryName}' failed after ${elapsedMs}ms: ${failure::class.java.simpleName}: ${failure.message}"
                                )
                                CategoryFetchOutcome.Failure(category.categoryName, failure)
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
                    }
                }
            }.awaitAll()
        }

        if (concurrency > 1 && shouldDowngradeCategorySync(
                totalCategories = categories.size,
                failures = failureCount.get(),
                fastFailures = fastFailureCount.get(),
                outcomes = categoryOutcomes
            )
        ) {
            val downgradeReasons = buildList {
                add("failures=${failureCount.get()}/${categories.size}")
                if (fastFailureCount.get() > 0) add("fastFailures=${fastFailureCount.get()}")
                val providerStressSignals = categoryOutcomes.count { outcome ->
                    outcome is CategoryFetchOutcome.Failure && shouldRememberSequentialPreference(outcome.error)
                }
                if (providerStressSignals > 0) add("providerStressSignals=$providerStressSignals")
            }.joinToString(", ")
            Log.w(
                TAG,
                "Xtream movie category sync is downgrading to sequential mode for provider ${provider.id}: $downgradeReasons"
            )
            return when (val sequentialResult = loadXtreamMoviesByCategory(
                provider = provider,
                api = api,
                rawCategories = rawCategories,
                onProgress = onProgress,
                preferSequential = true
            )) {
                is CatalogStrategyResult.Success -> sequentialResult.copy(
                    warnings = sequentialResult.warnings + MOVIE_CATEGORY_SEQUENTIAL_MODE_WARNING
                )
                is CatalogStrategyResult.Partial -> sequentialResult.copy(
                    warnings = sequentialResult.warnings + MOVIE_CATEGORY_SEQUENTIAL_MODE_WARNING
                )
                is CatalogStrategyResult.Failure -> sequentialResult.copy(
                    warnings = sequentialResult.warnings + MOVIE_CATEGORY_SEQUENTIAL_MODE_WARNING
                )
                is CatalogStrategyResult.EmptyValid -> sequentialResult.copy(
                    warnings = sequentialResult.warnings + MOVIE_CATEGORY_SEQUENTIAL_MODE_WARNING
                )
            }
        }

        val movies = categoryOutcomes
            .asSequence()
            .filterIsInstance<CategoryFetchOutcome.Success<Movie>>()
            .flatMap { it.items.asSequence() }
            .filter { it.streamId > 0L }
            .associateBy { it.streamId }
            .values
            .toList()

        val failedCategories = categoryOutcomes.count { it is CategoryFetchOutcome.Failure }
        val successfulCategories = categoryOutcomes.count { it is CategoryFetchOutcome.Success }
        val emptyCategories = categoryOutcomes.count { it is CategoryFetchOutcome.Empty }
        Log.i(
            TAG,
            "Xtream movie category strategy summary for provider ${provider.id}: successful=$successfulCategories empty=$emptyCategories failed=$failedCategories dedupedMovies=${movies.size} concurrency=$concurrency"
        )
        return when {
            movies.isNotEmpty() && failedCategories == 0 -> CatalogStrategyResult.Success(
                strategyName = "category_bulk",
                items = movies,
                warnings = warnings.toList()
            )
            movies.isNotEmpty() -> CatalogStrategyResult.Partial(
                strategyName = "category_bulk",
                items = movies,
                warnings = warnings.toList()
            )
            failedCategories > 0 -> CatalogStrategyResult.Failure(
                strategyName = "category_bulk",
                error = IllegalStateException("Movie category-bulk sync failed for all usable categories"),
                warnings = warnings.toList()
            )
            else -> CatalogStrategyResult.EmptyValid(
                strategyName = "category_bulk",
                warnings = listOf("All movie categories returned valid empty results.")
            )
        }
    }

    private suspend fun loadXtreamMoviesByPage(
        provider: Provider,
        api: XtreamProvider,
        onProgress: ((String) -> Unit)?
    ): CatalogStrategyResult<Movie> {
        val moviesByStreamId = LinkedHashMap<Long, Movie>()
        val warnings = mutableListOf<String>()
        var sawValidPage = false
        for (page in 1..XTREAM_CATALOG_MAX_PAGES) {
            progress(provider.id, onProgress, "Downloading Movies page $page...")
            rateLimitXtreamCatalogRequest()
            var rawStreams: List<XtreamStream> = emptyList()
            var pageFailure: Throwable? = null
            val pageElapsedMs = measureTimeMillis {
                runCatching {
                    retryXtreamCatalogTransient {
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
                }.onSuccess { streams ->
                    rawStreams = streams
                }.onFailure { error ->
                    pageFailure = error
                }
            }

            if (pageFailure != null) {
                val failure = pageFailure!!
                Log.w(
                    TAG,
                    "Xtream paged movie request failed for provider ${provider.id} on page $page after ${pageElapsedMs}ms: ${failure::class.java.simpleName}: ${failure.message}"
                )
                return if (page == 1 || moviesByStreamId.isEmpty()) {
                    CatalogStrategyResult.Failure(
                        strategyName = "paged",
                        error = failure,
                        warnings = listOf("Movie paging failed on the first page: ${failure.message}")
                    )
                } else {
                    warnings += "Movie paging failed on page $page: ${failure.message}"
                    CatalogStrategyResult.Partial(
                        strategyName = "paged",
                        items = moviesByStreamId.values.toList(),
                        warnings = warnings.toList()
                    )
                }
            }

            Log.i(
                TAG,
                "Xtream paged movie request for provider ${provider.id} page $page completed in ${pageElapsedMs}ms with ${rawStreams.size} raw items."
            )

            if (rawStreams.isEmpty()) {
                return when {
                    moviesByStreamId.isNotEmpty() -> CatalogStrategyResult.Success(
                        strategyName = "paged",
                        items = moviesByStreamId.values.toList(),
                        warnings = warnings.toList()
                    )
                    sawValidPage -> CatalogStrategyResult.EmptyValid(
                        strategyName = "paged",
                        warnings = listOf("Paged movie catalog returned no items.")
                    )
                    else -> CatalogStrategyResult.EmptyValid(
                        strategyName = "paged",
                        warnings = listOf("Paged movie catalog is unsupported or empty on this provider.")
                    )
                }
            }

            sawValidPage = true
            val mappedMovies = api.mapVodStreamsResponse(rawStreams)
            val beforeCount = moviesByStreamId.size
            mappedMovies.forEach { movie ->
                if (movie.streamId > 0L) {
                    moviesByStreamId[movie.streamId] = movie
                }
            }
            val newItems = moviesByStreamId.size - beforeCount
            if (rawStreams.size < XTREAM_CATALOG_PAGE_SIZE || newItems == 0) {
                break
            }
        }

        return moviesByStreamId.values.toList()
            .takeIf { it.isNotEmpty() }
            ?.let { items ->
                Log.i(TAG, "Xtream paged movie strategy completed for provider ${provider.id} with ${items.size} deduped items.")
                CatalogStrategyResult.Success("paged", items, warnings.toList())
            }
            ?: CatalogStrategyResult.EmptyValid(
                strategyName = "paged",
                warnings = listOf("Paged movie catalog completed without items.")
            )
    }

    private suspend fun syncXtreamSeriesCatalog(
        provider: Provider,
        api: XtreamProvider,
        onProgress: ((String) -> Unit)?
    ): CatalogSyncPayload<Series> {
        Log.i(TAG, "Xtream series strategy start for provider ${provider.id}.")
        val rawSeriesCategories = runCatching {
            retryTransient {
                xtreamCatalogApiService.getSeriesCategories(
                    XtreamUrlFactory.buildPlayerApiUrl(
                        serverUrl = provider.serverUrl,
                        username = provider.username,
                        password = provider.password,
                        action = "get_series_categories"
                    )
                )
            }
        }.onFailure { error ->
            Log.w(TAG, "Xtream series categories request failed for provider ${provider.id}: ${sanitizeThrowableMessage(error)}")
        }.getOrNull()
        val resolvedCategories = rawSeriesCategories
            ?.let { categories -> api.mapCategories(ContentType.SERIES, categories) }
            ?.map { category -> category.toEntity(provider.id) }
            ?.takeIf { it.isNotEmpty() }

        val fullResult = loadXtreamSeriesFull(provider, api)
        when (fullResult) {
            is CatalogStrategyResult.Success -> return CatalogSyncPayload(
                catalogResult = fullResult,
                categories = resolvedCategories ?: buildFallbackSeriesCategories(provider.id, fullResult.items)
            )
            is CatalogStrategyResult.Partial -> return CatalogSyncPayload(
                catalogResult = fullResult,
                categories = resolvedCategories ?: buildFallbackSeriesCategories(provider.id, fullResult.items),
                warnings = emptyList()
            )
            else -> Unit
        }

        val categoryResult = loadXtreamSeriesByCategory(provider, api, rawSeriesCategories.orEmpty(), onProgress, preferSequential = false)
        when (categoryResult) {
            is CatalogStrategyResult.Success -> return CatalogSyncPayload(
                catalogResult = categoryResult,
                categories = resolvedCategories ?: buildFallbackSeriesCategories(provider.id, categoryResult.items),
                warnings = strategyWarnings(fullResult)
            )
            is CatalogStrategyResult.Partial -> return CatalogSyncPayload(
                catalogResult = categoryResult,
                categories = resolvedCategories ?: buildFallbackSeriesCategories(provider.id, categoryResult.items),
                warnings = strategyWarnings(fullResult)
            )
            else -> Unit
        }

        val pagedResult = loadXtreamSeriesByPage(provider, api, onProgress)
        return CatalogSyncPayload(
            catalogResult = pagedResult,
            categories = when (pagedResult) {
                is CatalogStrategyResult.Success -> resolvedCategories ?: buildFallbackSeriesCategories(provider.id, pagedResult.items)
                is CatalogStrategyResult.Partial -> resolvedCategories ?: buildFallbackSeriesCategories(provider.id, pagedResult.items)
                else -> null
            },
            warnings = strategyWarnings(fullResult) + strategyWarnings(categoryResult)
        )
    }

    private suspend fun loadXtreamSeriesFull(
        provider: Provider,
        api: XtreamProvider
    ): CatalogStrategyResult<Series> {
        var fullSeries: List<Series>? = null
        var fullSeriesFailure: Throwable? = null
        val fullSeriesElapsedMs = measureTimeMillis {
            runCatching {
                retryXtreamCatalogTransient { api.getSeriesList().getOrThrow("Series list") }
            }.onSuccess { series ->
                fullSeries = series
            }.onFailure { error ->
                fullSeriesFailure = error
            }
        }
        if (!fullSeries.isNullOrEmpty()) {
            Log.i(TAG, "Xtream series full catalog succeeded for provider ${provider.id} in ${fullSeriesElapsedMs}ms with ${fullSeries!!.size} items.")
            return CatalogStrategyResult.Success("full", fullSeries!!)
        }
        return if (fullSeriesFailure != null) {
            logXtreamCatalogFallback(
                provider = provider,
                section = "series",
                stage = "full catalog",
                elapsedMs = fullSeriesElapsedMs,
                itemCount = fullSeries?.size,
                error = fullSeriesFailure,
                nextStep = "category-bulk"
            )
            CatalogStrategyResult.Failure(
                strategyName = "full",
                error = fullSeriesFailure!!,
                warnings = listOf("Series full catalog request failed: ${sanitizeThrowableMessage(fullSeriesFailure)}")
            )
        } else {
            logXtreamCatalogFallback(
                provider = provider,
                section = "series",
                stage = "full catalog",
                elapsedMs = fullSeriesElapsedMs,
                itemCount = fullSeries?.size,
                error = null,
                nextStep = "category-bulk"
            )
            CatalogStrategyResult.EmptyValid(
                strategyName = "full",
                warnings = listOf("Series full catalog returned an empty valid result.")
            )
        }
    }

    private suspend fun loadXtreamSeriesByCategory(
        provider: Provider,
        api: XtreamProvider,
        rawCategories: List<XtreamCategory>,
        onProgress: ((String) -> Unit)?,
        preferSequential: Boolean
    ): CatalogStrategyResult<Series> {
        val categories = rawCategories.filter { it.categoryId.isNotBlank() }
        if (categories.isEmpty()) {
            return CatalogStrategyResult.Failure(
                strategyName = "category_bulk",
                error = IllegalStateException("No series categories available"),
                warnings = listOf("Series category-bulk strategy was unavailable because no categories were returned.")
            )
        }

        val concurrency = if (preferSequential) 1 else xtreamCategorySyncConcurrency(categories.size)
        val semaphore = Semaphore(concurrency)
        val progressCounter = java.util.concurrent.atomic.AtomicInteger(0)
        val failureCount = java.util.concurrent.atomic.AtomicInteger(0)
        val fastFailureCount = java.util.concurrent.atomic.AtomicInteger(0)
        val warnings = mutableListOf<String>()
        val warningsMutex = Mutex()
        progress(provider.id, onProgress, "Downloading Series 0/${categories.size}...")

        val categoryOutcomes = coroutineScope {
            categories.map { category ->
                async {
                    semaphore.withPermit {
                        rateLimitXtreamCatalogRequest()
                        var rawSeries: List<XtreamSeriesItem> = emptyList()
                        var categoryFailure: Throwable? = null
                        val elapsedMs = measureTimeMillis {
                            runCatching {
                                retryXtreamCatalogTransient {
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
                            }.onSuccess { series ->
                                rawSeries = series
                            }.onFailure { error ->
                                categoryFailure = error
                            }
                        }

                        val completed = progressCounter.incrementAndGet()
                        progress(provider.id, onProgress, "Downloading Series $completed/${categories.size}...")

                        when {
                            categoryFailure != null -> {
                                failureCount.incrementAndGet()
                                if (elapsedMs <= 5_000L) fastFailureCount.incrementAndGet()
                                val failure = categoryFailure!!
                                warningsMutex.withLock {
                                    warnings += "Series category '${category.categoryName}' failed: ${sanitizeThrowableMessage(failure)}"
                                }
                                Log.w(TAG, "Xtream series category '${category.categoryName}' failed after ${elapsedMs}ms: ${sanitizeThrowableMessage(failure)}")
                                CategoryFetchOutcome.Failure(category.categoryName, failure)
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
                    }
                }
            }.awaitAll()
        }

        if (concurrency > 1 && shouldDowngradeCategorySync(categories.size, failureCount.get(), fastFailureCount.get(), categoryOutcomes)) {
            Log.w(TAG, "Xtream series category sync is downgrading to sequential mode for provider ${provider.id}.")
            return when (val sequentialResult = loadXtreamSeriesByCategory(provider, api, rawCategories, onProgress, preferSequential = true)) {
                is CatalogStrategyResult.Success -> sequentialResult.copy(warnings = sequentialResult.warnings + SERIES_CATEGORY_SEQUENTIAL_MODE_WARNING)
                is CatalogStrategyResult.Partial -> sequentialResult.copy(warnings = sequentialResult.warnings + SERIES_CATEGORY_SEQUENTIAL_MODE_WARNING)
                is CatalogStrategyResult.Failure -> sequentialResult.copy(warnings = sequentialResult.warnings + SERIES_CATEGORY_SEQUENTIAL_MODE_WARNING)
                is CatalogStrategyResult.EmptyValid -> sequentialResult.copy(warnings = sequentialResult.warnings + SERIES_CATEGORY_SEQUENTIAL_MODE_WARNING)
            }
        }

        val series = categoryOutcomes
            .asSequence()
            .filterIsInstance<CategoryFetchOutcome.Success<Series>>()
            .flatMap { it.items.asSequence() }
            .filter { it.seriesId > 0L }
            .associateBy { it.seriesId }
            .values
            .toList()
        val failedCategories = categoryOutcomes.count { it is CategoryFetchOutcome.Failure }
        val emptyCategories = categoryOutcomes.count { it is CategoryFetchOutcome.Empty }
        val successfulCategories = categoryOutcomes.count { it is CategoryFetchOutcome.Success }
        Log.i(TAG, "Xtream series category strategy summary for provider ${provider.id}: successful=$successfulCategories empty=$emptyCategories failed=$failedCategories dedupedSeries=${series.size} concurrency=$concurrency")

        return when {
            series.isNotEmpty() && failedCategories == 0 -> CatalogStrategyResult.Success("category_bulk", series, warnings.toList())
            series.isNotEmpty() -> CatalogStrategyResult.Partial("category_bulk", series, warnings.toList())
            failedCategories > 0 -> CatalogStrategyResult.Failure(
                strategyName = "category_bulk",
                error = IllegalStateException("Series category-bulk sync failed for all usable categories"),
                warnings = warnings.toList()
            )
            else -> CatalogStrategyResult.EmptyValid(
                strategyName = "category_bulk",
                warnings = listOf("All series categories returned valid empty results.")
            )
        }
    }

    private suspend fun loadXtreamSeriesByPage(
        provider: Provider,
        api: XtreamProvider,
        onProgress: ((String) -> Unit)?
    ): CatalogStrategyResult<Series> {
        val seriesById = LinkedHashMap<Long, Series>()
        val warnings = mutableListOf<String>()
        var sawValidPage = false
        for (page in 1..XTREAM_CATALOG_MAX_PAGES) {
            progress(provider.id, onProgress, "Downloading Series page $page...")
            rateLimitXtreamCatalogRequest()
            var rawSeries: List<XtreamSeriesItem> = emptyList()
            var pageFailure: Throwable? = null
            val pageElapsedMs = measureTimeMillis {
                runCatching {
                    retryXtreamCatalogTransient {
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
                }.onSuccess { series ->
                    rawSeries = series
                }.onFailure { error ->
                    pageFailure = error
                }
            }

            if (pageFailure != null) {
                val failure = pageFailure!!
                Log.w(TAG, "Xtream paged series request failed for provider ${provider.id} on page $page after ${pageElapsedMs}ms: ${sanitizeThrowableMessage(failure)}")
                return if (page == 1 || seriesById.isEmpty()) {
                    CatalogStrategyResult.Failure(
                        strategyName = "paged",
                        error = failure,
                        warnings = listOf("Series paging failed on the first page: ${sanitizeThrowableMessage(failure)}")
                    )
                } else {
                    warnings += "Series paging failed on page $page: ${sanitizeThrowableMessage(failure)}"
                    CatalogStrategyResult.Partial(
                        strategyName = "paged",
                        items = seriesById.values.toList(),
                        warnings = warnings.toList()
                    )
                }
            }

            Log.i(TAG, "Xtream paged series request for provider ${provider.id} page $page completed in ${pageElapsedMs}ms with ${rawSeries.size} raw items.")
            if (rawSeries.isEmpty()) {
                return when {
                    seriesById.isNotEmpty() -> CatalogStrategyResult.Success("paged", seriesById.values.toList(), warnings.toList())
                    sawValidPage -> CatalogStrategyResult.EmptyValid("paged", listOf("Paged series catalog returned no items."))
                    else -> CatalogStrategyResult.EmptyValid("paged", listOf("Paged series catalog is unsupported or empty on this provider."))
                }
            }

            sawValidPage = true
            val mappedSeries = api.mapSeriesListResponse(rawSeries)
            val beforeCount = seriesById.size
            mappedSeries.forEach { item ->
                if (item.seriesId > 0L) {
                    seriesById[item.seriesId] = item
                }
            }
            val newItems = seriesById.size - beforeCount
            if (rawSeries.size < XTREAM_CATALOG_PAGE_SIZE || newItems == 0) {
                break
            }
        }

        return seriesById.values.toList()
            .takeIf { it.isNotEmpty() }
            ?.let { items -> CatalogStrategyResult.Success("paged", items, warnings.toList()) }
            ?: CatalogStrategyResult.EmptyValid(
                strategyName = "paged",
                warnings = listOf("Paged series catalog completed without items.")
            )
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
        val existingChannelIds = if (includeLive) channelDao.getIdMappings(provider.id).associate { it.remoteId to it.id } else emptyMap()
        val existingMovieIds = if (includeMovies) movieDao.getIdMappings(provider.id).associate { it.remoteId to it.id } else emptyMap()
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
                        val stableStreamId = stableId(provider.id, entry.tvgId, entry.url, stableLongHasher)
                        val categoryId = movieCategories.idFor(groupTitle)
                        val isAdult = AdultContentClassifier.isAdultCategoryName(groupTitle)
                        seenMovieStreamIds?.add(stableStreamId)
                        movieBatch.add(
                            MovieEntity(
                                id = existingMovieIds[stableStreamId] ?: 0L,
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
                            flushMovieBatch(movieBatch)
                        }
                    } else {
                        if (!includeLive) {
                            return@parseStreaming
                        }
                        val groupTitle = entry.groupTitle.ifBlank { "Uncategorized" }
                        val stableStreamId = stableId(provider.id, entry.tvgId, entry.url, stableLongHasher)
                        val categoryId = liveCategories.idFor(groupTitle)
                        val isAdult = AdultContentClassifier.isAdultCategoryName(groupTitle)
                        seenLiveStreamIds?.add(stableStreamId)
                        channelBatch.add(
                            ChannelEntity(
                                id = existingChannelIds[stableStreamId] ?: 0L,
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
                            flushChannelBatch(channelBatch)
                        }
                    }
                }
            }
        }

        flushChannelBatch(channelBatch)
        flushMovieBatch(movieBatch)
        if (includeLive) {
            categoryDao.replaceAll(provider.id, "LIVE", liveCategories.entities())
            pruneStaleChannels(existingChannelIds, seenLiveStreamIds.orEmpty())
        }
        if (includeMovies) {
            categoryDao.replaceAll(provider.id, "MOVIE", movieCategories.entities())
            pruneStaleMovies(existingMovieIds, seenMovieStreamIds.orEmpty())
            movieDao.restoreWatchProgress(provider.id)
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

    private suspend fun flushChannelBatch(batch: MutableList<ChannelEntity>) {
        if (batch.isEmpty()) {
            return
        }
        channelDao.insertAll(batch.distinctBy { it.streamId })
        batch.clear()
    }

    private suspend fun flushMovieBatch(batch: MutableList<MovieEntity>) {
        if (batch.isEmpty()) {
            return
        }
        movieDao.insertAll(batch.distinctBy { it.streamId })
        batch.clear()
    }

    private suspend fun pruneStaleChannels(
        existingChannelIds: Map<Long, Long>,
        seenStreamIds: Set<Long>
    ) {
        val staleIds = existingChannelIds
            .filterKeys { it !in seenStreamIds }
            .values

        staleIds.chunked(900).forEach { chunk ->
            if (chunk.isNotEmpty()) {
                channelDao.deleteByIds(chunk)
            }
        }
    }

    private suspend fun pruneStaleMovies(
        existingMovieIds: Map<Long, Long>,
        seenStreamIds: Set<Long>
    ) {
        val staleIds = existingMovieIds
            .filterKeys { it !in seenStreamIds }
            .values

        staleIds.chunked(900).forEach { chunk ->
            if (chunk.isNotEmpty()) {
                movieDao.deleteByIds(chunk)
            }
        }
    }

    private suspend fun fetchXtreamVodCategories(provider: Provider): List<XtreamCategory>? {
        return runCatching {
            retryTransient {
                xtreamCatalogApiService.getVodCategories(
                    XtreamUrlFactory.buildPlayerApiUrl(
                        serverUrl = provider.serverUrl,
                        username = provider.username,
                        password = provider.password,
                        action = "get_vod_categories"
                    )
                )
            }
        }.onFailure { error ->
            Log.w(TAG, "Xtream VOD categories request failed for provider ${provider.id}: ${error::class.java.simpleName}: ${sanitizeThrowableMessage(error)}")
        }.getOrNull()
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

    private fun shouldRememberSequentialPreference(error: Throwable): Boolean {
        return error is XtreamAuthenticationException ||
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

    private suspend fun rateLimitXtreamCatalogRequest() {
        delay(250L)
    }

    private fun updateMovieProviderAdaptation(
        previousRemembered: Boolean,
        previousHealthyStreak: Int,
        sawSequentialStress: Boolean
    ): MovieProviderAdaptation {
        if (sawSequentialStress) {
            return MovieProviderAdaptation(rememberSequential = true, healthyStreak = 0)
        }
        if (!previousRemembered) {
            return MovieProviderAdaptation(rememberSequential = false, healthyStreak = 0)
        }
        val nextHealthyStreak = (previousHealthyStreak + 1).coerceAtMost(2)
        return if (nextHealthyStreak >= 2) {
            MovieProviderAdaptation(rememberSequential = false, healthyStreak = 0)
        } else {
            MovieProviderAdaptation(rememberSequential = true, healthyStreak = nextHealthyStreak)
        }
    }

    private fun stableId(
        providerId: Long,
        tvgId: String?,
        url: String,
        hasher: StableLongHasher
    ): Long {
        return if (!tvgId.isNullOrBlank()) {
            hasher.hash("$providerId:tvg:$tvgId")
        } else {
            hasher.hash("$providerId:url:$url")
        }
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

    /** Delegates to M3uParser to avoid duplicate logic. */
    internal fun isVodEntry(entry: M3uParser.M3uEntry): Boolean = M3uParser.isVodEntry(entry)

    private fun progress(providerId: Long, callback: ((String) -> Unit)?, message: String) {
        publishSyncState(providerId, SyncState.Syncing(message))
        callback?.invoke(message)
    }

    private fun publishSyncState(providerId: Long, state: SyncState) {
        _syncState.value = state
        _syncStatesByProvider.update { states -> states + (providerId to state) }
    }

    private fun redactUrlForLogs(url: String?): String {
        if (url.isNullOrBlank()) return "<empty>"
        return runCatching {
            val parsed = java.net.URI(url)
            val scheme = parsed.scheme ?: "http"
            val host = parsed.host ?: return@runCatching "<redacted>"
            val path = parsed.path.orEmpty()
            "$scheme://$host$path"
        }.getOrDefault("<redacted>")
    }

    private fun sanitizeThrowableMessage(error: Throwable?): String {
        return sanitizeLogMessage(error?.message)
    }

    private fun sanitizeLogMessage(message: String?): String {
        if (message.isNullOrBlank()) {
            return "<empty>"
        }
        return message
            .replace(Regex("""https?://\S+""", RegexOption.IGNORE_CASE), "<redacted-url>")
            .replace(Regex("""([?&](username|password|token)=[^&\s]+)""", RegexOption.IGNORE_CASE), "<redacted-param>")
    }

    private suspend fun <T> retryTransient(
        maxAttempts: Int = 3,
        initialDelayMs: Long = 700L,
        block: suspend () -> T
    ): T {
        var attempt = 0
        var delayMs = initialDelayMs
        var lastError: Throwable? = null

        while (attempt < maxAttempts) {
            try {
                return block()
            } catch (t: Throwable) {
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

    private suspend fun <T> retryXtreamCatalogTransient(block: suspend () -> T): T {
        var attempt = 0
        var delayMs = 1_000L
        var lastError: Throwable? = null

        while (attempt < 3) {
            try {
                return block()
            } catch (t: Throwable) {
                lastError = t
                attempt++
                if (attempt >= 3 || !isXtreamCatalogRetryable(t, attempt)) {
                    throw t
                }
                val jitterMs = Random.nextLong(0L, (delayMs / 3L).coerceAtLeast(1L) + 1L)
                delay(delayMs + jitterMs)
                delayMs = (delayMs * 2).coerceAtMost(8_000L)
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
            is XtreamParsingException -> attempt == 1
            is XtreamRequestException -> error.statusCode == 408 || error.statusCode == 409 || error.statusCode in 500..599
            is XtreamNetworkException -> true
            is IOException -> true
            else -> isRetryable(error)
        }
    }

    companion object {
        private const val PROGRESS_INTERVAL = 5_000
        private const val XTREAM_CATEGORY_SYNC_MAX_CONCURRENCY = 2
    }

    private fun xtreamCategorySyncConcurrency(categoryCount: Int): Int {
        return categoryCount
            .coerceAtLeast(1)
            .coerceAtMost(XTREAM_CATEGORY_SYNC_MAX_CONCURRENCY)
    }

    private fun createXtreamSyncProvider(provider: Provider): XtreamProvider {
        return XtreamProvider(
            providerId = provider.id,
            api = xtreamCatalogApiService,
            serverUrl = provider.serverUrl,
            username = provider.username,
            password = provider.password,
            allowedOutputFormats = provider.allowedOutputFormats
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
}
