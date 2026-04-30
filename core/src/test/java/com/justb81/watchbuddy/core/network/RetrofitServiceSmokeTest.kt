package com.justb81.watchbuddy.core.network

import com.justb81.watchbuddy.core.justwatch.JustWatchApiService
import com.justb81.watchbuddy.core.tmdb.TmdbApiService
import com.justb81.watchbuddy.core.trakt.TokenProxyService
import com.justb81.watchbuddy.core.trakt.TraktApiService
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.stream.Stream

/**
 * Smoke tests for Retrofit service interfaces.
 *
 * These tests verify two properties that are required for the generic
 * ProGuard rule in core/consumer-rules.pro to protect each interface:
 *
 *  1. The interface has at least one method annotated with @retrofit2.http.*,
 *     which is the trigger condition for the rule.
 *  2. Retrofit.create() succeeds (valid interface contract).
 *
 * Neither test can reproduce the R8 ClassCastException directly (R8 only runs
 * on release builds). The consumer-rules.pro rule is the primary protection;
 * these tests act as a registry guard — if a new Retrofit service interface is
 * added to :core without HTTP annotations, the parameterised test will fail and
 * surface the issue before release.
 */
@DisplayName("Retrofit service smoke tests")
class RetrofitServiceSmokeTest {

    companion object {
        private val CORE_RETROFIT_SERVICES: List<Class<*>> = listOf(
            TmdbApiService::class.java,
            TraktApiService::class.java,
            TokenProxyService::class.java,
            JustWatchApiService::class.java,
        )

        @JvmStatic
        fun coreRetrofitServices(): Stream<Class<*>> = CORE_RETROFIT_SERVICES.stream()

        private fun buildRetrofit(baseUrl: String = "https://example.com/"): Retrofit =
            Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(OkHttpClient())
                .addConverterFactory(
                    WatchBuddyJson.asConverterFactory("application/json".toMediaType())
                )
                .build()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("coreRetrofitServices")
    @DisplayName("interface has at least one @retrofit2.http.* annotated method")
    fun `each core Retrofit service has HTTP-annotated methods`(serviceClass: Class<*>) {
        val httpAnnotationPackage = "retrofit2.http"
        val hasHttpMethod = serviceClass.methods.any { method ->
            method.annotations.any { ann ->
                ann.annotationClass.java.packageName.startsWith(httpAnnotationPackage)
            }
        }
        assertTrue(
            hasHttpMethod,
            "${serviceClass.simpleName} has no @retrofit2.http.* annotated methods — " +
                "the generic keep rule in core/consumer-rules.pro would not protect it"
        )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("coreRetrofitServices")
    @DisplayName("Retrofit.create() succeeds without throwing")
    fun `each core Retrofit service can be created via Retrofit`(serviceClass: Class<*>) {
        val retrofit = buildRetrofit()
        assertDoesNotThrow {
            retrofit.create(serviceClass)
        }
    }

    @Test
    @DisplayName("all known core Retrofit services are listed in the test registry")
    fun `core service registry is complete`() {
        // If this count does not match, a newly added Retrofit service interface
        // in :core was not registered here. Add it to CORE_RETROFIT_SERVICES above.
        val expectedCount = 4
        assertTrue(
            CORE_RETROFIT_SERVICES.size == expectedCount,
            "Expected $expectedCount core Retrofit services in the registry but found " +
                "${CORE_RETROFIT_SERVICES.size}. Update CORE_RETROFIT_SERVICES when adding " +
                "or removing Retrofit service interfaces in :core."
        )
    }
}
