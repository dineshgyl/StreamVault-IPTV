package com.streamvault.data.local.dao

import androidx.room.*
import com.streamvault.data.local.entity.*
import kotlinx.coroutines.flow.Flow

data class RemoteIdMapping(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "remote_id") val remoteId: Long
)

@Dao
abstract class ProviderDao {
    @Query("SELECT * FROM providers ORDER BY created_at DESC")
    abstract fun getAll(): Flow<List<ProviderEntity>>

    @Query("SELECT * FROM providers WHERE is_active = 1 LIMIT 1")
    abstract fun getActive(): Flow<ProviderEntity?>

    @Query("SELECT * FROM providers WHERE server_url = :serverUrl AND username = :username")
    abstract suspend fun getByUrlAndUser(serverUrl: String, username: String): ProviderEntity?

    @Query("SELECT * FROM providers WHERE id = :id")
    abstract suspend fun getById(id: Long): ProviderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(provider: ProviderEntity): Long

    @Update
    abstract suspend fun update(provider: ProviderEntity)

    @Query("DELETE FROM providers WHERE id = :id")
    abstract suspend fun delete(id: Long)

    @Query("UPDATE providers SET is_active = 0")
    abstract suspend fun deactivateAll()

    @Query("UPDATE providers SET is_active = 1 WHERE id = :id")
    abstract suspend fun activate(id: Long)

    @Query("UPDATE providers SET last_synced_at = :timestamp WHERE id = :id")
    abstract suspend fun updateSyncTime(id: Long, timestamp: Long)

    @Query("UPDATE providers SET epg_url = :epgUrl WHERE id = :id")
    abstract suspend fun updateEpgUrl(id: Long, epgUrl: String)

    /** Atomically deactivates all providers then activates the given one. */
    @Transaction
    open suspend fun setActive(id: Long) {
        deactivateAll()
        activate(id)
    }
}

@Dao
interface ChannelDao {
    @Query(
        """
        SELECT id, stream_id, name, logo_url, group_title, category_id, category_name, stream_url,
               epg_channel_id, number, catch_up_supported, catch_up_days, catchUpSource,
               provider_id, is_adult, is_user_protected, logical_group_id, error_count
        FROM channels
        WHERE provider_id = :providerId
        ORDER BY number ASC
        """
    )
    fun getByProvider(providerId: Long): Flow<List<ChannelBrowseEntity>>

    @Query(
        """
        SELECT id, stream_id, name, logo_url, group_title, category_id, category_name, stream_url,
               epg_channel_id, number, catch_up_supported, catch_up_days, catchUpSource,
               provider_id, is_adult, is_user_protected, logical_group_id, error_count
        FROM channels
        WHERE provider_id = :providerId AND error_count = 0
        ORDER BY number ASC
        """
    )
    fun getByProviderWithoutErrors(providerId: Long): Flow<List<ChannelBrowseEntity>>

    @Query("SELECT * FROM channels WHERE provider_id = :providerId ORDER BY number ASC LIMIT :limit OFFSET :offset")
    fun getByProviderPage(providerId: Long, limit: Int, offset: Int): Flow<List<ChannelEntity>>

    @Query(
        """
        SELECT id, stream_id, name, logo_url, group_title, category_id, category_name, stream_url,
               epg_channel_id, number, catch_up_supported, catch_up_days, catchUpSource,
               provider_id, is_adult, is_user_protected, logical_group_id, error_count
        FROM channels
        WHERE provider_id = :providerId AND category_id = :categoryId
        ORDER BY number ASC
        """
    )
    fun getByCategory(providerId: Long, categoryId: Long): Flow<List<ChannelBrowseEntity>>

    @Query(
        """
        SELECT id, stream_id, name, logo_url, group_title, category_id, category_name, stream_url,
               epg_channel_id, number, catch_up_supported, catch_up_days, catchUpSource,
               provider_id, is_adult, is_user_protected, logical_group_id, error_count
        FROM channels
        WHERE provider_id = :providerId AND category_id = :categoryId AND error_count = 0
        ORDER BY number ASC
        """
    )
    fun getByCategoryWithoutErrors(providerId: Long, categoryId: Long): Flow<List<ChannelBrowseEntity>>

    @Query("SELECT * FROM channels WHERE provider_id = :providerId AND category_id = :categoryId ORDER BY number ASC LIMIT :limit OFFSET :offset")
    fun getByCategoryPage(providerId: Long, categoryId: Long, limit: Int, offset: Int): Flow<List<ChannelEntity>>

    @Query(
        """
        SELECT c.id, c.stream_id, c.name, c.logo_url, c.group_title, c.category_id, c.category_name, c.stream_url,
               c.epg_channel_id, c.number, c.catch_up_supported, c.catch_up_days, c.catchUpSource,
               c.provider_id, c.is_adult, c.is_user_protected, c.logical_group_id, c.error_count
        FROM channels c
        JOIN channels_fts ON c.id = channels_fts.rowid
        WHERE c.provider_id = :providerId
          AND channels_fts MATCH :query
        ORDER BY c.name ASC
        LIMIT :limit
        """
    )
    fun search(providerId: Long, query: String, limit: Int): Flow<List<ChannelBrowseEntity>>

    @Query(
        """
        SELECT c.id, c.stream_id, c.name, c.logo_url, c.group_title, c.category_id, c.category_name, c.stream_url,
               c.epg_channel_id, c.number, c.catch_up_supported, c.catch_up_days, c.catchUpSource,
               c.provider_id, c.is_adult, c.is_user_protected, c.logical_group_id, c.error_count
        FROM channels c
        JOIN channels_fts ON c.id = channels_fts.rowid
        WHERE c.provider_id = :providerId
          AND c.category_id = :categoryId
          AND channels_fts MATCH :query
        ORDER BY c.name ASC
        LIMIT :limit
        """
    )
    fun searchByCategory(providerId: Long, categoryId: Long, query: String, limit: Int): Flow<List<ChannelBrowseEntity>>

    @Query("SELECT * FROM channels WHERE id = :id")
    suspend fun getById(id: Long): ChannelEntity?

    @Query("SELECT * FROM channels WHERE provider_id = :providerId")
    suspend fun getByProviderSync(providerId: Long): List<ChannelEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(channels: List<ChannelEntity>)

    @Query("SELECT id, stream_id AS remote_id FROM channels WHERE provider_id = :providerId")
    suspend fun getIdMappings(providerId: Long): List<RemoteIdMapping>

    @Query("DELETE FROM channels WHERE provider_id = :providerId")
    suspend fun deleteByProvider(providerId: Long)

    @Query("DELETE FROM channels WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Transaction
    suspend fun replaceAll(providerId: Long, channels: List<ChannelEntity>) {
        val existingByRemoteId = getIdMappings(providerId).associate { it.remoteId to it.id }
        val remapped = channels
            .distinctBy { it.streamId }
            .map { entity -> entity.copy(id = existingByRemoteId[entity.streamId] ?: 0L) }
        deleteByProvider(providerId)
        insertAll(remapped)
    }

    @Query(
        """
        SELECT id, stream_id, name, logo_url, group_title, category_id, category_name, stream_url,
               epg_channel_id, number, catch_up_supported, catch_up_days, catchUpSource,
               provider_id, is_adult, is_user_protected, logical_group_id, error_count
        FROM channels
        WHERE id IN (:ids)
        """
    )
    fun getByIds(ids: List<Long>): Flow<List<ChannelBrowseEntity>>

    @Query("SELECT category_id, COUNT(*) as item_count FROM channels WHERE provider_id = :providerId AND category_id IS NOT NULL GROUP BY category_id")
    fun getCategoryCounts(providerId: Long): Flow<List<CategoryCount>>

    @Query(
        """
        SELECT
            category_id,
            COUNT(
                DISTINCT CASE
                    WHEN logical_group_id IS NOT NULL AND logical_group_id != '' THEN logical_group_id
                    ELSE CAST(id AS TEXT)
                END
            ) AS item_count
        FROM channels
        WHERE provider_id = :providerId
          AND category_id IS NOT NULL
        GROUP BY category_id
        """
    )
    fun getGroupedCategoryCounts(providerId: Long): Flow<List<CategoryCount>>

    @Query("SELECT COUNT(*) FROM channels WHERE provider_id = :providerId")
    fun getCount(providerId: Long): Flow<Int>

    @Query("UPDATE channels SET is_user_protected = :isProtected WHERE provider_id = :providerId AND category_id = :categoryId")
    suspend fun updateProtectionStatus(providerId: Long, categoryId: Long, isProtected: Boolean)

    @Query("UPDATE channels SET error_count = error_count + 1 WHERE id = :id")
    suspend fun incrementErrorCount(id: Long)

    @Query("UPDATE channels SET error_count = 0 WHERE id = :id")
    suspend fun resetErrorCount(id: Long)
}

@Dao
interface ChannelPreferenceDao {
    @Query("SELECT aspect_ratio FROM channel_preferences WHERE channel_id = :channelId LIMIT 1")
    fun observeAspectRatio(channelId: Long): Flow<String?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(preference: ChannelPreferenceEntity)

