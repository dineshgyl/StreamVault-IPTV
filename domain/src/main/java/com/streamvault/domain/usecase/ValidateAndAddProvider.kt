package com.streamvault.domain.usecase

import com.streamvault.domain.manager.ProviderSetupInputValidator
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.ProviderRepository
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject

data class XtreamProviderSetupCommand(
    val serverUrl: String,
    val username: String,
    val password: String,
    val name: String,
    val xtreamFastSyncEnabled: Boolean = true,
    val epgSyncMode: ProviderEpgSyncMode = ProviderEpgSyncMode.UPFRONT,
    val existingProviderId: Long? = null
)

data class M3uProviderSetupCommand(
    val url: String,
    val name: String,
    val epgSyncMode: ProviderEpgSyncMode = ProviderEpgSyncMode.UPFRONT,
    val m3uVodClassificationEnabled: Boolean = true,
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
                epgSyncMode = command.epgSyncMode,
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
            is Result.Success -> {
                val validatedInput = validated.data
                val parsedXtream = parseXtreamPlaylistUrl(validatedInput.url)
                if (parsedXtream != null) {
                    providerRepository.loginXtream(
                        serverUrl = parsedXtream.serverUrl,
                        username = parsedXtream.username,
                        password = parsedXtream.password,
                        name = validatedInput.name,
                        xtreamFastSyncEnabled = true,
                        epgSyncMode = command.epgSyncMode,
                        onProgress = onProgress,
                        id = command.existingProviderId
                    ).toUseCaseResult()
                } else {
                    providerRepository.validateM3u(
                        url = validatedInput.url,
                        name = validatedInput.name,
                        epgSyncMode = command.epgSyncMode,
                        m3uVodClassificationEnabled = command.m3uVodClassificationEnabled,
                        onProgress = onProgress,
                        id = command.existingProviderId
                    ).toUseCaseResult()
                }
            }

            is Result.Error -> ValidateAndAddProviderResult.ValidationError(validated.message)
            is Result.Loading -> ValidateAndAddProviderResult.Error("Unexpected loading state")
        }
    }

    private fun Result<Provider>.toUseCaseResult(): ValidateAndAddProviderResult = when (this) {
        is Result.Success -> ValidateAndAddProviderResult.Success(data)
        is Result.Error -> ValidateAndAddProviderResult.Error(message, exception)
        is Result.Loading -> ValidateAndAddProviderResult.Error("Unexpected loading state")
    }

    private data class ParsedXtreamPlaylistUrl(
        val serverUrl: String,
        val username: String,
        val password: String
    )

    private fun parseXtreamPlaylistUrl(url: String): ParsedXtreamPlaylistUrl? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null

        val normalizedPath = uri.path.orEmpty().lowercase()
        if (!normalizedPath.endsWith("/get.php")) return null

        val query = parseQueryParameters(uri.rawQuery)
        val username = query["username"]?.takeIf { it.isNotBlank() } ?: return null
        val password = query["password"]?.takeIf { it.isNotBlank() } ?: return null
        val type = query["type"]?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        if (type != "m3u" && type != "m3u_plus") return null

        val authority = uri.rawAuthority?.takeIf { it.isNotBlank() } ?: return null
        return ParsedXtreamPlaylistUrl(
            serverUrl = "$scheme://$authority",
            username = username,
            password = password
        )
    }

    private fun parseQueryParameters(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split('&')
            .mapNotNull { part ->
                val key = part.substringBefore('=', "")
                    .takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val value = part.substringAfter('=', "")
                decodeQueryComponent(key) to decodeQueryComponent(value)
            }
            .toMap()
    }

    private fun decodeQueryComponent(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}
