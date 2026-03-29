package com.idlebloom.app.data

import android.content.Context

class PhotoRepository(
    context: Context,
    private val photoSource: WebDavPhotoSource = WebDavPhotoSource(),
    private val photoIndexStore: PhotoIndexStore = PhotoIndexStore(context.applicationContext)
) {

    suspend fun refreshPhotos(config: SourceConfig): PhotoDiscoveryResult {
        val result = photoSource.discoverPhotos(config)
        if (result.isSuccess) {
            photoIndexStore.save(config, result.photos)
        }
        return result
    }

    suspend fun loadCachedPhotos(config: SourceConfig): CachedPhotoIndex? {
        val cached = photoIndexStore.load(config) ?: return null
        if (cached.photos.isEmpty()) return null
        return cached
    }
}
