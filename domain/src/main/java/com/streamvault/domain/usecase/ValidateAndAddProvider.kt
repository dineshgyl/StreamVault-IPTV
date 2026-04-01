package com.streamvault.domain.usecase

import com.streamvault.domain.manager.ProviderSetupInputValidator
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.ProviderRepository
import javax.inject.Inject

data class XtreamProviderSetupCommand(
    val serverUrl: String,
    val username: String,
    val password: String,
    val name: String,
    val xtreamFastSyncEnabled: Boolean = true,
    val existingProviderId: Long? = null
)

data class M3uProviderSetupCommand(
    val url: String,
    val name: String,
    val existingProviderId: Long? = null
)

sealed class ValidateAndAddProviderResult {
    data class Success(val provider: Provider) : ValidateAndAddProviderResult()
    data class ValidationError(val message: String) : ValidateAndAddProviderResult()
    data class Error(val message: String, val exception: Throwable? = null) : ValidateAndAddProviderResult()
}

class ValidateAndAddProvider @Inject constructor(
    private val providerSetupInputValidator: ProviderSetupInputValidator,
    private val providerRepository: ProviderRepository
) {
    suspend fun loginXtream(
        command: XtreamProviderSetupCommand,
        onProgress: ((String) -> Unit)? = null
    ): ValidateAndAddProviderResult {
        return when (
            val validated = providerSetupInputValidator.validateXtream(
                serverUrl = command.serverUrl,
                username = command.username,
                name = command.name
            )
        ) {
            is Result.Success -> providerRepository.loginXtream(
                serverUrl = validated.data.serverUrl,
                username = validated.data.username,
                password = command.password,
                name = validated.data.name,
                xtreamFastSyncEnabled = command.xtreamFastSyncEnabled,
                onProgress = onProgress,
                id = command.existingProviderId
            ).toUseCaseResult()

            is Result.Error -> ValidateAndAddProviderResult.ValidationError(validated.message)
            is Result.Loading -> ValidateAndAddProviderResult.Error("Unexpected loading state")
        }
    }

    suspend fun addM3u(
        command: M3uProviderSetupCommand,
        onProgress: ((String) -> Unit)? = null
    ): ValidateAndAddProviderResult {
        return when (
            val validated = providerSetupInputValidator.validateM3u(
                url = command.url,
                name = command.name
            )
        ) {
            is Result.Success -> providerRepository.validateM3u(
                url = validated.data.url,
                name = validated.data.name,
                onProgress = onProgress,
                id = command.existingProviderId
            ).toUseCaseResult()

            is Result.Error -> ValidateAndAddProviderResult.ValidationError(validated.message)
            is Result.Loading -> ValidateAndAddProviderResult.Error("Unexpected loading state")
        }
    }

    private fun Result<Provider>.toUseCaseResult(): ValidateAndAddProviderResult = when (this) {
        is Result.Success -> ValidateAndAddProviderResult.Success(data)
        is Result.Error -> ValidateAndAddProviderResult.Error(message, exception)
        is Result.Loading -> ValidateAndAddProviderResult.Error("Unexpected loading state")
    }
}
