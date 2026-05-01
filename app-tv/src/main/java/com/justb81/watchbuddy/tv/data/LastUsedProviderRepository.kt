package com.justb81.watchbuddy.tv.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.providerDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "last_used_providers"
)

private const val TAG = "LastUsedProviderRepo"
private const val V1_PREFIX = "v1:"

@Serializable
private data class ProviderMapV1(val providers: Map<String, Int> = emptyMap())

private val codec = Json { ignoreUnknownKeys = true }

/**
 * Persists which streaming provider was most recently used for each show on this TV.
 * Stored locally — not synced to phones.
 *
 * Storage format: a single string preference prefixed with "v1:" followed by a
 * JSON-serialized object. Legacy pipe-delimited entries ("showId|providerId;...") are
 * migrated on first read and rewritten in the v1 format on the next write.
 * Updated whenever the user launches a provider deep link from ShowDetailScreen, or
 * when a confirmed scrobble can be attributed to a known package in ProviderCatalog.
 */
@Singleton
class LastUsedProviderRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
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

    internal fun encode(map: Map<Int, Int>): String {
        val dto = ProviderMapV1(map.mapKeys { it.key.toString() })
        return "$V1_PREFIX${codec.encodeToString(dto)}"
    }

    internal fun decode(s: String): Map<Int, Int> {
        if (s.isBlank()) return emptyMap()
        return if (s.startsWith(V1_PREFIX)) {
            decodeV1(s.removePrefix(V1_PREFIX))
        } else {
            migrateLegacy(s)
        }
    }

    private fun decodeV1(jsonPart: String): Map<Int, Int> {
        return try {
            val dto = codec.decodeFromString<ProviderMapV1>(jsonPart)
            dto.providers.entries.mapNotNull { (k, v) ->
                k.toIntOrNull()?.let { it to v }
            }.toMap()
        } catch (e: Exception) {
            DiagnosticLog.warn(TAG, "v1 provider map decode failed, discarding entries", e)
            emptyMap()
        }
    }

    private fun migrateLegacy(s: String): Map<Int, Int> {
        val result = s.split(";").mapNotNull { entry ->
            val parts = entry.split("|")
            if (parts.size == 2) {
                parts[0].toIntOrNull()?.let { k ->
                    parts[1].toIntOrNull()?.let { v -> k to v }
                }
            } else {
                if (entry.isNotBlank()) {
                    DiagnosticLog.warn(TAG, "Legacy provider map entry malformed, skipping: '$entry'")
                }
                null
            }
        }.toMap()
        DiagnosticLog.warn(TAG, "Migrated legacy pipe-format provider map (${result.size} entries)")
        return result
    }
}
