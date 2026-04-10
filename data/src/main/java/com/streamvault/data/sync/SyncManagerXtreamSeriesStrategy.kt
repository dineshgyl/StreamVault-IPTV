package com.streamvault.data.sync

import android.util.Log
import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.mapper.toEntity
import com.streamvault.data.remote.dto.XtreamCategory
import com.streamvault.data.remote.dto.XtreamSeriesItem
import com.streamvault.data.remote.xtream.OkHttpXtreamApiService
import com.streamvault.data.remote.xtream.XtreamApiService
import com.streamvault.data.remote.xtream.XtreamProvider
import com.streamvault.data.remote.xtream.XtreamUrlFactory
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.Series
import com.streamvault.domain.model.SyncMetadata
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.system.measureTimeMillis

private const val XTREAM_SERIES_STRATEGY_TAG = "SyncManager"

internal class SyncManagerXtreamSeriesStrategy(
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
    private val stageSeriesItems: suspend (Long, List<Series>, MutableSet<Long>, FallbackCategoryCollector, Long?) -> StagedCatalogSnapshot,
    private val stageSeriesSequence: suspend (Long, Sequence<Series>, MutableSet<Long>, FallbackCategoryCollector, Long?) -> StagedCatalogSnapshot,
    private val categorySequentialModeWarning: String,
    private val pagedSequentialModeWarning: String,
    private val fallbackStageBatchSize: Int,
    private val maxCatalogPages: Int,
    private val pageSize: Int
) {
    suspend fun syncXtreamSeriesCatalog(
        provider: Provider,
        api: XtreamProvider,
        existingMetadata: SyncMetadata,
        onProgress: ((String) -> Unit)?
    ): CatalogSyncPayload<Series> {
        Log.i(XTREAM_SERIES_STRATEGY_TAG, "Xtream series strategy start for provider ${provider.id}.")
        val rawSeriesCategories = when (val attempt = xtreamSupport.attemptNonCancellation {
            xtreamSupport.withSeriesRequestTimeout("series categories") {
                xtreamSupport.retryXtreamCatalogTransient(provider.id) {
                    xtreamSupport.executeXtreamRequest(provider.id, XtreamAdaptiveSyncPolicy.Stage.LIGHTWEIGHT) {
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
                Log.w(
                    XTREAM_SERIES_STRATEGY_TAG,
                    "Xtream series categories request failed for provider ${provider.id}: ${sanitizeThrowableMessage(attempt.error)}"
                )
                null
            }
        }
        val resolvedCategories = rawSeriesCategories
            ?.let { categories -> api.mapCategories(ContentType.SERIES, categories) }
            ?.map { category -> category.toEntity(provider.id) }
            ?.takeIf { it.isNotEmpty() }

        var fullPayload = CatalogSyncPayload<Series>(CatalogStrategyResult.EmptyValid("full"), null)
        var pagedPayload = CatalogSyncPayload<Series>(CatalogStrategyResult.EmptyValid("paged"), null)
        var categoryPayload = CatalogSyncPayload<Series>(CatalogStrategyResult.EmptyValid("category_bulk"), null)

        if (provider.xtreamFastSyncEnabled) {
            return if (!resolvedCategories.isNullOrEmpty()) {
                Log.i(
                    XTREAM_SERIES_STRATEGY_TAG,
                    "Xtream series fast sync: returning lazy_by_category for provider ${provider.id} with ${resolvedCategories.size} categories."
                )
                CatalogSyncPayload(
                    catalogResult = CatalogStrategyResult.Failure(
                        strategyName = "lazy_by_category",
                        error = IllegalStateException("Fast sync enabled; series will hydrate on demand"),
                        warnings = emptyList()
                    ),
                    categories = resolvedCategories,
                    warnings = emptyList(),
                    strategyFeedback = XtreamStrategyFeedback(preferredSegmentedFirst = false)
                )
            } else {
                Log.w(XTREAM_SERIES_STRATEGY_TAG, "Xtream series fast sync: no categories available for provider ${provider.id}.")
                CatalogSyncPayload(
                    catalogResult = CatalogStrategyResult.Failure(
                        strategyName = "fast_sync_no_categories",
                        error = IllegalStateException("Fast sync enabled but no series categories available"),
                        warnings = listOf("Fast sync enabled but no series categories returned from server.")
                    ),
                    categories = null,
                    warnings = listOf("Fast sync enabled but no series categories returned from server."),
                    strategyFeedback = XtreamStrategyFeedback(preferredSegmentedFirst = false)
                )
            }
        }

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
                is CatalogStrategyResult.Success, is CatalogStrategyResult.Partial -> return CatalogSyncPayload(
                    catalogResult = categoryResult,
                    categories = catalogStrategySupport.mergePreferredAndFallbackCategories(resolvedCategories, categoryPayload.categories),
                    warnings = catalogStrategySupport.strategyWarnings(fullPayload.catalogResult),
                    strategyFeedback = XtreamStrategyFeedback(
                        preferredSegmentedFirst = true,
                        segmentedStressDetected = catalogStrategySupport.sawSegmentedStress(
                            warnings = catalogStrategySupport.strategyWarnings(fullPayload.catalogResult),
                            result = categoryResult,
                            sequentialWarnings = setOf(categorySequentialModeWarning)
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
                is CatalogStrategyResult.Success, is CatalogStrategyResult.Partial -> return CatalogSyncPayload(
                    catalogResult = pagedResult,
                    categories = pagedPayload.categories,
                    warnings = catalogStrategySupport.strategyWarnings(fullPayload.catalogResult) +
                        catalogStrategySupport.strategyWarnings(categoryPayload.catalogResult),
                    strategyFeedback = XtreamStrategyFeedback(
                        preferredSegmentedFirst = true,
                        segmentedStressDetected = catalogStrategySupport.sawSegmentedStress(
                            warnings = catalogStrategySupport.strategyWarnings(fullPayload.catalogResult) +
                                catalogStrategySupport.strategyWarnings(categoryPayload.catalogResult),
                            result = pagedResult,
                            sequentialWarnings = setOf(pagedSequentialModeWarning, categorySequentialModeWarning)
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
                is CatalogStrategyResult.Success, is CatalogStrategyResult.Partial -> return CatalogSyncPayload(
                    catalogResult = categoryResult,
                    categories = categoryPayload.categories,
                    warnings = catalogStrategySupport.strategyWarnings(fullPayload.catalogResult) +
                        catalogStrategySupport.strategyWarnings(pagedPayload.catalogResult),
                    strategyFeedback = XtreamStrategyFeedback(
                        preferredSegmentedFirst = true,
                        segmentedStressDetected = catalogStrategySupport.sawSegmentedStress(
                            warnings = catalogStrategySupport.strategyWarnings(fullPayload.catalogResult) +
                                catalogStrategySupport.strategyWarnings(pagedPayload.catalogResult),
                            result = categoryResult,
                            sequentialWarnings = setOf(categorySequentialModeWarning, pagedSequentialModeWarning)
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
                warnings = catalogStrategySupport.strategyWarnings(categoryPayload.catalogResult) +
                    catalogStrategySupport.strategyWarnings(pagedPayload.catalogResult),
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
            addAll(catalogStrategySupport.strategyWarnings(categoryPayload.catalogResult))
            addAll(catalogStrategySupport.strategyWarnings(fullPayload.catalogResult))
            if (!resolvedCategories.isNullOrEmpty()) {
                add("Series entered lazy category-only mode after category-bulk and full strategies failed.")
            } else {
                addAll(catalogStrategySupport.strategyWarnings(pagedPayload.catalogResult))
                add("Series entered lazy category-only mode after category-bulk, paged, and full strategies failed.")
            }
        }
        Log.w(
            XTREAM_SERIES_STRATEGY_TAG,
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

    suspend fun loadXtreamSeriesFull(provider: Provider, api: XtreamProvider): CatalogSyncPayload<Series> {
        val fallbackCollector = FallbackCategoryCollector(provider.id, ContentType.SERIES)
        val seenSeriesIds = HashSet<Long>()
        val rawBatch = ArrayList<XtreamSeriesItem>(fallbackStageBatchSize)
        var stagedSessionId: Long? = null
        var acceptedCount = 0
        var streamedRawCount = 0
        var fullSeriesFailure: Throwable? = null

        suspend fun flushRawBatch() {
            if (rawBatch.isEmpty()) return
            val staged = stageSeriesSequence(provider.id, api.mapSeriesListSequence(rawBatch.asSequence()), seenSeriesIds, fallbackCollector, stagedSessionId)
            stagedSessionId = staged.sessionId
            acceptedCount += staged.acceptedCount
            rawBatch.clear()
        }

        val fullSeriesElapsedMs = measureTimeMillis {
            when (val attempt = xtreamSupport.attemptNonCancellation {
                xtreamSupport.withSeriesRequestTimeout("full series catalog") {
                    xtreamSupport.retryXtreamCatalogTransient(provider.id) {
                        xtreamSupport.executeXtreamRequest(provider.id, XtreamAdaptiveSyncPolicy.Stage.HEAVY) {
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
                                if (rawBatch.size >= fallbackStageBatchSize) flushRawBatch()
                            }.also { flushRawBatch() }
                        }
                    }
                }
            }) {
                is Attempt.Success -> Unit
                is Attempt.Failure -> {
                    fullSeriesFailure = attempt.error
                    stagedSessionId?.let { syncCatalogStore.discardStagedImport(provider.id, it) }
                    stagedSessionId = null
                }
            }
        }
        if (streamedRawCount > 0) {
            Log.i(
                XTREAM_SERIES_STRATEGY_TAG,
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
            xtreamSupport.logXtreamCatalogFallback(provider, "series", "full catalog", fullSeriesElapsedMs, streamedRawCount, fullSeriesFailure, "category-bulk")
            CatalogSyncPayload(
                catalogResult = CatalogStrategyResult.Failure(
                    strategyName = "full",
                    error = fullSeriesFailure!!,
                    warnings = listOf(fullCatalogFallbackWarning("Series", fullSeriesFailure))
                ),
                categories = null
            )
        } else {
            xtreamSupport.logXtreamCatalogFallback(provider, "series", "full catalog", fullSeriesElapsedMs, streamedRawCount, null, "category-bulk")
            CatalogSyncPayload(
                catalogResult = CatalogStrategyResult.EmptyValid(
                    strategyName = "full",
                    warnings = listOf("Series full catalog returned an empty valid result.")
                ),
                categories = null
            )
        }
    }

    suspend fun loadXtreamSeriesByCategory(
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
        val concurrency = xtreamAdaptiveSyncPolicy.concurrencyFor(provider.id, categories.size, preferSequential, XtreamAdaptiveSyncPolicy.Stage.CATEGORY)
        progress(provider.id, onProgress, "Downloading Series by category 0/${categories.size}...")
        val executionPlan = xtreamSupport.executeCategoryRecoveryPlan(
            provider = provider,
            categories = categories,
            initialConcurrency = concurrency,
            sectionLabel = "Series",
            sequentialModeWarning = categorySequentialModeWarning,
            onProgress = onProgress,
            fetch = { category -> xtreamFetcher.fetchSeriesCategoryOutcome(provider, api, category) }
        )
        var timedOutcomes = executionPlan.outcomes
        val categoryOutcomes = timedOutcomes.map { it.outcome }
        val failureCount = timedOutcomes.count { it.outcome is CategoryFetchOutcome.Failure }
        val fastFailureCount = timedOutcomes.count { it.elapsedMs <= 5_000L && it.outcome is CategoryFetchOutcome.Failure }
        val downgradeRecommended = catalogStrategySupport.shouldDowngradeCategorySync(categories.size, failureCount, fastFailureCount, categoryOutcomes)
        var fallbackWarnings = executionPlan.warnings
        if (concurrency > 1 && catalogStrategySupport.shouldRetryFailedCategories(categories.size, failureCount, downgradeRecommended, categoryOutcomes)) {
            Log.w(XTREAM_SERIES_STRATEGY_TAG, "Xtream series category sync is continuing in sequential mode for failed categories on provider ${provider.id}.")
            timedOutcomes = xtreamSupport.continueFailedCategoryOutcomes(
                provider = provider,
                timedOutcomes = timedOutcomes,
                fetchSequentially = { category -> xtreamFetcher.fetchSeriesCategoryOutcome(provider, api, category) }
            )
            fallbackWarnings = (fallbackWarnings + if (downgradeRecommended) listOf(categorySequentialModeWarning) else emptyList()).distinct()
        }
        val finalOutcomes = timedOutcomes.map { it.outcome }
        val warnings = finalOutcomes.filterIsInstance<CategoryFetchOutcome.Failure>()
            .map { failure -> categoryFailureWarning("Series", failure.categoryName, failure.error) } + fallbackWarnings

        val seenSeriesIds = HashSet<Long>()
        val fallbackCollector = FallbackCategoryCollector(provider.id, ContentType.SERIES)
        var sessionId: Long? = null
        var acceptedCount = 0
        finalOutcomes.filterIsInstance<CategoryFetchOutcome.Success<Series>>().forEach { success ->
            val staged = stageSeriesItems(provider.id, success.items, seenSeriesIds, fallbackCollector, sessionId)
            sessionId = staged.sessionId
            acceptedCount += staged.acceptedCount
        }
        val failedCategories = finalOutcomes.count { it is CategoryFetchOutcome.Failure }
        val emptyCategories = finalOutcomes.count { it is CategoryFetchOutcome.Empty }
        val successfulCategories = finalOutcomes.count { it is CategoryFetchOutcome.Success }
        Log.i(
            XTREAM_SERIES_STRATEGY_TAG,
            "Xtream series category strategy summary for provider ${provider.id}: successful=$successfulCategories empty=$emptyCategories failed=$failedCategories dedupedSeries=$acceptedCount concurrency=$concurrency"
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

    suspend fun loadXtreamSeriesByPage(
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
        while (nextPage <= maxCatalogPages && !stopPaging) {
            val remainingPages = maxCatalogPages - nextPage + 1
            val concurrency = xtreamAdaptiveSyncPolicy.concurrencyFor(provider.id, remainingPages, forceSequential, XtreamAdaptiveSyncPolicy.Stage.PAGED)
            val pageWindow = (nextPage until (nextPage + concurrency)).takeWhile { it <= maxCatalogPages }
            progress(provider.id, onProgress, if (pageWindow.size == 1) "Downloading Series by page ${pageWindow.first()}..." else "Downloading Series by page ${pageWindow.first()}-${pageWindow.last()}...")
            var timedOutcomes = coroutineScope { pageWindow.map { page -> async { xtreamFetcher.fetchSeriesPageOutcome(provider, api, page) } }.awaitAll() }
            val failures = timedOutcomes.count { it.outcome is PageFetchOutcome.Failure }
            val recoveryPlan = xtreamSupport.evaluatePageRecoveryPlan(provider, "Series", pageWindow, timedOutcomes, pagedSequentialModeWarning)
            forceSequential = forceSequential || recoveryPlan.warnings.any { it == pagedSequentialModeWarning }
            warnings += recoveryPlan.warnings
            if (recoveryPlan.stoppedEarly) stopPaging = true
            if (!recoveryPlan.stoppedEarly && concurrency > 1 && catalogStrategySupport.shouldRetryFailedPages(pageWindow.size, failures, timedOutcomes.map { it.outcome })) {
                timedOutcomes = xtreamSupport.continueFailedPageOutcomes(provider, timedOutcomes) { page ->
                    xtreamFetcher.fetchSeriesPageOutcome(provider, api, page)
                }
            }
            var terminalFailure: Throwable? = null
            var terminalFailurePage: Int? = null
            timedOutcomes.sortedBy { it.page }.forEach { timedOutcome ->
                when (val outcome = timedOutcome.outcome) {
                    is PageFetchOutcome.Success -> {
                        val staged = stageSeriesItems(provider.id, outcome.items, seenSeriesIds, fallbackCollector, sessionId)
                        sessionId = staged.sessionId
                        acceptedCount += staged.acceptedCount
                        if (outcome.rawCount < pageSize || staged.acceptedCount == 0) stopPaging = true
                    }
                    is PageFetchOutcome.Empty -> stopPaging = true
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
                            warnings = listOf(pagingFailureWarning("Series", terminalFailurePage ?: nextPage, terminalFailure!!))
                        ),
                        categories = null
                    )
                } else {
                    CatalogSyncPayload(
                        catalogResult = CatalogStrategyResult.Partial("paged", emptyList(), warnings.toList()),
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
}
