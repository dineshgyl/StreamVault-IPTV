package com.streamvault.data.repository

import com.streamvault.data.local.dao.EpisodeDao
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.dao.PlaybackHistoryDao
import com.streamvault.data.mapper.toDomain
import com.streamvault.data.mapper.toEntity
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.model.PlaybackWatchedStatus
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.PlaybackHistoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.remote.xtream.XtreamUrlFactory

@Singleton
class PlaybackHistoryRepositoryImpl @Inject constructor(
    private val dao: PlaybackHistoryDao,
    private val preferencesRepository: PreferencesRepository,
    private val movieDao: MovieDao,
    private val episodeDao: EpisodeDao
) : PlaybackHistoryRepository {

    override fun getRecentlyWatched(limit: Int): Flow<List<PlaybackHistory>> {
        return dao.getRecentlyWatched(limit).map { list -> list.map { it.toDomain() } }
    }

    override fun getRecentlyWatchedByProvider(providerId: Long, limit: Int): Flow<List<PlaybackHistory>> {
        return dao.getRecentlyWatchedByProvider(providerId, limit).map { list -> list.map { it.toDomain() } }
    }

    override fun getUnwatchedCount(providerId: Long, seriesId: Long): Flow<Int> {
        return episodeDao.getUnwatchedCount(
            providerId = providerId,
            seriesId = seriesId,
            completionThreshold = DEFAULT_PLAYBACK_COMPLETION_THRESHOLD
        )
    }

    override suspend fun getPlaybackHistory(contentId: Long, contentType: ContentType, providerId: Long): PlaybackHistory? {
        return dao.get(contentId, contentType.name, providerId)?.toDomain()
    }

    override suspend fun markAsWatched(history: PlaybackHistory): Result<Unit> {
        return try {
            if (preferencesRepository.isIncognitoMode.first()) {
                return Result.success(Unit)
            }

            val existing = dao.get(history.contentId, history.contentType.name, history.providerId)
            val resolvedTotalDuration = history.totalDurationMs.takeIf { it > 0L } ?: existing?.totalDurationMs ?: 0L
            val resolvedResumePosition = when {
                resolvedTotalDuration > 0L -> resolvedTotalDuration
                history.resumePositionMs > 0L -> history.resumePositionMs
                else -> existing?.resumePositionMs ?: 0L
            }
            val updatedHistory = history.copy(
                streamUrl = XtreamUrlFactory.sanitizePersistedStreamUrl(history.streamUrl, history.providerId),
                resumePositionMs = resolvedResumePosition,
                totalDurationMs = resolvedTotalDuration,
                watchCount = existing?.watchCount ?: history.watchCount,
                watchedStatus = PlaybackWatchedStatus.COMPLETED_MANUAL,
                lastWatchedAt = System.currentTimeMillis()
            )
            dao.insertOrUpdate(updatedHistory.toEntity())
            syncDenormalizedProgress(updatedHistory.contentId, updatedHistory.contentType, updatedHistory.providerId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.error("Failed to mark content as watched", e)
        }
    }

    override suspend fun recordPlayback(history: PlaybackHistory): Result<Unit> {
        return try {
            if (preferencesRepository.isIncognitoMode.first()) {
                return Result.success(Unit)
            }

            val existing = dao.get(history.contentId, history.contentType.name, history.providerId)
            val updatedHistory = history.copy(
                streamUrl = XtreamUrlFactory.sanitizePersistedStreamUrl(history.streamUrl, history.providerId),
                resumePositionMs = history.resumePositionMs.takeIf { it > 0L } ?: existing?.resumePositionMs ?: 0L,
                totalDurationMs = history.totalDurationMs.takeIf { it > 0L } ?: existing?.totalDurationMs ?: 0L,
                watchCount = (existing?.watchCount ?: 0) + 1,
                watchedStatus = resolveWatchedStatus(
                    resumePositionMs = history.resumePositionMs.takeIf { it > 0L } ?: existing?.resumePositionMs ?: 0L,
                    totalDurationMs = history.totalDurationMs.takeIf { it > 0L } ?: existing?.totalDurationMs ?: 0L,
                    fallback = history.watchedStatus
                ),
                lastWatchedAt = System.currentTimeMillis()
            )
            dao.insertOrUpdate(updatedHistory.toEntity())
            syncDenormalizedProgress(updatedHistory.contentId, updatedHistory.contentType, updatedHistory.providerId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.error("Failed to record playback history", e)
        }
    }

    override suspend fun updateResumePosition(history: PlaybackHistory): Result<Unit> {
        return try {
            if (preferencesRepository.isIncognitoMode.first()) {
                return Result.success(Unit)
            }

            val existing = dao.get(history.contentId, history.contentType.name, history.providerId)
            val existingWatchedStatus = existing?.toDomain()?.watchedStatus

            val updatedHistory = history.copy(
                streamUrl = XtreamUrlFactory.sanitizePersistedStreamUrl(history.streamUrl, history.providerId),
                watchCount = existing?.watchCount ?: 1,
                watchedStatus = resolveWatchedStatus(
                    resumePositionMs = history.resumePositionMs,
                    totalDurationMs = history.totalDurationMs,
                    fallback = existingWatchedStatus ?: history.watchedStatus
                ),
                lastWatchedAt = System.currentTimeMillis()
            )
            dao.insertOrUpdate(updatedHistory.toEntity())
            syncDenormalizedProgress(updatedHistory.contentId, updatedHistory.contentType, updatedHistory.providerId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.error("Failed to update playback resume position", e)
        }
    }

    override suspend fun removeFromHistory(contentId: Long, contentType: ContentType, providerId: Long): Result<Unit> = try {
        dao.delete(contentId, contentType.name, providerId)
        syncDenormalizedProgress(contentId, contentType, providerId)
        Result.success(Unit)
    } catch (e: Exception) {
        Result.error("Failed to remove playback history item", e)
    }

    override suspend fun clearAllHistory(): Result<Unit> = try {
        dao.deleteAll()
        syncAllDenormalizedProgress()
        Result.success(Unit)
    } catch (e: Exception) {
        Result.error("Failed to clear playback history", e)
    }

    override suspend fun clearHistoryForProvider(providerId: Long): Result<Unit> = try {
        dao.deleteByProvider(providerId)
        syncDenormalizedProgressForProvider(providerId)
        Result.success(Unit)
    } catch (e: Exception) {
        Result.error("Failed to clear provider playback history", e)
    }

    private suspend fun syncDenormalizedProgress(contentId: Long, contentType: ContentType, providerId: Long) {
        when (contentType) {
            ContentType.MOVIE -> movieDao.syncWatchProgressFromHistory(contentId, providerId)
            ContentType.SERIES_EPISODE -> episodeDao.syncWatchProgressFromHistory(contentId, providerId)
            else -> Unit
        }
    }

    private suspend fun syncDenormalizedProgressForProvider(providerId: Long) {
        movieDao.syncWatchProgressFromHistoryByProvider(providerId)
        episodeDao.syncWatchProgressFromHistoryByProvider(providerId)
    }

    private suspend fun syncAllDenormalizedProgress() {
        movieDao.syncAllWatchProgressFromHistory()
        episodeDao.syncAllWatchProgressFromHistory()
    }

    private fun resolveWatchedStatus(
        resumePositionMs: Long,
        totalDurationMs: Long,
        fallback: PlaybackWatchedStatus
    ): PlaybackWatchedStatus {
        if (totalDurationMs <= 0L) {
            return fallback
        }
        return if (isPlaybackComplete(resumePositionMs, totalDurationMs, DEFAULT_PLAYBACK_COMPLETION_THRESHOLD)) {
            PlaybackWatchedStatus.COMPLETED_AUTO
        } else {
            PlaybackWatchedStatus.IN_PROGRESS
        }
    }
}

private const val DEFAULT_PLAYBACK_COMPLETION_THRESHOLD = 0.95f

private fun isPlaybackComplete(
    progressMs: Long,
    totalDurationMs: Long,
    threshold: Float = DEFAULT_PLAYBACK_COMPLETION_THRESHOLD
): Boolean {
    if (progressMs <= 0L || totalDurationMs <= 0L) {
        return false
    }
    return progressMs >= (totalDurationMs * threshold).toLong()
}