    @Query("DELETE FROM channel_preferences")
    suspend fun deleteAll()
}

@Dao
@RewriteQueriesToDropUnusedColumns
interface MovieDao {
    @Query("SELECT * FROM movies WHERE provider_id = :providerId ORDER BY name ASC")
    fun getByProvider(providerId: Long): Flow<List<MovieBrowseEntity>>

    /** SQL-level parental filter — avoids loading protected items into memory. */
    @Query("SELECT * FROM movies WHERE provider_id = :providerId AND is_user_protected = 0 ORDER BY name ASC")
    fun getByProviderUnprotected(providerId: Long): Flow<List<MovieBrowseEntity>>

    @Query("SELECT * FROM movies WHERE provider_id = :providerId ORDER BY name ASC LIMIT :limit OFFSET :offset")
    fun getByProviderPage(providerId: Long, limit: Int, offset: Int): Flow<List<MovieBrowseEntity>>

    @Query(
        """
        SELECT movies.* FROM movies
        WHERE movies.provider_id = :providerId
          AND EXISTS (
              SELECT 1 FROM favorites
              WHERE favorites.content_type = 'MOVIE'
                AND favorites.group_id IS NULL
                AND favorites.content_id = movies.id
          )
        ORDER BY movies.name ASC
        LIMIT :limit OFFSET :offset
        """
    )
    fun getFavoritesByProviderPage(providerId: Long, limit: Int, offset: Int): Flow<List<MovieBrowseEntity>>

    @Query(
        """
        SELECT COUNT(*)
        FROM movies
        WHERE provider_id = :providerId
          AND EXISTS (
              SELECT 1 FROM favorites
              WHERE favorites.content_type = 'MOVIE'
                AND favorites.group_id IS NULL
                AND favorites.content_id = movies.id
          )
        """
    )
    fun getFavoriteCountByProvider(providerId: Long): Flow<Int>

    @Query(
        """
        SELECT movies.* FROM movies
        WHERE movies.provider_id = :providerId
          AND EXISTS (
              SELECT 1 FROM playback_history
              WHERE playback_history.provider_id = movies.provider_id
                AND playback_history.content_type = 'MOVIE'
                AND playback_history.content_id = movies.id
                AND playback_history.resume_position_ms > 0
                AND (
                    playback_history.total_duration_ms <= 0
                    OR playback_history.resume_position_ms < CAST(playback_history.total_duration_ms * 0.95 AS INTEGER)
                )
          )
        ORDER BY movies.name ASC
        LIMIT :limit OFFSET :offset
        """
    )
    fun getInProgressByProviderPage(providerId: Long, limit: Int, offset: Int): Flow<List<MovieBrowseEntity>>

    @Query(
        """
        SELECT COUNT(*)
        FROM movies
        WHERE provider_id = :providerId
          AND EXISTS (
              SELECT 1 FROM playback_history
              WHERE playback_history.provider_id = movies.provider_id
                AND playback_history.content_type = 'MOVIE'
                AND playback_history.content_id = movies.id
                AND playback_history.resume_position_ms > 0
                AND (
                    playback_history.total_duration_ms <= 0
                    OR playback_history.resume_position_ms < CAST(playback_history.total_duration_ms * 0.95 AS INTEGER)
                )
          )
        """
    )
    fun getInProgressCountByProvider(providerId: Long): Flow<Int>

    @Query(
        """
        SELECT movies.* FROM movies
        WHERE movies.provider_id = :providerId
          AND COALESCE(movies.watch_progress, 0) <= 0
        ORDER BY movies.name ASC
        LIMIT :limit OFFSET :offset
        """
    )
    fun getUnwatchedByProviderPage(providerId: Long, limit: Int, offset: Int): Flow<List<MovieBrowseEntity>>

    @Query("SELECT COUNT(*) FROM movies WHERE provider_id = :providerId AND COALESCE(watch_progress, 0) <= 0")
    fun getUnwatchedCountByProvider(providerId: Long): Flow<Int>

    @Query(
        """
        SELECT movies.* FROM movies
        WHERE movies.provider_id = :providerId
        ORDER BY COALESCE((
            SELECT playback_history.watch_count
            FROM playback_history
            WHERE playback_history.provider_id = movies.provider_id
              AND playback_history.content_type = 'MOVIE'
              AND playback_history.content_id = movies.id
            LIMIT 1
        ), 0) DESC, movies.name ASC
        LIMIT :limit OFFSET :offset
        """
    )
    fun getByWatchCountProviderPage(providerId: Long, limit: Int, offset: Int): Flow<List<MovieBrowseEntity>>

    @Query("SELECT * FROM movies WHERE provider_id = :providerId AND category_id = :categoryId ORDER BY name ASC")
    fun getByCategory(providerId: Long, categoryId: Long): Flow<List<MovieBrowseEntity>>

    @Query(
        """
        SELECT movies.* FROM movies
        WHERE movies.provider_id = :providerId
          AND movies.category_id = :categoryId
          AND EXISTS (
              SELECT 1 FROM favorites
              WHERE favorites.content_type = 'MOVIE'
                AND favorites.group_id IS NULL
                AND favorites.content_id = movies.id
          )
        ORDER BY movies.name ASC
        LIMIT :limit OFFSET :offset
        """
    )
    fun getFavoritesByCategoryPage(providerId: Long, categoryId: Long, limit: Int, offset: Int): Flow<List<MovieBrowseEntity>>

    @Query(
        """
        SELECT COUNT(*)
        FROM movies
        WHERE provider_id = :providerId
          AND category_id = :categoryId
          AND EXISTS (
              SELECT 1 FROM favorites
              WHERE favorites.content_type = 'MOVIE'
                AND favorites.group_id IS NULL
                AND favorites.content_id = movies.id
          )
        """
    )
    fun getFavoriteCountByCategory(providerId: Long, categoryId: Long): Flow<Int>

    @Query(
        """
        SELECT movies.* FROM movies
        WHERE movies.provider_id = :providerId
          AND movies.category_id = :categoryId
          AND EXISTS (
              SELECT 1 FROM playback_history
              WHERE playback_history.provider_id = movies.provider_id
                AND playback_history.content_type = 'MOVIE'
                AND playback_history.content_id = movies.id
                AND playback_history.resume_position_ms > 0
                AND (
                    playback_history.total_duration_ms <= 0
                    OR playback_history.resume_position_ms < CAST(playback_history.total_duration_ms * 0.95 AS INTEGER)
                )
          )
        ORDER BY movies.name ASC
        LIMIT :limit OFFSET :offset
        """
    )
    fun getInProgressByCategoryPage(providerId: Long, categoryId: Long, limit: Int, offset: Int): Flow<List<MovieBrowseEntity>>

