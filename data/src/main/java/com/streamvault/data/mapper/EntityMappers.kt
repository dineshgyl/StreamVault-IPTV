package com.streamvault.data.mapper

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.streamvault.data.local.entity.*
import com.streamvault.domain.model.*

private val qualityOptionsGson = Gson()
private val channelQualityOptionsType = object : TypeToken<List<ChannelQualityOption>>() {}.type
private val providerAllowedOutputFormatsType = object : TypeToken<List<String>>() {}.type

// ── Provider ───────────────────────────────────────────────────────

fun ProviderEntity.toDomain() = Provider(
    id = id,
    name = name,
    type = type,
    serverUrl = serverUrl,
    username = username,
    password = password,
    m3uUrl = m3uUrl,
    epgUrl = epgUrl,
    isActive = isActive,
    maxConnections = maxConnections,
    expirationDate = expirationDate,
    apiVersion = apiVersion,
    allowedOutputFormats = decodeAllowedOutputFormats(allowedOutputFormatsJson),
    xtreamFastSyncEnabled = xtreamFastSyncEnabled,
    status = status,
    lastSyncedAt = lastSyncedAt,
    createdAt = createdAt
)

fun Provider.toEntity() = ProviderEntity(
    id = id,
    name = name,
    type = type,
    serverUrl = serverUrl,
    username = username,
    password = password,
    m3uUrl = m3uUrl,
    epgUrl = epgUrl,
    isActive = isActive,
    maxConnections = maxConnections,
    expirationDate = expirationDate,
    apiVersion = apiVersion,
    allowedOutputFormatsJson = encodeAllowedOutputFormats(allowedOutputFormats),
    xtreamFastSyncEnabled = xtreamFastSyncEnabled,
    status = status,
    lastSyncedAt = lastSyncedAt,
    createdAt = createdAt
)

// ── Channel ────────────────────────────────────────────────────────

fun ChannelEntity.toDomain(): Channel {
    val qualityOptions = decodeQualityOptions(qualityOptionsJson)
    return Channel(
        id = id,
        name = name,
        logoUrl = logoUrl,
        groupTitle = groupTitle,
        categoryId = categoryId,
        categoryName = categoryName,
        streamUrl = streamUrl,
        epgChannelId = epgChannelId,
        number = number,
        catchUpSupported = catchUpSupported,
        catchUpDays = catchUpDays,
        catchUpSource = catchUpSource,
        providerId = providerId,
        isAdult = isAdult,
        isUserProtected = isUserProtected,
        logicalGroupId = logicalGroupId,
        errorCount = errorCount,
        qualityOptions = qualityOptions,
        alternativeStreams = qualityOptions.mapNotNull { it.url }.filter { it != streamUrl }.distinct(),
        streamId = streamId
    )
}

fun Channel.toEntity() = ChannelEntity(
    id = id,
    streamId = streamId.takeIf { it > 0 } ?: id,
    name = name,
    logoUrl = logoUrl,
    groupTitle = groupTitle,
    categoryId = categoryId,
    categoryName = categoryName,
    streamUrl = streamUrl,
    epgChannelId = epgChannelId,
    number = number,
    catchUpSupported = catchUpSupported,
    catchUpDays = catchUpDays,
    catchUpSource = catchUpSource,
    providerId = providerId,
    isAdult = isAdult,
    isUserProtected = isUserProtected,
    logicalGroupId = logicalGroupId,
    errorCount = errorCount,
    qualityOptionsJson = encodeQualityOptions(qualityOptions)
)

// ── Movie ──────────────────────────────────────────────────────────

fun MovieEntity.toDomain() = Movie(
    id = id,
    name = name,
    posterUrl = posterUrl,
    backdropUrl = backdropUrl,
    categoryId = categoryId,
    categoryName = categoryName,
    streamUrl = streamUrl,
    containerExtension = containerExtension,
    plot = plot,
    cast = cast,
    director = director,
    genre = genre,
    releaseDate = releaseDate,
    duration = duration,
    durationSeconds = durationSeconds,
    rating = rating,
    year = year,
    tmdbId = tmdbId,
    youtubeTrailer = youtubeTrailer,
    providerId = providerId,
    watchProgress = watchProgress,
    lastWatchedAt = lastWatchedAt,
    isAdult = isAdult,
    isUserProtected = isUserProtected,
    streamId = streamId
)

