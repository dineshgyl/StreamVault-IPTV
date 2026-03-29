package com.streamvault.player

import android.content.Context
import android.os.Handler
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes as Media3AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.ScrubbingModeParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.session.MediaSession
import com.streamvault.domain.model.DecoderMode
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.VideoFormat
import com.streamvault.player.audio.PlayerAudioFocusController
import com.streamvault.player.playback.DefaultDecoderPreferencePolicy
import com.streamvault.player.playback.DefaultPlaybackCompatibilityProfile
import com.streamvault.player.playback.PlaybackCompatibilityProfile
import com.streamvault.player.playback.PlaybackErrorCategory
import com.streamvault.player.playback.PlaybackLogSanitizer
import com.streamvault.player.playback.PlaybackRetryContext
import com.streamvault.player.playback.PlayerDataSourceFactoryProvider
import com.streamvault.player.playback.PlayerErrorClassifier
import com.streamvault.player.playback.PlayerMediaSourceFactory
import com.streamvault.player.playback.PlayerRetryPolicy
import com.streamvault.player.playback.PlayerTimeoutProfile
import com.streamvault.player.playback.PreloadCoordinator
import com.streamvault.player.playback.ResolvedStreamType
import com.streamvault.player.playback.StreamTypeResolver
import com.streamvault.player.stats.PlayerStatsCollector
import com.streamvault.player.tracks.PlayerTrackController
import com.streamvault.player.ui.PlayerViewBinder
import com.streamvault.player.ui.SubtitleStyleController
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

@OptIn(UnstableApi::class)
class Media3PlayerEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) : PlayerEngine {

    companion object {
        private const val TAG = "Media3PlayerEngine"
    }

    var constrainResolutionForMultiView: Boolean = false
    var bypassAudioFocus: Boolean = false
        set(value) {
            field = value
            audioFocusController.bypassAudioFocus = value
        }
    var enableMediaSession: Boolean = true

    private var supervisorJob = SupervisorJob()
    private var scope = CoroutineScope(Dispatchers.Main.immediate + supervisorJob)
    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var requestedDecoderMode: DecoderMode = DecoderMode.AUTO
    private var activeDecoderMode: DecoderMode = DecoderMode.HARDWARE
    private var lastStreamInfo: StreamInfo? = null
    private var lastMediaId: String? = null
    private var currentResolvedStreamType: ResolvedStreamType = ResolvedStreamType.UNKNOWN
    private var currentTimeoutProfile: PlayerTimeoutProfile = PlayerTimeoutProfile.VOD
    private var currentRetryPolicy: PlayerRetryPolicy? = null
    private var currentRetryContext: PlaybackRetryContext? = null
    private var playbackStarted = false
    private var retryAttempt = 0
    private var retryJob: Job? = null
    private var retryGeneration = 0L

    private val _playbackState = MutableStateFlow(PlaybackState.IDLE)
    override val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    override val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    override val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _videoFormat = MutableStateFlow(VideoFormat(0, 0))
    override val videoFormat: StateFlow<VideoFormat> = _videoFormat.asStateFlow()

    private val _error = MutableSharedFlow<PlayerError?>(replay = 1)
    override val error: Flow<PlayerError?> = _error.asSharedFlow()

