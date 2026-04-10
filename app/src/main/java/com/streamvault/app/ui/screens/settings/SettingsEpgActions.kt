package com.streamvault.app.ui.screens.settings

import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.EpgSourceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class SettingsEpgActions(
    private val epgSourceRepository: EpgSourceRepository,
    private val uiState: MutableStateFlow<SettingsUiState>
) {
    fun loadEpgSources(scope: CoroutineScope) {
        scope.launch {
            epgSourceRepository.getAllSources().collect { sources ->
                uiState.update { it.copy(epgSources = sources) }
            }
        }
    }

    fun loadEpgAssignments(scope: CoroutineScope, providerId: Long) {
        scope.launch {
            epgSourceRepository.getAssignmentsForProvider(providerId).collect { assignments ->
                val summary = epgSourceRepository.getResolutionSummary(providerId)
                uiState.update {
                    it.copy(
                        epgSourceAssignments = it.epgSourceAssignments + (providerId to assignments),
                        epgResolutionSummaries = it.epgResolutionSummaries + (providerId to summary)
                    )
                }
            }
        }
    }

    fun addEpgSource(scope: CoroutineScope, name: String, url: String) {
        scope.launch {
            val result = epgSourceRepository.addSource(name, url)
            if (result is Result.Error) {
                uiState.update { it.copy(userMessage = result.message) }
            }
        }
    }

    fun deleteEpgSource(scope: CoroutineScope, sourceId: Long) {
        scope.launch {
            val affectedProviders = loadedProvidersForSource(sourceId)
            epgSourceRepository.deleteSource(sourceId)
            refreshLoadedResolutionSummaries(affectedProviders)
        }
    }

    fun toggleEpgSourceEnabled(scope: CoroutineScope, sourceId: Long, enabled: Boolean) {
        scope.launch {
            val affectedProviders = loadedProvidersForSource(sourceId)
            epgSourceRepository.setSourceEnabled(sourceId, enabled)
            refreshLoadedResolutionSummaries(affectedProviders)
        }
    }

    fun refreshEpgSource(scope: CoroutineScope, sourceId: Long) {
        scope.launch {
            uiState.update { it.copy(isSyncing = true) }
            val affectedProviders = loadedProvidersForSource(sourceId)
            val result = epgSourceRepository.refreshSource(sourceId)
            if (result !is Result.Error) {
                refreshLoadedResolutionSummaries(affectedProviders)
            }
            uiState.update {
                it.copy(
                    isSyncing = false,
                    userMessage = if (result is Result.Error) result.message else "EPG source refreshed"
                )
            }
        }
    }

    fun assignEpgSourceToProvider(scope: CoroutineScope, providerId: Long, epgSourceId: Long) {
        scope.launch {
            val existingAssignments = uiState.value.epgSourceAssignments[providerId].orEmpty()
            val nextPriority = (existingAssignments.maxOfOrNull { it.priority } ?: 0) + 1
            val result = epgSourceRepository.assignSourceToProvider(providerId, epgSourceId, nextPriority)
            if (result is Result.Error) {
                uiState.update { it.copy(userMessage = result.message) }
            } else {
                refreshProviderEpgSummary(providerId)
            }
        }
    }

    fun unassignEpgSourceFromProvider(scope: CoroutineScope, providerId: Long, epgSourceId: Long) {
        scope.launch {
            epgSourceRepository.unassignSourceFromProvider(providerId, epgSourceId)
            refreshProviderEpgSummary(providerId)
        }
    }

    fun moveEpgSourceAssignmentUp(scope: CoroutineScope, providerId: Long, epgSourceId: Long) {
        scope.launch {
            val assignments = uiState.value.epgSourceAssignments[providerId].orEmpty().sortedBy { it.priority }
            val index = assignments.indexOfFirst { it.epgSourceId == epgSourceId }
            if (index <= 0) return@launch
            val current = assignments[index]
            val previous = assignments[index - 1]
            epgSourceRepository.updateAssignmentPriority(providerId, current.epgSourceId, previous.priority)
            epgSourceRepository.updateAssignmentPriority(providerId, previous.epgSourceId, current.priority)
            refreshProviderEpgSummary(providerId)
        }
    }

    fun moveEpgSourceAssignmentDown(scope: CoroutineScope, providerId: Long, epgSourceId: Long) {
        scope.launch {
            val assignments = uiState.value.epgSourceAssignments[providerId].orEmpty().sortedBy { it.priority }
            val index = assignments.indexOfFirst { it.epgSourceId == epgSourceId }
            if (index == -1 || index >= assignments.lastIndex) return@launch
            val current = assignments[index]
            val next = assignments[index + 1]
            epgSourceRepository.updateAssignmentPriority(providerId, current.epgSourceId, next.priority)
            epgSourceRepository.updateAssignmentPriority(providerId, next.epgSourceId, current.priority)
            refreshProviderEpgSummary(providerId)
        }
    }

    private suspend fun refreshProviderEpgSummary(providerId: Long) {
        val summary = epgSourceRepository.getResolutionSummary(providerId)
        uiState.update {
            it.copy(epgResolutionSummaries = it.epgResolutionSummaries + (providerId to summary))
        }
    }

    private fun loadedProvidersForSource(sourceId: Long): List<Long> =
        uiState.value.epgSourceAssignments
            .filterValues { assignments -> assignments.any { it.epgSourceId == sourceId } }
            .keys
            .toList()

    private suspend fun refreshLoadedResolutionSummaries(providerIds: Iterable<Long>) {
        providerIds.asSequence().distinct().forEach { providerId ->
            refreshProviderEpgSummary(providerId)
        }
    }
}
