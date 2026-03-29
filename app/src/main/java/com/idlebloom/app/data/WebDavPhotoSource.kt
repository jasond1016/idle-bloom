package com.idlebloom.app.data

class WebDavPhotoSource(
    private val client: WebDavClient = OkHttpWebDavClient()
) {
    suspend fun discoverPhotos(config: SourceConfig): PhotoDiscoveryResult {
        return client.discoverPhotos(config)
    }

    suspend fun listPhotos(config: SourceConfig): List<RemotePhoto> {
        return client.listPhotos(config)
    }
}
