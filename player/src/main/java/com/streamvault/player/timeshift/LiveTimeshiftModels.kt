package com.streamvault.player.timeshift

data class TimeshiftConfig(
    val enabled: Boolean = false,
    val depthMinutes: Int = 30
) {
    val depthMs: Long = depthMinutes.coerceIn(15, 60) * 60_000L
}

enum class LiveTimeshiftBackend {
    NONE,
    DISK,
    MEMORY
}

enum class LiveTimeshiftStatus {
    DISABLED,
    UNSUPPORTED,
    PREPARING,
    LIVE,
    PAUSED_BEHIND_LIVE,
    PLAYING_BEHIND_LIVE,
    BUFFERING,
    FAILED
}

data class LiveTimeshiftState(
    val enabled: Boolean = false,
    val supported: Boolean = false,
    val backend: LiveTimeshiftBackend = LiveTimeshiftBackend.NONE,
    val status: LiveTimeshiftStatus = LiveTimeshiftStatus.DISABLED,
    val bufferStartMs: Long = 0L,
    val bufferEndMs: Long = 0L,
    val liveEdgePositionMs: Long = 0L,
    val currentOffsetFromLiveMs: Long = 0L,
    val bufferedDurationMs: Long = 0L,
    val message: String? = null
) {
    val canSeekToLive: Boolean = supported && currentOffsetFromLiveMs > 1_000L
    val isActive: Boolean = enabled && supported && status != LiveTimeshiftStatus.DISABLED && status != LiveTimeshiftStatus.UNSUPPORTED
}

internal data class LiveTimeshiftSnapshot(
    val url: String,
    val durationMs: Long,
    val backend: LiveTimeshiftBackend
)
