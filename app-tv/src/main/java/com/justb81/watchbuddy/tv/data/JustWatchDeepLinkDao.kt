package com.justb81.watchbuddy.tv.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface JustWatchDeepLinkDao {

    @Query(
        """
        SELECT * FROM justwatch_deep_link
        WHERE tmdb_show_id = :showId
          AND season = :season
          AND episode = :episode
          AND provider_id = :providerId
          AND country_code = :countryCode
        LIMIT 1
        """
    )
    suspend fun get(
        showId: Int,
        season: Int,
        episode: Int,
        providerId: Int,
        countryCode: String,
    ): JustWatchDeepLink?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: JustWatchDeepLink)

    @Query("DELETE FROM justwatch_deep_link")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM justwatch_deep_link WHERE standard_web_url IS NOT NULL")
    suspend fun countPositive(): Int

    @Query("SELECT COUNT(*) FROM justwatch_deep_link WHERE standard_web_url IS NULL")
    suspend fun countNegative(): Int

    @Query("SELECT MAX(fetched_at) FROM justwatch_deep_link")
    suspend fun lastFetchedAt(): Long?
}
