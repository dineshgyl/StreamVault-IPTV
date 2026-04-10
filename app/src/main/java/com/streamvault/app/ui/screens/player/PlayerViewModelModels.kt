package com.streamvault.app.ui.screens.player

import android.graphics.Bitmap
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ChannelQualityOption
import com.streamvault.domain.model.Season
import com.streamvault.domain.model.Series
import com.streamvault.domain.model.DecoderMode
import java.util.Locale

data class ResumePromptState(
    val show: Boolean = false,
    val positionMs: Long = 0L,
    val title: String = ""
)

data class NumericChannelInputState(
    val input: String = "",
    val matchedChannelName: String? = null,
    val invalid: Boolean = false
)

data class PlayerNoticeState(
    val message: String = "",
    val recoveryType: PlayerRecoveryType = PlayerRecoveryType.UNKNOWN,
    val actions: List<PlayerNoticeAction> = emptyList(),
    val isRetryNotice: Boolean = false
)

data class SeekPreviewState(
    val visible: Boolean = false,
    val positionMs: Long = 0L,
    val frameBitmap: Bitmap? = null,
    val artworkUrl: String? = null,
    val title: String = "",
    val isLoading: Boolean = false
)

data class PlayerDiagnosticsUiState(
    val providerName: String = "",
    val providerSourceLabel: String = "",
    val decoderMode: DecoderMode = DecoderMode.AUTO,
    val streamClassLabel: String = "Primary",
    val playbackStateLabel: String = "Idle",
    val alternativeStreamCount: Int = 0,
    val channelErrorCount: Int = 0,
    val archiveSupportLabel: String = "",
    val lastFailureReason: String? = null,
    val recentRecoveryActions: List<String> = emptyList(),
    val troubleshootingHints: List<String> = emptyList()
)

enum class PlayerRecoveryType {
    NETWORK,
    SOURCE,
    DECODER,
    DRM,
    CATCH_UP,
    BUFFER_TIMEOUT,
    UNKNOWN
}

enum class PlayerNoticeAction {
    RETRY,
    LAST_CHANNEL,
    ALTERNATE_STREAM,
    OPEN_GUIDE
}

enum class AspectRatio(val modeName: String) {
    FIT("Fit"),
    FILL("Stretch"),
    ZOOM("Zoom")
}

internal data class EpgRequestKey(
    val providerId: Long,
    val internalChannelId: Long,
    val epgChannelId: String?,
    val streamId: Long
)

internal data class PlayerUiTimeouts(
    val controlsMs: Long,
    val liveOverlayMs: Long,
    val noticeMs: Long,
    val diagnosticsMs: Long
)

internal fun resolvePreferredAudioLanguage(preferredAudioLanguage: String?, appLanguage: String): String? {
    val normalizedPreference = preferredAudioLanguage
        ?.trim()
        ?.takeIf { it.isNotBlank() && !it.equals("auto", ignoreCase = true) }
    val effectiveTag = normalizedPreference ?: appLanguage.takeIf { it.isNotBlank() && it != "system" }
        ?: Locale.getDefault().toLanguageTag()
    return effectiveTag
        .takeIf { it.isNotBlank() }
        ?.let { Locale.forLanguageTag(it) }
        ?.takeIf { it.language.isNotBlank() }
        ?.toLanguageTag()
}

internal fun Series.sanitizedForPlayer(): Series = copy(
    seasons = seasons.sanitizedForPlayer()
)

internal fun List<Season>?.sanitizedForPlayer(): List<Season> = this.orEmpty().map { season ->
    val sanitizedEpisodes = season.episodes.orEmpty()
    season.copy(
        episodes = sanitizedEpisodes,
        episodeCount = season.episodeCount.takeIf { it > 0 } ?: sanitizedEpisodes.size
    )
}

internal fun Channel.sanitizedForPlayer(): Channel {
    val sanitizedQualityOptions = qualityOptions.orEmpty()
    val sanitizedAlternativeStreams = alternativeStreams.orEmpty()
        .filter { it.isNotBlank() }
        .distinct()
    return copy(
        qualityOptions = sanitizedQualityOptions,
        alternativeStreams = sanitizedAlternativeStreams
    )
}

internal fun List<Channel>?.sanitizedChannelsForPlayer(): List<Channel> =
    this.orEmpty().map(Channel::sanitizedForPlayer)
