package com.streamvault.data.sync

import android.util.Log
import com.streamvault.data.remote.dto.XtreamCategory
import com.streamvault.data.remote.dto.XtreamSeriesItem
import com.streamvault.data.remote.dto.XtreamStream
import com.streamvault.data.remote.xtream.XtreamApiService
import com.streamvault.data.remote.xtream.XtreamProvider
import com.streamvault.data.remote.xtream.XtreamUrlFactory
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.Series
import kotlin.system.measureTimeMillis

private const val XTREAM_FETCHER_TAG = "SyncManager"

internal class SyncManagerXtreamFetcher(
    private val xtreamCatalogApiService: XtreamApiService,
    private val xtreamSupport: SyncManagerXtreamSupport,
    private val sanitizeThrowableMessage: (Throwable?) -> String,
    private val pageSize: Int
) {
    suspend fun fetchLiveCategoryOutcome(
        provider: Provider,
        api: XtreamProvider,
        category: XtreamCategory
    ): TimedCategoryOutcome<Channel> {
        var rawStreams: List<XtreamStream> = emptyList()
        var categoryFailure: Throwable? = null
        val elapsedMs = measureTimeMillis {
            when (val attempt = xtreamSupport.attemptNonCancellation {
                xtreamSupport.retryXtreamCatalogTransient(provider.id) {
                    xtreamSupport.executeXtreamRequest(provider.id, XtreamAdaptiveSyncPolicy.Stage.CATEGORY) {
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
                Log.w(
                    XTREAM_FETCHER_TAG,
                    "Xtream live category '${category.categoryName}' failed after ${elapsedMs}ms: ${sanitizeThrowableMessage(categoryFailure)}"
                )
                CategoryFetchOutcome.Failure(category.categoryName, categoryFailure!!)
            }
            rawStreams.isEmpty() -> {
                Log.i(
                    XTREAM_FETCHER_TAG,
                    "Xtream live category '${category.categoryName}' completed in ${elapsedMs}ms with a valid empty result."
                )
                CategoryFetchOutcome.Empty(category.categoryName)
            }
            else -> {
                Log.i(
                    XTREAM_FETCHER_TAG,
                    "Xtream live category '${category.categoryName}' completed in ${elapsedMs}ms with ${rawStreams.size} raw items."
                )
                CategoryFetchOutcome.Success(category.categoryName, api.mapLiveStreamsResponse(rawStreams))
            }
        }
        return TimedCategoryOutcome(category, outcome, elapsedMs)
    }

    suspend fun fetchMovieCategoryOutcome(
        provider: Provider,
        api: XtreamProvider,
        category: XtreamCategory
    ): TimedCategoryOutcome<Movie> {
        var rawStreams: List<XtreamStream> = emptyList()
        var categoryFailure: Throwable? = null
        val elapsedMs = measureTimeMillis {
            when (val attempt = xtreamSupport.attemptNonCancellation {
                xtreamSupport.withMovieRequestTimeout("movie category '${category.categoryName}'") {
                    xtreamSupport.retryXtreamCatalogTransient(provider.id) {
                        xtreamSupport.executeXtreamRequest(provider.id, XtreamAdaptiveSyncPolicy.Stage.CATEGORY) {
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
                Log.w(
                    XTREAM_FETCHER_TAG,
                    "Xtream movie category '${category.categoryName}' failed after ${elapsedMs}ms: ${sanitizeThrowableMessage(categoryFailure)}"
                )
                CategoryFetchOutcome.Failure(category.categoryName, categoryFailure!!)
            }
            rawStreams.isEmpty() -> {
                Log.i(
                    XTREAM_FETCHER_TAG,
                    "Xtream movie category '${category.categoryName}' completed in ${elapsedMs}ms with a valid empty result."
                )
                CategoryFetchOutcome.Empty(category.categoryName)
            }
            else -> {
                Log.i(
                    XTREAM_FETCHER_TAG,
                    "Xtream movie category '${category.categoryName}' completed in ${elapsedMs}ms with ${rawStreams.size} raw items."
                )
                CategoryFetchOutcome.Success(category.categoryName, api.mapVodStreamsResponse(rawStreams))
            }
        }
        return TimedCategoryOutcome(category, outcome, elapsedMs)
    }

    suspend fun fetchSeriesCategoryOutcome(
        provider: Provider,
        api: XtreamProvider,
        category: XtreamCategory
    ): TimedCategoryOutcome<Series> {
        var rawSeries: List<XtreamSeriesItem> = emptyList()
        var categoryFailure: Throwable? = null
        val elapsedMs = measureTimeMillis {
            when (val attempt = xtreamSupport.attemptNonCancellation {
                xtreamSupport.withSeriesRequestTimeout("series category '${category.categoryName}'") {
                    xtreamSupport.retryXtreamCatalogTransient(provider.id) {
                        xtreamSupport.executeXtreamRequest(provider.id, XtreamAdaptiveSyncPolicy.Stage.CATEGORY) {
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
                Log.w(
                    XTREAM_FETCHER_TAG,
                    "Xtream series category '${category.categoryName}' failed after ${elapsedMs}ms: ${sanitizeThrowableMessage(categoryFailure)}"
                )
                CategoryFetchOutcome.Failure(category.categoryName, categoryFailure!!)
            }
            rawSeries.isEmpty() -> {
                Log.i(
                    XTREAM_FETCHER_TAG,
                    "Xtream series category '${category.categoryName}' completed in ${elapsedMs}ms with a valid empty result."
                )
                CategoryFetchOutcome.Empty(category.categoryName)
            }
            else -> {
                Log.i(
                    XTREAM_FETCHER_TAG,
                    "Xtream series category '${category.categoryName}' completed in ${elapsedMs}ms with ${rawSeries.size} raw items."
                )
                CategoryFetchOutcome.Success(category.categoryName, api.mapSeriesListResponse(rawSeries))
            }
        }
        return TimedCategoryOutcome(category, outcome, elapsedMs)
    }

    suspend fun fetchMoviePageOutcome(
        provider: Provider,
        api: XtreamProvider,
        page: Int
    ): TimedPageOutcome<Movie> {
        var rawStreams: List<XtreamStream> = emptyList()
        var pageFailure: Throwable? = null
        val elapsedMs = measureTimeMillis {
            when (val attempt = xtreamSupport.attemptNonCancellation {
                xtreamSupport.withMovieRequestTimeout("movie page $page") {
                    xtreamSupport.retryXtreamCatalogTransient(provider.id) {
                        xtreamSupport.executeXtreamRequest(provider.id, XtreamAdaptiveSyncPolicy.Stage.PAGED) {
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
                    XTREAM_FETCHER_TAG,
                    "Xtream paged movie request failed for provider ${provider.id} on page $page after ${elapsedMs}ms: ${sanitizeThrowableMessage(pageFailure)}"
                )
                PageFetchOutcome.Failure(page, pageFailure!!)
            }
            rawStreams.isEmpty() -> {
                Log.i(
                    XTREAM_FETCHER_TAG,
                    "Xtream paged movie request for provider ${provider.id} page $page completed in ${elapsedMs}ms with a valid empty result."
                )
                PageFetchOutcome.Empty(page)
            }
            else -> {
                Log.i(
                    XTREAM_FETCHER_TAG,
                    "Xtream paged movie request for provider ${provider.id} page $page completed in ${elapsedMs}ms with ${rawStreams.size} raw items."
                )
                PageFetchOutcome.Success(api.mapVodStreamsResponse(rawStreams), rawStreams.size)
            }
        }
        return TimedPageOutcome(page = page, outcome = outcome, elapsedMs = elapsedMs)
    }

    suspend fun fetchSeriesPageOutcome(
        provider: Provider,
        api: XtreamProvider,
        page: Int
    ): TimedPageOutcome<Series> {
        var rawSeries: List<XtreamSeriesItem> = emptyList()
        var pageFailure: Throwable? = null
        val elapsedMs = measureTimeMillis {
            when (val attempt = xtreamSupport.attemptNonCancellation {
                xtreamSupport.withSeriesRequestTimeout("series page $page") {
                    xtreamSupport.retryXtreamCatalogTransient(provider.id) {
                        xtreamSupport.executeXtreamRequest(provider.id, XtreamAdaptiveSyncPolicy.Stage.PAGED) {
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
                    XTREAM_FETCHER_TAG,
                    "Xtream paged series request failed for provider ${provider.id} on page $page after ${elapsedMs}ms: ${sanitizeThrowableMessage(pageFailure)}"
                )
                PageFetchOutcome.Failure(page, pageFailure!!)
            }
            rawSeries.isEmpty() -> {
                Log.i(
                    XTREAM_FETCHER_TAG,
                    "Xtream paged series request for provider ${provider.id} page $page completed in ${elapsedMs}ms with a valid empty result."
                )
                PageFetchOutcome.Empty(page)
            }
            else -> {
                Log.i(
                    XTREAM_FETCHER_TAG,
                    "Xtream paged series request for provider ${provider.id} page $page completed in ${elapsedMs}ms with ${rawSeries.size} raw items."
                )
                PageFetchOutcome.Success(api.mapSeriesListResponse(rawSeries), rawSeries.size)
            }
        }
        return TimedPageOutcome(page = page, outcome = outcome, elapsedMs = elapsedMs)
    }

    private fun paginationParamsForPage(page: Int): Map<String, String> {
        return mapOf(
            "page" to page.toString(),
            "limit" to pageSize.toString(),
            "offset" to ((page - 1) * pageSize).toString(),
            "items_per_page" to pageSize.toString()
        )
    }
}
