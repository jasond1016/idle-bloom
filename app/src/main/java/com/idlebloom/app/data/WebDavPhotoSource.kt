package com.idlebloom.app.data

class WebDavPhotoSource(
    private val client: WebDavClient = OkHttpWebDavClient()
) {
    suspend fun listPhotos(config: SourceConfig): List<RemotePhoto> {
        return client.listPhotos(config)
    }
}
