package com.justb81.watchbuddy.tv.discovery

import android.util.Log
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import com.justb81.watchbuddy.core.model.LibraryHint
import com.justb81.watchbuddy.core.model.LlmBackend
import com.justb81.watchbuddy.core.model.MediaMetadataSnapshot
import com.justb81.watchbuddy.core.model.TitleExtractionRequest
import com.justb81.watchbuddy.core.model.TitleExtractionResponse
import com.justb81.watchbuddy.core.network.WatchBuddyJson
import com.justb81.watchbuddy.core.scrobbler.TitleExtractor
import com.justb81.watchbuddy.core.scrobbler.WatchedShowSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TV-side [TitleExtractor] that forwards a [MediaMetadataSnapshot] to the
 * best-scoring connected phone's `POST /scrobble/extract` endpoint. Owns three
 * concerns the core scrobbler shouldn't touch:
 *
 *  1. **Best-phone selection.** Only phones with `llmBackend != NONE` and
 *     positive `modelQuality` are eligible; falls back to null when no phone
 *     can run inference so the scrobbler skips straight to its TMDB fallback.
 *  2. **Library hints.** Builds a compact [LibraryHint] list from the
 *     [WatchedShowSource]'s cached shows, trimmed to [MAX_HINTS] so the prompt
 *     stays within a couple of KB.
 *  3. **In-flight dedup + long timeout.** Because Gemma on a low-tier phone
 *     can take 30–60 s to produce structured JSON, the HTTP budget is [CLIENT_TIMEOUT_SECONDS].
 *     The 30 s MediaSession poll cycle would otherwise stack duplicate calls
 *     for the same raw title — so every in-flight request is memoised by
 *     `packageName:title` and reused by later callers until it completes.
 */
@Singleton
class PhoneTitleExtractionClient @Inject constructor(
    private val phoneDiscovery: PhoneDiscoveryManager,
    private val watchedShowSource: WatchedShowSource,
    private val sharedHttpClient: OkHttpClient,
) : TitleExtractor {

    companion object {
        private const val TAG = "PhoneTitleExtractor"
        private const val DEDUP_KEY_HEX_LENGTH = 32
        internal const val MAX_HINTS = 50
        internal const val CLIENT_TIMEOUT_SECONDS = 90L
        internal const val CALL_TIMEOUT_SECONDS = 95L
        private const val MILLIS_PER_SECOND = 1_000L
    }

    private val cache = ConcurrentHashMap<String, PhoneApiService>()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<TitleExtractionResponse?>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val longTimeoutHttpClient: OkHttpClient by lazy {
        // Derive from the shared client so logging/interceptors carry over, but
        // bump the read/call timeouts well past the 30 s poll cycle and let the
        // in-flight map (see [dedupKey]) prevent duplicate inference requests.
        sharedHttpClient.newBuilder()
            .readTimeout(CLIENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(CLIENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun extract(snapshot: MediaMetadataSnapshot): TitleExtractionResponse? {
        val phone = bestPhoneWithLlm() ?: return null
        val key = dedupKey(snapshot)
        // Atomic: insert a new deferred if the slot is empty or the previous one
        // has already completed. If we win the slot, launch the inference; other
        // concurrent callers just await our deferred.
        val newDeferred = CompletableDeferred<TitleExtractionResponse?>()
        val deferred = inFlight.compute(key) { _, existing ->
            existing?.takeIf { !it.isCompleted } ?: newDeferred
        } ?: newDeferred
        if (deferred === newDeferred) {
            scope.launch { runExtraction(phone, snapshot, newDeferred, key) }
        }
        return deferred.await()
    }

    private suspend fun runExtraction(
        phone: PhoneDiscoveryManager.DiscoveredPhone,
        snapshot: MediaMetadataSnapshot,
        deferred: CompletableDeferred<TitleExtractionResponse?>,
        key: String,
    ) {
        try {
            val service = clientFor(phone.baseUrl)
            val hints = buildLibraryHints()
            val request = TitleExtractionRequest(snapshot = snapshot, libraryHints = hints)
            val response = withTimeoutOrNull(CLIENT_TIMEOUT_SECONDS * MILLIS_PER_SECOND) {
                service.extractTitle(request)
            }
            if (response == null) {
                DiagnosticLog.warn(TAG, "extractTitle timed out on ${phone.baseUrl}")
            } else {
                Log.i(TAG, "extractTitle ok via ${phone.baseUrl}: '${response.showTitle}' " +
                    "S${response.season}E${response.episode} confidence=${response.confidence}")
            }
            deferred.complete(response)
        } catch (e: Exception) {
            DiagnosticLog.warn(TAG, "extractTitle failed on ${phone.baseUrl}", e)
            deferred.complete(null)
        } finally {
            inFlight.remove(key, deferred)
        }
    }

    private fun bestPhoneWithLlm(): PhoneDiscoveryManager.DiscoveredPhone? =
        phoneDiscovery.discoveredPhones.value
            .filter { it.capability?.isAvailable != false }
            .filter { (it.capability?.llmBackend ?: it.txtRecord?.llmBackend) != LlmBackend.NONE }
            .filter { (it.capability?.modelQuality ?: it.txtRecord?.modelQuality ?: 0) > 0 }
            .maxByOrNull { it.score }

    private suspend fun buildLibraryHints(): List<LibraryHint> =
        watchedShowSource.getCachedShows()
            .take(MAX_HINTS)
            .map { entry ->
                LibraryHint(
                    traktId = entry.show.ids.trakt,
                    tmdbId = entry.show.ids.tmdb,
                    title = entry.show.title,
                    year = entry.show.year,
                )
            }

    private fun clientFor(baseUrl: String): PhoneApiService = cache.getOrPut(baseUrl) {
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(longTimeoutHttpClient)
            .addConverterFactory(WatchBuddyJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(PhoneApiService::class.java)
    }

    /**
     * Dedup key — same evidence text should hit one inference even if minor
     * positional drift occurs between polls. Hashes [MediaMetadataSnapshot.text]
     * so the key is stable regardless of field additions or source-tag changes,
     * and position/duration data never influences the dedup window.
     */
    private fun dedupKey(snapshot: MediaMetadataSnapshot): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(snapshot.text.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }.take(DEDUP_KEY_HEX_LENGTH)
        return "${snapshot.packageName}:$hex"
    }
}
