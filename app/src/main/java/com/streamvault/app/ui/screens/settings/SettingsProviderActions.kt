package com.streamvault.app.ui.screens.settings

import com.streamvault.app.tvinput.TvInputChannelSyncManager
import com.streamvault.domain.model.ActiveLiveSource
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.CombinedM3uRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.repository.SyncMetadataRepository
import com.streamvault.domain.usecase.SyncProvider
import com.streamvault.domain.usecase.SyncProviderCommand
import com.streamvault.domain.usecase.SyncProviderResult
import com.streamvault.data.preferences.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class SettingsProviderActions(
    private val providerRepository: ProviderRepository,
    private val combinedM3uRepository: CombinedM3uRepository,
    private val preferencesRepository: PreferencesRepository,
    private val syncProvider: SyncProvider,
    private val tvInputChannelSyncManager: TvInputChannelSyncManager,
    private val uiState: MutableStateFlow<SettingsUiState>
) {
    fun setActiveProvider(scope: CoroutineScope, providerId: Long) {
        scope.launch {
            preferencesRepository.setLastActiveProviderId(providerId)
            combinedM3uRepository.setActiveLiveSource(ActiveLiveSource.ProviderSource(providerId))
            providerRepository.setActiveProvider(providerId)
            refreshProvider(scope, providerId)
        }
    }

    fun setActiveCombinedProfile(scope: CoroutineScope, profileId: Long) {
        scope.launch {
            when (combinedM3uRepository.setActiveLiveSource(ActiveLiveSource.CombinedM3uSource(profileId))) {
                is Result.Success -> uiState.update { it.copy(userMessage = "Combined M3U source activated") }
                is Result.Error -> uiState.update { it.copy(userMessage = "Could not activate combined source") }
                Result.Loading -> Unit
            }
        }
    }

    fun createCombinedProfile(scope: CoroutineScope, name: String, providerIds: List<Long>) {
        scope.launch {
            when (val result = combinedM3uRepository.createProfile(name, providerIds)) {
                is Result.Success -> {
                    combinedM3uRepository.setActiveLiveSource(ActiveLiveSource.CombinedM3uSource(result.data.id))
                    uiState.update { it.copy(userMessage = "Combined M3U source created") }
                }
                is Result.Error -> uiState.update { it.copy(userMessage = result.message) }
                Result.Loading -> Unit
            }
        }
    }

    fun deleteCombinedProfile(scope: CoroutineScope, profileId: Long) {
        scope.launch {
            when (val result = combinedM3uRepository.deleteProfile(profileId)) {
                is Result.Success -> uiState.update { it.copy(userMessage = "Combined M3U source deleted") }
                is Result.Error -> uiState.update { it.copy(userMessage = result.message) }
                Result.Loading -> Unit
            }
        }
    }

    fun addProviderToCombinedProfile(scope: CoroutineScope, profileId: Long, providerId: Long) {
        scope.launch {
            when (val result = combinedM3uRepository.addProvider(profileId, providerId)) {
                is Result.Success -> uiState.update { it.copy(userMessage = "Playlist added to combined source") }
                is Result.Error -> uiState.update { it.copy(userMessage = result.message) }
                Result.Loading -> Unit
            }
        }
    }

    fun renameCombinedProfile(scope: CoroutineScope, profileId: Long, name: String) {
        scope.launch {
            when (val result = combinedM3uRepository.updateProfileName(profileId, name)) {
                is Result.Success -> uiState.update { it.copy(userMessage = "Combined M3U source renamed") }
                is Result.Error -> uiState.update { it.copy(userMessage = result.message) }
                Result.Loading -> Unit
            }
        }
    }

    fun removeProviderFromCombinedProfile(scope: CoroutineScope, profileId: Long, providerId: Long) {
        scope.launch {
            when (val result = combinedM3uRepository.removeProvider(profileId, providerId)) {
                is Result.Success -> uiState.update { it.copy(userMessage = "Playlist removed from combined source") }
                is Result.Error -> uiState.update { it.copy(userMessage = result.message) }
                Result.Loading -> Unit
            }
        }
    }

    fun moveCombinedProvider(scope: CoroutineScope, profileId: Long, providerId: Long, moveUp: Boolean) {
        scope.launch {
            val profile = uiState.value.combinedProfiles.firstOrNull { it.id == profileId } ?: return@launch
            val orderedProviderIds = profile.members.sortedBy { it.priority }.map { it.providerId }.toMutableList()
            val currentIndex = orderedProviderIds.indexOf(providerId)
            if (currentIndex == -1) return@launch
            val targetIndex = if (moveUp) currentIndex - 1 else currentIndex + 1
            if (targetIndex !in orderedProviderIds.indices) return@launch
            java.util.Collections.swap(orderedProviderIds, currentIndex, targetIndex)
            when (val result = combinedM3uRepository.reorderMembers(profileId, orderedProviderIds)) {
                is Result.Success -> uiState.update { it.copy(userMessage = "Combined playlist order updated") }
                is Result.Error -> uiState.update { it.copy(userMessage = result.message) }
                Result.Loading -> Unit
            }
        }
    }

    fun setCombinedProviderEnabled(scope: CoroutineScope, profileId: Long, providerId: Long, enabled: Boolean) {
        scope.launch {
            when (val result = combinedM3uRepository.setMemberEnabled(profileId, providerId, enabled)) {
                is Result.Success -> uiState.update {
                    it.copy(userMessage = if (enabled) "Playlist enabled in combined source" else "Playlist disabled in combined source")
                }
                is Result.Error -> uiState.update { it.copy(userMessage = result.message) }
                Result.Loading -> Unit
            }
        }
    }

    fun setM3uVodClassificationEnabled(scope: CoroutineScope, providerId: Long, enabled: Boolean) {
        scope.launch {
            val provider = providerRepository.getProvider(providerId) ?: return@launch
            if (provider.type != ProviderType.M3U) return@launch
            when (val result = providerRepository.updateProvider(provider.copy(m3uVodClassificationEnabled = enabled))) {
                is Result.Error -> uiState.update { it.copy(userMessage = "Could not save provider setting: ${result.message}") }
                else -> uiState.update {
                    it.copy(
                        userMessage = if (enabled) {
                            "M3U VOD classification enabled. Refresh the playlist to reclassify content."
                        } else {
                            "M3U VOD classification disabled. Refresh the playlist to reclassify content."
                        }
                    )
                }
            }
        }
    }

    fun refreshProvider(scope: CoroutineScope, providerId: Long, movieFastSyncOverride: Boolean? = null) {
        scope.launch {
            uiState.update { it.copy(isSyncing = true) }
            try {
                val result = syncProvider(
                    SyncProviderCommand(
                        providerId = providerId,
                        force = true,
                        movieFastSyncOverride = movieFastSyncOverride
                    )
                )
                if (result !is SyncProviderResult.Error) {
                    tvInputChannelSyncManager.refreshTvInputCatalog()
                }
                uiState.update { state ->
                    val partialWarnings = (result as? SyncProviderResult.Success)?.warnings.orEmpty()
                    val warningsMessage = partialWarnings.take(3).joinToString(separator = ", ").ifBlank { "Some sections are incomplete." }
                    state.copy(
                        isSyncing = false,
                        userMessage = when {
                            result is SyncProviderResult.Error -> "Refresh failed: ${result.message}"
                            (result as? SyncProviderResult.Success)?.isPartial == true -> "Refresh completed with warnings: $warningsMessage"
                            else -> "Provider refreshed successfully"
                        },
                        syncWarningsByProvider = when {
                            result is SyncProviderResult.Error -> state.syncWarningsByProvider - providerId
                            (result as? SyncProviderResult.Success)?.isPartial == true -> state.syncWarningsByProvider + (providerId to partialWarnings)
                            else -> state.syncWarningsByProvider - providerId
                        }
                    )
                }
            } catch (e: Exception) {
                uiState.update { it.copy(isSyncing = false, userMessage = "Sync failed: ${e.message}") }
            }
        }
    }

    fun deleteProvider(scope: CoroutineScope, providerId: Long) {
        scope.launch {
            providerRepository.deleteProvider(providerId)
        }
    }
}
