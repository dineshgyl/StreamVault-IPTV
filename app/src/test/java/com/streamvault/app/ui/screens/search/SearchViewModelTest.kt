package com.streamvault.app.ui.screens.search

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Series
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.manager.ParentalControlManager
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.usecase.SearchContent
import com.streamvault.domain.usecase.SearchContentResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val providerRepository: ProviderRepository = mock()
    private val searchContent: SearchContent = mock()
    private val preferencesRepository: PreferencesRepository = mock()
    private val parentalControlManager: ParentalControlManager = mock()

    private lateinit var viewModel: SearchViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
        whenever(providerRepository.getActiveProvider()).thenReturn(flowOf(null))
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(preferencesRepository.recentSearchQueries).thenReturn(flowOf(emptyList()))
        whenever(parentalControlManager.unlockedCategoriesForProvider(any())).thenReturn(flowOf(emptySet()))
        whenever(searchContent.invoke(any(), any(), any(), any())).thenReturn(flowOf(SearchContentResult()))

        viewModel = SearchViewModel(
            providerRepository,
            searchContent,
            preferencesRepository,
            parentalControlManager
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `submitted queries are stored trimmed deduplicated and capped`() = runTest {
        viewModel.onQueryChange(" news ")
        viewModel.onSearchSubmitted()
        viewModel.onQueryChange("sports")
        viewModel.onSearchSubmitted()
        viewModel.onQueryChange("NEWS")
        viewModel.onSearchSubmitted()
        viewModel.onQueryChange("movies")
        viewModel.onSearchSubmitted()
        viewModel.onQueryChange("series")
        viewModel.onSearchSubmitted()
        viewModel.onQueryChange("kids")
        viewModel.onSearchSubmitted()
        viewModel.onQueryChange("music")
        viewModel.onSearchSubmitted()

        assertThat(viewModel.recentQueries.value).containsExactly(
            "music",
            "kids",
            "series",
            "movies",
            "NEWS",
            "sports"
        ).inOrder()
    }

    @Test
    fun `clearRecentQueries removes all stored search shortcuts`() = runTest {
        viewModel.onQueryChange("news")
        viewModel.onSearchSubmitted()

        viewModel.clearRecentQueries()

        assertThat(viewModel.recentQueries.value).isEmpty()
    }

    @Test
    fun `ui state exposes provider and query readiness before results load`() = runTest {
        whenever(providerRepository.getActiveProvider()).thenReturn(
            flowOf(
                Provider(
                    id = 1L,
                    name = "Provider",
                    type = ProviderType.M3U,
                    serverUrl = "http://test"
                )
            )
        )

        viewModel = SearchViewModel(
            providerRepository,
            searchContent,
            preferencesRepository,
            parentalControlManager
        )

        val collectorJob = backgroundScope.launch { viewModel.uiState.collect { } }
        advanceUntilIdle()
        viewModel.onQueryChange("a")
        testScheduler.advanceTimeBy(400)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.hasActiveProvider).isTrue()
        assertThat(viewModel.uiState.value.queryLength).isEqualTo(1)
        assertThat(viewModel.uiState.value.hasSearched).isFalse()
        collectorJob.cancel()
    }

    @Test
    fun `search results flow through the shared search use case`() = runTest {
        whenever(providerRepository.getActiveProvider()).thenReturn(
            flowOf(
                Provider(
                    id = 5L,
                    name = "Provider",
                    type = ProviderType.M3U,
                    serverUrl = "http://test"
                )
            )
        )
        whenever(searchContent.invoke(any(), any(), any(), any())).thenReturn(
            flowOf(
                SearchContentResult(
                    channels = listOf(Channel(id = 1L, name = "News", streamUrl = "http://stream", providerId = 5L)),
                    movies = listOf(Movie(id = 2L, name = "Movie")),
                    series = listOf(Series(id = 3L, name = "Series"))
                )
            )
        )

        viewModel = SearchViewModel(
            providerRepository,
            searchContent,
            preferencesRepository,
            parentalControlManager
        )

        val collectorJob = backgroundScope.launch { viewModel.uiState.collect { } }
        advanceUntilIdle()
        viewModel.onQueryChange("news")
        testScheduler.advanceTimeBy(400)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.channels.map { it.id }).containsExactly(1L)
        assertThat(viewModel.uiState.value.movies.map { it.id }).containsExactly(2L)
        assertThat(viewModel.uiState.value.series.map { it.id }).containsExactly(3L)
        assertThat(viewModel.uiState.value.hasSearched).isTrue()
        collectorJob.cancel()
    }
}