    @Query(
        """
        SELECT COUNT(*)
        FROM movies
        WHERE provider_id = :providerId
          AND category_id = :categoryId
          AND EXISTS (
              SELECT 1 FROM playback_history
              WHERE playback_history.provider_id = movies.provider_id
                AND playback_history.content_type = 'MOVIE'
                AND playback_history.content_id = movies.id
                AND playback_history.resume_position_ms > 0
                AND (
                    playback_history.total_duration_ms <= 0
                    OR playback_history.resume_position_ms < CAST(playback_history.total_duration_ms * 0.95 AS INTEGER)
                )
          )
        """
    )
    fun getInProgressCountByCategory(providerId: Long, categoryId: Long): Flow<Int>

    @Query(
        """
        SELECT movies.* FROM movies
        WHERE movies.provider_id = :providerId
          AND movies.category_id = :categoryId
          AND COALESCE(movies.watch_progress, 0) <= 0
        ORDER BY movies.name ASC
        LIMIT :limit OFFSET :offset
        """
    )
    fun getUnwatchedByCategoryPage(providerId: Long, categoryId: Long, limit: Int, offset: Int): Flow<List<MovieBrowseEntity>>

    @Query(
        "SELECT COUNT(*) FROM movies WHERE provider_id = :providerId AND category_id = :categoryId AND COALESCE(watch_progress, 0) <= 0"
    )
    fun getUnwatchedCountByCategory(providerId: Long, categoryId: Long): Flow<Int>

    @Query(
        """
        SELECT movies.* FROM movies
        WHERE movies.provider_id = :providerId
          AND movies.category_id = :categoryId
        ORDER BY COALESCE((
            SELECT playback_history.watch_count
            FROM playback_history
            WHERE playback_history.provider_id = movies.provider_id
              AND playback_history.content_type = 'MOVIE'
              AND playback_history.content_id = movies.id
            LIMIT 1
        ), 0) DESC, movies.name ASC
        LIMIT :limit OFFSET :offset
        """
    )
    fun getByWatchCountCategoryPage(providerId: Long, categoryId: Long, limit: Int, offset: Int): Flow<List<MovieBrowseEntity>>

    /** SQL-level parental filter per category. */
    @Query("SELECT * FROM movies WHERE provider_id = :providerId AND category_id = :categoryId AND is_user_protected = 0 ORDER BY name ASC")
    fun getByCategoryUnprotected(providerId: Long, categoryId: Long): Flow<List<MovieBrowseEntity>>

    @Query("SELECT * FROM movies WHERE provider_id = :providerId AND category_id = :categoryId ORDER BY name ASC LIMIT :limit OFFSET :offset")
    fun getByCategoryPage(providerId: Long, categoryId: Long, limit: Int, offset: Int): Flow<List<MovieBrowseEntity>>

    @Query("SELECT * FROM movies WHERE provider_id = :providerId AND category_id = :categoryId ORDER BY name ASC LIMIT :limit")
    fun getByCategoryPreview(providerId: Long, categoryId: Long, limit: Int): Flow<List<MovieBrowseEntity>>

    @Query("SELECT * FROM movies WHERE provider_id = :providerId ORDER BY rating DESC, name ASC LIMIT :limit")
    fun getTopRatedPreview(providerId: Long, limit: Int): Flow<List<MovieBrowseEntity>>

    @Query("SELECT * FROM movies WHERE provider_id = :providerId AND category_id = :categoryId ORDER BY rating DESC, name ASC LIMIT :limit")
    fun getTopRatedByCategoryPreview(providerId: Long, categoryId: Long, limit: Int): Flow<List<MovieBrowseEntity>>

    @Query("SELECT * FROM movies WHERE provider_id = :providerId ORDER BY release_date DESC, name ASC LIMIT :limit")
    fun getFreshPreview(providerId: Long, limit: Int): Flow<List<MovieBrowseEntity>>

    @Query("SELECT * FROM movies WHERE provider_id = :providerId AND category_id = :categoryId ORDER BY release_date DESC, name ASC LIMIT :limit")
    fun getFreshByCategoryPreview(providerId: Long, categoryId: Long, limit: Int): Flow<List<MovieBrowseEntity>>

    @Query(
        """
        SELECT m.* FROM movies m
        JOIN movies_fts ON m.id = movies_fts.rowid
        WHERE m.provider_id = :providerId
          AND movies_fts MATCH :query
        ORDER BY m.name ASC
        LIMIT :limit
        """
    )
    fun search(providerId: Long, query: String, limit: Int): Flow<List<MovieBrowseEntity>>

    @Query(
        """
        SELECT m.* FROM movies m
        JOIN movies_fts ON m.id = movies_fts.rowid
        WHERE m.provider_id = :providerId
          AND m.category_id = :categoryId
          AND movies_fts MATCH :query
        ORDER BY m.name ASC
        LIMIT :limit
        """
    )
    fun searchByCategory(providerId: Long, categoryId: Long, query: String, limit: Int): Flow<List<MovieBrowseEntity>>

    @Query("SELECT * FROM movies WHERE id = :id")
    suspend fun getById(id: Long): MovieEntity?

    @Query("SELECT * FROM movies WHERE provider_id = :providerId")
    suspend fun getByProviderSync(providerId: Long): List<MovieEntity>

    @Query("SELECT * FROM movies WHERE id IN (:ids)")
    fun getByIds(ids: List<Long>): Flow<List<MovieBrowseEntity>>

    @Query("SELECT * FROM movies WHERE provider_id = :providerId AND stream_id = :streamId")
    suspend fun getByStreamId(providerId: Long, streamId: Long): MovieEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(movies: List<MovieEntity>)

    @Query("SELECT id, stream_id AS remote_id FROM movies WHERE provider_id = :providerId")
    suspend fun getIdMappings(providerId: Long): List<RemoteIdMapping>

    @Query("SELECT id, stream_id AS remote_id FROM movies WHERE provider_id = :providerId AND category_id = :categoryId")
    suspend fun getIdMappingsByCategory(providerId: Long, categoryId: Long): List<RemoteIdMapping>

    @Update
    suspend fun update(movie: MovieEntity)

    @Query("UPDATE movies SET watch_progress = :progress, last_watched_at = :timestamp WHERE id = :id")
    suspend fun updateWatchProgress(id: Long, progress: Long, timestamp: Long = System.currentTimeMillis())

    @Query(
        """
        UPDATE movies
        SET watch_progress = COALESCE((
            SELECT resume_position_ms FROM playback_history
            WHERE playback_history.content_id = movies.id
              AND playback_history.content_type = 'MOVIE'
              AND playback_history.provider_id = movies.provider_id
        ), 0),
            last_watched_at = COALESCE((
                SELECT last_watched_at FROM playback_history
                WHERE playback_history.content_id = movies.id
                  AND playback_history.content_type = 'MOVIE'
                  AND playback_history.provider_id = movies.provider_id
            ), 0)
        WHERE id = :id AND provider_id = :providerId
        """
    )
    suspend fun syncWatchProgressFromHistory(id: Long, providerId: Long)

    @Query(
        """
        UPDATE movies
        SET watch_progress = COALESCE((
            SELECT resume_position_ms FROM playback_history
            WHERE playback_history.content_id = movies.id
              AND playback_history.content_type = 'MOVIE'
              AND playback_history.provider_id = movies.provider_id
        ), 0),
            last_watched_at = COALESCE((
                SELECT last_watched_at FROM playback_history
                WHERE playback_history.content_id = movies.id
                  AND playback_history.content_type = 'MOVIE'
                  AND playback_history.provider_id = movies.provider_id
            ), 0)
        WHERE provider_id = :providerId
        """
    )
    suspend fun syncWatchProgressFromHistoryByProvider(providerId: Long)

    @Query(
        """
        UPDATE movies
        SET watch_progress = COALESCE((
            SELECT resume_position_ms FROM playback_history
            WHERE playback_history.content_id = movies.id
              AND playback_history.content_type = 'MOVIE'
              AND playback_history.provider_id = movies.provider_id
        ), 0),
            last_watched_at = COALESCE((
                SELECT last_watched_at FROM playback_history
                WHERE playback_history.content_id = movies.id
                  AND playback_history.content_type = 'MOVIE'
                  AND playback_history.provider_id = movies.provider_id
            ), 0)
        """
    )
    suspend fun syncAllWatchProgressFromHistory()

