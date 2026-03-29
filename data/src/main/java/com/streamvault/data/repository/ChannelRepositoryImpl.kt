package com.streamvault.data.repository

import com.streamvault.data.local.dao.CategoryDao
import com.streamvault.data.local.dao.ChannelDao
import com.streamvault.data.local.entity.ChannelBrowseEntity
import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.local.entity.ChannelEntity
import com.streamvault.data.mapper.toDomain
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.ChannelNumberingMode
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.repository.ChannelRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import com.streamvault.data.util.toFtsPrefixQuery
import com.streamvault.data.util.rankSearchResults
import javax.inject.Inject
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.remote.xtream.XtreamStreamUrlResolver
import javax.inject.Singleton

@Singleton
class ChannelRepositoryImpl @Inject constructor(
    private val channelDao: ChannelDao,
    private val categoryDao: CategoryDao,
    private val preferencesRepository: PreferencesRepository,
    private val parentalControlManager: com.streamvault.domain.manager.ParentalControlManager,
    private val xtreamStreamUrlResolver: XtreamStreamUrlResolver
) : ChannelRepository {
    private companion object {
        const val GLOBAL_SEARCH_LIMIT = 200
        const val CATEGORY_SEARCH_LIMIT = 300
        const val MIN_SEARCH_QUERY_LENGTH = 2
        val QUALITY_HEIGHT_REGEX = Regex("(?<!\\d)(2160|1440|1080|720|576|480|360|240)p?(?!\\d)", RegexOption.IGNORE_CASE)
    }

    private data class ChannelGroupAccumulator(
        var primary: ChannelBrowseEntity,
        val alternatives: MutableList<ChannelBrowseEntity> = mutableListOf()
    )

    private val channelPriorityComparator = compareBy<ChannelBrowseEntity>({ it.errorCount }, { it.name.length })
    private val channelNumberComparator = compareBy<Channel>(
        { it.number <= 0 },
        { it.number.takeIf { number -> number > 0 } ?: Int.MAX_VALUE },
        { it.name.lowercase() }
    )

    override fun getChannels(providerId: Long): Flow<List<Channel>> =
        observeChannels(channelDao.getByProvider(providerId), providerId)

    override fun getChannelsByCategory(providerId: Long, categoryId: Long): Flow<List<Channel>> =
        observeChannels(channelFlow(providerId, categoryId), providerId)

    override fun getChannelsByNumber(providerId: Long, categoryId: Long): Flow<List<Channel>> =
        observeChannels(channelFlow(providerId, categoryId), providerId)
            .map(::sortChannelsByNumber)

    override fun getChannelsWithoutErrors(providerId: Long, categoryId: Long): Flow<List<Channel>> =
        observeChannels(channelFlowWithoutErrors(providerId, categoryId), providerId)
            .map(::sortChannelsByNumber)

    override fun searchChannelsByCategory(providerId: Long, categoryId: Long, query: String): Flow<List<Channel>> =
        query.trim().takeIf { it.length >= MIN_SEARCH_QUERY_LENGTH }?.toFtsPrefixQuery().let { ftsQuery ->
            if (ftsQuery.isNullOrBlank()) {
                flowOf(emptyList())
            } else {
                combine(
                    if (categoryId == ChannelRepository.ALL_CHANNELS_ID) {
                        channelDao.search(providerId, ftsQuery, CATEGORY_SEARCH_LIMIT)
                    } else {
                        channelDao.searchByCategory(providerId, categoryId, ftsQuery, CATEGORY_SEARCH_LIMIT)
                    },
                    preferencesRepository.parentalControlLevel,
                    parentalControlManager.unlockedCategoriesForProvider(providerId),
                    preferencesRepository.liveChannelNumberingMode
                ) { entities, level, unlockedCats, numberingMode ->
                    val filtered = if (level == 2) {
                        entities.filter { entity ->
                            val isUnlocked = entity.categoryId != null && unlockedCats.contains(entity.categoryId)
                            (!entity.isAdult && !entity.isUserProtected) || isUnlocked
                        }
                    } else {
                        entities
                    }

                    applyNumbering(groupAndMapChannels(filtered, unlockedCats), numberingMode)
                }
            }
        }

    override fun getCategories(providerId: Long): Flow<List<Category>> =
        combine(
            categoryDao.getByProviderAndType(providerId, ContentType.LIVE.name),
            channelDao.getGroupedCategoryCounts(providerId),
            preferencesRepository.parentalControlLevel,
            parentalControlManager.unlockedCategoriesForProvider(providerId)
        ) { categories: List<CategoryEntity>, categoryCounts, level: Int, unlockedCats: Set<Long> ->
            val countMap = categoryCounts.associate { count -> count.categoryId to count.item_count }
            val mappedCategories = categories.map { entity ->
                val domain = entity.toDomain().copy(count = countMap[entity.categoryId] ?: 0)
                if (unlockedCats.contains(entity.categoryId)) {
                    domain.copy(isUserProtected = false)
                } else {
                    domain
                }
            }
            val filteredCategories = if (level == 2) {
                mappedCategories.filter { category ->
                    (!category.isAdult && !category.isUserProtected) || unlockedCats.contains(category.id)
                }
            } else {
                mappedCategories
            }

            val allChannelsCategory = Category(
                id = ChannelRepository.ALL_CHANNELS_ID,
                name = "All Channels",
                type = ContentType.LIVE,
                count = filteredCategories.sumOf(Category::count)
            )

            listOf(allChannelsCategory) + filteredCategories
        }

    override fun searchChannels(providerId: Long, query: String): Flow<List<Channel>> =
        query.trim().takeIf { it.length >= MIN_SEARCH_QUERY_LENGTH }?.toFtsPrefixQuery().let { ftsQuery ->
            if (ftsQuery.isNullOrBlank()) {
                flowOf(emptyList())
            } else combine(
                channelDao.search(providerId, ftsQuery, GLOBAL_SEARCH_LIMIT),
                preferencesRepository.parentalControlLevel,
                parentalControlManager.unlockedCategoriesForProvider(providerId),
                preferencesRepository.liveChannelNumberingMode
            ) { entities, level, unlockedCats, numberingMode ->
                val filtered = if (level == 2) {
                    entities.filter { entity ->
                        val isUnlocked = entity.categoryId != null && unlockedCats.contains(entity.categoryId)
                        (!entity.isAdult && !entity.isUserProtected) || isUnlocked
                    }
                } else {
                    entities
                }

                applyNumbering(groupAndMapChannels(filtered, unlockedCats), numberingMode)
                    .rankSearchResults(query) { it.name }
            }
        }

    override suspend fun getChannel(channelId: Long): Channel? =
        channelDao.getById(channelId)?.toDomain()

    override suspend fun getStreamInfo(channel: Channel): Result<StreamInfo> = try {
        xtreamStreamUrlResolver.resolveWithMetadata(
            url = channel.streamUrl,
            fallbackProviderId = channel.providerId,
            fallbackStreamId = channel.streamId,
            fallbackContentType = ContentType.LIVE
        )?.let { resolvedStream ->
            Result.success(
                StreamInfo(
                    url = resolvedStream.url,
                    title = channel.name,
                    expirationTime = resolvedStream.expirationTime
                )
            )
        } ?: Result.error("No stream URL available for channel: ${channel.name}")
    } catch (e: Exception) {
        Result.error(e.message ?: "Failed to resolve stream URL for channel: ${channel.name}", e)
    }

    override suspend fun refreshChannels(providerId: Long): Result<Unit> {
        // Refresh is handled by ProviderRepository.refreshProviderData
        return Result.success(Unit)
    }

    override fun getChannelsByIds(ids: List<Long>): Flow<List<Channel>> =
        channelDao.getByIds(ids).map { entities -> entities.map { it.toBrowseDomain() } }

    override suspend fun incrementChannelErrorCount(channelId: Long): Result<Unit> = try {
        channelDao.incrementErrorCount(channelId)
        Result.success(Unit)
    } catch (e: Exception) {
        Result.error("Failed to increment channel error count", e)
    }

    override suspend fun resetChannelErrorCount(channelId: Long): Result<Unit> = try {
        channelDao.resetErrorCount(channelId)
        Result.success(Unit)
    } catch (e: Exception) {
        Result.error("Failed to reset channel error count", e)
    }

    private fun observeChannels(
        source: Flow<List<ChannelBrowseEntity>>,
        providerId: Long
    ): Flow<List<Channel>> = combine(
        source,
        preferencesRepository.parentalControlLevel,
        parentalControlManager.unlockedCategoriesForProvider(providerId),
        preferencesRepository.liveChannelNumberingMode
    ) { entities, level, unlockedCats, numberingMode ->
        applyNumbering(
            groupAndMapChannels(applyVisibilityFilter(entities, level, unlockedCats), unlockedCats),
            numberingMode
        )
    }

    private fun groupAndMapChannels(entities: List<ChannelBrowseEntity>, unlockedCats: Set<Long>): List<Channel> {
        val grouped = LinkedHashMap<String, ChannelGroupAccumulator>()
        entities.forEach { entity ->
            val key = channelGroupKey(entity)
            val existing = grouped[key]
            if (existing == null) {
                grouped[key] = ChannelGroupAccumulator(primary = entity)
            } else if (channelPriorityComparator.compare(entity, existing.primary) < 0) {
                existing.alternatives += existing.primary
                existing.primary = entity
            } else {
                existing.alternatives += entity
            }
        }

        return grouped.values.map { group ->
            val primaryEntity = group.primary
            val alternativeStreams = group.alternatives
                .sortedWith(channelPriorityComparator)
                .map { it.streamUrl }
                .distinct()
            val mergedQualityOptions = buildMergedQualityOptions(
                baseOptions = emptyList(),
                primaryStreamUrl = primaryEntity.streamUrl,
                alternativeStreams = alternativeStreams
            )
            val domain = primaryEntity.toBrowseDomain().copy(
                qualityOptions = mergedQualityOptions,
                alternativeStreams = alternativeStreams
            )

            if (primaryEntity.categoryId != null && unlockedCats.contains(primaryEntity.categoryId)) {
                domain.copy(isUserProtected = false, isAdult = false)
            } else {
                domain
            }
        }
    }

    private fun applyVisibilityFilter(
        entities: List<ChannelBrowseEntity>,
        level: Int,
        unlockedCats: Set<Long>
    ): List<ChannelBrowseEntity> {
        return if (level == 2) {
            entities.filter { entity ->
                val isUnlocked = entity.categoryId != null && unlockedCats.contains(entity.categoryId)
                (!entity.isAdult && !entity.isUserProtected) || isUnlocked
            }
        } else {
            entities
        }
    }

    private fun sortChannelsByNumber(channels: List<Channel>): List<Channel> =
        channels.sortedWith(channelNumberComparator)

    private fun applyNumbering(
        channels: List<Channel>,
        numberingMode: ChannelNumberingMode
    ): List<Channel> =
        when (numberingMode) {
            ChannelNumberingMode.GROUP -> channels.mapIndexed { index, channel ->
                channel.copy(number = index + 1)
            }
            ChannelNumberingMode.PROVIDER -> channels
        }

    private fun channelFlow(providerId: Long, categoryId: Long): Flow<List<ChannelBrowseEntity>> =
        if (categoryId == ChannelRepository.ALL_CHANNELS_ID) {
            channelDao.getByProvider(providerId)
        } else {
            channelDao.getByCategory(providerId, categoryId)
        }

    private fun channelFlowWithoutErrors(providerId: Long, categoryId: Long): Flow<List<ChannelBrowseEntity>> =
        if (categoryId == ChannelRepository.ALL_CHANNELS_ID) {
            channelDao.getByProviderWithoutErrors(providerId)
        } else {
            channelDao.getByCategoryWithoutErrors(providerId, categoryId)
        }

    private fun buildMergedQualityOptions(
        baseOptions: List<com.streamvault.domain.model.ChannelQualityOption>,
        primaryStreamUrl: String,
        alternativeStreams: List<String>
    ): List<com.streamvault.domain.model.ChannelQualityOption> {
        val derivedOptions = (listOf(primaryStreamUrl) + alternativeStreams)
            .mapNotNull(::deriveQualityOption)
        return (baseOptions + derivedOptions)
            .distinctBy { option -> option.height to option.label }
            .sortedWith(compareByDescending<com.streamvault.domain.model.ChannelQualityOption> { it.height ?: -1 }.thenBy { it.label })
    }

    private fun deriveQualityOption(url: String): com.streamvault.domain.model.ChannelQualityOption? {
        val match = QUALITY_HEIGHT_REGEX.find(url) ?: return null
        val height = match.groupValues[1].toIntOrNull() ?: return null
        return com.streamvault.domain.model.ChannelQualityOption(
            label = "${height}p",
            height = height,
            url = url
        )
    }

    private fun channelGroupKey(entity: ChannelBrowseEntity): String =
        if (entity.logicalGroupId.isNotBlank()) entity.logicalGroupId else entity.id.toString()

    private fun ChannelBrowseEntity.toBrowseDomain(): Channel =
        Channel(
            id = id,
            name = name,
            logoUrl = logoUrl,
            groupTitle = groupTitle,
            categoryId = categoryId,
            categoryName = categoryName,
            streamUrl = streamUrl,
            epgChannelId = epgChannelId,
            number = number,
            catchUpSupported = catchUpSupported,
            catchUpDays = catchUpDays,
            catchUpSource = catchUpSource,
            providerId = providerId,
            isAdult = isAdult,
            isUserProtected = isUserProtected,
            logicalGroupId = logicalGroupId,
            errorCount = errorCount,
            streamId = streamId
        )
}
