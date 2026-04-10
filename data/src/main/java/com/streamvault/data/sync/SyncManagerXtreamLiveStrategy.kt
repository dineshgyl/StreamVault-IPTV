package com.streamvault.data.sync

import android.util.Log
import com.streamvault.data.mapper.toEntity
import com.streamvault.data.remote.dto.XtreamCategory
import com.streamvault.data.remote.xtream.XtreamApiService
import com.streamvault.data.remote.xtream.XtreamProvider
import com.streamvault.data.remote.xtream.XtreamUrlFactory
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.SyncMetadata
import kotlin.system.measureTimeMillis

private const val XTREAM_LIVE_STRATEGY_TAG = "SyncManager"

internal class SyncManagerXtreamLiveStrategy(
    private val xtreamCatalogApiService: XtreamApiService,
    private val xtreamAdaptiveSyncPolicy: XtreamAdaptiveSyncPolicy,
    private val xtreamSupport: SyncManagerXtreamSupport,
    private val xtreamFetcher: SyncManagerXtreamFetcher,
    private val catalogStrategySupport: SyncManagerCatalogStrategySupport,
    private val progress: (Long, ((String) -> Unit)?, String) -> Unit,
    private val sanitizeThrowableMessage: (Throwable?) -> String,
    private val fullCatalogFallbackWarning: (String, Throwable?) -> String,
    private val categoryFailureWarning: (String, String, Throwable) -> String,
    private val liveCategorySequentialModeWarning: String
) {
    suspend fun syncXtreamLiveCatalog(
        provider: Provider,
        api: XtreamProvider,
        existingMetadata: SyncMetadata,
        hiddenLiveCategoryIds: Set<Long>,
        onProgress: ((String) -> Unit)?
    ): CatalogSyncPayload<Channel> {
        Log.i(XTREAM_LIVE_STRATEGY_TAG, "Xtream live strategy start for provider ${provider.id}.")
        val rawLiveCategories = when (val attempt = xtreamSupport.attemptNonCancellation {
            xtreamSupport.retryXtreamCatalogTransient(provider.id) {
                xtreamSupport.executeXtreamRequest(provider.id, XtreamAdaptiveSyncPolicy.Stage.LIGHTWEIGHT) {
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
                Log.w(
                    XTREAM_LIVE_STRATEGY_TAG,
                    "Xtream live categories request failed for provider ${provider.id}: ${sanitizeThrowableMessage(attempt.error)}"
                )
                null
            }
        }
        val resolvedCategories = rawLiveCategories
            ?.let { categories -> api.mapCategories(ContentType.LIVE, categories) }
            ?.map { category -> category.toEntity(provider.id) }
            ?.takeIf { it.isNotEmpty() }
        val filteredRawLiveCategories = rawLiveCategories.orEmpty().filterNot { category ->
            category.categoryId.toLongOrNull() in hiddenLiveCategoryIds
        }
        val visibleResolvedCategories = resolvedCategories
            ?.filterNot { category -> category.categoryId in hiddenLiveCategoryIds }
            ?.takeIf { it.isNotEmpty() }

        var fullResult: CatalogStrategyResult<Channel> = CatalogStrategyResult.EmptyValid("full")
        if (hiddenLiveCategoryIds.isEmpty()) {
            progress(provider.id, onProgress, "Downloading Live TV...")
            fullResult = loadXtreamLiveFull(provider, api)
            when (fullResult) {
                is CatalogStrategyResult.Success -> return CatalogSyncPayload(
                    catalogResult = fullResult,
                    categories = catalogStrategySupport.mergePreferredAndFallbackCategories(
                        visibleResolvedCategories,
                        catalogStrategySupport.buildFallbackLiveCategories(provider.id, fullResult.items)
                    ),
                    warnings = emptyList(),
                    strategyFeedback = XtreamStrategyFeedback(
                        attemptedFullCatalog = true,
                        fullCatalogUnsafe = false
                    )
                )
                is CatalogStrategyResult.Partial -> return CatalogSyncPayload(
                    catalogResult = fullResult,
                    categories = catalogStrategySupport.mergePreferredAndFallbackCategories(
                        visibleResolvedCategories,
                        catalogStrategySupport.buildFallbackLiveCategories(provider.id, fullResult.items)
                    ),
                    warnings = emptyList(),
                    strategyFeedback = XtreamStrategyFeedback(
                        attemptedFullCatalog = true,
                        fullCatalogUnsafe = false
                    )
                )
                else -> Unit
            }
        } else {
            Log.i(
                XTREAM_LIVE_STRATEGY_TAG,
                "Xtream live full catalog skipped for provider ${provider.id} because ${hiddenLiveCategoryIds.size} live categories are hidden."
            )
        }

        progress(provider.id, onProgress, "Downloading Live TV by category...")
        val categoryResult = loadXtreamLiveByCategory(
            provider = provider,
            api = api,
            rawCategories = filteredRawLiveCategories,
            onProgress = onProgress,
            preferSequential = existingMetadata.liveSequentialFailuresRemembered
        )
        return CatalogSyncPayload(
            catalogResult = categoryResult,
            categories = when (categoryResult) {
                is CatalogStrategyResult.Success -> catalogStrategySupport.mergePreferredAndFallbackCategories(
                    visibleResolvedCategories,
                    catalogStrategySupport.buildFallbackLiveCategories(provider.id, categoryResult.items)
                )
                is CatalogStrategyResult.Partial -> catalogStrategySupport.mergePreferredAndFallbackCategories(
                    visibleResolvedCategories,
                    catalogStrategySupport.buildFallbackLiveCategories(provider.id, categoryResult.items)
                )
                else -> null
            },
            warnings = catalogStrategySupport.strategyWarnings(fullResult),
            strategyFeedback = XtreamStrategyFeedback(
                attemptedFullCatalog = true,
                fullCatalogUnsafe = (fullResult as? CatalogStrategyResult.Failure)?.error?.let(catalogStrategySupport::shouldAvoidFullCatalogStrategy) == true,
                segmentedStressDetected = catalogStrategySupport.sawSegmentedStress(
                    warnings = catalogStrategySupport.strategyWarnings(fullResult),
                    result = categoryResult,
                    sequentialWarnings = setOf(liveCategorySequentialModeWarning)
                )
            )
        )
    }

    suspend fun loadXtreamLiveFull(
        provider: Provider,
        api: XtreamProvider
    ): CatalogStrategyResult<Channel> {
        var fullChannels: List<Channel>? = null
        var fullChannelsFailure: Throwable? = null
        val fullChannelsElapsedMs = measureTimeMillis {
            when (val attempt = xtreamSupport.attemptNonCancellation {
                xtreamSupport.retryXtreamCatalogTransient(provider.id) {
                    xtreamSupport.executeXtreamRequest(provider.id, XtreamAdaptiveSyncPolicy.Stage.HEAVY) {
                        api.getLiveStreams().getOrThrow("Live streams")
                    }
                }
            }) {
                is Attempt.Success -> fullChannels = attempt.value
                is Attempt.Failure -> fullChannelsFailure = attempt.error
            }
        }
        if (!fullChannels.isNullOrEmpty()) {
            Log.i(
                XTREAM_LIVE_STRATEGY_TAG,
                "Xtream live full catalog succeeded for provider ${provider.id} in ${fullChannelsElapsedMs}ms with ${fullChannels!!.size} items."
            )
            return CatalogStrategyResult.Success("full", fullChannels!!)
        }
        return if (fullChannelsFailure != null) {
            xtreamSupport.logXtreamCatalogFallback(
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
            xtreamSupport.logXtreamCatalogFallback(
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

    suspend fun loadXtreamLiveByCategory(
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

        val executionPlan = xtreamSupport.executeCategoryRecoveryPlan(
            provider = provider,
            categories = categories,
            initialConcurrency = concurrency,
            sectionLabel = "Live TV",
            sequentialModeWarning = liveCategorySequentialModeWarning,
            onProgress = onProgress,
            fetch = { category -> xtreamFetcher.fetchLiveCategoryOutcome(provider, api, category) }
        )
        var timedOutcomes = executionPlan.outcomes

        val categoryOutcomes = timedOutcomes.map { it.outcome }
        val failureCount = timedOutcomes.count { it.outcome is CategoryFetchOutcome.Failure }
        val fastFailureCount = timedOutcomes.count {
            it.elapsedMs <= 5_000L && it.outcome is CategoryFetchOutcome.Failure
        }
        val downgradeRecommended = catalogStrategySupport.shouldDowngradeCategorySync(
            categories.size,
            failureCount,
            fastFailureCount,
            categoryOutcomes
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
                XTREAM_LIVE_STRATEGY_TAG,
                "Xtream live category sync is continuing in sequential mode for failed categories on provider ${provider.id}."
            )
            timedOutcomes = xtreamSupport.continueFailedCategoryOutcomes(
                provider = provider,
                timedOutcomes = timedOutcomes,
                fetchSequentially = { category -> xtreamFetcher.fetchLiveCategoryOutcome(provider, api, category) }
            )
            fallbackWarnings = (fallbackWarnings + if (downgradeRecommended) listOf(liveCategorySequentialModeWarning) else emptyList()).distinct()
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
        Log.i(
            XTREAM_LIVE_STRATEGY_TAG,
            "Xtream live category strategy summary for provider ${provider.id}: successful=$successfulCategories empty=$emptyCategories failed=$failedCategories dedupedChannels=${channels.size} concurrency=$concurrency"
        )

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
}
