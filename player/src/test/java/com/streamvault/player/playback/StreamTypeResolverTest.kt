package com.streamvault.player.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StreamTypeResolverTest {

    @Test
    fun `m3u8 resolves to HLS`() {
        assertThat(StreamTypeResolver.resolve("http://example.com/channel.m3u8"))
            .isEqualTo(ResolvedStreamType.HLS)
    }

    @Test
    fun `mpd resolves to DASH`() {
        assertThat(StreamTypeResolver.resolve("http://example.com/manifest.mpd"))
            .isEqualTo(ResolvedStreamType.DASH)
    }

    @Test
    fun `live url resolves to MPEG TS live`() {
        assertThat(StreamTypeResolver.resolve("http://example.com/live/channel1", isLive = true))
            .isEqualTo(ResolvedStreamType.MPEG_TS_LIVE)
    }

    @Test
    fun `mp4 resolves to progressive`() {
        assertThat(StreamTypeResolver.resolve("http://example.com/movie.mp4"))
            .isEqualTo(ResolvedStreamType.PROGRESSIVE)
    }

    @Test
    fun `unknown url resolves to unknown`() {
        assertThat(StreamTypeResolver.resolve("http://example.com/stream"))
            .isEqualTo(ResolvedStreamType.UNKNOWN)
    }
}

