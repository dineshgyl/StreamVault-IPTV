package com.streamvault.app.ui.screens.player

import androidx.lifecycle.viewModelScope
import com.streamvault.domain.model.ChannelNumberingMode
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.StreamType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch

fun PlayerViewModel.playNext() {
    clearNumericChannelInput()
    if (channelList.isEmpty()) return
    val nextIndex = wrappedChannelIndex(1)
    if (nextIndex == -1) return
    changeChannel(nextIndex)
}

fun PlayerViewModel.playPrevious() {
    clearNumericChannelInput()
    if (channelList.isEmpty()) return
    val prevIndex = wrappedChannelIndex(-1)
    if (prevIndex == -1) return
    changeChannel(prevIndex)
}

fun PlayerViewModel.zapToChannel(channelId: Long) {
    clearNumericChannelInput()
    if (currentContentType != ContentType.LIVE || channelList.isEmpty()) return
    val index = channelList.indexOfFirst { it.id == channelId }
    if (index != -1) {
        changeChannel(index)
        closeOverlays()
    }
}

fun PlayerViewModel.zapToLastChannel() {
    clearNumericChannelInput()
    if (currentContentType != ContentType.LIVE || channelList.isEmpty()) return
    if (previousChannelIndex in channelList.indices && previousChannelIndex != currentChannelIndex) {
        changeChannel(previousChannelIndex)
    }
}

fun PlayerViewModel.hasLastChannel(): Boolean {
    if (currentContentType != ContentType.LIVE) return false
    val channels = channelList
    if (channels.isEmpty()) return false
    return previousChannelIndex in channels.indices && previousChannelIndex != currentChannelIndex
}

fun PlayerViewModel.hasPendingNumericChannelInput(): Boolean = numericInputBuffer.isNotBlank()

fun PlayerViewModel.inputNumericChannelDigit(digit: Int) {
    if (currentContentType != ContentType.LIVE || channelList.isEmpty()) return
    if (digit !in 0..9) return

    if (numericInputBuffer.isBlank() && digit == 0 && hasLastChannel()) {
        zapToLastChannel()
        return
    }

    numericInputBuffer = if (numericInputBuffer.length >= 4) {
        digit.toString()
    } else {
        numericInputBuffer + digit.toString()
    }
    val exactMatch = resolveChannelByNumber(numericInputBuffer.toIntOrNull())
    val previewMatch = exactMatch ?: resolveChannelByPrefix(numericInputBuffer)

    numericChannelInputFlow.value = NumericChannelInputState(
        input = numericInputBuffer,
        matchedChannelName = previewMatch?.name,
        invalid = false
    )

    scheduleNumericChannelCommit()
}

fun PlayerViewModel.commitNumericChannelInput() {
    numericInputCommitJob?.cancel()
    if (numericInputBuffer.isBlank()) return

    val targetChannel = resolveChannelByNumber(numericInputBuffer.toIntOrNull())
    if (targetChannel != null) {
        val targetIndex = channelList.indexOfFirst { it.id == targetChannel.id }
        if (targetIndex != -1) {
            changeChannel(targetIndex)
        }
        clearNumericChannelInput()
        return
    }

    numericChannelInputFlow.value = NumericChannelInputState(
        input = numericInputBuffer,
        matchedChannelName = null,
        invalid = true
    )

    numericInputFeedbackJob?.cancel()
    numericInputFeedbackJob = viewModelScope.launch {
        delay(900)
        clearNumericChannelInput()
    }
}

fun PlayerViewModel.clearNumericChannelInput() {
    numericInputCommitJob?.cancel()
    numericInputFeedbackJob?.cancel()
    numericInputBuffer = ""
    numericChannelInputFlow.value = null
}