    private val _retryStatus = MutableStateFlow<PlayerRetryStatus?>(null)
    override val retryStatus: StateFlow<PlayerRetryStatus?> = _retryStatus.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1f)
    override val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _playerStats = MutableStateFlow(PlayerStats())
    override val playerStats: StateFlow<PlayerStats> = _playerStats.asStateFlow()

    private val _mediaTitle = MutableStateFlow<String?>(null)
    override val mediaTitle: StateFlow<String?> = _mediaTitle.asStateFlow()

    private val subtitleStyleController = SubtitleStyleController()
    private val viewBinder = PlayerViewBinder(subtitleStyleController)
    private val trackController = PlayerTrackController(context)
    override val availableAudioTracks: StateFlow<List<PlayerTrack>> = trackController.availableAudioTracks
    override val availableSubtitleTracks: StateFlow<List<PlayerTrack>> = trackController.availableSubtitleTracks
    override val availableVideoTracks: StateFlow<List<PlayerTrack>> = trackController.availableVideoTracks

    private val audioFocusController = PlayerAudioFocusController(
        context = context,
        applyVolume = { volume -> exoPlayer?.volume = volume },
        setPlayWhenReady = { playWhenReady -> exoPlayer?.playWhenReady = playWhenReady }
    ).also {
        it.bypassAudioFocus = bypassAudioFocus
    }
    override val isMuted: StateFlow<Boolean> = audioFocusController.isMuted

    private val statsCollector = PlayerStatsCollector(
        scopeProvider = { scope },
        currentPosition = _currentPosition,
        duration = _duration,
        videoFormat = _videoFormat,
        playerStats = _playerStats
    ).also {
        it.bind { exoPlayer }
    }
    private val dataSourceFactoryProvider = PlayerDataSourceFactoryProvider(okHttpClient)
    private val mediaSourceFactory = PlayerMediaSourceFactory(dataSourceFactoryProvider)
    private val preloadCoordinator = PreloadCoordinator()
    private val compatibilityProfile: PlaybackCompatibilityProfile = DefaultPlaybackCompatibilityProfile
    private val decoderPreferencePolicy = DefaultDecoderPreferencePolicy()

    override fun prepare(streamInfo: StreamInfo) {
        prepareInternal(streamInfo = streamInfo, preserveRetryState = false, seekPositionMs = null, autoPlay = true)
    }

    override fun play() {
        if (audioFocusController.requestAudioFocusIfNeeded()) {
            exoPlayer?.playWhenReady = true
        }
    }

    override fun pause() {
        retryJob?.cancel()
        exoPlayer?.playWhenReady = false
        audioFocusController.onPauseOrStop()
    }

    override fun stop() {
        retryJob?.cancel()
        exoPlayer?.stop()
        _playbackState.value = PlaybackState.IDLE
        audioFocusController.onPauseOrStop()
    }

    override fun seekTo(positionMs: Long) {
        exoPlayer?.seekTo(positionMs)
    }

    override fun seekForward(ms: Long) {
        exoPlayer?.let { player ->
            val duration = player.duration
            val newPosition = if (duration != C.TIME_UNSET) {
                (player.currentPosition + ms).coerceAtMost(duration)
            } else {
                player.currentPosition + ms
            }
            player.seekTo(newPosition)
        }
    }

    override fun seekBackward(ms: Long) {
        exoPlayer?.seekTo((exoPlayer?.currentPosition ?: 0L - ms).coerceAtLeast(0L))
    }

    override fun setDecoderMode(mode: DecoderMode) {
        if (requestedDecoderMode == mode) return
        requestedDecoderMode = mode
        lastStreamInfo?.let { streamInfo ->
            val wasPlaying = exoPlayer?.playWhenReady == true
            val position = exoPlayer?.currentPosition
            prepareInternal(streamInfo, preserveRetryState = false, seekPositionMs = position, autoPlay = wasPlaying)
        }
    }

    override fun setVolume(volume: Float) {
        audioFocusController.setVolume(volume)
    }

    override fun setMuted(muted: Boolean) {
        audioFocusController.setMuted(muted)
    }

    override fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 2f)
        _playbackSpeed.value = clamped
        exoPlayer?.playbackParameters = PlaybackParameters(clamped)
    }

    override fun setPreferredAudioLanguage(languageTag: String?) {
        trackController.setPreferredAudioLanguage(exoPlayer, languageTag)
    }

    override fun setSubtitleStyle(style: PlayerSubtitleStyle) {
        subtitleStyleController.updateStyle(style)
        viewBinder.reapplyStyle()
    }

    override fun setNetworkQualityPreferences(wifiMaxHeight: Int?, ethernetMaxHeight: Int?) {
        trackController.setNetworkQualityPreferences(wifiMaxHeight, ethernetMaxHeight)
        exoPlayer?.let { player -> trackController.applyInitialParameters(player, constrainResolutionForMultiView) }
    }

    override fun selectAudioTrack(trackId: String) {
        exoPlayer?.let { trackController.selectAudioTrack(it, trackId) }
    }

    override fun selectVideoTrack(trackId: String) {
        exoPlayer?.let { trackController.selectVideoTrack(it, trackId) }
    }

    override fun selectSubtitleTrack(trackId: String?) {
        exoPlayer?.let { trackController.selectSubtitleTrack(it, trackId) }
    }

    override fun toggleMute() {
        audioFocusController.toggleMute()
    }

    override fun setScrubbingMode(enabled: Boolean) {
        val player = exoPlayer ?: return
        if (enabled) {
            player.setScrubbingModeEnabled(true)
        } else {
            player.setScrubbingModeEnabled(false)
            player.setScrubbingModeParameters(ScrubbingModeParameters.DEFAULT)
        }
    }

    override fun preload(streamInfo: StreamInfo?) {
        if (streamInfo == null) {
            preloadCoordinator.invalidate("cleared")
            return
        }
        if (streamInfo.url == lastStreamInfo?.url) return

        val mediaId = mediaSourceFactory.mediaIdFor(streamInfo)
        val resolvedStreamType = StreamTypeResolver.resolve(streamInfo)
        val retryContext = PlaybackRetryContext(
            resolvedStreamType = resolvedStreamType,
            timeoutProfile = PlayerTimeoutProfile.resolve(streamInfo, resolvedStreamType, preload = true)
        )
        val retryPolicy = PlayerRetryPolicy(retryContext) { false }
        val (_, mediaSource) = mediaSourceFactory.create(
            streamInfo = streamInfo,
            resolvedStreamType = resolvedStreamType,
            retryPolicy = retryPolicy,
            preload = true
        )
        preloadCoordinator.store(mediaId, streamInfo, resolvedStreamType, mediaSource)
    }

    override fun createRenderView(context: Context, resizeMode: PlayerSurfaceResizeMode) =
        viewBinder.createRenderView(context, resizeMode)

    override fun bindRenderView(renderView: android.view.View, resizeMode: PlayerSurfaceResizeMode) {
        viewBinder.bind(renderView, getOrCreatePlayer(), resizeMode)
    }

    override fun releaseRenderView(renderView: android.view.View) {
        viewBinder.release(renderView)
    }

    override fun release() {
        retryJob?.cancel()
        retryJob = null
        preloadCoordinator.release()
        statsCollector.stop()
        audioFocusController.release()
        mediaSession?.release()
        mediaSession = null
        viewBinder.clear()
        exoPlayer?.release()
        exoPlayer = null
        lastStreamInfo = null
        lastMediaId = null
        currentRetryPolicy = null
        currentRetryContext = null
        playbackStarted = false
        retryAttempt = 0
        _retryStatus.value = null
        _playbackState.value = PlaybackState.IDLE
        _isPlaying.value = false
        _mediaTitle.value = null
        trackController.resetSelections()
        statsCollector.reset()
        supervisorJob.cancel()
        supervisorJob = SupervisorJob()
        scope = CoroutineScope(Dispatchers.Main.immediate + supervisorJob)
    }

    private fun prepareInternal(
        streamInfo: StreamInfo,
        preserveRetryState: Boolean,
        seekPositionMs: Long?,
        autoPlay: Boolean
    ) {
        retryJob?.cancel()
        retryJob = null
        retryGeneration++
        lastStreamInfo = streamInfo
        val mediaId = mediaSourceFactory.mediaIdFor(streamInfo)
        if (!preserveRetryState || lastMediaId != mediaId) {
            retryAttempt = 0
            _retryStatus.value = null
        }
        if (lastMediaId != mediaId) {
            decoderPreferencePolicy.resetForMedia(mediaId)
        }
        lastMediaId = mediaId
        playbackStarted = false
        _error.tryEmit(null)
        _mediaTitle.value = null
        trackController.resetSelections()
        statsCollector.reset()

        currentResolvedStreamType = StreamTypeResolver.resolve(streamInfo)
        currentTimeoutProfile = PlayerTimeoutProfile.resolve(streamInfo, currentResolvedStreamType, preload = false)
        currentRetryContext = PlaybackRetryContext(currentResolvedStreamType, currentTimeoutProfile)
        currentRetryPolicy = PlayerRetryPolicy(currentRetryContext!!) { playbackStarted }

        val preferredDecoderMode = decoderPreferencePolicy.preferredMode(requestedDecoderMode, mediaId)
        if (activeDecoderMode != preferredDecoderMode) {
            activeDecoderMode = preferredDecoderMode
            recreatePlayer()
        }

        Log.i(
            TAG,
            "prepare resolvedStreamType=$currentResolvedStreamType timeoutProfile=$currentTimeoutProfile decoderPreference=$activeDecoderMode target=${PlaybackLogSanitizer.sanitizeUrl(streamInfo.url)}"
        )

        val player = getOrCreatePlayer()
        trackController.applyInitialParameters(player, constrainResolutionForMultiView)
        player.playbackParameters = PlaybackParameters(_playbackSpeed.value)

        val mediaSource = preloadCoordinator.tryReuse(mediaId, streamInfo, currentResolvedStreamType)
            ?: mediaSourceFactory.create(
                streamInfo = streamInfo,
                resolvedStreamType = currentResolvedStreamType,
                retryPolicy = currentRetryPolicy!!,
                preload = false
            ).second
        preloadCoordinator.onPlaybackStarted(mediaId)
        player.setMediaSource(mediaSource)
        player.prepare()
        seekPositionMs?.takeIf { it > 0L }?.let(player::seekTo)
        player.playWhenReady = autoPlay && audioFocusController.requestAudioFocusIfNeeded()
        viewBinder.attachPlayer(player)
    }

    private fun recreatePlayer() {
        val existing = exoPlayer
        if (existing != null) {
            val currentViewPosition = existing.currentPosition
            val playWhenReady = existing.playWhenReady
            mediaSession?.release()
            mediaSession = null
            exoPlayer?.release()
            exoPlayer = null
            viewBinder.attachPlayer(null)
            lastStreamInfo?.let { prepareInternal(it, preserveRetryState = true, seekPositionMs = currentViewPosition, autoPlay = playWhenReady) }
        }
    }

    private fun getOrCreatePlayer(): ExoPlayer {
        return exoPlayer ?: createPlayer().also { player ->
            exoPlayer = player
            statsCollector.bind { exoPlayer }
            audioFocusController.reapplyVolume()
            if (enableMediaSession) {
                mediaSession = MediaSession.Builder(context, player).build()
            }
        }
    }

    private fun createPlayer(): ExoPlayer {
        val renderersFactory = buildRenderersFactory()
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                30_000,
                90_000,
                2_500,
                10_000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val livePlaybackSpeedControl = DefaultLivePlaybackSpeedControl.Builder()
            .setFallbackMinPlaybackSpeed(1.0f)
            .setFallbackMaxPlaybackSpeed(1.0f)
            .build()

        return ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .setLivePlaybackSpeedControl(livePlaybackSpeedControl)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setAudioAttributes(
                Media3AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_UNKNOWN)
                    .build(),
                false
            )
            .build()
            .apply {
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                playbackParameters = PlaybackParameters(_playbackSpeed.value)
                addAnalyticsListener(createAnalyticsListener())
                addListener(createPlayerListener())
            }
    }

    private fun buildRenderersFactory(): DefaultRenderersFactory {
        val baseFactory = if (compatibilityProfile.shouldDisableDecoderReuseWorkaround()) {
            object : DefaultRenderersFactory(context) {
                override fun buildVideoRenderers(
                    context: Context,
                    extensionRendererMode: Int,
                    mediaCodecSelector: MediaCodecSelector,
                    enableDecoderFallback: Boolean,
                    eventHandler: Handler,
                    eventListener: VideoRendererEventListener,
                    allowedVideoJoiningTimeMs: Long,
                    out: ArrayList<Renderer>
                ) {
                    out.add(object : MediaCodecVideoRenderer(
                        context,
                        mediaCodecSelector,
                        allowedVideoJoiningTimeMs,
                        enableDecoderFallback,
                        eventHandler,
                        eventListener,
                        50
                    ) {
                        override fun canReuseCodec(
                            codecInfo: MediaCodecInfo,
                            oldFormat: Format,
                            newFormat: Format
                        ): DecoderReuseEvaluation {
                            return DecoderReuseEvaluation(
                                codecInfo.name,
                                oldFormat,
                                newFormat,
                                DecoderReuseEvaluation.REUSE_RESULT_NO,
                                DecoderReuseEvaluation.DISCARD_REASON_MAX_INPUT_SIZE_EXCEEDED
                            )
                        }
                    })
                }
            }
        } else {
            DefaultRenderersFactory(context)
        }

        return baseFactory.apply {
            setExtensionRendererMode(
                when (activeDecoderMode) {
                    DecoderMode.AUTO, DecoderMode.HARDWARE -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                    DecoderMode.SOFTWARE -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
                }
            )
        }
    }

    private fun createAnalyticsListener(): AnalyticsListener {
        return object : AnalyticsListener {
            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?
            ) {
                statsCollector.onVideoFormatChanged(format)
            }

            override fun onAudioInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?
            ) {
                statsCollector.onAudioFormatChanged(format)
            }

            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long
            ) {
                statsCollector.onDroppedFrames(droppedFrames)
            }

            override fun onBandwidthEstimate(
                eventTime: AnalyticsListener.EventTime,
                totalLoadTimeMs: Int,
                totalBytesLoaded: Long,
                bitrateEstimate: Long
            ) {
                statsCollector.onBandwidthEstimate(bitrateEstimate)
            }

            override fun onRenderedFirstFrame(
                eventTime: AnalyticsListener.EventTime,
                output: Any,
                renderTimeMs: Long
            ) {
                playbackStarted = true
                Log.i(
                    TAG,
                    "first-frame-success streamType=$currentResolvedStreamType timeoutProfile=$currentTimeoutProfile target=${PlaybackLogSanitizer.sanitizeUrl(lastStreamInfo?.url)}"
                )
            }
        }
    }

    private fun createPlayerListener(): Player.Listener {
        return object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                val previousState = _playbackState.value
                _playbackState.value = when (state) {
                    Player.STATE_IDLE -> PlaybackState.IDLE
                    Player.STATE_BUFFERING -> PlaybackState.BUFFERING
                    Player.STATE_READY -> PlaybackState.READY
                    Player.STATE_ENDED -> PlaybackState.ENDED
                    else -> PlaybackState.IDLE
                }
                if (previousState == PlaybackState.READY && _playbackState.value == PlaybackState.BUFFERING) {
                    statsCollector.incrementRebufferCount()
                }
                if (_playbackState.value == PlaybackState.READY) {
                    _retryStatus.value = null
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                if (isPlaying) {
                    statsCollector.start()
                    audioFocusController.onPlaybackStarted()
                } else {
                    statsCollector.stop()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                handlePlaybackError(error)
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                _currentPosition.value = newPosition.positionMs
            }

            override fun onMediaMetadataChanged(metadata: androidx.media3.common.MediaMetadata) {
                _mediaTitle.value = metadata.title?.toString()?.takeIf { it.isNotBlank() }
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                trackController.onTracksChanged(tracks)
            }
        }
    }

    private fun handlePlaybackError(error: PlaybackException) {
        retryJob?.cancel()
        retryJob = null
        val streamInfo = lastStreamInfo
        val mediaId = lastMediaId
        val retryPolicy = currentRetryPolicy
        val retryContext = currentRetryContext
        val category = PlayerErrorClassifier.classify(error)

        if (streamInfo == null || mediaId == null || retryPolicy == null || retryContext == null) {
            _retryStatus.value = null
            _error.tryEmit(PlayerError.fromException(error))
            _playbackState.value = PlaybackState.ERROR
            return
        }

        if (category == PlaybackErrorCategory.DECODER) {
            val fallbackMode = decoderPreferencePolicy.onDecoderInitFailure(activeDecoderMode, mediaId)
            if (fallbackMode != null) {
                Log.w(
                    TAG,
                    "decoder-preference fallback=$fallbackMode mediaId=$mediaId target=${PlaybackLogSanitizer.sanitizeUrl(streamInfo.url)}"
                )
                activeDecoderMode = fallbackMode
                prepareInternal(streamInfo, preserveRetryState = false, seekPositionMs = exoPlayer?.currentPosition, autoPlay = true)
                return
            }
        }

        val nextAttempt = retryAttempt + 1
        if (retryPolicy.shouldRetry(error, retryContext, playbackStarted, nextAttempt)) {
            val delayMs = retryPolicy.retryDelayMs(error, nextAttempt)
            val scheduledRetryGeneration = retryGeneration
            retryAttempt = nextAttempt
            _retryStatus.value = PlayerRetryStatus(
                attempt = nextAttempt,
                maxAttempts = retryPolicy.maxAttempts(error, playbackStarted),
                delayMs = delayMs
            )
            Log.w(
                TAG,
                "retry category=$category attempt=$nextAttempt delayMs=$delayMs reason=${retryPolicy.retryReason(error)} target=${PlaybackLogSanitizer.sanitizeUrl(streamInfo.url)}"
            )
            retryJob = scope.launch {
                delay(delayMs)
                if (
                    scheduledRetryGeneration != retryGeneration ||
                    lastMediaId != mediaId ||
                    retryAttempt != nextAttempt
                ) {
                    return@launch
                }
                retryJob = null
                prepareInternal(streamInfo, preserveRetryState = true, seekPositionMs = null, autoPlay = true)
            }
            return
        }

        _retryStatus.value = null
        Log.e(
            TAG,
            "fatal-error category=$category target=${PlaybackLogSanitizer.sanitizeUrl(streamInfo.url)} message=${PlaybackLogSanitizer.sanitizeMessage(error.message)}"
        )
        _error.tryEmit(PlayerError.fromException(error))
        _playbackState.value = PlaybackState.ERROR
    }
}