    @Query("DELETE FROM movies WHERE provider_id = :providerId")
    suspend fun deleteByProvider(providerId: Long)

    @Query("DELETE FROM movies WHERE provider_id = :providerId AND category_id = :categoryId")
    suspend fun deleteByProviderAndCategory(providerId: Long, categoryId: Long)

    @Query("DELETE FROM movies WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM movies WHERE provider_id = :providerId AND category_id = :categoryId AND stream_id NOT IN (:remoteIds)")
    suspend fun deleteMissingByCategory(providerId: Long, categoryId: Long, remoteIds: List<Long>)

    @Query("""
        UPDATE movies 
        SET watch_progress = (
            SELECT resume_position_ms FROM playback_history 
            WHERE playback_history.content_id = movies.id 
            AND playback_history.content_type = 'MOVIE'
            AND playback_history.provider_id = :providerId
        )
        WHERE provider_id = :providerId AND EXISTS (
            SELECT 1 FROM playback_history 
            WHERE playback_history.content_id = movies.id
            AND playback_history.content_type = 'MOVIE' 
            AND playback_history.provider_id = :providerId
        )
    """)
    suspend fun restoreWatchProgress(providerId: Long)

    @Transaction
    suspend fun replaceAll(providerId: Long, movies: List<MovieEntity>) {
        val existingByRemoteId = getIdMappings(providerId).associate { it.remoteId to it.id }
        val remapped = movies
            .distinctBy { it.streamId }
            .map { entity -> entity.copy(id = existingByRemoteId[entity.streamId] ?: 0L) }
        deleteByProvider(providerId)
        insertAll(remapped)
        restoreWatchProgress(providerId)
    }

    @Transaction
    suspend fun replaceCategory(providerId: Long, categoryId: Long, movies: List<MovieEntity>) {
        val existingByRemoteId = getIdMappingsByCategory(providerId, categoryId).associate { it.remoteId to it.id }
        val remapped = movies
            .distinctBy { it.streamId }
            .map { entity -> entity.copy(id = existingByRemoteId[entity.streamId] ?: 0L) }

        if (remapped.isEmpty()) {
            deleteByProviderAndCategory(providerId, categoryId)
        } else {
            insertAll(remapped)
            deleteMissingByCategory(providerId, categoryId, remapped.map { it.streamId })
        }

        restoreWatchProgress(providerId)
    }

    @Query("SELECT category_id, COUNT(*) as item_count FROM movies WHERE provider_id = :providerId AND category_id IS NOT NULL GROUP BY category_id")
    fun getCategoryCounts(providerId: Long): Flow<List<CategoryCount>>

    @Query("SELECT COUNT(*) FROM movies WHERE provider_id = :providerId")
    fun getCount(providerId: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM movies WHERE provider_id = :providerId AND category_id = :categoryId")
    fun getCountByCategory(providerId: Long, categoryId: Long): Flow<Int>

    @Query("UPDATE movies SET is_user_protected = :isProtected WHERE provider_id = :providerId AND category_id = :categoryId")
    suspend fun updateProtectionStatus(providerId: Long, categoryId: Long, isProtected: Boolean)
}

@Dao
@RewriteQueriesToDropUnusedColumns
interface SeriesDao {
    @Query("SELECT * FROM series WHERE provider_id = :providerId ORDER BY name ASC")
    fun getByProvider(providerId: Long): Flow<List<SeriesBrowseEntity>>

    @Query("SELECT * FROM series WHERE provider_id = :providerId ORDER BY name ASC LIMIT :limit OFFSET :offset")
    fun getByProviderPage(providerId: Long, limit: Int, offset: Int): Flow<List<SeriesBrowseEntity>>

    @Query(
        """
        SELECT series.* FROM series
        WHERE series.provider_id = :providerId
          AND EXISTS (
              SELECT 1 FROM favorites
              WHERE favorites.content_type = 'SERIES'
                AND favorites.group_id IS NULL
                AND favorites.content_id = series.id
          )
        ORDER BY series.name ASC
        LIMIT :limit OFFSET :offset
        """
    )
    fun getFavoritesByProviderPage(providerId: Long, limit: Int, offset: Int): Flow<List<SeriesBrowseEntity>>

    @Query(
        """
        SELECT COUNT(*)
        FROM series
        WHERE provider_id = :providerId
          AND EXISTS (
              SELECT 1 FROM favorites
              WHERE favorites.content_type = 'SERIES'
                AND favorites.group_id IS NULL
                AND favorites.content_id = series.id
          )
        """
    )
    fun getFavoriteCountByProvider(providerId: Long): Flow<Int>

    @Query(
        """
        SELECT series.* FROM series
        WHERE series.provider_id = :providerId
          AND EXISTS (
              SELECT 1 FROM playback_history
              WHERE playback_history.provider_id = series.provider_id
                AND playback_history.resume_position_ms > 0
                AND (
                    playback_history.total_duration_ms <= 0
                    OR playback_history.resume_position_ms < CAST(playback_history.total_duration_ms * 0.95 AS INTEGER)
                )
                AND (
                    (playback_history.content_type = 'SERIES' AND playback_history.content_id = series.id)
                    OR (
                        playback_history.content_type = 'SERIES_EPISODE'
                        AND EXISTS (
                            SELECT 1 FROM episodes
                            WHERE episodes.id = playback_history.content_id
                              AND episodes.series_id = series.id
                        )
                    )
                )
          )
        ORDER BY series.name ASC
        LIMIT :limit OFFSET :offset
        """
    )
    fun getInProgressByProviderPage(providerId: Long, limit: Int, offset: Int): Flow<List<SeriesBrowseEntity>>

    @Query(
        """
        SELECT COUNT(*)
        FROM series
        WHERE provider_id = :providerId
          AND EXISTS (
              SELECT 1 FROM playback_history
              WHERE playback_history.provider_id = series.provider_id
                AND playback_history.resume_position_ms > 0
                AND (
                    playback_history.total_duration_ms <= 0
                    OR playback_history.resume_position_ms < CAST(playback_history.total_duration_ms * 0.95 AS INTEGER)
                )
                AND (
                    (playback_history.content_type = 'SERIES' AND playback_history.content_id = series.id)
                    OR (
                        playback_history.content_type = 'SERIES_EPISODE'
                        AND EXISTS (
                            SELECT 1 FROM episodes
                            WHERE episodes.id = playback_history.content_id
                              AND episodes.series_id = series.id
                        )
                    )
                )
          )
        """
    )
    fun getInProgressCountByProvider(providerId: Long): Flow<Int>

    @Query(
        """
        SELECT series.* FROM series
        WHERE series.provider_id = :providerId
          AND NOT EXISTS (
              SELECT 1 FROM playback_history
              WHERE playback_history.provider_id = series.provider_id
                AND (
                    (playback_history.content_type = 'SERIES' AND playback_history.content_id = series.id)
                    OR (
                        playback_history.content_type = 'SERIES_EPISODE'
                        AND EXISTS (
                            SELECT 1 FROM episodes
                            WHERE episodes.id = playback_history.content_id
                              AND episodes.series_id = series.id
                        )
                    )
                )
          )
        ORDER BY series.name ASC
        LIMIT :limit OFFSET :offset
        """
    )
    fun getUnwatchedByProviderPage(providerId: Long, limit: Int, offset: Int): Flow<List<SeriesBrowseEntity>>

    @Query(
        """
        SELECT COUNT(*)
        FROM series
        WHERE provider_id = :providerId
          AND NOT EXISTS (
              SELECT 1 FROM playback_history
              WHERE playback_history.provider_id = series.provider_id
                AND (
                    (playback_history.content_type = 'SERIES' AND playback_history.content_id = series.id)
                    OR (
                        playback_history.content_type = 'SERIES_EPISODE'
                        AND EXISTS (
                            SELECT 1 FROM episodes
                            WHERE episodes.id = playback_history.content_id
                              AND episodes.series_id = series.id
                        )
                    )
                )
          )
        """
    )
    fun getUnwatchedCountByProvider(providerId: Long): Flow<Int>

