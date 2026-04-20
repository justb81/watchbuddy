package com.justb81.watchbuddy.tv.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.providerDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "last_used_providers"
)

/**
 * Persists which streaming provider was most recently used for each show on this TV.
 * Stored locally — not synced to phones.
 *
 * Format: a single string preference encoded as "tmdbShowId1|providerId1;tmdbShowId2|providerId2".
 * Updated whenever the user launches a provider deep link from ShowDetailScreen, or
 * when a confirmed scrobble can be attributed to a known package in ProviderCatalog.
 */
@Singleton
class LastUsedProviderRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val mapKey = stringPreferencesKey("provider_map")

    suspend fun recordUsed(tmdbShowId: Int, providerId: Int) {
        context.providerDataStore.edit { prefs ->
            val map = decode(prefs[mapKey] ?: "").toMutableMap()
            map[tmdbShowId] = providerId
            prefs[mapKey] = encode(map)
        }
    }

    suspend fun getLastUsedProviderId(tmdbShowId: Int): Int? {
        val raw = context.providerDataStore.data
            .catch { emit(emptyPreferences()) }
            .first()[mapKey] ?: return null
        return decode(raw)[tmdbShowId]
    }

    private fun encode(map: Map<Int, Int>): String =
        map.entries.joinToString(";") { "${it.key}|${it.value}" }

    private fun decode(s: String): Map<Int, Int> {
        if (s.isBlank()) return emptyMap()
        return s.split(";").mapNotNull { entry ->
            val parts = entry.split("|")
            if (parts.size == 2) {
                parts[0].toIntOrNull()?.let { k ->
                    parts[1].toIntOrNull()?.let { v -> k to v }
                }
            } else {
                null
            }
        }.toMap()
    }
}
