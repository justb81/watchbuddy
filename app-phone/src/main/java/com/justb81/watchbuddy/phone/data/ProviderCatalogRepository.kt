package com.justb81.watchbuddy.phone.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.justb81.watchbuddy.core.deeplink.ProviderCatalog
import com.justb81.watchbuddy.core.justwatch.JustWatchPackageMap
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import com.justb81.watchbuddy.core.model.ProviderCatalogSnapshot
import com.justb81.watchbuddy.core.network.WatchBuddyJson
import androidx.hilt.work.HiltWorker
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ProviderCatalogRepo"
private const val WORK_NAME = "provider_catalog_refresh"
private const val REFRESH_INTERVAL_HOURS = 24L

@Singleton
class ProviderCatalogRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>,
    private val workManager: WorkManager,
    private val backendUrl: String,
) {

    private object Keys {
        val CATALOG_JSON = stringPreferencesKey("provider_catalog_json")
        val CATALOG_VERSION = intPreferencesKey("provider_catalog_version")
        val CATALOG_ETAG = stringPreferencesKey("provider_catalog_etag")
        val CATALOG_FETCHED_AT = longPreferencesKey("provider_catalog_fetched_at")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _catalog = MutableStateFlow<ProviderCatalogSnapshot?>(null)
    val catalog: StateFlow<ProviderCatalogSnapshot?> = _catalog.asStateFlow()

    init {
        scope.launch {
            val prefs = dataStore.data.first()
            val json = prefs[Keys.CATALOG_JSON]
            if (json != null) {
                parseCatalog(json)?.let { applySnapshot(it) }
            }
            if (_catalog.value == null) {
                loadBundled()
            }
            if (backendUrl.isNotBlank()) {
                schedulePeriodicRefresh()
            }
        }
    }

    private fun schedulePeriodicRefresh() {
        val request = PeriodicWorkRequestBuilder<CatalogRefreshWorker>(
            REFRESH_INTERVAL_HOURS, TimeUnit.HOURS,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    suspend fun refresh(): Boolean {
        if (backendUrl.isBlank()) return false
        val url = backendUrl.trimEnd('/') + "/provider-catalog"
        return try {
            val client = OkHttpClient()
            val prefs = dataStore.data.first()
            val storedEtag = prefs[Keys.CATALOG_ETAG]
            val reqBuilder = Request.Builder().url(url)
            if (storedEtag != null) reqBuilder.header("If-None-Match", storedEtag)
            val response = client.newCall(reqBuilder.build()).execute()
            when {
                response.code == 304 -> {
                    DiagnosticLog.event(TAG, "catalog up to date (304 Not Modified)")
                    true
                }
                response.isSuccessful -> {
                    val body = response.body?.string() ?: return false
                    val etag = response.header("ETag")
                    val parsed = parseCatalog(body) ?: return false
                    val currentVersion = prefs[Keys.CATALOG_VERSION] ?: 0
                    if (parsed.version < currentVersion) {
                        DiagnosticLog.warn(TAG, "server version ${parsed.version} < cached $currentVersion, ignoring")
                        return false
                    }
                    persist(body, parsed.version, etag)
                    applySnapshot(parsed)
                    DiagnosticLog.event(TAG, "catalog updated to v${parsed.version}")
                    true
                }
                else -> {
                    DiagnosticLog.warn(TAG, "catalog fetch failed: HTTP ${response.code}")
                    false
                }
            }
        } catch (e: Exception) {
            DiagnosticLog.error(TAG, "catalog fetch error", e)
            false
        }
    }

    suspend fun currentJson(): String {
        val prefs = dataStore.data.first()
        return prefs[Keys.CATALOG_JSON] ?: loadBundledJson()
    }

    fun currentVersion(): Int = _catalog.value?.version ?: 0

    suspend fun fetchedAtMs(): Long = dataStore.data.first()[Keys.CATALOG_FETCHED_AT] ?: 0L

    suspend fun isLive(): Boolean = dataStore.data.first()[Keys.CATALOG_JSON] != null

    private fun applySnapshot(snapshot: ProviderCatalogSnapshot) {
        _catalog.value = snapshot
        ProviderCatalog.updateFromSnapshot(snapshot)
        JustWatchPackageMap.updateFromSnapshot(snapshot)
    }

    private fun loadBundled() {
        val json = loadBundledJson()
        parseCatalog(json)?.let { applySnapshot(it) }
    }

    private fun loadBundledJson(): String = runCatching {
        context.assets.open("provider-catalog-bundled.json").bufferedReader().readText()
    }.getOrElse { "{\"version\":0,\"lastUpdated\":\"\",\"providers\":[]}" }

    private fun parseCatalog(json: String): ProviderCatalogSnapshot? = runCatching {
        WatchBuddyJson.decodeFromString(ProviderCatalogSnapshot.serializer(), json)
    }.onFailure {
        DiagnosticLog.error(TAG, "failed to parse catalog JSON", it)
    }.getOrNull()

    private suspend fun persist(json: String, version: Int, etag: String?) {
        dataStore.edit { prefs ->
            prefs[Keys.CATALOG_JSON] = json
            prefs[Keys.CATALOG_VERSION] = version
            prefs[Keys.CATALOG_FETCHED_AT] = System.currentTimeMillis()
            if (etag != null) prefs[Keys.CATALOG_ETAG] = etag else prefs.remove(Keys.CATALOG_ETAG)
        }
    }

    suspend fun currentEtag(): String? = dataStore.data.first()[Keys.CATALOG_ETAG]
}

@HiltWorker
class CatalogRefreshWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: ProviderCatalogRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return if (repository.refresh()) Result.success() else Result.retry()
    }
}
