package com.justb81.watchbuddy.tv.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Database(entities = [JustWatchDeepLink::class], version = 1, exportSchema = true)
abstract class JustWatchDeepLinkDatabase : RoomDatabase() {
    abstract fun dao(): JustWatchDeepLinkDao
}

@Module
@InstallIn(SingletonComponent::class)
object JustWatchDatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): JustWatchDeepLinkDatabase =
        Room.databaseBuilder(context, JustWatchDeepLinkDatabase::class.java, "justwatch_deep_links.db")
            .build()

    @Provides
    @Singleton
    fun provideDao(db: JustWatchDeepLinkDatabase): JustWatchDeepLinkDao = db.dao()
}
