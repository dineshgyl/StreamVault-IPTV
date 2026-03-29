package com.streamvault.app.tv

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.net.Uri
import android.provider.BaseColumns
import android.util.Log
import com.streamvault.app.MainActivity
import com.streamvault.app.R
import com.streamvault.app.device.isTelevisionDevice
import com.streamvault.app.navigation.PlayerNavigationRequest
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.repository.PlaybackHistoryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WatchNextManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playbackHistoryRepository: PlaybackHistoryRepository
) {

    suspend fun refreshWatchNext() {
        if (!context.isTelevisionDevice()) return
        val historyEntries = playbackHistoryRepository.getRecentlyWatched(limit = 40)
            .first()
            .asSequence()
            .filter(::isEligibleForWatchNext)
            .distinctBy(::watchNextKey)
            .sortedByDescending { it.lastWatchedAt }
            .take(MAX_WATCH_NEXT_ITEMS)
            .toList()

        runCatching {
            val existingEntries = loadExistingEntries()
            val targetKeys = historyEntries.mapTo(mutableSetOf(), ::watchNextKey)

            existingEntries
                .filterKeys { it !in targetKeys }
                .values
                .forEach { rowId ->
                    context.contentResolver.delete(
                        ContentUris.withAppendedId(TvContract.WatchNextPrograms.CONTENT_URI, rowId),
                        null,
                        null
                    )
                }

            historyEntries.forEach { history ->
                val values = buildWatchNextValues(history)
                val existingId = existingEntries[watchNextKey(history)]
                if (existingId == null) {
                    context.contentResolver.insert(TvContract.WatchNextPrograms.CONTENT_URI, values)
                } else {
                    context.contentResolver.update(
                        ContentUris.withAppendedId(TvContract.WatchNextPrograms.CONTENT_URI, existingId),
                        values,
                        null,
                        null
                    )
                }
            }
        }.onFailure { throwable ->
            Log.w(TAG, "Watch Next sync failed", throwable)
        }
    }

    private fun loadExistingEntries(): Map<String, Long> {
        val projection = arrayOf(BaseColumns._ID, COLUMN_INTERNAL_PROVIDER_ID)
        return context.contentResolver.query(
            TvContract.WatchNextPrograms.CONTENT_URI,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(BaseColumns._ID)
            val keyIndex = cursor.getColumnIndexOrThrow(COLUMN_INTERNAL_PROVIDER_ID)
            buildMap {
                while (cursor.moveToNext()) {
                    put(cursor.getString(keyIndex), cursor.getLong(idIndex))
                }
            }
        }.orEmpty()
    }

    private fun buildWatchNextValues(history: PlaybackHistory): ContentValues {
        val launchIntent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(MainActivity.EXTRA_PLAYER_REQUEST, history.toPlayerRequest())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        return ContentValues().apply {
            put(COLUMN_INTERNAL_PROVIDER_ID, watchNextKey(history))
            put(COLUMN_TYPE, watchNextProgramType(history.contentType))
            put(COLUMN_TITLE, history.title)
            put(COLUMN_DESCRIPTION, context.getString(R.string.saved_preset_watch_next))
            put(COLUMN_POSTER_ART_URI, artworkUriFor(history).toString())
            put(COLUMN_INTENT_URI, launchIntent.toUri(Intent.URI_INTENT_SCHEME))
            put(COLUMN_LAST_PLAYBACK_POSITION_MILLIS, history.resumePositionMs)
            put(COLUMN_DURATION_MILLIS, history.totalDurationMs)
            put(COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, history.lastWatchedAt)
            put(COLUMN_WATCH_NEXT_TYPE, TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
        }
    }

    private fun watchNextProgramType(contentType: ContentType): Int = when (contentType) {
        ContentType.MOVIE -> TvContract.PreviewPrograms.TYPE_MOVIE
        ContentType.SERIES,
        ContentType.SERIES_EPISODE -> TvContract.PreviewPrograms.TYPE_TV_EPISODE
        ContentType.LIVE -> TvContract.PreviewPrograms.TYPE_CLIP
    }

    private fun artworkUriFor(history: PlaybackHistory): Uri {
        val remoteArtwork = history.posterUrl?.takeIf { it.isNotBlank() }
        if (remoteArtwork != null) {
            return Uri.parse(remoteArtwork)
        }
        return Uri.parse("android.resource://${context.packageName}/${R.mipmap.ic_launcher_vault}")
    }

    private fun isEligibleForWatchNext(history: PlaybackHistory): Boolean {
        if (history.providerId <= 0L) return false
        if (history.resumePositionMs <= 0L || history.totalDurationMs <= 0L) return false
        if (history.contentType != ContentType.MOVIE && history.contentType != ContentType.SERIES_EPISODE) {
            return false
        }
        return history.resumePositionMs < history.totalDurationMs * WATCH_NEXT_COMPLETION_THRESHOLD
    }

    private fun watchNextKey(history: PlaybackHistory): String =
        "${history.providerId}:${history.contentType.name}:${history.contentId}"

    private fun PlaybackHistory.toPlayerRequest(): PlayerNavigationRequest =
        PlayerNavigationRequest(
            streamUrl = streamUrl,
            title = title,
            internalId = contentId,
            providerId = providerId,
            contentType = contentType.name,
            artworkUrl = posterUrl
        )

    private companion object {
        const val TAG = "WatchNextManager"
        const val MAX_WATCH_NEXT_ITEMS = 12
        const val WATCH_NEXT_COMPLETION_THRESHOLD = 0.95f
        const val COLUMN_INTERNAL_PROVIDER_ID = "internal_provider_id"
        const val COLUMN_TYPE = "type"
        const val COLUMN_TITLE = "title"
        const val COLUMN_DESCRIPTION = "description"
        const val COLUMN_POSTER_ART_URI = "poster_art_uri"
        const val COLUMN_INTENT_URI = "intent_uri"
        const val COLUMN_LAST_PLAYBACK_POSITION_MILLIS = "last_playback_position_millis"
        const val COLUMN_DURATION_MILLIS = "duration_millis"
        const val COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS = "last_engagement_time_utc_millis"
        const val COLUMN_WATCH_NEXT_TYPE = "watch_next_type"
    }
}
