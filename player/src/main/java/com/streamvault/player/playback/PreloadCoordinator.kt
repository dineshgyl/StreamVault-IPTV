package com.streamvault.player.playback

import android.util.Log
import androidx.media3.exoplayer.source.MediaSource
import com.streamvault.domain.model.StreamInfo

class PreloadCoordinator(
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    var currentPreloadedMediaId: String? = null
        private set
    var currentPreloadedUrlHash: String? = null
        private set
    var preloadedMediaSource: MediaSource? = null
        private set
    var createdAtMs: Long = 0L
        private set

    private var currentResolvedType: ResolvedStreamType? = null
    private var currentDrmKey: String? = null
    private var currentHeadersHash: Int? = null

    fun store(mediaId: String, streamInfo: StreamInfo, resolvedType: ResolvedStreamType, mediaSource: MediaSource) {
        currentPreloadedMediaId = mediaId
        currentPreloadedUrlHash = normalizedUrlHash(streamInfo.url)
        preloadedMediaSource = mediaSource
        currentResolvedType = resolvedType
        currentDrmKey = drmKey(streamInfo)
        currentHeadersHash = headersHash(streamInfo)
        createdAtMs = nowMs()
        safeLog("preload stored mediaId=$mediaId type=$resolvedType target=${PlaybackLogSanitizer.sanitizeUrl(streamInfo.url)}")
    }

    fun tryReuse(mediaId: String, streamInfo: StreamInfo, resolvedType: ResolvedStreamType): MediaSource? {
        val result = when {
            preloadedMediaSource == null -> null
            isExpired() -> {
                invalidate("expired")
                null
            }
            currentPreloadedMediaId != mediaId -> {
                invalidate("different-media-id")
                null
            }
            currentPreloadedUrlHash != normalizedUrlHash(streamInfo.url) -> {
                invalidate("different-url")
                null
            }
            currentResolvedType != resolvedType -> {
                invalidate("different-stream-type")
                null
            }
            currentDrmKey != drmKey(streamInfo) -> {
                invalidate("different-drm")
                null
            }
            currentHeadersHash != headersHash(streamInfo) -> {
                invalidate("different-headers")
                null
            }
            else -> preloadedMediaSource
        }
        if (result != null) {
            safeLog("preload reused mediaId=$mediaId target=${PlaybackLogSanitizer.sanitizeUrl(streamInfo.url)}")
            clear()
        }
        return result
    }

    fun onPlaybackStarted(mediaId: String) {
        if (currentPreloadedMediaId != null && currentPreloadedMediaId != mediaId) {
            invalidate("playback-started-different-media")
        }
    }

    fun invalidate(reason: String) {
        if (preloadedMediaSource != null) {
            safeLog("preload invalidated reason=$reason mediaId=$currentPreloadedMediaId")
        }
        clear()
    }

    fun release() {
        invalidate("release")
    }

    fun isExpired(): Boolean = preloadedMediaSource != null && nowMs() - createdAtMs > PRELOAD_EXPIRY_MS

    private fun clear() {
        currentPreloadedMediaId = null
        currentPreloadedUrlHash = null
        preloadedMediaSource = null
        currentResolvedType = null
        currentDrmKey = null
        currentHeadersHash = null
        createdAtMs = 0L
    }

    private fun normalizedUrlHash(url: String): String {
        return url.trim().substringBefore('#').hashCode().toString()
    }

    private fun drmKey(streamInfo: StreamInfo): String? {
        return streamInfo.drmInfo?.let { "${it.scheme}:${it.licenseUrl}:${it.headers.hashCode()}" }
    }

    private fun headersHash(streamInfo: StreamInfo): Int {
        return (streamInfo.headers.toSortedMap() + ("User-Agent" to (streamInfo.userAgent ?: ""))).hashCode()
    }

    companion object {
        private const val TAG = "PreloadCoordinator"
        private const val PRELOAD_EXPIRY_MS = 2 * 60 * 1000L
    }

    private fun safeLog(message: String) {
        runCatching { Log.i(TAG, message) }
    }
}
