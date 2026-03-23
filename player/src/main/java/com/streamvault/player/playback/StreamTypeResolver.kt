package com.streamvault.player.playback

import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.StreamType
import java.net.URI
import java.util.Locale

enum class ResolvedStreamType {
    HLS,
    DASH,
    PROGRESSIVE,
    MPEG_TS_LIVE,
    UNKNOWN
}

object StreamTypeResolver {
    private val progressiveExtensions = listOf(".mp4", ".mkv", ".avi", ".mov", ".mp3", ".aac", ".m4a")
    private val hlsMimeHints = listOf(
        "application/vnd.apple.mpegurl",
        "application/x-mpegurl"
    )
    private val dashMimeHints = listOf("application/dash+xml")

    fun resolve(streamInfo: StreamInfo, mimeType: String? = null): ResolvedStreamType {
        return when (streamInfo.streamType) {
            StreamType.HLS -> ResolvedStreamType.HLS
            StreamType.DASH -> ResolvedStreamType.DASH
            StreamType.MPEG_TS -> ResolvedStreamType.MPEG_TS_LIVE
            StreamType.PROGRESSIVE -> ResolvedStreamType.PROGRESSIVE
            else -> resolve(url = streamInfo.url, mimeType = mimeType, isLive = isLive(streamInfo))
        }
    }

    fun resolve(url: String, mimeType: String? = null, isLive: Boolean = false): ResolvedStreamType {
        val normalizedMimeType = mimeType?.trim()?.lowercase(Locale.ROOT)
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault(url)
            .substringBefore('?')
            .substringBefore('#')
            .lowercase(Locale.ROOT)
        return when {
            normalizedMimeType != null && hlsMimeHints.any(normalizedMimeType::contains) -> ResolvedStreamType.HLS
            normalizedMimeType != null && dashMimeHints.any(normalizedMimeType::contains) -> ResolvedStreamType.DASH
            path.contains(".m3u8") -> ResolvedStreamType.HLS
            path.contains(".mpd") -> ResolvedStreamType.DASH
            path.endsWith(".ts") -> ResolvedStreamType.MPEG_TS_LIVE
            isLive && path.contains("/live/") && progressiveExtensions.none(path::endsWith) ->
                ResolvedStreamType.MPEG_TS_LIVE
            progressiveExtensions.any(path::endsWith) -> ResolvedStreamType.PROGRESSIVE
            else -> ResolvedStreamType.UNKNOWN
        }
    }

    private fun isLive(streamInfo: StreamInfo): Boolean {
        return streamInfo.streamType == StreamType.MPEG_TS ||
            streamInfo.url.contains("/live/", ignoreCase = true) ||
            streamInfo.catchUpUrl != null
    }
}

