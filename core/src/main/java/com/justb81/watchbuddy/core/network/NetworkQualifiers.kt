package com.justb81.watchbuddy.core.network

import javax.inject.Qualifier

/** Qualifies the Trakt [retrofit2.Retrofit] instance and its [okhttp3.OkHttpClient]. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TraktClient

/** Qualifies the TMDB [retrofit2.Retrofit] instance. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TmdbClient

/** Qualifies the JustWatch [okhttp3.OkHttpClient] and [retrofit2.Retrofit] instances. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class JustWatchClient

/** Qualifies the SIMKL [okhttp3.OkHttpClient] and [retrofit2.Retrofit] instances. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SimklClient

/** Qualifies the SIMKL client-ID provider lambda injected into [com.justb81.watchbuddy.core.tracking.SimklTrackingProvider]. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SimklClientIdProvider
