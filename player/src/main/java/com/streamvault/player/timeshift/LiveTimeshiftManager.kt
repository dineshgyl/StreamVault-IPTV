package com.streamvault.player.timeshift

import android.content.Context
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.StreamType
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

internal interface LiveTimeshiftManager {
    val state: StateFlow<LiveTimeshiftState>
    suspend fun startSession(streamInfo: StreamInfo, channelKey: String, config: TimeshiftConfig)
    suspend fun stopSession()
    suspend fun createSnapshot(): LiveTimeshiftSnapshot?
}

@Singleton
internal class DefaultLiveTimeshiftManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) : LiveTimeshiftManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(LiveTimeshiftState())
    override val state: StateFlow<LiveTimeshiftState> = _state.asStateFlow()

    private var activeSession: Session? = null

    override suspend fun startSession(streamInfo: StreamInfo, channelKey: String, config: TimeshiftConfig) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                stopSessionLocked()
                if (!config.enabled) {
                    _state.value = LiveTimeshiftState(enabled = false, status = LiveTimeshiftStatus.DISABLED)
                    return@withLock
                }
                val support = determineSupport(streamInfo)
                if (!support.supported) {
                    _state.value = LiveTimeshiftState(
                        enabled = true,
                        supported = false,
                        status = LiveTimeshiftStatus.UNSUPPORTED,
                        message = support.reason
                    )
                    return@withLock
                }
                val backend = chooseBackend()
                val sessionDir = File(context.cacheDir, "timeshift/${channelKey.hashCode()}-${System.currentTimeMillis()}").apply { mkdirs() }
                val session = when (support.streamType) {
                    StreamType.HLS -> HlsSession(streamInfo, config, backend, sessionDir)
                    StreamType.MPEG_TS,
                    StreamType.PROGRESSIVE,
                    StreamType.UNKNOWN -> ProgressiveSession(streamInfo, config, backend, sessionDir)
                    StreamType.DASH,
                    StreamType.RTSP -> null
                }
                if (session == null) {
                    _state.value = LiveTimeshiftState(
                        enabled = true,
                        supported = false,
                        status = LiveTimeshiftStatus.UNSUPPORTED,
                        message = "This live stream type cannot use local rewind yet."
                    )
                    return@withLock
                }
                activeSession = session
                _state.value = LiveTimeshiftState(
                    enabled = true,
                    supported = true,
                    backend = backend,
                    status = LiveTimeshiftStatus.PREPARING,
                    message = "Preparing local live rewind…"
                )
                session.job = scope.launch {
                    try {
                        session.capture()
                    } catch (t: Throwable) {
                        _state.value = _state.value.copy(
                            enabled = true,
                            supported = true,
                            backend = session.backend,
                            status = LiveTimeshiftStatus.FAILED,
                            message = t.message ?: "Local live rewind failed."
                        )
                    }
                }
            }
        }
    }

    override suspend fun stopSession() {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                stopSessionLocked()
                _state.value = LiveTimeshiftState(enabled = false, status = LiveTimeshiftStatus.DISABLED)
            }
        }
    }

    override suspend fun createSnapshot(): LiveTimeshiftSnapshot? {
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                activeSession?.createSnapshot()
            }
        }
    }

    private suspend fun stopSessionLocked() {
        activeSession?.stop()
        activeSession = null
    }

    private fun chooseBackend(): LiveTimeshiftBackend {
        return try {
            context.cacheDir?.takeIf { (it.exists() || it.mkdirs()) && it.canWrite() }
                ?.let { LiveTimeshiftBackend.DISK }
                ?: LiveTimeshiftBackend.MEMORY
        } catch (_: Throwable) {
            LiveTimeshiftBackend.MEMORY
        }
    }

    private fun determineSupport(streamInfo: StreamInfo): SupportResult {
        if (streamInfo.drmInfo != null) {
            return SupportResult(false, "DRM-protected streams cannot use local rewind yet.", streamInfo.streamType)
        }
        val type = inferType(streamInfo)
        return when (type) {
            StreamType.RTSP -> SupportResult(false, "RTSP streams cannot use local rewind yet.", type)
            StreamType.DASH -> SupportResult(false, "DASH local rewind is not available in this version.", type)
            else -> SupportResult(true, null, type)
        }
    }

    private fun inferType(streamInfo: StreamInfo): StreamType {
        if (streamInfo.streamType != StreamType.UNKNOWN) return streamInfo.streamType
        val url = streamInfo.url.lowercase(Locale.ROOT)
        return when {
            url.endsWith(".m3u8") -> StreamType.HLS
            url.endsWith(".mpd") -> StreamType.DASH
            url.endsWith(".ts") -> StreamType.MPEG_TS
            url.startsWith("rtsp") -> StreamType.RTSP
            else -> StreamType.PROGRESSIVE
        }
    }

    private inner class SupportResult(
        val supported: Boolean,
        val reason: String?,
        val streamType: StreamType
    )

    private abstract inner class Session(
        val streamInfo: StreamInfo,
        val config: TimeshiftConfig,
        val backend: LiveTimeshiftBackend,
        val sessionDir: File
    ) {
        var job: Job? = null
        protected val sequence = AtomicLong(0L)
        protected val stateStartMs = System.currentTimeMillis()

        abstract suspend fun capture()
        abstract suspend fun createSnapshot(): LiveTimeshiftSnapshot?

        open suspend fun stop() {
            job?.cancel()
            sessionDir.deleteRecursively()
        }

        protected fun makeRequest(url: String) = Request.Builder().url(url).apply {
            streamInfo.userAgent?.takeIf { it.isNotBlank() }?.let { header("User-Agent", it) }
            streamInfo.headers.forEach { (key, value) -> header(key, value) }
        }.build()

        protected fun updateWindow(windowDurationMs: Long, message: String? = null) {
            val now = System.currentTimeMillis()
            _state.value = _state.value.copy(
                enabled = true,
                supported = true,
                backend = backend,
                status = if (windowDurationMs > 0L) LiveTimeshiftStatus.LIVE else LiveTimeshiftStatus.PREPARING,
                bufferStartMs = (now - windowDurationMs).coerceAtLeast(0L),
                bufferEndMs = now,
                liveEdgePositionMs = windowDurationMs,
                bufferedDurationMs = windowDurationMs,
                currentOffsetFromLiveMs = 0L,
                message = message ?: if (windowDurationMs > 0L) "Local live rewind ready." else "Preparing local live rewind…"
            )
        }

        protected fun resolveRelativeUrl(baseUrl: String, value: String): String {
            return runCatching { URI(baseUrl).resolve(value).toString() }.getOrDefault(value)
        }
    }

    private inner class ProgressiveSession(
        streamInfo: StreamInfo,
        config: TimeshiftConfig,
        backend: LiveTimeshiftBackend,
        sessionDir: File
    ) : Session(streamInfo, config, backend, sessionDir) {

        private val chunks = ArrayDeque<ProgressiveChunk>()
        private val chunkMutex = Mutex()

        override suspend fun capture() {
            val request = makeRequest(streamInfo.url)
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Timeshift stream failed with HTTP ${response.code}")
                val input = response.body?.byteStream() ?: throw IOException("Timeshift stream returned an empty body")
                input.use { source ->
                    var current = createChunk()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (scope.isActive) {
                        currentCoroutineContext().ensureActive()
                        val read = source.read(buffer)
                        if (read <= 0) break
                        current.write(buffer, read)
                        if (System.currentTimeMillis() - current.startedAtMs >= PROGRESSIVE_CHUNK_MS) {
                            finalizeChunk(current)
                            current = createChunk()
                        }
                    }
                    if (current.bytesWritten > 0L) {
                        finalizeChunk(current)
                    }
                }
            }
        }

        override suspend fun createSnapshot(): LiveTimeshiftSnapshot? {
            val snapshotDir = File(sessionDir, "snapshot-progressive").apply {
                deleteRecursively()
                mkdirs()
            }
            val snapshotFile = File(snapshotDir, "buffer.ts")
            val orderedChunks = chunkMutex.withLock { chunks.toList() }
            if (orderedChunks.isEmpty()) return null
            snapshotFile.outputStream().use { output ->
                orderedChunks.forEach { chunk ->
                    when {
                        chunk.file != null && chunk.file.exists() -> chunk.file.inputStream().use { it.copyTo(output) }
                        chunk.payload != null -> output.write(chunk.payload)
                    }
                }
            }
            val durationMs = orderedChunks.sumOf { it.durationMs }
            return LiveTimeshiftSnapshot(
                url = snapshotFile.toURI().toString(),
                durationMs = durationMs,
                backend = backend
            )
        }

        private fun createChunk(): ActiveProgressiveChunk {
            val id = sequence.incrementAndGet()
            val targetFile = if (backend == LiveTimeshiftBackend.DISK) {
                File(sessionDir, "chunk-$id.ts")
            } else {
                null
            }
            return ActiveProgressiveChunk(
                id = id,
                startedAtMs = System.currentTimeMillis(),
                file = targetFile,
                output = if (targetFile != null) targetFile.outputStream().buffered() else ByteArrayOutputStream()
            )
        }

        private suspend fun finalizeChunk(active: ActiveProgressiveChunk) {
            active.output.flush()
            active.output.close()
            val endedAtMs = System.currentTimeMillis()
            val chunk = ProgressiveChunk(
                id = active.id,
                startedAtMs = active.startedAtMs,
                endedAtMs = endedAtMs,
                durationMs = (endedAtMs - active.startedAtMs).coerceAtLeast(1L),
                file = active.file,
                payload = if (backend == LiveTimeshiftBackend.MEMORY) (active.output as ByteArrayOutputStream).toByteArray() else null
            )
            val windowDuration = chunkMutex.withLock {
                chunks += chunk
                pruneProgressiveChunksLocked()
                chunks.sumOf { it.durationMs }
            }
            updateWindow(windowDuration)
        }

        private fun ActiveProgressiveChunk.write(buffer: ByteArray, read: Int) {
            output.write(buffer, 0, read)
            bytesWritten += read
        }

        private fun pruneProgressiveChunksLocked() {
            var totalDurationMs = chunks.sumOf { it.durationMs }
            while (totalDurationMs > config.depthMs && chunks.isNotEmpty()) {
                val removed = chunks.removeFirst()
                removed.file?.delete()
                totalDurationMs -= removed.durationMs
            }
        }
    }

    private inner class HlsSession(
        streamInfo: StreamInfo,
        config: TimeshiftConfig,
        backend: LiveTimeshiftBackend,
        sessionDir: File
    ) : Session(streamInfo, config, backend, sessionDir) {

        private val segments = ArrayDeque<HlsSegmentSnapshot>()
        private val segmentMutex = Mutex()

        override suspend fun capture() {
            var currentPlaylistUrl = streamInfo.url
            val seenSegments = linkedSetOf<String>()
            while (scope.isActive) {
                currentCoroutineContext().ensureActive()
                val playlistText = fetchText(currentPlaylistUrl)
                val parsed = parsePlaylist(currentPlaylistUrl, playlistText)
                when (parsed) {
                    is ParsedHlsPlaylist.Master -> currentPlaylistUrl = parsed.bestVariantUrl
                    is ParsedHlsPlaylist.Media -> {
                        parsed.segments.forEach { remoteSegment ->
                            currentCoroutineContext().ensureActive()
                            if (!seenSegments.add(remoteSegment.uri)) return@forEach
                            val bytes = fetchBytes(remoteSegment.uri)
                            val retained = retainHlsSegment(remoteSegment, bytes)
                            val windowDuration = segmentMutex.withLock {
                                segments += retained
                                pruneHlsSegmentsLocked()
                                segments.sumOf { it.durationMs }
                            }
                            updateWindow(windowDuration)
                        }
                        if (parsed.endList) break
                        delay((parsed.targetDurationSeconds.coerceAtLeast(2) * 1000L) / 2L)
                    }
                }
            }
        }

        override suspend fun createSnapshot(): LiveTimeshiftSnapshot? {
            val snapshotDir = File(sessionDir, "snapshot-hls").apply {
                deleteRecursively()
                mkdirs()
            }
            val snapshotSegments = segmentMutex.withLock { segments.toList() }
            if (snapshotSegments.isEmpty()) return null
            val playlist = File(snapshotDir, "index.m3u8")
            val targetDurationSeconds = snapshotSegments.maxOf { ((it.durationMs + 999L) / 1000L).toInt().coerceAtLeast(1) }
            val body = buildString {
                appendLine("#EXTM3U")
                appendLine("#EXT-X-VERSION:3")
                appendLine("#EXT-X-TARGETDURATION:$targetDurationSeconds")
                appendLine("#EXT-X-MEDIA-SEQUENCE:0")
                snapshotSegments.forEachIndexed { index, segment ->
                    val fileName = "segment-$index.ts"
                    val outputFile = File(snapshotDir, fileName)
                    when {
                        segment.file != null && segment.file.exists() -> segment.file.copyTo(outputFile, overwrite = true)
                        segment.payload != null -> outputFile.writeBytes(segment.payload)
                    }
                    appendLine("#EXTINF:${"%.3f".format(Locale.US, segment.durationMs / 1000.0)},")
                    appendLine(fileName)
                }
                appendLine("#EXT-X-ENDLIST")
            }
            playlist.writeText(body)
            return LiveTimeshiftSnapshot(
                url = playlist.toURI().toString(),
                durationMs = snapshotSegments.sumOf { it.durationMs },
                backend = backend
            )
        }

        private fun retainHlsSegment(remote: RemoteHlsSegment, bytes: ByteArray): HlsSegmentSnapshot {
            val id = sequence.incrementAndGet()
            return if (backend == LiveTimeshiftBackend.DISK) {
                val target = File(sessionDir, "segment-$id.ts").apply { writeBytes(bytes) }
                HlsSegmentSnapshot(remote.uri, remote.durationMs, target, null)
            } else {
                HlsSegmentSnapshot(remote.uri, remote.durationMs, null, bytes)
            }
        }

        private fun pruneHlsSegmentsLocked() {
            var totalDurationMs = segments.sumOf { it.durationMs }
            while (totalDurationMs > config.depthMs && segments.isNotEmpty()) {
                val removed = segments.removeFirst()
                removed.file?.delete()
                totalDurationMs -= removed.durationMs
            }
        }

        private fun fetchText(url: String): String {
            okHttpClient.newCall(makeRequest(url)).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Timeshift playlist failed with HTTP ${response.code}")
                return response.body?.string().orEmpty()
            }
        }

        private fun fetchBytes(url: String): ByteArray {
            okHttpClient.newCall(makeRequest(url)).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Timeshift segment failed with HTTP ${response.code}")
                return response.body?.bytes() ?: ByteArray(0)
            }
        }

        private fun parsePlaylist(baseUrl: String, rawText: String): ParsedHlsPlaylist {
            val lines = rawText.lineSequence().map(String::trim).filter { it.isNotEmpty() }.toList()
            if (lines.any { it.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) }) {
                val variants = mutableListOf<Pair<Int, String>>()
                var pendingBandwidth = 0
                lines.forEachIndexed { index, line ->
                    if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                        pendingBandwidth = Regex("""BANDWIDTH=(\d+)""")
                            .find(line)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()
                            ?: 0
                        val next = lines.getOrNull(index + 1)?.takeIf { !it.startsWith("#") } ?: return@forEachIndexed
                        variants += pendingBandwidth to resolveRelativeUrl(baseUrl, next)
                    }
                }
                val bestVariantUrl = variants.maxByOrNull { it.first }?.second
                    ?: throw IOException("No playable HLS variants were available.")
                return ParsedHlsPlaylist.Master(bestVariantUrl)
            }

            var targetDurationSeconds = 6
            var endList = false
            val segments = mutableListOf<RemoteHlsSegment>()
            var currentDurationMs = 6_000L
            lines.forEach { line ->
                when {
                    line.startsWith("#EXT-X-TARGETDURATION", ignoreCase = true) -> {
                        targetDurationSeconds = line.substringAfter(':', "6").toIntOrNull() ?: 6
                    }
                    line.startsWith("#EXTINF", ignoreCase = true) -> {
                        currentDurationMs = ((line.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: 6.0) * 1000.0).toLong()
                    }
                    line.startsWith("#EXT-X-ENDLIST", ignoreCase = true) -> endList = true
                    line.startsWith("#") -> Unit
                    else -> {
                        segments += RemoteHlsSegment(
                            uri = resolveRelativeUrl(baseUrl, line),
                            durationMs = currentDurationMs.coerceAtLeast(1L)
                        )
                    }
                }
            }
            return ParsedHlsPlaylist.Media(
                targetDurationSeconds = targetDurationSeconds,
                endList = endList,
                segments = segments
            )
        }
    }

    private data class ProgressiveChunk(
        val id: Long,
        val startedAtMs: Long,
        val endedAtMs: Long,
        val durationMs: Long,
        val file: File?,
        val payload: ByteArray?
    )

    private data class ActiveProgressiveChunk(
        val id: Long,
        val startedAtMs: Long,
        val file: File?,
        val output: java.io.OutputStream,
        var bytesWritten: Long = 0L
    )

    private data class HlsSegmentSnapshot(
        val remoteUrl: String,
        val durationMs: Long,
        val file: File?,
        val payload: ByteArray?
    )

    private data class RemoteHlsSegment(
        val uri: String,
        val durationMs: Long
    )

    private sealed interface ParsedHlsPlaylist {
        data class Master(val bestVariantUrl: String) : ParsedHlsPlaylist
        data class Media(
            val targetDurationSeconds: Int,
            val endList: Boolean,
            val segments: List<RemoteHlsSegment>
        ) : ParsedHlsPlaylist
    }

    private companion object {
        private const val PROGRESSIVE_CHUNK_MS = 2_000L
    }
}
