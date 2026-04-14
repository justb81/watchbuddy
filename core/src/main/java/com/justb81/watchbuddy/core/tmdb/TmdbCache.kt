package com.justb81.watchbuddy.core.tmdb

import com.justb81.watchbuddy.core.model.TmdbEpisode
import com.justb81.watchbuddy.core.model.TmdbShow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory cache for TMDB API responses with a 15-minute TTL.
 *
 * Show metadata and episode details rarely change, so caching them avoids redundant
 * network calls when multiple recap requests are made for the same show in quick
 * succession. This also helps stay within TMDB's API rate limits.
 */
@Singleton
class TmdbCache @Inject constructor() {

    private data class CacheEntry<T>(val timestamp: Long, val value: T)

    private val showCache = ConcurrentHashMap<Int, CacheEntry<TmdbShow>>()
    private val episodeCache = ConcurrentHashMap<Triple<Int, Int, Int>, CacheEntry<TmdbEpisode>>()

    /** Overridable time source — defaults to wall-clock time. Override in tests to control TTL behaviour. */
    internal var timeSource: () -> Long = { System.currentTimeMillis() }

    companion object {
        const val TTL_MS = 15 * 60 * 1000L // 15 minutes
    }

    fun getShow(id: Int): TmdbShow? {
        val entry = showCache[id] ?: return null
        return if (timeSource() - entry.timestamp < TTL_MS) {
            entry.value
        } else {
            showCache.remove(id)
            null
        }
    }

    fun putShow(id: Int, show: TmdbShow) {
        showCache[id] = CacheEntry(timeSource(), show)
    }

    fun getEpisode(seriesId: Int, season: Int, episode: Int): TmdbEpisode? {
        val key = Triple(seriesId, season, episode)
        val entry = episodeCache[key] ?: return null
        return if (timeSource() - entry.timestamp < TTL_MS) {
            entry.value
        } else {
            episodeCache.remove(key)
            null
        }
    }

    fun putEpisode(seriesId: Int, season: Int, episode: Int, tmdbEpisode: TmdbEpisode) {
        val key = Triple(seriesId, season, episode)
        episodeCache[key] = CacheEntry(timeSource(), tmdbEpisode)
    }

    fun clear() {
        showCache.clear()
        episodeCache.clear()
    }
}
