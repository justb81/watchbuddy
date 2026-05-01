package com.justb81.watchbuddy.tv.di

import javax.inject.Qualifier

/** Qualifies the application-level [kotlinx.coroutines.CoroutineScope] provided by [AppModule]. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
