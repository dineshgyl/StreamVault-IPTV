package com.streamvault.player.playback

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.streamvault.domain.model.StreamInfo
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

class PlayerDataSourceFactoryProvider(
    private val baseClient: OkHttpClient
) {
    private val clientsByProfile = ConcurrentHashMap<PlayerTimeoutProfile, OkHttpClient>()

    fun createFactory(
        streamInfo: StreamInfo,
        resolvedStreamType: ResolvedStreamType,
        preload: Boolean = false
    ): Pair<PlayerTimeoutProfile, DataSource.Factory> {
        val profile = PlayerTimeoutProfile.resolve(streamInfo, resolvedStreamType, preload)
        val headers = buildMap {
            putAll(streamInfo.headers)
            streamInfo.userAgent?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
        }
        val client = clientsByProfile.computeIfAbsent(profile) {
            baseClient.newBuilder()
                .connectTimeout(profile.connectTimeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(profile.readTimeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(profile.writeTimeoutMs, TimeUnit.MILLISECONDS)
                .build()
        }
        val factory = OkHttpDataSource.Factory(client).apply {
            if (headers.isNotEmpty()) {
                setDefaultRequestProperties(headers)
            }
        }
        return profile to factory
    }
}

