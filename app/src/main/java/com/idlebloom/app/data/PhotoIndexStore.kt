package com.idlebloom.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

private val Context.photoIndexDataStore: DataStore<Preferences> by preferencesDataStore(name = "idle_bloom_photo_index")

data class CachedPhotoIndex(
    val photos: List<RemotePhoto>,
    val savedAtEpochMillis: Long
)

class PhotoIndexStore(
    private val context: Context
) {

    suspend fun load(config: SourceConfig): CachedPhotoIndex? {
        val preferences = context.photoIndexDataStore.data.first()
        val storedKey = preferences[SOURCE_KEY] ?: return null
        val photosJson = preferences[PHOTO_INDEX_JSON] ?: return null
        val savedAt = preferences[SAVED_AT_EPOCH_MILLIS] ?: return null
        if (storedKey != sourceKey(config)) return null

        return runCatching {
            CachedPhotoIndex(
                photos = decodePhotos(photosJson),
                savedAtEpochMillis = savedAt
            )
        }.getOrNull()
    }

    suspend fun save(config: SourceConfig, photos: List<RemotePhoto>) {
        context.photoIndexDataStore.edit { preferences ->
            preferences[SOURCE_KEY] = sourceKey(config)
            preferences[PHOTO_INDEX_JSON] = encodePhotos(photos)
            preferences[SAVED_AT_EPOCH_MILLIS] = System.currentTimeMillis()
        }
    }

    private fun sourceKey(config: SourceConfig): String {
        return listOf(
            config.normalizedBaseUrl(),
            config.username.trim(),
            config.normalizedRemoteDirectory()
        ).joinToString("|")
    }

    private fun encodePhotos(photos: List<RemotePhoto>): String {
        val array = JSONArray()
        photos.forEach { photo ->
            array.put(
                JSONObject()
                    .put("id", photo.id)
                    .put("name", photo.name)
                    .put("url", photo.url)
                    .put("contentType", photo.contentType)
                    .put("lastModified", photo.lastModified)
            )
        }
        return array.toString()
    }

    private fun decodePhotos(value: String): List<RemotePhoto> {
        val array = JSONArray(value)
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    RemotePhoto(
                        id = item.getString("id"),
                        name = item.getString("name"),
                        url = item.getString("url"),
                        contentType = item.optString("contentType")
                            .takeUnless { it.isBlank() || it == "null" },
                        lastModified = item.optString("lastModified")
                            .takeUnless { it.isBlank() || it == "null" }
                    )
                )
            }
        }
    }

    companion object {
        private val SOURCE_KEY = stringPreferencesKey("source_key")
        private val PHOTO_INDEX_JSON = stringPreferencesKey("photo_index_json")
        private val SAVED_AT_EPOCH_MILLIS = longPreferencesKey("saved_at_epoch_millis")
    }
}
