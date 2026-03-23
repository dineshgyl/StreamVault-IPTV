package com.streamvault.app.ui.screens.movies

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.app.util.isPlaybackComplete
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.MovieRepository
import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.repository.ProviderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class MovieDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val movieRepository: MovieRepository,
    private val providerRepository: ProviderRepository,
    private val playbackHistoryRepository: PlaybackHistoryRepository
) : ViewModel() {

    private val movieId: Long = checkNotNull(
        savedStateHandle.get<Long>("movieId")
            ?: savedStateHandle.get<String>("movieId")?.toLongOrNull()
    )

    private val _uiState = MutableStateFlow(MovieDetailUiState())
    val uiState: StateFlow<MovieDetailUiState> = _uiState.asStateFlow()

    init {
        loadMovieDetails()
    }

    private fun loadMovieDetails() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            val provider = providerRepository.getActiveProvider().first()
            if (provider == null) {
                _uiState.update { it.copy(isLoading = false, error = "No active provider") }
                return@launch
            }

            val playbackHistory = playbackHistoryRepository.getPlaybackHistory(
                contentId = movieId,
                contentType = ContentType.MOVIE,
                providerId = provider.id
            )

            when (val result = movieRepository.getMovieDetails(provider.id, movieId)) {
                is Result.Success -> _uiState.update {
                    val movie = result.data
                    val movieDurationMs = movie.durationSeconds.takeIf { it > 0 }?.times(1000L) ?: 0L
                    val resumePositionMs = playbackHistory?.resumePositionMs ?: movie.watchProgress
                    val hasResume = resumePositionMs > 5000L && !isPlaybackComplete(
                        progressMs = resumePositionMs,
                        totalDurationMs = playbackHistory?.totalDurationMs?.takeIf { it > 0L } ?: movieDurationMs
                    )
                    it.copy(isLoading = false, movie = result.data, error = null)
                        .copy(
                            hasResume = hasResume,
                            resumePositionMs = if (hasResume) resumePositionMs else 0L
                        )
                }
                is Result.Error -> _uiState.update {
                    it.copy(isLoading = false, error = result.message)
                }
                is Result.Loading -> _uiState.update {
                    it.copy(isLoading = true)
                }
            }
        }
    }
}

data class MovieDetailUiState(
    val isLoading: Boolean = false,
    val movie: Movie? = null,
    val error: String? = null,
    val hasResume: Boolean = false,
    val resumePositionMs: Long = 0L
)