    @Query(
        """
        SELECT series.* FROM series
        WHERE series.provider_id = :providerId
        ORDER BY COALESCE((
            SELECT MAX(playback_history.watch_count)
            FROM playback_history
            WHERE playback_history.provider_id = series.provider_id
              AND (
                  (playback_history.content_type = 'SERIES' AND playback_history.content_id = series.id)
                  OR (
                      playback_history.content_type = 'SERIES_EPISODE'
                      AND EXISTS (
                          SELECT 1 FROM episodes
                          WHERE episodes.id = playback_history.content_id
                            AND episodes.series_id = series.id
                      )
                  )
              )
        ), 0) DESC, series.name ASC
        LIMIT :limit OFFSET :offset
        """
    )
    fun getByWatchCountProviderPage(providerId: Long, limit: Int, offset: Int): Flow<List<SeriesBrowseEntity>>

    @Query("SELECT * FROM series WHERE provider_id = :providerId AND category_id = :categoryId ORDER BY name ASC")
    fun getByCategory(providerId: Long, categoryId: Long): Flow<List<SeriesBrowseEntity>>

    @Query(
        """
        SELECT series.* FROM series
        WHERE series.provider_id = :providerId
          AND series.category_id = :categoryId
          AND EXISTS (
              SELECT 1 FROM favorites
              WHERE favorites.content_type = 'SERIES'
                AND favorites.group_id IS NULL
                AND favorites.content_id = series.id
          )
        ORDER BY series.name ASC
        LIMIT :limit OFFSET :offset
        """
    )
    fun getFavoritesByCategoryPage(providerId: Long, categoryId: Long, limit: Int, offset: Int): Flow<List<SeriesBrowseEntity>>

    @Query(
        """
        SELECT COUNT(*)
        FROM series
        WHERE provider_id = :providerId
          AND category_id = :categoryId
          AND EXISTS (
              SELECT 1 FROM favorites
              WHERE favorites.content_type = 'SERIES'
                AND favorites.group_id IS NULL
                AND favorites.content_id = series.id
          )
        """
    )
    fun getFavoriteCountByCategory(providerId: Long, categoryId: Long): Flow<Int>

    @Query(
        """
        SELECT series.* FROM series
        WHERE series.provider_id = :providerId
          AND series.category_id = :categoryId
          AND EXISTS (
              SELECT 1 FROM playback_history
              WHERE playback_history.provider_id = series.provider_id
                AND playback_history.resume_position_ms > 0
                AND (
                    playback_history.total_duration_ms <= 0
                    OR playback_history.resume_position_ms < CAST(playback_history.total_duration_ms * 0.95 AS INTEGER)
                )
                AND (
                    (playback_history.content_type = 'SERIES' AND playback_history.content_id = series.id)
                    OR (
                        playback_history.content_type = 'SERIES_EPISODE'
                        AND EXISTS (
                            SELECT 1 FROM episodes
                            WHERE episodes.id = playback_history.content_id
                              AND episodes.series_id = series.id
                        )
                    )
                )
          )
        ORDER BY series.name ASC
        LIMIT :limit OFFSET :offset
        """
    )
    fun getInProgressByCategoryPage(providerId: Long, categoryId: Long, limit: Int, offset: Int): Flow<List<SeriesBrowseEntity>>

    @Query(
        """
        SELECT COUNT(*)
        FROM series
        WHERE provider_id = :providerId
          AND category_id = :categoryId
          AND EXISTS (
              SELECT 1 FROM playback_history
              WHERE playback_history.provider_id = series.provider_id
                AND playback_history.resume_position_ms > 0
                AND (
                    playback_history.total_duration_ms <= 0
                    OR playback_history.resume_position_ms < CAST(playback_history.total_duration_ms * 0.95 AS INTEGER)
                )
                AND (
                    (playback_history.content_type = 'SERIES' AND playback_history.content_id = series.id)
                    OR (
                        playback_history.content_type = 'SERIES_EPISODE'
                        AND EXISTS (
                            SELECT 1 FROM episodes
                            WHERE episodes.id = playback_history.content_id
                              AND episodes.series_id = series.id
                        )
                    )
                )
          )
        """
    )
    fun getInProgressCountByCategory(providerId: Long, categoryId: Long): Flow<Int>

    @Query(
        """
        SELECT series.* FROM series
        WHERE series.provider_id = :providerId
          AND series.category_id = :categoryId
          AND NOT EXISTS (
              SELECT 1 FROM playback_history
              WHERE playback_history.provider_id = series.provider_id
                AND (
                    (playback_history.content_type = 'SERIES' AND playback_history.content_id = series.id)
                    OR (
                        playback_history.content_type = 'SERIES_EPISODE'
                        AND EXISTS (
                            SELECT 1 FROM episodes
                            WHERE episodes.id = playback_history.content_id
                              AND episodes.series_id = series.id
                        )
                    )
                )
          )
        ORDER BY series.name ASC
        LIMIT :limit OFFSET :offset
        """
    )
    fun getUnwatchedByCategoryPage(providerId: Long, categoryId: Long, limit: Int, offset: Int): Flow<List<SeriesBrowseEntity>>

    @Query(
        """
        SELECT COUNT(*)
        FROM series
        WHERE provider_id = :providerId
          AND category_id = :categoryId
          AND NOT EXISTS (
              SELECT 1 FROM playback_history
              WHERE playback_history.provider_id = series.provider_id
                AND (
                    (playback_history.content_type = 'SERIES' AND playback_history.content_id = series.id)
                    OR (
                        playback_history.content_type = 'SERIES_EPISODE'
                        AND EXISTS (
                            SELECT 1 FROM episodes
                            WHERE episodes.id = playback_history.content_id
                              AND episodes.series_id = series.id
                        )
                    )
                )
          )
        """
    )
    fun getUnwatchedCountByCategory(providerId: Long, categoryId: Long): Flow<Int>

    @Query(
        """
        SELECT series.* FROM series
        WHERE series.provider_id = :providerId
          AND series.category_id = :categoryId
        ORDER BY COALESCE((
            SELECT MAX(playback_history.watch_count)
            FROM playback_history
            WHERE playback_history.provider_id = series.provider_id
              AND (
                  (playback_history.content_type = 'SERIES' AND playback_history.content_id = series.id)
                  OR (
                      playback_history.content_type = 'SERIES_EPISODE'
                      AND EXISTS (
                          SELECT 1 FROM episodes
                          WHERE episodes.id = playback_history.content_id
                            AND episodes.series_id = series.id
                      )
                  )
              )
        ), 0) DESC, series.name ASC
        LIMIT :limit OFFSET :offset
        """
    )
    fun getByWatchCountCategoryPage(providerId: Long, categoryId: Long, limit: Int, offset: Int): Flow<List<SeriesBrowseEntity>>

    @Query("SELECT * FROM series WHERE provider_id = :providerId AND category_id = :categoryId ORDER BY name ASC LIMIT :limit OFFSET :offset")
    fun getByCategoryPage(providerId: Long, categoryId: Long, limit: Int, offset: Int): Flow<List<SeriesBrowseEntity>>

    @Query("SELECT * FROM series WHERE provider_id = :providerId AND category_id = :categoryId ORDER BY name ASC LIMIT :limit")
    fun getByCategoryPreview(providerId: Long, categoryId: Long, limit: Int): Flow<List<SeriesBrowseEntity>>

    @Query("SELECT * FROM series WHERE provider_id = :providerId ORDER BY rating DESC, name ASC LIMIT :limit")
    fun getTopRatedPreview(providerId: Long, limit: Int): Flow<List<SeriesBrowseEntity>>

