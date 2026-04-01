package com.streamvault.app.ui.screens.provider

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.usecase.M3uProviderSetupCommand
import com.streamvault.domain.usecase.ValidateAndAddProvider
import com.streamvault.domain.usecase.ValidateAndAddProviderResult
import com.streamvault.domain.usecase.XtreamProviderSetupCommand
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class ProviderSetupViewModel @Inject constructor(
    private val providerRepository: ProviderRepository,
    private val validateAndAddProvider: ValidateAndAddProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProviderSetupState())
    val uiState: StateFlow<ProviderSetupState> = _uiState.asStateFlow()
    private val _knownLocalM3uUrls = MutableStateFlow<Set<String>>(emptySet())
    val knownLocalM3uUrls: StateFlow<Set<String>> = _knownLocalM3uUrls.asStateFlow()

    init {
        viewModelScope.launch {
            providerRepository.getActiveProvider().collect { provider ->
                if (provider != null) {
                    _uiState.update { it.copy(hasExistingProvider = true) }
                }
            }
        }
        viewModelScope.launch {
            providerRepository.getProviders().collect { providers ->
                _knownLocalM3uUrls.value = providers
                    .mapNotNull { provider ->
                        provider.m3uUrl.takeIf { it.startsWith("file://") }
                    }
                    .toSet()
            }
        }
    }

    fun loadProvider(id: Long) {
        viewModelScope.launch {
            val provider = providerRepository.getProvider(id)
            if (provider != null) {
                _uiState.update {
                    it.copy(
                        isEditing = true,
                        existingProviderId = id,
                        name = provider.name,
                        serverUrl = provider.serverUrl,
                        username = provider.username,
                        password = "",
                        m3uUrl = provider.m3uUrl,
                        xtreamFastSyncEnabled = provider.xtreamFastSyncEnabled,
                        selectedTab = if (provider.type == ProviderType.M3U) 1 else 0,
                        m3uTab = if (provider.m3uUrl.startsWith("file://")) 1 else 0
                    )
                }
            }
        }
    }

    fun updateM3uTab(tab: Int) {
        _uiState.update { it.copy(m3uTab = tab) }
    }

    fun updateXtreamFastSyncEnabled(enabled: Boolean) {
        _uiState.update { it.copy(xtreamFastSyncEnabled = enabled) }
    }

    fun loginXtream(serverUrl: String, username: String, password: String, name: String) {
        _uiState.update { it.copy(validationError = null, error = null) }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, validationError = null, syncProgress = "Connecting...") }
            val existingId = if (_uiState.value.isEditing) _uiState.value.existingProviderId else null

            when (val result = validateAndAddProvider.loginXtream(
                XtreamProviderSetupCommand(
                    serverUrl = serverUrl,
                    username = username,
                    password = password,
                    name = name,
                    xtreamFastSyncEnabled = _uiState.value.xtreamFastSyncEnabled,
                    existingProviderId = existingId
                ),
                onProgress = { msg -> _uiState.update { it.copy(syncProgress = msg) } }
            )) {
                is ValidateAndAddProviderResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            loginSuccess = true,
                            error = null,
                            validationError = null,
                            syncProgress = null
                        )
                    }
                }
                is ValidateAndAddProviderResult.ValidationError -> {
                    _uiState.update {
                        it.copy(isLoading = false, validationError = result.message, error = null, syncProgress = null)
                    }
                }
                is ValidateAndAddProviderResult.Error -> {
                    val normalizedMessage = result.message.lowercase()
                    val userMessage = when {
                        normalizedMessage.contains("certificate") ||
                            normalizedMessage.contains("trust") ->
                            "Secure connection failed - the server's TLS certificate is not trusted on this device"
                        normalizedMessage.contains("provider login succeeded") ||
                            normalizedMessage.contains("initial sync failed") ->
                            "Login succeeded, but the initial sync failed while loading the playlist"
                        normalizedMessage.contains("authentication failed") ||
                            normalizedMessage.contains("invalid credentials") ||
                            (normalizedMessage.contains("username") && normalizedMessage.contains("password")) ->
                            "Login failed - please check your credentials and server URL"
                        normalizedMessage.contains("temporarily busy") ->
                            "Server is temporarily busy - try syncing again in a moment"
                        normalizedMessage.contains("unable to connect") ||
                            normalizedMessage.contains("timeout") ||
                            normalizedMessage.contains("network") ->
                            "Cannot reach server - check your internet connection and server URL"
                        else -> result.message
                    }
                    _uiState.update {
                        it.copy(isLoading = false, error = userMessage, validationError = null, syncProgress = null)
                    }
                }
            }
        }
    }

    fun addM3u(url: String, name: String) {
        _uiState.update { it.copy(validationError = null, error = null) }

        if (url.isBlank()) {
            _uiState.update {
                it.copy(validationError = if (_uiState.value.m3uTab == 0) "Please enter M3U URL" else "Please select a file")
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, validationError = null, syncProgress = "Validating...") }
            val existingId = if (_uiState.value.isEditing) _uiState.value.existingProviderId else null

            when (val result = validateAndAddProvider.addM3u(
                M3uProviderSetupCommand(
                    url = url,
                    name = name,
                    existingProviderId = existingId
                ),
                onProgress = { msg -> _uiState.update { it.copy(syncProgress = msg) } }
            )) {
                is ValidateAndAddProviderResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            loginSuccess = true,
                            error = null,
                            validationError = null,
                            syncProgress = null
                        )
                    }
                }
                is ValidateAndAddProviderResult.ValidationError -> {
                    _uiState.update {
                        it.copy(isLoading = false, validationError = result.message, error = null, syncProgress = null)
                    }
                }
                is ValidateAndAddProviderResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "Could not validate playlist: ${result.message}",
                            validationError = null,
                            syncProgress = null
                        )
                    }
                }
            }
        }
    }
}

data class ProviderSetupState(
    val isLoading: Boolean = false,
    val loginSuccess: Boolean = false,
    val hasExistingProvider: Boolean = false,
    val error: String? = null,
    val validationError: String? = null,
    val syncProgress: String? = null,
    val isEditing: Boolean = false,
    val existingProviderId: Long? = null,
    val selectedTab: Int = 0,
    val m3uTab: Int = 0,
    val name: String = "",
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val m3uUrl: String = "",
    val xtreamFastSyncEnabled: Boolean = true
)
