package com.streamvault.data.sync

import com.streamvault.data.local.entity.ChannelEntity
import com.streamvault.data.local.entity.MovieEntity
import com.streamvault.data.parser.M3uParser
import com.streamvault.data.util.AdultContentClassifier
import com.streamvault.data.util.UrlSecurityPolicy
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Provider
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.util.zip.GZIPInputStream

private const val M3U_PROGRESS_INTERVAL = 5_000

internal class SyncManagerM3uImporter(
    private val m3uParser: M3uParser,
    private val okHttpClient: OkHttpClient,
    private val syncCatalogStore: SyncCatalogStore,
    private val retryTransient: suspend (suspend () -> Unit) -> Unit,
    private val progress: (Long, ((String) -> Unit)?, String) -> Unit
) {
    suspend fun importPlaylist(
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
        var nextMilestone = M3U_PROGRESS_INTERVAL
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
                            nextMilestone += M3U_PROGRESS_INTERVAL
                        }
                        if (!UrlSecurityPolicy.isAllowedStreamEntryUrl(entry.url)) {
                            insecureStreamCount++
                            return@parseStreaming
                        }

                        val safeLogoUrl = UrlSecurityPolicy.sanitizeImportedAssetUrl(entry.tvgLogo)
                        val safeCatchUpSource = UrlSecurityPolicy.sanitizeImportedAssetUrl(entry.catchUpSource)

                        if (provider.m3uVodClassificationEnabled && M3uParser.isVodEntry(entry)) {
                            if (!includeMovies) return@parseStreaming
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
                            if (seenMovieStreamIds?.add(stableStreamId) != true) return@parseStreaming
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
                            if (!includeLive) return@parseStreaming
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
                            if (seenLiveStreamIds?.add(stableStreamId) != true) return@parseStreaming
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
                                    catchUpSupported = !entry.catchUp.isNullOrBlank() ||
                                        !entry.catchUpSource.isNullOrBlank() ||
                                        !entry.timeshift.isNullOrBlank(),
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
        if (response.isSuccessful) return
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
        if (batch.isEmpty()) return
        syncCatalogStore.stageChannelBatch(providerId, sessionId, batch)
        batch.clear()
    }

    private suspend fun flushMovieBatch(providerId: Long, sessionId: Long, batch: MutableList<MovieEntity>) {
        if (batch.isEmpty()) return
        syncCatalogStore.stageMovieBatch(providerId, sessionId, batch)
        batch.clear()
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
        return value.orEmpty().lowercase().replace(Regex("\\s+"), " ").trim()
    }
}
