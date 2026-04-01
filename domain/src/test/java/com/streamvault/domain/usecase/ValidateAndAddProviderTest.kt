package com.streamvault.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.manager.ProviderSetupInputValidator
import com.streamvault.domain.model.Program
import com.streamvault.domain.manager.ValidatedM3uProviderInput
import com.streamvault.domain.manager.ValidatedXtreamProviderInput
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.ProviderStatus
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.ProviderRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ValidateAndAddProviderTest {

    @Test
    fun returns_validation_error_without_calling_repository_for_xtream() = runTest {
        val repository = FakeProviderRepository()
        val useCase = ValidateAndAddProvider(
            providerSetupInputValidator = FakeProviderSetupInputValidator(
                xtreamResult = Result.error("Please enter server URL")
            ),
            providerRepository = repository
        )

        val result = useCase.loginXtream(
            XtreamProviderSetupCommand(
                serverUrl = "",
                username = "user",
                password = "secret",
                name = "Provider"
            )
        )

        assertThat(result).isInstanceOf(ValidateAndAddProviderResult.ValidationError::class.java)
        assertThat((result as ValidateAndAddProviderResult.ValidationError).message).isEqualTo("Please enter server URL")
        assertThat(repository.lastXtreamCall).isNull()
    }

    @Test
    fun delegates_normalized_xtream_input_to_repository() = runTest {
        val repository = FakeProviderRepository()
        val useCase = ValidateAndAddProvider(
            providerSetupInputValidator = FakeProviderSetupInputValidator(
                xtreamResult = Result.success(
                    ValidatedXtreamProviderInput(
                        serverUrl = "https://example.com",
                        username = "alice",
                        name = "Premium"
                    )
                )
            ),
            providerRepository = repository
        )

        val result = useCase.loginXtream(
            XtreamProviderSetupCommand(
                serverUrl = " https://example.com ",
                username = " alice ",
                password = "secret",
                name = " Premium ",
                xtreamFastSyncEnabled = true,
                existingProviderId = 7L
            )
        )

        assertThat(result).isInstanceOf(ValidateAndAddProviderResult.Success::class.java)
        assertThat(repository.lastXtreamCall).isEqualTo(
            XtreamCall(
                serverUrl = "https://example.com",
                username = "alice",
                password = "secret",
                name = "Premium",
                xtreamFastSyncEnabled = true,
                id = 7L
            )
        )
    }

    @Test
    fun delegates_validated_m3u_input_to_repository() = runTest {
        val repository = FakeProviderRepository()
        val useCase = ValidateAndAddProvider(
            providerSetupInputValidator = FakeProviderSetupInputValidator(
                m3uResult = Result.success(
                    ValidatedM3uProviderInput(
                        url = "file://playlist.m3u",
                        name = "Local Playlist"
                    )
                )
            ),
            providerRepository = repository
        )

        val result = useCase.addM3u(
            M3uProviderSetupCommand(
                url = "file://playlist.m3u",
                name = "Local Playlist",
                existingProviderId = 11L
            )
        )

        assertThat(result).isInstanceOf(ValidateAndAddProviderResult.Success::class.java)
        assertThat(repository.lastM3uCall).isEqualTo(
            M3uCall(
                url = "file://playlist.m3u",
                name = "Local Playlist",
                id = 11L
            )
        )
    }
}

private class FakeProviderSetupInputValidator(
    private val xtreamResult: Result<ValidatedXtreamProviderInput> = Result.success(
        ValidatedXtreamProviderInput(
            serverUrl = "https://example.com",
            username = "user",
            name = "Provider"
        )
    ),
    private val m3uResult: Result<ValidatedM3uProviderInput> = Result.success(
        ValidatedM3uProviderInput(
            url = "https://example.com/playlist.m3u",
            name = "Playlist"
        )
    )
) : ProviderSetupInputValidator {
    override fun validateXtream(
        serverUrl: String,
        username: String,
        name: String
    ): Result<ValidatedXtreamProviderInput> = xtreamResult

    override fun validateM3u(
        url: String,
        name: String
    ): Result<ValidatedM3uProviderInput> = m3uResult
}

private data class XtreamCall(
    val serverUrl: String,
    val username: String,
    val password: String,
    val name: String,
    val xtreamFastSyncEnabled: Boolean,
    val id: Long?
)

private data class M3uCall(
    val url: String,
    val name: String,
    val id: Long?
)

private class FakeProviderRepository : ProviderRepository {
    var lastXtreamCall: XtreamCall? = null
    var lastM3uCall: M3uCall? = null

    override fun getProviders(): Flow<List<Provider>> = flowOf(emptyList())

    override fun getActiveProvider(): Flow<Provider?> = flowOf(null)

    override suspend fun getProvider(id: Long): Provider? = null

    override suspend fun addProvider(provider: Provider): Result<Long> = error("Not used in test")

    override suspend fun updateProvider(provider: Provider): Result<Unit> = error("Not used in test")

    override suspend fun deleteProvider(id: Long): Result<Unit> = error("Not used in test")

    override suspend fun setActiveProvider(id: Long): Result<Unit> = error("Not used in test")

    override suspend fun loginXtream(
        serverUrl: String,
        username: String,
        password: String,
        name: String,
        xtreamFastSyncEnabled: Boolean,
        onProgress: ((String) -> Unit)?,
        id: Long?
    ): Result<Provider> {
        lastXtreamCall = XtreamCall(serverUrl, username, password, name, xtreamFastSyncEnabled, id)
        return Result.success(provider(id = id ?: 1L, name = name, type = ProviderType.XTREAM_CODES))
    }

    override suspend fun validateM3u(
        url: String,
        name: String,
        onProgress: ((String) -> Unit)?,
        id: Long?
    ): Result<Provider> {
        lastM3uCall = M3uCall(url, name, id)
        return Result.success(provider(id = id ?: 2L, name = name, type = ProviderType.M3U, m3uUrl = url))
    }

    override suspend fun refreshProviderData(
        providerId: Long,
        force: Boolean,
        onProgress: ((String) -> Unit)?
    ): Result<Unit> = error("Not used in test")

    override suspend fun getProgramsForLiveStream(
        providerId: Long,
        streamId: Long,
        epgChannelId: String?,
        limit: Int
    ): Result<List<Program>> = error("Not used in test")

    override suspend fun buildCatchUpUrl(providerId: Long, streamId: Long, start: Long, end: Long): String? = null

    private fun provider(
        id: Long,
        name: String,
        type: ProviderType,
        m3uUrl: String = ""
    ) = Provider(
        id = id,
        name = name,
        type = type,
        serverUrl = if (type == ProviderType.M3U) m3uUrl else "https://example.com",
        username = if (type == ProviderType.XTREAM_CODES) "user" else "",
        m3uUrl = m3uUrl,
        status = ProviderStatus.ACTIVE
    )
}
