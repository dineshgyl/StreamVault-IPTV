package com.streamvault.data.sync

import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.CatalogSyncDao
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.local.entity.CategoryImportStageEntity
import com.streamvault.data.local.entity.ChannelEntity
import com.streamvault.data.local.entity.ChannelImportStageEntity
import com.streamvault.data.local.entity.MovieEntity
import com.streamvault.data.local.entity.MovieImportStageEntity
import com.streamvault.data.local.entity.SeriesEntity
import com.streamvault.data.local.entity.SeriesImportStageEntity
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

internal class SyncCatalogStore(
    private val movieDao: MovieDao,
    private val catalogSyncDao: CatalogSyncDao,
    private val transactionRunner: DatabaseTransactionRunner
) {
    private val sessionIds = AtomicLong(System.currentTimeMillis())
    private val digest = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }

    fun newSessionId(): Long = sessionIds.incrementAndGet()

    suspend fun replaceLiveCatalog(providerId: Long, categories: List<CategoryEntity>?, channels: List<ChannelEntity>): Int {
        val sessionId = newSessionId()
        val stagedChannels = buildChannelStages(providerId, sessionId, channels)
        try {
            clearProviderStaging(providerId)
            categories?.let { stageCategories(providerId, sessionId, it) }
            insertStageRows(stagedChannels, catalogSyncDao::insertChannelStages)
            transactionRunner.inTransaction {
                categories?.let { applyCategories(providerId, sessionId, "LIVE") }
                applyChannels(providerId, sessionId)
            }
            return stagedChannels.size
        } finally {
            clearSession(providerId, sessionId)
        }
    }

    suspend fun replaceMovieCatalog(providerId: Long, categories: List<CategoryEntity>?, movies: Sequence<MovieEntity>): Int {
        val sessionId = newSessionId()
        try {
            clearProviderStaging(providerId)
            categories?.let { stageCategories(providerId, sessionId, it) }
            val acceptedCount = stageMovieSequence(providerId, sessionId, movies)
            transactionRunner.inTransaction {
                categories?.let { applyCategories(providerId, sessionId, "MOVIE") }
                applyMovies(providerId, sessionId)
            }
            movieDao.restoreWatchProgress(providerId)
            return acceptedCount
        } finally {
            clearSession(providerId, sessionId)
        }
    }

    suspend fun replaceSeriesCatalog(providerId: Long, categories: List<CategoryEntity>?, series: Sequence<SeriesEntity>): Int {
        val sessionId = newSessionId()
        try {
            clearProviderStaging(providerId)
            categories?.let { stageCategories(providerId, sessionId, it) }
            val acceptedCount = stageSeriesSequence(providerId, sessionId, series)
            transactionRunner.inTransaction {
                categories?.let { applyCategories(providerId, sessionId, "SERIES") }
                applySeries(providerId, sessionId)
            }
            return acceptedCount
        } finally {
            clearSession(providerId, sessionId)
        }
    }

    suspend fun applyStagedMovieCatalog(providerId: Long, sessionId: Long, categories: List<CategoryEntity>?) {
        try {
            transactionRunner.inTransaction {
                categories?.let { stageCategories(providerId, sessionId, it) }
                categories?.let { applyCategories(providerId, sessionId, "MOVIE") }
                applyMovies(providerId, sessionId)
            }
            movieDao.restoreWatchProgress(providerId)
        } finally {
            clearSession(providerId, sessionId)
        }
    }

    suspend fun applyStagedSeriesCatalog(providerId: Long, sessionId: Long, categories: List<CategoryEntity>?) {
        try {
            transactionRunner.inTransaction {
                categories?.let { stageCategories(providerId, sessionId, it) }
                categories?.let { applyCategories(providerId, sessionId, "SERIES") }
                applySeries(providerId, sessionId)
            }
        } finally {
            clearSession(providerId, sessionId)
        }
    }

    suspend fun replaceCategories(providerId: Long, type: String, categories: List<CategoryEntity>) {
        val sessionId = newSessionId()
        try {
            clearProviderStaging(providerId)
            stageCategories(providerId, sessionId, categories)
            transactionRunner.inTransaction {
                applyCategories(providerId, sessionId, type)
            }
        } finally {
            clearSession(providerId, sessionId)
        }
    }

    suspend fun stageChannelBatch(providerId: Long, sessionId: Long, channels: List<ChannelEntity>) {
        insertStageRows(buildChannelStages(providerId, sessionId, channels), catalogSyncDao::insertChannelStages)
    }

    suspend fun stageMovieBatch(providerId: Long, sessionId: Long, movies: List<MovieEntity>) {
        insertStageRows(buildMovieStages(providerId, sessionId, movies), catalogSyncDao::insertMovieStages)
    }

    suspend fun stageSeriesBatch(providerId: Long, sessionId: Long, series: List<SeriesEntity>) {
        insertStageRows(buildSeriesStages(providerId, sessionId, series), catalogSyncDao::insertSeriesStages)
    }

    suspend fun stageCategories(providerId: Long, sessionId: Long, categories: List<CategoryEntity>) {
        val rows = categories
            .distinctBy { it.categoryId to it.type }
            .map { category ->
                CategoryImportStageEntity(
                    sessionId = sessionId,
                    providerId = providerId,
                    categoryId = category.categoryId,
                    name = category.name,
                    parentId = category.parentId,
                    type = category.type,
                    isAdult = category.isAdult,
                    syncFingerprint = categoryFingerprint(category)
                )
            }
        insertStageRows(rows, catalogSyncDao::insertCategoryStages)
    }

    suspend fun finalizeStagedImport(
        providerId: Long,
        sessionId: Long,
        liveCategories: List<CategoryEntity>?,
        movieCategories: List<CategoryEntity>?,
        includeLive: Boolean,
        includeMovies: Boolean
    ) {
        transactionRunner.inTransaction {
            if (includeLive) {
                stageCategories(providerId, sessionId, liveCategories.orEmpty())
                applyCategories(providerId, sessionId, "LIVE")
                applyChannels(providerId, sessionId)
            }
            if (includeMovies) {
                stageCategories(providerId, sessionId, movieCategories.orEmpty())
                applyCategories(providerId, sessionId, "MOVIE")
                applyMovies(providerId, sessionId)
            }
        }
        if (includeMovies) {
            movieDao.restoreWatchProgress(providerId)
        }
    }

    suspend fun discardStagedImport(providerId: Long, sessionId: Long) {
        clearSession(providerId, sessionId)
    }

    suspend fun clearProviderStaging(providerId: Long) {
        catalogSyncDao.clearProviderChannelStages(providerId)
        catalogSyncDao.clearProviderMovieStages(providerId)
        catalogSyncDao.clearProviderSeriesStages(providerId)
        catalogSyncDao.clearProviderCategoryStages(providerId)
    }

    private suspend fun applyCategories(providerId: Long, sessionId: Long, type: String) {
        catalogSyncDao.updateChangedCategoriesFromStage(providerId, sessionId, type)
        catalogSyncDao.insertMissingCategoriesFromStage(providerId, sessionId, type)
        catalogSyncDao.deleteStaleCategoriesForStage(providerId, sessionId, type)
    }

    private suspend fun applyChannels(providerId: Long, sessionId: Long) {
        catalogSyncDao.updateChangedChannelsFromStage(providerId, sessionId)
        catalogSyncDao.insertMissingChannelsFromStage(providerId, sessionId)
        catalogSyncDao.deleteStaleChannelsForStage(providerId, sessionId)
    }

    private suspend fun applyMovies(providerId: Long, sessionId: Long) {
        catalogSyncDao.updateChangedMoviesFromStage(providerId, sessionId)
        catalogSyncDao.insertMissingMoviesFromStage(providerId, sessionId)
        catalogSyncDao.deleteStaleMoviesForStage(providerId, sessionId)
    }

    private suspend fun applySeries(providerId: Long, sessionId: Long) {
        catalogSyncDao.updateChangedSeriesFromStage(providerId, sessionId)
        catalogSyncDao.insertMissingSeriesFromStage(providerId, sessionId)
        catalogSyncDao.deleteStaleSeriesForStage(providerId, sessionId)
    }

    private suspend fun clearSession(providerId: Long, sessionId: Long) {
        catalogSyncDao.clearChannelStages(providerId, sessionId)
        catalogSyncDao.clearMovieStages(providerId, sessionId)
        catalogSyncDao.clearSeriesStages(providerId, sessionId)
        catalogSyncDao.clearCategoryStages(providerId, sessionId)
    }

    private fun buildChannelStages(
        providerId: Long,
        sessionId: Long,
        channels: List<ChannelEntity>
    ): List<ChannelImportStageEntity> {
        return channels
            .distinctBy { it.streamId }
            .map { channel ->
                ChannelImportStageEntity(
                    sessionId = sessionId,
                    providerId = providerId,
                    streamId = channel.streamId,
                    name = channel.name,
                    logoUrl = channel.logoUrl,
                    groupTitle = channel.groupTitle,
                    categoryId = channel.categoryId,
                    categoryName = channel.categoryName,
                    streamUrl = channel.streamUrl,
                    epgChannelId = channel.epgChannelId,
                    number = channel.number,
                    catchUpSupported = channel.catchUpSupported,
                    catchUpDays = channel.catchUpDays,
                    catchUpSource = channel.catchUpSource,
                    isAdult = channel.isAdult,
                    logicalGroupId = channel.logicalGroupId,
                    errorCount = channel.errorCount,
                    syncFingerprint = channelFingerprint(channel)
                )
            }
    }

    private fun buildMovieStages(
        providerId: Long,
        sessionId: Long,
        movies: List<MovieEntity>
    ): List<MovieImportStageEntity> {
        return movies
            .distinctBy { it.streamId }
            .map { movie ->
                MovieImportStageEntity(
                    sessionId = sessionId,
                    providerId = providerId,
                    streamId = movie.streamId,
                    name = movie.name,
                    posterUrl = movie.posterUrl,
                    backdropUrl = movie.backdropUrl,
                    categoryId = movie.categoryId,
                    categoryName = movie.categoryName,
                    streamUrl = movie.streamUrl,
                    containerExtension = movie.containerExtension,
                    plot = movie.plot,
                    cast = movie.cast,
                    director = movie.director,
                    genre = movie.genre,
                    releaseDate = movie.releaseDate,
                    duration = movie.duration,
                    durationSeconds = movie.durationSeconds,
                    rating = movie.rating,
                    year = movie.year,
                    tmdbId = movie.tmdbId,
                    youtubeTrailer = movie.youtubeTrailer,
                    isAdult = movie.isAdult,
                    syncFingerprint = movieFingerprint(movie)
                )
            }
    }

    private fun buildSeriesStages(
        providerId: Long,
        sessionId: Long,
        series: List<SeriesEntity>
    ): List<SeriesImportStageEntity> {
        return series
            .distinctBy { it.seriesId }
            .map { item ->
                SeriesImportStageEntity(
                    sessionId = sessionId,
                    providerId = providerId,
                    seriesId = item.seriesId,
                    name = item.name,
                    posterUrl = item.posterUrl,
                    backdropUrl = item.backdropUrl,
                    categoryId = item.categoryId,
                    categoryName = item.categoryName,
                    plot = item.plot,
                    cast = item.cast,
                    director = item.director,
                    genre = item.genre,
                    releaseDate = item.releaseDate,
                    rating = item.rating,
                    tmdbId = item.tmdbId,
                    youtubeTrailer = item.youtubeTrailer,
                    episodeRunTime = item.episodeRunTime,
                    lastModified = item.lastModified,
                    isAdult = item.isAdult,
                    syncFingerprint = seriesFingerprint(item)
                )
            }
    }

    private suspend fun stageMovieSequence(
        providerId: Long,
        sessionId: Long,
        movies: Sequence<MovieEntity>
    ): Int {
        return stageDistinctRows(
            items = movies,
            keySelector = { movie -> movie.streamId },
            stageBuilder = { movie -> movieStageEntity(providerId, sessionId, movie) },
            insert = catalogSyncDao::insertMovieStages
        )
    }

    private suspend fun stageSeriesSequence(
        providerId: Long,
        sessionId: Long,
        series: Sequence<SeriesEntity>
    ): Int {
        return stageDistinctRows(
            items = series,
            keySelector = { item -> item.seriesId },
            stageBuilder = { item -> seriesStageEntity(providerId, sessionId, item) },
            insert = catalogSyncDao::insertSeriesStages
        )
    }

    private suspend fun <T, K, S> stageDistinctRows(
        items: Sequence<T>,
        keySelector: (T) -> K,
        stageBuilder: (T) -> S,
        insert: suspend (List<S>) -> Unit
    ): Int {
        val seenKeys = HashSet<K>()
        val batch = ArrayList<S>(STAGE_BATCH_SIZE)
        var acceptedCount = 0

        items.forEach { item ->
            if (!seenKeys.add(keySelector(item))) {
                return@forEach
            }
            batch += stageBuilder(item)
            acceptedCount++
            if (batch.size >= STAGE_BATCH_SIZE) {
                insert(batch)
                batch.clear()
            }
        }

        if (batch.isNotEmpty()) {
            insert(batch)
        }

        return acceptedCount
    }

    private suspend fun <T> insertStageRows(
        rows: List<T>,
        insert: suspend (List<T>) -> Unit
    ) {
        rows.chunked(STAGE_BATCH_SIZE).forEach { chunk ->
            if (chunk.isNotEmpty()) {
                insert(chunk)
            }
        }
    }

    private fun movieStageEntity(
        providerId: Long,
        sessionId: Long,
        movie: MovieEntity
    ): MovieImportStageEntity {
        return MovieImportStageEntity(
            sessionId = sessionId,
            providerId = providerId,
            streamId = movie.streamId,
            name = movie.name,
            posterUrl = movie.posterUrl,
            backdropUrl = movie.backdropUrl,
            categoryId = movie.categoryId,
            categoryName = movie.categoryName,
            streamUrl = movie.streamUrl,
            containerExtension = movie.containerExtension,
            plot = movie.plot,
            cast = movie.cast,
            director = movie.director,
            genre = movie.genre,
            releaseDate = movie.releaseDate,
            duration = movie.duration,
            durationSeconds = movie.durationSeconds,
            rating = movie.rating,
            year = movie.year,
            tmdbId = movie.tmdbId,
            youtubeTrailer = movie.youtubeTrailer,
            isAdult = movie.isAdult,
            syncFingerprint = movieFingerprint(movie)
        )
    }

    private fun seriesStageEntity(
        providerId: Long,
        sessionId: Long,
        item: SeriesEntity
    ): SeriesImportStageEntity {
        return SeriesImportStageEntity(
            sessionId = sessionId,
            providerId = providerId,
            seriesId = item.seriesId,
            name = item.name,
            posterUrl = item.posterUrl,
            backdropUrl = item.backdropUrl,
            categoryId = item.categoryId,
            categoryName = item.categoryName,
            plot = item.plot,
            cast = item.cast,
            director = item.director,
            genre = item.genre,
            releaseDate = item.releaseDate,
            rating = item.rating,
            tmdbId = item.tmdbId,
            youtubeTrailer = item.youtubeTrailer,
            episodeRunTime = item.episodeRunTime,
            lastModified = item.lastModified,
            isAdult = item.isAdult,
            syncFingerprint = seriesFingerprint(item)
        )
    }

    private fun channelFingerprint(channel: ChannelEntity): String {
        return fingerprint(
            normalizeText(channel.name),
            normalizeUrl(channel.logoUrl),
            normalizeText(channel.groupTitle),
            channel.categoryId?.toString().orEmpty(),
            normalizeText(channel.categoryName),
            normalizeUrl(channel.streamUrl),
            normalizeText(channel.epgChannelId),
            channel.number.toString(),
            channel.catchUpSupported.toString(),
            channel.catchUpDays.toString(),
            normalizeUrl(channel.catchUpSource),
            channel.isAdult.toString()
        )
    }

    private fun movieFingerprint(movie: MovieEntity): String {
        return fingerprint(
            normalizeText(movie.name),
            normalizeUrl(movie.posterUrl),
            normalizeUrl(movie.backdropUrl),
            movie.categoryId?.toString().orEmpty(),
            normalizeText(movie.categoryName),
            normalizeUrl(movie.streamUrl),
            normalizeText(movie.containerExtension),
            normalizeText(movie.plot),
            normalizeText(movie.cast),
            normalizeText(movie.director),
            normalizeText(movie.genre),
            normalizeText(movie.releaseDate),
            normalizeText(movie.duration),
            movie.durationSeconds.toString(),
            movie.rating.toString(),
            normalizeText(movie.year),
            movie.tmdbId?.toString().orEmpty(),
            normalizeUrl(movie.youtubeTrailer),
            movie.isAdult.toString()
        )
    }

    private fun seriesFingerprint(series: SeriesEntity): String {
        return fingerprint(
            normalizeText(series.name),
            normalizeUrl(series.posterUrl),
            normalizeUrl(series.backdropUrl),
            series.categoryId?.toString().orEmpty(),
            normalizeText(series.categoryName),
            normalizeText(series.plot),
            normalizeText(series.cast),
            normalizeText(series.director),
            normalizeText(series.genre),
            normalizeText(series.releaseDate),
            series.rating.toString(),
            series.tmdbId?.toString().orEmpty(),
            normalizeUrl(series.youtubeTrailer),
            normalizeText(series.episodeRunTime),
            series.lastModified.toString(),
            series.isAdult.toString()
        )
    }

    private fun categoryFingerprint(category: CategoryEntity): String {
        return fingerprint(
            category.categoryId.toString(),
            normalizeText(category.name),
            category.parentId?.toString().orEmpty(),
            category.type.name,
            category.isAdult.toString()
        )
    }

    private fun fingerprint(vararg values: String): String {
        val messageDigest = requireNotNull(digest.get())
        messageDigest.reset()
        values.forEach { value ->
            messageDigest.update(value.toByteArray(Charsets.UTF_8))
            messageDigest.update(0)
        }
        return messageDigest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun normalizeText(value: String?): String {
        return value
            .orEmpty()
            .trim()
            .replace(Regex("\\s+"), " ")
            .lowercase()
    }

    private fun normalizeUrl(value: String?): String {
        val url = value?.trim().orEmpty()
        if (url.isEmpty()) {
            return ""
        }
        val parsed = runCatching { URI(url) }.getOrNull()
        val scheme = parsed?.scheme?.lowercase().orEmpty()
        val host = parsed?.host?.lowercase().orEmpty()
        val path = parsed?.path.orEmpty().trimEnd('/')
        val query = parsed?.query
            ?.split('&')
            ?.mapNotNull { pair ->
                val key = pair.substringBefore('=').lowercase()
                val normalizedValue = pair.substringAfter('=', "")
                when (key) {
                    "token", "auth", "password", "username" -> null
                    else -> "$key=$normalizedValue"
                }
            }
            ?.sorted()
            ?.joinToString("&")
            .orEmpty()
        return listOf(scheme, host, path, query)
            .joinToString("|")
            .ifBlank { url.lowercase() }
    }

    private companion object {
        private const val STAGE_BATCH_SIZE = 500
    }
}
