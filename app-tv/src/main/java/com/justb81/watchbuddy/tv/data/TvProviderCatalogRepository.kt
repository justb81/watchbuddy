package com.justb81.watchbuddy.tv.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.justb81.watchbuddy.core.deeplink.ProviderCatalog
import com.justb81.watchbuddy.core.justwatch.JustWatchPackageMap
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import com.justb81.watchbuddy.core.model.ProviderCatalogSnapshot
import com.justb81.watchbuddy.core.network.WatchBuddyJson
import com.justb81.watchbuddy.tv.discovery.PhoneDiscoveryManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TvProviderCatalogRepo"
private const val HTTP_NOT_MODIFIED = 304

// ── Room entity ───────────────────────────────────────────────────────────────

@Entity(tableName = "provider_catalog_cache")
data class ProviderCatalogCacheRow(
    @PrimaryKey val id: Int = 0,
    @ColumnInfo(name = "catalog_json") val catalogJson: String,
    @ColumnInfo(name = "version") val version: Int,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
    @ColumnInfo(name = "source") val source: String,
)

// ── DAO ───────────────────────────────────────────────────────────────────────

@Dao
interface ProviderCatalogCacheDao {

    @Query("SELECT * FROM provider_catalog_cache WHERE id = 0 LIMIT 1")
    suspend fun get(): ProviderCatalogCacheRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: ProviderCatalogCacheRow)
}

// ── Database ──────────────────────────────────────────────────────────────────

@Database(entities = [ProviderCatalogCacheRow::class], version = 1, exportSchema = true)
abstract class ProviderCatalogDatabase : RoomDatabase() {
    abstract fun dao(): ProviderCatalogCacheDao
}

@Module
@InstallIn(SingletonComponent::class)
object ProviderCatalogDatabaseModule {

    @Provides
    @Singleton
    fun provideProviderCatalogDatabase(
        @ApplicationContext context: Context,
    ): ProviderCatalogDatabase = Room.databaseBuilder(
        context,
        ProviderCatalogDatabase::class.java,
        "provider_catalog.db",
    ).build()

    @Provides
    @Singleton
    fun provideProviderCatalogCacheDao(
        db: ProviderCatalogDatabase,
    ): ProviderCatalogCacheDao = db.dao()
}

// ── Repository ────────────────────────────────────────────────────────────────

/** Source of the active provider catalog on TV. */
enum class CatalogSource { LIVE, BUNDLED }

data class CatalogStatus(
    val version: Int = 0,
    val fetchedAtMs: Long = 0L,
    val source: CatalogSource = CatalogSource.BUNDLED,
)

@Singleton
class TvProviderCatalogRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: ProviderCatalogCacheDao,
    private val discoveryManager: PhoneDiscoveryManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow(CatalogStatus())
    val status: StateFlow<CatalogStatus> = _status.asStateFlow()

    init {
        scope.launch {
            val cached = dao.get()
            if (cached != null) {
                parseCatalog(cached.catalogJson)?.let { snapshot ->
                    applySnapshot(snapshot, CatalogSource.valueOf(cached.source), cached.fetchedAt)
                }
            }
            if (_status.value.version == 0) {
                loadBundled()
            }
        }
    }

    suspend fun refresh() {
        val phones = discoveryManager.discoveredPhones.value
        if (phones.isEmpty()) {
            DiagnosticLog.warn(TAG, "no phones available for catalog refresh")
            return
        }
        val best = phones.maxByOrNull { it.score } ?: return
        val url = "${best.baseUrl}/provider-catalog"
        try {
            val client = okhttp3.OkHttpClient()
            val cached = dao.get()
            val reqBuilder = okhttp3.Request.Builder().url(url)
            cached?.let { reqBuilder.header("If-None-Match", "\"${it.version}\"") }
            val response = client.newCall(reqBuilder.build()).execute()
            when {
                response.code == HTTP_NOT_MODIFIED -> {
                    DiagnosticLog.event(TAG, "catalog up to date (304)")
                }
                response.isSuccessful -> {
                    val body = response.body?.string()
                    val parsed = if (body != null) parseCatalog(body) else null
                    if (body == null || parsed == null) return
                    val currentVersion = cached?.version ?: 0
                    if (parsed.version < currentVersion) {
                        DiagnosticLog.warn(TAG, "phone version ${parsed.version} < cached $currentVersion, ignoring")
                        return
                    }
                    val row = ProviderCatalogCacheRow(
                        catalogJson = body,
                        version = parsed.version,
                        fetchedAt = System.currentTimeMillis(),
                        source = CatalogSource.LIVE.name,
                    )
                    dao.upsert(row)
                    applySnapshot(parsed, CatalogSource.LIVE, row.fetchedAt)
                    DiagnosticLog.event(TAG, "catalog updated to v${parsed.version} from phone")
                }
                else -> {
                    DiagnosticLog.warn(TAG, "catalog fetch from phone failed: HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            DiagnosticLog.error(TAG, "catalog fetch from phone error", e)
        }
    }

    private fun applySnapshot(snapshot: ProviderCatalogSnapshot, source: CatalogSource, fetchedAtMs: Long) {
        ProviderCatalog.updateFromSnapshot(snapshot)
        JustWatchPackageMap.updateFromSnapshot(snapshot)
        _status.value = CatalogStatus(
            version = snapshot.version,
            fetchedAtMs = fetchedAtMs,
            source = source,
        )
    }

    private fun loadBundled() {
        val json = loadBundledJson() ?: return
        parseCatalog(json)?.let { snapshot ->
            applySnapshot(snapshot, CatalogSource.BUNDLED, 0L)
        }
    }

    private fun loadBundledJson(): String? = runCatching {
        context.assets.open("provider-catalog-bundled.json").bufferedReader().readText()
    }.getOrNull()

    private fun parseCatalog(json: String): ProviderCatalogSnapshot? = runCatching {
        WatchBuddyJson.decodeFromString(ProviderCatalogSnapshot.serializer(), json)
    }.onFailure {
        DiagnosticLog.error(TAG, "failed to parse catalog JSON", it)
    }.getOrNull()
}
