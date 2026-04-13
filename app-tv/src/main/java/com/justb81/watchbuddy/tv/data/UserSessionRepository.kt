package com.justb81.watchbuddy.tv.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.userSessionDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "user_session"
)

@Singleton
class UserSessionRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val selectedUserIdsKey = stringSetPreferencesKey("selected_user_ids")

    val selectedUserIds: Flow<Set<String>> = context.userSessionDataStore.data.map { prefs ->
        prefs[selectedUserIdsKey] ?: emptySet()
    }

    suspend fun setSelectedUsers(ids: Set<String>) {
        context.userSessionDataStore.edit { prefs ->
            prefs[selectedUserIdsKey] = ids
        }
    }
}
