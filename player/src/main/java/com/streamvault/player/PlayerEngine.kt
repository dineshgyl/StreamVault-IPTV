package com.streamvault.player

import android.content.Context
import android.view.View
import com.streamvault.domain.model.DecoderMode
import com.streamvault.domain.model.DrmScheme
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.VideoFormat
import androidx.media3.common.PlaybackException
import com.streamvault.player.playback.PlaybackErrorCategory
import com.streamvault.player.playback.PlayerErrorClassifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

const val PLAYER_TRACK_AUTO_ID = "__auto__"

/**
 * Abstraction over the underlying media player.
 * UI layer talks to this interface, never to ExoPlayer directly.
 */
interface PlayerEngine {
    val playbackState: StateFlow<PlaybackState>
    val isPlaying: StateFlow<Boolean>
    val currentPosition: StateFlow<Long>
    val duration: StateFlow<Long>
    val videoFormat: StateFlow<VideoFormat>
    val error: Flow<PlayerError?>
    val retryStatus: StateFlow<PlayerRetryStatus?>
    val playerStats: StateFlow<PlayerStats>

    // Tracks
    val availableAudioTracks: StateFlow<List<PlayerTrack>>
    val availableSubtitleTracks: StateFlow<List<PlayerTrack>>
    val availableVideoTracks: StateFlow<List<PlayerTrack>>
    val playbackSpeed: StateFlow<Float>

    /** In-stream metadata title (ICY / HLS). Null when the stream sends nothing. */
    val mediaTitle: StateFlow<String?>

    fun prepare(streamInfo: StreamInfo)
    fun play()
    fun pause()
    fun stop()
    fun seekTo(positionMs: Long)
    fun seekForward(ms: Long = 10_000)
    fun seekBackward(ms: Long = 10_000)
    fun setDecoderMode(mode: DecoderMode)
    fun setVolume(volume: Float)
    fun setMuted(muted: Boolean)
    fun setPlaybackSpeed(speed: Float)
    fun setPreferredAudioLanguage(languageTag: String?)
    fun setSubtitleStyle(style: PlayerSubtitleStyle)
    fun setNetworkQualityPreferences(wifiMaxHeight: Int?, ethernetMaxHeight: Int?)
    fun selectAudioTrack(trackId: String)
    fun selectVideoTrack(trackId: String)
    fun selectSubtitleTrack(trackId: String?) // null to disable subtitles
    fun release()

    /** Toggle mute without losing the remembered volume level. */
    fun toggleMute()
    val isMuted: StateFlow<Boolean>

    /**
     * Enable scrubbing mode for rapid switching (channel zapping).
     * While enabled, the player skips re-buffering and favours lowest-latency
     * key-frame-only decoding so each channel appears on screen faster.
     */
    fun setScrubbingMode(enabled: Boolean)

    /**
     * Pre-warm the player with a stream that will likely be played next.
     * Only the initial manifest/key-frames are fetched — no full buffering.
     * Call with `null` to discard any preloaded data.
     */
    fun preload(streamInfo: StreamInfo?)

    fun createRenderView(context: Context, resizeMode: PlayerSurfaceResizeMode): View
    fun bindRenderView(renderView: View, resizeMode: PlayerSurfaceResizeMode)
    fun releaseRenderView(renderView: View)
}

data class PlayerRetryStatus(
    val attempt: Int,
    val maxAttempts: Int,
    val delayMs: Long
)

enum class PlayerSurfaceResizeMode {
    FIT,
    FILL,
    ZOOM
}

enum class PlaybackState {
    IDLE,
    BUFFERING,
    READY,
    ENDED,
    ERROR
}

data class PlayerStats(
    val videoCodec: String = "Unknown",
    val audioCodec: String = "Unknown",
    val videoBitrate: Int = 0,
    val droppedFrames: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val bandwidthEstimate: Long = 0,
    val bufferedDurationMs: Long = 0,
    val rebufferCount: Int = 0
)

sealed class PlayerError(val message: String) {
    class NetworkError(message: String) : PlayerError(message)
    class SourceError(message: String) : PlayerError(message)
    class DecoderError(message: String) : PlayerError(message)
    class DrmError(message: String, val scheme: DrmScheme? = null) : PlayerError(message)
    class UnknownError(message: String) : PlayerError(message)

    companion object {
        fun fromException(e: Throwable): PlayerError {
            val msg = e.message ?: "Unknown playback error"
            return when (PlayerErrorClassifier.classify(e)) {
                PlaybackErrorCategory.NETWORK,
                PlaybackErrorCategory.HTTP_SERVER,
                PlaybackErrorCategory.HTTP_AUTH,
                PlaybackErrorCategory.CLEAR_TEXT_BLOCKED,
                PlaybackErrorCategory.SSL -> NetworkError(msg)

                PlaybackErrorCategory.SOURCE_MALFORMED,
                PlaybackErrorCategory.LIVE_WINDOW -> SourceError(msg)

                PlaybackErrorCategory.DECODER,
                PlaybackErrorCategory.FORMAT_UNSUPPORTED -> DecoderError(msg)

                PlaybackErrorCategory.DRM -> DrmError(msg)
                PlaybackErrorCategory.UNKNOWN -> UnknownError(msg)
            }
        }
    }
}