internal fun PlayerViewModel.changeChannel(index: Int) {
    clearNumericChannelInput()
    if (currentChannelIndex != -1 && currentChannelIndex != index) {
        previousChannelIndex = currentChannelIndex
    }
    val requestVersion = beginPlaybackSession()
    val channel = channelList[index]
    currentChannelIndex = index
    currentContentId = channel.id
    currentTitle = channel.name
    playbackTitleFlow.value = currentTitle
    currentStreamUrl = channel.streamUrl
    pendingCatchUpUrls = emptyList()
    updateStreamClass("Primary")
    currentChannelFlow.value = channel
    refreshCurrentChannelRecording()
    displayChannelNumberFlow.value = resolveChannelNumber(channel, index)
    recentChannelsFlow.update { channels -> channels.filterNot { it.id == channel.id } }

    playerEngine.setScrubbingMode(true)

    viewModelScope.launch {
        val resolvedUrl = resolvePlaybackUrl(channel.streamUrl, channel.id, channel.providerId, ContentType.LIVE)
            ?: return@launch
        if (!isActivePlaybackSession(requestVersion, channel.streamUrl)) return@launch
        val streamInfo = StreamInfo(
            url = resolvedUrl,
            title = currentTitle,
            streamType = StreamType.UNKNOWN
        )
        if (!preparePlayer(streamInfo, requestVersion)) return@launch
        playerEngine.play()

        playerEngine.playbackState
            .filter { it == com.streamvault.player.PlaybackState.READY }
            .take(1)
            .collect {
                if (isActivePlaybackSession(requestVersion, channel.streamUrl)) {
                    playerEngine.setScrubbingMode(false)
                }
            }
    }

    preloadAdjacentChannel(index)

    requestEpg(
        providerId = currentProviderId,
        epgChannelId = channel.epgChannelId,
        streamId = channel.streamId,
        internalChannelId = channel.id
    )

    showZapOverlayFlow.value = false
    showControlsFlow.value = false
    openChannelInfoOverlay()

    triedAlternativeStreams.clear()
    triedAlternativeStreams.add(channel.streamUrl)
    if (currentContentType == ContentType.LIVE) {
        recordLivePlayback(channel)
        scheduleZapBufferWatchdog(index)
    }
}

internal fun PlayerViewModel.preloadAdjacentChannel(currentIndex: Int) {
    if (channelList.size < 2) return
    val nextIndex = (currentIndex + 1) % channelList.size
    val nextChannel = channelList[nextIndex]
    viewModelScope.launch {
        val nextUrl = resolvePlaybackUrl(
            nextChannel.streamUrl,
            nextChannel.id,
            nextChannel.providerId,
            ContentType.LIVE
        ) ?: return@launch
        playerEngine.preload(
            StreamInfo(
                url = nextUrl,
                title = nextChannel.name,
                streamType = StreamType.UNKNOWN
            )
        )
    }
}

internal fun PlayerViewModel.recordLivePlayback(channel: com.streamvault.domain.model.Channel) {
    if (currentProviderId <= 0 || currentContentType != ContentType.LIVE) return

    val playbackKey = currentProviderId to channel.id
    if (lastRecordedLivePlaybackKey == playbackKey) return
    lastRecordedLivePlaybackKey = playbackKey

    viewModelScope.launch {
        logRepositoryFailure(
            operation = "Record live playback",
            result = playbackHistoryRepository.recordPlayback(
                PlaybackHistory(
                    contentId = channel.id,
                    contentType = ContentType.LIVE,
                    providerId = currentProviderId,
                    title = channel.name,
                    streamUrl = channel.streamUrl,
                    lastWatchedAt = System.currentTimeMillis()
                )
            )
        )
    }
}

internal fun PlayerViewModel.scheduleNumericChannelCommit() {
    numericInputCommitJob?.cancel()
    numericInputCommitJob = viewModelScope.launch {
        delay(1300)
        commitNumericChannelInput()
    }
}

internal fun PlayerViewModel.resolveChannelByNumber(number: Int?): com.streamvault.domain.model.Channel? {
    if (number == null) return null
    return channelList
        .withIndex()
        .firstOrNull { (index, channel) -> resolveChannelNumber(channel, index) == number }
        ?.value
}

internal fun PlayerViewModel.resolveChannelByPrefix(prefix: String): com.streamvault.domain.model.Channel? {
    return channelList
        .withIndex()
        .firstOrNull { (index, channel) ->
            resolveChannelNumber(channel, index).toString().startsWith(prefix)
        }
        ?.value
}

internal fun PlayerViewModel.resolveChannelNumber(
    channel: com.streamvault.domain.model.Channel,
    index: Int
): Int = when (channelNumberingMode) {
    ChannelNumberingMode.GROUP -> if (index >= 0) index + 1 else channel.number.takeIf { it > 0 } ?: 0
    ChannelNumberingMode.PROVIDER -> channel.number.takeIf { it > 0 } ?: if (index >= 0) index + 1 else 0
}
