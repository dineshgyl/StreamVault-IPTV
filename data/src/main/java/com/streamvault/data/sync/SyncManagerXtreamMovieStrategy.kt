package com.streamvault.data.sync

import android.util.Log
import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.mapper.toEntity
import com.streamvault.data.remote.dto.XtreamCategory
import com.streamvault.data.remote.dto.XtreamStream
import com.streamvault.data.remote.xtream.OkHttpXtreamApiService
import com.streamvault.data.remote.xtream.XtreamApiService
import com.streamvault.data.remote.xtream.XtreamProvider
import com.streamvault.data.remote.xtream.XtreamUrlFactory
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.SyncMetadata
import com.streamvault.domain.model.VodSyncMode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.system.measureTimeMillis

private const val XTREAM_MOVIE_STRATEGY_TAG = "SyncManager"

internal class SyncManagerXtreamMovieStrategy(
    private val xtreamCatalogApiService: XtreamApiService,
    private val xtreamCatalogHttpService: OkHttpXtreamApiService,
    private val xtreamAdaptiveSyncPolicy: XtreamAdaptiveSyncPolicy,
    private val xtreamSupport: SyncManagerXtreamSupport,
    private val xtreamFetcher: SyncManagerXtreamFetcher,
    private val catalogStrategySupport: SyncManagerCatalogStrategySupport,
    private val syncCatalogStore: SyncCatalogStore,
    private val progress: (Long, ((String) -> Unit)?, String) -> Unit,
    private val sanitizeThrowableMessage: (Throwable?) -> String,
    private val fullCatalogFallbackWarning: (String, Throwable?) -> String,
    private val categoryFailureWarning: (String, String, Throwable) -> String,
    private val pagingFailureWarning: (String, Int, Throwable) -> String,
    private val stageMovieItems: suspend (Long, List<Movie>, MutableSet<Long>, FallbackCategoryCollector, Long?) -> StagedCatalogSnapshot,
    private val stageMovieSequence: suspend (Long, Sequence<Movie>, MutableSet<Long>, FallbackCategoryCollector, Long?) -> StagedCatalogSnapshot,
    private val movieCategorySequentialModeWarning: String,
    private val moviePagedSequentialModeWarning: String,
    private val fallbackStageBatchSize: Int,
    private val maxCatalogPages: Int,
    private val pageSize: Int
) {
    suspend fun syncXtreamMoviesCatalog(
        provider: Provider,
        api: XtreamProvider,
        existingMetadata: SyncMetadata,
        onProgress: ((String) -> Unit)?
    ): MovieCatalogSyncResult {
        Log.i(
            XTREAM_MOVIE_STRATEGY_TAG,
            "Xtream movies strategy start for provider ${provider.id}. previousMode=${existingMetadata.movieSyncMode} rememberSequential=${existingMetadata.movieParallelFailuresRemembered}"
        )
        val rawVodCategories = fetchXtreamVodCategories(provider)
        val resolvedCategories = rawVodCategories
            ?.let { categories -> api.mapCategories(ContentType.MOVIE, categories) }
            ?.map { category -> category.toEntity(provider.id) }
            ?.takeIf { it.isNotEmpty() }

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

        if (provider.xtreamFastSyncEnabled) {
            return if (!resolvedCategories.isNullOrEmpty()) {
                Log.i(
                    XTREAM_MOVIE_STRATEGY_TAG,
                    "Xtream movies fast sync: returning LAZY_BY_CATEGORY for provider ${provider.id} with ${resolvedCategories.size} categories."
                )
                MovieCatalogSyncResult(
                    catalogResult = CatalogStrategyResult.Failure(
                        strategyName = "fast_sync",
                        error = IllegalStateException("Fast sync enabled; movies will hydrate on demand"),
                        warnings = emptyList()
                    ),
                    categories = resolvedCategories,
                    syncMode = VodSyncMode.LAZY_BY_CATEGORY,
                    warnings = emptyList(),
                    strategyFeedback = XtreamStrategyFeedback(preferredSegmentedFirst = false)
                )
            } else {
                Log.w(
                    XTREAM_MOVIE_STRATEGY_TAG,
                    "Xtream movies fast sync: no categories available for provider ${provider.id}, falling through to standard strategies."
                )
                MovieCatalogSyncResult(
                    catalogResult = CatalogStrategyResult.Failure(
                        strategyName = "fast_sync_no_categories",
                        error = IllegalStateException("Fast sync enabled but no categories available"),
                        warnings = listOf("Fast sync enabled but no categories returned from server.")
                    ),
                    categories = null,
                    syncMode = VodSyncMode.UNKNOWN,
                    warnings = listOf("Fast sync enabled but no categories returned from server."),
                    strategyFeedback = XtreamStrategyFeedback(preferredSegmentedFirst = false)
                )
            }
        }

        progress(provider.id, onProgress, "Checking Movies full catalog...")
        fullPayload = loadXtreamMoviesFull(provider, api)
        when (val fullResult = fullPayload.catalogResult) {
            is CatalogStrategyResult.Success -> return MovieCatalogSyncResult(
                catalogResult = fullResult,
                categories = catalogStrategySupport.mergePreferredAndFallbackCategories(resolvedCategories, fullPayload.categories),
                syncMode = VodSyncMode.FULL,
                warnings = emptyList(),
                strategyFeedback = XtreamStrategyFeedback(
                    preferredSegmentedFirst = false,
                    attemptedFullCatalog = true,
                    fullCatalogUnsafe = false
                ),
                stagedSessionId = fullPayload.stagedSessionId,
                stagedAcceptedCount = fullPayload.stagedAcceptedCount
            ).also {
                Log.i(
                    XTREAM_MOVIE_STRATEGY_TAG,
                    "Xtream movies strategy selected FULL for provider ${provider.id} with ${fullPayload.stagedAcceptedCount} items."
                )
            }
            is CatalogStrategyResult.Partial -> return MovieCatalogSyncResult(
                catalogResult = fullResult,
                categories = catalogStrategySupport.mergePreferredAndFallbackCategories(resolvedCategories, fullPayload.categories),
                syncMode = VodSyncMode.FULL,
                warnings = emptyList(),
                strategyFeedback = XtreamStrategyFeedback(
                    preferredSegmentedFirst = false,
                    attemptedFullCatalog = true,
                    fullCatalogUnsafe = false
                ),
                stagedSessionId = fullPayload.stagedSessionId,
                stagedAcceptedCount = fullPayload.stagedAcceptedCount
            ).also {
                Log.w(
                    XTREAM_MOVIE_STRATEGY_TAG,
                    "Xtream movies strategy selected FULL(partial) for provider ${provider.id} with ${fullPayload.stagedAcceptedCount} items."
                )
            }
            else -> Unit
        }

        if (!resolvedCategories.isNullOrEmpty()) {
            progress(provider.id, onProgress, "Preparing Movies category sync...")
            categoryPayload = loadXtreamMoviesByCategory(
                provider = provider,
                api = api,
                rawCategories = rawVodCategories.orEmpty(),
                onProgress = onProgress,
                preferSequential = existingMetadata.movieParallelFailuresRemembered
            )
        }
        when (val categoryResult = categoryPayload.catalogResult) {
            is CatalogStrategyResult.Success -> return buildCategoryResult(
                provider = provider,
                resolvedCategories = resolvedCategories,
                categoryPayload = categoryPayload,
                fullPayload = fullPayload,
                pagedPayload = pagedPayload,
                categoryResult = categoryResult,
                partial = false
            )
            is CatalogStrategyResult.Partial -> return buildCategoryResult(
                provider = provider,
                resolvedCategories = resolvedCategories,
                categoryPayload = categoryPayload,
                fullPayload = fullPayload,
                pagedPayload = pagedPayload,
                categoryResult = categoryResult,
                partial = true
            )
            else -> Unit
        }

        if (resolvedCategories.isNullOrEmpty()) {
            progress(provider.id, onProgress, "Checking Movies paged catalog...")
            pagedPayload = loadXtreamMoviesByPage(provider, api, onProgress)
            when (val pagedResult = pagedPayload.catalogResult) {
                is CatalogStrategyResult.Success -> return buildPagedResult(
                    provider = provider,
                    pagedPayload = pagedPayload,
                    fullPayload = fullPayload,
                    categoryPayload = categoryPayload,
                    pagedResult = pagedResult,
                    partial = false
                )
                is CatalogStrategyResult.Partial -> return buildPagedResult(
                    provider = provider,
                    pagedPayload = pagedPayload,
                    fullPayload = fullPayload,
                    categoryPayload = categoryPayload,
                    pagedResult = pagedResult,
                    partial = true
                )
                else -> Unit
            }
        }

        progress(provider.id, onProgress, "Checking Movies full catalog...")
        fullPayload = loadXtreamMoviesFull(provider, api)
        when (val fullResult = fullPayload.catalogResult) {
            is CatalogStrategyResult.Success -> return MovieCatalogSyncResult(
                catalogResult = fullResult,
                categories = catalogStrategySupport.mergePreferredAndFallbackCategories(resolvedCategories, fullPayload.categories),
                syncMode = VodSyncMode.FULL,
                warnings = catalogStrategySupport.strategyWarnings(categoryPayload.catalogResult) +
                    catalogStrategySupport.strategyWarnings(pagedPayload.catalogResult),
                strategyFeedback = XtreamStrategyFeedback(
                    preferredSegmentedFirst = true,
                    attemptedFullCatalog = true,
                    fullCatalogUnsafe = false
                ),
                stagedSessionId = fullPayload.stagedSessionId,
                stagedAcceptedCount = fullPayload.stagedAcceptedCount
            ).also {
                val itemCount = fullPayload.stagedAcceptedCount.takeIf { it > 0 } ?: fullResult.items.size
                Log.i(XTREAM_MOVIE_STRATEGY_TAG, "Xtream movies strategy selected FULL for provider ${provider.id} with $itemCount items.")
            }
            else -> Unit
        }

        val lazyWarnings = buildList {
            addAll(catalogStrategySupport.strategyWarnings(categoryPayload.catalogResult))
            addAll(catalogStrategySupport.strategyWarnings(fullPayload.catalogResult))
            if (!resolvedCategories.isNullOrEmpty()) {
                add("Movies entered lazy category-only mode after category-bulk and full strategies failed.")
            } else {
                addAll(catalogStrategySupport.strategyWarnings(pagedPayload.catalogResult))
                add("Movies entered lazy category-only mode after category-bulk, paged, and full strategies failed.")
            }
        }
        Log.w(
            XTREAM_MOVIE_STRATEGY_TAG,
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

    suspend fun loadXtreamMoviesFull(
        provider: Provider,
        api: XtreamProvider
    ): CatalogSyncPayload<Movie> {
        val fallbackCollector = FallbackCategoryCollector(provider.id, ContentType.MOVIE)
        val seenStreamIds = HashSet<Long>()
        val rawBatch = ArrayList<XtreamStream>(fallbackStageBatchSize)
        var stagedSessionId: Long? = null
        var acceptedCount = 0
        var streamedRawCount = 0
        var fullMoviesFailure: Throwable? = null

        suspend fun flushRawBatch() {
            if (rawBatch.isEmpty()) return
            val staged = stageMovieSequence(
                provider.id,
                api.mapVodStreamsSequence(rawBatch.asSequence()),
                seenStreamIds,
                fallbackCollector,
                stagedSessionId
            )
            stagedSessionId = staged.sessionId
            acceptedCount += staged.acceptedCount
            rawBatch.clear()
        }

        val fullMoviesElapsedMs = measureTimeMillis {
            when (val attempt = xtreamSupport.attemptNonCancellation {
                xtreamSupport.withMovieRequestTimeout("full movie catalog") {
                    xtreamSupport.retryXtreamCatalogTransient(provider.id) {
                        xtreamSupport.executeXtreamRequest(provider.id, XtreamAdaptiveSyncPolicy.Stage.HEAVY) {
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
                                if (rawBatch.size >= fallbackStageBatchSize) {
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
                XTREAM_MOVIE_STRATEGY_TAG,
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
            xtreamSupport.logXtreamCatalogFallback(
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
            xtreamSupport.logXtreamCatalogFallback(
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

    suspend fun loadXtreamMoviesByCategory(
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

        val executionPlan = xtreamSupport.executeCategoryRecoveryPlan(
            provider = provider,
            categories = categories,
            initialConcurrency = concurrency,
            sectionLabel = "Movies",
            sequentialModeWarning = movieCategorySequentialModeWarning,
            onProgress = onProgress,
            fetch = { category -> xtreamFetcher.fetchMovieCategoryOutcome(provider, api, category) }
        )
        var timedOutcomes = executionPlan.outcomes

        val categoryOutcomes = timedOutcomes.map { it.outcome }
        val failureCount = timedOutcomes.count { it.outcome is CategoryFetchOutcome.Failure }
        val fastFailureCount = timedOutcomes.count { it.elapsedMs <= 5_000L && it.outcome is CategoryFetchOutcome.Failure }

        val downgradeRecommended = catalogStrategySupport.shouldDowngradeCategorySync(
            totalCategories = categories.size,
            failures = failureCount,
            fastFailures = fastFailureCount,
            outcomes = categoryOutcomes
        )
        var fallbackWarnings = executionPlan.warnings
        if (concurrency > 1 && catalogStrategySupport.shouldRetryFailedCategories(
                categories.size,
                failureCount,
                downgradeRecommended,
                categoryOutcomes
            )
        ) {
            Log.w(
                XTREAM_MOVIE_STRATEGY_TAG,
                "Xtream movie category sync is continuing in sequential mode for failed categories on provider ${provider.id}."
            )
            timedOutcomes = xtreamSupport.continueFailedCategoryOutcomes(
                provider = provider,
                timedOutcomes = timedOutcomes,
                fetchSequentially = { category -> xtreamFetcher.fetchMovieCategoryOutcome(provider, api, category) }
            )
            fallbackWarnings = (
                fallbackWarnings +
                    if (downgradeRecommended) listOf(movieCategorySequentialModeWarning) else emptyList()
                ).distinct()
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
                val staged = stageMovieItems(provider.id, success.items, seenStreamIds, fallbackCollector, sessionId)
                sessionId = staged.sessionId
                acceptedCount += staged.acceptedCount
            }
        val failedCategories = finalOutcomes.count { it is CategoryFetchOutcome.Failure }
        val emptyCategories = finalOutcomes.count { it is CategoryFetchOutcome.Empty }
        val successfulCategories = finalOutcomes.count { it is CategoryFetchOutcome.Success }
        Log.i(
            XTREAM_MOVIE_STRATEGY_TAG,
            "Xtream movie category strategy summary for provider ${provider.id}: successful=$successfulCategories empty=$emptyCategories failed=$failedCategories dedupedMovies=$acceptedCount concurrency=$concurrency"
        )

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

    suspend fun loadXtreamMoviesByPage(
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

        while (nextPage <= maxCatalogPages && !stopPaging) {
            val remainingPages = maxCatalogPages - nextPage + 1
            val concurrency = xtreamAdaptiveSyncPolicy.concurrencyFor(
                providerId = provider.id,
                workloadSize = remainingPages,
                preferSequential = forceSequential,
                stage = XtreamAdaptiveSyncPolicy.Stage.PAGED
            )
            val pageWindow = (nextPage until (nextPage + concurrency))
                .takeWhile { it <= maxCatalogPages }
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
                    async { xtreamFetcher.fetchMoviePageOutcome(provider, api, page) }
                }.awaitAll()
            }
            val failures = timedOutcomes.count { it.outcome is PageFetchOutcome.Failure }
            val recoveryPlan = xtreamSupport.evaluatePageRecoveryPlan(
                provider = provider,
                sectionLabel = "Movies",
                pageWindow = pageWindow,
                outcomes = timedOutcomes,
                sequentialModeWarning = moviePagedSequentialModeWarning
            )
            forceSequential = forceSequential || recoveryPlan.warnings.any { it == moviePagedSequentialModeWarning }
            warnings += recoveryPlan.warnings
            if (recoveryPlan.stoppedEarly) {
                stopPaging = true
            }
            if (!recoveryPlan.stoppedEarly && concurrency > 1 &&
                catalogStrategySupport.shouldRetryFailedPages(pageWindow.size, failures, timedOutcomes.map { it.outcome })
            ) {
                timedOutcomes = xtreamSupport.continueFailedPageOutcomes(
                    provider = provider,
                    timedOutcomes = timedOutcomes,
                    fetchSequentially = { page -> xtreamFetcher.fetchMoviePageOutcome(provider, api, page) }
                )
            }

            var terminalFailure: Throwable? = null
            var terminalFailurePage: Int? = null
            timedOutcomes.sortedBy { it.page }.forEach { timedOutcome ->
                when (val outcome = timedOutcome.outcome) {
                    is PageFetchOutcome.Success -> {
                        val staged = stageMovieItems(provider.id, outcome.items, seenStreamIds, fallbackCollector, sessionId)
                        sessionId = staged.sessionId
                        acceptedCount += staged.acceptedCount
                        val newItems = staged.acceptedCount
                        if (outcome.rawCount < pageSize || newItems == 0) {
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
                            warnings = listOf(pagingFailureWarning("Movies", terminalFailurePage ?: nextPage, terminalFailure!!))
                        ),
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
            Log.i(
                XTREAM_MOVIE_STRATEGY_TAG,
                "Xtream paged movie strategy completed for provider ${provider.id} with $acceptedCount deduped items."
            )
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

    suspend fun fetchXtreamVodCategories(provider: Provider): List<XtreamCategory>? {
        return when (val attempt = xtreamSupport.attemptNonCancellation {
            xtreamSupport.withMovieRequestTimeout("movie categories") {
                xtreamSupport.retryXtreamCatalogTransient(provider.id) {
                    xtreamSupport.executeXtreamRequest(provider.id, XtreamAdaptiveSyncPolicy.Stage.LIGHTWEIGHT) {
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
                Log.w(
                    XTREAM_MOVIE_STRATEGY_TAG,
                    "Xtream VOD categories request failed for provider ${provider.id}: ${sanitizeThrowableMessage(attempt.error)}"
                )
                null
            }
        }
    }

    private fun buildCategoryResult(
        provider: Provider,
        resolvedCategories: List<CategoryEntity>?,
        categoryPayload: CatalogSyncPayload<Movie>,
        fullPayload: CatalogSyncPayload<Movie>,
        pagedPayload: CatalogSyncPayload<Movie>,
        categoryResult: CatalogStrategyResult<Movie>,
        partial: Boolean
    ): MovieCatalogSyncResult {
        val warnings = catalogStrategySupport.strategyWarnings(fullPayload.catalogResult) +
            catalogStrategySupport.strategyWarnings(pagedPayload.catalogResult)
        return MovieCatalogSyncResult(
            catalogResult = categoryResult,
            categories = catalogStrategySupport.mergePreferredAndFallbackCategories(resolvedCategories, categoryPayload.categories),
            syncMode = VodSyncMode.CATEGORY_BULK,
            warnings = warnings,
            strategyFeedback = XtreamStrategyFeedback(
                preferredSegmentedFirst = true,
                segmentedStressDetected = catalogStrategySupport.sawSegmentedStress(
                    warnings = warnings,
                    result = categoryResult,
                    sequentialWarnings = setOf(movieCategorySequentialModeWarning, moviePagedSequentialModeWarning)
                )
            ),
            stagedSessionId = categoryPayload.stagedSessionId,
            stagedAcceptedCount = categoryPayload.stagedAcceptedCount
        ).also {
            Log.println(
                if (partial) Log.WARN else Log.INFO,
                XTREAM_MOVIE_STRATEGY_TAG,
                "Xtream movies strategy selected CATEGORY_BULK${if (partial) "(partial)" else ""} for provider ${provider.id} with ${categoryPayload.stagedAcceptedCount} items."
            )
        }
    }

    private fun buildPagedResult(
        provider: Provider,
        pagedPayload: CatalogSyncPayload<Movie>,
        fullPayload: CatalogSyncPayload<Movie>,
        categoryPayload: CatalogSyncPayload<Movie>,
        pagedResult: CatalogStrategyResult<Movie>,
        partial: Boolean
    ): MovieCatalogSyncResult {
        val warnings = catalogStrategySupport.strategyWarnings(fullPayload.catalogResult) +
            catalogStrategySupport.strategyWarnings(categoryPayload.catalogResult)
        return MovieCatalogSyncResult(
            catalogResult = pagedResult,
            categories = pagedPayload.categories,
            syncMode = VodSyncMode.PAGED,
            warnings = warnings,
            strategyFeedback = XtreamStrategyFeedback(
                preferredSegmentedFirst = true,
                segmentedStressDetected = catalogStrategySupport.sawSegmentedStress(
                    warnings = warnings,
                    result = pagedResult,
                    sequentialWarnings = setOf(moviePagedSequentialModeWarning, movieCategorySequentialModeWarning)
                )
            ),
            stagedSessionId = pagedPayload.stagedSessionId,
            stagedAcceptedCount = pagedPayload.stagedAcceptedCount
        ).also {
            Log.println(
                if (partial) Log.WARN else Log.INFO,
                XTREAM_MOVIE_STRATEGY_TAG,
                "Xtream movies strategy selected PAGED${if (partial) "(partial)" else ""} for provider ${provider.id} with ${pagedPayload.stagedAcceptedCount} items."
            )
        }
    }
}
