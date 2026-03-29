package com.streamvault.data.remote.xtream

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.remote.dto.XtreamAuthResponse
import com.streamvault.data.remote.dto.XtreamCategory
import com.streamvault.data.remote.dto.XtreamEpgListing
import com.streamvault.data.remote.dto.XtreamEpgResponse
import com.streamvault.data.remote.dto.XtreamEpisode
import com.streamvault.data.remote.dto.XtreamEpisodeInfo
import com.streamvault.data.remote.dto.XtreamSeriesInfoResponse
import com.streamvault.data.remote.dto.XtreamSeriesItem
import com.streamvault.data.remote.dto.XtreamServerInfo
import com.streamvault.data.remote.dto.XtreamStream
import com.streamvault.data.remote.dto.XtreamUserInfo
import com.streamvault.data.remote.dto.XtreamVodInfo
import com.streamvault.data.remote.dto.XtreamVodInfoResponse
import com.streamvault.data.remote.dto.XtreamVodMovieData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class XtreamProviderTest {

    @Test
    fun `parseXtreamExpirationDate handles slash separated local date times`() {
        assertThat(parseXtreamExpirationDate("2026/03/20 14:30:00")).isEqualTo(1774017000000L)
    }

    @Test
    fun `parseXtreamExpirationDate handles slash separated dates`() {
        assertThat(parseXtreamExpirationDate("2026/03/20")).isEqualTo(1773964800000L)
    }

    @Test
    fun `parseXtreamExpirationDate handles timestamps and iso instants`() {
        assertThat(parseXtreamExpirationDate("1710801000")).isEqualTo(1710801000000L)
        assertThat(parseXtreamExpirationDate("2026-03-20T14:30:00Z")).isEqualTo(1774017000000L)
        assertThat(parseXtreamExpirationDate("Unlimited")).isEqualTo(Long.MAX_VALUE)
    }

    @Test
    fun `authenticate normalizes allowed output formats`() = runBlocking {
        val provider = XtreamProvider(
            providerId = 42,
            api = FakeXtreamApiService(
                authResponse = XtreamAuthResponse(
                    userInfo = XtreamUserInfo(
                        auth = 1,
                        allowedOutputFormats = listOf("TS", "m3u8", "  ts  ")
                    ),
                    serverInfo = XtreamServerInfo()
                )
            ),
            serverUrl = "https://example.com",
            username = "user",
            password = "pass"
        )

        val authenticated = provider.authenticate().getOrNull()

        assertThat(authenticated?.allowedOutputFormats).containsExactly("ts", "m3u8").inOrder()
    }

    @Test
    fun `getLiveStreams preserves live container extension in internal url`() = runBlocking {
        val provider = XtreamProvider(
            providerId = 42,
            api = FakeXtreamApiService(
                liveStreams = listOf(
                    XtreamStream(
                        name = "Live Channel",
                        streamId = 777,
                        containerExtension = ".M3U8",
                        directSource = "https://cdn.example.com/live/777/master.m3u8?token=abc"
                    )
                )
            ),
            serverUrl = "https://example.com",
            username = "user",
            password = "pass"
        )

        val channels = provider.getLiveStreams().getOrNull().orEmpty()

        assertThat(channels).hasSize(1)
        assertThat(channels.first().streamUrl).isEqualTo(
            "xtream://42/live/777?ext=m3u8&src=https%3A%2F%2Fcdn.example.com%2Flive%2F777%2Fmaster.m3u8%3Ftoken%3Dabc"
        )
    }

    @Test
    fun `getLiveStreams prefers hls and keeps ts fallback when both output formats are allowed`() = runBlocking {
        val provider = XtreamProvider(
            providerId = 42,
            api = FakeXtreamApiService(
                liveStreams = listOf(
                    XtreamStream(
                        name = "Live Channel",
                        streamId = 777
                    )
                )
            ),
            serverUrl = "https://example.com",
            username = "user",
            password = "pass",
            allowedOutputFormats = listOf("ts", "m3u8")
        )

        val channel = provider.getLiveStreams().getOrNull().orEmpty().single()

        assertThat(channel.streamUrl).isEqualTo("xtream://42/live/777?ext=m3u8")
        assertThat(channel.alternativeStreams).containsExactly("xtream://42/live/777?ext=ts")
        assertThat(channel.qualityOptions.map { it.label to it.url }).containsExactly(
            "HLS" to "xtream://42/live/777?ext=m3u8",
            "MPEG-TS" to "xtream://42/live/777?ext=ts"
        ).inOrder()
    }

    @Test
    fun `buildCatchUpUrls includes xtream route and php fallbacks for preferred formats`() = runBlocking {
        val provider = XtreamProvider(
            providerId = 42,
            api = FakeXtreamApiService(),
            serverUrl = "https://example.com",
            username = "user",
            password = "pass",
            allowedOutputFormats = listOf("m3u8", "ts")
        )

        val urls = provider.buildCatchUpUrls(
            streamId = 777,
            start = 1_710_000_000L,
            end = 1_710_003_600L
        )

        assertThat(urls).containsAtLeast(
            "https://example.com/timeshift/user/pass/60/2024-03-09%3A16-00/777.m3u8",
            "https://example.com/timeshifts/user/pass/60/777/2024-03-09%3A16-00.m3u8",
            "https://example.com/streaming/timeshift.php?username=user&password=pass&stream=777&start=2024-03-09%3A16-00&duration=60&extension=m3u8",
            "https://example.com/timeshift.php?username=user&password=pass&stream=777&start=2024-03-09%3A16-00&duration=60"
        )
        assertThat(urls.first()).isEqualTo("https://example.com/timeshift/user/pass/60/2024-03-09%3A16-00/777.m3u8")
    }

    @Test
    fun `getVodInfo decodes common xtream metadata fields`() = runBlocking {
        val provider = XtreamProvider(
            providerId = 42,
            api = FakeXtreamApiService(
                vodInfo = XtreamVodInfoResponse(
                    info = XtreamVodInfo(
                        plot = "U29tZSBQbG90",
                        cast = "Sm9obiBEb2U=",
                        director = "SmFuZSBEb2U=",
                        genre = "QWN0aW9u",
                        durationSecs = 120,
                        rating = "7.5"
                    ),
                    movieData = XtreamVodMovieData(
                        streamId = 99,
                        name = "TW92aWUgTmFtZQ==",
                        containerExtension = "MKV",
                        directSource = "https://cdn.example.com/vod/99/movie.mkv?exp=1774017000"
                    )
                )
            ),
            serverUrl = "https://example.com",
            username = "user",
            password = "pass"
        )

        val movie = provider.getVodInfo(99).getOrNull()

        assertThat(movie).isNotNull()
        assertThat(movie?.name).isEqualTo("Movie Name")
        assertThat(movie?.plot).isEqualTo("Some Plot")
        assertThat(movie?.cast).isEqualTo("John Doe")
        assertThat(movie?.director).isEqualTo("Jane Doe")
        assertThat(movie?.genre).isEqualTo("Action")
        assertThat(movie?.streamUrl).isEqualTo(
            "xtream://42/movie/99?ext=mkv&src=https%3A%2F%2Fcdn.example.com%2Fvod%2F99%2Fmovie.mkv%3Fexp%3D1774017000"
        )
    }

    @Test
    fun `getVodStreams still loads when category prefetch fails`() = runBlocking {
        val provider = XtreamProvider(
            providerId = 42,
            api = object : XtreamApiService {
                override suspend fun authenticate(endpoint: String): XtreamAuthResponse =
                    XtreamAuthResponse(XtreamUserInfo(auth = 1), XtreamServerInfo())

                override suspend fun getLiveCategories(endpoint: String): List<XtreamCategory> = emptyList()

                override suspend fun getLiveStreams(endpoint: String): List<XtreamStream> = emptyList()

                override suspend fun getVodCategories(endpoint: String): List<XtreamCategory> {
                    throw XtreamNetworkException("category prefetch failed")
                }

                override suspend fun getVodStreams(endpoint: String): List<XtreamStream> = listOf(
                    XtreamStream(
                        name = "Action Movie",
                        streamId = 321,
                        categoryId = "vod-action",
                        categoryName = "Action",
                        containerExtension = "mp4"
                    )
                )

                override suspend fun getVodInfo(endpoint: String): XtreamVodInfoResponse = XtreamVodInfoResponse()

                override suspend fun getSeriesCategories(endpoint: String): List<XtreamCategory> = emptyList()

                override suspend fun getSeriesList(endpoint: String): List<XtreamSeriesItem> = emptyList()

                override suspend fun getSeriesInfo(endpoint: String): XtreamSeriesInfoResponse = XtreamSeriesInfoResponse()

                override suspend fun getShortEpg(endpoint: String): XtreamEpgResponse = XtreamEpgResponse()

                override suspend fun getFullEpg(endpoint: String): XtreamEpgResponse = XtreamEpgResponse()
            },
            serverUrl = "https://example.com",
            username = "user",
            password = "pass"
        )

        val movies = provider.getVodStreams().getOrNull().orEmpty()

        assertThat(movies).hasSize(1)
        assertThat(movies.first().name).isEqualTo("Action Movie")
        assertThat(movies.first().categoryName).isEqualTo("Action")
        assertThat(movies.first().categoryId).isNotNull()
    }

    @Test
    fun `getVodStreams honors explicit adult flag from xtream payload`() = runBlocking {
        val provider = XtreamProvider(
            providerId = 42,
            api = FakeXtreamApiService(
                vodStreams = listOf(
                    XtreamStream(
                        name = "Movie",
                        streamId = 55,
                        categoryName = "Cinema",
                        isAdult = true
                    )
                )
            ),
            serverUrl = "https://example.com",
            username = "user",
            password = "pass"
        )

        val movie = provider.getVodStreams().getOrNull().orEmpty().single()

        assertThat(movie.isAdult).isTrue()
    }

    @Test
    fun `vod list and details both normalize ratings to ten point scale`() = runBlocking {
        val provider = XtreamProvider(
            providerId = 42,
            api = FakeXtreamApiService(
                vodStreams = listOf(
                    XtreamStream(
                        name = "Movie",
                        streamId = 55,
                        rating = "10.0",
                        rating5based = "5"
                    )
                ),
                vodInfo = XtreamVodInfoResponse(
                    info = XtreamVodInfo(
                        rating = "10.0",
                        rating5based = "5"
                    ),
                    movieData = XtreamVodMovieData(
                        streamId = 55,
                        name = "Movie"
                    )
                )
            ),
            serverUrl = "https://example.com",
            username = "user",
            password = "pass"
        )

        val gridMovie = provider.getVodStreams().getOrNull().orEmpty().single()
        val detailMovie = provider.getVodInfo(55).getOrNull()

        assertThat(gridMovie.rating).isEqualTo(10f)
        assertThat(detailMovie?.rating).isEqualTo(10f)
    }

    @Test
    fun `getSeriesInfo falls back to legacy series query parameter when primary payload is empty`() = runBlocking {
        val requestedEndpoints = mutableListOf<String>()
        val provider = XtreamProvider(
            providerId = 42,
            api = object : XtreamApiService {
                override suspend fun authenticate(endpoint: String): XtreamAuthResponse =
                    XtreamAuthResponse(XtreamUserInfo(auth = 1), XtreamServerInfo())

                override suspend fun getLiveCategories(endpoint: String): List<XtreamCategory> = emptyList()

                override suspend fun getLiveStreams(endpoint: String): List<XtreamStream> = emptyList()

                override suspend fun getVodCategories(endpoint: String): List<XtreamCategory> = emptyList()

                override suspend fun getVodStreams(endpoint: String): List<XtreamStream> = emptyList()

                override suspend fun getVodInfo(endpoint: String): XtreamVodInfoResponse = XtreamVodInfoResponse()

                override suspend fun getSeriesCategories(endpoint: String): List<XtreamCategory> = emptyList()

                override suspend fun getSeriesList(endpoint: String): List<XtreamSeriesItem> = emptyList()

                override suspend fun getSeriesInfo(endpoint: String): XtreamSeriesInfoResponse {
                    requestedEndpoints += endpoint
                    return if (endpoint.contains("series_id=77")) {
                        XtreamSeriesInfoResponse()
                    } else {
                        XtreamSeriesInfoResponse(
                            info = XtreamSeriesItem(name = "Fallback Series"),
                            episodes = mapOf(
                                "1" to listOf(
                                    XtreamEpisode(
                                        id = "501",
                                        episodeNum = 1,
                                        title = "Episode One",
                                        season = 1,
                                        containerExtension = "mp4"
                                    )
                                )
                            )
                        )
                    }
                }

                override suspend fun getShortEpg(endpoint: String): XtreamEpgResponse = XtreamEpgResponse()

                override suspend fun getFullEpg(endpoint: String): XtreamEpgResponse = XtreamEpgResponse()
            },
            serverUrl = "https://example.com",
            username = "user",
            password = "pass"
        )

        val series = provider.getSeriesInfo(77).getOrNull()

        assertThat(series).isNotNull()
        assertThat(series?.name).isEqualTo("Fallback Series")
        assertThat(series?.seasons).hasSize(1)
        assertThat(requestedEndpoints).hasSize(2)
        assertThat(requestedEndpoints.first()).contains("series_id=77")
        assertThat(requestedEndpoints.last()).contains("series=77")
    }

    @Test
    fun `getSeriesInfo builds usable series from episodes when info block is missing`() = runBlocking {
        val provider = XtreamProvider(
            providerId = 42,
            api = FakeXtreamApiService(
                seriesInfo = XtreamSeriesInfoResponse(
                    episodes = mapOf(
                        "1" to listOf(
                            XtreamEpisode(
                                id = "701",
                                episodeNum = 1,
                                title = "Pilot",
                                season = 1,
                                containerExtension = "mp4"
                            )
                        )
                    )
                )
            ),
            serverUrl = "https://example.com",
            username = "user",
            password = "pass"
        )

        val series = provider.getSeriesInfo(88).getOrNull()

        assertThat(series).isNotNull()
        assertThat(series?.seriesId).isEqualTo(88L)
        assertThat(series?.seasons).hasSize(1)
        assertThat(series?.seasons?.first()?.episodes).hasSize(1)
    }

    private class FakeXtreamApiService(
        private val authResponse: XtreamAuthResponse = XtreamAuthResponse(XtreamUserInfo(auth = 1), XtreamServerInfo()),
        private val liveCategories: List<XtreamCategory> = emptyList(),
        private val liveStreams: List<XtreamStream> = emptyList(),
        private val vodCategories: List<XtreamCategory> = emptyList(),
        private val vodStreams: List<XtreamStream> = emptyList(),
        private val vodInfo: XtreamVodInfoResponse = XtreamVodInfoResponse(),
        private val seriesCategories: List<XtreamCategory> = emptyList(),
        private val seriesList: List<XtreamSeriesItem> = emptyList(),
        private val seriesInfo: XtreamSeriesInfoResponse = XtreamSeriesInfoResponse(),
        private val shortEpg: XtreamEpgResponse = XtreamEpgResponse(),
        private val fullEpg: XtreamEpgResponse = XtreamEpgResponse()
    ) : XtreamApiService {
        override suspend fun authenticate(endpoint: String): XtreamAuthResponse {
            return authResponse
        }

        override suspend fun getLiveCategories(endpoint: String): List<XtreamCategory> = liveCategories

        override suspend fun getLiveStreams(endpoint: String): List<XtreamStream> = liveStreams

        override suspend fun getVodCategories(endpoint: String): List<XtreamCategory> = vodCategories

        override suspend fun getVodStreams(endpoint: String): List<XtreamStream> = vodStreams

        override suspend fun getVodInfo(endpoint: String): XtreamVodInfoResponse = vodInfo

        override suspend fun getSeriesCategories(endpoint: String): List<XtreamCategory> = seriesCategories

        override suspend fun getSeriesList(endpoint: String): List<XtreamSeriesItem> = seriesList

        override suspend fun getSeriesInfo(endpoint: String): XtreamSeriesInfoResponse = seriesInfo

        override suspend fun getShortEpg(endpoint: String): XtreamEpgResponse = shortEpg

        override suspend fun getFullEpg(endpoint: String): XtreamEpgResponse = fullEpg
    }
}
