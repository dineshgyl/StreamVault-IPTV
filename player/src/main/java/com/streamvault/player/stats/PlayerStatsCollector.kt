package com.streamvault.player.stats

import androidx.media3.common.Format
import androidx.media3.exoplayer.ExoPlayer
import com.streamvault.domain.model.VideoFormat
import com.streamvault.player.PlayerStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PlayerStatsCollector(
    private val scope: CoroutineScope,
    private val currentPosition: MutableStateFlow<Long>,
    private val duration: MutableStateFlow<Long>,
    private val videoFormat: MutableStateFlow<VideoFormat>,
    private val playerStats: MutableStateFlow<PlayerStats>
) {
    private var positionJob: Job? = null
    private var bufferedJob: Job? = null
    private var statsJob: Job? = null
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
        positionJob = scope.launch {
            while (isActive) {
                playerProvider?.invoke()?.let { player ->
                    currentPosition.value = player.currentPosition
                    duration.value = player.duration.coerceAtLeast(0L)
                }
                delay(250L)
            }
        }
        bufferedJob = scope.launch {
            while (isActive) {
                playerProvider?.invoke()?.let { player ->
                    val bufferedDuration = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0L)
                    playerStats.value = playerStats.value.copy(bufferedDurationMs = bufferedDuration)
                }
                delay(500L)
            }
        }
        statsJob = scope.launch {
            while (isActive) {
                playerProvider?.invoke()?.let { player ->
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
                }
                delay(1000L)
            }
        }
    }

    fun stop() {
        positionJob?.cancel()
        bufferedJob?.cancel()
        statsJob?.cancel()
        positionJob = null
        bufferedJob = null
        statsJob = null
    }
}
