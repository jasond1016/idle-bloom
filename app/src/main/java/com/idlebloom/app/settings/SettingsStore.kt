package com.idlebloom.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.idlebloom.app.data.SourceConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "idle_bloom_settings")

class SettingsStore(
    private val context: Context
) {

    suspend fun load(): SourceConfig {
        return context.dataStore.data.map { preferences ->
            SourceConfig(
                baseUrl = preferences[BASE_URL] ?: "",
                username = preferences[USERNAME] ?: "",
                password = preferences[PASSWORD] ?: "",
                remoteDirectory = preferences[REMOTE_DIRECTORY] ?: "/",
                slideIntervalSeconds = preferences[SLIDE_INTERVAL] ?: 20,
                shuffleEnabled = preferences[SHUFFLE_ENABLED] ?: true
            )
        }.first()
    }

    suspend fun save(config: SourceConfig) {
        context.dataStore.edit { preferences ->
            preferences[BASE_URL] = config.baseUrl.trim()
            preferences[USERNAME] = config.username.trim()
            preferences[PASSWORD] = config.password
            preferences[REMOTE_DIRECTORY] = config.normalizedRemoteDirectory()
            preferences[SLIDE_INTERVAL] = config.slideIntervalSeconds.coerceAtLeast(5)
            preferences[SHUFFLE_ENABLED] = config.shuffleEnabled
        }
    }

    companion object {
        private val BASE_URL = stringPreferencesKey("base_url")
        private val USERNAME = stringPreferencesKey("username")
        private val PASSWORD = stringPreferencesKey("password")
        private val REMOTE_DIRECTORY = stringPreferencesKey("remote_directory")
        private val SLIDE_INTERVAL = intPreferencesKey("slide_interval")
        private val SHUFFLE_ENABLED = booleanPreferencesKey("shuffle_enabled")
    }
}