    @Query("SELECT * FROM series WHERE provider_id = :providerId AND category_id = :categoryId ORDER BY rating DESC, name ASC LIMIT :limit")
    fun getTopRatedByCategoryPreview(providerId: Long, categoryId: Long, limit: Int): Flow<List<SeriesBrowseEntity>>

    @Query("SELECT * FROM series WHERE provider_id = :providerId ORDER BY last_modified DESC, name ASC LIMIT :limit")
    fun getFreshPreview(providerId: Long, limit: Int): Flow<List<SeriesBrowseEntity>>

    @Query("SELECT * FROM series WHERE provider_id = :providerId AND category_id = :categoryId ORDER BY last_modified DESC, name ASC LIMIT :limit")
    fun getFreshByCategoryPreview(providerId: Long, categoryId: Long, limit: Int): Flow<List<SeriesBrowseEntity>>

    @Query(
        """
        SELECT s.* FROM series s
        JOIN series_fts ON s.id = series_fts.rowid
        WHERE s.provider_id = :providerId
          AND series_fts MATCH :query
        ORDER BY s.name ASC
        LIMIT :limit
        """
    )
    fun search(providerId: Long, query: String, limit: Int): Flow<List<SeriesBrowseEntity>>

    @Query(
        """
        SELECT s.* FROM series s
        JOIN series_fts ON s.id = series_fts.rowid
        WHERE s.provider_id = :providerId
          AND s.category_id = :categoryId
          AND series_fts MATCH :query
        ORDER BY s.name ASC
        LIMIT :limit
        """
    )
    fun searchByCategory(providerId: Long, categoryId: Long, query: String, limit: Int): Flow<List<SeriesBrowseEntity>>

    @Query("SELECT * FROM series WHERE id = :id")
    suspend fun getById(id: Long): SeriesEntity?

    @Query("SELECT * FROM series WHERE provider_id = :providerId")
    suspend fun getByProviderSync(providerId: Long): List<SeriesEntity>

    @Query("SELECT * FROM series WHERE id IN (:ids)")
    fun getByIds(ids: List<Long>): Flow<List<SeriesBrowseEntity>>

    @Query("SELECT * FROM series WHERE provider_id = :providerId AND series_id = :seriesId LIMIT 1")
    suspend fun getBySeriesId(providerId: Long, seriesId: Long): SeriesEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(series: List<SeriesEntity>)

    @Update
    suspend fun update(series: SeriesEntity)

    @Query("SELECT id, series_id AS remote_id FROM series WHERE provider_id = :providerId")
    suspend fun getIdMappings(providerId: Long): List<RemoteIdMapping>

    @Query("SELECT id, series_id AS remote_id FROM series WHERE provider_id = :providerId AND category_id = :categoryId")
    suspend fun getIdMappingsByCategory(providerId: Long, categoryId: Long): List<RemoteIdMapping>

    @Query("DELETE FROM series WHERE provider_id = :providerId")
    suspend fun deleteByProvider(providerId: Long)

    @Query("DELETE FROM series WHERE provider_id = :providerId AND category_id = :categoryId")
    suspend fun deleteByProviderAndCategory(providerId: Long, categoryId: Long)

