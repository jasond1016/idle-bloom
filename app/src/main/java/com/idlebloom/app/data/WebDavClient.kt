package com.idlebloom.app.data

interface WebDavClient {
    suspend fun discoverPhotos(config: SourceConfig): PhotoDiscoveryResult

    suspend fun listPhotos(config: SourceConfig): List<RemotePhoto> {
        val result = discoverPhotos(config)
        if (!result.isSuccess) {
            throw IllegalStateException(result.errorMessage ?: "WebDAV discovery failed")
        }
        return result.photos
    }
}