fun MovieBrowseEntity.toDomain() = Movie(
    id = id,
    name = name,
    posterUrl = posterUrl,
    backdropUrl = null,
    categoryId = categoryId,
    categoryName = categoryName,
    streamUrl = streamUrl,
    containerExtension = containerExtension,
    plot = null,
    cast = null,
    director = null,
    genre = genre,
    releaseDate = releaseDate,
    duration = null,
    durationSeconds = durationSeconds,
    rating = rating,
    year = year,
    tmdbId = null,
    youtubeTrailer = null,
    providerId = providerId,
    watchProgress = watchProgress,
    lastWatchedAt = lastWatchedAt,
    isAdult = isAdult,
    isUserProtected = isUserProtected,
    streamId = streamId
)

fun Movie.toEntity() = MovieEntity(
    id = id,
    streamId = streamId.takeIf { it > 0 } ?: id,
    name = name,
    posterUrl = posterUrl,
    backdropUrl = backdropUrl,
    categoryId = categoryId,
    categoryName = categoryName,
    streamUrl = streamUrl,
    containerExtension = containerExtension,
    plot = plot,
    cast = cast,
    director = director,
    genre = genre,
    releaseDate = releaseDate,
    duration = duration,
    durationSeconds = durationSeconds,
    rating = rating,
    year = year,
    tmdbId = tmdbId,
    youtubeTrailer = youtubeTrailer,
    providerId = providerId,
    watchProgress = watchProgress,
    lastWatchedAt = lastWatchedAt,
    isAdult = isAdult,
    isUserProtected = isUserProtected
)

// ── Series ─────────────────────────────────────────────────────────

fun SeriesEntity.toDomain() = Series(
    id = id,
    name = name,
    posterUrl = posterUrl,
    backdropUrl = backdropUrl,
    categoryId = categoryId,
    categoryName = categoryName,
    plot = plot,
    cast = cast,
    director = director,
    genre = genre,
    releaseDate = releaseDate,
    rating = rating,
    tmdbId = tmdbId,
    youtubeTrailer = youtubeTrailer,
    episodeRunTime = episodeRunTime,
    lastModified = lastModified,
    providerId = providerId,
    isAdult = isAdult,
    isUserProtected = isUserProtected,
    seriesId = seriesId
)

fun SeriesBrowseEntity.toDomain() = Series(
    id = id,
    name = name,
    posterUrl = posterUrl,
    backdropUrl = null,
    categoryId = categoryId,
    categoryName = categoryName,
    plot = null,
    cast = null,
    director = null,
    genre = genre,
    releaseDate = releaseDate,
    rating = rating,
    tmdbId = null,
    youtubeTrailer = null,
    episodeRunTime = null,
    lastModified = lastModified,
    providerId = providerId,
    isAdult = isAdult,
    isUserProtected = isUserProtected,
    seriesId = seriesId
)

fun Series.toEntity() = SeriesEntity(
    id = id,
    seriesId = seriesId.takeIf { it > 0 } ?: id,
    name = name,
    posterUrl = posterUrl,
    backdropUrl = backdropUrl,
    categoryId = categoryId,
    categoryName = categoryName,
    plot = plot,
    cast = cast,
    director = director,
    genre = genre,
    releaseDate = releaseDate,
    rating = rating,
    tmdbId = tmdbId,
    youtubeTrailer = youtubeTrailer,
    episodeRunTime = episodeRunTime,
    lastModified = lastModified,
    providerId = providerId,
    isAdult = isAdult,
    isUserProtected = isUserProtected
)

// ── Episode ────────────────────────────────────────────────────────

fun EpisodeEntity.toDomain() = Episode(
    id = id,
    title = title,
    episodeNumber = episodeNumber,
    seasonNumber = seasonNumber,
    streamUrl = streamUrl,
    containerExtension = containerExtension,
    coverUrl = coverUrl,
    plot = plot,
    duration = duration,
    durationSeconds = durationSeconds,
    rating = rating,
    releaseDate = releaseDate,
    seriesId = seriesId,
    providerId = providerId,
    watchProgress = watchProgress,
    lastWatchedAt = lastWatchedAt,
    isAdult = isAdult,
    isUserProtected = isUserProtected,
    episodeId = episodeId
)

fun EpisodeBrowseEntity.toDomain() = Episode(
    id = id,
    title = title,
    episodeNumber = episodeNumber,
    seasonNumber = seasonNumber,
    streamUrl = streamUrl,
    containerExtension = containerExtension,
    coverUrl = coverUrl,
    plot = plot,
    duration = duration,
    durationSeconds = durationSeconds,
    rating = rating,
    releaseDate = releaseDate,
    seriesId = seriesId,
    providerId = providerId,
    watchProgress = watchProgress,
    lastWatchedAt = lastWatchedAt,
    isAdult = isAdult,
    isUserProtected = isUserProtected,
    episodeId = episodeId
)

