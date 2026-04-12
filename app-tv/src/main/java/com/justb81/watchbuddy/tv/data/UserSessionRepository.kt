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

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "tv_session")

@Singleton
class UserSessionRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val KEY_SELECTED_USER_IDS = stringSetPreferencesKey("selected_user_ids")

    val selectedUserIds: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[KEY_SELECTED_USER_IDS] ?: emptySet()
    }

    suspend fun setSelectedUsers(ids: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SELECTED_USER_IDS] = ids
        }
    }
}
