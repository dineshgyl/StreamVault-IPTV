package com.streamvault.app.ui.screens.vod

import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.VirtualGroup
import com.streamvault.domain.repository.FavoriteRepository

data class VodDialogSelection<T>(
    val selectedItem: T,
    val groupMemberships: List<Long>
)

suspend fun <T> loadVodDialogSelection(
    item: T,
    itemId: Long,
    contentType: ContentType,
    favoriteRepository: FavoriteRepository,
    copyWithFavorite: (T, Boolean) -> T
): VodDialogSelection<T> {
    val memberships = favoriteRepository.getGroupMemberships(itemId, contentType)
    val isFavorite = favoriteRepository.isFavorite(itemId, contentType)
    return VodDialogSelection(
        selectedItem = copyWithFavorite(item, isFavorite),
        groupMemberships = memberships
    )
}

suspend fun setVodFavorite(
    itemId: Long,
    contentType: ContentType,
    isFavorite: Boolean,
    favoriteRepository: FavoriteRepository
) {
    if (isFavorite) {
        favoriteRepository.addFavorite(itemId, contentType)
    } else {
        favoriteRepository.removeFavorite(itemId, contentType)
    }
}

suspend fun updateVodGroupMembership(
    itemId: Long,
    groupId: Long,
    contentType: ContentType,
    shouldBeMember: Boolean,
    favoriteRepository: FavoriteRepository
): List<Long> {
    val encodedGroupId = -groupId
    if (shouldBeMember) {
        favoriteRepository.addFavorite(itemId, contentType, groupId = encodedGroupId)
    } else {
        favoriteRepository.removeFavorite(itemId, contentType, groupId = encodedGroupId)
    }
    return favoriteRepository.getGroupMemberships(itemId, contentType)
}

suspend fun createVodGroup(
    name: String,
    contentType: ContentType,
    favoriteRepository: FavoriteRepository
): Result<VirtualGroup> =
    favoriteRepository.createGroup(name, contentType = contentType)
