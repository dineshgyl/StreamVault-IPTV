package com.streamvault.app.ui.screens.settings

import android.app.Application
import com.streamvault.app.R
import com.streamvault.app.tvinput.TvInputChannelSyncManager
import com.streamvault.data.sync.SyncManager
import com.streamvault.data.sync.SyncRepairSection
import com.streamvault.domain.model.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class SettingsSyncActions(
    private val appContext: Application,
    private val syncManager: SyncManager,
    private val tvInputChannelSyncManager: TvInputChannelSyncManager,
    private val uiState: MutableStateFlow<SettingsUiState>,
    private val refreshProvider: (CoroutineScope, Long) -> Unit
) {
    fun syncProviderSection(scope: CoroutineScope, providerId: Long, selection: ProviderSyncSelection) {
        scope.launch {
            when (selection) {
                ProviderSyncSelection.ALL, ProviderSyncSelection.FAST -> refreshProvider(scope, providerId)
                else -> runSectionSync(providerId, listOf(selection))
            }
        }
    }

    fun syncProviderCustom(scope: CoroutineScope, providerId: Long, selections: Set<ProviderSyncSelection>) {
        scope.launch {
            val orderedSelections = listOf(
                ProviderSyncSelection.TV,
                ProviderSyncSelection.MOVIES,
                ProviderSyncSelection.SERIES,
                ProviderSyncSelection.EPG
            ).filter { it in selections }
            if (orderedSelections.isEmpty()) {
                uiState.update {
                    it.copy(userMessage = appContext.getString(R.string.settings_sync_custom_required))
                }
                return@launch
            }
            runSectionSync(providerId, orderedSelections)
        }
    }

    fun retryWarningAction(scope: CoroutineScope, providerId: Long, action: ProviderWarningAction) {
        scope.launch {
            uiState.update { it.copy(isSyncing = true) }
            val section = when (action) {
                ProviderWarningAction.EPG -> SyncRepairSection.EPG
                ProviderWarningAction.MOVIES -> SyncRepairSection.MOVIES
                ProviderWarningAction.SERIES -> SyncRepairSection.SERIES
            }
            val result = syncManager.retrySection(providerId, section)
            uiState.update { state ->
                if (result is Result.Error) {
                    state.copy(
                        isSyncing = false,
                        userMessage = "Retry failed: ${result.message}"
                    )
                } else {
                    val currentWarnings = state.syncWarningsByProvider[providerId].orEmpty()
                    val updatedWarnings = currentWarnings.filterNot { warning ->
                        when (action) {
                            ProviderWarningAction.EPG -> warning.contains("EPG", ignoreCase = true)
                            ProviderWarningAction.MOVIES -> warning.contains("Movies", ignoreCase = true)
                            ProviderWarningAction.SERIES -> warning.contains("Series", ignoreCase = true)
                        }
                    }
                    state.copy(
                        isSyncing = false,
                        userMessage = if (updatedWarnings.isEmpty()) {
                            "Section retry succeeded. All current warnings cleared."
                        } else {
                            "Section retry succeeded."
                        },
                        syncWarningsByProvider = if (updatedWarnings.isEmpty()) {
                            state.syncWarningsByProvider - providerId
                        } else {
                            state.syncWarningsByProvider + (providerId to updatedWarnings)
                        }
                    )
                }
            }
        }
    }

    private suspend fun runSectionSync(
        providerId: Long,
        selections: List<ProviderSyncSelection>
    ) {
        uiState.update { it.copy(isSyncing = true) }
        try {
            val failures = mutableListOf<String>()
            val completed = mutableListOf<String>()

            selections.forEach { selection ->
                val section = when (selection) {
                    ProviderSyncSelection.TV -> SyncRepairSection.LIVE
                    ProviderSyncSelection.MOVIES -> SyncRepairSection.MOVIES
                    ProviderSyncSelection.SERIES -> SyncRepairSection.SERIES
                    ProviderSyncSelection.EPG -> SyncRepairSection.EPG
                    ProviderSyncSelection.ALL, ProviderSyncSelection.FAST -> null
                } ?: return@forEach

                when (val result = syncManager.retrySection(providerId, section)) {
                    is Result.Error -> failures += "${selection.label(appContext)}: ${result.message}"
                    else -> completed += selection.label(appContext)
                }
            }

            if (completed.any {
                    it == appContext.getString(R.string.settings_sync_option_all) ||
                        it == appContext.getString(R.string.settings_sync_option_tv)
                }
            ) {
                tvInputChannelSyncManager.refreshTvInputCatalog()
            }

            uiState.update { state ->
                state.copy(
                    isSyncing = false,
                    userMessage = when {
                        failures.isEmpty() -> appContext.getString(
                            R.string.settings_sync_sections_success,
                            completed.joinToString()
                        )
                        completed.isEmpty() -> appContext.getString(
                            R.string.settings_sync_sections_failed,
                            failures.joinToString()
                        )
                        else -> appContext.getString(
                            R.string.settings_sync_sections_partial,
                            completed.joinToString(),
                            failures.joinToString()
                        )
                    }
                )
            }
        } catch (e: Exception) {
            uiState.update { it.copy(isSyncing = false, userMessage = "Sync failed: ${e.message}") }
        }
    }
}