fun Episode.toEntity() = EpisodeEntity(
    id = id,
    episodeId = episodeId.takeIf { it > 0 } ?: id,
    title = title,
    episodeNumber = episodeNumber,
    seasonNumber = seasonNumber,
    streamUrl = streamUrl,
    containerExtension = containerExtension,
    coverUrl = coverUrl,
    plot = plot,
    duration = duration,
    durationSeconds = durationSeconds,
    rating = rating,
    releaseDate = releaseDate,
    seriesId = seriesId,
    providerId = providerId,
    watchProgress = watchProgress,
    lastWatchedAt = lastWatchedAt,
    isAdult = isAdult,
    isUserProtected = isUserProtected
)

// ── Category ───────────────────────────────────────────────────────

fun CategoryEntity.toDomain() = com.streamvault.domain.model.Category(
    id = categoryId,
    roomId = id,
    name = name,
    parentId = parentId,
    type = type,
    isAdult = isAdult,
    isUserProtected = isUserProtected
)

fun com.streamvault.domain.model.Category.toEntity(providerId: Long) = CategoryEntity(
    categoryId = id,
    name = name,
    parentId = parentId,
    type = type,
    providerId = providerId,
    isAdult = isAdult,
    isUserProtected = isUserProtected
)

// ── Program ────────────────────────────────────────────────────────

fun ProgramEntity.toDomain() = Program(
    id = id,
    channelId = channelId,
    title = title,
    description = description,
    startTime = startTime,
    endTime = endTime,
    lang = lang,
    rating = rating,
    imageUrl = imageUrl,
    genre = genre,
    category = category,
    hasArchive = hasArchive,
    providerId = providerId
)

fun ProgramBrowseEntity.toDomain() = Program(
    id = id,
    channelId = channelId,
    title = title,
    description = description,
    startTime = startTime,
    endTime = endTime,
    lang = lang,
    rating = rating,
    imageUrl = imageUrl,
    genre = genre,
    category = category,
    hasArchive = hasArchive,
    providerId = providerId
)

fun Program.toEntity() = ProgramEntity(
    id = 0,
    channelId = channelId,
    title = title,
    description = description,
    startTime = startTime,
    endTime = endTime,
    lang = lang,
    rating = rating,
    imageUrl = imageUrl,
    genre = genre,
    category = category,
    hasArchive = hasArchive,
    providerId = providerId
)

// ── Favorite ───────────────────────────────────────────────────────

fun FavoriteEntity.toDomain() = Favorite(
    id = id,
    contentId = contentId,
    contentType = contentType,
    position = position,
    groupId = groupId,
    addedAt = addedAt
)

fun Favorite.toEntity() = FavoriteEntity(
    id = id,
    contentId = contentId,
    contentType = contentType,
    position = position,
    groupId = groupId,
    addedAt = addedAt
)

// ── Virtual Group ──────────────────────────────────────────────────

fun VirtualGroupEntity.toDomain() = VirtualGroup(
    id = id,
    name = name,
    iconEmoji = iconEmoji,
    position = position,
    createdAt = createdAt,
    contentType = contentType
)

fun VirtualGroup.toEntity() = VirtualGroupEntity(
    id = id,
    name = name,
    iconEmoji = iconEmoji,
    position = position,
    createdAt = createdAt,
    contentType = contentType
)

// ── Playback History ───────────────────────────────────────────────

fun PlaybackHistoryEntity.toDomain() = PlaybackHistory(
    id = id,
    contentId = contentId,
    contentType = contentType,
    providerId = providerId,
    title = title,
    posterUrl = posterUrl,
    streamUrl = streamUrl,
    resumePositionMs = resumePositionMs,
    totalDurationMs = totalDurationMs,
    lastWatchedAt = lastWatchedAt,
    watchCount = watchCount,
    watchedStatus = watchedStatus.toPlaybackWatchedStatus(),
    seriesId = seriesId,
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber
)

fun PlaybackHistoryLiteEntity.toDomain() = PlaybackHistory(
    id = id,
    contentId = contentId,
    contentType = contentType,
    providerId = providerId,
    title = title,
    posterUrl = posterUrl,
    streamUrl = streamUrl,
    resumePositionMs = resumePositionMs,
    totalDurationMs = totalDurationMs,
    lastWatchedAt = lastWatchedAt,
    watchCount = watchCount,
    watchedStatus = watchedStatus.toPlaybackWatchedStatus(),
    seriesId = seriesId,
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber
)

fun PlaybackHistory.toEntity() = PlaybackHistoryEntity(
    id = id,
    contentId = contentId,
    contentType = contentType,
    providerId = providerId,
    title = title,
    posterUrl = posterUrl,
    streamUrl = streamUrl,
    resumePositionMs = resumePositionMs,
    totalDurationMs = totalDurationMs,
    lastWatchedAt = lastWatchedAt,
    watchCount = watchCount,
    watchedStatus = watchedStatus.name,
    seriesId = seriesId,
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber
)

