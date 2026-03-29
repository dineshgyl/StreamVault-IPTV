package com.streamvault.data.manager

import android.content.Context
import android.os.StatFs
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.streamvault.data.remote.xtream.XtreamStreamUrlResolver
import com.streamvault.domain.manager.RecordingManager
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.RecordingItem
import com.streamvault.domain.model.RecordingRecurrence
import com.streamvault.domain.model.RecordingRequest
import com.streamvault.domain.model.RecordingStatus
import com.streamvault.domain.model.RecordingStorageState
import com.streamvault.domain.model.Result
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.coroutineContext

@Singleton
class RecordingManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gson: Gson,
    private val okHttpClient: OkHttpClient,
    private val xtreamStreamUrlResolver: XtreamStreamUrlResolver
) : RecordingManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateFile by lazy { File(recordingsDir, "recordings_state.json") }
    private val recordingsDir by lazy { File(context.filesDir, "recordings").apply { mkdirs() } }

    private val itemsState = MutableStateFlow(loadState())
    private val storageState = MutableStateFlow(readStorageState())
    private val stateMutex = Mutex()
    private val activeJobs = mutableMapOf<String, Job>()
    private var lastRetentionSweepMs = 0L

    /** Cancels the background polling coroutine. Call when the manager is no longer needed. */
    fun cancel() {
        scope.cancel()
    }

    init {
        scope.launch {
            while (isActive) {
                processSchedules()
                runRetentionSweepIfDue()
                storageState.value = readStorageState()
                delay(15_000L)
            }
        }
    }

    override fun observeRecordingItems(): Flow<List<RecordingItem>> = itemsState.asStateFlow()

    override fun observeStorageState(): Flow<RecordingStorageState> = storageState.asStateFlow()

    override suspend fun startManualRecording(request: RecordingRequest): Result<RecordingItem> = withContext(Dispatchers.IO) {
        val effectiveStreamUrl = resolveRecordingStreamUrl(request.providerId, request.channelId, request.streamUrl)
            ?: return@withContext Result.error("Recording stream URL could not be resolved.")
        if (isAdaptiveStream(effectiveStreamUrl)) {
            return@withContext Result.error("Live recording currently supports direct stream URLs only. Adaptive streams are not recordable yet.")
        }
        if (!storageState.value.isWritable) {
            return@withContext Result.error("Recording storage is not writable.")
        }

        val item = RecordingItem(
            id = UUID.randomUUID().toString(),
            providerId = request.providerId,
            channelId = request.channelId,
            channelName = request.channelName,
            streamUrl = request.streamUrl,
            scheduledStartMs = System.currentTimeMillis(),
            scheduledEndMs = request.scheduledEndMs,
            programTitle = request.programTitle,
            outputPath = request.outputPath ?: buildOutputFile(request).absolutePath,
            recurrence = RecordingRecurrence.NONE,
            recurringRuleId = null,
            status = RecordingStatus.RECORDING
        )
        val conflict = appendItemIfNoConflict(item)
        if (conflict != null) {
            return@withContext Result.error(recordingConflictMessage(conflict))
        }
        startCapture(item)
        storageState.value = readStorageState()
        Result.success(item)
    }

    override suspend fun scheduleRecording(request: RecordingRequest): Result<RecordingItem> = withContext(Dispatchers.IO) {
        val item = RecordingItem(
            id = UUID.randomUUID().toString(),
            providerId = request.providerId,
            channelId = request.channelId,
            channelName = request.channelName,
            streamUrl = request.streamUrl,
            scheduledStartMs = request.scheduledStartMs,
            scheduledEndMs = request.scheduledEndMs,
            programTitle = request.programTitle,
            outputPath = request.outputPath ?: buildOutputFile(request).absolutePath,
            recurrence = request.recurrence,
            recurringRuleId = request.recurringRuleId ?: request.recurrence.takeIf { it != RecordingRecurrence.NONE }?.let { UUID.randomUUID().toString() },
            status = RecordingStatus.SCHEDULED
        )
        val conflict = appendItemIfNoConflict(item)
        if (conflict != null) {
            return@withContext Result.error(recordingConflictMessage(conflict))
        }
        Result.success(item)
    }

    override suspend fun stopRecording(recordingId: String): Result<Unit> = withContext(Dispatchers.IO) {
        removeActiveJob(recordingId)?.cancel()
        val item = getItem(recordingId)
            ?: return@withContext Result.error("Recording not found")
        val now = System.currentTimeMillis()
        val fileLength = item.outputPath?.let { path -> File(path).takeIf { it.exists() }?.length() } ?: 0L
        updateItem(recordingId) {
            it.copy(
                status = if (fileLength > 0L) RecordingStatus.COMPLETED else RecordingStatus.CANCELLED,
                scheduledEndMs = now,
                terminalAtMs = now
            )
        }
        Result.success(Unit)
    }

    override suspend fun cancelRecording(recordingId: String): Result<Unit> = withContext(Dispatchers.IO) {
        removeActiveJob(recordingId)?.cancel()
        val now = System.currentTimeMillis()
        updateItem(recordingId) {
            it.copy(
                status = RecordingStatus.CANCELLED,
                terminalAtMs = now
            )
        }
        Result.success(Unit)
    }

    override suspend fun deleteRecording(recordingId: String): Result<Unit> = withContext(Dispatchers.IO) {
        removeActiveJob(recordingId)?.cancel()
        val item = getItem(recordingId)
            ?: return@withContext Result.error("Recording not found")
        if (item.status == RecordingStatus.RECORDING || item.status == RecordingStatus.SCHEDULED) {
            return@withContext Result.error("Only finished recordings can be deleted.")
        }
        deleteOutputFile(item.outputPath)
        removeItem(recordingId)
        storageState.value = readStorageState()
        Result.success(Unit)
    }

    private suspend fun processSchedules() {
        val now = System.currentTimeMillis()
        val snapshot = stateMutex.withLock { itemsState.value }

        snapshot
            .filter { it.status == RecordingStatus.SCHEDULED && it.scheduledStartMs <= now }
            .sortedBy(RecordingItem::scheduledStartMs)
            .forEach { scheduled ->
                val startDecision = promoteScheduledItemToRecordingIfNoConflict(scheduled.id)
                if (startDecision == null) {
                    return@forEach
                }
                if (startDecision.conflict != null) {
                    scheduleNextRecurringOccurrence(startDecision.recording)
                    val failureTime = System.currentTimeMillis()
                    updateItemIf(scheduled.id, expectedStatus = RecordingStatus.SCHEDULED) {
                        it.copy(
                            status = RecordingStatus.FAILED,
                            failureReason = recordingConflictMessage(startDecision.conflict),
                            terminalAtMs = failureTime
                        )
                    }
                    return@forEach
                }

                scheduleNextRecurringOccurrence(startDecision.recording)

                val effectiveStreamUrl = resolveRecordingStreamUrl(scheduled.providerId, scheduled.channelId, scheduled.streamUrl)
                if (effectiveStreamUrl == null) {
                    val failureTime = System.currentTimeMillis()
                    updateItemIf(scheduled.id, expectedStatus = RecordingStatus.RECORDING) {
                        it.copy(
                            status = RecordingStatus.FAILED,
                            failureReason = "Recording stream URL could not be resolved.",
                            terminalAtMs = failureTime
                        )
                    }
                } else if (isAdaptiveStream(effectiveStreamUrl)) {
                    val failureTime = System.currentTimeMillis()
                    updateItemIf(scheduled.id, expectedStatus = RecordingStatus.RECORDING) {
                        it.copy(
                            status = RecordingStatus.FAILED,
                            failureReason = "Scheduled recording supports direct stream URLs only.",
                            terminalAtMs = failureTime
                        )
                    }
                } else {
                    startCapture(startDecision.recording)
                }
            }

        snapshot
            .filter { it.status == RecordingStatus.RECORDING && it.scheduledEndMs in 1 until now }
            .forEach { stopCandidate ->
                removeActiveJob(stopCandidate.id)?.cancel()
                val fileLength = stopCandidate.outputPath?.let { path -> File(path).takeIf { it.exists() }?.length() } ?: 0L
                updateItemIf(stopCandidate.id, expectedStatus = RecordingStatus.RECORDING) {
                    it.copy(
                        status = if (fileLength > 0L) RecordingStatus.COMPLETED else RecordingStatus.CANCELLED,
                        scheduledEndMs = now,
                        terminalAtMs = now
                    )
                }
            }
    }

    private suspend fun startCapture(item: RecordingItem) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val outputFile = File(item.outputPath ?: return@launch)
            outputFile.parentFile?.mkdirs()
            runCatching {
                captureStreamToFile(item, outputFile)
            }.onSuccess {
                val completedAt = System.currentTimeMillis()
                updateItemIf(item.id, expectedStatus = RecordingStatus.RECORDING) { current ->
                    current.copy(status = RecordingStatus.COMPLETED, terminalAtMs = completedAt)
                }
            }.onFailure { error ->
                if (error is CancellationException) {
                    val cancelledAt = System.currentTimeMillis()
                    val fileLength = outputFile.takeIf { it.exists() }?.length() ?: 0L
                    updateItemIf(item.id, expectedStatus = RecordingStatus.RECORDING) { current ->
                        current.copy(
                            status = if (fileLength > 0L) RecordingStatus.COMPLETED else RecordingStatus.CANCELLED,
                            terminalAtMs = cancelledAt
                        )
                    }
                } else {
                    val failureTime = System.currentTimeMillis()
                    updateItemIf(item.id, expectedStatus = RecordingStatus.RECORDING) { current ->
                        current.copy(
                            status = RecordingStatus.FAILED,
                            failureReason = error.message,
                            terminalAtMs = failureTime
                        )
                    }
                }
            }
            removeActiveJob(item.id)
            storageState.value = readStorageState()
        }
        val replaced = stateMutex.withLock { activeJobs.putIfAbsent(item.id, job) }
        if (replaced != null) {
            job.cancel()
            return
        }
        job.start()
    }

    private suspend fun captureStreamToFile(item: RecordingItem, outputFile: File) {
        val resolvedUrl = resolveRecordingStreamUrl(item.providerId, item.channelId, item.streamUrl)
            ?: throw IOException("Recording stream URL could not be resolved")
        var attempt = 0
        var retryDelayMs = 1_000L
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                streamResponseBody(resolvedUrl, outputFile)
                return
            } catch (error: Throwable) {
                if (error is CancellationException || !isRetryableRecordingError(error) || attempt >= MAX_RECORDING_RETRIES) {
                    throw error
                }
                attempt++
                delay(retryDelayMs)
                retryDelayMs = (retryDelayMs * 2).coerceAtMost(8_000L)
            }
        }
    }

    private fun buildOutputFile(request: RecordingRequest): File {
        return buildOutputFile(request.channelName, request.scheduledStartMs)
    }

    private fun buildOutputFile(channelName: String, scheduledStartMs: Long): File {
        val safeName = channelName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(recordingsDir, "${safeName}_${scheduledStartMs}.ts")
    }

    private fun readStorageState(): RecordingStorageState {
        recordingsDir.mkdirs()
        val stat = StatFs(recordingsDir.absolutePath)
        return RecordingStorageState(
            outputDirectory = recordingsDir.absolutePath,
            availableBytes = stat.availableBytes,
            isWritable = recordingsDir.canWrite()
        )
    }

    private fun persistStateLocked() {
        stateFile.parentFile?.mkdirs()
        val tmpFile = File(stateFile.parentFile, "${stateFile.name}.tmp")
        tmpFile.writeText(gson.toJson(itemsState.value))
        tmpFile.renameTo(stateFile)
    }

    private fun loadState(): List<RecordingItem> {
        if (!stateFile.exists()) return emptyList()
        return runCatching {
            val listType = object : TypeToken<List<RecordingItem>>() {}.type
            FileInputStream(stateFile).bufferedReader().use { reader ->
                gson.fromJson<List<RecordingItem>>(reader, listType).orEmpty()
            }
        }.getOrDefault(emptyList())
    }

    private fun isAdaptiveStream(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") || lower.contains(".mpd")
    }

    private suspend fun resolveRecordingStreamUrl(providerId: Long, channelId: Long, logicalUrl: String): String? {
        return xtreamStreamUrlResolver.resolve(
            url = logicalUrl,
            fallbackProviderId = providerId,
            fallbackStreamId = channelId,
            fallbackContentType = ContentType.LIVE
        )
    }

    private suspend fun appendItem(item: RecordingItem) {
        stateMutex.withLock {
            itemsState.value = itemsState.value + item
            persistStateLocked()
        }
    }

    private suspend fun appendItemIfNoConflict(item: RecordingItem): RecordingItem? = stateMutex.withLock {
        val conflict = itemsState.value.findRecordingConflict(
            candidateStartMs = item.scheduledStartMs,
            candidateEndMs = item.scheduledEndMs,
            statuses = ACTIVE_RECORDING_STATUSES
        )
        if (conflict != null) {
            return@withLock conflict
        }
        itemsState.value = itemsState.value + item
        persistStateLocked()
        null
    }

    private suspend fun removeItem(recordingId: String): RecordingItem? = stateMutex.withLock {
        val existing = itemsState.value.firstOrNull { it.id == recordingId } ?: return@withLock null
        itemsState.value = itemsState.value.filterNot { it.id == recordingId }
        persistStateLocked()
        existing
    }

    private suspend fun getItem(recordingId: String): RecordingItem? =
        stateMutex.withLock { itemsState.value.firstOrNull { it.id == recordingId } }

    private suspend fun updateItem(
        recordingId: String,
        transform: (RecordingItem) -> RecordingItem
    ): RecordingItem? = stateMutex.withLock {
        var updated: RecordingItem? = null
        itemsState.value = itemsState.value.map { item ->
            if (item.id == recordingId) {
                transform(item).also { updated = it }
            } else {
                item
            }
        }
        if (updated != null) {
            persistStateLocked()
        }
        updated
    }

    private suspend fun updateItemIf(
        recordingId: String,
        expectedStatus: RecordingStatus,
        transform: (RecordingItem) -> RecordingItem
    ): RecordingItem? = stateMutex.withLock {
        var updated: RecordingItem? = null
        itemsState.value = itemsState.value.map { item ->
            if (item.id == recordingId && item.status == expectedStatus) {
                transform(item).also { updated = it }
            } else {
                item
            }
        }
        if (updated != null) {
            persistStateLocked()
        }
        updated
    }

    private suspend fun removeActiveJob(recordingId: String): Job? =
        stateMutex.withLock { activeJobs.remove(recordingId) }

    private suspend fun scheduleNextRecurringOccurrence(source: RecordingItem) {
        if (source.recurrence == RecordingRecurrence.NONE || source.recurringRuleId.isNullOrBlank()) {
            return
        }
        stateMutex.withLock {
            val nextStartMs = source.scheduledStartMs + source.recurrence.intervalMs
            val duplicateExists = itemsState.value.any { item ->
                item.recurringRuleId == source.recurringRuleId &&
                    item.scheduledStartMs == nextStartMs &&
                    item.status == RecordingStatus.SCHEDULED
            }
            if (duplicateExists) {
                return@withLock
            }
            val nextItem = source.copy(
                id = UUID.randomUUID().toString(),
                scheduledStartMs = nextStartMs,
                scheduledEndMs = source.scheduledEndMs + source.recurrence.intervalMs,
                outputPath = buildOutputFile(source.channelName, nextStartMs).absolutePath,
                status = RecordingStatus.SCHEDULED,
                failureReason = null,
                terminalAtMs = null
            )
            itemsState.value = itemsState.value + nextItem
            persistStateLocked()
        }
    }

    private suspend fun promoteScheduledItemToRecordingIfNoConflict(recordingId: String): RecordingStartDecision? =
        stateMutex.withLock {
            val scheduled = itemsState.value.firstOrNull {
                it.id == recordingId && it.status == RecordingStatus.SCHEDULED
            } ?: return@withLock null
            val conflict = itemsState.value.findRecordingConflict(
                candidateStartMs = scheduled.scheduledStartMs,
                candidateEndMs = scheduled.scheduledEndMs,
                ignoreRecordingId = recordingId,
                statuses = setOf(RecordingStatus.RECORDING)
            )
            if (conflict != null) {
                return@withLock RecordingStartDecision(recording = scheduled, conflict = conflict)
            }
            val updated = scheduled.copy(status = RecordingStatus.RECORDING)
            itemsState.value = itemsState.value.map { item ->
                if (item.id == recordingId) updated else item
            }
            persistStateLocked()
            RecordingStartDecision(recording = updated, conflict = null)
        }

    private suspend fun runRetentionSweepIfDue() {
        val now = System.currentTimeMillis()
        if (now - lastRetentionSweepMs < RETENTION_SWEEP_INTERVAL_MS) {
            return
        }
        lastRetentionSweepMs = now

        val expiredIds = stateMutex.withLock {
            itemsState.value
                .filter { item ->
                    item.status.isTerminal() &&
                        item.terminalAtMs?.let { terminalAt -> now - terminalAt >= FINISHED_RECORDING_RETENTION_MS } == true
                }
                .map(RecordingItem::id)
        }
        if (expiredIds.isEmpty()) {
            return
        }
        expiredIds.forEach { recordingId ->
            val removed = removeItem(recordingId)
            deleteOutputFile(removed?.outputPath)
        }
    }

    private fun deleteOutputFile(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching {
            val file = File(path)
            if (file.exists()) {
                file.delete()
            }
        }
    }

    private suspend fun streamResponseBody(url: String, outputFile: File) {
        val request = Request.Builder().url(url).get().build()
        val call = okHttpClient.newCall(request)
        val cancellationHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                call.cancel()
            }
        }
        try {
            call.execute().use { response ->
                ensureSuccessfulRecordingResponse(response)
                val body = response.body ?: throw IOException("Recording stream returned an empty body")
                body.byteStream().use { input ->
                    FileOutputStream(outputFile, false).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val bytes = input.read(buffer)
                            if (bytes <= 0) {
                                break
                            }
                            output.write(buffer, 0, bytes)
                        }
                    }
                }
            }
        } finally {
            cancellationHandle?.dispose()
        }
    }

    private fun ensureSuccessfulRecordingResponse(response: Response) {
        if (response.isSuccessful) {
            return
        }
        if (response.code in 500..599 || response.code == 429) {
            throw IOException("Transient HTTP ${response.code}")
        }
        throw IllegalStateException("Recording stream failed with HTTP ${response.code}")
    }

    private fun isRetryableRecordingError(error: Throwable): Boolean {
        if (error is IOException) {
            return true
        }
        val message = error.message.orEmpty().lowercase()
        return message.contains("timeout") ||
            message.contains("timed out") ||
            message.contains("connection reset") ||
            message.contains("connect") ||
            message.contains("network")
    }

    private companion object {
        val ACTIVE_RECORDING_STATUSES = setOf(RecordingStatus.SCHEDULED, RecordingStatus.RECORDING)
        const val MAX_RECORDING_RETRIES = 3
        const val FINISHED_RECORDING_RETENTION_MS = 30L * 24L * 60L * 60L * 1000L
        const val RETENTION_SWEEP_INTERVAL_MS = 60L * 60L * 1000L
    }
}

