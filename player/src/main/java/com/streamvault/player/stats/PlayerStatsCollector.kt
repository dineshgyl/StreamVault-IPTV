package com.streamvault.player.stats

import androidx.media3.common.Format
import androidx.media3.exoplayer.ExoPlayer
import com.streamvault.domain.model.VideoFormat
import com.streamvault.player.PlayerStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlayerStatsCollector(
    private val scopeProvider: () -> CoroutineScope,
    private val currentPosition: MutableStateFlow<Long>,
    private val duration: MutableStateFlow<Long>,
    private val videoFormat: MutableStateFlow<VideoFormat>,
    private val playerStats: MutableStateFlow<PlayerStats>
) {
    private data class PlayerSnapshot(
        val currentPosition: Long,
        val duration: Long,
        val bufferedDurationMs: Long?
    )

    private var pollingJob: Job? = null
    private var playerProvider: (() -> ExoPlayer?)? = null
    private var lastVideoFormat: Format? = null
    private var lastAudioFormat: Format? = null
    private var lastFrameRate = 0f
    private var lastBandwidthEstimate = 0L
    private var droppedFrames = 0

    fun bind(playerProvider: () -> ExoPlayer?) {
        this.playerProvider = playerProvider
    }

    fun reset() {
        lastVideoFormat = null
        lastAudioFormat = null
        lastFrameRate = 0f
        lastBandwidthEstimate = 0L
        droppedFrames = 0
        currentPosition.value = 0L
        duration.value = 0L
        videoFormat.value = VideoFormat(0, 0)
        playerStats.value = PlayerStats()
    }

    fun onVideoFormatChanged(format: Format) {
        lastVideoFormat = format
        lastFrameRate = format.frameRate.takeIf { it > 0f } ?: lastFrameRate
    }

    fun onAudioFormatChanged(format: Format) {
        lastAudioFormat = format
    }

    fun onDroppedFrames(count: Int) {
        droppedFrames += count
    }

    fun onBandwidthEstimate(bitrateEstimate: Long) {
        if (bitrateEstimate > 0L) {
            lastBandwidthEstimate = bitrateEstimate
        }
    }

    fun incrementRebufferCount() {
        playerStats.value = playerStats.value.copy(rebufferCount = playerStats.value.rebufferCount + 1)
    }

    fun start() {
        stop()
        pollingJob = scopeProvider().launch(Dispatchers.Default) {
            var bufferedUpdateElapsedMs = BUFFERED_UPDATE_INTERVAL_MS
            var statsUpdateElapsedMs = STATS_UPDATE_INTERVAL_MS
            while (isActive) {
                val shouldUpdateBuffered = bufferedUpdateElapsedMs >= BUFFERED_UPDATE_INTERVAL_MS
                val shouldUpdateStats = statsUpdateElapsedMs >= STATS_UPDATE_INTERVAL_MS
                val snapshot = readPlayerSnapshot(shouldUpdateBuffered)

                if (snapshot != null) {
                    currentPosition.value = snapshot.currentPosition
                    duration.value = snapshot.duration

                    snapshot.bufferedDurationMs?.let { bufferedDuration ->
                        playerStats.value = playerStats.value.copy(bufferedDurationMs = bufferedDuration)
                        bufferedUpdateElapsedMs = 0L
                    }

                    if (shouldUpdateStats) {
                        val video = lastVideoFormat
                        val audio = lastAudioFormat
                        videoFormat.value = VideoFormat(
                            width = video?.width?.takeIf { it > 0 } ?: 0,
                            height = video?.height?.takeIf { it > 0 } ?: 0,
                            frameRate = lastFrameRate,
                            bitrate = video?.bitrate?.takeIf { it > 0 } ?: 0,
                            codecV = video?.sampleMimeType ?: video?.codecs,
                            codecA = audio?.sampleMimeType ?: audio?.codecs
                        )
                        playerStats.value = playerStats.value.copy(
                            videoCodec = video?.sampleMimeType ?: video?.codecs ?: playerStats.value.videoCodec,
                            audioCodec = audio?.sampleMimeType ?: audio?.codecs ?: playerStats.value.audioCodec,
                            videoBitrate = video?.bitrate?.takeIf { it > 0 } ?: playerStats.value.videoBitrate,
                            droppedFrames = droppedFrames,
                            width = video?.width?.takeIf { it > 0 } ?: playerStats.value.width,
                            height = video?.height?.takeIf { it > 0 } ?: playerStats.value.height,
                            bandwidthEstimate = lastBandwidthEstimate
                        )
                        statsUpdateElapsedMs = 0L
                    }
                }
                delay(POSITION_UPDATE_INTERVAL_MS)
                bufferedUpdateElapsedMs += POSITION_UPDATE_INTERVAL_MS
                statsUpdateElapsedMs += POSITION_UPDATE_INTERVAL_MS
            }
        }
    }

    private suspend fun readPlayerSnapshot(includeBufferedDuration: Boolean): PlayerSnapshot? {
        return withContext(Dispatchers.Main.immediate) {
            playerProvider?.invoke()?.let { player ->
                PlayerSnapshot(
                    currentPosition = player.currentPosition,
                    duration = player.duration.coerceAtLeast(0L),
                    bufferedDurationMs = if (includeBufferedDuration) {
                        (player.bufferedPosition - player.currentPosition).coerceAtLeast(0L)
                    } else {
                        null
                    }
                )
            }
        }
    }

    fun stop() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private companion object {
        private const val POSITION_UPDATE_INTERVAL_MS = 250L
        private const val BUFFERED_UPDATE_INTERVAL_MS = 500L
        private const val STATS_UPDATE_INTERVAL_MS = 1000L
    }
}