// ── Sync Metadata ──────────────────────────────────────────────────

fun SyncMetadataEntity.toDomain() = SyncMetadata(
    providerId = providerId,
    lastLiveSync = lastLiveSync,
    lastMovieSync = lastMovieSync,
    lastSeriesSync = lastSeriesSync,
    lastEpgSync = lastEpgSync,
    lastMovieAttempt = lastMovieAttempt,
    lastMovieSuccess = lastMovieSuccess,
    lastMoviePartial = lastMoviePartial,
    liveCount = liveCount,
    movieCount = movieCount,
    seriesCount = seriesCount,
    epgCount = epgCount,
    lastSyncStatus = lastSyncStatus,
    movieSyncMode = runCatching { com.streamvault.domain.model.VodSyncMode.valueOf(movieSyncMode) }
        .getOrDefault(com.streamvault.domain.model.VodSyncMode.UNKNOWN),
    movieWarningsCount = movieWarningsCount,
    movieCatalogStale = movieCatalogStale,
    liveAvoidFullUntil = liveAvoidFullUntil,
    movieAvoidFullUntil = movieAvoidFullUntil,
    seriesAvoidFullUntil = seriesAvoidFullUntil,
    liveSequentialFailuresRemembered = liveSequentialFailuresRemembered,
    liveHealthySyncStreak = liveHealthySyncStreak,
    movieParallelFailuresRemembered = movieParallelFailuresRemembered,
    movieHealthySyncStreak = movieHealthySyncStreak,
    seriesSequentialFailuresRemembered = seriesSequentialFailuresRemembered,
    seriesHealthySyncStreak = seriesHealthySyncStreak
)

fun SyncMetadata.toEntity() = SyncMetadataEntity(
    providerId = providerId,
    lastLiveSync = lastLiveSync,
    lastMovieSync = lastMovieSync,
    lastSeriesSync = lastSeriesSync,
    lastEpgSync = lastEpgSync,
    lastMovieAttempt = lastMovieAttempt,
    lastMovieSuccess = lastMovieSuccess,
    lastMoviePartial = lastMoviePartial,
    liveCount = liveCount,
    movieCount = movieCount,
    seriesCount = seriesCount,
    epgCount = epgCount,
    lastSyncStatus = lastSyncStatus,
    movieSyncMode = movieSyncMode.name,
    movieWarningsCount = movieWarningsCount,
    movieCatalogStale = movieCatalogStale,
    liveAvoidFullUntil = liveAvoidFullUntil,
    movieAvoidFullUntil = movieAvoidFullUntil,
    seriesAvoidFullUntil = seriesAvoidFullUntil,
    liveSequentialFailuresRemembered = liveSequentialFailuresRemembered,
    liveHealthySyncStreak = liveHealthySyncStreak,
    movieParallelFailuresRemembered = movieParallelFailuresRemembered,
    movieHealthySyncStreak = movieHealthySyncStreak,
    seriesSequentialFailuresRemembered = seriesSequentialFailuresRemembered,
    seriesHealthySyncStreak = seriesHealthySyncStreak
)

private fun encodeQualityOptions(options: List<ChannelQualityOption>): String? {
    if (options.isEmpty()) {
        return null
    }
    return qualityOptionsGson.toJson(options, channelQualityOptionsType)
}

private fun decodeQualityOptions(encoded: String?): List<ChannelQualityOption> {
    if (encoded.isNullOrBlank()) {
        return emptyList()
    }
    return runCatching {
        qualityOptionsGson.fromJson<List<ChannelQualityOption>>(encoded, channelQualityOptionsType).orEmpty()
    }.getOrDefault(emptyList())
}

private fun decodeAllowedOutputFormats(encoded: String?): List<String> {
    if (encoded.isNullOrBlank()) {
        return emptyList()
    }
    return runCatching {
        qualityOptionsGson.fromJson<List<String>>(encoded, providerAllowedOutputFormatsType)
            .orEmpty()
            .mapNotNull { value -> value.trim().lowercase().takeIf { it.isNotEmpty() } }
            .distinct()
    }.getOrDefault(emptyList())
}

private fun encodeAllowedOutputFormats(formats: List<String>): String {
    val normalized = formats
        .mapNotNull { value -> value.trim().lowercase().takeIf { it.isNotEmpty() } }
        .distinct()
    return qualityOptionsGson.toJson(normalized, providerAllowedOutputFormatsType)
}

private fun String?.toPlaybackWatchedStatus(): PlaybackWatchedStatus =
    runCatching {
        this?.let(PlaybackWatchedStatus::valueOf)
    }.getOrNull() ?: PlaybackWatchedStatus.IN_PROGRESS
