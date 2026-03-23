package com.streamvault.app.navigation

sealed interface ExternalNavigationRequest {
    data class Search(val query: String) : ExternalNavigationRequest
    data class Player(val request: PlayerNavigationRequest) : ExternalNavigationRequest
    data class Route(val route: String) : ExternalNavigationRequest
    data class ImportM3u(val uri: String) : ExternalNavigationRequest
}
