package com.streamvault.player

import com.streamvault.domain.model.StreamType
import com.streamvault.player.playback.ResolvedStreamType
import com.streamvault.player.playback.StreamTypeResolver

@Deprecated("Use StreamTypeResolver")
object StreamTypeDetector {
    fun detect(url: String): StreamType {
        return when (StreamTypeResolver.resolve(url = url, isLive = url.contains("/live/", ignoreCase = true))) {
            ResolvedStreamType.HLS -> StreamType.HLS
            ResolvedStreamType.DASH -> StreamType.DASH
            ResolvedStreamType.MPEG_TS_LIVE -> StreamType.MPEG_TS
            ResolvedStreamType.PROGRESSIVE -> StreamType.PROGRESSIVE
            ResolvedStreamType.UNKNOWN -> StreamType.UNKNOWN
        }
    }
}