    @Query("DELETE FROM series WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Transaction
    suspend fun replaceAll(providerId: Long, series: List<SeriesEntity>) {
        val existingByRemoteId = getIdMappings(providerId).associate { it.remoteId to it.id }
        val remapped = series
            .distinctBy { it.seriesId }
            .map { entity -> entity.copy(id = existingByRemoteId[entity.seriesId] ?: 0L) }
        deleteByProvider(providerId)
        insertAll(remapped)
    }

    @Query("DELETE FROM series WHERE provider_id = :providerId AND category_id = :categoryId AND series_id NOT IN (:remoteIds)")
    suspend fun deleteMissingByCategory(providerId: Long, categoryId: Long, remoteIds: List<Long>)

    @Transaction
    suspend fun replaceCategory(providerId: Long, categoryId: Long, series: List<SeriesEntity>) {
        val existingByRemoteId = getIdMappingsByCategory(providerId, categoryId).associate { it.remoteId to it.id }
        val remapped = series
            .distinctBy { it.seriesId }
            .map { entity -> entity.copy(id = existingByRemoteId[entity.seriesId] ?: 0L) }

        if (remapped.isEmpty()) {
            deleteByProviderAndCategory(providerId, categoryId)
        } else {
            insertAll(remapped)
            deleteMissingByCategory(providerId, categoryId, remapped.map { it.seriesId })
        }
    }

    @Query("SELECT category_id, COUNT(*) as item_count FROM series WHERE provider_id = :providerId AND category_id IS NOT NULL GROUP BY category_id")
    fun getCategoryCounts(providerId: Long): Flow<List<CategoryCount>>

    @Query("SELECT COUNT(*) FROM series WHERE provider_id = :providerId")
    fun getCount(providerId: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM series WHERE provider_id = :providerId AND category_id = :categoryId")
    fun getCountByCategory(providerId: Long, categoryId: Long): Flow<Int>

    @Query("UPDATE series SET is_user_protected = :isProtected WHERE provider_id = :providerId AND category_id = :categoryId")
    suspend fun updateProtectionStatus(providerId: Long, categoryId: Long, isProtected: Boolean)
}


@Dao
@RewriteQueriesToDropUnusedColumns
interface EpisodeDao {
    @Query("SELECT * FROM episodes WHERE series_id = :seriesId ORDER BY season_number ASC, episode_number ASC")
    fun getBySeries(seriesId: Long): Flow<List<EpisodeBrowseEntity>>

    @Query("SELECT * FROM episodes WHERE series_id = :seriesId ORDER BY season_number ASC, episode_number ASC")
    suspend fun getBySeriesSync(seriesId: Long): List<EpisodeBrowseEntity>

    @Query("SELECT * FROM episodes WHERE id = :id")
    suspend fun getById(id: Long): EpisodeEntity?

    @Query(
        """
        SELECT COUNT(*)
        FROM episodes
        LEFT JOIN playback_history
            ON playback_history.content_id = episodes.id
           AND playback_history.content_type = 'SERIES_EPISODE'
           AND playback_history.provider_id = episodes.provider_id
        WHERE episodes.provider_id = :providerId
          AND episodes.series_id = :seriesId
          AND (
              COALESCE(playback_history.total_duration_ms, episodes.duration_seconds * 1000) <= 0
              OR COALESCE(playback_history.resume_position_ms, episodes.watch_progress) < CAST(
                  COALESCE(playback_history.total_duration_ms, episodes.duration_seconds * 1000) * :completionThreshold
                  AS INTEGER
              )
          )
        """
    )
    fun getUnwatchedCount(providerId: Long, seriesId: Long, completionThreshold: Float): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(episodes: List<EpisodeEntity>)

    @Query("SELECT id, episode_id AS remote_id FROM episodes WHERE provider_id = :providerId AND series_id = :seriesId")
    suspend fun getIdMappings(providerId: Long, seriesId: Long): List<RemoteIdMapping>

    @Query("UPDATE episodes SET watch_progress = :progress, last_watched_at = :timestamp WHERE id = :id")
    suspend fun updateWatchProgress(id: Long, progress: Long, timestamp: Long = System.currentTimeMillis())

    @Query(
        """
        UPDATE episodes
        SET watch_progress = COALESCE((
            SELECT resume_position_ms FROM playback_history
            WHERE playback_history.content_id = episodes.id
              AND playback_history.content_type = 'SERIES_EPISODE'
              AND playback_history.provider_id = episodes.provider_id
        ), 0),
            last_watched_at = COALESCE((
                SELECT last_watched_at FROM playback_history
                WHERE playback_history.content_id = episodes.id
                  AND playback_history.content_type = 'SERIES_EPISODE'
                  AND playback_history.provider_id = episodes.provider_id
            ), 0)
        WHERE id = :id AND provider_id = :providerId
        """
    )
    suspend fun syncWatchProgressFromHistory(id: Long, providerId: Long)

    @Query(
        """
        UPDATE episodes
        SET watch_progress = COALESCE((
            SELECT resume_position_ms FROM playback_history
            WHERE playback_history.content_id = episodes.id
              AND playback_history.content_type = 'SERIES_EPISODE'
              AND playback_history.provider_id = episodes.provider_id
        ), 0),
            last_watched_at = COALESCE((
                SELECT last_watched_at FROM playback_history
                WHERE playback_history.content_id = episodes.id
                  AND playback_history.content_type = 'SERIES_EPISODE'
                  AND playback_history.provider_id = episodes.provider_id
            ), 0)
        WHERE provider_id = :providerId
        """
    )
    suspend fun syncWatchProgressFromHistoryByProvider(providerId: Long)

    @Query(
        """
        UPDATE episodes
        SET watch_progress = COALESCE((
            SELECT resume_position_ms FROM playback_history
            WHERE playback_history.content_id = episodes.id
              AND playback_history.content_type = 'SERIES_EPISODE'
              AND playback_history.provider_id = episodes.provider_id
        ), 0),
            last_watched_at = COALESCE((
                SELECT last_watched_at FROM playback_history
                WHERE playback_history.content_id = episodes.id
                  AND playback_history.content_type = 'SERIES_EPISODE'
                  AND playback_history.provider_id = episodes.provider_id
            ), 0)
        """
    )
    suspend fun syncAllWatchProgressFromHistory()

    @Query("DELETE FROM episodes WHERE series_id = :seriesId")
    suspend fun deleteBySeries(seriesId: Long)

    @Query("DELETE FROM episodes WHERE series_id NOT IN (SELECT id FROM series)")
    suspend fun deleteOrphans(): Int

    @Query("""
        UPDATE episodes 
        SET watch_progress = (
            SELECT resume_position_ms FROM playback_history 
            WHERE playback_history.content_id = episodes.id 
            AND playback_history.content_type = 'SERIES_EPISODE'
            AND playback_history.provider_id = episodes.provider_id
        )
        WHERE series_id = :seriesId AND EXISTS (
            SELECT 1 FROM playback_history 
            WHERE playback_history.content_id = episodes.id
            AND playback_history.content_type = 'SERIES_EPISODE' 
            AND playback_history.provider_id = episodes.provider_id
        )
    """)
    suspend fun restoreWatchProgress(seriesId: Long)

    @Transaction
    suspend fun replaceAll(seriesId: Long, providerId: Long, episodes: List<EpisodeEntity>) {
        val existingByRemoteId = getIdMappings(providerId, seriesId).associate { it.remoteId to it.id }
        val remapped = episodes
            .distinctBy { it.episodeId }
            .map { entity -> entity.copy(id = existingByRemoteId[entity.episodeId] ?: 0L) }
        deleteBySeries(seriesId)
        insertAll(remapped)
        restoreWatchProgress(seriesId)
    }
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories WHERE provider_id = :providerId AND type = :type ORDER BY id ASC")
    fun getByProviderAndType(providerId: Long, type: String): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE provider_id = :providerId AND type = :type ORDER BY id ASC")
    suspend fun getByProviderAndTypeSync(providerId: Long, type: String): List<CategoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(categories: List<CategoryEntity>)

    @Query("DELETE FROM categories WHERE provider_id = :providerId AND type = :type")
    suspend fun deleteByProviderAndType(providerId: Long, type: String)

    @Query("DELETE FROM categories WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Transaction
    suspend fun replaceAll(providerId: Long, type: String, categories: List<CategoryEntity>) {
        deleteByProviderAndType(providerId, type)
        insertAll(categories)
    }

    @Query("UPDATE categories SET is_user_protected = :isProtected WHERE provider_id = :providerId AND category_id = :categoryId AND type = :type")
    suspend fun updateProtectionStatus(providerId: Long, categoryId: Long, type: String, isProtected: Boolean)
}

@Dao
@RewriteQueriesToDropUnusedColumns
interface ProgramDao {
    @Query(
        """
        SELECT
            id,
            provider_id,
            channel_id,
            title,
            CASE
                WHEN LENGTH(description) > 600 THEN SUBSTR(description, 1, 600) || '...'
                ELSE description
            END AS description,
            start_time,
            end_time,
            lang,
            NULL AS rating,
            NULL AS image_url,
            NULL AS genre,
            NULL AS category,
            has_archive
        FROM programs
        WHERE provider_id = :providerId
          AND channel_id = :channelId
          AND end_time > :startTime
          AND start_time < :endTime
        ORDER BY start_time ASC
        """
    )
    fun getForChannel(providerId: Long, channelId: String, startTime: Long, endTime: Long): Flow<List<ProgramBrowseEntity>>

    @Query(
        """
        SELECT
            id,
            provider_id,
            channel_id,
            title,
            CASE
                WHEN LENGTH(description) > 600 THEN SUBSTR(description, 1, 600) || '...'
                ELSE description
            END AS description,
            start_time,
            end_time,
            lang,
            NULL AS rating,
            NULL AS image_url,
            NULL AS genre,
            NULL AS category,
            has_archive
        FROM programs
        WHERE provider_id = :providerId
          AND channel_id IN (:channelIds)
          AND end_time > :startTime
          AND start_time < :endTime
        ORDER BY channel_id ASC, start_time ASC
        """
    )
    fun getForChannels(providerId: Long, channelIds: List<String>, startTime: Long, endTime: Long): Flow<List<ProgramBrowseEntity>>

    @Query(
        """
        SELECT
            programs.id,
            programs.provider_id,
            programs.channel_id,
            programs.title,
            CASE
                WHEN LENGTH(programs.description) > 600 THEN SUBSTR(programs.description, 1, 600) || '...'
                ELSE programs.description
            END AS description,
            programs.start_time,
            programs.end_time,
            programs.lang,
            NULL AS rating,
            NULL AS image_url,
            NULL AS genre,
            NULL AS category,
            programs.has_archive
        FROM programs
        INNER JOIN channels
            ON channels.provider_id = programs.provider_id
           AND (
               channels.epg_channel_id = programs.channel_id
               OR CAST(channels.stream_id AS TEXT) = programs.channel_id
           )
        WHERE programs.provider_id = :providerId
          AND channels.category_id = :categoryId
          AND programs.end_time > :startTime
          AND programs.start_time < :endTime
        ORDER BY channels.number ASC, programs.channel_id ASC, programs.start_time ASC
        """
    )
    fun getForCategory(providerId: Long, categoryId: Long, startTime: Long, endTime: Long): Flow<List<ProgramBrowseEntity>>

    @Query(
        """
        SELECT
            programs.id,
            programs.provider_id,
            programs.channel_id,
            programs.title,
            CASE
                WHEN LENGTH(programs.description) > 600 THEN SUBSTR(programs.description, 1, 600) || '...'
                ELSE programs.description
            END AS description,
            programs.start_time,
            programs.end_time,
            programs.lang,
            NULL AS rating,
            NULL AS image_url,
            NULL AS genre,
            NULL AS category,
            programs.has_archive
        FROM programs
        WHERE programs.provider_id = :providerId
          AND programs.end_time > :startTime
          AND programs.start_time < :endTime
          AND (
              programs.title LIKE :queryPattern
              OR programs.description LIKE :queryPattern
          )
          AND (
              :categoryId IS NULL
              OR EXISTS (
                  SELECT 1 FROM channels
                  WHERE channels.provider_id = programs.provider_id
                    AND (
                        channels.epg_channel_id = programs.channel_id
                        OR CAST(channels.stream_id AS TEXT) = programs.channel_id
                    )
                    AND channels.category_id = :categoryId
              )
          )
        ORDER BY programs.start_time ASC, programs.channel_id ASC
        LIMIT :limit
        """
    )
    fun searchPrograms(
        providerId: Long,
        queryPattern: String,
        startTime: Long,
        endTime: Long,
        categoryId: Long?,
        limit: Int
    ): Flow<List<ProgramBrowseEntity>>

    @Query(
        """
        SELECT
            id,
            provider_id,
            channel_id,
            title,
            CASE
                WHEN LENGTH(description) > 600 THEN SUBSTR(description, 1, 600) || '...'
                ELSE description
            END AS description,
            start_time,
            end_time,
            lang,
            NULL AS rating,
            NULL AS image_url,
            NULL AS genre,
            NULL AS category,
            has_archive
        FROM programs
        WHERE provider_id = :providerId
          AND channel_id = :channelId
          AND start_time <= :now
          AND end_time > :now
        LIMIT 1
        """
    )
    fun getNowPlaying(providerId: Long, channelId: String, now: Long = System.currentTimeMillis()): Flow<ProgramBrowseEntity?>

    @Query(
        """
        SELECT
            id,
            provider_id,
            channel_id,
            title,
            CASE
                WHEN LENGTH(description) > 600 THEN SUBSTR(description, 1, 600) || '...'
                ELSE description
            END AS description,
            start_time,
            end_time,
            lang,
            NULL AS rating,
            NULL AS image_url,
            NULL AS genre,
            NULL AS category,
            has_archive
        FROM programs
        WHERE provider_id = :providerId
          AND channel_id IN (:channelIds)
          AND start_time <= :now
          AND end_time > :now
        """
    )
    fun getNowPlayingForChannels(providerId: Long, channelIds: List<String>, now: Long = System.currentTimeMillis()): Flow<List<ProgramBrowseEntity>>

    @Query(
        """
        SELECT
            id,
            provider_id,
            channel_id,
            title,
            CASE
                WHEN LENGTH(description) > 600 THEN SUBSTR(description, 1, 600) || '...'
                ELSE description
            END AS description,
            start_time,
            end_time,
            lang,
            NULL AS rating,
            NULL AS image_url,
            NULL AS genre,
            NULL AS category,
            has_archive
        FROM programs
        WHERE provider_id = :providerId
          AND channel_id IN (:channelIds)
          AND start_time <= :now
          AND end_time > :now
        """
    )
    suspend fun getNowPlayingForChannelsSync(providerId: Long, channelIds: List<String>, now: Long = System.currentTimeMillis()): List<ProgramBrowseEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(programs: List<ProgramEntity>)

    @Query("SELECT COUNT(*) FROM programs WHERE provider_id = :providerId")
    suspend fun countByProvider(providerId: Long): Int

    @Query("DELETE FROM programs WHERE end_time < :beforeTime")
    suspend fun deleteOld(beforeTime: Long): Int

    @Query("DELETE FROM programs WHERE provider_id = :providerId")
    suspend fun deleteByProvider(providerId: Long)

    @Query("UPDATE programs SET provider_id = :targetProviderId WHERE provider_id = :sourceProviderId")
    suspend fun moveToProvider(sourceProviderId: Long, targetProviderId: Long)

    @Query("DELETE FROM programs WHERE provider_id = :providerId AND channel_id = :channelId")
    suspend fun deleteForChannel(providerId: Long, channelId: String)
}

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites WHERE group_id IS NULL ORDER BY position ASC")
    fun getAllGlobal(): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE content_type = :contentType AND group_id IS NULL ORDER BY position ASC")
    fun getGlobalByType(contentType: String): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE content_type = :contentType ORDER BY position ASC")
    fun getAllByType(contentType: String): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE group_id = :groupId ORDER BY position ASC")
    fun getByGroup(groupId: Long): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE content_id = :contentId AND content_type = :contentType AND (:groupId IS NULL AND group_id IS NULL OR group_id = :groupId) LIMIT 1")
    suspend fun get(contentId: Long, contentType: String, groupId: Long?): FavoriteEntity?

    @Query("SELECT COUNT(*) FROM favorites WHERE group_id IS NULL AND content_type = :contentType")
    fun getGlobalFavoriteCount(contentType: String): Flow<Int>

    @Query("SELECT group_id as category_id, COUNT(*) as item_count FROM favorites WHERE group_id IS NOT NULL AND content_type = :contentType GROUP BY group_id")
    fun getGroupFavoriteCounts(contentType: String): Flow<List<CategoryCount>>

    @Query("SELECT group_id FROM favorites WHERE content_id = :contentId AND content_type = :contentType AND group_id IS NOT NULL")
    suspend fun getGroupMemberships(contentId: Long, contentType: String): List<Long>

    @Query("SELECT MAX(position) FROM favorites WHERE (:groupId IS NULL AND group_id IS NULL OR group_id = :groupId)")
    suspend fun getMaxPosition(groupId: Long?): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(favorite: FavoriteEntity)

    @Update
    suspend fun updateAll(favorites: List<FavoriteEntity>)

    @Query("DELETE FROM favorites WHERE content_id = :contentId AND content_type = :contentType AND (:groupId IS NULL AND group_id IS NULL OR group_id = :groupId)")
    suspend fun delete(contentId: Long, contentType: String, groupId: Long?)

    @Query("DELETE FROM favorites WHERE content_type = 'LIVE' AND content_id NOT IN (SELECT id FROM channels)")
    suspend fun deleteMissingLiveFavorites(): Int

    @Query("DELETE FROM favorites WHERE content_type = 'MOVIE' AND content_id NOT IN (SELECT id FROM movies)")
    suspend fun deleteMissingMovieFavorites(): Int

    @Query("DELETE FROM favorites WHERE content_type = 'SERIES' AND content_id NOT IN (SELECT id FROM series)")
    suspend fun deleteMissingSeriesFavorites(): Int

    @Query("UPDATE favorites SET group_id = :groupId WHERE id = :favoriteId")
    suspend fun updateGroup(favoriteId: Long, groupId: Long?)
}

@Dao
interface VirtualGroupDao {
    @Query("SELECT * FROM virtual_groups WHERE content_type = :contentType ORDER BY position ASC")
    fun getByType(contentType: String): Flow<List<VirtualGroupEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(group: VirtualGroupEntity): Long

    @Query("UPDATE virtual_groups SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM virtual_groups WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
@RewriteQueriesToDropUnusedColumns
interface PlaybackHistoryDao {
    @Query("SELECT * FROM playback_history ORDER BY last_watched_at DESC LIMIT :limit")
    fun getRecentlyWatched(limit: Int = 100): Flow<List<PlaybackHistoryLiteEntity>>

    @Query("SELECT * FROM playback_history WHERE provider_id = :providerId ORDER BY last_watched_at DESC LIMIT :limit")
    fun getRecentlyWatchedByProvider(providerId: Long, limit: Int = 100): Flow<List<PlaybackHistoryLiteEntity>>

    @Query("SELECT * FROM playback_history WHERE provider_id = :providerId ORDER BY last_watched_at DESC")
    fun getByProvider(providerId: Long): Flow<List<PlaybackHistoryLiteEntity>>

    @Query("SELECT * FROM playback_history ORDER BY last_watched_at DESC")
    suspend fun getAllSync(): List<PlaybackHistoryEntity>

    @Query("SELECT * FROM playback_history WHERE content_id = :contentId AND content_type = :contentType AND provider_id = :providerId")
    suspend fun get(contentId: Long, contentType: String, providerId: Long): PlaybackHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(history: PlaybackHistoryEntity)

    @Query("DELETE FROM playback_history WHERE content_id = :contentId AND content_type = :contentType AND provider_id = :providerId")
    suspend fun delete(contentId: Long, contentType: String, providerId: Long)

    @Query("DELETE FROM playback_history")
    suspend fun deleteAll()

    @Query("DELETE FROM playback_history WHERE provider_id = :providerId")
    suspend fun deleteByProvider(providerId: Long)
}

@Dao
interface SyncMetadataDao {
    @Query("SELECT * FROM sync_metadata WHERE provider_id = :providerId")
    fun get(providerId: Long): Flow<SyncMetadataEntity?>

    @Query("SELECT * FROM sync_metadata WHERE provider_id = :providerId")
    suspend fun getSync(providerId: Long): SyncMetadataEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(metadata: SyncMetadataEntity)

    @Query("DELETE FROM sync_metadata WHERE provider_id = :providerId")
    suspend fun delete(providerId: Long)
}
