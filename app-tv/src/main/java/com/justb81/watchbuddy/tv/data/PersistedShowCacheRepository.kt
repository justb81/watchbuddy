package com.justb81.watchbuddy.tv.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import com.justb81.watchbuddy.core.model.EnrichedShowEntry
import com.justb81.watchbuddy.core.network.WatchBuddyJson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import javax.inject.Inject
import javax.inject.Singleton

private val Context.showCacheDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "persisted_show_cache"
)

private const val TAG = "PersistedShowCacheRepo"

/**
 * Persists the last successfully loaded [EnrichedShowEntry] list to DataStore so
 * it can be used as a fallback when the companion phone is unreachable after a
 * process restart.
 *
 * Uses the project-wide [SharedJson] instance for kotlinx.serialization so that
 * model changes (new optional fields, [ignoreUnknownKeys]) are handled consistently.
 */
@Singleton
class PersistedShowCacheRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val showsKey = stringPreferencesKey("shows_json")
    private val timestampKey = longPreferencesKey("saved_at_ms")

    /**
     * Writes [shows] and the current wall-clock timestamp to DataStore.
     * Serialization failures are logged and swallowed so a write error never
     * propagates to the ViewModel.
     */
    suspend fun save(shows: List<EnrichedShowEntry>) {
        try {
            val json = WatchBuddyJson.encodeToString(shows)
            context.showCacheDataStore.edit { prefs ->
                prefs[showsKey] = json
                prefs[timestampKey] = System.currentTimeMillis()
            }
        } catch (e: Exception) {
            DiagnosticLog.warn(TAG, "Failed to persist show cache", e)
        }
    }

    /**
     * Returns the persisted shows and the wall-clock millisecond timestamp at which
     * they were saved, or `null` if no cache has been written yet.
     *
     * Deserialization failures are logged and return `null` so a corrupt cache entry
     * never crashes the app.
     */
    suspend fun load(): CacheEntry? {
        return try {
            val prefs = context.showCacheDataStore.data
                .catch { emit(emptyPreferences()) }
                .first()
            val json = prefs[showsKey] ?: return null
            val savedAtMs = prefs[timestampKey] ?: return null
            val shows = WatchBuddyJson.decodeFromString<List<EnrichedShowEntry>>(json)
            CacheEntry(shows, savedAtMs)
        } catch (e: Exception) {
            DiagnosticLog.warn(TAG, "Failed to load persisted show cache", e)
            null
        }
    }

    data class CacheEntry(
        val shows: List<EnrichedShowEntry>,
        val savedAtMs: Long,
    )
}