private data class RecordingStartDecision(
    val recording: RecordingItem,
    val conflict: RecordingItem?
)

internal fun List<RecordingItem>.findRecordingConflict(
    candidateStartMs: Long,
    candidateEndMs: Long,
    ignoreRecordingId: String? = null,
    statuses: Set<RecordingStatus>
): RecordingItem? = asSequence()
    .filter { it.id != ignoreRecordingId }
    .filter { it.status in statuses }
    .sortedWith(compareBy<RecordingItem> { it.scheduledStartMs }.thenBy { it.id })
    .firstOrNull { existing ->
        candidateStartMs < existing.scheduledEndMs && existing.scheduledStartMs < candidateEndMs
    }

private fun recordingConflictMessage(conflict: RecordingItem): String {
    val title = conflict.programTitle?.takeIf { it.isNotBlank() } ?: conflict.channelName
    val stateLabel = when (conflict.status) {
        RecordingStatus.SCHEDULED -> "scheduled"
        RecordingStatus.RECORDING -> "active"
        RecordingStatus.COMPLETED,
        RecordingStatus.FAILED,
        RecordingStatus.CANCELLED -> "finished"
    }
    return "Recording conflicts with an existing $stateLabel recording for $title on ${conflict.channelName}."
}

private val RecordingRecurrence.intervalMs: Long
    get() = when (this) {
        RecordingRecurrence.NONE -> 0L
        RecordingRecurrence.DAILY -> 24L * 60L * 60L * 1000L
        RecordingRecurrence.WEEKLY -> 7L * 24L * 60L * 60L * 1000L
    }

private fun RecordingStatus.isTerminal(): Boolean = when (this) {
    RecordingStatus.SCHEDULED,
    RecordingStatus.RECORDING -> false
    RecordingStatus.COMPLETED,
    RecordingStatus.FAILED,
    RecordingStatus.CANCELLED -> true
}
